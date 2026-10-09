// Separate recorded conversations, fed by the same timeline/export as the main dashboard.
let mount;
export function renderBlueprintDesigner(content,{observations,replay}) {
  if(content.dataset.view!=='blueprint-designer'||mount?.content!==content){
    content.dataset.view='blueprint-designer';
    content.innerHTML='<section class="card"><div class="card-head"><h2>Blueprint designer</h2></div><div class="card-body"><p data-designer-status></p><p class="muted">Separate recorded specialist conversations. Shows retained entries through the selected moment; older entries may have left the recording window.</p><div data-designer-transcript></div></div></section>';
    mount={content,key:''};
  }
  const key=replay+':'+observations.map(o=>o.sequence).join(',');if(mount.key===key)return;mount.key=key;
  const status=content.querySelector('[data-designer-status]'),root=content.querySelector('[data-designer-transcript]');
  status.textContent=(replay?'Recorded playback':'Live recording')+' · '+observations.length+' entries';
  const open=new Set([...root.querySelectorAll('details[open]')].map(e=>e.dataset.key));root.replaceChildren();
  const runs=new Map();
  for(const observation of observations){const e=observation.payload;let run=runs.get(e.runId);if(!run){run={meta:e,entries:[],status:'in progress / start not retained'};runs.set(e.runId,run);}run.entries.push({sequence:observation.sequence,...e});if(e.kind==='status')run.status=e.text;}
  if(!runs.size){const p=document.createElement('p');p.textContent='No designer entries recorded at this moment. New design runs appear here and in session exports.';root.append(p);}
  for(const [id,run] of runs){
    const section=document.createElement('section'),heading=document.createElement('h3');heading.textContent=run.status+' · '+run.meta.model+' · '+run.meta.blueprintId;section.append(heading);
    const label=document.createElement('p');label.className='muted';label.textContent='Run '+id;section.append(label);
    for(const e of run.entries){const row=document.createElement('details');row.dataset.key=String(e.sequence);row.open=open.has(row.dataset.key)||e.kind==='error';const summary=document.createElement('summary');summary.textContent=new Date(e.at).toLocaleTimeString()+' · '+e.kind+(e.truncated?' · truncated':'');const body=document.createElement('pre');body.textContent=e.text;row.append(summary,body);section.append(row);}
    root.append(section);
  }
}
