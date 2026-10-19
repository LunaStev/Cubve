#!/usr/bin/env python3
"""Measure native /clone allocation and verify disposable worlds, not WorldEdit/TPS.

Uses an explicitly installed benchmark-only probe; no production instrumentation.
Counter/timer samples include native parsing/dispatch and synchronous clone work,
not queued lighting, other-thread allocation, packet fanout or client rendering.
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
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
Z = 4096  # Keep pending-tick tests outside startup spawn chunks on both boots.
SEED = 8142026
SCENARIOS = [('normal16K', 135, '', 16384), ('normal32K', 151, '', 32768),
             ('force32K', 151, 'replace force', 32768),
             ('strict32K', 151, 'strict replace normal', 32768),
             ('move16K', 135, 'replace move', 16384)]


def commands(server, *values):
    for value in values:
        server.send(value)
    server.barrier()


def require(server, condition):
    marker = 'CLONE_CHECK_' + uuid.uuid4().hex
    server.send('execute ' + condition + ' run say ' + marker)
    server.wait('[Server] ' + marker, 30)
    server.barrier()


def probe(server, label, arguments, count):
    server.send(f'cubvecloneprobe {label} 1 {arguments}')
    result = None
    while True:
        line = server.wait('CUBVE_CLONE_PROBE', 90)
        if 'CUBVE_CLONE_PROBE_ERROR' in line:
            raise RuntimeError(line.strip())
        if 'CUBVE_CLONE_PROBE_DONE' in line:
            if f'label={label} repetitions=1' not in line or result is None:
                raise ValueError('Unexpected or incomplete probe completion')
            server.barrier()
            return result
        prefix = 'CUBVE_CLONE_PROBE '
        if prefix not in line or result is not None:
            raise ValueError('Missing or duplicate probe result')
        result = json.loads(line.split(prefix, 1)[1])
        if (result.get('label'), result.get('iteration'), result.get('dispatch'),
                result.get('success'), result.get('result')) != (label, 0, True, True, count):
            raise ValueError(f'Wrong actual native command result: {result}')
        if result['allocatedBytes'] < 0 or result['nanos'] <= 0:
            raise ValueError('Invalid allocation/time counters')


def regressions(server):
    """Return independently expected cells plus source/destination block-entity pairs."""
    expected = {}
    def cell(x, z, material, y=120):
        expected[(x, y, Z + z)] = 'minecraft:' + material
    def row(z, start, states):
        for offset, material in enumerate(states):
            server.send(f'setblock {start+offset} 120 {Z+z} {material}')
            cell(start+offset, z, material)
        server.barrier()
    def clone(z, source, end, dest, suffix=''):
        commands(server, f'clone {source} 120 {Z+z} {end} 120 {Z+z} {dest} 120 {Z+z} {suffix}')
    commands(server, f'fill 0 120 {Z+64} 127 135 {Z+79} air strict',
             f'fill 0 119 {Z+64} 127 119 {Z+79} stone strict')
    states = ['stone', 'glass', 'redstone_torch', 'gold_block']
    row(64, 0, states); clone(64, 0, 3, 16)
    for i, material in enumerate(states):
        cell(16+i, 64, material)
    row(66, 0, ['stone', 'air', 'glass', 'air']); row(66, 16, ['dirt']*4)
    clone(66, 0, 3, 16, 'masked normal')
    for i, material in enumerate(['stone', 'dirt', 'glass', 'dirt']):
        cell(16+i, 66, material)
    row(68, 0, ['stone', 'glass', 'stone', 'glass']); row(68, 16, ['dirt']*4)
    clone(68, 0, 3, 16, 'filtered minecraft:stone normal')
    for i, material in enumerate(['stone', 'dirt', 'stone', 'dirt']):
        cell(16+i, 68, material)
    for z, source, dest, suffix, final in [
            (70, 0, 2, 'replace force', ['stone', 'glass', *states]),
            (72, 0, 2, 'replace move', ['air', 'air', *states]),
            (74, 2, 0, 'replace move', [*states, 'air', 'air'])]:
        row(z, source, states); clone(z, source, source+3, dest, suffix)
        for i, material in enumerate(final):
            cell(i, z, material)
    commands(server, f'setblock 0 120 {Z+76} chest{{Items:[{{Slot:0b,id:"minecraft:diamond",count:17}}]}}',
             f'setblock 1 120 {Z+76} oak_sign',
             f'data merge block 1 120 {Z+76} {{front_text:{{messages:[{{text:"Cubve clone"}},"","",""]}}}}',
             f'setblock 2 120 {Z+76} decorated_pot{{item:{{id:"minecraft:diamond",count:5}}}}',
             f'clone 0 120 {Z+76} 2 120 {Z+76} 16 120 {Z+76}',
             # Exercise block-entity/non-solid placement order in overlapping moves both ways.
             f'clone 16 120 {Z+76} 18 120 {Z+76} 17 120 {Z+76} replace move',
             f'clone 17 120 {Z+76} 19 120 {Z+76} 16 120 {Z+76} replace move',
             # Changing support power schedules a torch block tick; frozen ticks preserve it.
             f'setblock 8 120 {Z+78} redstone_torch',
             f'setblock 8 119 {Z+78} redstone_block',
             f'clone 8 119 {Z+78} 8 120 {Z+78} 24 119 {Z+78}')
    for x, material in [(0, 'chest'), (1, 'oak_sign'), (2, 'decorated_pot')]:
        cell(x, 76, material); cell(x+16, 76, material)
    cell(19, 76, 'air')
    for x in (8, 24):
        cell(x, 78, 'redstone_torch'); cell(x, 78, 'redstone_block', 119)
    require(server, f'if blocks 0 120 {Z+76} 2 120 {Z+76} 16 120 {Z+76} all')
    return expected, [((x, 120, Z+76), (x+16, 120, Z+76)) for x in range(3)]


def saved_snapshot(world, expected, pairs):
    cache = {}
    def chunk(x, z):
        key = x // 16, z // 16
        if key not in cache:
            cache[key] = CHUNKS.read_chunk(world, *key)
        return cache[key]
    def block(x, y, z):
        section = next(s for s in chunk(x, z)['sections'] if s['Y'] == y // 16)
        container = section['block_states']; palette = container['palette']
        if len(palette) == 1:
            value = palette[0]
            return {'Name': value} if isinstance(value, str) else value
        index = (y & 15)*256 + (z & 15)*16 + (x & 15)
        bits = max(4, (len(palette)-1).bit_length()); per_long = 64 // bits
        packed = container['data'][index // per_long] & ((1 << 64)-1)
        value = palette[(packed >> ((index % per_long)*bits)) & ((1 << bits)-1)]
        return {'Name': value} if isinstance(value, str) else value
    def entity(position):
        x, y, z = position
        matches = [e for e in chunk(x, z).get('block_entities', []) if
                   (e.get('x'), e.get('y'), e.get('z')) == position]
        if len(matches) != 1:
            raise ValueError(f'Missing or duplicate block entity at {position}')
        return {k:v for k,v in matches[0].items() if k not in ('x', 'y', 'z', 'keepPacked')}
    cells = {}
    for position, material in expected.items():
        state = block(*position)
        if state['Name'] != material:
            raise ValueError(f'Saved block {position}: {state} != {material}')
        cells[','.join(map(str, position))] = state
    stone_count = 0
    for left in (0, 64):
        for x in range(left, left+32):
            for y in range(120, 152):
                for z in range(Z, Z+32):
                    if block(x, y, z)['Name'] != 'minecraft:stone':
                        raise ValueError(f'Expected persisted stone at {(x,y,z)}')
                    stone_count += 1
    entities = []
    for source, destination in pairs:
        a, b = entity(source), entity(destination)
        if a != b:
            raise ValueError(f'Block entity changed in clone: {source} -> {destination}')
        entities.append(a)
    chest, sign, pot = entities
    if not any(i.get('id') == 'minecraft:diamond' and i.get('count') == 17 for i in chest.get('Items', [])):
        raise ValueError('Cloned chest lost its expected diamond stack')
    if 'Cubve clone' not in json.dumps(sign) or pot.get('item', {}).get('count') != 5:
        raise ValueError('Cloned sign text or pot contents differ from expected data')
    ticks = []
    for x in (8, 24):
        matches = [t for t in chunk(x, Z+78).get('block_ticks', []) if
                   (t.get('x'), t.get('y'), t.get('z'), t.get('i')) == (x, 120, Z+78, 'minecraft:redstone_torch')]
        if len(matches) != 1:
            raise ValueError('Expected original and copied pending redstone torch ticks')
        ticks.append({k:v for k,v in matches[0].items() if k not in ('x', 'y', 'z')})
    if ticks[0] != ticks[1]:
        raise ValueError('Copied pending tick does not preserve delay/priority')
    return {'stoneCells':stone_count, 'regressionCells':cells, 'blockEntities':entities, 'pendingTicks':ticks}


def compare(report, baseline):
    for key in ('schemaVersion', 'seed', 'probeSha256', 'jvmFlags', 'javaVersion', 'host',
                'generationWorkerThreads', 'warmup', 'samples', 'scenarioDefinitions', 'savedAfterRestart'):
        if baseline.get(key) != report.get(key):
            raise ValueError('Cannot compare incompatible clone report field ' + key)
    result = {}
    for scenario in report['scenarios']:
        old = next(s for s in baseline['scenarios'] if s['name'] == scenario['name'])
        result[scenario['name']] = {
            'allocatedBytesMedianRatio':scenario['allocatedBytesMedian']/old['allocatedBytesMedian'],
            'nanosMedianRatio':scenario['nanosMedian']/old['nanosMedian'],
            'performancePassThreshold':None}
    return result


def run(args):
    if not args.accept_eula:
        raise ValueError('Review/accept Minecraft EULA before using --accept-eula')
    jar, probe_jar = Path(args.jar).resolve(), Path(args.probe_jar).resolve()
    if not jar.is_file() or not probe_jar.is_file():
        raise ValueError('Both server and separately built probe JARs must exist')
    label = re.sub('[^A-Za-z0-9_-]', '-', args.label)[:32].strip('-_') or 'measurement'
    directory = ROOT / 'run/benchmarks' / ('clone-' + label + '-' +
        datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6])
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    CHUNKS.prepare(directory, SEED, True, port, 1)
    properties = (directory/'server.properties').read_text().replace('gamemode=survival', 'gamemode=creative').replace('difficulty=normal', 'difficulty=peaceful')
    (directory/'server.properties').write_text(properties)
    (directory/'plugins').mkdir(); shutil.copy2(probe_jar, directory/'plugins/clone-probe.jar')
    shutil.copy2(Path(__file__), directory/'runner.py')
    report = {'schemaVersion':1, 'scope':'native clone only; not WorldEdit or production TPS',
              'label':args.label, 'seed':SEED, 'jar':str(jar), 'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
              'probeSha256':hashlib.sha256(probe_jar.read_bytes()).hexdigest(), 'host':platform.platform(),
              'javaVersion':subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr.strip(),
              'jvmFlags':CHUNKS.JVM_FLAGS, 'jfrEnabled':False, 'generationWorkerThreads':1,
              'warmup':args.warmup, 'samples':args.samples, 'scenarioDefinitions':[list(s) for s in SCENARIOS],
              'players':0, 'plugins':['CubveCloneProbe'], 'ticksFrozen':True, 'workDirectory':str(directory),
              'limitations':['No connected clients or WorldEdit', 'Only current-thread synchronous allocation/time',
                             'No lighting, packet, client, undo or production-spike improvement claim',
                             'Pending block ticks preserved while frozen; redstone execution after unfreeze not tested'],
              'scenarios':[]}
    coordinates = CHUNKS.chunk_coordinates([(0, Z//16, 8)])
    print('Work directory:', directory, flush=True)
    server = None
    try:
        server = CHUNKS.Server(args.java, jar, directory, 'measurement', jfr=False)
        server.freeze(); server.load((0, Z//16, 8), keep_loaded=True)
        server.wait_entity_ticking(coordinates, time.monotonic()+120)
        commands(server, f'fill 0 120 {Z} 31 151 {Z+31} stone strict')
        time.sleep(2)  # Initial generation/edit settling is not part of a measurement.
        for name, ymax, suffix, count in SCENARIOS:
            scenario = {'name':name, 'warmup':[], 'measurements':[]}
            report['scenarios'].append(scenario)
            print('Measuring', name, flush=True)
            for iteration in range(args.warmup+args.samples):
                setup = [f'fill 64 120 {Z} 95 {ymax} {Z+31} air strict']
                if name == 'move16K':
                    setup.append(f'fill 0 120 {Z} 31 {ymax} {Z+31} stone strict')
                commands(server, *setup)
                sample = probe(server, f'{label}-{name}-{iteration:03}',
                               f'0 120 {Z} 31 {ymax} {Z+31} 64 120 {Z} {suffix}', count)
                scenario['warmup' if iteration < args.warmup else 'measurements'].append(sample)
            for metric in ('allocatedBytes', 'nanos'):
                scenario[metric+'Median'] = statistics.median(s[metric] for s in scenario['measurements'])
        expected, pairs = regressions(server)
        commands(server, f'fill 0 120 {Z} 31 151 {Z+31} stone strict',
                 f'clone 0 120 {Z} 31 151 {Z+31} 64 120 {Z}')
        require(server, f'if blocks 0 120 {Z} 31 151 {Z+31} 64 120 {Z} all')
        report['initialSave'] = server.save(coordinates)
        server.release_forced_chunks_and_save(); server.close(); server = None
        before = saved_snapshot(directory/'world', expected, pairs)
        report['savedBeforeRestart'] = before
        server = CHUNKS.Server(args.java, jar, directory, 'restart', jfr=False)
        server.freeze(); server.verify_no_forced_chunks()
        server.load((0, Z//16, 8), keep_loaded=True)
        report['restartSave'] = server.save(coordinates)
        server.release_forced_chunks_and_save(); server.close(); server = None
        after = saved_snapshot(directory/'world', expected, pairs)
        if before != after:
            raise ValueError('Saved regression cells, block entities or pending ticks changed after restart')
        report['savedAfterRestart'] = after; report['saveRestartVerified'] = True
        if args.compare_summary:
            report['comparison'] = compare(report, json.loads(Path(args.compare_summary).read_text()))
    except BaseException as error:
        report['error'] = str(error)
        raise
    finally:
        try:
            if server is not None:
                server.close()
        finally:
            (directory/'summary.json').write_text(json.dumps(report, indent=2)+'\n')
    print('Results:', directory/'summary.json', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--jar', required=True)
    parser.add_argument('--probe-jar', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-25-openjdk'))/'bin/java'))
    parser.add_argument('--warmup', type=int, default=10)
    parser.add_argument('--samples', type=int, default=20)
    parser.add_argument('--compare-summary')
    args = parser.parse_args()
    if args.warmup < 10 or args.samples < 20 or args.warmup > 100 or args.samples > 100:
        parser.error('Use 10–100 warm-up invocations and 20–100 measurements per scenario')
    run(args)
