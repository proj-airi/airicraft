const state = {
  token: new URLSearchParams(location.hash.slice(1)).get('token') || '',
  observations: [],
  bySequence: new Map(),
  observationCharacters: new Map(),
  loadedCharacters: 0,
  metadata: null,
  partialHistory: false,
  live: true,
  selectedSequence: 0,
  selectedObservation: null,
  view: 'overview',
  search: '',
  streamAbort: null,
  replay: false,
  replayFile: null,
  replayIndex: [],
  selectedServerTick: 0,
  seekGeneration: 0,
  frameCache: null,
  frameLoadGeneration: 0,
  playbackTimer: null,
};

const CLIENT_HISTORY_CHARACTER_BUDGET = 4 * 1024 * 1024;
const INITIAL_SEQUENCE_WINDOW = 500;
const el = id => document.getElementById(id);
const fmt = new Intl.NumberFormat();
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });

function authHeaders() { return { Authorization: `Bearer ${state.token}` }; }
async function api(path) {
  const response = await fetch(path, { headers: authHeaders(), cache: 'no-store' });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  return response;
}

function safe(value, fallback = '—') {
  return value === undefined || value === null || value === '' ? fallback : value;
}
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', "'":'&#39;', '"':'&quot;' }[c]));
}
function pretty(value) { return JSON.stringify(value, null, 2); }
function bytes(value) {
  if (!Number.isFinite(value)) return '—';
  const units = ['B', 'KB', 'MB', 'GB']; let n = value; let i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i ? 1 : 0)} ${units[i]}`;
}
function duration(ms) {
  if (!Number.isFinite(ms) || ms < 0) return 'pending';
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(2)} s`;
}

function addObservations(items, shouldRender = true) {
  let changed = false;
  for (const item of items || []) {
    if (state.bySequence.has(item.sequence)) continue;
    const characters = JSON.stringify(item).length;
    state.bySequence.set(item.sequence, item);
    state.observationCharacters.set(item.sequence, characters);
    state.loadedCharacters += characters;
    state.observations.push(item);
    changed = true;
  }
  if (!changed) return;
  state.observations.sort((a, b) => a.sequence - b.sequence);
  trimClientHistory();
  if (state.live) state.selectedSequence = state.observations.at(-1)?.sequence || 0;
  if (!shouldRender) return;
  updateChrome();
  render();
}

function trimClientHistory() {
  let dropCount = 0;
  while (state.loadedCharacters > CLIENT_HISTORY_CHARACTER_BUDGET && dropCount < state.observations.length - 1) {
    const item = state.observations[dropCount++];
    state.loadedCharacters -= state.observationCharacters.get(item.sequence) || 0;
    state.observationCharacters.delete(item.sequence);
    state.bySequence.delete(item.sequence);
  }
  if (!dropCount) return;
  state.partialHistory = true;
  state.observations.splice(0, dropCount);
  if (state.selectedObservation && !state.bySequence.has(state.selectedObservation.sequence)) {
    state.selectedObservation = null;
    el('inspector-content').innerHTML = '<p class="muted">The selected observation moved outside the recent browser window.</p>';
  }
}

function resetObservations() {
  ++state.frameLoadGeneration;
  state.observations = [];
  state.bySequence.clear();
  state.observationCharacters.clear();
  state.loadedCharacters = 0;
  state.selectedObservation = null;
  state.selectedSequence = 0;
  el('inspector-content').innerHTML = '<p class="muted">Select an observation from this recording to inspect its payload.</p>';
}

function updateMetadata(data) {
  const metadata = { ...(data || {}) };
  delete metadata.observations;
  if (!state.replay && state.metadata?.sessionId && metadata.sessionId && state.metadata.sessionId !== metadata.sessionId) {
    stopPlayback();
    ++state.seekGeneration;
    resetObservations();
    state.live = true;
    state.partialHistory = false;
    if (state.frameCache?.url.startsWith('blob:')) URL.revokeObjectURL(state.frameCache.url);
    state.frameCache = null;
  }
  state.metadata = { ...(state.metadata || {}), ...metadata };
  updateChrome();
}

