// Supply input.terrain from blueprint sample at the intended construction origin.
// Foundations fill only above observed ground; this fixture never excavates terrain.
function design(input) {
  const surface = input.terrain && input.terrain.surface;
  if (!surface) throw Error('Sample terrain before drafting this platform');
  const ground = (x, z) => {
    const y = surface[x + ',' + z];
    if (!Number.isInteger(y)) throw Error('Missing sampled terrain column ' + x + ',' + z);
    return y;
  };
  const heights = [];
  for (let x = 4; x <= 8; x++) for (let z = 4; z <= 10; z++) heights.push(ground(x, z));
  const deckY = Math.max(...heights) + 1;
  if (deckY - Math.min(...heights) > 6) throw Error('Choose a gentler site for this platform fixture');
  let approach;
  for (let rise = 1; rise <= 5; rise++) {
    const z = 4 - rise, base = ground(6, z) + 1;
    if (base + rise - 1 !== deckY) continue;
    if (Array.from({ length: rise }, (_, step) => ground(6, z + step) < base + step).every(Boolean)) {
      approach = { rise, z, base };
      break;
    }
  }
  if (!approach) throw Error('No clear front stair approach at this terrain anchor');
  const children = [
    Foundation({ id: 'foundation', at: [4, deckY, 4], size: [5, 1, 7], material: 'stone_bricks' }),
    Floor({ id: 'deck', at: [4, deckY, 4], size: [5, 1, 7], material: 'oak_planks' }),
    Staircase({ id: 'entryStairs', at: [6, approach.base, approach.z], rise: approach.rise, width: 1, material: 'oak_stairs' }),
    Solid({ id: 'leftRail', at: [4, deckY + 1, 4], size: [1, 1, 7], material: 'dark_oak_planks' }),
    Solid({ id: 'rightRail', at: [8, deckY + 1, 4], size: [1, 1, 7], material: 'dark_oak_planks' }),
    Solid({ id: 'backRail', at: [5, deckY + 1, 10], size: [3, 1, 1], material: 'dark_oak_planks' }),
    WalkableArea({ id: 'walkway', surface: 'terrain_platform.deck',
      blocks: Array.from({ length: 18 }, (_, i) => [1 + i % 3, 0, Math.floor(i / 3)]) })
  ];
  for (let step = 0; step < approach.rise; step++) {
    children.push(Foundation({ id: 'stairSupport' + step,
      at: [6, approach.base + step, approach.z + step], size: [1, 1, 1], material: 'stone_bricks' }));
  }
  return Assembly({ id: 'terrain_platform', guidance: { environment: 'outdoor', open: true }, children });
}
