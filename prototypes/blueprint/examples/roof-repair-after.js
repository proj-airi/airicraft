function design(input) {
  return Assembly({id:'dwelling',children:[
    Room({id:'room',at:[0,-1,0],interior:[7,3,5],openings:{front:[
      Door({id:'entrance',at:[4,0,0],guidance:{access:'walk'}}),
      Window({id:'window',at:[1,1,0],width:2,height:1})
    ]},floorOpenings:[Solid({id:'lamp',at:[4,0,3],size:[1,1,1],material:'glowstone'})]}),
    GableRoof({id:'roof',at:[0,3,0],width:9,depth:7}),
    Solid({id:'ceiling',at:[1,3,1],size:[7,1,5],material:'oak_planks',replaces:['dwelling.roof.course0.gable0','dwelling.roof.course0.gable1']})
  ]});
}