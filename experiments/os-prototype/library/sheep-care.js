function work(os, config, world) {
  const herd = world[config.scope];
  if (!herd?.known) return [];
  if (herd.escaped.length) return herd.wheat > 0
    ? [os.work('recover_sheep', { scope:config.scope, uuids:herd.escaped.slice(0,8) })] : [];
  const jobs = herd.cullable.map(uuid => os.work('cull_sheep', { scope:config.scope, uuid }, config.context));
  if (herd.loot) jobs.push(os.work('collect_wool', { scope:config.scope }, config.context));
  return jobs;
}
