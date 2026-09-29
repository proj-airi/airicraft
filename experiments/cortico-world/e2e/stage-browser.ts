#!/usr/bin/env tsx
/**
 * Drives the real AIRI stage (stage-web dev server with the patch) in headless Chromium: types a chat message and
 * waits for the persona's reply to appear. Run `tsx e2e/run.ts <seconds>` with E2E_NO_STAND_IN=1 alongside it.
 */
import { chromium } from 'playwright-core';

const shot = process.argv[2] ?? '/tmp/stage-chat.png';
const message = process.argv[3] ?? 'hello from the browser stage';
const reply = process.argv[4] ?? 'Hi viewer, I can see you.';

const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox', '--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const frames: string[] = [];
page.on('websocket', (ws) => {
  if (!ws.url().includes(':6122')) return;
  ws.on('framesent', (f) => frames.push(`> ${String(f.payload).slice(0, 110)}`));
  ws.on('framereceived', (f) => frames.push(`< ${String(f.payload).slice(0, 110)}`));
});
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 200)));
await page.addInitScript(() => {
  localStorage.setItem('settings/cortico/enabled', 'true');
  localStorage.setItem('settings/cortico/bridge-url', 'ws://127.0.0.1:6122');
  localStorage.setItem('onboarding/skipped', 'true');
});
await page.goto('http://127.0.0.1:5173/', { waitUntil: 'domcontentloaded', timeout: 120000 });
const box = page.locator('textarea').first();
await box.waitFor({ timeout: 120000 });
await page.waitForTimeout(5000);
await box.click();
await box.fill(message);
await box.press('Enter');
let seen = false;
try {
  await page.waitForFunction((text) => document.body.innerText.includes(text), reply, { timeout: 60000 });
  seen = true;
} catch { /* reported below */ }
await page.waitForTimeout(8000); // let playback reports arrive
await page.screenshot({ path: shot });
console.log('reply visible in chat:', seen);
console.log(frames.join('\n'));
await browser.close();
process.exit(seen ? 0 : 1);
