/**
 * Wire frames between the AIRI stage and this World. The stage side is the patch in
 * `patches/airi` (adapted from moeru-ai/airi#2634); frames it sends that this World does not use are ignored.
 */

export interface SessionRef {
  id: string;
  label: string;
}

/** Stage → World. */
export type ClientFrame =
  | { type: 'hello'; name?: string }
  | { type: 'msg'; text: string; images?: string[]; session?: SessionRef }
  | { type: 'event'; kind: string; text: string }
  | { type: 'sessions'; sessions: SessionRef[] }
  /** Playback of one `speak` finished or was cut off. The unpatched stage does not send this yet. */
  | { type: 'speech_end'; id: string; interrupted?: boolean }
  | { type: 'provider'; config: unknown }
  | { type: 'memory_query' };

/** World → stage. */
export type ServerFrame =
  | { type: 'speak'; id: string; text: string; sessionId?: string }
  | { type: 'act'; emotion?: string; motion?: string; delay?: number; sessionId?: string }
  | { type: 'delta'; text: string }
  | { type: 'turn_end' }
  | { type: 'sys'; text: string }
  | { type: 'memory'; dir: string; sections: []; totalFiles: 0 };

export function parseClientFrame(raw: string): ClientFrame | null {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const frame = value as Record<string, unknown>;
  switch (frame.type) {
    case 'hello':
    case 'sessions':
    case 'provider':
    case 'memory_query':
      return frame as unknown as ClientFrame;
    case 'msg':
      return typeof frame.text === 'string' && frame.text.length > 0 ? (frame as unknown as ClientFrame) : null;
    case 'event':
      return typeof frame.kind === 'string' && typeof frame.text === 'string' && frame.text.length > 0 ? (frame as unknown as ClientFrame) : null;
    case 'speech_end':
      return typeof frame.id === 'string' ? (frame as unknown as ClientFrame) : null;
    default:
      return null;
  }
}