function updateChrome() {
  const latest = state.observations.at(-1);
  const meta = state.metadata || {};
  el('tick').textContent = fmt.format(meta.serverTickId ?? latest?.serverTickId ?? latest?.tick ?? 0);
  el('session').textContent = String(meta.sessionId || latest?.sessionId || '—').slice(0, 8);
  el('memory').textContent = `${bytes(meta.retainedBytes)} retained · ~${bytes(state.loadedCharacters)} loaded`;
  el('llm-count').textContent = state.observations.filter(o => o.type === 'llm_call').length;
  el('event-count').textContent = state.observations.filter(o => o.type === 'semantic_event' || o.type === 'debug_timeline').length;
  el('log-count').textContent = state.observations.filter(o => o.type === 'log').length;
  const cursor = el('time-cursor');
  cursor.disabled = meta.serverClockAvailable === false && !state.replay;
  el('play-history').disabled = cursor.disabled;
  cursor.min = meta.fromServerTickId ?? Math.max(0, (meta.serverTickId || 0) - (meta.historyWindowTicks || 12000));
  cursor.max = meta.toServerTickId ?? meta.serverTickId ?? latest?.serverTickId ?? 0;
  cursor.value = state.live ? cursor.max : state.selectedServerTick;
  const selected = observationAtCursor();
  el('cursor-label').textContent = state.live
    ? (meta.paused ? 'Server paused · recording window frozen' : 'Following the latest observation')
    : selected ? `Server tick ${fmt.format(state.selectedServerTick)} · ${timeFmt.format(selected.capturedAtMs)}` : 'Historical cursor';
  el('return-live').classList.toggle('active', state.live);
}

function observationAtCursor() {
  if (!state.observations.length) return null;
  let result = state.observations[0];
  for (const item of state.observations) {
    if (item.sequence > state.selectedSequence) break;
    result = item;
  }
  return result;
}

function snapshotAtCursor() {
  let result = null;
  for (const item of state.observations) {
    if (item.sequence > state.selectedSequence) break;
    if (item.type === 'runtime_snapshot') result = item;
  }
  return result;
}

function matches(item) {
  if (!state.search) return true;
  return JSON.stringify(item).toLowerCase().includes(state.search);
}

// Retain matching nodes so live updates do not reset disclosures, scroll, or RGB.
function updateContent(html) {
  const content = el('content');
  const template = document.createElement('template');
  template.innerHTML = html;
  if (content.dataset.view !== state.view) {
    content.replaceChildren(template.content);
    content.dataset.view = state.view;
    return;
  }
  reconcileChildren(content, template.content);
}
function nodeKey(node) {
  return node.nodeType === 1 ? node.dataset.key || node.dataset.sequence || null : null;
}
function reconcileChildren(parent, next) {
  const keyed = new Map([...parent.childNodes].filter(nodeKey).map(node => [nodeKey(node), node]));
  let cursor = parent.firstChild;
  for (const wanted of [...next.childNodes]) {
    const key = nodeKey(wanted);
    let current = key ? keyed.get(key) : cursor;
    if (!current || current.nodeType !== wanted.nodeType || current.nodeName !== wanted.nodeName || nodeKey(current) !== key) {
      parent.insertBefore(wanted, cursor);
      continue;
    }
    if (current !== cursor) parent.insertBefore(current, cursor);
    if (current.nodeType === 1) {
      for (const attribute of [...current.attributes]) {
        if (current.tagName === 'DETAILS' && attribute.name === 'open') continue;
        if (current.tagName === 'IMG' && attribute.name === 'src') continue;
        if (!wanted.hasAttribute(attribute.name)) current.removeAttribute(attribute.name);
      }
      for (const attribute of wanted.attributes) {
        if (current.getAttribute(attribute.name) !== attribute.value) current.setAttribute(attribute.name, attribute.value);
      }
      reconcileChildren(current, wanted);
    } else if (current.nodeValue !== wanted.nodeValue) current.nodeValue = wanted.nodeValue;
    cursor = current.nextSibling;
  }
  while (cursor) { const next = cursor.nextSibling; cursor.remove(); cursor = next; }
}

