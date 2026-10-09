// Wide roof supported only at the rear; the front edge needs temporary access.
function design(input) {
  const children = [
    Floor({ id: 'paving', at: [0, 0, 0], size: [11, 1, 7], material: 'stone_bricks' }),
    Solid({ id: 'roof', type: 'Roof', at: [0, 6, 0], size: [11, 1, 7], material: 'oak_slab[type=bottom]' }),
    Solid({ id: 'leftSupport', at: [1, 1, 6], size: [1, 5, 1], material: 'oak_planks' }),
    Solid({ id: 'rightSupport', at: [9, 1, 6], size: [1, 5, 1], material: 'oak_planks' }),
    Solid({ id: 'entryStep', at: [5, 0, -1], size: [1, 1, 1], material: 'stone_brick_stairs[facing=south]' })
  ];
  const walkable = [];
  for (let x = 0; x < 11; x++) for (let z = 0; z < 7; z++)
    if (!(z === 6 && (x === 1 || x === 9))) walkable.push([x, 0, z]);
  children.push(WalkableArea({ id: 'pavingClearance', surface: 'cantilever.paving', blocks: walkable }));
  return Assembly({ id: 'cantilever', guidance: { environment: 'outdoor', open: true }, children });
}
