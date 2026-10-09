function design(input) {
  const root = Assembly({ id: 'watchtower', at: [0, 0, 0] });

  // ─── Ground floor room with entrance ───
  const room = Room({
    id: 'ground_room',
    at: [0, 0, 0],
    interior: [7, 3, 7],
    wall: 'oak_planks',
    floor: 'stone_bricks',
    openings: {
      front: [
        Door({ id: 'entrance_door', at: [4, 0, 0], material: 'oak_door', guidance: { access: 'walk' } })
      ]
    }
  });

  // ─── Staircase flight 1: 4 steps rising +Z, replaces interior air ───
  const flight1 = Staircase({
    id: 'stair_flight1',
    at: [6, 1, 1],
    rise: 4,
    width: 2,
    material: 'oak_stairs',
    replaces: 'watchtower.ground_room.interior'
  });

  // ─── Mid landing connecting the two flights ───
  const midLanding = Solid({
    id: 'mid_landing',
    at: [6, 4, 5],
    size: [2, 1, 2],
    material: 'stone_bricks',
    replaces: 'watchtower.ground_room.interior'
  });

  room.children.push(flight1, midLanding);

  // ─── Staircase flight 2: 2 steps above room to platform level ───
  const flight2 = Staircase({ id: 'stair_flight2', at: [6, 5, 7], rise: 2, width: 2, material: 'oak_stairs' });

  // ─── Observation platform (open to the sky) ───
  const platform = Assembly({
    id: 'observation_platform',
    at: [0, 6, 0],
    guidance: { environment: 'outdoor', open: true }
  });

  const pFloor = Solid({ id: 'floor', at: [0, 0, 0], size: [9, 1, 8], material: 'stone_bricks' });

  // Stairwell hatch: opens the platform floor above the stair approach for headroom
  const stairHatch = Clearance({
    id: 'stairwell_hatch',
    at: [6, 0, 6],
    size: [2, 1, 1],
    replaces: 'watchtower.observation_platform.floor'
  });

  // Guardrails: front/back span full width; left/right span z:1-6 to avoid corner overlap
  // Gap in back rail at x:6-7 for unobstructed stair arrival
  const pRails = [
    Solid({ id: 'rail_front', at: [0, 1, 0], size: [9, 1, 1], material: 'iron_bars' }),
    Solid({ id: 'rail_back_left', at: [0, 1, 7], size: [6, 1, 1], material: 'iron_bars' }),
    Solid({ id: 'rail_back_right', at: [8, 1, 7], size: [1, 1, 1], material: 'iron_bars' }),
    Solid({ id: 'rail_left', at: [0, 1, 1], size: [1, 1, 6], material: 'iron_bars' }),
    Solid({ id: 'rail_right', at: [8, 1, 1], size: [1, 1, 6], material: 'iron_bars' })
  ];

  // Night lighting on platform (glowstone replacing floor cells)
  const pLights = [
    Solid({ id: 'light_1', at: [1, 0, 1], size: [1, 1, 1], material: 'glowstone', replaces: 'watchtower.observation_platform.floor' }),
    Solid({ id: 'light_2', at: [7, 0, 1], size: [1, 1, 1], material: 'glowstone', replaces: 'watchtower.observation_platform.floor' }),
    Solid({ id: 'light_3', at: [1, 0, 6], size: [1, 1, 1], material: 'glowstone', replaces: 'watchtower.observation_platform.floor' })
  ];

  platform.children.push(pFloor, stairHatch, ...pRails, ...pLights);

  // ─── Exterior entrance step ───
  const extStep = Staircase({ id: 'entrance_step', at: [4, 0, -1], rise: 1, width: 1, material: 'oak_stairs' });

  // ─── Ground floor lights (two sources for adequate coverage) ───
  const lightGround1 = Solid({ id: 'light_ground_1', at: [1, 0, 1], size: [1, 1, 1], material: 'glowstone', replaces: 'watchtower.ground_room.floor' });
  const lightGround2 = Solid({ id: 'light_ground_2', at: [7, 0, 7], size: [1, 1, 1], material: 'glowstone', replaces: 'watchtower.ground_room.floor' });

  root.children.push(room, flight2, platform, extStep, lightGround1, lightGround2);

  return root;
}