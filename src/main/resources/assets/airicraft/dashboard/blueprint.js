// Read-only visualization of the Java-owned blueprint session. No compiler or mutation API.
let mounted=null;
export function renderBlueprint(content,{api,replay}) {
  if(content.dataset.view==='blueprint'&&mounted?.content===content&&mounted.replay===replay)return;
  if(mounted)clearInterval(mounted.timer);
  content.dataset.view='blueprint';
  content.innerHTML=`<section class="blueprint-view"><h2>Blueprint</h2><p class="muted">Current world session · read-only visualization. Drafts, rules and commits are owned by the mod.</p><p data-bp="status">Loading the published draft…</p><div class="bp-controls"><button data-bp="refresh">Refresh snapshot</button><label>View <select data-bp="mode"><option value="iso">Isometric</option><option value="slice">Horizontal slice</option></select></label><label>Layer <input data-bp="layer" type="number" value="1"></label><label><input data-bp="air" type="checkbox"> Explicit air</label><button data-bp="clear">Clear selection</button></div><canvas data-bp="canvas" aria-label="Blueprint block visualization"></canvas><p class="muted">Drag to rotate. Click a block or component to inspect ownership. The drawing uses schematic cubes; advice uses captured Minecraft collision shapes.</p><div class="bp-columns"><section><h3>Components</h3><div data-bp="tree"></div></section><section><h3>Advisory rules</h3><div data-bp="rules"></div></section></div><h3>Inspect a coordinate</h3><div class="bp-controls"><input data-bp="x" type="number" value="0" aria-label="Blueprint X"><input data-bp="y" type="number" value="0" aria-label="Blueprint Y"><input data-bp="z" type="number" value="0" aria-label="Blueprint Z"><button data-bp="inspect">Inspect coordinate</button></div><pre data-bp="detail">Select a block, component or finding.</pre><details><summary>Blueprint source (read-only)</summary><pre data-bp="source"></pre></details></section>`;
  const root=content.firstElementChild,$=id=>root.querySelector(`[data-bp="${id}"]`),canvas=$('canvas'),ctx=canvas.getContext('2d');
  const owner={content,replay,timer:null};mounted=owner;
  let draft=null,selected='',markers=new Set(),angle=.72,hits=[],last='',busy=false;
  const show=value=>{$('detail').textContent=JSON.stringify(value,null,2);};
  function button(parent,label,action){const b=document.createElement('button');b.textContent=label;b.onclick=action;parent.append(b);return b;}
  function annotationCells(){
    const c=draft?.components.find(c=>c.path===selected),r=c?.guidance?.walkable,s=draft?.components.find(c=>c.path===r?.surface),result=new Set();
    if(!s)return result;
    let blocks=r.blocks;if(!blocks&&s.volumeSize){const [w,h,d]=s.volumeSize;blocks=[];if(w*d<=1024)for(let x=0;x<w;x++)for(let z=0;z<d;z++)blocks.push([x,h-1,z]);}
    for(const p of blocks||[]){const [x,y,z]=p,q=s.rotation===90?[-z,y,x]:s.rotation===180?[-x,y,-z]:s.rotation===270?[z,y,-x]:p;result.add(q.map((n,i)=>n+s.origin[i]).join(','));}return result;
  }
  function draw(){
    const ratio=devicePixelRatio||1,W=canvas.clientWidth,H=canvas.clientHeight;canvas.width=W*ratio;canvas.height=H*ratio;ctx.setTransform(ratio,0,0,ratio,0,0);ctx.clearRect(0,0,W,H);hits=[];
    if(!draft)return;
    const slice=$('mode').value==='slice',supports=annotationCells();let cells=draft.cells.filter(c=>($('air').checked||!c.state.includes(':air'))&&(!slice||c.position[1]===Number($('layer').value)));
    if(!cells.length)return;
    const project=([x,y,z])=>slice?[x,z]:[x*Math.cos(angle)-z*Math.sin(angle),(x*Math.sin(angle)+z*Math.cos(angle))*.48-y*.9];
    const ps=cells.map(c=>project(c.position)),xs=ps.map(p=>p[0]),ys=ps.map(p=>p[1]),minX=Math.min(...xs),maxX=Math.max(...xs),minY=Math.min(...ys),maxY=Math.max(...ys),scale=Math.min(34,(W-60)/(maxX-minX+2),(H-50)/(maxY-minY+3));
    const screen=p=>{const q=project(p);return [W/2+(q[0]-(minX+maxX)/2)*scale,H/2+(q[1]-(minY+maxY)/2)*scale];};
    cells=cells.slice().sort((a,b)=>(a.position[0]*Math.sin(angle)+a.position[2]*Math.cos(angle)+a.position[1]*.01)-(b.position[0]*Math.sin(angle)+b.position[2]*Math.cos(angle)+b.position[1]*.01));
    for(const c of cells){const [x,y,z]=c.position,k=c.position.join(','),active=!selected||c.owner===selected||c.owner.startsWith(selected+'.')||markers.has(k)||supports.has(k),s=c.state;
      ctx.globalAlpha=active?(s.includes(':air')?.25:1):.13;ctx.fillStyle=markers.has(k)?'#ffb84d':supports.has(k)?'#73dbb1':s.includes('glass')?'#86cbd5':s.includes('dark_oak')?'#614537':s.includes('oak')?'#b58d57':s.includes('glowstone')?'#ffda76':s.includes(':air')?'#559fa0':'#82928e';ctx.strokeStyle='#102027';ctx.lineWidth=.6;
      const face=points=>{ctx.beginPath();points.forEach((p,i)=>{const [a,b]=screen(p);if(i)ctx.lineTo(a,b);else ctx.moveTo(a,b);});ctx.closePath();ctx.fill();ctx.stroke();};
      if(slice)face([[x,y,z],[x+1,y,z],[x+1,y,z+1],[x,y,z+1]]);else{face([[x,y,z+1],[x+1,y,z+1],[x+1,y+1,z+1],[x,y+1,z+1]]);face([[x+1,y,z],[x+1,y,z+1],[x+1,y+1,z+1],[x+1,y+1,z]]);face([[x,y+1,z],[x+1,y+1,z],[x+1,y+1,z+1],[x,y+1,z+1]]);}
      const [a,b]=screen([x+.5,y+(slice?0:1),z+.5]);hits.push({x:a,y:b,c,r:scale*.7});
    }ctx.globalAlpha=1;
  }
  function display(value){
    draft=value.draft||null;$('tree').replaceChildren();$('rules').replaceChildren();$('source').textContent=value.source||'';
    if(!draft){$('status').textContent=value.message||'No blueprint draft. Author a draft through the blueprint agent tool.';draw();return;}
    $('status').textContent=`Draft revision ${value.revision} · ${draft.cellCount} cells · last committed revision ${value.committedRevision||'none'}. ${value.lint?'Advice captured for revision '+value.lint.revision+'.':'Advice has not been run for this revision.'}`;
    for(const c of draft.components){const b=button($('tree'),c.path.split('.').at(-1)+' · '+c.type,()=>{selected=c.path;markers.clear();show(c);draw();});b.style.paddingLeft=(c.path.split('.').length-1)*10+8+'px';b.title=c.path;}
    for(const rule of value.lint?.rules||[]){const row=document.createElement('p'),a=rule.result?.applicability;row.textContent=rule.id+' · '+(rule.status==='error'?'failed: '+rule.message:a?.status==='not-applicable'?'no applicable subjects':a?.status==='checked'?a.checked+' checked':'applicability unreported');$('rules').append(row);
      for(const f of rule.result?.findings||[])button($('rules'),f.level+' · '+f.message,()=>{selected=f.component;markers=new Set((f.positions||[]).map(p=>p.join(',')));$('air').checked=true;show({rule:rule.id,revision:value.lint.revision,...f});draw();});
      if(rule.result?.suppressed?.length){const p=document.createElement('p');p.textContent=rule.result.suppressed.length+' suppressed by component guidance';$('rules').append(p);}
      if(rule.result?.assessments?.length)button($('rules'),'Inspect checks · '+rule.id,()=>show(rule.result.assessments));
    }draw();
  }
  async function refresh(){
    if(mounted!==owner||content.dataset.view!=='blueprint'||!root.isConnected){clearInterval(owner.timer);return;}
    if(replay){$('status').textContent='Blueprint snapshots are live session views, not part of historical playback. Return to LIVE to view the current draft.';return;}
    if(busy||document.hidden)return;busy=true;
    try{const value=await (await api('/api/blueprint')).json();if(mounted!==owner||!root.isConnected)return;const encoded=JSON.stringify(value);if(encoded!==last){last=encoded;display(value);}}
    catch(e){if(root.isConnected)$('status').textContent='Blueprint snapshot unavailable: '+e.message;}finally{busy=false;}
  }
  $('refresh').onclick=()=>{last='';refresh();};$('clear').onclick=()=>{selected='';markers.clear();draw();};
  for(const id of ['mode','layer','air'])$(id).onchange=draw;
  $('inspect').onclick=()=>{const p=['x','y','z'].map(id=>Number($(id).value)),cell=draft?.cells.find(c=>c.position.every((n,i)=>n===p[i]));show(cell||{position:p,specified:false});if(cell){selected=cell.owner;draw();}};
  let start=null,moved=false;canvas.onpointerdown=e=>{start=e.clientX;moved=false;canvas.setPointerCapture(e.pointerId);};canvas.onpointermove=e=>{if(start!==null&&$('mode').value==='iso'&&Math.abs(e.clientX-start)>2){angle+=(e.clientX-start)*.01;start=e.clientX;moved=true;draw();}};canvas.onpointerup=e=>{start=null;if(moved)return;const r=canvas.getBoundingClientRect(),h=hits.slice().reverse().find(h=>Math.hypot(h.x-e.clientX+r.left,h.y-e.clientY+r.top)<h.r);if(h){selected=h.c.owner;show(h.c);draw();}};
  const observer=new ResizeObserver(()=>{if(root.isConnected)draw();else observer.disconnect();});observer.observe(canvas);
  owner.timer=setInterval(refresh,2000);refresh();
}
