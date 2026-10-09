// A tall single-column canopy exercises access above normal ground reach.
// Supply native sampled terrain; no permanent ladder or temporary block is authored.
function design(input) {
  const surface = input.terrain && input.terrain.surface;
  const ground = surface && surface['5,5'];
  if (!Number.isInteger(ground)) throw Error('Sample terrain before drafting the canopy');
  const base = ground + 1;
  return Assembly({ id: 'tall_canopy', guidance: { environment: 'outdoor', open: true }, children: [
    Solid({ id: 'column', type: 'Column', at: [5, base, 5], size: [1, 7, 1], material: 'stone_bricks' }),
    Solid({ id: 'roof', type: 'Roof', at: [4, base + 7, 4], size: [3, 1, 3], material: 'oak_planks' })
  ] });
}
