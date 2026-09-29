/** Turns the mod's tool descriptors into Cortico `ToolDef`s that call back through the bridge. */
import type { BlobInput, ToolCallContext, ToolDef, ToolOutcome, ToolTag } from 'cortico/core/types.ts';
import type { BridgeClient, ToolDescriptor } from './bridge.ts';
import { BridgeError, BridgeUnreachable } from './bridge.ts';

const READ_NAME = /^(observe|inspect_|list_|get_|recall_|search_|read_|find_|check_|describe_|query_)/;
const FLOW_NAME = /^(continue|clear_queue)$/;
const TOOL_NAME = /^[a-zA-Z0-9_-]{1,64}$/;

export function tagsFor(name: string): readonly ToolTag[] {
  if (FLOW_NAME.test(name)) return ['flow'];
  return READ_NAME.test(name) ? ['read'] : ['act'];
}

export interface ToolOptions {
  bridge: BridgeClient;
  prefix: string;
  exclude: readonly string[];
  timeoutMs: number;
}

/** The mod reports tool failures as text starting with `TOOL_ERROR:`. */
function failed(text: string): boolean {
  return text.startsWith('TOOL_ERROR:');
}

export function toToolDefs(descriptors: readonly ToolDescriptor[], opts: ToolOptions): ToolDef[] {
  const excluded = new Set(opts.exclude);
  return descriptors
    .filter((tool) => !excluded.has(tool.name) && TOOL_NAME.test(`${opts.prefix}${tool.name}`))
    .map((tool) => ({
      name: `${opts.prefix}${tool.name}`,
      description: tool.description,
      parameters: tool.parameters,
      tags: tagsFor(tool.name),
      handler: (args: Record<string, unknown>, ctx: ToolCallContext) => call(opts, tool.name, args, ctx),
    }));
}

async function call(opts: ToolOptions, name: string, args: Record<string, unknown>, ctx: ToolCallContext): Promise<ToolOutcome> {
  try {
    const result = await opts.bridge.callTool(name, args, { timeoutMs: opts.timeoutMs, signal: ctx.signal });
    const blobs: BlobInput[] | undefined = result.image
      ? [{ bytes: result.image.bytes, mime: result.image.mime, name: `${name}.png`, fallbackText: `[image from ${name}]` }]
      : undefined;
    return { text: result.text, ...(blobs ? { blobs } : {}), ...(failed(result.text) ? { failed: true as const } : {}) };
  } catch (error) {
    if (ctx.signal?.aborted) return { text: `[${name} abandoned by the host]`, failed: true };
    if (error instanceof BridgeUnreachable) return { text: `[${name} not run] the airicraft bridge is unreachable: ${error.message}`, failed: true };
    if (error instanceof BridgeError) return { text: `[${name} rejected] ${error.code}: ${error.message}`, failed: true };
    throw error;
  }
}
