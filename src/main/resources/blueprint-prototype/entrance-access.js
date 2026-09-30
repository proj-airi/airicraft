function check(ctx) {
  ctx.track();
  for(const door of ctx.components().filter(c=>['Door','Entrance'].includes(c.type)||(c.guidance.role==='entrance'&&!ctx.components().some(p=>p.path===c.path.slice(0,c.path.lastIndexOf('.'))&&p.guidance.role==='entrance')))) {
    if(door.guidance.access!=='walk')continue;
    ctx.checked(door);
    const result=ctx.access.entrance(door);
    if(!result.known){ctx.unverified(door,result.reason);continue;}
    if(!result.walkable)ctx.warn({component:door.path,positions:result.positions,message:'Potentially awkward entrance: '+result.reason,evidence:{method:'local collision-shape approach',maxStep:0.6}});
  }
}
