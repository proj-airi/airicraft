function check(ctx) {
  ctx.track();
  for(const c of ctx.components({type:'Guardrail'})) {
    const r=ctx.protection(c);
    ctx.checked(c,r);
    if(!r.known){ctx.unverified(c,r.reason);continue;}
    if(r.positions.length)ctx.warn({component:c.path,message:r.reason,positions:r.positions,evidence:r.evidence});
    if(r.unknown.length)ctx.unverified(c,'Barrier protection is uncertain in '+r.unknown.length+' edge columns (unsupported or partial collision shapes)');
  }
}
