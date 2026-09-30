// Shared read-only queries. Rules define function check(ctx); return values are ignored.
function runRule(snapshot,input,check) {
  const findings=[],suppressed=[],grid=new Map(snapshot.geometry.map(v=>[v.slice(0,3).join(','),v]));
  const components=snapshot.components,byPath=new Map(components.map(c=>[c.path,c]));
  const voxel=p=>grid.get(p.map(Math.floor).join(','));
  const rotate=(p,r)=>r===90?[-p[2],p[1],p[0]]:r===180?[-p[0],p[1],-p[2]]:r===270?[p[2],p[1],-p[0]]:p;
  function report(f) {
    if(findings.length+suppressed.length>=48)throw Error('finding_limit');
    const c=byPath.get(f.component);if(!c)throw Error('unknown_component: '+f.component);
    if(typeof f.message!=='string'||f.message.length>1000)throw Error('invalid_message');
    const positions=(f.positions||[]).slice(0,24);
    if(positions.some(p=>!Array.isArray(p)||p.length!==3||p.some(n=>!Number.isFinite(n))))throw Error('invalid_positions');
    const item={component:c.path,level:f.level||'warning',message:f.message,positions,evidence:f.evidence||{}};
    if((c.guidance.disabledRules||[]).includes(input.ruleId))suppressed.push({...item,reason:c.guidance.suppressionReason||'component guidance'});
    else findings.push(item);
  }
  function boxes(x,z,lo,hi) {
    const result=[];
    for(let y=Math.floor(lo);y<=Math.floor(hi);y++)for(let bx=Math.floor(x-.3);bx<=Math.floor(x+.3);bx++)for(let bz=Math.floor(z-.3);bz<=Math.floor(z+.3);bz++) {
      const v=voxel([bx,y,bz]);if(!v||!v[3]||v[7])return null;
      for(const b of v[4])result.push([bx+b[0],y+b[1],bz+b[2],bx+b[3],y+b[4],bz+b[5]]);
    }return result;
  }
  function clearAt(x,y,z,bs) {return !bs.some(b=>x+.3>b[0]+.001&&x-.3<b[3]-.001&&z+.3>b[2]+.001&&z-.3<b[5]-.001&&y+1.8>b[1]+.001&&y<b[4]-.001);}
  function entrance(c) {
    const [ox,oy,oz]=c.origin,dir=rotate([0,0,-1],c.rotation);let previous=null;const route=[];
    for(let i=0;i<=15;i++) {
      const d=2-i*.2,x=ox+.5+dir[0]*d,z=oz+.5+dir[2]*d;
      const bs=boxes(x,z,oy-4,oy+3);if(!bs)return {known:false,reason:'Approach contains unsampled geometry or fluid',positions:[[Math.floor(x),oy,Math.floor(z)]]};
      const heights=[...new Set(bs.filter(b=>x+.3>b[0]+.001&&x-.3<b[3]-.001&&z+.3>b[2]+.001&&z-.3<b[5]-.001&&b[4]<=oy+.61).map(b=>b[4]))].sort((a,b)=>b-a);
      const possible=heights.filter(y=>clearAt(x,y,z,bs));
      const next=possible.find(y=>previous===null||y<=previous+.601&&y>=previous-.601);
      if(next===undefined)return {known:true,walkable:false,reason:possible.length?'Approach requires a jump or drop greater than 0.6 blocks':'Approach lacks standing clearance or support',positions:[[Math.floor(x),Math.floor(previous===null?oy:previous),Math.floor(z)]],route};
      previous=next;route.push([x,next,z]);
    }
    return {known:true,walkable:Math.abs(previous-oy)<.61,reason:'Local straight approach checked; wooden doors assumed operable',positions:[c.origin],route};
  }
  function lighting(c,threshold=8) {
    const samples=[];let unknown=0;
    for(const cell of snapshot.cells) {
      if(!cell.owner.startsWith(c.path+'.')||!cell.state.startsWith('minecraft:air'))continue;
      const p=cell.position,v=voxel(p),below=voxel([p[0],p[1]-1,p[2]]);
      if(!v||!below||!v[3]||!below[3]){unknown++;continue;}
      if(!below[4].some(b=>b[4]>=.99)||v[4].length)continue;
      samples.push({position:p,level:v[6]});
    }
    return {known:unknown===0&&samples.length>0,reason:unknown?'Incomplete captured geometry':samples.length?'Approximate artificial block-light only':'No walking surfaces identified',sampleCount:samples.length,minLevel:samples.length?Math.min(...samples.map(s=>s.level)):null,positions:samples.filter(s=>s.level<threshold).map(s=>s.position).slice(0,24),brightPositions:samples.filter(s=>s.level>0).map(s=>s.position).slice(0,24)};
  }
  const ctx={revision:snapshot.revision,
    components:({type}={})=>components.filter(c=>!type||c.type===type),
    cells:c=>snapshot.cells.filter(b=>b.owner===c.path||b.owner.startsWith(c.path+'.')),
    block:p=>{const v=voxel(p);return v?{known:v[3],collisionBoxes:v[4],emission:v[5],estimatedBlockLight:v[6],fluid:v[7]}:{known:false};},
    access:{entrance},lighting:{darkWalkingSurfaces:lighting},
    warn:f=>report({...f,level:'warning'}),
    info:f=>report({...f,level:'info'}),
    unverified:(c,reason)=>report({component:c.path,level:'unverified',message:reason}),
  };
  check(ctx);return {findings,suppressed};
}
