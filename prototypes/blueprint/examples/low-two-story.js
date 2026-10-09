function design(input) {
  const ground = Room({
    id: 'ground-floor',
    at: [0, -1, 0],
    interior: [7, 4, 7],
    wall: 'oak_planks',
    floor: 'stone_bricks',
    openings: {
      front: [
        Door({
          id: 'front-door',
          at: [4, 0, 0],
          material: 'oak_door',
          guidance: { access: 'walk' }
        }),
        Window({
          id: 'ground-window',
          at: [1, 1, 0],
          width: 3,
          height: 2
        })
      ]
    }
  });

  const upper = Room({
    id: 'upper-floor',
    at: [0, 4, 0],
    interior: [7, 4, 7],
    wall: 'oak_planks',
    floor: 'stone_bricks',
    openings: {
      front: [
        Window({
          id: 'upper-window',
          at: [4, 1, 0],
          width: 3,
          height: 2
        })
      ]
    },
    floorOpenings: [
      Clearance({
        id: 'stair-opening',
        at: [1, 0, 7],
        size: [2, 1, 1]
      })
    ]
  });

  const stairs = Staircase({
    id: 'stairs',
    at: [0, 0, 3],
    rise: 4,
    width: 2,
    material: 'oak_stairs'
  });

  const supportA = Solid({ id: 'stair-support-a', at: [0, 0, 4], size: [2, 1, 1], material: 'oak_planks' });
  const supportB = Solid({ id: 'stair-support-b', at: [0, 0, 5], size: [2, 2, 1], material: 'oak_planks' });
  const supportC = Solid({ id: 'stair-support-c', at: [0, 0, 6], size: [2, 3, 1], material: 'oak_planks' });

  // Ground floor: 3 lights covering front-left, front-right, back-center
  const lightG1 = Solid({ id: 'light-g1', at: [1, 3, 1], size: [1, 1, 1], material: 'sea_lantern' });
  const lightG2 = Solid({ id: 'light-g2', at: [5, 3, 1], size: [1, 1, 1], material: 'sea_lantern' });
  const lightG3 = Solid({ id: 'light-g3', at: [3, 3, 5], size: [1, 1, 1], material: 'sea_lantern' });

  // Upper floor: 3 lights covering front-left, front-right, back-right
  const lightU1 = Solid({ id: 'light-u1', at: [1, 3, 1], size: [1, 1, 1], material: 'sea_lantern' });
  const lightU2 = Solid({ id: 'light-u2', at: [5, 3, 1], size: [1, 1, 1], material: 'sea_lantern' });
  const lightU3 = Solid({ id: 'light-u3', at: [5, 3, 5], size: [1, 1, 1], material: 'sea_lantern' });

  const gInterior = ground.children.find(c => c.id === 'interior');
  gInterior.children.push(stairs, supportA, supportB, supportC, lightG1, lightG2, lightG3);

  const uInterior = upper.children.find(c => c.id === 'interior');
  uInterior.children.push(lightU1, lightU2, lightU3);

  const roof = GableRoof({
    id: 'roof',
    at: [0, 9, 0],
    width: 9,
    depth: 9
  });

  return Assembly({
    id: 'house',
    children: [ground, upper, roof]
  });
}