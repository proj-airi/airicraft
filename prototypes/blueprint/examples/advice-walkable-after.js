// Human-authored surface-intent demo. Only the center aisle is declared walkable.
function design() {
  return Assembly({id:'workshop',children:[
    Floor({id:'floor',size:[7,1,5],material:'stone_bricks'}),
    Solid({id:'shelvesLeft',at:[0,1,1],size:[2,2,3],material:'oak_planks'}),
    Solid({id:'shelvesRight',at:[5,1,1],size:[2,2,3],material:'oak_planks'}),
    Solid({id:'beam',at:[0,3,2],size:[7,1,1],material:'dark_oak_planks',replaces:['workshop.shelvesLeft','workshop.shelvesRight']}),
    WalkableArea({id:'aisle',surface:'workshop.floor',blocks:[[3,0,0],[3,0,1],[3,0,2],[3,0,3],[3,0,4]]}),
    WalkRoute({id:'throughAisle',from:[3,1,0],to:[3,1,4],bounds:[[3,1,0],[3,1,4]]})
  ]});
}
