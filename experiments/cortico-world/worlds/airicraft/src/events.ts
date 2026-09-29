/**
 * Maps airicraft semantic events onto Cortico events: which ones reach the persona and how they wake it.
 * The producer chooses the trigger mode (Cortico's rule); this table mirrors the intent of airicraft's own
 * attention rules (reflex start preempts, direct chat and outcomes flush, percepts debounce).
 */
import type { PushOptions } from 'cortico/core/types.ts';
import type { BridgeEvent } from './bridge.ts';

export type Delivery = NonNullable<PushOptions['trigger']> | 'archive';

interface Rule {
  match: RegExp;
  delivery: Delivery;
}

/** First match wins. Anything unmatched is delivered without waking (`piggyback`). */
const RULES: readonly Rule[] = [
  { match: /^reflex\.started$/, delivery: 'preempt' },
  { match: /^(player\.died|player\.respawned|reflex\.resolved|reflex\.hold_released)$/, delivery: 'flush' },
  { match: /^(action_graph\.goal_terminal|action_graph\.goal_suspended|task\.(blocked|failed|completed))$/, delivery: 'flush' },
  { match: /^(smelting\.output_ready|session\.(world_loaded|world_unloaded|connection_lost))$/, delivery: 'flush' },
  { match: /^social\.player_addressed_agent$/, delivery: 'flush' },
  { match: /^(social\.player_spoke|social\.system_message|social\.item_offered|social\.player_(joined|left)_)/, delivery: 'debounce' },
  { match: /^(perception\.|combat\.damage_taken|pickup\.|crafting\.|follow\.)/, delivery: 'debounce' },
  // Bookkeeping that only matters as history.
  { match: /^(action_graph\.|mission\.|planner\.|rules\.|policy\.event_intervened$|social\.local_controller_spoke$)/, delivery: 'archive' },
];

const TERMINAL_WORK = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);

export function deliveryFor(type: string, payload?: Record<string, unknown>): Delivery {
  // Work progress is frequent bookkeeping; only a top-level job reaching a final state is worth a wake.
  if (type === 'work.changed') {
    return TERMINAL_WORK.has(String(payload?.state)) && !payload?.parentWorkId ? 'flush' : 'piggyback';
  }
  // The mod announces its debug dashboard (a URL with a viewer token) as a system message; that is not for the persona.
  if (type === 'social.system_message' && /dashboard/i.test(JSON.stringify(payload ?? {}))) return 'archive';
  return RULES.find((rule) => rule.match.test(type))?.delivery ?? 'piggyback';
}

/** Credentials never go into persona context, whatever event carried them. */
export function redact(text: string): string {
  return text.replace(/(token=)[^\s&"'#]+/gi, '$1<redacted>');
}

/** Fields shown for event types whose full payload is mostly internal detail. */
const SHOWN_FIELDS: Record<string, readonly string[]> = {
  'work.changed': ['label', 'state', 'phase', 'workId', 'parentWorkId'],
};

const MAX_VALUE_CHARS = 120;
const MAX_TEXT_CHARS = 600;

function render(value: unknown): string {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text.length > MAX_VALUE_CHARS ? `${text.slice(0, MAX_VALUE_CHARS)}…` : text;
}

/**
 * Event text states only what the mod reported: the type and its payload fields. Nothing is inferred.
 * Richer per-type phrasing can replace this without touching delivery.
 */
export function eventText(event: BridgeEvent): string {
  const shown = SHOWN_FIELDS[event.type];
  const fields = Object.entries(event.payload ?? {})
    .filter(([key]) => !shown || shown.includes(key))
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${key}=${render(value)}`);
  const text = redact(fields.length ? `${event.type} ${fields.join(' ')}` : event.type);
  return text.length > MAX_TEXT_CHARS ? `${text.slice(0, MAX_TEXT_CHARS)}…` : text;
}
