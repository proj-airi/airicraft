#!/usr/bin/env python3
"""Regression probes for advice derived from component meaning and final geometry."""
from pathlib import Path
from offline import Offline
import json
HERE=Path(__file__).resolve().parent
results={}
def lint(name,source):
    x=Offline();x.call({'op':'draft','source':source});r=x.call({'op':'lint'});results[name]=r
    assert all(t['status']=='ok' for t in r['rules']),r
    return {t['id']:t['result'] for t in r['rules']}
def roof_source(hole=False,open_air=False,rotate=0,material='stone'):
    return '''function design(){return Assembly({id:'site',rotate:ROT,children:[Room({id:'room',interior:[3,3,3],guidance:{coverage:INTENT}}),Solid({id:'roof',at:[0,4,0],size:[5,1,5],material:MAT,children:HOLE})]});}'''.replace('ROT',str(rotate)).replace('INTENT',json.dumps('open' if open_air else 'covered')).replace('MAT',json.dumps(material)).replace('HOLE',"[Clearance({id:'hole',at:[2,0,2],size:[1,1,1]})]" if hole else '[]')
assert not lint('covered',roof_source())['room-coverage']['findings']
f=lint('roof-hole',roof_source(hole=True))['room-coverage']['findings'];assert len(f)==1 and f[0]['evidence']['uncoveredColumns']==1
assert not lint('open-courtyard',roof_source(hole=True,open_air=True))['room-coverage']['findings']
assert not lint('rotated-covered',roof_source(rotate=90))['room-coverage']['findings']
f=lint('rotated-hole',roof_source(hole=True,rotate=90))['room-coverage']['findings'];assert f[0]['positions']==[[-2,4,2]]
f=lint('unknown-roof',roof_source(material='iron_bars'))['room-coverage']['findings'];assert [a['level'] for a in f]==['unverified']
old=(HERE/'examples/two-story.js').read_text();r=lint('qwen-two-story',old)
assert len(r['room-coverage']['findings'])==2
assert len(r['component-semantics']['findings'])==2
assert r['stair-access']['findings']
assert not lint('qwen-roof-only-repaired',old.replace('width: 6, depth: D','width: 10, depth: D'))['room-coverage']['findings']
r=lint('qwen-empty-components',(HERE/'examples/lit-room.js').read_text());assert len(r['component-semantics']['findings'])==2
house=(HERE/'house.js').read_text();bad=house.replace('at:[7,0,2],size:[2,1,4]','at:[7,0,3],size:[2,1,3]')
assert lint('stair-blocked-headroom',bad)['stair-access']['findings']
assert not lint('stair-clear-headroom',house)['stair-access']['findings']
suppressed=roof_source(hole=True).replace("coverage:\"covered\"","coverage:\"covered\",disabledRules:['room-coverage'],suppressionReason:'skylight work in progress'")
r=lint('scoped-suppression',suppressed)['room-coverage'];assert not r['findings'] and len(r['suppressed'])==1
out=HERE.parents[1]/'run/blueprint-evidence/rule-probes/semantics.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(results,indent=2))
print('PASS: full/holey/rotated/unknown/open roofs, coverage repair, empty semantics, ignored fields, stair headroom repair, scoped suppression')
