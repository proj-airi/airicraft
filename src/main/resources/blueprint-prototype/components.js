// Throwaway semantic authoring library. Every constructor returns a component tree.
function Component({id, type='Component', at=[0,0,0], rotate=0, children=[], ...rest}) {
  return {id,type,at,rotate,children,...rest};
}
function Assembly(p) { return Component({type:'Assembly',...p}); }
function Solid({size, material, ...p}) { return Component({type:'Solid',volume:{size,state:material},...p}); }
function Clearance({size,...p}) { return Solid({type:'Clearance',size,material:'air',...p}); }
function Floor(p) { return Solid({type:'Floor',...p}); }
function Window({id,at=[0,0,0],width=3,height=2,material='glass',...p}) {
  return Solid({id,at,type:'Window',size:[width,height,1],material,...p});
}
function Door({id,at=[0,0,0],material='oak_door',...p}) {
  return Component({id,at,type:'Door',...p,children:[
    Solid({id:'lower',size:[1,1,1],material:material+'[facing=north,half=lower,hinge=left]'}),
    Solid({id:'upper',at:[0,1,0],size:[1,1,1],material:material+'[facing=north,half=upper,hinge=left]'})
  ]});
}
// Local front is z=0. Each opening is a descendant of its host wall and intentionally replaces it.
function Room({id,at=[0,0,0],rotate=0,interior=[9,3,7],wall='oak_planks',floor='stone_bricks',openings={},floorOpenings=[]}) {
  const [iw,h,idp]=interior,w=iw+2,d=idp+2;
  const Wall=(id,at,size,rotate,children=[])=>Solid({id,at,size,rotate,material:wall,type:'Wall',children});
  return Component({id,type:'Room',at,rotate,interior,anchors:{entrance:[Math.floor(w/2),1,0],above:[0,h+1,0]},children:[
    Floor({id:'floor',size:[w,1,d],material:floor,children:floorOpenings}),
    Clearance({id:'interior',at:[1,1,1],size:[iw,h,idp]}),
    Wall('front',[0,1,0],[w,h,1],0,openings.front),
    Wall('back',[w-1,1,d-1],[w,h,1],180,openings.back),
    Wall('left',[0,1,d-2],[d-2,h,1],270,openings.left),
    Wall('right',[w-1,1,1],[d-2,h,1],90,openings.right)
  ]});
}
function GableRoof({id,at=[0,0,0],width,depth,material='dark_oak_planks',rotate=0}) {
  return Component({id,type:'GableRoof',at,rotate,children:Array.from({length:Math.ceil(width/2)},(_,y)=>
    Component({id:'course'+y,at:[0,y,0],type:'RoofCourse',children:
      [...(y===width-1-y?[y]:[y,width-1-y]).map((x,i)=>Solid({id:'slope'+i,at:[x,0,0],size:[1,1,depth],material})),
      ...(width-2*y-2>0?[1,depth-2].map((z,i)=>Solid({id:'gable'+i,at:[y+1,0,z],size:[width-2*y-2,1,1],material:'white_concrete'})):[])]}))});
}
function Staircase({id,at=[0,0,0],rise=4,width=2,material='oak_stairs',rotate=0,replaces}) {
  return Component({id,at,rotate,replaces,type:'Staircase',children:Array.from({length:rise},(_,i)=>
    Solid({id:'step'+i,at:[0,i,i],size:[width,1,1],material:material+'[facing=south,half=bottom]'}))});
}
// Terrain is detached input: surface[x,z] is local ground height; source cells remain inspectable context.
function Foundation({id,at=[0,0,0],size,terrain,material='cobblestone'}) {
  return Component({id,at,type:'Foundation',foundation:{size,material}});
}
