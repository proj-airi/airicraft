// Run with node --test; requires playwright on NODE_PATH and its Chromium browser.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require('playwright');
const root = 'src/main/resources/assets/airicraft/dashboard/';
async function dashboard(t) {
  const browser = await chromium.launch({ headless: true });
  t.after(() => browser.close());
  const page = await browser.newPage();
  await page.setContent(fs.readFileSync(root + 'index.html', 'utf8').replace(/<script[^>]*>.*?<\/script>/s, ''));
  const source = fs.readFileSync(root + 'app.js', 'utf8');
  await page.addScriptTag({ content: source.slice(0, source.lastIndexOf('loadInitial().catch')) });
  return page;
}
test('visual frames stay recorded without flooding causal timeline', async t => {
  const page = await dashboard(t);
  const result = await page.evaluate(() => {
    state.view = 'timeline';
    addObservations([{sequence:1,type:'semantic_event',capturedAtMs:1000,payload:{summary:'task finished'}},
      {sequence:2,type:'visual_frame',capturedAtMs:1001,payload:{}}]);
    return {text:el('content').textContent, retained:state.bySequence.has(2)};
  });
  assert.match(result.text, /task finished/);
  assert.doesNotMatch(result.text, /visual frame/);
  assert.equal(result.retained, true);
});
test('live batches preserve an open request envelope and its DOM node', async t => {
  const page = await dashboard(t);
  assert.equal(await page.evaluate(() => {
    state.view = 'transcript';
    addObservations([{sequence:1,type:'llm_call',capturedAtMs:1000,payload:{sequenceId:1,status:'COMPLETED',requestBody:'{"messages":[]}'}}]);
    const detail = document.querySelector('details'); detail.open = true;
    addObservations([{sequence:2,type:'visual_frame',capturedAtMs:1001,payload:{}}]);
    return detail === document.querySelector('details') && detail.open;
  }), true);
});
test('compaction viewer separates micro and full calls, showing inputs and outputs', async t => {
  const page = await dashboard(t);
  const result = await page.evaluate(() => {
    state.view = 'compactions';
    addObservations(['micro_compaction','compaction','planner'].map((kind,i) => ({sequence:i+1,type:'llm_call',capturedAtMs:1000,payload:{sequenceId:i+1,requestKind:kind,status:'COMPLETED',requestBody:JSON.stringify({messages:[{role:'user',content:kind+' input'}]}),rawResponseBody:kind+' output'}})));
    return el('content').textContent;
  });
  assert.match(result, /Microcompaction/); assert.match(result, /Full compaction/);
  assert.match(result, /micro_compaction input/); assert.match(result, /compaction output/);
  assert.doesNotMatch(result, /planner input/);
});
test('overview retains the displayed image while the next frame loads', async t => {
  const page = await dashboard(t);
  assert.equal(await page.evaluate(() => {
    loadRecordedFrame = async () => {};
    addObservations([{sequence:1,type:'runtime_snapshot',capturedAtMs:1000,payload:{}}, {sequence:2,type:'visual_frame',capturedAtMs:1000,payload:{}}]);
    const image = document.querySelector('.visual-frame'); image.src = 'data:image/png;base64,old';
    addObservations([{sequence:3,type:'visual_frame',capturedAtMs:1001,payload:{}}]);
    return image === document.querySelector('.visual-frame') && image.getAttribute('src') === 'data:image/png;base64,old';
  }), true);
});
test('late frame responses cannot replace or revoke the newest frame', async t => {
  const page = await dashboard(t);
  assert.equal(await page.evaluate(async () => {
    const pending = [];
    api = () => new Promise(resolve => pending.push(resolve));
    el('content').innerHTML = '<img data-recorded-frame="2">';
    const older = loadRecordedFrame({sessionId:'s',sequence:1});
    const newer = loadRecordedFrame({sessionId:'s',sequence:2});
    const response = {blob:async () => new Blob(['image'])};
    pending[1](response); await newer;
    const displayed = document.querySelector('img').src;
    pending[0](response); await older;
    return state.frameCache.key === 's:2' && state.frameCache.url === displayed;
  }), true);
});
test('new calls and lifecycle updates keep the inspected call expanded', async t => {
  const page = await dashboard(t);
  assert.equal(await page.evaluate(() => {
    state.view = 'transcript';
    const call = (sequence, id, status) => ({sequence,type:'llm_call',capturedAtMs:1000,payload:{sequenceId:id,status,requestBody:'{"messages":[]}'}});
    addObservations([call(1,1,'REQUESTED')]);
    const article = document.querySelector('article');
    const detail = article.querySelector('details'); detail.open = true;
    addObservations([call(2,1,'COMPLETED'), call(3,2,'REQUESTED')]);
    article.querySelector('.selectable').click();
    return article.isConnected && detail.open && article.textContent.includes('COMPLETED') && state.selectedObservation.sequence === 2;
  }), true);
});

