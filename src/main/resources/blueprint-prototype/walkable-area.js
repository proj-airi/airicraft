function check(ctx) {
  ctx.track();
  for(const c of ctx.components({type:'WalkableArea'})) {
    const r=ctx.access.walkableArea(c);ctx.checked(c,r);
    if(!r.known){ctx.unverified(c,r.reason);continue;}
    if(r.missing.length)ctx.warn({component:c.path,message:'Walkable area has missing support blocks.',positions:r.missing.map(b=>b.position),evidence:{...r.evidence,missing:r.missing.slice(0,24)}});
    if(r.blockers.length)ctx.warn({component:c.path,message:'Walkable surface needs at least two blocks of vertical clearance.',positions:r.blockers.map(b=>b.position),evidence:{...r.evidence,blockers:r.blockers.slice(0,24)}});
    if(r.unknown.length)ctx.unverified(c,'Support or clearance is unverified at '+r.unknown.length+' coordinates because geometry is missing, partial, or unsupported');
  }
}
