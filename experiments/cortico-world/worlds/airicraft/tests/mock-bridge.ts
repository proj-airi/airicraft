/** A tiny in-process stand-in for the airicraft bridge, speaking the same paths and bearer auth. */
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { BridgeEvent } from '../src/bridge.ts';

export interface MockBridge {
  port: number;
  token: string;
  events: BridgeEvent[];
  oldestSeqNo: number;
  tools: Array<{ type: 'function'; function: { name: string; description: string; parameters: object } }>;
  toolCalls: Array<{ name: string; arguments: Record<string, unknown>; timeoutMs?: number }>;
  toolResult: (name: string) => Record<string, unknown>;
  close: () => Promise<void>;
}

export async function startMockBridge(): Promise<MockBridge> {
  const state: MockBridge = {
    port: 0,
    token: 'secret-token',
    events: [],
    oldestSeqNo: 1,
    tools: [],
    toolCalls: [],
    toolResult: (name) => ({ available: true, toolName: name, result: `ok ${name}`, imageAttached: false }),
    close: async () => {},
  };
  const server: Server = createServer((req, res) => {
    const send = (status: number, body: unknown) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    if (req.headers.authorization !== `Bearer ${state.token}`) return send(401, { error: 'unauthorized', message: 'Invalid bridge token' });
    const url = new URL(req.url ?? '/', 'http://localhost');
    if (req.method === 'GET' && url.pathname === '/v1/agent/tools') {
      return send(200, { available: true, codexDriverActive: true, toolCount: state.tools.length, tools: state.tools });
    }
    if (req.method === 'POST' && url.pathname === '/v1/agent/tools') {
      let raw = '';
      req.on('data', (chunk) => (raw += chunk));
      req.on('end', () => {
        const body = JSON.parse(raw) as { name: string; arguments: Record<string, unknown>; timeoutMs?: number };
        state.toolCalls.push(body);
        if (body.name === 'reject_me') return send(400, { error: 'invalid_request', message: 'Invalid tool arguments' });
        send(200, state.toolResult(body.name));
      });
      return;
    }
    if (req.method === 'GET' && url.pathname === '/v1/agent/events/recent') {
      const sinceParam = url.searchParams.get('since');
      const since = sinceParam === null ? null : Number(sinceParam);
      const latest = state.events.length ? state.events[state.events.length - 1].seqNo : state.oldestSeqNo - 1;
      return send(200, {
        available: true,
        oldestSeqNo: state.oldestSeqNo,
        latestSeqNo: latest,
        truncated: since !== null && state.oldestSeqNo > 1 && since < state.oldestSeqNo - 1,
        events: state.events.filter((event) => event.seqNo > (since ?? 0)),
      });
    }
    send(404, { error: 'not_found', message: url.pathname });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  state.port = (server.address() as AddressInfo).port;
  state.close = () => new Promise<void>((resolve) => server.close(() => resolve()));
  return state;
}
