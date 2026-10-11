#!/usr/bin/env python3
"""Encrypted online-mode Paper pipeline with a loopback-only MOCK session service.
No Microsoft/Mojang credentials; fixture profiles are not authenticated accounts.
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading
import time
from urllib.parse import urlparse, parse_qs
import uuid
from prepare import ROOT
from smoke import Runtime
from luckperms import install

def profile(name, suffix):
    value = uuid.UUID(bytes=hashlib.md5(('StandaloneQueue-staging-' + suffix).encode()).digest(), version=3)
    return {'id': value.hex, 'name': name, 'properties': []}

PROFILES = {name: profile(name, suffix) for name, suffix in
            [('HolderFixture','holder'), ('VipFixture','vip'), ('StaffFixture','staff'), ('NormalFixture','normal')]}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True, choices=['1.21.11'])
    parser.add_argument('--runtime', required=True, type=Path)
    args = parser.parse_args()
    runtime = Runtime(args.version, str(Path(os.environ['JAVA_HOME'])/'bin/java'), args.runtime)
    requests = []
    class Session(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            parsed = urlparse(self.path)
            data = None
            if parsed.path.endswith('/hasJoined'):
                name = parse_qs(parsed.query).get('username',[''])[0]
                data = PROFILES.get(name)
                requests.append({'name':name, 'profile':data})
            elif parsed.path == '/publickeys':
                data = {'profilePropertyKeys':[], 'playerCertificateKeys':[]}
            elif '/profile/' in parsed.path:
                identifier = parsed.path.rsplit('/',1)[-1]
                data = next((p for p in PROFILES.values() if p['id'] == identifier), None)
            if data is None:
                self.send_response(204); self.end_headers(); return
            body = json.dumps(data).encode()
            self.send_response(200)
            self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(body)))
            self.end_headers(); self.wfile.write(body)
    session = ThreadingHTTPServer(('127.0.0.1',0),Session)
    threading.Thread(target=session.serve_forever,daemon=True).start()
    host = 'http://127.0.0.1:' + str(session.server_port)
    runtime.java_args = ['-Dminecraft.api.'+key+'.host='+host for key in ['session','services','profiles']]
    result = {'version':args.version, 'scope':'encrypted online-mode pipeline; MOCK loopback session profiles, not real Mojang accounts', 'checks':runtime.checks}
    config = runtime.path/'plugins/StandaloneQueue/config.yml'
    if config.exists(): raise ValueError('Fresh isolated runtime required')
    config.parent.mkdir(parents=True,exist_ok=True)
    source = (ROOT/'src/main/resources/config.yml').read_text()
    source = source.replace('rate-limit-seconds: 2','rate-limit-seconds: 0').replace('resolve-timeout-ms: 1000','resolve-timeout-ms: 5000')
    source = source.replace('  anti-bot:\n    enabled: true','  anti-bot:\n    enabled: false')
    config.write_text(source)
    properties = runtime.path/'server.properties'
    properties.write_text(properties.read_text().replace('online-mode=false','online-mode=true'))
    def backend(action):
        index = len(runtime.lines)
        runtime.command('sqprobe '+action)
        line = runtime.wait(lambda: next((s for s in runtime.lines[index:] if 'SQLP '+action+' PASS' in s or 'SQLP '+action+' FAIL' in s),None),120,action)
        assert ' PASS' in line, line
    def connect(name, expected):
        identifier = runtime.connect(name,expected)
        assert any(r['name'] == name and r['profile'] == PROFILES[name] for r in requests), 'Must traverse online session lookup'
        if expected == 'play':
            event = next(e for e in runtime.events if e.get('id') == identifier and e.get('event') == 'play')
            assert uuid.UUID(event['uuid']).hex == PROFILES[name]['id'], event
        return identifier
    try:
        result['luckperms'] = install(runtime)
        runtime.start()
        backend('lp-network-seed')
        holder = connect('HolderFixture','play')
        runtime.state(online=1,inFlight=0)
        connect('NormalFixture','kick')
        connect('VipFixture','kick')
        connect('StaffFixture','kick')
        runtime.state(online=1,queued=3,names='StaffFixture,VipFixture,NormalFixture',inFlight=0)
        runtime.check('Encrypted online session pipeline uses fixture UUID and real LuckPerms tier order')
        runtime.rpc(action='close',id=holder)
        runtime.state(online=0,leases=1)
        for name, remaining in [('StaffFixture',2),('VipFixture',1),('NormalFixture',0)]:
            identifier = connect(name,'play')
            runtime.state(online=1,queued=remaining,leases=0,inFlight=0)
            runtime.rpc(action='close',id=identifier)
            runtime.state(online=0)
        runtime.check('Online encrypted queued clients complete exclusive lease JOIN/QUIT handoff')
        holder = connect('HolderFixture','play')
        runtime.state(online=1,inFlight=0)
        backend('lp-network-grant')
        vip = connect('VipFixture','play')
        deadline = time.monotonic()+25
        while True:
            actual = runtime.snapshot()
            if actual['online'] == 2 and actual['queued'] == 0 and actual['inFlight'] == 0: break
            if time.monotonic() >= deadline: raise AssertionError(actual)
            time.sleep(.2)
        runtime.rpc(action='close',id=vip)
        runtime.state(online=1,inFlight=0)
        backend('lp-network-revoke')
        connect('VipFixture','kick')
        runtime.state(online=1,queued=1,names='VipFixture',inFlight=0)
        runtime.check('Real LuckPerms bypass grant and revoke observed by online encrypted connections')
        backend('lp-network-grant')
        runtime.command('ban VipFixture staging')
        runtime.snapshot()
        connect('VipFixture','kick')
        runtime.state(online=1,inFlight=0)
        runtime.command('pardon VipFixture')
        runtime.command('whitelist on')
        runtime.snapshot()
        connect('VipFixture','kick')
        runtime.state(online=1,inFlight=0)
        runtime.command('whitelist off')
        runtime.snapshot()
        runtime.check('Native ban and whitelist override real LuckPerms bypass in online pipeline')
        result['result']='PASS'
    except Exception as error:
        result.update(result='FAIL',error=str(error)); raise
    finally:
        runtime.stop()
        session.shutdown(); session.server_close()
        result['mock_session_requests']=requests
        (runtime.path/'smoke-online-result.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result),flush=True)

if __name__ == '__main__': main()
