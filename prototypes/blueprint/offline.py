"""Low-memory offline session. No game, renderer, Java build or world mutations."""
import json, subprocess
from pathlib import Path
class Offline:
    def __init__(self): self.draft=None; self.revision=0
    def call(self,args):
        op=args['op']
        if op in ('get','lint','explain') and self.draft is None: raise ValueError('Compile a draft first')
        if op=='get': return self.draft
        if op=='explain':
            if args.get('world'): raise ValueError('Offline: use local coordinates')
            return next((c for c in self.draft['cells'] if c['position']==args['position']),{'specified':False})
        if op not in ('draft','lint'): raise ValueError('Offline mode: Minecraft operations are disabled')
        payload={**args,'revision':self.revision+1}
        if op=='lint':payload['draft']=self.draft
        run=subprocess.run(['node','--max-old-space-size=96',str(Path(__file__).with_name('offline.cjs'))],input=json.dumps(payload),text=True,capture_output=True,timeout=8)
        if not run.stdout:raise RuntimeError('Offline worker failed: '+run.stderr[:500])
        result=json.loads(run.stdout)
        if 'error' in result:raise ValueError(result['error'])
        if op=='draft':self.draft=result;self.revision=result['revision']
        return result
