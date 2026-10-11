#!/usr/bin/env python3
"""Real Paper/LuckPerms/H2 qualification; fixture UUIDs are not authenticated players."""
import argparse
import hashlib
import json
from pathlib import Path
import socket
import struct
import urllib.parse
import zipfile
from prepare import fetch, ROOT
from smoke import Runtime
from raw_client import varint, read_varint, text

def install(runtime):
    metadata_url = 'https://metadata.luckperms.net/data/all'
    with fetch(metadata_url) as response:
        metadata = json.load(response)
    url = metadata['downloads']['bukkit']
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != 'https' or not (parsed.hostname.endswith('.luckperms.net') or parsed.hostname == 'ci.lucko.me'):
        raise ValueError('Unexpected official LuckPerms download host')
    with fetch(url) as response:
        binary = response.read()
    target = runtime.path / 'plugins/LuckPerms.jar'
    target.write_bytes(binary)
    with zipfile.ZipFile(target) as archive:
        if 'name: LuckPerms' not in archive.read('plugin.yml').decode():
            raise ValueError('Unexpected plugin descriptor')
    source = {'metadata_url':metadata_url, 'version':metadata['version'], 'url':url,
              'sha256':hashlib.sha256(binary).hexdigest()}
    (runtime.path / 'luckperms-source.json').write_text(json.dumps(source, indent=2)+'\n')
    directory = runtime.path / 'plugins/LuckPerms'
    directory.mkdir(exist_ok=True)
    (directory / 'config.yml').write_text('server: sqstaging\nstorage-method: h2\nmessaging-service: none\nsync-minutes: -1\nwatch-files: false\n')
    return source

def unauthenticated_boundary(runtime):
    protocol = None
    for cached in (runtime.path / 'cache').glob('*.jar'):
        with zipfile.ZipFile(cached) as archive:
            if 'version.json' in archive.namelist():
                version = json.loads(archive.read('version.json'))
                if version['id'] == runtime.version:
                    protocol = version['protocol_version']
    if protocol is None:
        raise ValueError('Missing exact Minecraft protocol')
    with socket.create_connection(('127.0.0.1',25576), timeout=15) as connection:
        def send(identifier, payload):
            frame = varint(identifier) + payload
            connection.sendall(varint(len(frame)) + frame)
        def read(length):
            data = bytearray()
            while len(data) < length:
                part = connection.recv(length-len(data))
                if not part: raise EOFError('Server closed before encryption challenge')
                data.extend(part)
            return bytes(data)
        send(0, varint(protocol)+text('127.0.0.1')+struct.pack('>H',25576)+varint(2))
        send(0, text('AuthFixture')+bytes(16))
        length=read_varint(read)
        if length > 1024*1024: raise ValueError('Oversized authentication challenge')
        packet=read(length)
        import io
        identifier=read_varint(io.BytesIO(packet).read)
        assert identifier == 1, 'Online server must request encryption/authentication before admission'
        runtime.state(online=0, queued=0, leases=0, inFlight=0)
    runtime.state(online=0, queued=0, leases=0, inFlight=0)
    runtime.check('Online unauthenticated handshake does not allocate queue/admission')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version',required=True)
    parser.add_argument('--runtime',type=Path,required=True)
    args=parser.parse_args()
    import os
    runtime=Runtime(args.version,str(Path(os.environ['JAVA_HOME'])/'bin/java'),args.runtime)
    result={'minecraft':args.version,'scope':'real LuckPerms/H2 plus online unauthenticated boundary; no authenticated Mojang gameplay','checks':runtime.checks}
    config=runtime.path/'plugins/StandaloneQueue/config.yml'
    if config.exists():raise ValueError('A fresh isolated runtime is required')
    config.parent.mkdir(parents=True,exist_ok=True)
    source=(ROOT/'src/main/resources/config.yml').read_text()
    source=source.replace('rate-limit-seconds: 2','rate-limit-seconds: 0')
    source=source.replace('permissions:\n','permissions:\n')
    source=source.replace('resolve-timeout-ms: 1000','resolve-timeout-ms: 5000')
    source=source.replace('  anti-bot:\n    enabled: true','  anti-bot:\n    enabled: false')
    config.write_text(source)
    properties=runtime.path/'server.properties'
    properties.write_text(properties.read_text().replace('online-mode=false','online-mode=true'))
    try:
        result['luckperms']=install(runtime)
        runtime.start()
        runtime.wait(lambda:any('Successfully enabled.' in s and 'LP' in s for s in runtime.lines) or
                     any('[LuckPerms] Enabling LuckPerms' in s for s in runtime.lines),timeout=120,label='LuckPerms enabled')
        unauthenticated_boundary(runtime)
        runtime.command('sqprobe lp-seed')
        runtime.wait(lambda:any('SQLP lp-seed PASS' in s for s in runtime.lines),timeout=120,label='H2 seed persisted')
        runtime.stop()
        runtime.start()
        index=len(runtime.lines)
        runtime.command('sqprobe lp-check')
        def completed():
            return next((s for s in runtime.lines[index:] if 'SQLP lp-check PASS' in s or 'SQLP lp-check FAIL' in s),None)
        line=runtime.wait(completed,timeout=180,label='Real LuckPerms backend qualification')
        assert ' PASS' in line,line
        result['backend_checks']=[s[s.index('SQLP '):] for s in runtime.lines[index:] if 'SQLP ' in s]
        runtime.check('Actual LuckPerms binary/H2 restart, context, grant/revoke, reload and recovery')
        result['result']='PASS'
    except Exception as error:
        result.update(result='FAIL',error=str(error))
        raise
    finally:
        runtime.stop()
        (runtime.path/'luckperms-result.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result),flush=True)

if __name__ == '__main__':
    main()
