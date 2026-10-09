// Roofed perimeter corridor around an open five-by-five courtyard.
function design(input) {
  const children = [];
  const pieces = [[0, 0, 9, 2], [0, 7, 9, 2], [0, 2, 2, 5], [7, 2, 2, 5]];
  for (const [i, [x, z, width, depth]] of pieces.entries()) {
    children.push(Floor({ id: 'walkway' + i, at: [x, 0, z], size: [width, 1, depth], material: 'stone_bricks' }));
    children.push(Solid({ id: 'eaves' + i, type: 'Roof', at: [x, 4, z], size: [width, 1, depth], material: 'oak_slab[type=bottom]' }));
  }
  for (const [i, x, z] of [[0, 0, 0], [1, 8, 0], [2, 0, 8], [3, 8, 8]])
    children.push(Solid({ id: 'column' + i, at: [x, 1, z], size: [1, 3, 1], material: 'oak_planks' }));
  children.push(Solid({ id: 'backWall', at: [1, 1, 8], size: [7, 3, 1], material: 'oak_planks' }));
  children.push(Solid({ id: 'leftWall', at: [0, 1, 1], size: [1, 3, 7], material: 'oak_planks' }));
  children.push(Solid({ id: 'rightWall', at: [8, 1, 1], size: [1, 3, 7], material: 'oak_planks' }));
  children.push(Solid({ id: 'frontLeft', at: [1, 1, 0], size: [3, 3, 1], material: 'oak_planks' }));
  children.push(Solid({ id: 'frontRight', at: [5, 1, 0], size: [3, 3, 1], material: 'oak_planks' }));
  children.push(Solid({ id: 'entryStep', at: [4, 0, -1], size: [1, 1, 1], material: 'stone_brick_stairs[facing=south]' }));
  children.push(Solid({ id: 'courtStep', at: [4, 0, 2], size: [1, 1, 1], material: 'stone_brick_stairs[facing=north]' }));
  for (const [i, [x, z, width, depth]] of pieces.entries()) {
    const blocks = [];
    for (let dx = 0; dx < width; dx++) for (let dz = 0; dz < depth; dz++)
      if ((x + dx > 0 && x + dx < 8 && z + dz > 0 && z + dz < 8) || (x + dx === 4 && z + dz === 0)) blocks.push([dx, 0, dz]);
    children.push(WalkableArea({ id: 'corridorClearance' + i, surface: 'courtyard.walkway' + i, blocks }));
  }
  return Assembly({ id: 'courtyard', guidance: { environment: 'outdoor', open: true }, children });
}
