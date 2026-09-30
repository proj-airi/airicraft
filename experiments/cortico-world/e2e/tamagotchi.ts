#!/usr/bin/env tsx
/**
 * Drives the real AIRI desktop app (stage-tamagotchi, Electron) end to end against a running persona:
 * the leader window holds the World socket; the chat page runs in a follower window and sends through the synced
 * store. Run `E2E_NO_STAND_IN=1 tsx e2e/run.ts <seconds>` alongside, under xvfb-run, with ELECTRON_BIN set.
 */
import { _electron as electron } from 'playwright-core';
import { resolve } from 'node:path';

const appDir = resolve(new URL('.', import.meta.url).pathname, '../../../.cortico-experiment/airi/apps/stage-tamagotchi');
const shot = process.argv[2] ?? '/tmp/tama-chat.png';
const message = process.argv[3] ?? 'hello from the desktop app';
const reply = process.argv[4] ?? 'Hi viewer, I can see you.';

const app = await electron.launch({
  executablePath: process.env.ELECTRON_BIN!,
  args: [appDir, '--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  timeout: 120000,
});
const pause = (ms: number) => new Promise((r) => setTimeout(r, ms));
await pause(20000);

const find = (needle: string) => app.windows().find((w) => w.url().includes(needle));
const leader = find('synced-leader=true');
const follower = find('synced-leader=false');
if (!leader || !follower) throw new Error(`expected a leader and a follower window, got: ${app.windows().map((w) => w.url()).join(' | ')}`);

const frames: string[] = [];
leader.on('websocket', (ws) => {
  if (!ws.url().includes(':6122')) return;
  ws.on('framesent', (f) => frames.push(`> ${String(f.payload).slice(0, 110)}`));
  ws.on('framereceived', (f) => frames.push(`< ${String(f.payload).slice(0, 110)}`));
});

// Same storage for every window of the app: skip onboarding, switch the Cortico persona on, then reload.
await leader.evaluate(() => {
  localStorage.setItem('onboarding/skipped', 'true');
  localStorage.setItem('settings/cortico/enabled', 'true');
  localStorage.setItem('settings/cortico/bridge-url', 'ws://127.0.0.1:6122');
});
await leader.reload();
await follower.reload();
await pause(15000);

// The chat page shows in the follower window (synced with the leader's stores).
await follower.evaluate(() => { location.hash = '#/chat'; });
const box = follower.locator('textarea').first();
await box.waitFor({ timeout: 60000 });
await pause(3000);
await box.click();
await box.fill(message);
await box.press('Enter');

let seen = false;
try {
  await follower.waitForFunction((text) => document.body.innerText.includes(text), reply, { timeout: 60000 });
  seen = true;
} catch { /* reported below */ }
await pause(8000); // let playback reports arrive
await follower.screenshot({ path: shot }).catch(() => {});
console.log('reply visible in the desktop chat window:', seen);
console.log(frames.filter((f) => !/provider|sessions/.test(f)).join('\n'));
await app.close();
process.exit(seen ? 0 : 1);
