/**
 * The AIRI stage as a Cortico World: audience input becomes events, the persona's tools become speech and stage
 * directions on the socket. Statements here are facts the World can confirm: what the audience typed, what was
 * sent to the stage, and what the stage reported back.
 */
import { randomUUID } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import { fileURLToPath } from 'node:url';
import type { BlobInput, OutputTap, ToolDef, World, WorldConsoleDecl, WorldHost } from 'cortico/core/types.ts';
import type { StreamEvent } from 'cortico/protocol/open-responses/index.ts';
import { nowIso } from 'cortico/core/util.ts';
import { WebSocketServer, type WebSocket } from 'ws';
import type { AiriStageConfigSection } from './config.ts';
import { parseClientFrame, type ClientFrame, type ServerFrame, type SessionRef } from './frames.ts';

const ENV_PROMPT_FILE = fileURLToPath(new URL('./ENV_PROMPT.md', import.meta.url));

export interface AiriStageWorldOptions {
  /** Live reference to `worlds.airi`. */
  cfg: AiriStageConfigSection;
  timezone: string;
  botName: string;
}

interface Client {
  name: string;
  socket: WebSocket;
}

/** Decodes `data:<mime>;base64,...` URLs into event attachments. */
function dataUrlsToBlobs(urls: string[] | undefined): BlobInput[] | undefined {
  const blobs = (urls ?? []).flatMap((url, index) => {
    const match = /^data:([^;,]+);base64,(.+)$/s.exec(url);
    if (!match) return [];
    return [{ bytes: new Uint8Array(Buffer.from(match[2], 'base64')), mime: match[1], name: `image-${index}`, fallbackText: `[image ${match[1]}]` }];
  });
  return blobs.length ? blobs : undefined;
}

export class AiriStageWorld implements World {
  readonly id = 'airi';
  private host: WorldHost | null = null;
  private server: WebSocketServer | null = null;
  private readonly clients = new Set<Client>();
  private readonly sessions = new Map<string, string>(); // id -> label
  private currentSessionId: string | undefined;
  /** Text of speech sent to the stage and not yet reported finished. */
  private readonly speaking = new Map<string, string>();

  constructor(private readonly opts: AiriStageWorldOptions) {}

  /** The port actually bound (differs from the configured one when that is 0). */
  get port(): number {
    return (this.server?.address() as AddressInfo | null)?.port ?? this.opts.cfg.port;
  }

  envPromptVars(): Record<string, string> {
    const conversations = [...this.sessions.entries()].map(([id, label]) => `- ${label} (id: ${id})`);
    return {
      'airi.state': this.clients.size > 0 ? `${this.clients.size} stage client(s) connected` : 'no stage connected',
      'airi.conversations': conversations.length ? conversations.join('\n') : '(none yet)',
    };
  }

  console(): WorldConsoleDecl {
    return {
      promptDocs: [
        {
          key: 'worlds.airi.envPrompt',
          title: 'AIRI stage environment prompt',
          description: 'The stage section of the system prefix.',
          path: ENV_PROMPT_FILE,
          role: 'envPrompt',
          vars: [
            { name: 'airi.state', description: 'Connected stage clients.' },
            { name: 'airi.conversations', description: 'Chat sessions the stage reported.' },
          ],
        },
      ],
    };
  }

  /** Draft model output goes to the stage as `delta`. A draft is not external output, so a new message may still preempt it. */
  outputTap(): OutputTap {
    return {
      onEvent: (event: StreamEvent) => {
        if (event.type === 'response.output_text.delta' && 'delta' in event && typeof event.delta === 'string') {
          this.broadcast({ type: 'delta', text: event.delta });
        }
      },
      externalizes: () => false,
      onRoundEnd: () => {},
      onAbort: () => {},
    };
  }

  tools(): ToolDef[] {
    const target = { type: 'string', description: 'Conversation label or id to answer in. Omit for the most recent one.' };
    return [
      {
        name: 'airi_speak',
        description: 'Say text out loud on stage: it is voiced and shown as your reply. One or two natural sentences per call; call again to continue.',
        tags: ['speak'],
        parameters: { type: 'object', properties: { text: { type: 'string', description: 'What to say.' }, to: target }, required: ['text'] },
        handler: async (args) => {
          const text = typeof args.text === 'string' ? args.text.trim() : '';
          if (!text) return { text: '[speak failed] text must not be empty', failed: true as const };
          const resolved = this.resolveSession(typeof args.to === 'string' ? args.to : undefined);
          if ('error' in resolved) return { text: `[speak failed] ${resolved.error}`, failed: true as const };
          if (this.clients.size === 0) return { text: '[speak failed] no stage is connected', failed: true as const };
          const id = randomUUID();
          this.speaking.set(id, text);
          this.broadcast({ type: 'speak', id, text, ...(resolved.id ? { sessionId: resolved.id } : {}) });
          return `[sent to stage, id ${id}] Playback is reported later as a speech event.`;
        },
      },
      {
        name: 'airi_act',
        description: 'Perform a stage direction: set an emotion, play a named motion, or pause briefly.',
        tags: ['act'],
        parameters: {
          type: 'object',
          properties: {
            emotion: { type: 'string', description: 'One of: happy, sad, angry, think, surprised, awkward, question, curious, neutral.' },
            motion: { type: 'string', description: 'Named motion group to play.' },
            delay: { type: 'number', description: 'Seconds to pause before continuing.' },
            to: target,
          },
        },
        handler: async (args) => {
          const frame: Extract<ServerFrame, { type: 'act' }> = { type: 'act' };
          if (typeof args.emotion === 'string' && args.emotion) frame.emotion = args.emotion;
          if (typeof args.motion === 'string' && args.motion) frame.motion = args.motion;
          if (typeof args.delay === 'number' && Number.isFinite(args.delay)) frame.delay = args.delay;
          if (!frame.emotion && !frame.motion && frame.delay === undefined) return { text: '[act failed] provide emotion, motion or delay', failed: true as const };
          const resolved = this.resolveSession(typeof args.to === 'string' ? args.to : undefined);
          if ('error' in resolved) return { text: `[act failed] ${resolved.error}`, failed: true as const };
          if (this.clients.size === 0) return { text: '[act failed] no stage is connected', failed: true as const };
          if (resolved.id) frame.sessionId = resolved.id;
          this.broadcast(frame);
          return '[sent to stage]';
        },
      },
    ];
  }