function render() {
  const snapshot = snapshotAtCursor();
  if (!snapshot && state.view !== 'timeline' && state.view !== 'logs' && state.view !== 'raw' && state.view !== 'transcript' && state.view !== 'compactions') {
    updateContent(`<div class="empty-state"><div class="loader"></div><h2>Waiting for the first runtime snapshot</h2><p>Transitions and transcripts will appear as soon as Airicraft emits them.</p></div>`);
    return;
  }
  ({ overview: renderOverview, transcript: renderTranscript, compactions: renderCompactions, timeline: renderTimeline, runtime: renderRuntime, logs: renderLogs, raw: renderRaw })[state.view](snapshot);
}

function renderOverview(snapshot) {
  const p = snapshot.payload || {};
  const agent = p.agent || {};
  const planner = p.planner || {};
  const task = p.task || {};
  const action = p.actionGraph || {};
  const world = p.world || {};
  const player = world.player || {};
  const lastEvents = state.observations.filter(o => o.sequence <= state.selectedSequence && ['semantic_event','debug_timeline','observation_gap'].includes(o.type) && matches(o)).slice(-12).reverse();
  const calls = currentLlmCalls().slice(-4).reverse();
  const frame = state.observations.filter(o => o.sequence <= state.selectedSequence && o.type === 'visual_frame').at(-1);
  const dropped = state.metadata?.droppedByType || {};
  const hasDrops = Object.values(dropped).some(Number);
  const warnings = [
    hasDrops ? `Server history has evicted observations: ${escapeHtml(JSON.stringify(dropped))}` : '',
    state.partialHistory ? 'Showing a recent browser window to stay responsive. Raw developer export saves the full retained history.' : '',
  ].filter(Boolean);
  updateContent(`
    ${warnings.map(warning => `<div class="warning">${warning}</div>`).join('')}
    <div class="grid summary-grid" style="margin-top:${warnings.length ? '12px' : '0'}">
      ${statCard('Planner', safe(planner.phase || planner.sessionPhase || (p.degraded ? 'DEGRADED' : 'READY')), `enabled ${p.plannerEnabled}`, 'cyan')}
      ${statCard('Active task', safe(task.status || task.state || agent.task?.status, 'IDLE'), safe(task.mission?.summary || task.summary || task.mission?.goal, 'No active mission'), 'blue')}
      ${statCard('Action graph', `${safe(action.nonterminalCount, 0)} active`, safe(action.foregroundExecutionId, 'No foreground graph'), 'amber')}
      ${statCard('World', world.loaded ? safe(world.dimension, 'loaded') : 'NOT LOADED', world.loaded ? `${Number(player.x || 0).toFixed(1)}, ${Number(player.y || 0).toFixed(1)}, ${Number(player.z || 0).toFixed(1)}` : 'Waiting for a world', 'purple')}
    </div>
    <div class="grid two-col" style="margin-top:12px">
      <section class="card">
        <div class="card-head"><h2>Causal timeline</h2><small>latest transitions</small></div>
        <div class="timeline">${lastEvents.length ? lastEvents.map(timelineRow).join('') : '<p class="muted card-body">No transitions in the retained window.</p>'}</div>
      </section>
      <div class="grid">
        ${frame ? `<section class="card"><div class="card-head"><h2>Visual context</h2><small>server tick ${fmt.format(frame.serverTickId ?? frame.tick)} · sparse client RGB</small></div><img class="visual-frame selectable" data-key="visual-frame" data-sequence="${frame.sequence}" data-recorded-frame="${frame.sequence}" alt="Minecraft frame at tick ${frame.tick}"></section>` : ''}
        <section class="card">
          <div class="card-head"><h2>Embodied state</h2><small>tick ${fmt.format(snapshot.tick)}</small></div>
          <div class="card-body">${kv({
            'session mode': agent.session?.mode,
            'runtime initialized': agent.initialized,
            'active job': p.activeJob?.status || p.activeJob?.jobId,
            'task execution': p.taskExecution?.state || p.taskExecution?.phase,
            'mission': p.missionExecution?.state || p.missionExecution?.status,
            'survival reflex': p.reflex?.state,
            'behavior tree': p.behaviorTree?.status || p.behaviorTree?.state,
            'health / food / air': world.loaded ? `${player.health}/${player.maxHealth} · ${player.food} · ${player.air}` : null,
          })}</div>
        </section>
        <section class="card">
          <div class="card-head"><h2>Recent LLM calls</h2><small>${calls.length} shown</small></div>
          <div class="card-body transcript">${calls.length ? calls.map(callSummary).join('') : '<p class="muted">No LLM calls recorded.</p>'}</div>
        </section>
      </div>
    </div>`);
  bindSelectable();
  if (frame) loadRecordedFrame(frame).catch(error => toast(error.message));
}

