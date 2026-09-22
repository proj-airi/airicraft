function work(os, config, world) {
  const herd = world[config.scope];
  if (!herd?.known || herd.escaped.length) return [];
  const eligible = [...herd.breedable].sort();
  const pairs = [];
  for (let i = 0; i + 1 < eligible.length; i += 2) {
    pairs.push(os.work('breed_sheep', { scope:config.scope, uuids:eligible.slice(i, i + 2) }, config.context));
  }
  return pairs;
}
