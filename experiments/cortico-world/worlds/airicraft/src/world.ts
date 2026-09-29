/**
 * airicraft as a Cortico World. Events come from polling the mod's event feed, tools from the mod's
 * driver tool list. Statements here are facts the World can confirm: what the mod reported, and whether
 * the bridge answered.
 */
import { fileURLToPath } from 'node:url';
import type { ToolDef, World, WorldConsoleDecl, WorldHost } from 'cortico/core/types.ts';
import { nowIso } from 'cortico/core/util.ts';
import type { AiricraftConfigSection } from './config.ts';
import { BridgeClient, BridgeUnreachable, type BridgeEvent } from './bridge.ts';
import { deliveryFor, eventText } from './events.ts';
import { toToolDefs } from './tools.ts';

const ENV_PROMPT_FILE = fileURLToPath(new URL('./ENV_PROMPT.md', import.meta.url));

export interface AiricraftWorldOptions {
  /** Live reference to `worlds.airicraft`. */
  cfg: AiricraftConfigSection;
  timezone: string;
  /** Injected in tests. */
  bridge?: BridgeClient;
}

type Link = 'unknown' | 'up' | 'down';

export class AiricraftWorld implements World {
  readonly id = 'airicraft';
  private readonly bridge: BridgeClient;
  private host: WorldHost | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stopped = true;
  private cursor: number | null = null;
  private link: Link = 'unknown';
  private loaded: ToolDef[] = [];

  constructor(private readonly opts: AiricraftWorldOptions) {
    this.bridge = opts.bridge ?? new BridgeClient({ stateFile: opts.cfg.bridgeStateFile });
  }

  envPromptVars(): Record<string, string> {
    return {
      'airicraft.link': this.link === 'up' ? 'connected' : this.link === 'down' ? 'unreachable' : 'not yet contacted',
      'airicraft.prefix': this.opts.cfg.toolPrefix,
    };
  }

  /** Tools are read from the mod at start and after a lost bridge comes back. */
  tools(): ToolDef[] {
    return this.loaded;
  }

  console(): WorldConsoleDecl {
    return {
      promptDocs: [
        {
          key: 'worlds.airicraft.envPrompt',
          title: 'airicraft environment prompt',
          description: 'The Minecraft section of the system prefix.',
          path: ENV_PROMPT_FILE,
          role: 'envPrompt',
          vars: [
            { name: 'airicraft.link', description: 'Whether the mod bridge answered on the last poll.' },
            { name: 'airicraft.prefix', description: 'Prefix of the tool names.' },
          ],
        },
      ],
    };
  }

  async start(host: WorldHost): Promise<void> {
    this.host = host;
    this.stopped = false;
    await this.refreshTools();
    await this.poll();
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.host = null;
  }

  private async refreshTools(): Promise<void> {
    try {
      const descriptors = await this.bridge.listTools();
      this.loaded = toToolDefs(descriptors, {
        bridge: this.bridge,
        prefix: this.opts.cfg.toolPrefix,
        exclude: this.opts.cfg.excludeTools,
        timeoutMs: this.opts.cfg.toolTimeoutMs,
      });
      this.host?.log.info('airicraft tools loaded', { count: this.loaded.length });
    } catch (error) {
      this.host?.log.warn('airicraft tools unavailable at start', { err: String(error) });
    }
  }

  private schedule(): void {
    if (this.stopped) return;
    this.timer = setTimeout(() => void this.poll(), this.opts.cfg.pollIntervalMs);
  }

  private async poll(): Promise<void> {
    if (this.stopped || !this.host) return;
    try {
      const feed = await this.bridge.recentEvents(this.cursor);
      await this.setLink('up');
      if (this.cursor === null) {
        // Start at the present: history from before this World mounted is not delivered.
        this.cursor = feed.latestSeqNo;
      } else {
        if (feed.truncated) {
          await this.push('airicraft.events_missed', `airicraft event buffer overflowed: events ${this.cursor + 1} to ${feed.oldestSeqNo - 1} were not delivered`, 'flush', 'internal');
        }
        for (const event of feed.events) await this.pushEvent(event);
        this.cursor = Math.max(this.cursor, feed.latestSeqNo);
      }
    } catch (error) {
      if (error instanceof BridgeUnreachable) await this.setLink('down', error.message);
      else this.host?.log.warn('airicraft poll failed', { err: String(error) });
    }
    this.schedule();
  }

  /** One connection notice per transition, so a dead bridge does not flood the persona. */
  private async setLink(next: Link, detail?: string): Promise<void> {
    if (next === this.link) return;
    const previous = this.link;
    this.link = next;
    if (next === 'up' && previous === 'down') {
      this.cursor = null;
      await this.refreshTools();
      await this.push('airicraft.bridge_connected', 'airicraft bridge is reachable again; events before this point were not delivered', 'flush', 'internal');
    } else if (next === 'down' && previous === 'up') {
      await this.push('airicraft.bridge_lost', `airicraft bridge stopped answering${detail ? `: ${detail}` : ''}`, 'flush', 'internal');
    }
  }

  private async pushEvent(event: BridgeEvent): Promise<void> {
    await this.push(`airicraft.${event.type}`, eventText(event), deliveryFor(event.type, event.payload), 'external', {
      seqNo: event.seqNo,
      tick: event.tick,
    });
  }

  private async push(
    type: string,
    text: string,
    delivery: ReturnType<typeof deliveryFor>,
    origin: 'external' | 'internal',
    meta?: Record<string, unknown>,
  ): Promise<void> {
    const host = this.host;
    if (!host) return;
    await host.pushEvent(
      { type, ts: nowIso(this.opts.timezone), source: this.id, origin, senderKey: this.id, text, ...(meta ? { meta } : {}) },
      delivery === 'archive' ? { deliver: false } : { trigger: delivery },
    );
  }
}
