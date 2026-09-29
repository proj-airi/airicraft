import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { ToolCallContext } from 'cortico/core/types.ts';
import { dryMountWorld, fakeWorldContext } from 'cortico/extensions/dry-mount.ts';
import { AIRICRAFT } from '../src/definition.ts';
import { AIRICRAFT_DEFAULTS } from '../src/config.ts';
import { AiricraftWorld } from '../src/world.ts';
import { BridgeClient } from '../src/bridge.ts';
import { deliveryFor, eventText } from '../src/events.ts';
import { tagsFor } from '../src/tools.ts';
import { FakeHost } from './helpers/fake-host.ts';
import { startMockBridge, type MockBridge } from './mock-bridge.ts';

let scratchDir: string;
let mock: MockBridge;
beforeEach(async () => {
  scratchDir = mkdtempSync(join(tmpdir(), 'airicraft-world-'));
  mock = await startMockBridge();
});
afterEach(async () => {
  await mock.close();
  rmSync(scratchDir, { recursive: true, force: true });
});

const tool = (name: string, description = name) => ({ type: 'function' as const, function: { name, description, parameters: { type: 'object', properties: {} } } });
const event = (seqNo: number, type: string, payload: Record<string, unknown> = {}) => ({ seqNo, tick: seqNo * 10, timestampMs: 1000 + seqNo, type, payload });
const cfg = (over: Partial<typeof AIRICRAFT_DEFAULTS> = {}) => ({ ...AIRICRAFT_DEFAULTS, enabled: true, pollIntervalMs: 20, ...over });
const bridge = () => new BridgeClient({ state: { port: mock.port, token: mock.token } });
const call = {} as ToolCallContext;
const until = async (cond: () => boolean, ms = 3000) => {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error('timed out waiting');
    await new Promise((r) => setTimeout(r, 10));
  }
};

describe('definition', () => {
  it('dry-mounts without failures (the loader would accept it)', async () => {
    const report = await dryMountWorld(AIRICRAFT, { scratchDir });
    expect(report.failures).toEqual([]);
  });
});

describe('delivery table', () => {
  it.each([
    ['reflex.started', 'preempt'],
    ['player.died', 'flush'],
    ['task.failed', 'flush'],
    ['social.player_addressed_agent', 'flush'],
    ['perception.entity_noticed', 'debounce'],
    ['combat.damage_taken', 'debounce'],
    ['action_graph.goal_started', 'archive'],
    ['planner.response_applied', 'archive'],
    ['food.eaten', 'piggyback'],
    ['something.unknown', 'piggyback'],
  ])('%s -> %s', (type, expected) => {
    expect(deliveryFor(type)).toBe(expected);
  });

  it('archives the dashboard notice and redacts tokens in any event text', () => {
    const notice = { normalizedMessage: 'Airicraft debug dashboard: http://192.0.2.2:8765/#token=abc123def' };
    expect(deliveryFor('social.system_message', notice)).toBe('archive');
    expect(deliveryFor('social.system_message', { normalizedMessage: 'Server restarting' })).toBe('debounce');
    const text = eventText({ seqNo: 1, tick: 1, timestampMs: 1, type: 'social.system_message', payload: { normalizedMessage: 'open http://h/?a=1&token=abc123def now' } });
    expect(text).not.toContain('abc123def');
    expect(text).toContain('token=<redacted>');
  });

  it('classifies tools', () => {
    expect(tagsFor('inspect_inventory')).toEqual(['read']);
    expect(tagsFor('navigate_to')).toEqual(['act']);
    expect(tagsFor('clear_queue')).toEqual(['flow']);
  });
});

