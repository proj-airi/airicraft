#!/usr/bin/env python3
"""Small deterministic rule/authoring probes, requiring only Python and Node."""
from offline import Offline
from pathlib import Path
import json
out=Path(__file__).resolve().parents[2]/'run/blueprint-evidence/rule-probes'
out.mkdir(parents=True,exist_ok=True)
results={}
def source(step=False,light=False,dark=False,suppress=False):
    guidance={'lighting':'dark' if dark else 'expected'}
    if suppress:guidance.update(disabledRules=['room-lighting'],suppressionReason='unfinished example')
    return '''function design(){return Assembly({id:'site',children:[Room({id:'room',interior:[3,3,3],guidance:GUIDANCE,openings:{front:[Door({id:'entry',at:[2,0,0],guidance:{access:'walk'}})]},floorOpenings:LIGHT}),Solid({id:'roof',at:[0,4,0],size:[5,1,5],material:'stone'}) STEP]});}'''.replace('GUIDANCE',json.dumps(guidance)).replace('LIGHT',"[Solid({id:'light',at:[2,0,2],size:[1,1,1],material:'glowstone'})]" if light else '[]').replace('STEP',",Staircase({id:'step',at:[2,0,-1],rise:1,width:1})" if step else '')
def findings(v,id):return next(r['result'] for r in v['rules'] if r['id']==id)['findings']
x=Offline()
for name,opts in [('missing',{}),('fixed',{'step':True,'light':True}),('dark',{'step':True,'dark':True}),('light-in-dark',{'step':True,'dark':True,'light':True}),('suppressed',{'step':True,'suppress':True})]:
    x.call({'op':'draft','source':source(**opts)});results[name]=x.call({'op':'lint'})
assert findings(results['missing'],'entrance-access')
assert findings(results['missing'],'room-lighting')
assert not findings(results['fixed'],'entrance-access'),results['fixed']
assert not findings(results['fixed'],'room-lighting')
assert not findings(results['dark'],'room-lighting')
assert findings(results['light-in-dark'],'room-lighting')
assert not findings(results['suppressed'],'room-lighting')
assert results['suppressed']['rules'][1]['result']['suppressed']
before=x.call({'op':'get'})
results['isolation']=x.call({'op':'lint','rules':[{'id':'loop','source':'function check(ctx){while(true){}}'},{'id':'host','source':'function check(ctx){process.exit()}'},{'id':'healthy','source':"function check(ctx){ctx.info({component:ctx.components()[0].path,message:'still running'})}"}]})
assert [r['status'] for r in results['isolation']['rules']]==['error','error','ok']
assert x.call({'op':'get'})==before
house=x.call({'op':'draft','source':Path(__file__).with_name('house.js').read_text()})
assert house['cellCount']==1008
assert x.call({'op':'explain','position':[5,0,-1]})['owner']=='village.house.entranceSteps.step0'
results['house']=x.call({'op':'lint'})
assert not findings(results['house'],'entrance-access')
(out/'offline.json').write_text(json.dumps(results,indent=2))
print('PASS: missing/fixed entrance, lighting, intentional darkness, accidental light, scoped suppression, rule timeout/isolation, no mutation, house provenance')
