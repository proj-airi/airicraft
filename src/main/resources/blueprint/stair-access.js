function check(ctx) {
  ctx.track();
  for(const stair of ctx.components({type:'Staircase'})) {
    if(stair.guidance.access==='decorative')continue;
    ctx.checked(stair);
    const r=ctx.access.stair(stair);
    if(!r.known){ctx.unverified(stair,r.reason);continue;}
    if(!r.walkable)ctx.warn({component:stair.path,positions:r.positions,message:'Potentially unusable staircase: '+r.reason,evidence:{method:'Straight center-lane support and 1.8-block headroom; local landing only'}});
  }
}
