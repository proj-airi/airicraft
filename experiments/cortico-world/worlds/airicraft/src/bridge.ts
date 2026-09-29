/** Client for the airicraft localhost bridge (discovery file + bearer token). */
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

export interface BridgeState {
  port: number;
  token: string;
}

export class BridgeUnreachable extends Error {}

/** The bridge answered with an error status. */
export class BridgeError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

export interface ToolDescriptor {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface ToolCallResult {
  text: string;
  image?: { bytes: Uint8Array; mime: string };
}

export interface BridgeEvent {
  seqNo: number;
  tick: number;
  timestampMs: number;
  type: string;
  payload?: Record<string, unknown>;
  source?: string;
}

export interface EventFeed {
  oldestSeqNo: number;
  latestSeqNo: number;
  truncated: boolean;
  events: BridgeEvent[];
}

export interface BridgeClientOptions {
  /** Explicit state; when absent it is read from the discovery file on demand. */
  state?: BridgeState;
  stateFile?: string;
  fetchImpl?: typeof fetch;
}

export function defaultStateFile(configured: string): string {
  return configured || process.env.AIRICRAFT_BRIDGE_STATE_FILE || join(homedir(), '.airicraft', 'bridge-state.json');
}

export function readBridgeState(file: string): BridgeState {
  let raw: string;
  try {
    raw = readFileSync(file, 'utf8');
  } catch {
    throw new BridgeUnreachable(`bridge state file not found: ${file}`);
  }
  const parsed = JSON.parse(raw) as Partial<BridgeState>;
  if (typeof parsed.port !== 'number' || typeof parsed.token !== 'string') {
    throw new BridgeUnreachable(`bridge state file is malformed: ${file}`);
  }
  return { port: parsed.port, token: parsed.token };
}

export class BridgeClient {
  private cached: BridgeState | undefined;
  private readonly doFetch: typeof fetch;

  constructor(private readonly opts: BridgeClientOptions = {}) {
    this.cached = opts.state;
    this.doFetch = opts.fetchImpl ?? fetch;
  }

  private state(): BridgeState {
    if (!this.cached) this.cached = readBridgeState(defaultStateFile(this.opts.stateFile ?? ''));
    return this.cached;
  }

  /** The mod rewrites the state file (new port and token) on every launch, so drop the cache on failure. */
  private async request(method: 'GET' | 'POST', path: string, body?: unknown, signal?: AbortSignal): Promise<unknown> {
    const state = this.state();
    let response: Response;
    try {
      response = await this.doFetch(`http://127.0.0.1:${state.port}${path}`, {
        method,
        headers: { Authorization: `Bearer ${state.token}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal,
      });
    } catch (error) {
      if (signal?.aborted) throw error;
      if (!this.opts.state) this.cached = undefined;
      throw new BridgeUnreachable(error instanceof Error ? error.message : String(error));
    }
    const text = await response.text();
    let json: Record<string, unknown> = {};
    try {
      json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
    } catch {
      // Non-JSON error bodies fall through to the status check.
    }
    if (response.status === 401 && !this.opts.state) this.cached = undefined;
    if (!response.ok) {
      throw new BridgeError(
        response.status,
        String(json.error ?? json.errorCode ?? 'http_error'),
        String(json.message ?? (text || response.statusText)),
      );
    }
    return json;
  }

  async listTools(): Promise<ToolDescriptor[]> {
    const json = (await this.request('GET', '/v1/agent/tools')) as { tools?: unknown[] };
    return (json.tools ?? []).flatMap((entry) => {
      const item = entry as { function?: Partial<ToolDescriptor> } & Partial<ToolDescriptor>;
      const fn = item.function ?? item;
      if (typeof fn.name !== 'string') return [];
      return [{ name: fn.name, description: fn.description ?? '', parameters: fn.parameters ?? { type: 'object', properties: {} } }];
    });
  }

  async callTool(name: string, args: Record<string, unknown>, opts: { timeoutMs: number; signal?: AbortSignal }): Promise<ToolCallResult> {
    const json = (await this.request('POST', '/v1/agent/tools', { name, arguments: args, timeoutMs: opts.timeoutMs }, opts.signal)) as {
      result?: string;
      imageAttached?: boolean;
      imageMimeType?: string;
      imageBase64?: string;
    };
    const result: ToolCallResult = { text: json.result ?? '' };
    if (json.imageAttached && json.imageBase64) {
      result.image = { bytes: new Uint8Array(Buffer.from(json.imageBase64, 'base64')), mime: json.imageMimeType ?? 'image/png' };
    }
    return result;
  }

  /** `since` null asks for everything the buffer still holds; otherwise events with `seqNo > since`. */
  async recentEvents(since: number | null, signal?: AbortSignal): Promise<EventFeed> {
    const query = since === null ? '' : `?since=${since}`;
    const json = (await this.request('GET', `/v1/agent/events/recent${query}`, undefined, signal)) as Partial<EventFeed>;
    return {
      oldestSeqNo: json.oldestSeqNo ?? 0,
      latestSeqNo: json.latestSeqNo ?? 0,
      truncated: json.truncated === true,
      events: json.events ?? [],
    };
  }
}
