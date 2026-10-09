// Construction fixture: flat-ground abutments, an elevated span, and opposing stair approaches.
// Origin Y is the first air block above level ground. Uses the house trial's supplied materials.
function design(input) {
  const children = [
    Solid({ id: 'westAbutment', at: [0, 0, 0], size: [1, 3, 3], material: 'stone_bricks' }),
    Solid({ id: 'eastAbutment', at: [8, 0, 0], size: [1, 3, 3], material: 'stone_bricks' }),
    Assembly({ id: 'deck', at: [0, 3, 0], children: [
      Floor({ id: 'walkway', at: [0, 0, 1], size: [9, 1, 1], material: 'oak_planks' }),
      Solid({ id: 'northBeam', at: [0, 0, 0], size: [9, 1, 1], material: 'oak_planks' }),
      Solid({ id: 'southBeam', at: [0, 0, 2], size: [9, 1, 1], material: 'oak_planks' })
    ] }),
    Clearance({ id: 'underpass', at: [1, 0, 0], size: [7, 3, 3] }),
    Solid({ id: 'northRail', type: 'Guardrail', guidance: { guardrail: { surface: 'raised_bridge.deck.walkway', edge: 'minZ', height: 1 } }, at: [0, 4, 0], size: [9, 1, 1], material: 'dark_oak_planks' }),
    Solid({ id: 'southRail', type: 'Guardrail', guidance: { guardrail: { surface: 'raised_bridge.deck.walkway', edge: 'maxZ', height: 1 } }, at: [0, 4, 2], size: [9, 1, 1], material: 'dark_oak_planks' }),
    Staircase({ id: 'westApproach', at: [-4, 0, 1], rise: 4, width: 1, rotate: 270, material: 'oak_stairs' }),
    Staircase({ id: 'eastApproach', at: [12, 0, 1], rise: 4, width: 1, rotate: 90, material: 'oak_stairs' }),
    WalkableArea({ id: 'crossing', surface: 'raised_bridge.deck.walkway', blocks: Array.from({ length: 9 }, (_, x) => [x, 0, 0]) })
  ];
  for (let height = 1; height <= 3; height++) {
    children.push(Solid({ id: 'westStairSupport' + height, at: [-4 + height, 0, 1], size: [1, height, 1], material: 'stone_bricks' }));
    children.push(Solid({ id: 'eastStairSupport' + height, at: [12 - height, 0, 1], size: [1, height, 1], material: 'stone_bricks' }));
  }
  return Assembly({ id: 'raised_bridge', guidance: { environment: 'outdoor', open: true }, children });
}
