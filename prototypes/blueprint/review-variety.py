#!/usr/bin/env python3
"""Post-hoc geometry probes for the frozen variety briefs; never fed back to Qwen.
Conservative full-cube, integer-height checks. No Minecraft physics or stair path solver.
"""
import json,re,sys
from pathlib import Path
from collections import deque,Counter
HERE=Path(__file__).resolve().parent
RUN=Path(sys.argv[1]) if len(sys.argv)>1 else HERE.parents[1]/'run/blueprint-evidence/model-trial-variety-low-32k'
def material(state):return state.split('[')[0].removeprefix('minecraft:')
def full(state):
    s=material(state)
    return s in {'stone','polished_andesite','stone_bricks','cobblestone','dirt','grass_block','sand','glass','glowstone','sea_lantern','shroomlight','jack_o_lantern'} or bool(re.fullmatch(r'\w+_(planks|concrete|wool|stained_glass)',s))
def clear(state):
    s=material(state)
    return s in {'air','cave_air','torch','wall_torch','soul_torch'} or (s.endswith('_door') and s!='iron_door')
def flood(nodes,starts):
    seen=set(starts)&nodes;q=deque(seen)
    while q:
        x,z=q.popleft()
        for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
            p=(x+dx,z+dz)
            if p in nodes and p not in seen:seen.add(p);q.append(p)
    return seen
assert flood({(0,0),(1,0),(3,0)},[(0,0)])=={(0,0),(1,0)}
for td in sorted(RUN.iterdir()):
    if not (td/'draft.json').exists():continue
    d=json.loads((td/'draft.json').read_text());cells={tuple(c['position']):c for c in d['cells']}
    def state(x,y,z):return cells.get((x,y,z),{}).get('state','minecraft:stone' if y<0 else 'minecraft:air')
    def walk(x,y,z):return full(state(x,y-1,z)) and clear(state(x,y,z)) and clear(state(x,y+1,z))
    lo=[min(p[i] for p in cells) for i in range(3)];hi=[max(p[i] for p in cells) for i in range(3)]
    out={'scope':'Independent post-hoc full-cube/flat-route probes; wooden doors assumed open; polished_andesite treated as a full cube here only; unsupported shapes are not certified walkable','bounds':[lo,hi],'footprint':[hi[0]-lo[0]+1,hi[2]-lo[2]+1]}
    rooms=[c for c in d['components'] if c['type']=='Room']
    def world(c,p):
        x,y,z=p;r=c['rotation'];q={0:(x,y,z),90:(-z,y,x),180:(-x,y,-z),270:(z,y,-x)}[r]
        return tuple(a+b for a,b in zip(c['origin'],q))
    if td.name=='bridge':
        out['missingSupportAtRequestedDatum']=[[x,-1,z] for x in range(9) for z in range(2,5) if not full(state(x,-1,z))]
        out['blockedWalkingColumns']=[[x,0,z] for x in range(-1,10) for z in range(2,5) if not walk(x,0,z)]
        out['railGapsAtRequestedDatum']=[[x,0,z] for x in range(9) for z in [1,5] if not full(state(x,0,z))]
        actual={walk_y for x in range(9) for z in range(2,5) for walk_y in range(0,hi[1]+3) if walk(x,walk_y,z)}
        out['actualDeckWalkingHeights']=sorted(actual)
        out['railGapsAtActualDeckHeights']={y:[[x,y,z] for x in range(9) for z in [1,5] if not full(state(x,y,z))] for y in actual}
        out['openTrenchCellsBelowDeck']=sum(material(state(x,y,z))=='air' for x in range(9) for y in [-3,-2] for z in range(7))
    if td.name=='warehouse' and rooms:
        c=rooms[0];iw,h,dep=c['interior']
        def local_walk(x,z):return walk(*world(c,(x,1,z)))
        nodes={(x,z) for x in range(1,iw) for z in range(dep+1) if local_walk(x,z) and local_walk(x+1,z)}
        reached=flood(nodes,[(x,0) for x in range(1,iw)])
        out['twoWideFrontOpenings']=[list(world(c,(x,1,0))) for x in range(1,iw) if (x,0) in nodes]
        out['twoWideAisleReachesRear']=any(z>=dep-1 for x,z in reached)
        out['rearReachablePairCount']=sum(z>=dep-1 for x,z in reached)
        out['interiorDimensions']=c['interior']
        out['floorWalkingY']=c['origin'][1]+1
        out['frontApproaches']=[{'threshold':list(world(c,(x,1,0))),'outsideAtSameLevel':list(world(c,(x,1,-1))),'supportedAtSameLevel':walk(*world(c,(x,1,-1)))} for x in range(1,iw) if (x,0) in nodes]
    if td.name=='courtyard' and rooms:
        court=next((c for c in rooms if c['guidance'].get('coverage')=='open'),None)
        if court:
            y=court['origin'][1]+1
            nodes={(x,z) for x in range(lo[0]-2,hi[0]+3) for z in range(lo[2]-2,hi[2]+3) if walk(x,y,z)}
            center=world(court,(1+court['interior'][0]//2,1,1+court['interior'][2]//2));reached=flood(nodes,[(center[0],center[2])])
            out['courtyardInterior']=court['interior'];out['walkingLevel']=y
            out['courtReachesExteriorAtSameLevel']=any(x<lo[0] or x>hi[0] or z<lo[2] or z>hi[2] for x,z in reached)
            out['wingReachabilityAtSameLevel']={c['path']:(None if c['origin'][1]+1!=y else any((world(c,(x,1,z))[0],world(c,(x,1,z))[2]) in reached for x in range(1,c['interior'][0]+1) for z in range(1,c['interior'][2]+1))) for c in rooms if c is not court}
            out['approachBoundary']={'feet':[10,1,5],'surfaceY':1,'adjacentGroundFeet':[10,0,4],'adjacentGroundWalkable':walk(10,0,4),'sameHeightContinuation':walk(10,1,4)}
            out['courtyardCoveredColumns']=[list(world(court,(x,court['interior'][1]+1,z))) for x in range(1,court['interior'][0]+1) for z in range(1,court['interior'][2]+1) if any(full(state(*world(court,(x,y,z)))) for y in range(court['interior'][1]+1,hi[1]-court['origin'][1]+1))]
    if td.name=='watchtower':
        layers=Counter(p[1]+1 for p,c in cells.items() if full(c['state']) and walk(p[0],p[1]+1,p[2]))
        out['routeEvidence']=[{'position':list(p),'state':state(*p),'owner':cells.get(p,{}).get('owner')} for p in [(6,4,5),(6,5,5),(6,6,5),(6,6,6),(6,5,7),(6,6,7),(6,6,8),(6,6,9)]]
        out['flatStandingSurfaceCountsByHeight']=dict(sorted(layers.items()));out['withinRequestedFootprint']=all(n<=11 for n in out['footprint'])
    (td/'independent-review.json').write_text(json.dumps(out,indent=2));print(td.name,json.dumps(out))
