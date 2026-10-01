// Dedicated, read-only transcript. This does not use the main planner's conversation store.
let mount;
export function renderBlueprintDesigner(content,{api,replay}) {
  if(content.dataset.view==='blueprint-designer'&&mount?.content===content&&mount.replay===replay)return;
  if(mount)clearInterval(mount.timer);
  content.dataset.view='blueprint-designer';
  content.innerHTML='<section class="card"><div class="card-head"><h2>Blueprint designer</h2></div><div class="card-body"><p data-designer-status></p><p class="muted">Separate specialist conversation. Only the building brief, blueprint API and site/design evidence are supplied. Live session view.</p><div data-designer-transcript></div></div></section>';
  const root=content.firstElementChild,status=root.querySelector('[data-designer-status]'),transcript=root.querySelector('[data-designer-transcript]');
  const owner={content,replay,timer:null};mount=owner;let busy=false,last='';
  async function refresh(){
    if(mount!==owner||!root.isConnected){clearInterval(owner.timer);return;}
    if(replay){status.textContent='Return to LIVE to view the current designer session.';return;}
    if(busy||document.hidden)return;busy=true;
    try{const snapshot=await(await api('/api/blueprint')).json();if(mount!==owner||!root.isConnected)return;
      const d=snapshot.designer||{};status.textContent=`${d.status||'idle'} · ${d.model||'no worker started'}${d.blueprintId?' · '+d.blueprintId:''}`;
      const encoded=JSON.stringify(d.transcript||[]);if(last===encoded)return;last=encoded;
      const open=new Set([...transcript.querySelectorAll('details[open]')].map(e=>e.dataset.key));transcript.replaceChildren();
      for(const entry of d.transcript||[]){const row=document.createElement('details');row.dataset.key=entry.at+':'+entry.kind;row.open=open.has(row.dataset.key)||entry.kind==='error';const label=document.createElement('summary');label.textContent=new Date(entry.at).toLocaleTimeString()+' · '+entry.kind;const body=document.createElement('pre');body.textContent=entry.text;row.append(label,body);transcript.append(row);}
    }catch(e){status.textContent='Designer snapshot unavailable: '+e.message;}finally{busy=false;}
  }
  owner.timer=setInterval(refresh,2000);refresh();
}
