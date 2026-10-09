#!/usr/bin/env python3
"""Support-surface advice: no Minecraft or model calls."""
from pathlib import Path
import json
from offline import Offline
HERE=Path(__file__).resolve().parent
results={}
def probe(name,extra='',material='stone',blocks=None,rotate=0):
    selection='' if blocks is None else ',blocks:'+json.dumps(blocks)
    source=f'''function design(){{return Assembly({{id:'s',rotate:{rotate},children:[Floor({{id:'floor',size:[3,1,3],material:'{material}'}}),{extra}WalkableArea({{id:'aisle',surface:'s.floor'{selection}}})]}});}}'''
    x=Offline();d=x.call({'op':'draft','source':source});r=x.call({'op':'lint'});assert all(v['status']=='ok' for v in r['rules']),r
    result=next(v['result'] for v in r['rules'] if v['id']=='walkable-area');results[name]=result
    assert not any(c['owner']=='s.aisle' for c in d['cells'])
    return result
assert not probe('clear')['findings']
f=probe('low-ceiling',"Solid({id:'beam',at:[1,2,1],size:[1,1,1],material:'stone'}),")['findings'];assert f[0]['level']=='warning' and f[0]['evidence']['blockers'][0]['owner']=='s.beam'
assert not probe('two-high',"Solid({id:'ceiling',at:[0,3,0],size:[3,1,3],material:'stone'}),")['findings']
f=probe('removed-support',"Clearance({id:'hole',at:[1,0,1],size:[1,1,1],replaces:'s.floor'}),")['findings'];assert any('support' in v['message'].lower() for v in f)
assert not probe('selected-aisle',"Solid({id:'furniture',at:[1,1,1],size:[1,2,1],material:'stone'}),",blocks=[[0,0,0],[0,0,1],[0,0,2]])['findings']
assert probe('rotated',"Solid({id:'beam',at:[1,2,1],size:[1,1,1],material:'stone'}),",rotate=90)['findings'][0]['positions']==[[-1,2,1]]
assert not probe('bottom-slab',material='stone_slab[type=bottom]')['findings']
assert probe('slab-low',"Solid({id:'beam',at:[1,2,1],size:[1,1,1],material:'stone'}),",material='stone_slab[type=bottom]')['findings']
assert not probe('stairs',material='oak_stairs[facing=south,half=bottom]')['findings']
assert probe('stairs-low',"Solid({id:'beam',at:[1,2,1],size:[1,1,1],material:'stone'}),",material='oak_stairs[facing=south,half=bottom]')['findings']
assert probe('unknown',material='iron_bars')['findings'][0]['level']=='unverified'
assert probe('invalid-selection',blocks=[])['findings'][0]['level']=='unverified'
assert not probe('no-cells-replaced')['findings']
# Applicability and suppression remain advisory; annotations do not change emitted cells.
for label,expected in [('before',True),('after',False)]:
    source=(HERE/f'examples/advice-walkable-{label}.js').read_text();x=Offline();d=x.call({'op':'draft','source':source});r=x.call({'op':'lint'})
    assert all(v['status']=='ok' for v in r['rules']),r
    assert bool(next(v['result']['findings'] for v in r['rules'] if v['id']=='walkable-area'))==expected
source="function design(){return Assembly({id:'s',children:[Floor({id:'f',size:[1,1,1],material:'stone'}),Solid({id:'beam',at:[0,2,0],size:[1,1,1],material:'stone'}),WalkableArea({id:'area',surface:'s.f',guidance:{disabledRules:['walkable-area'],suppressionReason:'intentional crawl space'}})]});}"
x=Offline();d=x.call({'op':'draft','source':source});r=next(v['result'] for v in x.call({'op':'lint'})['rules'] if v['id']=='walkable-area');assert not r['findings'] and len(r['suppressed'])==1
assert len(d['cells'])==2
out=HERE.parents[1]/'run/blueprint-evidence/rule-probes/walkable.json';out.write_text(json.dumps(results,indent=2))
print('PASS: floor selection, support deletion, two-block clearance, ownership, rotation, slabs, stairs, uncertainty; annotation emits no blocks')
