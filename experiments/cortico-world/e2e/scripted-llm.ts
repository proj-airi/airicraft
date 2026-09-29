/**
 * A deterministic stand-in for a model, so the plumbing (events -> wakes -> tool calls -> receipts) can be run
 * end to end without an LLM endpoint. Adapted from the fake LLM in moeru-ai/airi#2634 (MIT).
 *
 * It only acts when the last thing in its input is a delivered event frame; after tool receipts it ends the turn.
 * What it does with a frame is decided by the `script` function, which sees the frame text.
 */
import type { GenerateOptions, Generation, ResponseClient } from 'cortico/core/generation.ts';
import type { ItemOrigin } from 'cortico/protocol/open-responses/context.ts';
import type { Request } from 'cortico/protocol/open-responses/index.ts';
import { unknownMeters } from 'cortico/core/generation.ts';
import { createResponse } from 'cortico/protocol/open-responses/index.ts';

const ORIGIN: ItemOrigin = { instance: 'scripted', module: 'scripted', model: 'scripted', compatibilityDomain: 'scripted' };

export interface PlannedCall {
  name: string;
  args: Record<string, unknown>;
}

export interface TurnRecord {
  round: number;
  at: string;
  frame: string;
  calls: PlannedCall[];
}

type Item = { type?: string; name?: string; output?: unknown; role?: string; content?: unknown };

function messageText(item: Item): string {
  if (typeof item.content === 'string') return item.content;
  if (!Array.isArray(item.content)) return '';
  return item.content.map((part) => (part as { text?: string }).text ?? '').join('');
}

/**
 * What woke the model: the delivered external event frame (newest tool exchange), or, for internal events such as
 * the persona heartbeat, a user message that is the last item of the input.
 */
function deliveredFrame(request: Request): string | null {
  const input = request.input;
  if (!Array.isArray(input)) return null;
  for (let i = input.length - 1; i >= 0; i--) {
    const item = input[i] as Item;
    if (item?.type === 'message' && item.role === 'user') return messageText(item);
    if (item?.type === 'function_call') return item.name === 'external_event_frame' ? '' : null;
    if (item?.type === 'function_call_output') {
      const call = input.slice(0, i).reverse().find((it) => (it as Item)?.type === 'function_call') as Item | undefined;
      return call?.name === 'external_event_frame' && typeof item.output === 'string' ? item.output : null;
    }
  }
  return null;
}

export function createScriptedLlm(
  script: (frame: string) => PlannedCall[],
  onTurn: (turn: TurnRecord) => void,
): ResponseClient {
  let round = 0;
  return {
    respond: async (request: Request, options?: GenerateOptions): Promise<Generation> => {
      const res = createResponse(`resp_scripted_${++round}`, request);
      res.status = 'completed';
      res.completed_at = Math.floor(Date.now() / 1000);
      const frame = options?.role === 'main' ? deliveredFrame(request) : null;
      if (options?.role === 'main') {
        const calls = frame ? script(frame) : [];
        if (frame) onTurn({ round, at: new Date().toISOString(), frame, calls });
        const planned = calls.length ? calls : [{ name: 'end_turn', args: {} }];
        res.output = planned.map((call, index) => ({
          type: 'function_call' as const,
          id: `fc_${round}_${index}`,
          call_id: `call_${round}_${index}`,
          name: call.name,
          arguments: JSON.stringify(call.args),
          status: 'completed' as const,
        }));
      } else {
        res.output = [{ type: 'message', id: `msg_${round}`, status: 'completed', role: 'assistant', content: [{ type: 'output_text', text: '(scripted)', annotations: [] }] }];
      }
      res.usage = { input_tokens: 1, output_tokens: 1, total_tokens: 2, input_tokens_details: { cached_tokens: 0 }, output_tokens_details: { reasoning_tokens: 0 } };
      return {
        response: res,
        origin: ORIGIN,
        attempts: [{
          id: `att_${round}`, generationId: res.id, ordinal: 1, origin: ORIGIN, startedAt: new Date().toISOString(), elapsedMs: 1,
          requestId: null, responseId: res.id, outcome: 'completed', status: 200, serviceTier: null, meters: unknownMeters(), charges: [],
        }],
      };
    },
  };
}
