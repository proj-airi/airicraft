function check(ctx) {
  for(const c of ctx.components()) {
    if(c.ignoredFields?.length)ctx.warn({component:c.path,positions:[c.origin],message:'Unsupported fields were ignored: '+c.ignoredFields.join(', ')+'. Compose content with supported slots or sibling components; these fields produced no blocks.'});
    if(['Door','Window','Room','Staircase','GableRoof'].includes(c.type)&&!ctx.cells(c).length)
      ctx.warn({component:c.path,positions:[c.origin],message:c.type+' has no resulting blocks. Use its constructor or provide geometry; setting type alone only labels a component.'});
  }
}
