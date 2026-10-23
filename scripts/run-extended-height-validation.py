#!/usr/bin/env python3
"""Actual WorldEdit edits with the unmodified Extended World Height 4.0 data pack."""
import argparse
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import socket
import statistics
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('chunk_fixture', ROOT / 'scripts/run-chunk-benchmark.py')
CHUNKS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHUNKS)


def probe(server, *args):
    arguments = ' '.join(map(str, args))
    server.send('cubveheightprobe ' + arguments)
    result = None
    while True:
        line = server.wait('CUBVE_HEIGHT_', 90)
        if 'CUBVE_HEIGHT_FAIL' in line:
            raise RuntimeError(line.strip())
        if 'CUBVE_HEIGHT_METRIC ' in line:
            result = json.loads(line.split('CUBVE_HEIGHT_METRIC ', 1)[1])
        if 'CUBVE_HEIGHT_PASS ' + arguments in line:
            server.barrier()
            return result


def fingerprint(world):
    chunk = CHUNKS.read_chunk(world, 64, 64)
    sections = [s for s in chunk['sections'] if 'block_states' in s and 'biomes' in s]
    assert sorted(s['Y'] for s in sections) == list(range(-64, 64))
    # Full saved section containers and heightmaps, with volatile tick/light metadata excluded.
    # Palette order is expected to be repeatable for this identical single-worker edit sequence.
    data = {'sections': sections, 'Heightmaps': chunk['Heightmaps'], 'block_entities': chunk['block_entities']}
    for section in sections:
        for key in list(section):
            if key not in ('Y', 'block_states', 'biomes'):
                del section[key]
    for entity in data['block_entities']:
        entity.pop('keepPacked', None)
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def wait_ready(server):
    deadline = time.monotonic() + 120
    attempts = 0
    while True:
        result = probe(server, 'ready')
        attempts += 1
        if result['pending'] == 0:
            return {'nativeReadiness': 'ENTITY_TICKING and empty postprocessing in all nine target chunks', 'attempts': attempts}
        if time.monotonic() >= deadline:
            raise TimeoutError('Target chunks have not completed generation postprocessing')
        time.sleep(.5)


