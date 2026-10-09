#!/usr/bin/env python3
"""Exploratory isolated Qwen tool loop; credentials read only from Airicraft config, never logged.
Uses the configured model/provider, not the embedded planner prompt or its scheduling loop.
Task file and budgets are fixed per run via environment; no manual source repair.
"""
import json, os, re, sys, time, hashlib, urllib.request, urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
from offline import Offline
call=Offline().call
HERE=Path(__file__).resolve().parent
OUT=ROOT/'run/blueprint-evidence'/os.environ.get('BLUEPRINT_TRIAL_ID','model-trial')
TOKEN_LIMIT=int(os.environ.get('BLUEPRINT_MODEL_TOKENS','4096'))
REASONING=os.environ.get('BLUEPRINT_REASONING')
REQUEST_TIMEOUT=int(os.environ.get('BLUEPRINT_REQUEST_TIMEOUT','240'))
assert 1<=REQUEST_TIMEOUT<=600
MAX_DESIGNS=int(os.environ.get('BLUEPRINT_MAX_DESIGNS','3'))
MAX_TURNS=int(os.environ.get('BLUEPRINT_MAX_TURNS','8'))
assert 1<=MAX_DESIGNS<=10 and 1<=MAX_TURNS<=20
OUT.mkdir(parents=True,exist_ok=True)
config=(ROOT/'run/config/airicraft/agent.yml').read_text()
def setting(name):
    value=re.search(r'^'+re.escape(name)+r':\s*(.+)$',config,re.M).group(1).strip()
    return json.loads(value) if value.startswith('"') else value
