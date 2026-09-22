function work(os, config, world) {
  const stock = world[config.scope];
  if (!stock || !stock.known) return [];
  return stock.demands.filter(d => d.ready && d.consumer === 'sheep').map(d =>
    os.work('ensure_stock',{scope:config.scope,itemId:d.itemId},config.context));
}