def save(server):
    ready = wait_ready(server)
    server.send('save-all flush')
    server.wait('Saved the game', 120)
    server.barrier()
    chunk = CHUNKS.read_chunk(server.directory / 'world', 64, 64)
    assert chunk['Status'] == 'minecraft:full' and CHUNKS.postprocessing_completed(chunk)
    return ready


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--java', default='/usr/lib/jvm/java-25-openjdk/bin/java')
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--probe-jar', type=Path, required=True)
    parser.add_argument('--datapack', type=Path, required=True)
    parser.add_argument('--worldedit', type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--pregen', action='store_true')
    parser.add_argument('--compare-summary', type=Path)
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Review the Minecraft EULA and explicitly pass --accept-eula')
    if not args.label or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in args.label):
        parser.error('Label must contain only letters, numbers, hyphen or underscore')
    jar, plugin, datapack, worldedit = [p.resolve(strict=True) for p in (args.jar, args.probe_jar, args.datapack, args.worldedit)]
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    directory = ROOT / 'run/benchmarks' / f'extended-height-{args.label}-{stamp}-{uuid.uuid4().hex[:6]}'
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    CHUNKS.prepare(directory, 8142026, True, port=port)
    (directory / 'world/datapacks').mkdir(parents=True)
    shutil.copyfile(datapack, directory / 'world/datapacks/extended-height.zip')
    (directory / 'plugins').mkdir()
    shutil.copyfile(worldedit, directory / 'plugins/worldedit.jar')
    shutil.copyfile(plugin, directory / 'plugins/height-probe.jar')
    shutil.copyfile(__file__, directory / 'runner.py')
    metrics, saving, snapshots = [], [], []
    for restart in (False, True):
        server = CHUNKS.Server(args.java, jar, directory, 'restart' if restart else 'initial')
        try:
            server.freeze()
            server.load((63, 63, 3), keep_loaded=True)
            wait_ready(server)
            if not restart:
                for y in (319, 1000, -900):
                    for i in range(30):
                        probe(server, 'setup', y)
                        result = probe(server, 'remove', f'y{y}-i{i}', y)
                        assert result['y'] == y and result['blocks'] == 256
                        assert result['nanos'] > 0 and result['allocatedBytes'] >= 0
                        if i >= 10: metrics.append(result)
                probe(server, 'regression')
                if args.pregen:
                    # No force-load tickets, plugins, futures or generated chunks retained by the job.
                    server.send('pregen start world 4096 4096 16 4')
                    server.wait('Pregeneration started: 9 chunks', 30)
                    server.send('pregen pause')
                    server.wait('PAUSED', 30)
                    server.send('pregen resume')
                    server.wait('Pregeneration world:', 30)
                    deadline = time.monotonic() + 180
                    while True:
                        server.send('pregen status')
                        line = server.wait('Pregeneration world:', 30)
                        if 'COMPLETED 9/9' in line: break
                        if 'FAILED' in line or time.monotonic() >= deadline: raise RuntimeError(line.strip())
                        time.sleep(1)
                    server.send('forceload query')
                    server.barrier()
                    server.send('pregen start world 4096 4096 16 4')
                    server.wait('Pregeneration started: 9 chunks', 30)
                    server.send('pregen cancel')
                    server.wait('CANCELLED', 30)
                    # Loading an already edited chunk must preserve all of its contents.
                    deadline = time.monotonic() + 30
                    while True:
                        server.send('pregen status')
                        line = server.wait('Pregeneration world:', 30)
                        if 'pending=false' in line: break
                        if time.monotonic() >= deadline: raise RuntimeError(line.strip())
                        time.sleep(.25)
                    server.send('pregen start world 1024 1024 0 4')
                    server.wait('Pregeneration started: 1 chunks', 30)
                    deadline = time.monotonic() + 30
                    while True:
                        server.send('pregen status')
                        line = server.wait('Pregeneration world:', 30)
                        if 'COMPLETED 1/1' in line: break
                        if 'FAILED' in line or time.monotonic() >= deadline: raise RuntimeError(line.strip())
                        time.sleep(.25)
            time.sleep(2)  # Lighting validation follows this delay; the delay alone is not a check.
            probe(server, 'verify')
            saving.append(save(server))
            server.release_forced_chunks_and_save()
        finally:
            server.close()
        snapshots.append(fingerprint(directory / 'world'))
        if args.pregen:
            for x in range(255, 258):
                for z in range(255, 258):
                    chunk = CHUNKS.read_chunk(directory / 'world', x, z)
                    assert chunk['Status'] == 'minecraft:full', 'Pregenerated chunk was not saved'
    assert snapshots[0] == snapshots[1], 'Saved WorldEdit blocks/biomes/heightmaps changed after restart'
    results = {}
    for y in (319, 1000, -900):
        rows = [r for r in metrics if r['y'] == y]
        results[str(y)] = {key: statistics.median(r[key] for r in rows) for key in ('nanos', 'allocatedBytes')}
    summary = {
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'sourceWorkingTreeModified': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()),
        'label': args.label, 'workDirectory': str(directory), 'worldRange': [-1024, 1024], 'seed': 8142026,
        'workerThreads': 1, 'players': 0, 'warmupPerScenario': 10, 'measurementsPerScenario': 20,
        'sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in
                   [('jar', jar), ('probe', plugin), ('datapack', datapack), ('worldedit', worldedit)]},
        'metrics': metrics, 'medians': results, 'savedFingerprints': snapshots, 'saving': saving,
        'pregen': args.pregen, 'limitations': ['No connected clients; packet sections decoded on the server',
            'Actual WorldEdit API EditSession, no player commands/permissions/clipboard history UI',
            'Times include EditSession construction, edits and close; exclude setup and asynchronous follow-up work',
            'One shared-host fixture, not production TPS or client rendering measurements'],
    }
    if args.compare_summary:
        baseline = json.loads(args.compare_summary.read_text())
        assert baseline['sha256']['probe'] == summary['sha256']['probe']
        assert baseline['sha256']['datapack'] == summary['sha256']['datapack']
        assert baseline['sha256']['worldedit'] == summary['sha256']['worldedit']
        assert baseline['savedFingerprints'][0] == snapshots[0], 'Baseline/candidate saved world differs'
        summary['baselineSavedFingerprintMatched'] = True
    (directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('Results: ' + str(directory / 'summary.json'), flush=True)


if __name__ == '__main__':
    main()