test('report flow defaults to minimal, previews before consent, and invalidates edits', async t => {
  const page = await dashboard(t);
  await page.evaluate(() => {
    window.reportCalls = [];
    api = async (path, body) => {
      reportCalls.push({path, body});
      if (path.endsWith('/mark')) return {json: async () => ({draftId:'marked-at-42'})};
      if (path.endsWith('/preview')) return {json: async () => ({previewId:'reviewed', summary:{text:'Incident: 0–42; safe description'}, privacy:{includedClasses:['Metadata only']}, attachments:{'summary.txt':'Readable summary', 'report.jsonl':'{"payload":"Actual attached evidence <script>bad()</script>"}\n'}, evidence:[{title:'Observation 42',text:'Actual attached evidence <script>bad()</script>',imageDataUrl:null},{title:'Screenshot 42',text:'Pixels in report',imageDataUrl:'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1sAAAAASUVORK5CYII='}]})};
      return {blob:async () => new Blob(['bundle']), headers:new Headers({'content-disposition':'attachment; filename="report.zip"'})};
    };
  });
  await page.click('#report');
  await page.waitForSelector('#report-dialog[open]');
  assert.equal(await page.inputValue('#report-mode'), 'MINIMAL');
  assert.equal(await page.isDisabled('#report-save'), true);
  await page.fill('#report-description', 'Stopped moving');
  await page.click('#report-preview');
  await page.waitForFunction(() => !document.getElementById('report-save').disabled);
  assert.match(await page.textContent('#report-preview-text'), /0–42/);
  assert.match(await page.textContent('#report-evidence'), /Actual attached evidence <script>bad\(\)<\/script>/);
  assert.equal(await page.locator('#report-evidence script').count(), 0);
  await page.locator('#report-evidence summary').last().click();
  await page.waitForFunction(() => document.querySelector('#report-evidence img')?.naturalWidth === 1);
  assert.match(await page.inputValue('#report-raw'), /Actual attached evidence/);
  assert.deepEqual(await page.evaluate(() => reportCalls.map(x => x.path)), ['/api/report/mark','/api/report/preview']);
  await page.fill('#report-description', 'Changed');
  assert.equal(await page.textContent('#report-evidence'), '');
  assert.equal(await page.inputValue('#report-raw'), '');
  assert.equal(await page.isDisabled('#report-save'), true);
  await page.selectOption('#report-mode', 'DEVELOPER');
  assert.match(await page.textContent('#report-categories'), /screenshots/i);
  await page.click('#report-preview');
  await page.waitForFunction(() => !document.getElementById('report-save').disabled);
  await page.click('#report-save');
  await page.waitForFunction(() => !document.getElementById('report-dialog').open);
  const last = await page.evaluate(() => reportCalls.at(-1));
  assert.deepEqual(last, {path:'/api/report/save',body:{previewId:'reviewed',consent:true}});
});

test('canceling raw developer export makes no request', async t => {
  const page = await dashboard(t);
  await page.evaluate(() => { window.calls = 0; api = async () => { calls++; throw Error('unexpected'); }; });
  page.on('dialog', dialog => dialog.dismiss());
  await page.click('#export');
  assert.equal(await page.evaluate(() => calls), 0);
});

