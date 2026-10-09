// Mixed collision shapes and directional furniture, with a step-in deck.
function design(input) {
  const children = [
    Solid({ id: 'entryStep', at: [3, 0, -1], size: [1, 1, 1], material: 'stone_brick_stairs[facing=south]' }),
    Floor({ id: 'deck', at: [0, 0, 0], size: [7, 1, 5], material: 'stone_bricks' }),
    Solid({ id: 'roof', type: 'Roof', at: [0, 4, 0], size: [7, 1, 5], material: 'oak_slab[type=bottom]' }),
    Solid({ id: 'leftBench', at: [1, 1, 2], size: [1, 1, 1], material: 'oak_stairs[facing=east]' }),
    Solid({ id: 'rightBench', at: [5, 1, 2], size: [1, 1, 1], material: 'oak_stairs[facing=west]' }),
    Solid({ id: 'tableLeg', at: [3, 1, 2], size: [1, 1, 1], material: 'oak_fence' }),
    Solid({ id: 'tableTop', at: [3, 2, 2], size: [1, 1, 1], material: 'oak_slab[type=bottom]' }),
    Solid({ id: 'lamp', at: [3, 3, 1], size: [1, 1, 1], material: 'lantern[hanging=true]' })
  ];
  for (const [i, x, z] of [[0, 0, 0], [1, 6, 0], [2, 0, 4], [3, 6, 4]]) {
    children.push(Solid({ id: 'post' + i, at: [x, 1, z], size: [1, 3, 1], material: 'oak_fence' }));
  }
  return Assembly({ id: 'pavilion', guidance: { environment: 'outdoor', open: true }, children });
}
