function check(ctx) {
  for(const room of ctx.components({type:'Room'})) {
    const intent=room.guidance.lighting;
    if(!['expected','dark'].includes(intent))continue;
    const result=ctx.lighting.darkWalkingSurfaces(room,room.guidance.minLight??8);
    if(!result.known){ctx.unverified(room,result.reason);continue;}
    const positions=intent==='dark'?result.brightPositions:result.positions;
    if(positions.length)ctx.warn({component:room.path,positions,message:intent==='dark'?'Possible unwanted artificial light in intentionally dark space.':'Some walking surfaces may need more artificial lighting.',evidence:{sampleCount:result.sampleCount,minEstimatedLight:result.minLevel,method:result.reason}});
  }
}