function statCard(label, value, note, accent) {
  return `<section class="card stat accent-${accent}"><label>${escapeHtml(label)}</label><strong>${escapeHtml(value)}</strong><small>${escapeHtml(note)}</small></section>`;
}
function kv(entries) {
  return `<dl class="kv">${Object.entries(entries).map(([k,v]) => `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(safe(v))}</dd>`).join('')}</dl>`;
}

function renderTranscript() {
  const calls = currentLlmCalls().filter(matches).reverse();
  updateContent(`<section class="card"><div class="card-head"><h2>Full LLM transcript</h2><small>${calls.length} lifecycle records · raw envelopes retained</small></div><div class="card-body transcript">${calls.length ? calls.map(renderCall).join('') : '<p class="muted">No matching LLM calls.</p>'}</div></section>`);
  bindSelectable();
}
function renderCompactions() {
  const calls = currentLlmCalls().filter(item => ['micro_compaction', 'compaction'].includes(item.payload?.requestKind)).filter(matches).reverse();
  updateContent(`<section class="card"><div class="card-head"><h2>Compactions</h2><small>${calls.length} calls in the loaded window</small></div>
    <div class="card-body transcript"><p class="muted">Inspect source evidence and returned findings or checkpoints. Call completion does not by itself confirm the planner applied the result.</p>
    ${calls.map(item => `<section data-key="compaction-${escapeHtml(item.sessionId || '')}-${escapeHtml(item.payload.sequenceId ?? item.sequence)}"><h3>${item.payload.requestKind === 'micro_compaction' ? 'Microcompaction' : 'Full compaction'}</h3>${renderCall(item)}</section>`).join('') || '<p class="muted">No matching compaction calls in the loaded window.</p>'}</div></section>`);
  bindSelectable();
}
function currentLlmCalls() {
  const latestById = new Map();
  for (const item of state.observations) {
    if (item.sequence > state.selectedSequence || item.type !== 'llm_call') continue;
    latestById.set(item.payload?.sequenceId ?? item.sequence, item);
  }
  return [...latestById.values()];
}
function callSummary(item) {
  const p = item.payload || {};
  return `<div class="call-header selectable" data-sequence="${item.sequence}"><span class="badge llm_call">${escapeHtml(p.status)}</span><strong>${escapeHtml(safe(p.model || p.requestKind, 'LLM call'))}</strong><small>${duration((p.completedAtMs || Date.now()) - p.requestedAtMs)}</small></div>`;
}
function renderCall(item) {
  const p = item.payload || {};
  let request = null;
  const requestBody = p.requestBody ?? state.bySequence.get(p.request?.observationSequence)?.payload?.requestBody;
  try { request = JSON.parse(requestBody || 'null'); } catch {}
  const messages = request?.messages || [];
  return `<article class="call" data-key="call-${escapeHtml(item.sessionId || '')}-${escapeHtml(p.sequenceId ?? item.sequence)}">
    <div class="call-header selectable" data-sequence="${item.sequence}">
      <span class="badge llm_call">${escapeHtml(p.status)}</span>
      <strong>${escapeHtml(safe(p.model || p.providerName, p.requestKind))}</strong>
      <small>#${p.sequenceId} · ${duration((p.completedAtMs || Date.now()) - p.requestedAtMs)} · ${escapeHtml(safe(p.usage?.totalTokens, '?'))} tok</small>
    </div>
    <div class="call-body">
      ${p.status === 'STREAMING' ? `<div class="message assistant"><label>Streaming · incomplete</label><pre>${escapeHtml(p.rawResponseBody || '')}</pre></div>` : ''}
      ${messages.map(messageHtml).join('') || '<p class="muted">Request body is not a chat envelope; use raw details below.</p>'}
      ${p.parsedResponse ? `<div class="message assistant"><label>parsed response</label><pre>${escapeHtml(typeof p.parsedResponse === 'string' ? p.parsedResponse : pretty(p.parsedResponse))}</pre></div>` : ''}
      ${p.failureMessage ? `<div class="message"><label class="error">${escapeHtml(p.failureType)}</label><pre>${escapeHtml(p.failureMessage)}</pre></div>` : ''}
    </div>
    <details><summary>Exact request envelope</summary><div class="card-body"><pre class="json">${escapeHtml(pretty(request ?? requestBody ?? "Request envelope no longer in the loaded window"))}</pre></div></details>
    <details><summary>${p.status === 'STREAMING' ? 'Stream preview' : request?.stream ? 'Assembled stream response' : 'Exact raw response'}</summary><div class="card-body"><pre class="json">${escapeHtml(p.rawResponseBody || 'No raw response yet')}</pre></div></details>
  </article>`;
}
function messageHtml(message) {
  const role = message.role || 'unknown';
  const content = typeof message.content === 'string' ? message.content : pretty(message.content);
  const extras = message.tool_calls ? `\n\ntool_calls:\n${pretty(message.tool_calls)}` : '';
  return `<div class="message ${escapeHtml(role)}"><label>${escapeHtml(role)}</label><pre>${escapeHtml(content + extras)}</pre></div>`;
}

