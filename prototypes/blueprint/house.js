// JavaScript builds semantic objects. Coordinates are local; front is -Z.
function design(input) {
  const base = input.terrain ? input.terrain.maxSurface + 1 : 0;
  const house = Assembly({id:'house',at:[0,base,0],children:[
    Room({id:'ground',interior:[9,3,7],openings:{
      front:[Door({id:'entrance',at:[5,0,0],guidance:{access:'walk'}}),Window({id:'window',at:[1,1,0],width:3,height:2})],
      left:[Window({id:'window',at:[2,1,0]})],right:[Window({id:'window',at:[2,1,0]})]
    }}),
    Room({id:'upper',at:[0,4,0],interior:[9,3,7],floor:'oak_planks',wall:'white_concrete',
      floorOpenings:[Clearance({id:'stairwell',at:[7,0,3],size:[2,1,3]})],openings:{
        front:[Window({id:'window',at:[3,0,0],width:5,height:2})],
        back:[Window({id:'window',at:[3,0,0],width:5,height:2})],
        left:[Window({id:'window',at:[2,0,0]})],right:[Window({id:'window',at:[2,0,0]})]
      }}),
    Staircase({id:'entranceSteps',at:[5,0,-1],rise:1,width:1,material:'stone_brick_stairs'}),
    Staircase({id:'stairs',at:[7,1,2],rise:4,replaces:['village.house.ground.interior','village.house.upper.floor.stairwell']}),
    GableRoof({id:'roof',at:[-1,8,-1],width:13,depth:11}),
    Solid({id:'landingLight',at:[1,4,1],size:[1,1,1],material:'glowstone',replaces:'village.house.upper.floor'})
  ]});
  return Assembly({id:'village',children:[
    ...(input.terrain?[Foundation({id:'foundation',at:[0,base,0],size:[11,1,9],terrain:input.terrain})]:[]),
    house
  ]});
}
