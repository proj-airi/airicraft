#!/usr/bin/env tsx
/**
 * End-to-end run: Cortico Core + the Cormini persona (scripted model) + the airicraft World against a live client
 * + the AIRI stage World against a socket stand-in for the stage.
 *
 *   tsx e2e/run.ts [seconds]          # needs `scripts/cortico-experiment setup` and a running client in a world
 *
 * Prints a summary and writes it to $E2E_SUMMARY when set.
 */
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import WebSocket from 'ws';

const here = new URL('.', import.meta.url).pathname;
const corticoRoot = resolve(here, '../../../.cortico-experiment/cortico');
// Registers the `cortico/*` specifier resolver; must load before anything imports `cortico/...`.
await import(resolve(corticoRoot, 'src/extensions/runtime.ts'));

const { createBot } = await import('cortico/bot.ts');
const { loadDeployment } = await import('cortico/deploy.ts');
const { withWorlds } = await import('cortico/world.ts');
const cormini = (await import(resolve(corticoRoot, 'bots/cormini/index.ts'))).default;
const { AIRICRAFT } = await import('../worlds/airicraft/src/index.ts');
const { AIRI_STAGE } = await import('../worlds/airi-stage/src/index.ts');
const { createScriptedLlm } = await import('./scripted-llm.ts');

const seconds = Number(process.argv[2] ?? 45);
const stagePort = 6122;
const deployDir = resolve(here, '../../../.cortico-experiment/deployments/e2e');
rmSync(deployDir, { recursive: true, force: true });
mkdirSync(deployDir, { recursive: true });
// Core refuses to run without a configured model; the scripted client replaces the real one, so this endpoint is never called.
mkdirSync(resolve(deployDir, 'providers/scripted'), { recursive: true });
writeFileSync(resolve(deployDir, 'providers/scripted/config.json'), JSON.stringify({
  kind: 'openai-responses-compat',
  baseUrl: 'http://127.0.0.1:1/v1',
  spec: { model: 'scripted', thinking: false, contextWindow: 128000 },
}));
writeFileSync(resolve(deployDir, 'config.json'), JSON.stringify({
  displayName: 'E2E',
  activeProvider: 'scripted',
  tick: { intervalMinutes: 0.2 }, // heartbeat every 12 s of quiet
  worlds: {
    terminal: { enabled: false },
    airicraft: { enabled: true, pollIntervalMs: 200 },
    airi: { enabled: true, port: stagePort },
  },
}));

const turns: unknown[] = [];
const stageFrames: Array<Record<string, unknown>> = [];

/** What the scripted model does with a delivered frame. */
const script = (frame: string) => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  if (/reflex\.started/.test(frame)) {
    calls.push({ name: 'airi_act', args: { emotion: 'surprised' } }, { name: 'airi_speak', args: { text: 'Something is after me!' } });
  }
  if (/walk to (-?\d+) (-?\d+) (-?\d+)/.test(frame)) {
    const [, x, y, z] = /walk to (-?\d+) (-?\d+) (-?\d+)/.exec(frame)!;
    calls.push(
      { name: 'airi_speak', args: { text: `Okay, heading to ${x}, ${z}.` } },
      { name: 'ac_navigate_to', args: { x: Number(x), y: Number(y), z: Number(z), exactY: false } },
    );
  } else if (/\b(viewer|user): /.test(frame)) {
    calls.push({ name: 'airi_speak', args: { text: 'Hi viewer, I can see you.' } }, { name: 'ac_inspect_inventory', args: {} });
  }
  if (/state=(SUCCEEDED|FAILED|CANCELLED)/.test(frame)) {
    calls.push({ name: 'airi_speak', args: { text: 'That job just finished.' } });
  }
  if (/已安静/.test(frame) && calls.length === 0) {
    calls.push({ name: 'airi_speak', args: { text: 'Still here, watching the world.' } });
  }
  return calls;
};

const definition = withWorlds(cormini, [AIRICRAFT, AIRI_STAGE]);
const loaded = loadDeployment(definition, deployDir, corticoRoot, resolve(corticoRoot, 'bots/cormini'));
const llm = createScriptedLlm(script, (turn) => { turns.push(turn); console.log('[turn]', JSON.stringify({ frame: turn.frame.slice(0, 160), calls: turn.calls.map((c) => c.name) })); });
const bot = createBot(loaded, { ...definition, build: (l, worlds) => ({ ...definition.build(l, worlds), llm }) });
await bot.start();

// Stage stand-in (skipped with E2E_NO_STAND_IN=1 when a real stage connects): records frames, "plays" each utterance
// for 400 ms, and speaks as an audience member.
let stage: WebSocket | null = null;
if (!process.env.E2E_NO_STAND_IN) {
  const socket = new WebSocket(`ws://127.0.0.1:${stagePort}`);
  stage = socket;
  await new Promise<void>((resolveOpen, reject) => { socket.once('open', () => resolveOpen()); socket.once('error', reject); });
  socket.on('message', (raw) => {
    const frame = JSON.parse(String(raw)) as Record<string, unknown>;
    stageFrames.push(frame);
    if (frame.type === 'speak') setTimeout(() => socket.send(JSON.stringify({ type: 'speech_end', id: frame.id })), 400);
  });
  socket.send(JSON.stringify({ type: 'hello', name: 'viewer' }));
  setTimeout(() => socket.send(JSON.stringify({ type: 'msg', text: 'hello from the audience', session: { id: 's1', label: 'chat' } })), 4000);
  if (process.env.E2E_WALK) setTimeout(() => socket.send(JSON.stringify({ type: 'msg', text: `please walk to ${process.env.E2E_WALK}`, session: { id: 's1', label: 'chat' } })), 9000);
}

await new Promise((r) => setTimeout(r, seconds * 1000));
stage?.close();
await bot.shutdown('e2e done');

const summary = {
  seconds,
  turns: turns.length,
  stageFrames: stageFrames.map((f) => (f.type === 'speak' ? { type: 'speak', text: f.text } : { type: f.type, ...(f.emotion ? { emotion: f.emotion } : {}) })),
  turnDetail: turns,
};
if (process.env.E2E_SUMMARY) writeFileSync(process.env.E2E_SUMMARY, JSON.stringify(summary, null, 2));
console.log('[summary]', JSON.stringify({ seconds, turns: turns.length, spoken: stageFrames.filter((f) => f.type === 'speak').length }));
process.exit(0);