function renderTimeline() {
  const items = state.observations.filter(o => o.sequence <= state.selectedSequence && o.type !== 'runtime_snapshot' && o.type !== 'log' && o.type !== 'visual_frame' && matches(o)).slice().reverse();
  updateContent(`<section class="card"><div class="card-head"><h2>Causal timeline</h2><small>${items.length} observations</small></div><div class="timeline">${items.length ? items.map(timelineRow).join('') : '<p class="muted card-body">No matching observations.</p>'}</div></section>`);
  bindSelectable();
}
function timelineRow(item) {
  const p = item.payload || {};
  const summary = p.summary || p.type || p.action || p.message || p.reason || item.type;
  return `<div class="timeline-row selectable ${state.selectedObservation?.sequence === item.sequence ? 'selected' : ''}" data-sequence="${item.sequence}"><time>${timeFmt.format(item.capturedAtMs)}</time><span class="badge ${item.type}">${escapeHtml(item.type.replaceAll('_',' '))}</span><p>${escapeHtml(summary)}</p><span class="sequence">#${item.sequence}</span></div>`;
}

function renderRuntime(snapshot) {
  const p = snapshot.payload || {};
  const sections = ['agent','system2','planner','activeGoal','activeJob','task','taskExecution','missionExecution','reflex','actionGraph','behaviorTree','eventPipeline','dialogueState','conversationSources','world','observability'];
  updateContent(`<div class="grid two-col">${sections.map(key => `<section class="card"><div class="card-head"><h2>${escapeHtml(key.replace(/([A-Z])/g,' $1'))}</h2><small>snapshot #${snapshot.sequence}</small></div><div class="card-body"><pre class="json">${escapeHtml(pretty(p[key]))}</pre></div></section>`).join('')}</div>`);
}

function contextReferences(value, references = new Set()) {
  if (!value || typeof value !== 'object') return references;
  for (const [key, child] of Object.entries(value)) {
    if (['observationSequence', 'recipeCatalogSequence'].includes(key) && Number.isInteger(child)) references.add(child);
    else contextReferences(child, references);
  }
  return references;
}

function renderLogs() {
  const logs = state.observations.filter(o => o.sequence <= state.selectedSequence && o.type === 'log' && matches(o)).slice().reverse();
  updateContent(`<section class="card"><div class="card-head"><h2>Minecraft / Airicraft logs</h2><small>${logs.length} retained lines</small></div>${logs.length ? logs.map(o => `<div class="log-line selectable" data-sequence="${o.sequence}"><time>${timeFmt.format(o.capturedAtMs)}</time><span>${escapeHtml(o.payload?.message)}</span></div>`).join('') : '<p class="muted card-body">No matching log lines.</p>'}</section>`);
  bindSelectable();
}

function renderRaw(snapshot) {
  const selected = state.selectedObservation || observationAtCursor() || snapshot;
  updateContent(`<section class="card"><div class="card-head"><h2>Raw observation</h2><small>${selected ? `#${selected.sequence} · ${selected.type}` : 'none selected'}</small></div><div class="card-body"><pre class="json">${escapeHtml(pretty(selected))}</pre></div></section>`);
}

function bindSelectable() {
  document.querySelectorAll('.selectable').forEach(node => node.onclick = () => selectObservation(Number(node.dataset.sequence)));
}
function selectObservation(sequence) {
  const item = state.bySequence.get(sequence);
  if (!item) return;
  state.selectedObservation = item;
  el('inspector-content').innerHTML = `<div class="section-label">${escapeHtml(item.type)} · #${item.sequence}</div>${kv({ session: item.sessionId, tick: item.tick, captured: new Date(item.capturedAtMs).toISOString() })}<div class="section-label">Exact payload</div><pre class="json">${escapeHtml(pretty(item.payload))}</pre>`;
  document.querySelector('.workspace').classList.remove('inspector-collapsed');
  el('inspector').classList.remove('collapsed');
  render();
}

async function loadInitial() {
  if (!state.token) throw new Error('Dashboard token is missing from the URL. Open the URL printed by Airicraft.');
  const bootstrap = await (await api('/api/bootstrap')).json();
  updateMetadata(bootstrap);
  const oldestCursor = Math.max(0, (bootstrap.oldestSequence || 1) - 1);
  let cursor = Math.max(oldestCursor, bootstrap.latestSequence - INITIAL_SEQUENCE_WINDOW);
  state.partialHistory = cursor > oldestCursor;
  while (cursor < bootstrap.latestSequence) {
    const batch = await (await api(`/api/observations?since=${cursor}&limit=1000`)).json();
    updateMetadata(batch);
    addObservations(batch.observations, false);
    const next = batch.observations?.at(-1)?.sequence;
    if (!next || next <= cursor) break;
    cursor = next;
  }
  updateChrome();
  render();
  connected(true);
  stream(cursor);
}

async function stream(since) {
  state.streamAbort?.abort();
  state.streamAbort = new AbortController();
  try {
    const response = await fetch(`/api/stream?since=${since}`, { headers: authHeaders(), signal: state.streamAbort.signal, cache: 'no-store' });
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split('\n\n');
      buffer = frames.pop();
      for (const frame of frames) {
        const line = frame.split('\n').find(part => part.startsWith('data: '));
        if (!line) continue;
        const batch = JSON.parse(line.slice(6));
        updateMetadata(batch);
        if (state.live) addObservations(batch.observations);
      }
    }
  } catch (error) {
    if (error.name === 'AbortError' || state.replay) return;
    connected(false, error.message);
    setTimeout(() => stream(state.observations.at(-1)?.sequence || since), 1500);
  }
}

function connected(isLive, error = '') {
  el('connection-dot').className = `dot ${isLive ? 'live' : 'offline'}`;
  el('connection-label').textContent = isLive ? (state.replay ? 'Replay' : 'Connected') : 'Reconnecting';
  if (error) toast(error);
}

async function exportSession(mode = 'raw') {
  if (state.replay) return toast('This is already a saved session.');
  try {
    const response = await api(mode === 'report' ? '/api/report' : '/api/export');
    const blob = await response.blob();
    const disposition = response.headers.get('content-disposition') || '';
    const name = disposition.match(/filename="([^"]+)"/)?.[1] || 'airicraft-debug.jsonl';
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob); link.download = name; link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    toast(mode === 'report' ? `Saved ${name}. Attach it with a description of the problem.` : `Saved ${name}`);
  } catch (error) { toast(error.message); }
}

