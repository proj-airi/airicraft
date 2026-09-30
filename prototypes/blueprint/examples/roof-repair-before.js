function design(input) {
  return Assembly({id:'dwelling',children:[
    Room({id:'room',at:[0,-1,0],interior:[7,3,5],openings:{front:[
      Door({id:'entrance',at:[4,0,0],guidance:{access:'walk'}}),
      Window({id:'window',at:[1,1,0],width:2,height:1})
    ]},floorOpenings:[Solid({id:'lamp',at:[4,0,3],size:[1,1,1],material:'glowstone'})]}),
    GableRoof({id:'roof',at:[0,3,0],width:5,depth:7})
  ]});
}