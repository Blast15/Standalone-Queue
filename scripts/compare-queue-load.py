#!/usr/bin/env python3
"""Compare the same JVM workload against compiled baseline and current QueueManager classes."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--baseline-classes', type=Path, required=True)
parser.add_argument('--forks', type=int, default=3)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
report = next((root / 'target/surefire-reports').glob('TEST-*.xml'))
classpath = next(p.attrib['value'] for p in ET.parse(report).findall('.//property')
                 if p.attrib['name'] == 'java.class.path')
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
if not java or not javac or args.forks < 1:
    parser.error('JDK 21 and --forks >= 1 are required')
baseline = args.baseline_classes.resolve()
if not (baseline / 'com/mineplugin/queue/manager/QueueManager.class').exists():
    parser.error('baseline QueueManager must already be compiled')
with tempfile.TemporaryDirectory(prefix='queue-load-') as work:
    subprocess.run([javac, '--release', '21', '-proc:none', '-cp', classpath, '-d', work,
                    str(root / 'src/test/java/com/mineplugin/queue/QueueLoadBenchmark.java')], check=True)
    for antibot in ('false', 'true'):
        for fork in range(args.forks):
            # Alternate order between forks to reduce a systematic warm-host/order bias.
            variants = [('baseline', str(baseline)), ('phase3', str(root / 'target/classes'))]
            if fork % 2:
                variants.reverse()
            for label, classes in variants:
                result = subprocess.run([java, '-cp', os.pathsep.join([work, classes, classpath]),
                                         'com.mineplugin.queue.QueueLoadBenchmark', antibot],
                                        check=True, capture_output=True, text=True)
                print(f'{label} fork={fork + 1} {result.stdout.strip()}', flush=True)
