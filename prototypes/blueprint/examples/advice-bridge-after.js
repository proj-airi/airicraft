function original(input) {
  const root = Assembly({
    id: 'root',
    children: [
      Solid({ id: 'ground-left', at: [-6, 0, 0], size: [6, 1, 7], material: 'grass_block' }),
      Solid({ id: 'ground-right', at: [9, 0, 0], size: [6, 1, 7], material: 'grass_block' }),
      Clearance({ id: 'trench', at: [0, -3, 0], size: [9, 3, 7] }),
      Assembly({
        id: 'bridge',
        children: [
          Solid({ id: 'deck', at: [0, 0, 2], size: [9, 1, 3], material: 'oak_planks' }),
          Assembly({
            id: 'rails',
            children: [
              Solid({ id: 'rail-north', at: [0, 0, 1], size: [9, 1, 1], material: 'dark_oak_planks' }),
              Solid({ id: 'rail-south', at: [0, 0, 5], size: [9, 1, 1], material: 'dark_oak_planks' }),
            ]
          }),
          Assembly({
            id: 'supports',
            children: [
              Solid({ id: 'support-1', at: [1, -3, 2], size: [1, 3, 3], material: 'stone_bricks', replaces: ['root.trench'] }),
              Solid({ id: 'support-2', at: [4, -3, 2], size: [1, 3, 3], material: 'stone_bricks', replaces: ['root.trench'] }),
              Solid({ id: 'support-3', at: [7, -3, 2], size: [1, 3, 3], material: 'stone_bricks', replaces: ['root.trench'] }),
            ]
          }),
        ]
      }),
    ]
  });
  return root;
}
// Human-added semantic review declarations; the geometry below repairs access/rails only, retaining the raised deck.
function design(input) {
  const root=original(input),bridge=root.children.find(c=>c.id==='bridge');
  const rails=bridge.children.find(c=>c.id==='rails').children;
  for(const [i,rail] of rails.entries()) {
    rail.type='Guardrail';rail.guidance={guardrail:{surface:'root.bridge.deck',edge:i?'maxZ':'minZ',height:1}};
  }
  root.children.push(WalkRoute({id:'crossing',from:[-8,0,3],to:[16,0,3],bounds:[[-9,0,0],[17,3,6]]}));
  for(const rail of rails)rail.at[1]=1;
  root.children.push(
    Staircase({id:'westApproach',at:[-7,0,4],rotate:270,rise:1,width:3}),
    Staircase({id:'eastApproach',at:[15,0,2],rotate:90,rise:1,width:3})
  );
  return root;
}
