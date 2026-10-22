#!/usr/bin/env python3
"""Validate ownership with real native dispatch and synthetic player identities."""
import argparse
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import socket
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('chunk_fixture', ROOT / 'scripts/run-chunk-benchmark.py')
FIXTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE)
OWNER = '11111111-1111-4111-8111-111111111111'
UNKNOWN = '44444444-4444-4444-8444-444444444444'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--java', default='/usr/lib/jvm/java-25-openjdk/bin/java')
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--probe-jar', type=Path, required=True)
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Review the Minecraft EULA and explicitly pass --accept-eula')
    jar, probe = args.jar.resolve(strict=True), args.probe_jar.resolve(strict=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    directory = ROOT / 'run/benchmarks' / f'owners-{stamp}-{uuid.uuid4().hex[:6]}'
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    FIXTURE.prepare(directory, 8142026, True, port=port)
    with (directory / 'server.properties').open('a') as properties:
        properties.write('\nwhite-list=true\n')
    (directory / 'plugins').mkdir()
    shutil.copyfile(probe, directory / 'plugins/owner-probe.jar')
    shutil.copyfile(__file__, directory / 'runner.py')
    runs = []
    for restart in (False, True):
        server = FIXTURE.Server(args.java, jar, directory, 'restart' if restart else 'initial')
        try:
            if not restart:
                server.send('owner add ' + OWNER)
                server.barrier()
                saved = json.loads((directory / 'owners.json').read_text())
                assert [entry['uuid'] for entry in saved['owners']] == [OWNER]
                server.send('owner remove ' + UNKNOWN)
                server.barrier()
                assert json.loads((directory / 'owners.json').read_text()) == saved
                server.send('owner remove ' + OWNER)
                server.barrier()
                assert json.loads((directory / 'owners.json').read_text())['owners'] == []
                server.send('owner add ' + OWNER)
                server.barrier()
            server.send('cubveownerprobe' + (' stop-as-owner' if restart else ''))
            line = server.wait('CUBVE_OWNER_PROBE_', 60)
            if 'CUBVE_OWNER_PROBE_PASS' not in line:
                raise AssertionError(line)
            if restart:
                server.process.wait(timeout=120)
                assert server.process.returncode == 0
            else:
                server.barrier()
            runs.append({'restart': restart, 'probeResult': line.strip()})
        finally:
            server.close()
    owners = json.loads((directory / 'owners.json').read_text())
    assert [entry['uuid'] for entry in owners['owners']] == [OWNER]
    summary = {
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'sourceWorkingTreeModified': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()),
        'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'probeSha256': hashlib.sha256(probe.read_bytes()).hexdigest(),
        'workDirectory': str(directory), 'players': 0, 'plugins': ['CubveOwnerProbe'],
        'runs': runs, 'persistedOwners': owners, 'ownerCommandStoppedServer': True,
        'limitations': ['Synthetic player senders; no authenticated player network sessions',
                        'No third-party moderation plugins or proxy forwarding'],
    }
    (directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('Results: ' + str(directory / 'summary.json'), flush=True)


if __name__ == '__main__':
    main()
