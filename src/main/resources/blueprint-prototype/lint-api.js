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
  function stair(c) {
    const steps=snapshot.cells.filter(b=>b.owner.startsWith(c.path+'.')&&b.state.includes('_stairs')).sort((a,b)=>a.position[1]-b.position[1]);
    if(!steps.length)return {known:false,reason:'No stair blocks emitted'};
    const first=steps[0],last=steps[steps.length-1],facing=/facing=(north|south|east|west)/.exec(first.state)?.[1];
    const dir={north:[0,0,-1],south:[0,0,1],east:[1,0,0],west:[-1,0,0]}[facing];
    if(!dir)return {known:false,reason:'Stair direction unavailable'};
    // Only a straight flight is inferred. Custom/turning staircases need their own rule.
    if(steps.some(s=>!/half=bottom/.test(s.state)||!s.state.includes('facing='+facing)))return {known:false,reason:'Only straight bottom-half flights are supported'};
    const lateral=p=>dir[0]?p[2]:p[0],axis=p=>dir[0]?p[0]*dir[0]:p[2]*dir[2];
    const lane=lateral(first.position),flight=steps.filter(s=>lateral(s.position)===lane);
    if(flight.some(s=>axis(s.position)-axis(first.position)!==s.position[1]-first.position[1]))return {known:false,reason:'Nonuniform or turning staircase'};
    const end=flight[flight.length-1].position,start=first.position;
    const distance=axis(end)-axis(start)+2,n=Math.ceil(distance/.2),targetY=end[1]+1;let previous=null;
    for(let i=0;i<=n;i++) {
      const d=-1+distance*i/n,x=start[0]+.5+dir[0]*d,z=start[2]+.5+dir[2]*d;
      const bs=boxes(x,z,start[1]-2,targetY+2);
      if(!bs)return {known:false,reason:'Stair route contains unknown geometry or fluid'};
      const heights=[...new Set(bs.filter(b=>x+.3>b[0]+.001&&x-.3<b[3]-.001&&z+.3>b[2]+.001&&z-.3<b[5]-.001&&b[4]<=targetY+.01).map(b=>b[4]))].sort((a,b)=>b-a);
      const y=heights.find(y=>clearAt(x,y,z,bs)&&(previous===null?Math.abs(y-start[1])<.61:Math.abs(y-previous)<.601));
      if(y===undefined)return {known:true,walkable:false,reason:'Missing support, excessive step, or blocked headroom along the flight',positions:[[Math.floor(x),Math.floor(previous??start[1]),Math.floor(z)]]};
      previous=y;
    }
    return {known:true,walkable:Math.abs(previous-targetY)<.01,reason:'The flight must end on a supported landing at its top height',positions:[[end[0]+dir[0],targetY,end[2]+dir[2]]]};
  }
  const owned=new Map(snapshot.cells.map(c=>[c.position.join(','),c]));
  const position=(c,p)=>{const q=rotate(p,c.rotation);return c.origin.map((n,i)=>n+q[i]);};
  // Collision witnesses include ownership; unknown cells affect only this body/support volume.
  function standing(p,width=.6) {
    const [x,y,z]=p,h=width/2,eps=.001,blockers=[],unknown=[];let support=false;
    for(let bx=Math.floor(x-h+eps);bx<=Math.floor(x+h-eps);bx++)
    for(let bz=Math.floor(z-h+eps);bz<=Math.floor(z+h-eps);bz++)
    for(let by=Math.floor(y-eps);by<=Math.floor(y+1.8-eps);by++) {
      const at=[bx,by,bz],v=voxel(at),cell=owned.get(at.join(','));
      if(!v||!v[3]||v[7]){unknown.push(at);continue;}
      for(const b of v[4]) {
        const q=[bx+b[0],by+b[1],bz+b[2],bx+b[3],by+b[4],bz+b[5]];
        if(x+h<=q[0]+eps||x-h>=q[3]-eps||z+h<=q[2]+eps||z-h>=q[5]-eps)continue;
        if(y+1.8>q[1]+eps&&y<q[4]-eps)blockers.push({position:at,owner:cell?.owner||null,state:cell?.state||null});
        if(Math.abs(y-q[4])<eps&&x>=q[0]&&x<=q[3]&&z>=q[2]&&z<=q[5])support=true;
      }
    }
    return {clear:!blockers.length&&support&&!unknown.length,possible:!blockers.length&&(support||unknown.length>0),blockers,unknown,support};
  }
  function connected(c) {
    const r=c.guidance.route,valid=p=>Array.isArray(p)&&p.length===3&&p.every(n=>Number.isInteger(n)&&Math.abs(n)<=128);
    if(!r||!valid(r.from)||!valid(r.to)||!Array.isArray(r.bounds)||r.bounds.length!==2||!r.bounds.every(valid)||r.bounds[0].some((v,i)=>v>r.bounds[1][i])||!Number.isFinite(r.width)||r.width<.6||r.width>4)
      return {known:false,reason:'Route requires integer local from/to feet coordinates, ordered bounds and width 0.6..4'};
    const corners=r.bounds.map(p=>position(c,p)),lo=corners[0].map((v,i)=>Math.min(v,corners[1][i])),hi=corners[0].map((v,i)=>Math.max(v,corners[1][i]));
    // Coordinates denote centers of transformed voxels, even under quarter turns.
    const point=p=>{const q=position(c,p);return [q[0]+.5,q[1],q[2]+.5];};
    const start=point(r.from),goal=point(r.to),inside=p=>p.every((v,i)=>v>=lo[i]+(i===1?0:.5)&&v<=hi[i]+(i===1?0:.5));
    if(!inside(start)||!inside(goal))return {known:false,reason:'Route endpoints are outside its declared search bounds'};
    const cache=new Map(),key=p=>p.join(','),sample=p=>{const k=key(p);if(!cache.has(k))cache.set(k,standing(p,r.width));return cache.get(k);};
    const endpoints=[start,goal].map(sample),evidence={method:'Bounded half-block walking graph; 0.5-step maximum, 1.8 headroom; no jumping',bounds:[lo,hi],width:r.width};
    for(let i=0;i<2;i++)if(!endpoints[i].possible)return {known:true,walkable:false,reason:'Route endpoint has blocked headroom or lacks support',positions:[(i?goal:start).map(Math.floor)],evidence:{...evidence,blockers:endpoints[i].blockers}};
    const frontier=new Map();
    function note(p,blockers) {
      const distance=p.reduce((n,v,i)=>n+Math.abs(v-goal[i]),0);
      for(const b of blockers){const k=b.position.join(','),old=frontier.get(k);if((!old&&frontier.size<1024)||(old&&distance<old.distance))frontier.set(k,{...b,distance});}
    }
    function search(optimistic) {
      if(!(optimistic?endpoints[0].possible:endpoints[0].clear)||!(optimistic?endpoints[1].possible:endpoints[1].clear))return {found:false};
      const queue=[start],parents=new Map([[key(start),null]]);let visits=0;
      for(let i=0;i<queue.length;i++) {
        const p=queue[i];if(++visits>16000)return {limit:true};
        if(key(p)===key(goal)) {const route=[];let k=key(p);while(k!==null){route.push(k.split(',').map(Number));k=parents.get(k);}return {found:true,route:route.reverse(),visits};}
        for(const [dx,dz] of [[.5,0],[-.5,0],[0,.5],[0,-.5]])for(const dy of [0,.5,-.5]) {
          const n=[p[0]+dx,p[1]+dy,p[2]+dz],k=key(n);if(!inside(n)||parents.has(k))continue;
          const v=sample(n);if(!(optimistic?v.possible:v.clear)){if(!optimistic)note(n,v.blockers);continue;}
          // Check a midpoint at the higher standing level: conservatively sweep the body.
          const mid=[(p[0]+n[0])/2,Math.max(p[1],n[1]),(p[2]+n[2])/2],m=sample(mid);
          if(m.blockers.length||(!optimistic&&m.unknown.length)){if(!optimistic)note(mid,m.blockers);continue;}
          parents.set(k,key(p));queue.push(n);
        }
      }return {found:false,visits};
    }
    const sure=search(false);
    if(sure.found)return {known:true,walkable:true,reason:'Connected route found within declared bounds',evidence:{...evidence,route:sure.route,visited:sure.visits}};
    if(sure.limit)return {known:false,reason:'Route search reached its 16000-state budget'};
    const maybe=search(true);
    if(maybe.found||maybe.limit)return {known:false,reason:'Unknown collision geometry or search budget prevents proving this connection'};
    return {known:true,walkable:false,reason:'No walking connection within the declared bounds; check steps, headroom and landings',positions:[r.from,r.to].map(p=>position(c,p)),evidence:{...evidence,blockers:[...frontier.values()].sort((a,b)=>a.distance-b.distance).slice(0,12).map(({distance,...b})=>b),visited:sure.visits}};
  }
  function protection(c) {
    const r=c.guidance.guardrail,s=byPath.get(r?.surface);
    if(!s||!['minX','maxX','minZ','maxZ'].includes(r?.edge)||!Number.isFinite(r.height)||r.height<.5||r.height>3)return {known:false,reason:'Guardrail requires an existing surface path, minX/maxX/minZ/maxZ edge, and height 0.5..3'};
    const cells=snapshot.cells.filter(b=>(b.owner===s.path||b.owner.startsWith(s.path+'.'))&&!b.state.startsWith('minecraft:air'));
    if(!cells.length)return {known:false,reason:'Protected surface has no final geometry'};
    const local=p=>rotate(p.map((n,i)=>n-s.origin[i]),(360-s.rotation)%360),points=cells.map(b=>local(b.position));
    const axis=r.edge.endsWith('X')?0:2,other=axis===0?2:0,sign=r.edge.startsWith('min')?-1:1;
    const boundary=(sign<0?Math.min:Math.max)(...points.map(p=>p[axis]));
    const edge=cells.filter((b,i)=>points[i][axis]===boundary),columns=new Map();
    for(const b of edge){const p=local(b.position),k=p[other],old=columns.get(k);if(!old||old.position[1]<b.position[1])columns.set(k,b);}
    let missing=[],uncertain=[];
    for(const b of columns.values()) {
      const v=voxel(b.position);
      if(!v?.[3]||!v[4].some(q=>q[0]===0&&q[2]===0&&q[3]===1&&q[5]===1&&q[4]===1)){uncertain.push(b.position);continue;}
      const d=[0,0,0];d[axis]=sign;const worldDir=rotate(d,s.rotation),base=b.position.map((n,i)=>n+worldDir[i]);base[1]++;
      // Conservative full-width barriers; fences/partial shapes remain explicitly unverified.
      let absent=false,unknownShape=false;
      for(let y=0;y<r.height;y+=.5){const p=[base[0],base[1]+y,base[2]],v=voxel(p),height=p[1]-Math.floor(p[1]);
        if(!v?.[3]||v[7]){unknownShape=true;continue;}
        if(!v[4].length){absent=true;continue;}
        if(!v[4].some(q=>q[0]===0&&q[2]===0&&q[3]===1&&q[5]===1&&q[1]<=height&&q[4]>=Math.min(1,height+.5)))unknownShape=true;
      }
      if(absent)missing.push(base);else if(unknownShape)uncertain.push(base);
    }
    return {known:true,positions:missing,unknown:uncertain,evidence:{surface:s.path,edge:r.edge,checkedColumns:columns.size,height:r.height},reason:'Guardrail must rise above the protected walking surface'};
  }

  const checked=new Set(),assessments=[];let tracked=false;
  const ctx={revision:snapshot.revision,
    components:({type}={})=>components.filter(c=>!type||c.type===type),
    position:(c,p)=>{const q=rotate(p,c.rotation);return c.origin.map((n,i)=>n+q[i]);},
    cells:c=>snapshot.cells.filter(b=>b.owner===c.path||b.owner.startsWith(c.path+'.')),
    block:p=>{const v=voxel(p);return v?{known:v[3],collisionBoxes:v[4],emission:v[5],estimatedBlockLight:v[6],fluid:v[7]}:{known:false};},
    track:()=>{tracked=true;}, checked:(c,result)=>{tracked=true;checked.add(c.path);if(result)assessments.push({component:c.path,...result});},
    access:{entrance,stair,connected}, protection,lighting:{darkWalkingSurfaces:lighting},
    warn:f=>report({...f,level:'warning'}),
    info:f=>report({...f,level:'info'}),
    unverified:(c,reason)=>report({component:c.path,level:'unverified',message:reason}),
  };
  check(ctx);return {findings,suppressed,assessments,applicability:{status:tracked?(checked.size?'checked':'not-applicable'):'unreported',checked:tracked?checked.size:null}};
}
