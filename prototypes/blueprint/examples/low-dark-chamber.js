function design(input) {
  const root = Assembly({
    id: 'root',
    children: [
      // Spawning Chamber — sealed, dark, no windows
      Room({
        id: 'spawning',
        at: [-7, 0, 0],
        interior: [5, 3, 5],
        wall: 'stone_bricks',
        floor: 'black_concrete',
        guidance: { lighting: 'dark' }
      }),
      // Ceiling for spawning chamber
      Solid({
        id: 'spawningCeil',
        at: [-7, 4, 0],
        size: [7, 1, 7],
        material: 'stone_bricks'
      }),
      // Maintenance Room — lit, exterior door with walk access
      Room({
        id: 'maint',
        at: [2, 0, 0],
        interior: [7, 3, 5],
        wall: 'oak_planks',
        floor: 'stone_bricks',
        openings: {
          front: [
            Door({ id: 'frontDoor', at: [4, 0, 0], guidance: { access: 'walk' } })
          ]
        }
      }),
      // Ceiling for maintenance room
      Solid({
        id: 'maintCeil',
        at: [2, 4, 0],
        size: [9, 1, 7],
        material: 'oak_planks'
      }),
      // Glowstone lamp inside maintenance room (replaces a cell of the interior)
      Solid({
        id: 'lamp',
        at: [6, 3, 3],
        size: [1, 1, 1],
        material: 'glowstone',
        replaces: 'root.maint.interior'
      }),
      // Exterior step aligned with the front door
      Staircase({
        id: 'step',
        at: [6, 0, -1],
        rise: 1,
        width: 1,
        material: 'oak_stairs'
      })
    ]
  });
  return root;
}