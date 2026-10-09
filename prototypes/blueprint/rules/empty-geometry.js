// Example extension added after the Qwen trials: a semantic label alone emits no geometry.
// This was not part of the bundled feedback supplied during those trials.
function check(ctx) {
  for (const c of ctx.components()) {
    if (['Door','Window','Room','Staircase','GableRoof'].includes(c.type) && !ctx.cells(c).length) {
      ctx.warn({component:c.path, positions:[c.origin],
        message:c.type+' has no resulting blocks. Did you mean to call its constructor instead of only setting type?'});
    }
  }
}