  async start(host: WorldHost): Promise<void> {
    this.host = host;
    const server = new WebSocketServer({ host: this.opts.cfg.host, port: this.opts.cfg.port });
    await new Promise<void>((resolve, reject) => {
      server.once('listening', () => resolve());
      server.once('error', reject);
    });
    server.on('connection', (socket) => this.onConnection(socket));
    this.server = server;
    host.log.info('airi stage socket listening', { host: this.opts.cfg.host, port: this.port });
  }

  async stop(): Promise<void> {
    this.host = null;
    for (const client of this.clients) client.socket.close();
    this.clients.clear();
    const server = this.server;
    this.server = null;
    if (server) await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  onTurnEnded(): void {
    this.broadcast({ type: 'turn_end' });
  }

  onHandoffEnded(): void {
    this.broadcast({ type: 'turn_end' });
  }

  private onConnection(socket: WebSocket): void {
    const client: Client = { name: 'user', socket };
    this.clients.add(client);
    socket.on('message', (raw) => {
      const frame = parseClientFrame(String(raw));
      if (frame) void this.onFrame(client, frame);
      else socket.send(JSON.stringify({ type: 'sys', text: 'malformed frame' } satisfies ServerFrame));
    });
    socket.on('close', () => this.clients.delete(client));
    socket.on('error', () => this.clients.delete(client));
    socket.send(JSON.stringify({ type: 'sys', text: 'connected to the Cortico airi world' } satisfies ServerFrame));
  }

  private async onFrame(client: Client, frame: ClientFrame): Promise<void> {
    switch (frame.type) {
      case 'hello':
        if (frame.name?.trim()) client.name = frame.name.trim().slice(0, 32);
        return;
      case 'sessions':
        for (const session of frame.sessions) this.sessions.set(session.id, session.label);
        return;
      case 'provider':
        // Model access is configured in the Cortico deployment, not pushed from the stage.
        return;
      case 'memory_query':
        client.socket.send(JSON.stringify({ type: 'memory', dir: '', sections: [], totalFiles: 0 } satisfies ServerFrame));
        return;
      case 'speech_end':
        await this.onSpeechEnd(frame.id, frame.interrupted === true);
        return;
      case 'msg':
        await this.onMessage(client, frame.text, frame.session, frame.images);
        return;
      case 'event':
        await this.push(`airi.${frame.kind}`, frame.text, 'debounce', { senderKey: client.name });
        return;
    }
  }

  private async onMessage(client: Client, text: string, session: SessionRef | undefined, images: string[] | undefined): Promise<void> {
    if (session) {
      this.sessions.set(session.id, session.label);
      this.currentSessionId = session.id;
    }
    const label = session?.label;
    await this.push('airi.user_message', `${label ? `[${label}] ` : ''}${client.name}: ${text}`, 'flush', {
      senderKey: client.name,
      blobs: dataUrlsToBlobs(images),
      meta: session ? { session } : undefined,
    });
  }

  /** Playback result of one utterance; rides along with the next wake instead of causing one. */
  private async onSpeechEnd(id: string, interrupted: boolean): Promise<void> {
    const text = this.speaking.get(id);
    if (text === undefined) return;
    this.speaking.delete(id);
    const shown = text.length > 80 ? `${text.slice(0, 80)}…` : text;
    await this.push(
      'airi.speech_ended',
      interrupted ? `speech was cut off: "${shown}"` : `finished speaking: "${shown}"`,
      'piggyback',
      { origin: 'internal', tags: ['speak'], meta: { id, interrupted } },
    );
  }

  private async push(
    type: string,
    text: string,
    trigger: 'flush' | 'debounce' | 'piggyback',
    extra: { senderKey?: string; blobs?: BlobInput[]; meta?: Record<string, unknown>; origin?: 'external' | 'internal'; tags?: readonly 'speak'[] } = {},
  ): Promise<void> {
    if (!this.host) return;
    await this.host.pushEvent(
      {
        type,
        ts: nowIso(this.opts.timezone),
        source: this.id,
        origin: extra.origin ?? 'external',
        senderKey: extra.senderKey ?? this.id,
        text,
        ...(extra.tags ? { tags: extra.tags } : {}),
        ...(extra.blobs ? { blobs: extra.blobs } : {}),
        ...(extra.meta ? { meta: extra.meta } : {}),
      },
      { trigger },
    );
  }

  /** `to` names a conversation by id or label; otherwise the one that spoke last. */
  private resolveSession(to: string | undefined): { id?: string } | { error: string } {
    const wanted = to?.trim();
    if (wanted) {
      for (const [id, label] of this.sessions) if (id === wanted || label === wanted) return { id };
      return { error: `unknown conversation "${wanted}"; use a label or id from the conversation list` };
    }
    return this.currentSessionId ? { id: this.currentSessionId } : {};
  }

  private broadcast(frame: ServerFrame): void {
    const data = JSON.stringify(frame);
    for (const client of this.clients) client.socket.send(data);
  }
}
