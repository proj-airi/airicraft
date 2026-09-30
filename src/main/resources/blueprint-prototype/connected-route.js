function check(ctx) {
  ctx.track();
  for(const c of ctx.components({type:'WalkRoute'})) {
    const r=ctx.access.connected(c);
    ctx.checked(c,r);
    if(!r.known)ctx.unverified(c,r.reason);
    else if(!r.walkable)ctx.warn({component:c.path,message:r.reason,positions:r.positions,evidence:r.evidence});
  }
}
