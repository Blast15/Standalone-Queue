#!/usr/bin/env python3
"""Real Paper / loopback protocol checks. Run prepare.py and npm ci first.

Uses only isolated runtimes marked by prepare.py. Never contacts a production host.
Java and clients must share this process's network namespace.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[2]


class Runtime:
    def __init__(self, version, java, path=None):
        self.version, self.java = version, java
        self.path = Path(path).resolve() if path else ROOT / ('.staging-work/paper-' + version)
        if not (self.path / '.sq-staging-runtime').exists():
            raise ValueError('Prepare an isolated runtime first')
        if 'eula=true' not in (self.path / 'eula.txt').read_text().splitlines():
            raise ValueError('EULA must be accepted explicitly with prepare.py')
        self.condition = threading.Condition()
        self.lines, self.events = [], []
        self.server = self.client = None
        self.sequence = 0
        self.checks = []
        self.raw = version in ('26.2', '26.3')
        self.raw_clients = {}
        self.packet_map = None

    def pump(self, stream, output, target, parse=False):
        for line in stream:
            output.write(line)
            output.flush()
            with self.condition:
                try:
                    target.append(json.loads(line) if parse else line.rstrip())
                except json.JSONDecodeError:
                    target.append({'event': 'unparsed', 'line': line.rstrip()})
                self.condition.notify_all()

    def wait(self, predicate, timeout=25, label='condition'):
        deadline = time.monotonic() + timeout
        with self.condition:
            while True:
                value = predicate()
                if value:
                    return value
                if self.server and self.server.poll() is not None:
                    raise AssertionError('Server exited while waiting for ' + label)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AssertionError('Timeout: ' + label)
                self.condition.wait(min(remaining, .25))

    def command(self, text):
        self.server.stdin.write(text + '\n')
        self.server.stdin.flush()

    def start(self):
        index = len(self.lines)
        self.server = subprocess.Popen([self.java, '-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'],
                                       cwd=self.path, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, bufsize=1)
        log = (self.path / 'smoke-server.log').open('a')
        threading.Thread(target=self.pump, args=(self.server.stdout, log, self.lines), daemon=True).start()
        self.wait(lambda: any('Done (' in s for s in self.lines[index:]), 180, 'Paper ready')
        assert any('Enabling StandaloneQueue v2.0.0' in s for s in self.lines[index:]), 'Plugin version/load'
        assert any('Staging probe active' in s for s in self.lines[index:]), 'Probe load'
        if self.raw:
            from raw_client import mappings
            self.packet_map = mappings(self.path, self.java, self.version)
            return
        self.client = subprocess.Popen(['node', str(ROOT / 'scripts/staging/clients.mjs')], cwd=ROOT,
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=(self.path / 'smoke-client-errors.log').open('a'), text=True, bufsize=1)
        clientlog = (self.path / 'smoke-clients.jsonl').open('a')
        threading.Thread(target=self.pump, args=(self.client.stdout, clientlog, self.events, True), daemon=True).start()

    def rpc(self, **request):
        if self.raw:
            from raw_client import Client
            action = request['action']
            if action == 'connect':
                def emit(event):
                    with self.condition:
                        self.events.append(event)
                        with (self.path / 'smoke-clients.jsonl').open('a') as output:
                            output.write(json.dumps(event) + '\n')
                        self.condition.notify_all()
                client = Client(request['id'], request['name'], request.get('localAddress', '127.0.0.1'), self.packet_map, emit)
                self.raw_clients[request['id']] = client
                threading.Thread(target=client.run, daemon=True).start()
            elif action == 'close':
                self.raw_clients[request['id']].close()
            elif action == 'shutdown':
                for client in self.raw_clients.values():
                    client.close()
                self.raw_clients.clear()
            return
        self.client.stdin.write(json.dumps(request) + '\n')
        self.client.stdin.flush()

    def connect(self, name, expected, address='127.0.0.1'):
        self.sequence += 1
        identifier = name + str(self.sequence)
        self.rpc(action='connect', id=identifier, name=name, localAddress=address, version=self.version)
        event = self.wait(lambda: next((e for e in self.events if e.get('id') == identifier
                                       and e.get('event') in ('play', 'kick', 'error')), None), label=name + ' ' + expected)
        assert event['event'] == expected, event
        if expected == 'kick':
            self.wait(lambda: any(e.get('id') == identifier and e.get('event') == 'end' for e in self.events), label=name + ' closed')
        return identifier

    def snapshot(self):
        self.sequence += 1
        token = 's' + str(self.sequence)
        self.command('sqprobe snapshot ' + token)
        line = self.wait(lambda: next((s for s in self.lines if 'SQSNAP ' + token + ' ' in s), None), label='snapshot')
        result = dict(re.findall(r'(\w+)=([^ ]*)', line))
        return {k: int(v) if v.isdigit() else v for k, v in result.items()}

    def state(self, **expected):
        deadline = time.monotonic() + 25
        while True:
            actual = self.snapshot()
            if all(actual.get(k) == v for k, v in expected.items()):
                assert actual['online'] + actual['leases'] + actual['inFlight'] <= actual['capacity'], actual
                return actual
            if time.monotonic() >= deadline:
                raise AssertionError({'expected': expected, 'actual': actual})
            with self.condition:
                self.condition.wait(.2)

    def check(self, label):
        self.checks.append(label)
        print(json.dumps({'version': self.version, 'check': label, 'result': 'PASS'}), flush=True)

    def stop(self):
        if self.raw:
            self.rpc(action='shutdown')
        if self.client and self.client.poll() is None:
            self.rpc(action='shutdown')
            try:
                self.client.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.client.kill()
        if self.server and self.server.poll() is None:
            self.command('stop')
            try:
                self.server.wait(timeout=45)
            except subprocess.TimeoutExpired:
                self.server.kill()
                self.server.wait()
                raise AssertionError('Paper shutdown timed out')

    def run(self, boot_only=False):
        config = self.path / 'plugins/StandaloneQueue/config.yml'
        if config.exists():
            raise ValueError('Smoke requires a fresh runtime config; retain logs before explicitly resetting staging state')
        config.parent.mkdir(parents=True, exist_ok=True)
        source = (ROOT / 'src/main/resources/config.yml').read_text()
        source = source.replace('rate-limit-seconds: 2', 'rate-limit-seconds: 0')
        source = source.replace('slot-reservation-seconds: 60', 'slot-reservation-seconds: 20')
        source = source.replace('max-attempts-per-ip: 20', 'max-attempts-per-ip: 200')
        source = source.replace('global-attempts-per-window: 100', 'global-attempts-per-window: 1000')
        source = source.replace('max-queued-per-ip: 5', 'max-queued-per-ip: 2')
        source = source.replace('persistence:\n  enabled: false', 'persistence:\n  enabled: true')
        source = source.replace('flush-interval-seconds: 10', 'flush-interval-seconds: 1')
        config.write_text(source)
        for name in ['StandaloneQueue-2.0.0.jar', 'StandaloneQueue-StagingProbe-2.0.0.jar']:
            origin = ROOT / ('staging-probe/target' if 'StagingProbe' in name else 'target') / name
            shutil.copy2(origin, self.path / 'plugins' / name)
        try:
            self.start()
            self.state(online=0, queued=0, leases=0, inFlight=0, capacity=1)
            self.check('Paper boots and loads plugin/probe 2.0.0')
            if boot_only:
                return
            holder = self.connect('Holder', 'play')
            self.state(online=1, inFlight=0)
            self.connect('Alice', 'kick')
            self.connect('Bob', 'kick')
            self.state(online=1, queued=2, names='Alice,Bob')
            self.connect('NatThird', 'kick')
            self.state(queued=2, names='Alice,Bob')
            self.check('Full server queues FIFO and enforces shared-IP quota')
            self.rpc(action='close', id=holder)
            self.state(online=0, leases=1, queued=2)
            self.connect('Bob', 'kick')
            self.state(online=0, leases=1, queued=2, names='Alice,Bob')
            alice = self.connect('Alice', 'play')
            self.state(online=1, queued=1, leases=0, inFlight=0)
            self.rpc(action='close', id=alice)
            self.state(online=0, leases=1)
            bob = self.connect('Bob', 'play')
            self.state(online=1, queued=0, leases=0, inFlight=0, joins=3)
            self.rpc(action='close', id=bob)
            self.state(online=0)
            self.check('Exclusive lease, reconnect handoff, JOIN/QUIT cleanup')
            for action, name, counter in [('deny-pre', 'PreDenied', 'cancelled'), ('deny-login', 'LoginDenied', 'failed')]:
                before = self.snapshot()[counter]
                self.command('sqprobe ' + action + ' ' + name)
                self.wait(lambda: any('Armed one ' + action + ' fixture for ' + name in s for s in self.lines))
                self.connect(name, 'kick')
                self.state(inFlight=0, online=0, **{counter: before + 1})
                self.check('Late ' + action + ' releases admission')
            self.command('ban BanDenied staging')
            self.wait(lambda: any('Banned BanDenied' in s for s in self.lines))
            self.connect('BanDenied', 'kick')
            self.state(inFlight=0, online=0)
            self.command('pardon BanDenied')
            self.command('whitelist on')
            self.snapshot()  # Console FIFO barrier; feedback wording varies between versions.
            self.connect('NotListed', 'kick')
            self.state(inFlight=0, online=0)
            self.command('whitelist off')
            self.snapshot()
            self.check('Native ban and whitelist rejection survives queue admission')
            self.command('queue pause')
            self.state(paused='true')
            self.connect('Paused', 'kick')
            self.state(online=0, inFlight=0)
            self.command('queue resume')
            self.state(paused='false')
            self.check('Pause/resume rejects new admission while paused')
            config.write_text(source.replace('in-flight-timeout-seconds: 30', 'in-flight-timeout-seconds: 5'))
            self.command('queue reload')
            self.state(timeout=5)
            self.command('sqprobe delay Slow 6500')
            self.wait(lambda: any('Armed one delay fixture for Slow' in s for s in self.lines))
            self.sequence += 1
            slow = 'slow' + str(self.sequence)
            self.rpc(action='connect', id=slow, name='Slow', version=self.version)
            self.wait(lambda: any('name=Slow' in s and 'event=delay-start' in s for s in self.lines))
            self.state(online=0, inFlight=1)
            self.connect('Waiting', 'kick', '127.0.0.2')
            self.state(queued=1, leases=0, inFlight=1)
            self.wait(lambda: any(e.get('id') == slow and e.get('event') == 'kick' for e in self.events))
            self.state(online=0, inFlight=0, leases=1, timeouts=1)
            config.write_text(source)
            self.command('queue reload')
            self.state(timeout=30)
            waiting = self.connect('Waiting', 'play', '127.0.0.2')
            self.state(online=1, queued=0, inFlight=0)
            self.check('Slow handshake keeps capacity until terminal rejection then hands off')
            config.write_text(source.replace('max-players-override: -1', 'max-players-override: 0'))
            self.command('queue reload')
            self.wait(lambda: any('Queue reload rejected:' in s for s in self.lines))
            self.state(capacity=1)
            config.write_text(source.replace('max-players-override: -1', 'max-players-override: 2'))
            self.command('queue reload')
            self.state(capacity=2)
            self.check('Invalid reload preserves generation; valid reload changes capacity')
            config.write_text(source)
            self.command('queue reload')
            self.state(capacity=1)
            self.connect('Persisted', 'kick', '127.0.0.3')
            self.state(queued=1, names='Persisted')
            self.stop()
            self.start()
            self.state(online=0, queued=1, names='Persisted', leases=1, inFlight=0)
            restored = self.connect('Persisted', 'play', '127.0.0.3')
            self.state(online=1, queued=0, inFlight=0)
            self.rpc(action='close', id=restored)
            self.state(online=0)
            self.check('Graceful restart restores queue and grants fresh lease')
            holder = self.connect('CrashHolder', 'play', '127.0.0.4')
            self.state(online=1)
            self.connect('CrashRestored', 'kick', '127.0.0.5')
            self.state(queued=1, names='CrashRestored')
            snapshot = self.path / 'plugins/StandaloneQueue/queue-state.bin'
            self.wait(lambda: snapshot.exists() and b'CrashRestored' in snapshot.read_bytes(), label='durable crash snapshot')
            self.server.kill()
            self.server.wait(timeout=10)
            self.stop()
            self.start()
            self.state(online=0, queued=1, names='CrashRestored', leases=1, inFlight=0)
            restored = self.connect('CrashRestored', 'play', '127.0.0.5')
            self.state(online=1, queued=0)
            self.rpc(action='close', id=restored)
            self.state(online=0)
            self.check('SIGKILL restores latest completed snapshot without old admission/lease')
            self.stop()
            corrupt = b'corrupt staging fixture'
            snapshot.write_bytes(corrupt)
            self.start()
            self.state(online=0, queued=0, leases=0, inFlight=0)
            holder = self.connect('MemoryOnly', 'play', '127.0.0.6')
            self.state(online=1)
            self.rpc(action='close', id=holder)
            self.state(online=0)
            self.stop()
            assert snapshot.read_bytes() == corrupt, 'Corrupt snapshot must be preserved'
            self.check('Corrupt snapshot falls back to memory-only and preserves evidence')
        finally:
            self.stop()

    def run_load(self):
        config = self.path / 'plugins/StandaloneQueue/config.yml'
        if config.exists():
            raise ValueError('Load check requires a fresh isolated runtime')
        config.parent.mkdir(parents=True, exist_ok=True)
        source = (ROOT / 'src/main/resources/config.yml').read_text()
        source = source.replace('rate-limit-seconds: 2', 'rate-limit-seconds: 0')
        source = source.replace('max-players-override: -1', 'max-players-override: 50')
        source = source.replace('keep-alive-seconds: 300', 'keep-alive-seconds: 3600')
        source = source.replace('global-attempts-per-window: 100', 'global-attempts-per-window: 1000')
        config.write_text(source)
        properties = self.path / 'server.properties'
        properties.write_text(properties.read_text().replace('max-players=1\n', 'max-players=50\n'))
        started = time.monotonic()
        try:
            self.start()
            holders = [self.connect('Online' + str(i), 'play', '127.0.10.' + str(i + 1)) for i in range(50)]
            self.state(online=50, capacity=50, queued=0, inFlight=0)
            self.check('50 real loopback clients reach Paper JOIN')
            for i in range(500):
                self.connect('Queued' + str(i), 'kick', '127.0.' + str(20 + i // 250) + '.' + str(1 + i % 250))
                if i % 50 == 49:
                    self.state(online=50, queued=i + 1, leases=0, inFlight=0)
                    print(json.dumps({'version': self.version, 'load_progress': i + 1}), flush=True)
            self.connect('Overflow', 'kick', '127.0.30.1')
            self.state(online=50, queued=500, leases=0, inFlight=0)
            self.check('500 waiting entries / 50 online: bounded queue rejects overflow')
            self.rpc(action='close', id=holders[0])
            self.state(online=49, queued=500, leases=1, inFlight=0)
            replacement = self.connect('Queued0', 'play', '127.0.20.1')
            self.state(online=50, queued=499, leases=0, inFlight=0)
            self.check('Lease handoff remains exclusive under 500/50 load')
            print(json.dumps({'version': self.version, 'elapsed_seconds': round(time.monotonic() - started, 2),
                              'note': 'Shared staging host; correctness check, not a production latency SLO'}), flush=True)
        finally:
            self.stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True, choices=['1.21.11', '26.1.1', '26.1.2', '26.2', '26.3'])
    parser.add_argument('--java', default=str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java'))
    parser.add_argument('--boot-only', action='store_true', help='No network lifecycle claim for unsupported client protocols')
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--load', action='store_true', help='50 real online clients / 500 queued entries; requires a separate fresh runtime')
    args = parser.parse_args()
    runtime = Runtime(args.version, args.java, args.runtime)
    result = {'version': args.version, 'scope': 'load-500-50' if args.load else 'boot-only' if args.boot_only else 'offline-network', 'checks': runtime.checks}
    try:
        if args.load:
            runtime.run_load()
        else:
            runtime.run(args.boot_only)
        result['result'] = 'PASS'
    except Exception as error:
        result.update(result='FAIL', error=str(error))
        raise
    finally:
        (runtime.path / 'smoke-result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
