// Narrow elevated lookout reached by a long stair approach.
function design(input) {
  const children = [
    Floor({ id: 'lookout', at: [8, 7, 0], size: [5, 1, 5], material: 'oak_planks' }),
    Solid({ id: 'roof', type: 'Roof', at: [8, 11, 0], size: [5, 1, 5], material: 'oak_slab[type=bottom]' }),
    Solid({ id: 'backRail', at: [12, 8, 1], size: [1, 1, 3], material: 'oak_planks' }),
    Solid({ id: 'northRail', at: [9, 8, 0], size: [3, 1, 1], material: 'oak_planks' }),
    Solid({ id: 'southRail', at: [9, 8, 4], size: [3, 1, 1], material: 'oak_planks' })
  ];
  for (let i = 0; i < 8; i++) children.push(Solid({ id: 'approach' + i, at: [i, i, 2], size: [1, 1, 1], material: 'oak_stairs[facing=east]' }));
  for (const [i, x, z] of [[0, 8, 0], [1, 12, 0], [2, 8, 4], [3, 12, 4]]) {
    children.push(Solid({ id: 'support' + i, at: [x, 0, z], size: [1, 7, 1], material: 'stone_bricks' }));
    children.push(Solid({ id: 'roofPost' + i, at: [x, 8, z], size: [1, 3, 1], material: 'oak_planks' }));
  }
  children.push(WalkableArea({ id: 'lookoutClearance', surface: 'watchtower.lookout', blocks: Array.from({ length: 9 }, (_, i) => [1 + i % 3, 0, 1 + Math.floor(i / 3)]) }));
  for (let i = 0; i < 8; i++) children.push(WalkableArea({ id: 'stairClearance' + i, surface: 'watchtower.approach' + i }));
  return Assembly({ id: 'watchtower', guidance: { environment: 'outdoor', open: true }, children });
}
