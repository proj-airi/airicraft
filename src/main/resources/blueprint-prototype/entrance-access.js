function check(ctx) {
  for(const door of ctx.components({type:'Door'})) {
    if(door.guidance.access!=='walk')continue;
    const result=ctx.access.entrance(door);
    if(!result.known){ctx.unverified(door,result.reason);continue;}
    if(!result.walkable)ctx.warn({component:door.path,positions:result.positions,message:'Potentially awkward entrance: '+result.reason,evidence:{method:'local collision-shape approach',maxStep:0.6}});
  }
}
