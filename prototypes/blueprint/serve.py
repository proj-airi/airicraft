#!/usr/bin/env python3
"""THROWAWAY localhost UI. Calls only the public Codex-driver CLI."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import json, os, subprocess, threading
ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CLI = ROOT / 'wrapper/build/install/airicraft/bin/airicraft'
LOCK = threading.Lock()
OFFLINE = os.environ.get('BLUEPRINT_OFFLINE') == '1'
if OFFLINE:
    from offline import Offline
    offline = Offline()
def call(args):
    if OFFLINE: return offline.call(args)
    env = dict(os.environ)
    env.setdefault('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')
    run = subprocess.run([str(CLI),'agent','tools','call','--name','blueprint_prototype','--arguments',json.dumps(args),'--verbose'],cwd=ROOT,env=env,text=True,capture_output=True,timeout=130)
    if run.returncode: raise RuntimeError(run.stdout+run.stderr)
    for line in run.stdout.splitlines():
        if line.startswith('result: '):
            value=line[8:]
            if value.startswith('"'): value=json.loads(value)
            if value.startswith('TOOL_ERROR'): raise RuntimeError(value)
            return json.loads(value)
    raise RuntimeError(run.stdout)
class Handler(BaseHTTPRequestHandler):
    def send(self,code,data,mime='application/json'):
        body=data if isinstance(data,bytes) else json.dumps(data).encode()
        self.send_response(code);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_GET(self):
        path=self.path.split('?')[0]
        if path=='/examples':
            examples=[{'id':p.stem,'source':p.read_text()} for p in sorted((HERE/'examples').glob('*.js'))]
            self.send(200,examples);return
        if path=='/mode':self.send(200,{'offline':OFFLINE});return
        files={'/':'index.html','/house.js':'house.js'}
        if path in files:self.send(200,(HERE/files[path]).read_bytes(),'text/html' if path=='/' else 'text/plain')
        else:self.send(404,{'error':'not found'})
    def do_POST(self):
        # Browser requests stay same-origin; don't expose the bridge token or arbitrary CLI commands.
        origin=self.headers.get('Origin')
        if origin and origin!='http://127.0.0.1:8788':self.send(403,{'error':'origin rejected'});return
        if self.path!='/api' or self.headers.get_content_type()!='application/json':self.send(400,{'error':'JSON required'});return
        try:
            size=int(self.headers.get('Content-Length','0'))
            if not 0<size<40000:raise ValueError('request too large')
            args=json.loads(self.rfile.read(size))
            with LOCK:result=call(args)
            self.send(200,result)
        except Exception as e:self.send(400,{'error':str(e)})
if __name__=='__main__':
    print('Blueprint prototype: http://127.0.0.1:8788',flush=True)
    ThreadingHTTPServer(('127.0.0.1',8788),Handler).serve_forever()
