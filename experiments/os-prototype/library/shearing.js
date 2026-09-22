// Publish every eligible target. The runtime owns travel and the shared pen visit.
function work(os, config, world) {
  const herd = world[config.scope];
  if (!herd?.known || herd.escaped.length) return [];
  return herd.shearable.map(uuid => os.work('shear_sheep', { scope:config.scope, uuid }, config.context));
}
