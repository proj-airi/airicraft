function design(input) {
  const root = Assembly({ id: 'compound' });

  // ── Courtyard: open-sky, 5×5 interior, centered ──
  const courtyard = Room({
    id: 'courtyard',
    at: [7, 0, 7],
    interior: [5, 3, 5],
    wall: 'oak_planks',
    floor: 'stone_bricks',
    guidance: { coverage: 'open' },
    openings: {
      front: [Door({ id: 'entry-door', at: [3, 0, 0], guidance: { access: 'walk' } })],
      left:  [Door({ id: 'west-passage', at: [2, 0, 0] })],
      right: [Door({ id: 'east-passage', at: [2, 0, 0] })]
    }
  });

  // ── Approach path: flat walkway from ground to courtyard entrance ──
  const entryPath = Solid({
    id: 'entry-path',
    at: [9, 0, 5],
    size: [3, 1, 2],
    material: 'stone_bricks'
  });

  // ── Wing A (west): covered, spruce, 5×5 interior ──
  const wingA = Room({
    id: 'wingA',
    at: [0, 0, 7],
    interior: [5, 3, 5],
    wall: 'spruce_planks',
    floor: 'polished_andesite',
    guidance: { lighting: 'expected' },
    openings: {
      right: [Door({ id: 'wingA-entry', at: [2, 0, 0] })],
      front: [Window({ id: 'wingA-win-front', at: [2, 0, 0], width: 3, height: 2 })],
      back:  [Window({ id: 'wingA-win-back',  at: [2, 0, 0], width: 3, height: 2 })]
    }
  });

  // ── Wing B (east): covered, birch, 5×5 interior ──
  const wingB = Room({
    id: 'wingB',
    at: [14, 0, 7],
    interior: [5, 3, 5],
    wall: 'birch_planks',
    floor: 'polished_andesite',
    guidance: { lighting: 'expected' },
    openings: {
      left:  [Door({ id: 'wingB-entry', at: [2, 0, 0] })],
      front: [Window({ id: 'wingB-win-front', at: [2, 0, 0], width: 3, height: 2 })],
      back:  [Window({ id: 'wingB-win-back',  at: [2, 0, 0], width: 3, height: 2 })]
    }
  });

  // ── Gable roofs over each wing (start at y=4, above 3-high walls) ──
  const roofA = GableRoof({ id: 'roofA', at: [0, 4, 7],  width: 7, depth: 7, material: 'dark_oak_planks' });
  const roofB = GableRoof({ id: 'roofB', at: [14, 4, 7], width: 7, depth: 7, material: 'dark_oak_planks' });

  // ── Night lighting: glowstone at ceiling height inside each wing ──
  const lightA = Solid({
    id: 'lightA', at: [3, 3, 10], size: [1, 1, 1], material: 'glowstone',
    replaces: 'compound.wingA.interior'
  });
  const lightB = Solid({
    id: 'lightB', at: [17, 3, 10], size: [1, 1, 1], material: 'glowstone',
    replaces: 'compound.wingB.interior'
  });

  root.children.push(courtyard, entryPath, wingA, wingB, roofA, roofB, lightA, lightB);
  return root;
}