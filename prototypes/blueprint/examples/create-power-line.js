// A Create water wheel driving a mechanical press through three shafts, with a depot to press onto.
// Run on flat ground; the wheel needs flowing water beside it (a source block at [1,3,0] flows down its side).
// Facings are neighbour-dependent in Create, so these were taken from live constructions (the wheel is placed
// first, on its own; the press goes beside the shafts); a facing the layout cannot produce fails with
// placement_state_requires_different_approach and should be redrafted with the opposite facing.
// The press head needs one empty block between it and the depot below.
function design() {
  return Assembly({ id: 'power_line', guidance: { environment: 'outdoor', open: true }, children: [
    Solid({ id: 'wheelPillar', at: [0, 0, 0], size: [1, 2, 1], material: 'stone_bricks' }),
    Solid({ id: 'wheel', at: [0, 2, 0], size: [1, 1, 1], material: 'create:water_wheel[facing=south]' }),
    Solid({ id: 'shaft1', at: [0, 2, 1], size: [1, 1, 1], material: 'create:shaft[axis=z]' }),
    Solid({ id: 'shaft2', at: [0, 2, 2], size: [1, 1, 1], material: 'create:shaft[axis=z]' }),
    Solid({ id: 'shaft3', at: [0, 2, 3], size: [1, 1, 1], material: 'create:shaft[axis=z]' }),
    Solid({ id: 'press', at: [0, 2, 4], size: [1, 1, 1], material: 'create:mechanical_press[facing=north]' }),
    Solid({ id: 'depotBase', at: [0, 0, 4], size: [1, 1, 1], material: 'create:depot' }),
    Clearance({ id: 'pressGap', at: [0, 1, 4], size: [1, 1, 1] })
  ]});
}
