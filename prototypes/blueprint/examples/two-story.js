function design(input) {
  const D = 8; // 8x6 interiors + walls
  return Assembly({
    id: 'house',
    at: [0, 0, 0],
    children: [
      // ---------- Ground floor: 8x3x6 interior ----------
      Room({
        id: 'ground',
        at: [0, 0, 0],
        interior: [8, 3, 6],
        wall: 'oak_planks',
        floor: 'stone_bricks',
        guidance: { lighting: 'expected' },
        openings: {
          front: [
            Door({ id: 'extDoor', at: [5, 0, 0], material: 'oak_door', guidance: { access: 'walk' } }),
            Window({ id: 'winG1', at: [1, 1, 0], width: 2, height: 2 }),
            Window({ id: 'winG2', at: [7, 1, 0], width: 2, height: 2 })
          ],
          back: [
            Window({ id: 'winGB', at: [3, 1, 0], width: 3, height: 2 })
          ]
        },
        floorOpenings: [
          // Hole in ground floor under the stair landing (stairs occupy (6..7, 4..5))
          Clearance({ id: 'stairHole', at: [6, 0, 4], size: [2, 1, 2] })
        ],
        children: [
          // Artificial lighting in the ground room
          Solid({ id: 'lampG1', at: [3, 1, 3], size: [1, 1, 1], material: 'lava' }),
          Solid({ id: 'lampG2', at: [6, 1, 1], size: [1, 1, 1], material: 'lava' })
        ]
      }),

      // ---------- Interior staircase ----------
      // Steps at global (6,1,4),(6,2,5),(6,3,6); each replaces the interior air it displaces.
      Staircase({
        id: 'stairs',
        at: [6, 1, 4],
        rise: 3,
        width: 1,
        material: 'oak_stairs',
        rotate: 0,
        replaces: 'house.ground.interior'
      }),

      // ---------- Upper floor: 8x3x6 interior at y=4 ----------
      Room({
        id: 'upper',
        at: [0, 4, 0],
        interior: [8, 3, 6],
        wall: 'oak_planks',
        floor: 'stone_bricks',
        guidance: { lighting: 'expected' },
        openings: {
          front: [
            Window({ id: 'winU1', at: [1, 1, 0], width: 2, height: 2 }),
            Window({ id: 'winU2', at: [7, 1, 0], width: 2, height: 2 })
          ],
          back: [
            Window({ id: 'winUB', at: [3, 1, 0], width: 3, height: 2 })
          ]
        },
        floorOpenings: [
          // Matching hole aligned above the staircase landing
          Clearance({ id: 'upperHole', at: [6, 0, 4], size: [2, 1, 2] })
        ],
        children: [
          // Artificial lighting in the upper room
          Solid({ id: 'lampU1', at: [3, 5, 3], size: [1, 1, 1], material: 'lava' })
        ]
      }),

      // ---------- Gable roof ----------
      GableRoof({ id: 'roof', at: [0, 8, 0], width: 6, depth: D, material: 'dark_oak_planks' }),

      // ---------- Exterior approach: one-block-high step + level landing ----------
      // Landing top y=1 is level with the door threshold; walking on it needs no jump.
      Solid({ id: 'stepStone', at: [5, 0, -1], size: [1, 1, 1], material: 'cobblestone' }),
      Solid({ id: 'landingStone', at: [5, 1, -2], size: [1, 1, 1], material: 'cobblestone' })
    ]
  });
}
