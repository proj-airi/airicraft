#!/usr/bin/env tsx
/**
 * Reads what an e2e run left behind (its summary and Cortico's logs) and prints cost, wake, repetition, dead-air and
 * context-growth figures. `--judge` also asks the same model whether a sample of utterances is supported by what the
 * persona had observed (needs the CORTICO_LLM_* variables).
 *
 *   tsx e2e/analyze.ts <summary.json> [--judge]
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const here = new URL('.', import.meta.url).pathname;
const summaryFile = process.argv[2];
if (!summaryFile) throw new Error('usage: tsx e2e/analyze.ts <summary.json> [--judge]');
const judge = process.argv.includes('--judge');
const summary = JSON.parse(readFileSync(summaryFile, 'utf8')) as {
  ranMs: number; stoppedByGuard: boolean; speechEndTrigger: string; tickMin: number; streamer: boolean;
  stageFrames: Array<{ atMs: number; type: string; text?: string; emotion?: string }>;
};

const dataDir = resolve(here, '../../../.cortico-experiment/deployments/e2e/data');
const jsonl = <T>(file: string): T[] => (existsSync(file) ? readFileSync(file, 'utf8').split('\n').flatMap((l) => { try { return l.startsWith('{') ? [JSON.parse(l) as T] : []; } catch { return []; } }) : []);
const runDir = resolve(dataDir, 'runs', readdirSync(resolve(dataDir, 'runs')).filter((n) => n.startsWith('r-')).sort().at(-1)!);

type Usage = { promptTokens: number; completionTokens: number; cacheHitTokens?: number; ts: string; attempt: { elapsedMs: number; outcome?: string; meters?: { native?: { cost?: number } | null } } };
type Call = { ts: string; round: number; tool: string; args: unknown; receipt?: string };
type Ev = { ts: string; type: string; text: string };

const usage = jsonl<Usage>(resolve(dataDir, 'usage.jsonl'));
const calls = jsonl<Call>(resolve(runDir, 'toolcalls.jsonl'));
const events = jsonl<Ev>(resolve(runDir, 'events.jsonl'));
const speaks = summary.stageFrames.filter((f) => f.type === 'speak' && f.text);
const minutes = summary.ranMs / 60000;

const words = (t: string) => new Set(t.toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, ' ').split(/\s+/).filter((w) => w.length > 2));
const jaccard = (a: Set<string>, b: Set<string>) => { const i = [...a].filter((w) => b.has(w)).length; return i / (a.size + b.size - i || 1); };
const sims = speaks.map((s, i) => Math.max(0, ...speaks.slice(0, i).map((p) => jaccard(words(s.text!), words(p.text!)))));
const gaps = speaks.slice(1).map((s, i) => (s.atMs - speaks[i].atMs) / 1000).sort((a, b) => a - b);
const median = (xs: number[]) => (xs.length ? xs[Math.floor(xs.length / 2)] : 0);
const cost = usage.reduce((sum, u) => sum + (u.attempt.meters?.native?.cost ?? 0), 0);
const prompt = usage.reduce((sum, u) => sum + u.promptTokens, 0);
const cached = usage.reduce((sum, u) => sum + (u.cacheHitTokens ?? 0), 0);
const byTool: Record<string, number> = {};
for (const c of calls) byTool[c.tool] = (byTool[c.tool] ?? 0) + 1;
const failedReceipts = calls.filter((c) => /^\[.* (rejected|not run|abandoned)|\[unknown tool\]|TOOL_ERROR/.test(String(c.receipt ?? ''))).length;
const opening = (t: string) => t.split(/\s+/).slice(0, 3).join(' ').toLowerCase();
const openings: Record<string, number> = {};
for (const s of speaks) openings[opening(s.text!)] = (openings[opening(s.text!)] ?? 0) + 1;
const topOpenings = Object.entries(openings).sort((a, b) => b[1] - a[1]).slice(0, 3);
const third = Math.max(1, Math.floor(usage.length / 3));

const report = {
  minutes: Number(minutes.toFixed(1)),
  speechEndTrigger: summary.speechEndTrigger,
  stoppedByGuard: summary.stoppedByGuard,
  modelRequests: usage.length,
  failedRequests: usage.filter((u) => u.attempt.outcome && u.attempt.outcome !== 'completed').length,
  requestsPerMinute: Number((usage.length / minutes).toFixed(2)),
  costUsd: Number(cost.toFixed(4)),
  costPerMinuteUsd: Number((cost / minutes).toFixed(5)),
  promptTokens: prompt,
  cacheRate: Number((cached / (prompt || 1)).toFixed(2)),
  avgRequestMs: Math.round(usage.reduce((s, u) => s + u.attempt.elapsedMs, 0) / (usage.length || 1)),
  spoken: speaks.length,
  spokenPerMinute: Number((speaks.length / minutes).toFixed(2)),
  deadAirSec: { median: Number(median(gaps).toFixed(1)), max: Number((gaps.at(-1) ?? 0).toFixed(1)) },
  repetition: { nearDuplicates: sims.filter((x) => x >= 0.7).length, maxSimilarity: Number(Math.max(0, ...sims).toFixed(2)), topOpenings },
  contextGrowth: { firstThirdAvgPrompt: Math.round(usage.slice(0, third).reduce((s, u) => s + u.promptTokens, 0) / third), lastThirdAvgPrompt: Math.round(usage.slice(-third).reduce((s, u) => s + u.promptTokens, 0) / third) },
  toolCalls: byTool,
  failedReceipts,
  bridgeLost: events.filter((e) => e.type === 'airicraft.bridge_lost').length,
  reflexEvents: events.filter((e) => e.type.startsWith('airicraft.reflex.')).length,
  speechEndEvents: events.filter((e) => e.type === 'airi.speech_ended').length,
};

if (judge) {
  const base = process.env.CORTICO_LLM_BASE_URL, model = process.env.CORTICO_LLM_MODEL, key = process.env.CORTICO_LLM_API_KEY;
  if (!base || !model || !key) throw new Error('--judge needs CORTICO_LLM_BASE_URL, CORTICO_LLM_MODEL and CORTICO_LLM_API_KEY');
  const t0 = new Date(summary.stageFrames.length ? 0 : 0).getTime();
  const speakCalls = calls.filter((c) => c.tool === 'airi_speak');
  const sample = speakCalls.filter((_, i) => i % Math.max(1, Math.ceil(speakCalls.length / 12)) === 0).slice(0, 12);
  const verdicts: Array<{ text: string; verdict: string; reason: string }> = [];
  for (const call of sample) {
    const at = new Date(call.ts).getTime();
    const facts = [
      ...events.filter((e) => new Date(e.ts).getTime() <= at && new Date(e.ts).getTime() > at - 120000 && !e.type.startsWith('airi.speech')).map((e) => `EVENT ${e.text}`),
      ...calls.filter((c) => c.tool.startsWith('ac_') && new Date(c.ts).getTime() <= at && new Date(c.ts).getTime() > at - 120000).map((c) => `TOOL ${c.tool} -> ${String(c.receipt ?? '').slice(0, 700)}`),
    ].join('\n').slice(-6000);
    const said = String((call.args as { text?: string } | null)?.text ?? '');
    const res = await fetch(`${base}/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model, temperature: 0, max_tokens: 1200, messages: [
        { role: 'system', content: 'You check whether a streamer character\'s spoken line is supported by the facts it had. Reply with JSON only: {"verdict":"supported"|"partly"|"unsupported"|"no-factual-claim","reason":"short"}. Opinions, greetings and offers are "no-factual-claim". Claims about the world, position, inventory, time or actions must be in the facts.' },
        { role: 'user', content: `FACTS (last two minutes):\n${facts || '(none)'}\n\nLINE: ${said}` },
      ] }),
    });
    const body = (await res.json()) as { choices?: Array<{ message?: { content?: string } }> };
    const raw = body.choices?.[0]?.message?.content ?? '';
    try { const v = JSON.parse(raw.replace(/^```json|```$/g, '').trim()); verdicts.push({ text: said.slice(0, 90), verdict: v.verdict, reason: String(v.reason).slice(0, 120) }); }
    catch { verdicts.push({ text: said.slice(0, 90), verdict: 'judge-error', reason: raw.slice(0, 80) }); }
  }
  void t0;
  (report as Record<string, unknown>).groundedness = {
    sampled: verdicts.length,
    counts: verdicts.reduce<Record<string, number>>((m, v) => ((m[v.verdict] = (m[v.verdict] ?? 0) + 1), m), {}),
    unsupported: verdicts.filter((v) => v.verdict === 'unsupported' || v.verdict === 'partly'),
  };
}

console.log(JSON.stringify(report, null, 2));
