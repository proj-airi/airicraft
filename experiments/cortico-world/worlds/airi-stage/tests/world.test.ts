import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';
import type { ToolCallContext } from 'cortico/core/types.ts';
import { dryMountWorld, fakeWorldContext } from 'cortico/extensions/dry-mount.ts';
import { AIRI_STAGE } from '../src/definition.ts';
import { AiriStageWorld } from '../src/world.ts';
import { FakeHost } from './helpers/fake-host.ts';

let scratchDir: string;
beforeEach(() => { scratchDir = mkdtempSync(join(tmpdir(), 'airi-stage-')); });
afterEach(() => rmSync(scratchDir, { recursive: true, force: true }));

const call = {} as ToolCallContext;
const cfg = () => ({ enabled: true, host: '127.0.0.1', port: 0, speechEndTrigger: 'piggyback' as const });

async function startWorld() {
  const world = new AiriStageWorld({ cfg: cfg(), timezone: 'UTC', botName: 'Bot' });
  const host = new FakeHost();
  await world.start(host);
  return { world, host };
}

/** A stage stand-in that records every frame it receives. */
async function connectStage(port: number) {
  const socket = new WebSocket(`ws://127.0.0.1:${port}`);
  const frames: Array<Record<string, unknown>> = [];
  socket.on('message', (raw) => frames.push(JSON.parse(String(raw))));
  await new Promise<void>((resolve, reject) => { socket.once('open', () => resolve()); socket.once('error', reject); });
  const send = (frame: unknown) => socket.send(JSON.stringify(frame));
  return { socket, frames, send };
}

const until = async (cond: () => boolean, ms = 3000) => {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error('timed out waiting');
    await new Promise((r) => setTimeout(r, 10));
  }
};
const tool = (world: AiriStageWorld, name: string) => world.tools().find((t) => t.name === name)!;