async function loadRecordedFrame(frame) {
  const generation = ++state.frameLoadGeneration;
  const key = `${frame.sessionId}:${frame.sequence}`;
  if (state.frameCache?.key !== key) {
    let url;
    if (state.replay) {
      const entry = state.replayIndex.find(item => item.sequence === frame.sequence);
      if (!entry) return;
      const record = JSON.parse(await state.replayFile.slice(entry.offset, entry.end).text());
      if (!record.payload.imageBase64) return;
      url = `data:image/${record.payload.format};base64,${record.payload.imageBase64}`;
    } else {
      url = URL.createObjectURL(await (await api(`/api/frame?sequence=${frame.sequence}`)).blob());
    }
    if (generation !== state.frameLoadGeneration) {
      if (url.startsWith('blob:')) URL.revokeObjectURL(url);
      return;
    }
    if (state.frameCache?.url.startsWith('blob:')) URL.revokeObjectURL(state.frameCache.url);
    state.frameCache = { key, url };
  }
  const img = document.querySelector(`[data-recorded-frame="${frame.sequence}"]`);
  if (img) img.src = state.frameCache.url;
}

async function seekRecording(tick) {
  const generation = ++state.seekGeneration;
  state.live = false;
  state.selectedServerTick = tick;
  let batch;
  if (state.replay) {
    const baseline = new Map();
    const events = [];
    for (const entry of state.replayIndex) {
      if (entry.serverTickId > tick) continue;
      if (['runtime_snapshot', 'decision_state', 'visual_frame', 'llm_call'].includes(entry.type)) baseline.set(entry.type, entry);
      else if (entry.serverTickId >= tick - 100) { events.push(entry); if (events.length > 50) events.shift(); }
    }
    const entries = [...events, ...baseline.values()].sort((a, b) => a.sequence - b.sequence);
    const observations = await Promise.all(entries.map(async entry => {
      const record = JSON.parse(await state.replayFile.slice(entry.offset, entry.end).text());
      if (record.type === 'visual_frame') delete record.payload.imageBase64;
      return record;
    }));
    const references = contextReferences(observations.map(record => record.payload));
    const loaded = new Set(observations.map(record => record.sequence));
    for (const entry of state.replayIndex) {
      if (references.has(entry.sequence) && !loaded.has(entry.sequence)) {
        observations.push(JSON.parse(await state.replayFile.slice(entry.offset, entry.end).text()));
      }
    }
    observations.sort((a, b) => a.sequence - b.sequence);
    batch = { observations };
  } else {
    batch = await (await api(`/api/recording?at=${tick}`)).json();
  }
  if (generation !== state.seekGeneration) return;
  resetObservations();
  updateMetadata(batch);
  addObservations(batch.observations, false);
  state.selectedSequence = state.observations.at(-1)?.sequence || 0;
  updateChrome(); render();
}

