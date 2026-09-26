(lib => ({
  step(input, state) {
    if (input.throwRule) throw Error('intentional rule failure');
    if (input.loopRule) while (true) {}
    const next = {
      bucket: state.bucket || {level: 0, tick: input.tick},
      windows: state.windows || {},
      cooldowns: state.cooldowns || {},
      hourly: state.hourly || {}
    };
    const owner = input.attention.actuatorOwner;
    const ownedTypes = new Set([
      'task.progress', 'inventory.changed', 'world.block_changed',
      'entity.nearby', 'weather.changed', 'goal.blocked'
    ]);
    const defaults = {
      'player.chat': 'HIGH', 'player.hurt': 'HIGH',
      'world.block_changed': 'LOW', 'task.progress': 'LOW',
      'entity.nearby': 'NORMAL', 'inventory.changed': 'LOW',
      'weather.changed': 'LOW', 'goal.blocked': 'HIGH'
    };
    const decisions = [];
    for (const event of input.events) {
      const urgency = input.plannerRules[event.type] || defaults[event.type] || event.urgency || 'LOW';
      let delivery = event.delivery || 'NEXT_BOUNDARY';
      let reason = 'catalog_default';
      let bucketAccepted = true;
      if (owner !== 'agent' && ownedTypes.has(event.type)) {
        delivery = 'LOG_ONLY'; reason = 'ownership_gate';
      } else if (input.attention.blockedGoal && event.type !== 'goal.blocked' && urgency !== 'HIGH') {
        delivery = 'LOG_ONLY'; reason = 'blocked_goal_gate';
      } else if (urgency === 'NORMAL' || urgency === 'LOW') {
        const cost = urgency === 'NORMAL' ? 1 : 0.5;
        const bucket = lib.leakyBucket(next.bucket, {capacity: 8, leakPerTick: 0.1, cost}, input.tick);
        next.bucket = bucket.state;
        bucketAccepted = bucket.accepted;
      }
      const windowLimit = event.type === 'world.block_changed' ? 8 : 120;
      const withinWindow = lib.slidingWindow(next.windows, event.type, input.tick, 120, windowLimit);
      const withinMinute = lib.tumblingWindow(next.windows, `${event.type}:minute`, input.tick, 1200, 100);
      const hourlyLimit = event.type === 'weather.changed' ? 30 : 60;
      const withinHour = lib.hourlyCap(next.hourly, event.type, input.tick, hourlyLimit);
      const offCooldown = lib.cooldown(next.cooldowns, event.type, input.tick, 20);
      if (delivery !== 'LOG_ONLY' && urgency !== 'HIGH') {
        if (!withinMinute || !withinHour) { delivery = 'LOG_ONLY'; reason = 'notice_cap'; }
        else if (!withinWindow) { delivery = 'LOG_ONLY'; reason = 'window_cap'; }
        else if (!offCooldown) { delivery = 'LOG_ONLY'; reason = 'cooldown'; }
        else if (!bucketAccepted) { delivery = 'LOG_ONLY'; reason = 'leaky_bucket'; }
      }
      decisions.push({seqNo: event.seqNo, delivery, urgency, reason});
    }
    const filtered = input.candidates.filter(candidate =>
      candidate.id !== 'minecraft:cobblestone' && candidate.id !== 'minecraft:dirt');
    const percepts = lib.cluster(filtered, 4).map(group => ({
      kind: group.candidate.kind, id: group.candidate.id,
      x: group.candidate.x, y: group.candidate.y, z: group.candidate.z,
      count: group.count, reason: 'visible_notable_candidate'
    }));
    return {
      percepts, decisions, state: next,
      probe: {now: Date.now(), date: new Date().getTime(), random: Math.random()}
    };
  }
}))
