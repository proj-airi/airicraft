function* main(os, config) {
  for (;;) {
    const bin = yield os.observe(config.scope);
    if (!bin.known || !bin.ready) { yield os.wait(config.scope); continue; }
    let action, args = {scope:config.scope};
    if (bin.level === 8 || bin.loot > 0) action='collect_compost';
    else if (bin.bonemeal > 0 && bin.fertilize.length) { action='fertilize_crops'; args.plot=bin.fertilize.includes('wheat') ? 'wheat' : bin.fertilize[0]; }
    else action='compost_seeds';
    const result = yield os.action(action,args);
    if (!result.ok) yield os.sleep(10000);
  }
}