function stopPlayback() {
  clearTimeout(state.playbackTimer);
  state.playbackTimer = null;
  el('play-history').textContent = 'Play history';
}

async function advancePlayback() {
  const end = Number(el('time-cursor').max);
  const next = Math.min(end, state.selectedServerTick + 5);
  try { await seekRecording(next); }
  catch (error) { stopPlayback(); return toast(error.message); }
  if (next >= end) return stopPlayback();
  if (state.playbackTimer !== null) state.playbackTimer = setTimeout(advancePlayback, 250);
}

async function openSession(file) {
  stopPlayback();
  ++state.seekGeneration;
  state.streamAbort?.abort();
  if (state.frameCache?.url.startsWith('blob:')) URL.revokeObjectURL(state.frameCache.url);
  state.frameCache = null;
  state.replay = true; state.live = false; state.metadata = null; state.partialHistory = false;
  state.replayFile = file; state.replayIndex = [];
  resetObservations();
  // Index byte ranges instead of retaining parsed payloads or decoded RGB for the entire file.
  const reader = file.stream().getReader();
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  let buffer = ''; let offset = 0;
  function indexLine(line, terminated) {
    const end = offset + encoder.encode(line).length;
    if (line.trim()) {
      const record = JSON.parse(line);
      if (record.recordType === 'manifest') updateMetadata(record);
      else if (record.recordType === 'observation') state.replayIndex.push({
        sequence: record.sequence, serverTickId: record.serverTickId ?? record.tick,
        type: record.type, offset, end,
      });
    }
    offset = end + (terminated ? 1 : 0);
  }
  while (true) {
    const { value, done } = await reader.read();
    buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      indexLine(buffer.slice(0, newline), true);
      buffer = buffer.slice(newline + 1);
    }
    if (done) { if (buffer) indexLine(buffer, false); break; }
  }
  const lastTick = state.metadata?.toServerTickId ?? state.metadata?.serverTickId ?? state.replayIndex.at(-1)?.serverTickId ?? 0;
  updateMetadata({ serverTickId: lastTick });
  await seekRecording(lastTick);
  connected(true); toast(`Opened ${file.name}`);
}

