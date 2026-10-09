
function design(input) {
  const room = Room({
    id: 'warehouse-room',
    at: [0, 0, 0],
    interior: [11, 4, 9],
    wall: 'oak_planks',
    floor: 'stone_bricks',
    openings: {
      front: [
        Clearance({ id: 'entrance-opening', at: [5, 0, 0], size: [2, 3, 1] })
      ]
    }
  });

  const interiorPath = 'warehouse.warehouse-room.interior';
  const aislePath = 'warehouse.warehouse-room.central-aisle';
  const workAreaPath = 'warehouse.warehouse-room.work-area';

  const additions = [
    Solid({ id: 'shelf-left', at: [2, 1, 2], size: [2, 2, 5], material: 'oak_planks', replaces: [interiorPath] }),
    Solid({ id: 'shelf-right', at: [9, 1, 2], size: [2, 2, 5], material: 'oak_planks', replaces: [interiorPath] }),
    Clearance({ id: 'central-aisle', at: [5, 1, 1], size: [3, 4, 6], replaces: [interiorPath] }),
    Clearance({ id: 'left-access-path', at: [4, 1, 2], size: [1, 4, 5], replaces: [interiorPath] }),
    Clearance({ id: 'right-access-path', at: [8, 1, 2], size: [1, 4, 5], replaces: [interiorPath] }),
    Clearance({ id: 'work-area', at: [1, 1, 7], size: [11, 4, 3], replaces: [interiorPath] }),
    Solid({ id: 'light-front-left', at: [3, 4, 3], size: [1, 1, 1], material: 'glowstone', replaces: [interiorPath] }),
    Solid({ id: 'light-aisle-front', at: [6, 4, 2], size: [1, 1, 1], material: 'glowstone', replaces: [interiorPath, aislePath] }),
    Solid({ id: 'light-front-right', at: [9, 4, 3], size: [1, 1, 1], material: 'glowstone', replaces: [interiorPath] }),
    Solid({ id: 'light-aisle-mid', at: [6, 4, 5], size: [1, 1, 1], material: 'glowstone', replaces: [interiorPath, aislePath] }),
    Solid({ id: 'light-rear-left', at: [3, 4, 8], size: [1, 1, 1], material: 'glowstone', replaces: [interiorPath, workAreaPath] }),
    Solid({ id: 'light-rear-right', at: [9, 4, 8], size: [1, 1, 1], material: 'glowstone', replaces: [interiorPath, workAreaPath] })
  ];

  room.children.push(...additions);

  const roof = GableRoof({
    id: 'warehouse-roof',
    at: [0, 5, 0],
    width: 13,
    depth: 11
  });

  return Assembly({
    id: 'warehouse',
    children: [room, roof]
  });
}
