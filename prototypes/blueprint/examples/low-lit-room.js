function design(input) {
  const room = Room({
    id: 'room',
    at: [0, -1, 0],
    interior: [7, 3, 5],
    wall: 'oak_planks',
    floor: 'stone_bricks',
    openings: {
      front: [
        Door({
          id: 'entrance',
          at: [4, 0, 0],
          material: 'oak_door',
          guidance: { access: 'walk' }
        }),
        Window({
          id: 'window1',
          at: [1, 1, 0],
          width: 2,
          height: 1,
          material: 'glass'
        })
      ]
    }
  });

  const roof = GableRoof({
    id: 'roof',
    at: [0, 3, 0],
    width: 9,
    depth: 7,
    material: 'dark_oak_planks'
  });

  const light = Solid({
    id: 'lantern',
    at: [4, 1, 3],
    size: [1, 1, 1],
    material: 'lantern',
    replaces: 'dwelling.room.interior'
  });

  return Assembly({
    id: 'dwelling',
    at: [0, 0, 0],
    children: [room, roof, light]
  });
}