function toast(message) {
  el('toast').textContent = message;
  el('toast').classList.add('show');
  clearTimeout(toast.timer); toast.timer = setTimeout(() => el('toast').classList.remove('show'), 3200);
}

document.querySelectorAll('#stream-nav button').forEach(button => button.addEventListener('click', () => {
  document.querySelectorAll('#stream-nav button').forEach(node => node.classList.toggle('active', node === button));
  state.view = button.dataset.view; render();
}));
el('search').addEventListener('input', event => { state.search = event.target.value.trim().toLowerCase(); render(); });
el('time-cursor').addEventListener('input', event => { stopPlayback(); seekRecording(Number(event.target.value)).catch(error => toast(error.message)); });
el('return-live').addEventListener('click', async () => {
  stopPlayback();
  if (state.replay) return seekRecording(Number(el('time-cursor').max));
  ++state.seekGeneration;
  state.live = true; resetObservations();
  await loadInitial();
});
el('play-history').addEventListener('click', async () => {
  if (state.playbackTimer !== null) return stopPlayback();
  if (state.live || state.selectedServerTick >= Number(el('time-cursor').max)) {
    await seekRecording(Number(el('time-cursor').min));
  }
  el('play-history').textContent = 'Pause history';
  state.playbackTimer = setTimeout(advancePlayback, 0);
});
el('export').addEventListener('click', () => exportSession('raw'));
el('report').addEventListener('click', () => exportSession('report'));
el('session-file').addEventListener('change', event => event.target.files[0] && openSession(event.target.files[0]).catch(error => toast(error.message)));
el('close-inspector').addEventListener('click', () => { el('inspector').classList.add('collapsed'); document.querySelector('.workspace').classList.add('inspector-collapsed'); });

loadInitial().catch(error => { connected(false, error.message); updateContent(`<div class="empty-state"><h2 class="error">Dashboard connection failed</h2><p>${escapeHtml(error.message)}</p></div>`); });
