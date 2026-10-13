// A Create hand crank driving a mechanical press through three shafts, with a depot to press onto.
// Run on flat ground. Nothing moves until the crank is used: call use_block on the crank with holdTicks
// (it turns only while clicked). The crank is attached to the end of the shaft line (its back faces the first
// shaft, so facing=north at the north end), not to a plain block: a crank on a non-kinetic block spins nothing.
// The press head needs one empty block between it and the depot below.
function design() {
  return Assembly({ id: 'crank_line', guidance: { environment: 'outdoor', open: true }, children: [
    Solid({ id: 'pillar', at: [0, 0, 1], size: [1, 2, 1], material: 'stone_bricks' }),
    Solid({ id: 'shaft1', at: [0, 2, 1], size: [1, 1, 1], material: 'create:shaft[axis=z]' }),
    Solid({ id: 'shaft2', at: [0, 2, 2], size: [1, 1, 1], material: 'create:shaft[axis=z]' }),
    Solid({ id: 'shaft3', at: [0, 2, 3], size: [1, 1, 1], material: 'create:shaft[axis=z]' }),
    Solid({ id: 'crank', at: [0, 2, 0], size: [1, 1, 1], material: 'create:hand_crank[facing=north]' }),
    Solid({ id: 'press', at: [0, 2, 4], size: [1, 1, 1], material: 'create:mechanical_press[facing=north]' }),
    Solid({ id: 'depotBase', at: [0, 0, 4], size: [1, 1, 1], material: 'create:depot' }),
    Clearance({ id: 'pressGap', at: [0, 1, 4], size: [1, 1, 1] })
  ]});
}
