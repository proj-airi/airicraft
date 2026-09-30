function design(input) {
  const doorX = 4;

  const room = Room({
    id: 'room',
    at: [0, 0, 0],
    interior: [7, 3, 5],
    wall: 'oak_planks',
    floor: 'stone_bricks',
    guidance: { lighting: 'expected' },
    openings: {
      front: [
        { id: 'entrance', type: 'Door', at: [doorX, 0, 0], material: 'oak_door', width: 1, height: 2, guidance: { access: 'walk' } },
        { id: 'window-front', type: 'Window', at: [6, 1, 0], material: 'glass', width: 2, height: 2 }
      ]
    }
  });

  const roof = GableRoof({ id: 'roof', at: [0, 4, 0], width: 7, depth: 9, material: 'dark_oak_planks' });
  const step = Staircase({ id: 'entrance-step', at: [doorX, 0, -1], rise: 1, width: 2, material: 'oak_stairs', rotate: 0 });
  const lantern = Solid({ id: 'lantern', at: [3, 1, 2], size: [1, 1, 1], material: 'lantern', replaces: ['dwelling', 'room', 'interior'] });

  return Assembly({ id: 'dwelling', at: [0, 0, 0], children: [room, roof, step, lantern] });
}