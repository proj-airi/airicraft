function* main(os, config) {
  for (;;) {
    const grove = yield os.observe(config.scope);
    if (!grove.known || !grove.ready) { yield os.wait(config.scope); continue; }
    const action = grove.loot.length ? 'collect_birch' : grove.harvestable.length ? 'harvest_birch' : 'plant_birch';
    const site = (grove.loot.length ? grove.loot : grove.harvestable.length ? grove.harvestable : grove.plantable)[0];
    const result = yield os.action(action,{scope:config.scope,site});
    if (!result.ok) yield os.sleep(15000);
  }
}
