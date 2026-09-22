function work(os, config, world) {
  const stock = world[config.scope];
  if (!stock || !stock.known) return [];
  return stock.outputs.slice(0,16).map(([itemId]) =>
    os.work('store_output',{scope:config.scope,itemId},config.context));
}
