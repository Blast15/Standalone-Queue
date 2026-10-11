#!/usr/bin/env python3
"""Prepare an isolated loopback Paper runtime. EULA acceptance is always explicit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
USER_AGENT = 'StandaloneQueue-Staging/2.0.0 (https://github.com/Blast15/Standalone-Queue)'
EULA = 'https://www.minecraft.net/en-us/eula'


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': USER_AGENT}), timeout=90)


def download(url, target, expected):
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return
    temporary = target.with_suffix(target.suffix + '.part')
    try:
        with fetch(url) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            raise ValueError('Download checksum mismatch: ' + target.name)
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', choices=['1.21.11', '26.1.1', '26.1.2', '26.2', '26.3'], default='1.21.11')
    parser.add_argument('--allow-unstable', action='store_true', help='Explicitly allow Paper ALPHA/BETA for compatibility testing')
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--accept-eula', action='store_true', help='Explicitly accept Minecraft EULA: ' + EULA)
    args = parser.parse_args()
    runtime = (args.runtime or ROOT / ('.staging-work/paper-' + args.version)).resolve()
    runtime.mkdir(parents=True, exist_ok=True)
    marker = runtime / '.sq-staging-runtime'
    if not marker.exists() and any(runtime.iterdir()):
        raise SystemExit('Runtime must be empty or previously marked by this staging preparer')
    marker.write_text('StandaloneQueue isolated loopback staging, never a production directory\n')
    with fetch('https://fill.papermc.io/v3/projects/paper/versions/' + args.version + '/builds') as response:
        builds = json.load(response)
    build = next((item for item in builds if item['channel'] == 'STABLE'), None)
    if build is None:
        if not args.allow_unstable:
            raise SystemExit('No STABLE Paper build for ' + args.version + '; use --allow-unstable for compatibility testing only')
        build = builds[0]
    asset = build['downloads']['server:default']
    download(asset['url'], runtime / 'paper.jar', asset['checksums']['sha256'])
    with zipfile.ZipFile(runtime / 'paper.jar') as archive:
        checksum, url, filename = archive.read('META-INF/download-context').decode().strip().split('\t')
    (runtime / 'cache').mkdir(exist_ok=True)
    download(url, runtime / 'cache' / filename, checksum)
    (runtime / 'paper-source.json').write_text(json.dumps(build, indent=2) + '\n')
    plugins = runtime / 'plugins'
    plugins.mkdir(exist_ok=True)
    for source in [ROOT / 'target/StandaloneQueue-2.0.0.jar',
                   ROOT / 'staging-probe/target/StandaloneQueue-StagingProbe-2.0.0.jar']:
        if not source.is_file():
            raise SystemExit('Build parent and staging probe first: missing ' + str(source))
        shutil.copy2(source, plugins / source.name)
    # Existing test worlds/state/configs are retained. These defaults only apply to the first setup.
    properties = runtime / 'server.properties'
    if not properties.exists():
        properties.write_text('server-ip=127.0.0.1\nserver-port=25576\nonline-mode=false\n'
                              'max-players=1\nview-distance=2\nsimulation-distance=2\n'
                              'level-type=minecraft:flat\ngenerate-structures=false\n'
                              'enforce-secure-profile=false\nwhite-list=false\ndifficulty=peaceful\n'
                              'spawn-protection=0\nenable-rcon=false\nenable-query=false\n')
    eula = runtime / 'eula.txt'
    if args.accept_eula:
        eula.write_text('# Accepted explicitly for this isolated staging runtime\neula=true\n')
    elif not eula.exists():
        eula.write_text('# Read ' + EULA + ' before accepting\neula=false\n')
    print(json.dumps({'runtime': str(runtime), 'paper_version': args.version, 'channel': build['channel'], 'build': build['id'],
                      'sha256': asset['checksums']['sha256'], 'eula': EULA,
                      'status': 'prepared; gameplay tests have not run'}, ensure_ascii=False))
    if args.prepare_only:
        return
    accepted = any(line.strip() == 'eula=true' for line in eula.read_text().splitlines())
    if not accepted:
        raise SystemExit('Minecraft EULA is not accepted. Review ' + EULA + ' then explicitly use --accept-eula.')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java:
        raise SystemExit('Java 21 is required for 1.21.11; Java 25 for 26.x')
    raise SystemExit(subprocess.call([java, '-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'], cwd=runtime))


if __name__ == '__main__':
    main()
