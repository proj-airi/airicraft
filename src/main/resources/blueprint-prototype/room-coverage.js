// A covered Room expects a solid cover above every column of its declared interior.
// Floors/ceilings/roofs can all provide cover; a roof's bounding box alone is insufficient.
function check(ctx) {
  const columns=new Map();
  const roots=ctx.components().filter(c=>!c.path.includes('.'));
  for(const root of roots)for(const cell of ctx.cells(root)) {
    if(cell.state.startsWith('minecraft:air'))continue;
    const p=cell.position,key=p[0]+','+p[2];if(!columns.has(key))columns.set(key,[]);columns.get(key).push(cell);
  }
  for(const room of ctx.components({type:'Room'})) {
    if(room.guidance.coverage==='open')continue;
    const size=room.interior;
    if(!Array.isArray(size)||size.length!==3||size.some(n=>!Number.isInteger(n)||n<1||n>128)){ctx.unverified(room,'Room interior dimensions are unavailable');continue;}
    let missing=[],unknown=[];
    for(let x=1;x<=size[0];x++)for(let z=1;z<=size[2];z++) {
      const p=ctx.position(room,[x,size[1]+1,z]),above=(columns.get(p[0]+','+p[2])||[]).filter(c=>c.position[1]>=p[1]);
      let covered=false,uncertain=false;
      for(const cell of above){const b=ctx.block(cell.position);if(!b.known){uncertain=true;continue;}
        if(b.collisionBoxes.some(a=>a[0]===0&&a[2]===0&&a[3]===1&&a[5]===1&&a[4]>a[1])){covered=true;break;}}
      if(!covered)(uncertain?unknown:missing).push(p);
    }
    if(missing.length)ctx.warn({component:room.path,positions:missing,message:missing.length+' of '+size[0]*size[2]+' interior columns have no full-width cover above them. Extend the roof/ceiling or mark this room coverage:"open".',evidence:{uncoveredColumns:missing.length,totalColumns:size[0]*size[2],method:'Vertical coverage of the declared room interior; actual final blocks, not roof bounds'}});
    if(unknown.length)ctx.unverified(room,'Coverage is uncertain in '+unknown.length+' columns because their block shapes are unsupported');
  }
}