describe('tools', () => {
  it('exposes the mod tools with a prefix and hides the excluded ones', async () => {
    mock.tools = [tool('navigate_to', 'go somewhere'), tool('say'), tool('report_to_me'), tool('set_planner_goal'), tool('inspect_inventory')];
    const world = new AiricraftWorld({ cfg: cfg(), timezone: 'UTC', bridge: bridge() });
    await world.start(new FakeHost());
    expect(world.tools().map((t) => t.name)).toEqual(['ac_navigate_to', 'ac_inspect_inventory']);
    expect(world.tools()[0].description).toBe('go somewhere');
    await world.stop();
  });

  it('runs a tool through the bridge with the configured timeout', async () => {
    mock.tools = [tool('navigate_to')];
    const world = new AiricraftWorld({ cfg: cfg({ toolTimeoutMs: 5000 }), timezone: 'UTC', bridge: bridge() });
    await world.start(new FakeHost());
    const outcome = await world.tools()[0].handler({ x: 1, y: 2, z: 3 }, call);
    expect(outcome).toEqual({ text: 'ok navigate_to' });
    expect(mock.toolCalls).toEqual([{ name: 'navigate_to', arguments: { x: 1, y: 2, z: 3 }, timeoutMs: 5000 }]);
    await world.stop();
  });

  it('marks TOOL_ERROR results and rejected calls as failed, and attaches images', async () => {
    mock.tools = [tool('navigate_to'), tool('reject_me'), tool('capture')];
    mock.toolResult = (name) => name === 'capture'
      ? { result: 'captured', imageAttached: true, imageMimeType: 'image/png', imageBase64: Buffer.from('png').toString('base64') }
      : { result: 'TOOL_ERROR: no path' };
    const world = new AiricraftWorld({ cfg: cfg(), timezone: 'UTC', bridge: bridge() });
    await world.start(new FakeHost());
    const byName = (n: string) => world.tools().find((t) => t.name === `ac_${n}`)!;
    expect(await byName('navigate_to').handler({}, call)).toEqual({ text: 'TOOL_ERROR: no path', failed: true });
    expect(await byName('reject_me').handler({}, call)).toMatchObject({ failed: true, text: expect.stringContaining('invalid_request') });
    const captured = (await byName('capture').handler({}, call)) as { text: string; blobs: Array<{ mime: string; bytes: Uint8Array }> };
    expect(captured.text).toBe('captured');
    expect(captured.blobs[0].mime).toBe('image/png');
    expect(Buffer.from(captured.blobs[0].bytes).toString()).toBe('png');
    await world.stop();
  });

  it('reports an unreachable bridge in the receipt instead of throwing', async () => {
    mock.tools = [tool('navigate_to')];
    const world = new AiricraftWorld({ cfg: cfg(), timezone: 'UTC', bridge: bridge() });
    await world.start(new FakeHost());
    await mock.close();
    const outcome = (await world.tools()[0].handler({}, call)) as { failed: boolean; text: string };
    expect(outcome.failed).toBe(true);
    expect(outcome.text).toContain('unreachable');
    await world.stop();
    mock = await startMockBridge();
  });

  it('finds a relaunched mod through its rewritten state file and reloads tools', async () => {
    const stateFile = join(scratchDir, 'bridge-state.json');
    const writeState = (m: MockBridge) => writeFileSync(stateFile, JSON.stringify({ port: m.port, token: m.token }));
    mock.tools = [tool('navigate_to')];
    writeState(mock);
    const host = new FakeHost();
    const world = new AiricraftWorld({ cfg: cfg(), timezone: 'UTC', bridge: new BridgeClient({ stateFile }) });
    await world.start(host);
    expect(world.tools().map((t) => t.name)).toEqual(['ac_navigate_to']);

    await mock.close();
    await until(() => host.events.some((e) => e.type === 'airicraft.bridge_lost'));

    mock = await startMockBridge(); // relaunch: new port, new token
    mock.token = 'relaunched-token';
    mock.tools = [tool('navigate_to'), tool('inspect_inventory')];
    writeState(mock);
    await until(() => host.events.some((e) => e.type === 'airicraft.bridge_connected'));
    expect(world.tools().map((t) => t.name)).toEqual(['ac_navigate_to', 'ac_inspect_inventory']);

    mock.events.push(event(1, 'player.died'));
    await until(() => host.events.some((e) => e.type === 'airicraft.player.died'));
    expect(world.envPromptVars()['airicraft.link']).toBe('connected');
    await world.stop();
  });
});

describe('events', () => {
  it('starts at the present, then delivers new events with the mapped trigger', async () => {
    mock.events = [event(1, 'perception.block_noticed', { blockId: 'minecraft:diamond_ore' })];
    const host = new FakeHost();
    const world = new AiricraftWorld({ cfg: cfg(), timezone: 'UTC', bridge: bridge() });
    await world.start(host);
    expect(host.events).toEqual([]); // history from before mounting is not delivered
    mock.events.push(event(2, 'reflex.started', { threat: 'creeper' }), event(3, 'action_graph.goal_started'));
    await until(() => host.events.length === 2);
    expect(host.events.map((e) => e.type)).toEqual(['airicraft.reflex.started', 'airicraft.action_graph.goal_started']);
    expect(host.events[0]).toMatchObject({ source: 'airicraft', origin: 'external', text: 'reflex.started threat=creeper', meta: { seqNo: 2 } });
    expect(host.pushOpts).toEqual([{ trigger: 'preempt' }, { deliver: false }]);
    await world.stop();
  });

  it('does not deliver an event twice', async () => {
    const host = new FakeHost();
    const world = new AiricraftWorld({ cfg: cfg(), timezone: 'UTC', bridge: bridge() });
    await world.start(host);
    mock.events.push(event(1, 'player.died'));
    await until(() => host.events.length === 1);
    await new Promise((r) => setTimeout(r, 100));
    expect(host.events).toHaveLength(1);
    await world.stop();
  });

  it('reports overflowed history as missed events, as fact', async () => {
    const host = new FakeHost();
    const world = new AiricraftWorld({ cfg: cfg({ pollIntervalMs: 200 }), timezone: 'UTC', bridge: bridge() });
    await world.start(host);
    mock.oldestSeqNo = 50;
    mock.events = [event(50, 'player.died')];
    await until(() => host.events.length === 2);
    expect(host.events[0]).toMatchObject({ type: 'airicraft.events_missed', origin: 'internal' });
    expect(host.events[0].text).toContain('events 1 to 49 were not delivered');
    expect(host.events[1].type).toBe('airicraft.player.died');
    await world.stop();
  });

  it('reports the bridge going away once, not on every failed poll', async () => {
    mock.tools = [tool('navigate_to')];
    const host = new FakeHost();
    const world = new AiricraftWorld({ cfg: cfg(), timezone: 'UTC', bridge: bridge() });
    await world.start(host);
    expect(world.envPromptVars()['airicraft.link']).toBe('connected');
    await mock.close();
    await until(() => host.events.some((e) => e.type === 'airicraft.bridge_lost'));
    await new Promise((r) => setTimeout(r, 120));
    expect(host.events.filter((e) => e.type === 'airicraft.bridge_lost')).toHaveLength(1);
    expect(world.envPromptVars()['airicraft.link']).toBe('unreachable');
    await world.stop();
    mock = await startMockBridge();
  });
});