test('a preview without inspectable attachments cannot enable save', async t => {
  const page = await dashboard(t);
  await page.evaluate(() => {
    api = async path => ({json:async () => path.endsWith('/mark') ? {draftId:'marker'}
      : {previewId:'incomplete', summary:{text:'Summary alone is not a preview'}}});
  });
  await page.click('#report');
  await page.waitForFunction(() => !document.getElementById('report-preview').disabled);
  await page.click('#report-preview');
  await page.waitForFunction(() => !document.getElementById('report-preview').disabled);
  assert.equal(await page.isDisabled('#report-save'), true);
});
test('attention view explains wakes, pending wakes and the rule engine, filtered by search', async t => {
  const page = await dashboard(t);
  const result = await page.evaluate(() => {
    state.view = 'attention';
    const decision = (seqNo, type, delivery, stage, ruleId) => ({seqNo, tick: seqNo * 10, type, emitSemantic: true, delivery,
      urgency: 'LOW', stage, ruleId, reason: ruleId + ' reason', wakeProduced: delivery === 'IMMEDIATE'});
    addObservations([{sequence:1,type:'runtime_snapshot',capturedAtMs:1000,payload:{attention:{recorded:2,dropped:0,
      countsByRule:{'catalog.trigger':1,'ownership.reflex_actuation':1},
      latest:[decision(1,'pickup.item_picked_up','IMMEDIATE','RULES','catalog.trigger'),
        decision(2,'combat.damage_taken','NONE','FALLBACK','ownership.reflex_actuation')],
      rules:{module:'config:rules/attention.js',ready:true,steps:5,fallbacks:1,failures:1,clamps:2,reverts:0,rebuilds:0,stateBytes:2,lastFailure:'guest_error: bad'},
      scheduler:{pending:[{path:'W2',urgency:'HIGH',tick:30,eventRefs:[7],guidanceRevision:0}],retainedBy:'G5.run_policy'},
      plannerRules:{attention:{activeVersion:2,edits:[{version:1,kind:'update',reason:'mute pickups while building',tick:40},
        {version:2,kind:'rollback',reason:'too quiet',tick:90}]},salience:{activeVersion:0,edits:[]}}}}}]);
    const all = el('content').textContent;
    state.search = 'reflex'; render();
    return {all, filtered: el('content').textContent};
  });
  assert.match(result.all, /OVERRIDE/);
  assert.match(result.all, /guest_error: bad/);
  assert.match(result.all, /held by G5\.run_policy/);
  assert.match(result.all, /pickup\.item_picked_up/);
  assert.match(result.all, /wake · LOW/);
  assert.match(result.all, /attention v2 · salience base/);
  assert.match(result.all, /mute pickups while building/);
  assert.match(result.filtered, /combat\.damage_taken/);
  assert.doesNotMatch(result.filtered, /pickup\.item_picked_up/);
});
test('attention view hides raw-only decisions until the toggle is checked', async t => {
  const page = await dashboard(t);
  const result = await page.evaluate(() => {
    state.view = 'attention';
    const decision = (seqNo, type, ruleId) => ({seqNo, tick: seqNo, type, emitSemantic: false, delivery: 'NONE', urgency: 'LOW',
      stage: 'RULES', ruleId, reason: '', wakeProduced: false});
    addObservations([{sequence:1,type:'runtime_snapshot',capturedAtMs:1000,payload:{attention:{recorded:2,dropped:0,countsByRule:{},
      latest:[decision(1,'work.changed','catalog.raw_only'), decision(2,'pickup.item_picked_up','ownership.collect_resource_progress')],
      rules:{module:'bundled:attention/default.js',ready:true},scheduler:{pending:[]}}}}]);
    const hidden = el('content').textContent;
    const toggle = el('attention-raw');
    toggle.checked = true; toggle.dispatchEvent(new Event('change'));
    const shown = el('content').textContent;
    addObservations([{sequence:2,type:'visual_frame',capturedAtMs:1001,payload:{}}]);
    return {hidden, shown, stillChecked: el('attention-raw').checked, afterLive: el('content').textContent};
  });
  assert.doesNotMatch(result.hidden, /work\.changed/);
  assert.match(result.hidden, /1 raw-only hidden/);
  assert.match(result.hidden, /pickup\.item_picked_up/);
  assert.match(result.shown, /work\.changed/);
  assert.equal(result.stillChecked, true);
  assert.match(result.afterLive, /work\.changed/);
});