MODEL,BASE,KEY=setting('model'),setting('providerBaseUrl').rstrip('/'),setting('apiKey')
def tool(name,description,props={},required=[]):return {'type':'function','function':{'name':name,'description':description,'parameters':{'type':'object','properties':props,'required':required,'additionalProperties':False}}}
TOOLS=[tool('design','Compile and inspect a semantic blueprint. Returns compiler diagnostics or component summaries; no world mutation.',{'source':{'type':'string'}},['source']),tool('lint','Run the bundled advisory rules over the current draft and flat-world site.'),tool('inspect','Explain a local blueprint coordinate.',{'position':{'type':'array','items':{'type':'integer'},'minItems':3,'maxItems':3}},['position'])]
SYSTEM='''You are testing a semantic Minecraft blueprint interface. Produce original JavaScript defining function design(input) returning a component tree. Call design to compile, lint to inspect the result, repair if needed, then finish with a brief factual report. Maximum 3 design submissions and 8 model turns. No world mutation tools are available.
Each component has a unique sibling id, optional at:[x,y,z] relative to parent and rotate:0/90/180/270. Front is negative Z. Room interior dimensions exclude one-block walls; its floor is y=0 and interior starts y=1. Floors between stacked rooms: second room at y=first interior height+1. Room wall openings use local [horizontalOffset,heightAboveFloor-1,0]. Nested children replace a parent's volume; unrelated overlaps error unless replaces names the earlier owner path (or array of paths). Paths include the root id. Clearance explicitly emits air. A wooden Door starts at its lower-half position. Staircase facing south rises toward positive Z; each step is one block higher and one block farther along Z. For an exterior step aligned with a door at x=5,z=0, a one-step staircase at [5,0,-1] faces toward it. The floor top is y=1, exterior ground top y=0. Use full block states where needed. Solid can emit lights, with an explicit replaces path if replacing a floor or interior cell. Avoid overlaps by assigning slots deliberately.
Room guidance defaults to lighting=expected; set guidance lighting=dark for intentionally dark rooms. Tag exterior Door with guidance:{access:'walk'}. Guidance is inherited and overridable. Rules are advisory, but address unintentional findings. Lighting estimates are approximate block-light only; missing skylight is deliberate for night-time usability. Do not assert a whole-building route was proven by the local entrance check.
You have the component library below; compose these constructors or write new JS functions. No Java, network, filesystem, async, or imports. Do not use unavailable fluent methods or fictional constructors.
'''+(ROOT/'src/main/resources/blueprint/components.js').read_text()
SYSTEM=SYSTEM.replace('Maximum 3 design submissions and 8 model turns.',f'Maximum {MAX_DESIGNS} design submissions and {MAX_TURNS} model turns.')
SYSTEM+='\nThe bundled rules also check room coverage, straight staircase headroom/landings, empty semantic components, and ignored constructor fields. Use returned coordinates and component paths to diagnose failures. Keep occupied rooms covered; do not change their intent to evade advice. Use actual constructor calls, not merely type labels.'
SYSTEM+='\nDeclare meaningful relationships when appropriate: Entrance is an open walk-in doorway; WalkRoute({id,from,to,bounds,width:.6}) checks a bounded connection between local integer feet coordinates (X/Z voxel centers); Guardrail({id,surface,edge,height:1,children}) identifies the exact surface component path and its local minX/maxX/minZ/maxZ edge. A cube at y=0 has walking surface y=1. Route bounds must include the complete outside-to-inside or floor-to-platform connection. No applicable subjects means a rule did not check that intent. Inspect assessments for route witnesses and collision owners. These remain advisory; use the constructors as documented.'
SYSTEM+='\nWalkableArea({id,surface,blocks}) is a blueprint-only annotation on support blocks: surface is an exact component path, optional blocks selects integer support-block coordinates in that surface component frame; omitted blocks means the declared volume top layer. It checks preserved support and two blocks of clearance above actual slab/stair/full-block collision tops. Mark circulation surfaces, not furniture footprints. Pair it with WalkRoute for required connections.'
resource_dir=ROOT/'src/main/resources/blueprint'
resource_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in resource_dir.glob('*.js')}
TASKS=json.loads(Path(os.environ.get('BLUEPRINT_TASKS_FILE',str(HERE/'model-tasks.json'))).read_text())
selected=sys.argv[1:] or [t['id'] for t in TASKS]
for task in TASKS:
    if task['id'] not in selected:continue
    td=OUT/task['id'];td.mkdir(exist_ok=True)
    if (td/'transcript.json').exists():raise SystemExit('Refusing to overwrite an existing trial: '+str(td))
    origin=task['origin'];call=Offline().call
    messages=[{'role':'system','content':SYSTEM},{'role':'user','content':task['prompt']}]
    log={'backend':'offline-flat-fixture','modelRequested':MODEL,'provider':BASE,'task':task,'reasoningEffort':REASONING,'sampling':'provider defaults; no temperature override','maxCompletionTokens':TOKEN_LIMIT,'transportTimeoutSeconds':REQUEST_TIMEOUT,'maxTurns':MAX_TURNS,'maxDesigns':MAX_DESIGNS,'resourceHashes':resource_hashes,'systemPrompt':SYSTEM,'calls':[],'outcome':'turn_limit'}
    designs=0;last=None
    for turn in range(MAX_TURNS):
        payload={'model':MODEL,'messages':messages,'tools':TOOLS,'tool_choice':'auto','max_tokens':TOKEN_LIMIT,'stream':False}
        if REASONING:payload['reasoning_effort']=REASONING
        start=time.monotonic()
        try:
            request=urllib.request.Request(BASE+'/chat/completions',data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+KEY,'Content-Type':'application/json'})
            with urllib.request.urlopen(request,timeout=REQUEST_TIMEOUT) as response: result=json.load(response)
        except Exception as e:
            log['outcome']='provider_error';log['error']=str(e);log['errorSeconds']=round(time.monotonic()-start,2);break
        msg=result['choices'][0]['message'];messages.append({k:v for k,v in msg.items() if k in ['role','content','tool_calls','reasoning_content']})
        entry={'finishReason':result['choices'][0].get('finish_reason'),'turn':turn+1,'seconds':round(time.monotonic()-start,2),'modelReturned':result.get('model'),'usage':result.get('usage'),'message':msg,'results':[]};log['calls'].append(entry)
        (td/'transcript.json').write_text(json.dumps(log,indent=2))
        if not msg.get('tool_calls'):
            log['outcome']='output_limit' if result['choices'][0].get('finish_reason')=='length' else ('model_finished' if (msg.get('content') or '').strip() else 'empty_response');break
        for tc in msg['tool_calls']:
            try:
                args=json.loads(tc['function']['arguments']);name=tc['function']['name']
                if name=='design':
                    designs+=1
                    if designs>MAX_DESIGNS:raise ValueError('Design submission limit reached; finish with your observed limitations.')
                    (td/f'attempt-{designs}.js').write_text(args['source'])
                    last=call({'op':'draft','source':args['source']})
                    (td/'draft.json').write_text(json.dumps(last,indent=2))
                    (td/'final.js').write_text(args['source'])
                    value={'revision':last['revision'],'cellCount':last['cellCount'],'components':last['components'],'materials':last['materials']}
                elif name=='lint':value=call({'op':'lint'})
                elif name=='inspect':value=call({'op':'explain',**args})
                else:raise ValueError('Unknown tool')
            except Exception as e:value={'error':str(e)}
            entry['results'].append({'tool':tc['function']['name'],'result':value})
            messages.append({'role':'tool','tool_call_id':tc['id'],'content':json.dumps(value)})
            print(task['id'],'turn',turn+1,tc['function']['name'],str(value)[:240],flush=True)
        (td/'transcript.json').write_text(json.dumps(log,indent=2))
    if last:
        # Freeze the final compiled draft and independently collect the final advisory result.
        log['finalLint']=call({'op':'lint','origin':origin});log['compiled']=True;log['cellCount']=last['cellCount']
    else:log['compiled']=False
    log['designRequests']=designs;log['acceptedDesignSubmissions']=min(designs,MAX_DESIGNS);log['designSubmissions']=designs;(td/'transcript.json').write_text(json.dumps(log,indent=2))
    print('FINAL',task['id'],{k:log.get(k) for k in ['outcome','compiled','cellCount','designSubmissions']},flush=True)
