function design(input) {
  const H = 3;
  const entryDoor = Door({ id: 'door', at: [0, 0, 0] });
  const ground = Room({
    id: 'ground', at: [0, -1, 0], interior: [7, H, 5],
    wall: 'oak_planks', floor: 'stone_bricks',
    openings: {
      front: [
        Entrance({ id: 'entry', at: [4, 0, 0], width: 1, height: 2, replaces: 'ground.front', children: [entryDoor] })
      ],
      back: [Window({ id: 'gwB1', at: [2, 1, 0] }), Window({ id: 'gwB2', at: [6, 1, 0] })],
      left: [Window({ id: 'gwL', at: [2, 1, 0] })],
      right: [Window({ id: 'gwR', at: [2, 1, 0] })]
    },
    guidance: { lighting: 'lantern at ceiling centre', access: 'walk-in from front door' }
  });

  const upper = Room({
    id: 'upper', at: [0, 3, 0], interior: [7, H, 5],
    wall: 'oak_planks', floor: 'oak_planks',
    openings: {
      front: [Window({ id: 'uwF1', at: [2, 1, 0] }), Window({ id: 'uwF2', at: [6, 1, 0] })],
      back: [Window({ id: 'uwB1', at: [2, 1, 0] }), Window({ id: 'uwB2', at: [6, 1, 0] })],
      left: [Window({ id: 'uwL', at: [2, 1, 0] })],
      right: [Window({ id: 'uwR', at: [2, 1, 0] })]
    },
    floorOpenings: [
      Clearance({ id: 'stairwell', at: [1, 0, 1], size: [2, 1, 3], replaces: 'upper.floor' })
    ],
    guidance: { lighting: 'lantern under ridge', access: 'stair head at z=4 beside the back wall' }
  });

  const roof = GableRoof({ id: 'roof', at: [0, 7, 0], width: 9, depth: 7, material: 'dark_oak_planks' });

  const stairs = Staircase({
    id: 'stairs', at: [1, 0, 1], rise: 4, width: 2,
    material: 'oak_stairs',
    replaces: ['oak_house.ground.interior', 'oak_house.upper.floor.stairwell', 'oak_house.upper.floor']
  });

  const lighting = [
    Solid({ id: 'lampGround', at: [4, 2, 3], size: [1, 1, 1], material: 'lantern[hanging=true]',
      replaces: 'oak_house.ground.interior' }),
    Solid({ id: 'lampUpper', at: [4, 4, 3], size: [1, 1, 1], material: 'lantern',
      replaces: 'oak_house.upper.interior' }),
    Solid({ id: 'lampStair', at: [4, 2, 5], size: [1, 1, 1], material: 'lantern[hanging=true]',
      replaces: 'oak_house.ground.interior' })
  ];

  // Support-block selections, in each floor's own frame (ground floor at local y=0,
  // upper floor at local y=3 for its room frame == y=0 in floor-local terms).
  const gBlocks = [], uBlocks = [];
  for (let x = 1; x <= 7; x++) for (let z = 1; z <= 5; z++) {
    if (!(z === 1 && x <= 2)) gBlocks.push([x, 0, z]);   // stair-foot bay omitted
    if (!((z >= 1 && z <= 3) && x <= 2) && !(x === 4 && z === 3)) uBlocks.push([x, 0, z]);   // stairwell void omitted
  }

  const walk = [
    WalkRoute({ id: 'routeEntry', from: [4, 0, 0], to: [3, 0, 1],
      bounds: [[0, 0, 0], [8, 1, 6]], guidance: { purpose: 'door to floor tile beside the stair base' } }),
    WalkRoute({ id: 'routeUpper', from: [2, 0, 4], to: [4, 0, 5],
      bounds: [[1, 0, 1], [7, 0, 5]], guidance: { purpose: 'stair head to upper room centre' } }),
    WalkableArea({ id: 'walkGround', surface: 'oak_house.ground.floor', blocks: gBlocks }),
    WalkableArea({ id: 'walkUpper', surface: 'oak_house.upper.floor', blocks: uBlocks })
  ];

  return Assembly({ id: 'oak_house', at: [0, 0, 0], children: [ground, upper, roof, stairs, ...lighting, ...walk] });
}