describe('airi stage world', () => {
  it('dry-mounts without failures', async () => {
    const report = await dryMountWorld(AIRI_STAGE, { scratchDir });
    expect(report.failures).toEqual([]);
  });

  it('binds loopback by default', () => {
    expect(AIRI_STAGE.defaults().host).toBe('127.0.0.1');
  });

  it('turns a chat message into a flush event carrying its conversation', async () => {
    const { world, host } = await startWorld();
    const stage = await connectStage(world.port);
    stage.send({ type: 'hello', name: 'viewer' });
    stage.send({ type: 'msg', text: 'hello there', session: { id: 's1', label: 'chat' } });
    await until(() => host.events.length === 1);
    expect(host.events[0]).toMatchObject({
      type: 'airi.user_message', origin: 'external', senderKey: 'viewer', text: '[chat] viewer: hello there', meta: { session: { id: 's1', label: 'chat' } },
    });
    expect(host.pushOpts[0]).toEqual({ trigger: 'flush' });
    stage.socket.close();
    await world.stop();
  });

  it('decodes image attachments', async () => {
    const { world, host } = await startWorld();
    const stage = await connectStage(world.port);
    stage.send({ type: 'msg', text: 'look', images: [`data:image/png;base64,${Buffer.from('png').toString('base64')}`] });
    await until(() => host.events.length === 1);
    const blobs = (host.events[0] as unknown as { blobs: Array<{ mime: string; bytes: Uint8Array }> }).blobs;
    expect(blobs[0].mime).toBe('image/png');
    expect(Buffer.from(blobs[0].bytes).toString()).toBe('png');
    stage.socket.close();
    await world.stop();
  });

  it('airi_speak sends a speak frame to the stage in the current conversation', async () => {
    const { world, host } = await startWorld();
    const stage = await connectStage(world.port);
    stage.send({ type: 'msg', text: 'hi', session: { id: 's1', label: 'chat' } });
    await until(() => host.events.length === 1);
    const receipt = await tool(world, 'airi_speak').handler({ text: ' hello ' }, call);
    expect(String(receipt)).toContain('sent to stage');
    await until(() => stage.frames.some((f) => f.type === 'speak'));
    expect(stage.frames.find((f) => f.type === 'speak')).toMatchObject({ text: 'hello', sessionId: 's1' });
    stage.socket.close();
    await world.stop();
  });

  it('fails clearly with no stage, empty text, or an unknown conversation', async () => {
    const { world } = await startWorld();
    const speak = tool(world, 'airi_speak');
    expect(await speak.handler({ text: 'x' }, call)).toMatchObject({ failed: true, text: expect.stringContaining('no stage') });
    expect(await speak.handler({ text: '  ' }, call)).toMatchObject({ failed: true });
    const stage = await connectStage(world.port);
    await until(() => stage.frames.length === 1);
    expect(await speak.handler({ text: 'x', to: 'nope' }, call)).toMatchObject({ failed: true, text: expect.stringContaining('unknown conversation') });
    stage.socket.close();
    await world.stop();
  });

  it('airi_act needs something to do and sends emotion and motion', async () => {
    const { world } = await startWorld();
    const stage = await connectStage(world.port);
    await until(() => stage.frames.length === 1);
    const act = tool(world, 'airi_act');
    expect(await act.handler({}, call)).toMatchObject({ failed: true });
    await act.handler({ emotion: 'happy', delay: 1 }, call);
    await until(() => stage.frames.some((f) => f.type === 'act'));
    expect(stage.frames.find((f) => f.type === 'act')).toMatchObject({ emotion: 'happy', delay: 1 });
    stage.socket.close();
    await world.stop();
  });

  it('reports playback end as a piggyback speak-tagged event, and only for known utterances', async () => {
    const { world, host } = await startWorld();
    const stage = await connectStage(world.port);
    await until(() => stage.frames.length === 1);
    await tool(world, 'airi_speak').handler({ text: 'a line' }, call);
    await until(() => stage.frames.some((f) => f.type === 'speak'));
    const id = String(stage.frames.find((f) => f.type === 'speak')!.id);
    stage.send({ type: 'speech_end', id: 'unknown' });
    stage.send({ type: 'speech_end', id, interrupted: true });
    await until(() => host.events.length === 1);
    expect(host.events[0]).toMatchObject({ type: 'airi.speech_ended', origin: 'internal', tags: ['speak'], text: 'speech was cut off: "a line"' });
    expect(host.pushOpts[0]).toEqual({ trigger: 'piggyback' });
    stage.socket.close();
    await world.stop();
  });

  it.each(['debounce', 'flush'] as const)('speechEndTrigger=%s makes playback end wake the persona', async (trigger) => {
    const world = new AiriStageWorld({ cfg: { ...cfg(), speechEndTrigger: trigger }, timezone: 'UTC', botName: 'Bot' });
    const host = new FakeHost();
    await world.start(host);
    const stage = await connectStage(world.port);
    await until(() => stage.frames.length === 1);
    await tool(world, 'airi_speak').handler({ text: 'a line' }, call);
    await until(() => stage.frames.some((f) => f.type === 'speak'));
    stage.send({ type: 'speech_end', id: String(stage.frames.find((f) => f.type === 'speak')!.id) });
    await until(() => host.events.length === 1);
    expect(host.pushOpts[0]).toEqual({ trigger });
    stage.socket.close();
    await world.stop();
  });

  it('tolerates the frames the patched stage sends that this world ignores', async () => {
    const { world, host } = await startWorld();
    const stage = await connectStage(world.port);
    stage.send({ type: 'provider', config: { baseUrl: 'x', model: 'y' } });
    stage.send({ type: 'sessions', sessions: [{ id: 's1', label: 'chat' }] });
    stage.send({ type: 'memory_query' });
    stage.send('garbage');
    await until(() => stage.frames.some((f) => f.type === 'memory') && stage.frames.some((f) => f.text === 'malformed frame'));
    expect(host.events).toEqual([]);
    expect(world.envPromptVars()['airi.conversations']).toContain('chat (id: s1)');
    stage.socket.close();
    await world.stop();
  });

  it('streams draft output as delta frames without counting it as external output', async () => {
    const { world } = await startWorld();
    const stage = await connectStage(world.port);
    await until(() => stage.frames.length === 1);
    const tap = world.outputTap();
    tap.onEvent({ type: 'response.output_text.delta', delta: 'thinking' } as never);
    await until(() => stage.frames.some((f) => f.type === 'delta'));
    expect(tap.externalizes?.({} as never)).toBe(false);
    world.onTurnEnded();
    await until(() => stage.frames.some((f) => f.type === 'turn_end'));
    stage.socket.close();
    await world.stop();
  });
});
