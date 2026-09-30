// Throwaway offline adapter: semantic compiler + conservative, explicitly synthetic geometry.
// No Minecraft registry or engine is loaded. Unsupported shapes remain unknown.
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const resources=path.resolve(__dirname,'../../src/main/resources/blueprint-prototype');
const resource=name=>fs.readFileSync(path.join(resources,name),'utf8');
function evaluate(source,expression,input={}) {
  if(source.length>32768)throw Error('source_limit');
  const context=vm.createContext(Object.create(null),{codeGeneration:{strings:false,wasm:false}});
  return JSON.parse(vm.runInContext(`const input=JSON.parse(${JSON.stringify(JSON.stringify(input))});\n${source}\nJSON.stringify(${expression})`,context,{timeout:1000}));
}
const rot=(p,r)=>r===90?[-p[2],p[1],p[0]]:r===180?[-p[0],p[1],-p[2]]:r===270?[p[2],p[1],-p[0]]:p;
const add=(a,b)=>a.map((n,i)=>n+b[i]),vec=p=>{if(!Array.isArray(p)||p.length!==3||p.some(n=>!Number.isInteger(n)))throw Error('expected_xyz');return p;};
function state(text,r) {
  if(typeof text!=='string'||!/^([a-z0-9_]+:)?[a-z0-9_]+(\[[a-z0-9_=,]+\])?$/.test(text))throw Error('invalid_state: '+text);
  if(!text.includes(':'))text='minecraft:'+text;
  const directions=['north','east','south','west'];
  return text.replace(/facing=(north|east|south|west)/,(_,d)=>'facing='+directions[(directions.indexOf(d)+r/90)%4]);
}
function compile(tree,revision) {
  const cells=new Map(),components=[],paths=new Set();
  function visit(n,parent='',origin=[0,0,0],rotation=0,inherited=[],guidance={},depth=0) {
    if(depth>24||components.length>=512)throw Error('component_limit');
    if(typeof n.id!=='string'||!/^[A-Za-z0-9_-]{1,64}$/.test(n.id))throw Error('invalid_component_id');
    const p=parent?parent+'.'+n.id:n.id;if(paths.has(p))throw Error('duplicate_component: '+p);paths.add(p);
    const at=add(origin,rot(vec(n.at||[0,0,0]),rotation));if(!Number.isInteger(n.rotate||0)||(n.rotate||0)%90)throw Error('rotation_must_be_quarter_turn');
    const r=((rotation+(n.rotate||0))%360+360)%360,allowed=[...inherited,...(n.replaces?(Array.isArray(n.replaces)?n.replaces:[n.replaces]):[])];
    const g={...guidance,...n.guidance},info={path:p,type:n.type||'Component',origin:at,rotation:r,guidance:g};
    for(const k of ['interior','ignoredFields'])if(n[k])info[k]=n[k];
    if(n.anchors)info.anchors=Object.fromEntries(Object.entries(n.anchors).map(([k,v])=>[k,add(at,rot(vec(v),r))]));components.push(info);
    if(n.foundation)throw Error('offline_foundation_requires_a_terrain_fixture');
    if(n.volume){const size=vec(n.volume.size);if(size.some(v=>v<1)||size.reduce((a,b)=>a*b,1)>8192)throw Error('volume_limit');const s=state(n.volume.state,r);
      for(let y=0;y<size[1];y++)for(let z=0;z<size[2];z++)for(let x=0;x<size[0];x++) {
        const pos=add(at,rot([x,y,z],r)),key=pos.join(','),old=cells.get(key);
        if(pos.some(v=>Math.abs(v)>128)||(!old&&cells.size>=8192))throw Error('blueprint_bounds_limit');
        if(old&&!p.startsWith(old.owner+'.')&&!allowed.some(a=>old.owner===a||old.owner.startsWith(a+'.')))throw Error('component_conflict at '+key+': '+old.owner+' vs '+p);
        cells.set(key,{position:pos,state:s,owner:p,contributors:[...(old?.contributors||[]),p],ancestry:p.split('.').map((_,i)=>p.split('.').slice(0,i+1).join('.'))});
      }
    }
    for(const child of n.children||[])visit(child,p,at,r,allowed,g,depth+1);
  }visit(tree);const values=[...cells.values()],materials={};for(const c of values){const id=c.state.split('[')[0];if(id!=='minecraft:air')materials[id]=(materials[id]||0)+1;}
  return {revision,tree,components,cells:values,materials,cellCount:values.length,backend:'offline-fixture',registryValidated:false};
}
const cube=[0,0,0,1,1,1];
function shape(s){
  const id=s.split('[')[0].replace('minecraft:','');
  if(['air','cave_air'].includes(id))return {boxes:[],opaque:false,emission:0};
  if(id.endsWith('_door')&&id!=='iron_door')return {boxes:[],opaque:false,emission:0};
  if(id.endsWith('_stairs')&&!s.includes('shape=inner')&&!s.includes('shape=outer')){
    const facing=/facing=(\w+)/.exec(s)?.[1]||'north',upper=s.includes('half=top');
    const second={north:[0,0,0,1,1,.5],south:[0,0,.5,1,1,1],east:[.5,0,0,1,1,1],west:[0,0,0,.5,1,1]}[facing];
    return {boxes:[[0,upper?.5:0,0,1,upper?1:.5,1],second],opaque:false,emission:0};
  }
  const emission={glowstone:15,sea_lantern:15,shroomlight:15,jack_o_lantern:15,lantern:15,torch:14,soul_lantern:10,soul_torch:10}[id]||0;
  if(['torch','wall_torch','soul_torch'].includes(id))return {boxes:[],opaque:false,emission};
  if(/^(stone|stone_bricks|cobblestone|dirt|grass_block|sand|glass|glowstone|sea_lantern|shroomlight|jack_o_lantern)$/.test(id)||/^\w+_(planks|concrete|wool|stained_glass)$/.test(id))return {boxes:[cube],opaque:!id.includes('glass'),emission};
  return null;
}
function capture(draft){
  if(!draft.cells.length)throw Error('empty_blueprint');
  const min=[0,1,2].map(i=>Math.min(...draft.cells.map(c=>c.position[i]))-4),max=[0,1,2].map(i=>Math.max(...draft.cells.map(c=>c.position[i]))+4);
  if(max.reduce((a,v,i)=>a*(v-min[i]+1),1)>24000)throw Error('lint_snapshot_limit');
  const planned=new Map(draft.cells.map(c=>[c.position.join(','),c.state])),grid=new Map(),geometry=[],opaque=new Set(),queue=[];
  for(let y=min[1];y<=max[1];y++)for(let z=min[2];z<=max[2];z++)for(let x=min[0];x<=max[0];x++){
    const key=[x,y,z].join(','),s=planned.get(key)||(y<0?'minecraft:stone':'minecraft:air'),v=shape(s),row=[x,y,z,!!v,v?.boxes||[],v?.emission||0,v?.emission||0,false];
    geometry.push(row);grid.set(key,row);if(!v||v.opaque)opaque.add(key);if(row[5])queue.push(row);
  }
  for(let i=0;i<queue.length;i++){const v=queue[i];if(v[6]<=1)continue;for(const d of [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]]){const k=add(v.slice(0,3),d).join(','),n=grid.get(k);if(n&&!opaque.has(k)&&n[6]<v[6]-1){n[6]=v[6]-1;queue.push(n);}}}
  return {revision:draft.revision,components:draft.components,cells:draft.cells,geometry,lightingMethod:'Offline approximate block-light flood fill; no skylight or engine validation',accessMethod:'Offline straight stairs/full-cube fixture; open wooden doors; flat ground top y=0; unknown shapes unverified'};
}
function defaults(){return ['entrance-access','room-lighting','room-coverage','stair-access','component-semantics'].map(id=>({id,source:resource(id+'.js')}));}
function execute(a){
  if(a.op==='draft')return compile(evaluate(resource('components.js')+'\n'+a.source,'design(input)',{}),a.revision||1);
  if(a.op==='lint'){
    const s=capture(a.draft),rules=a.rules||defaults(),ids=new Set();if(!Array.isArray(rules)||rules.length>12)throw Error('rule_limit');
    for(const r of rules){if(!/^[a-zA-Z0-9_.-]{1,80}$/.test(r.id)||ids.has(r.id))throw Error('invalid_or_duplicate_rule_id');ids.add(r.id);}
    return {revision:s.revision,advisory:true,backend:'offline-fixture',lightingMethod:s.lightingMethod,accessMethod:s.accessMethod,rules:rules.map(r=>{try{return {id:r.id,status:'ok',result:evaluate(resource('lint-api.js')+'\n'+r.source,'runRule(input.snapshot,{ruleId:input.ruleId},check)',{snapshot:s,ruleId:r.id})};}catch(e){return {id:r.id,status:'error',message:e.message};}})};
  }
  throw Error('unsupported_offline_operation');
}
try{const a=JSON.parse(fs.readFileSync(0,'utf8'));process.stdout.write(JSON.stringify(execute(a)));}catch(e){process.stdout.write(JSON.stringify({error:e.message}));process.exitCode=1;}
