(() => ({
  leakyBucket(state, config, tick) {
    const elapsed = Math.max(0, tick - (state.tick ?? tick));
    const level = Math.max(0, (state.level ?? 0) - elapsed * config.leakPerTick);
    const accepted = level + config.cost <= config.capacity;
    return {accepted, state: {level: accepted ? level + config.cost : level, tick}};
  },
  slidingWindow(state, key, tick, windowTicks, limit) {
    const times = (state[key] || []).filter(t => tick - t < windowTicks);
    const accepted = times.length < limit;
    if (accepted) times.push(tick);
    state[key] = times;
    return accepted;
  },
  tumblingWindow(state, key, tick, windowTicks, limit) {
    const period = Math.floor(tick / windowTicks);
    const previous = state[key];
    const count = previous && previous.period === period ? previous.count : 0;
    const accepted = count < limit;
    state[key] = {period, count: count + (accepted ? 1 : 0)};
    return accepted;
  },
  cooldown(state, key, tick, ticks) {
    if (tick - (state[key] ?? -Infinity) < ticks) return false;
    state[key] = tick;
    return true;
  },
  hourlyCap(state, key, tick, cap) {
    return this.slidingWindow(state, key, tick, 72_000, cap);
  },
  cluster(candidates, radius) {
    const groups = new Map();
    for (const candidate of candidates) {
      const key = candidate.kind === 'block'
        ? `${candidate.id}:${Math.floor(candidate.x / radius)}:${Math.floor(candidate.z / radius)}`
        : `${candidate.kind}:${candidate.id}`;
      const prior = groups.get(key);
      if (prior) prior.count++;
      else groups.set(key, {candidate, count: 1});
    }
    return Array.from(groups.values());
  },
  seededRandom(seed) {
    let value = (seed >>> 0) || 1;
    return () => {
      value = (Math.imul(value, 1664525) + 1013904223) >>> 0;
      return value / 4294967296;
    };
  }
}))
