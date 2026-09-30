#!/usr/bin/env python3
"""Advisory relationship regression probes, no Minecraft or model calls."""
from pathlib import Path
import json
from offline import Offline
HERE=Path(__file__).resolve().parent
results={}
def lint(name,source):
    x=Offline();x.call({'op':'draft','source':source});r=x.call({'op':'lint'});results[name]=r
    assert all(v['status']=='ok' for v in r['rules']),r
    return {v['id']:v['result'] for v in r['rules']}
def route(extra='',to='[0,1,3]',rotate=0,width=.6):
    return f'''function design(){{return Assembly({{id:'site',rotate:{rotate},children:[
    Solid({{id:'deck',at:[-1,0,1],size:[3,1,3],material:'stone'}}),{extra}
    WalkRoute({{id:'access',from:[0,0,-2],to:{to},bounds:[[-2,0,-3],[2,3,4]],width:{width}}})]}});}}'''
r=lint('no-subjects',"function design(){return Solid({id:'block',size:[1,1,1],material:'stone'});}")
assert r['entrance-access']['applicability']['checked']==0
assert r['entrance-access']['applicability']['status']=='not-applicable'
r=lint('raised-path',route())['connected-route'];assert r['findings'][0]['level']=='warning'
step="Staircase({id:'step',at:[-1,0,0],rise:1,width:3}),"
assert not lint('step-repair',route(step))['connected-route']['findings']
assert not lint('rotated-route',route(step,rotate=90))['connected-route']['findings']
assert lint('wide-route',route(step,width=4))['connected-route']['findings']
assert lint('blocked-destination',route("Solid({id:'obstruction',at:[0,1,3],size:[1,2,1],material:'stone'}),"))['connected-route']['findings'][0]['evidence']['blockers']
multi="function design(){return Assembly({id:'s',children:[Staircase({id:'steps',rise:4,width:2}),Solid({id:'landing',at:[0,3,4],size:[2,1,2],material:'stone'}),WalkRoute({id:'route',from:[0,0,-1],to:[0,4,5],bounds:[[-1,0,-2],[2,5,6]]})]});}"
assert not lint('multi-step-route',multi)['connected-route']['findings']
assert lint('multi-step-ceiling',multi.replace("WalkRoute({", "Solid({id:'ceiling',at:[0,5,3],size:[2,1,3],material:'stone'}),WalkRoute({"))['connected-route']['findings']
# Same geometry, different semantic relationship: full cube rails must rise above the walking surface.
def rail(y=0,surface='site.deck',rotate=0):
    return f'''function design(){{return Assembly({{id:'site',rotate:{rotate},children:[Solid({{id:'deck',size:[5,1,3],material:'stone'}}),Guardrail({{id:'rail',surface:'{surface}',edge:'minZ',children:[Solid({{id:'blocks',at:[0,{y},-1],size:[5,1,1],material:'oak_planks'}})]}})]}});}}'''
assert lint('flush-rails',rail())['guardrail-protection']['findings'][0]['level']=='warning'
assert not lint('raised-rails',rail(1))['guardrail-protection']['findings']
assert not lint('rotated-rails',rail(1,rotate=90))['guardrail-protection']['findings']
assert lint('missing-surface',rail(1,'site.missing'))['guardrail-protection']['findings'][0]['level']=='unverified'
# An archway represented by clearance is an entrance too.
s="function design(){return Assembly({id:'s',children:[Solid({id:'floor',size:[3,1,3],material:'stone'}),Entrance({id:'arch',at:[1,1,0],width:1})]});}"
assert lint('clearance-entrance',s)['entrance-access']['findings']
# Original model outputs stay unchanged. Explicit route declarations are review overlays.
for name,start,end,bounds in [('watchtower',[4,0,-2],[4,7,4],[[-1,0,-3],[9,8,10]]),('warehouse',[5,0,-2],[5,1,8],[[-2,0,-3],[14,4,12]]),('courtyard',[10,0,3],[10,1,10],[[-2,0,2],[22,3,15]])]:
    source=(HERE/f'examples/variety-{name}.js').read_text().replace('function design(input)','function original(input)',1)
    source+='\nfunction design(input){const root=original(input);root.children.push(WalkRoute({id:"reviewRoute",from:'+json.dumps(start)+',to:'+json.dumps(end)+',bounds:'+json.dumps(bounds)+'}));return root;}'
    r=lint('qwen-'+name,source)['connected-route'];assert r['findings'][0]['level']=='warning',r
# Uncertainty stays local to the body's collision/support volume.
def unknown(y,blocked=False):
    extra="Solid({id:'wall',at:[0,0,2],size:[1,2,1],material:'stone'})," if blocked else ''
    return "function design(){return Assembly({id:'s',children:[Solid({id:'unknown',at:[0,"+str(y)+",0],size:[1,1,1],material:'stone_slab'}),"+extra+"WalkRoute({id:'path',from:[0,0,-2],to:[0,0,2],bounds:[[0,0,-2],[0,0,2]]})]});}"
assert lint('unknown-on-route',unknown(0))['connected-route']['findings'][0]['level']=='unverified'
assert not lint('unknown-above-route',unknown(3))['connected-route']['findings']
assert lint('known-blocker-despite-unknown',unknown(0,True))['connected-route']['findings'][0]['level']=='warning'
# Explicit exemptions still work, without mutating geometry.
source=route().replace("id:'access'","id:'access',guidance:{disabledRules:['connected-route'],suppressionReason:'intentional jump challenge'}")
r=lint('route-suppression',source)['connected-route'];assert not r['findings'] and len(r['suppressed'])==1
x=Offline();x.call({'op':'draft','source':"function design(){return Solid({id:'s',size:[1,1,1],material:'stone'});}"})
r=x.call({'op':'lint','rules':[{'id':'legacy','source':'function check(ctx) {}'}]})
assert r['rules'][0]['result']['applicability']['status']=='unreported'
for name,count in [('before',3),('after',0)]:
    r=lint('bridge-demo-'+name,(HERE/f'examples/advice-bridge-{name}.js').read_text())
    assert sum(len(v['findings']) for v in r.values())==count
    if name=='after':assert r['connected-route']['assessments'][0]['evidence']['route']
out=HERE.parents[1]/'run/blueprint-evidence/rule-probes/relations.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(results,indent=2))
print('PASS: applicability, routes, stairs, rotation, width, collision witnesses, guardrails, semantic entrance, Qwen replay')
