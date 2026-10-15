#!/usr/bin/env python3
"""Measure forced chunk generation and compare saved terrain in isolated worlds.

Console forceload synchronously generates requested full chunks; it does not simulate
player exploration, networking or ordinary ticking. Generation also creates neighbor
chunks which are excluded from the requested-chunk throughput and terrain comparison.
The default single generation worker limits scheduling variance in this fixture;
production server defaults remain unchanged. Use --worker-threads -1 for Paper's
automatic worker policy, which can produce different feature placement between runs.
Measured targets retain their force-load tickets through saving, and fingerprints
require generation postprocessing to have completed before either terrain snapshot.
Tickets are then removed and saved before shutdown; restarted targets load only
after ticking is frozen again, preventing startup ticks from changing their terrain.
"""
import argparse
import datetime
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import queue
import re
import shutil
import socket
import struct
import subprocess
import threading
import time
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
ANSI = re.compile(r'\x1b\[[0-9;]*m')
JVM_FLAGS = ['-Xms2g', '-Xmx2g']
FIXTURE_POLICY = 'entity-ticking-before-save;remove-force-tickets-before-stop;reload-after-freeze'
HASH_POLICY = {
    'included': ['all section block-state cells', 'all section biome cells',
                 'Heightmaps', 'structures', 'block_entities except keepPacked', 'Status', 'blending_data',
                 'below_zero_retrogen', 'UpgradeData', 'xPos', 'yPos', 'zPos'],
    'excluded': ['LastUpdate', 'InhabitedTime', 'lighting', 'entities',
                 'block-entity keepPacked loading marker', 'scheduled block/fluid ticks', 'PostProcessing',
                 'DataVersion', 'Paper persistent data'],
    'description': 'Semantic block/biome cells, sorted compound keys; structure reference arrays are sorted. '
                   'Block-entity content (including generated loot seeds) is included; '
                   'entities and tick/light metadata are outside this terrain check.',
}


class NbtReader:
    """Read modern big-endian Anvil NBT, including tag types 1 through 12."""
    def __init__(self, data):
        self.data = memoryview(data)
        self.offset = 0

    def take(self, count):
        if count < 0 or self.offset + count > len(self.data):
            raise ValueError('Truncated or invalid NBT length')
        value = self.data[self.offset:self.offset + count]
        self.offset += count
        return value

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.take(struct.calcsize('>' + fmt)))[0]

    def string(self):
        # DataInput.readUTF uses modified UTF-8 (NUL and UTF-16 surrogate pairs).
        data = bytes(self.take(self.number('H'))).replace(b'\xc0\x80', b'\0')
        value = data.decode('utf-8', 'surrogatepass')
        return value.encode('utf-16-le', 'surrogatepass').decode('utf-16-le', 'surrogatepass')

    def length(self):
        count = self.number('i')
        if not 0 <= count <= 10_000_000:
            raise ValueError('Invalid NBT array/list length')
        return count

    def payload(self, kind, depth=0):
        if depth > 64:
            raise ValueError('NBT nesting is too deep')
        formats = {1:'b', 2:'h', 3:'i', 4:'q', 5:'f', 6:'d'}
        if kind in formats:
            return self.number(formats[kind])
        if kind == 7:
            return list(bytes(self.take(self.length())))
        if kind == 8:
            return self.string()
        if kind == 9:
            element, count = self.number('B'), self.length()
            if element == 0 and count:
                raise ValueError('Nonempty NBT list cannot contain end tags')
            values = [self.payload(element, depth + 1) for _ in range(count)]
            if element == 10:
                # ListTag.addAndUnwrap unwraps heterogenous-list wrappers exactly once.
                values = [value[''] if len(value) == 1 and '' in value else value for value in values]
            return values
        if kind == 10:
            result = {}
            while True:
                element = self.number('B')
                if element == 0:
                    return result
                name = self.string()
                if name in result:
                    raise ValueError('Duplicate NBT compound key')
                result[name] = self.payload(element, depth + 1)
        if kind in (11, 12):
            count = self.length()
            fmt, size = ('i', 4) if kind == 11 else ('q', 8)
            return [item[0] for item in struct.iter_unpack('>' + fmt, self.take(count * size))]
        raise ValueError(f'Unsupported NBT tag type {kind}')


def read_nbt(data):
    reader = NbtReader(data)
    if reader.number('B') != 10:
        raise ValueError('Chunk NBT root must be a compound')
    reader.string()
    value = reader.payload(10)
    if reader.offset != len(reader.data):
        raise ValueError('Trailing bytes after chunk NBT')
    return value


def decompress_nbt(data, compression):
    if compression == 1:
        data = gzip.decompress(data)
    elif compression == 2:
        data = zlib.decompress(data)
    elif compression != 3:
        raise ValueError(f'Unsupported Anvil compression {compression}; use gzip/zlib/uncompressed')
    if len(data) > 32 * 1024 * 1024:
        raise ValueError('Chunk NBT exceeds 32 MiB')
    return read_nbt(data)


def region_directory(world):
    modern = world / 'dimensions/minecraft/overworld/region'
    return modern if modern.is_dir() else world / 'region'


def read_chunk(world, x, z):
    region = region_directory(world) / f'r.{x // 32}.{z // 32}.mca'
    with region.open('rb') as file:
        file.seek(((x % 32) + (z % 32) * 32) * 4)
        location = file.read(4)
        if len(location) != 4:
            raise ValueError(f'Incomplete region header: {region}')
        entry = int.from_bytes(location, 'big')
        sector, count = entry >> 8, entry & 255
        if sector < 2 or not count:
            raise ValueError(f'Missing saved chunk {x},{z}')
        file.seek(sector * 4096)
        size_data = file.read(4)
        if len(size_data) != 4:
            raise ValueError('Missing Anvil chunk length')
        size = int.from_bytes(size_data, 'big')
        if not 1 <= size <= count * 4096 - 4:
            raise ValueError('Invalid Anvil chunk length')
        data = file.read(size)
        if len(data) != size:
            raise ValueError('Truncated Anvil chunk')
    compression = data[0]
    if compression & 128:
        if size != 1:
            raise ValueError('External Anvil chunk must have length one')
        data = (region.parent / f'c.{x}.{z}.mcc').read_bytes()
    else:
        data = data[1:]
    return decompress_nbt(data, compression & 127)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def palette_hash(container, cells, minimum_bits):
    """Hash every decoded cell; ignore palette ordering and unused packed bits."""
    palette = container.get('palette')
    if not isinstance(palette, list) or not palette:
        raise ValueError('Missing block/biome palette')
    data = container.get('data', [])
    if len(palette) == 1:
        return hashlib.sha256((canonical(palette[0]) + b'\n') * cells).hexdigest()
    bits = max(minimum_bits, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    if len(data) != (cells + per_long - 1) // per_long:
        raise ValueError('Invalid packed block/biome array size')
    values = [canonical(value) + b'\n' for value in palette]
    result = hashlib.sha256()
    for i in range(cells):
        index = ((data[i // per_long] & ((1 << 64) - 1)) >> ((i % per_long) * bits)) & ((1 << bits) - 1)
        if index >= len(palette):
            raise ValueError('Packed cell index exceeds its palette')
        result.update(values[index])
    return result.hexdigest()


def terrain_fingerprint(chunk, x, z):
    if (chunk.get('xPos'), chunk.get('zPos')) != (x, z):
        raise ValueError(f'Wrong saved chunk coordinate for {x},{z}')
    if chunk.get('Status') != 'minecraft:full':
        raise ValueError(f'Chunk {x},{z} has not completed generation')
    if chunk.get('yPos') != -4:
        raise ValueError('Normal overworld chunk must start at section Y=-4')
    sections = []
    for section in chunk.get('sections', []):
        if 'block_states' not in section and 'biomes' not in section:
            continue  # Extra lighting-only sections are outside the terrain.
        if 'block_states' not in section or 'biomes' not in section:
            raise ValueError('Terrain section must contain both blocks and biomes')
        sections.append({'Y':section['Y'],
                         'blocks':palette_hash(section['block_states'], 4096, 4),
                         'biomes':palette_hash(section['biomes'], 64, 1)})
    sections.sort(key=lambda section: section['Y'])
    section_ys = {section['Y'] for section in sections}
    if section_ys != set(range(-4, 20)) or len(section_ys) != len(sections):
        raise ValueError('Normal overworld chunk must contain exactly terrain sections Y=-4..19')
    if 'Heightmaps' not in chunk or 'structures' not in chunk:
        raise ValueError('Missing heightmap/structure terrain data')
    structures = dict(chunk['structures'])
    if 'References' in structures:
        structures['References'] = {name:sorted(values) for name, values in structures['References'].items()}
    terrain = {name:chunk[name] for name in ('xPos', 'yPos', 'zPos', 'Status', 'Heightmaps',
               'blending_data', 'below_zero_retrogen', 'UpgradeData') if name in chunk}
    block_entities = [{name:value for name, value in entity.items() if name != 'keepPacked'}
                      for entity in chunk.get('block_entities', [])]
    block_entities.sort(key=canonical)
    terrain.update(sections=sections, structures=structures, block_entities=block_entities)
    return digest(terrain)


def fingerprints(world, coordinates):
    result = {}
    for x, z in coordinates:
        chunk = read_chunk(world, x, z)
        if not postprocessing_completed(chunk):
            raise ValueError(f'Chunk {x},{z} has pending generation postprocessing')
        result[f'{x},{z}'] = terrain_fingerprint(chunk, x, z)
    return result


def postprocessing_completed(chunk):
    if 'PostProcessing' not in chunk:
        raise ValueError('Missing generation postprocessing data')
    positions = chunk.get('PostProcessing')
    if positions is None:
        return True
    if not isinstance(positions, list) or any(section is not None and not isinstance(section, list) for section in positions):
        raise ValueError('Invalid generation postprocessing data')
    if any(section is not None and any(not isinstance(offset, int) or not 0 <= offset <= 4095 for offset in section) for section in positions):
        raise ValueError('Invalid generation postprocessing offset')
    return all(section is None or section == [] for section in positions)


def pending_entity_ticking(dump, coordinates):
    if dump.get('data-version') != 2 or not isinstance(dump.get('worlds'), list):
        raise ValueError('Unsupported native chunk debug dump schema')
    worlds = [world for world in dump['worlds'] if world.get('name') == 'minecraft:overworld']
    if len(worlds) != 1:
        raise ValueError('Native chunk debug dump must contain exactly one overworld')
    holders = worlds[0].get('chunk-holder-manager', {}).get('chunkholders')
    if not isinstance(holders, list):
        raise ValueError('Missing native chunk debug holders')
    states = {}
    for holder in holders:
        key = holder['chunkX'], holder['chunkZ']
        state = holder['current_chunk_full_status']
        if key in states or state not in ('INACCESSIBLE', 'FULL', 'BLOCK_TICKING', 'ENTITY_TICKING'):
            raise ValueError('Invalid or duplicate native chunk debug holder')
        states[key] = state
    return [[x, z] for x, z in coordinates if states.get((x, z)) != 'ENTITY_TICKING']


def preserve_before_restart(directory):
    """Copy the closed world once so failures can be investigated offline."""
    destination = directory / 'before-restart-world'
    shutil.copytree(directory / 'world', destination)
    return destination


def regions(batches, width, warmup=False):
    start = -4096 if warmup else 256
    return [(start + i * 128, start + (i % 3) * 128, width) for i in range(batches)]


def chunk_coordinates(areas):
    return [[x, z] for left, top, width in areas for x in range(left, left + width) for z in range(top, top + width)]


def prepare(directory, seed, accept, port=25575, worker_threads=1):
    directory.mkdir(parents=True, exist_ok=False)
    (directory / 'eula.txt').write_text(f'eula={str(accept).lower()}\n')
    (directory / 'server.properties').write_text(f'''level-seed={seed}
level-name=world
level-type=minecraft:normal
gamemode=survival
difficulty=normal
server-ip=127.0.0.1
server-port={port}
online-mode=true
view-distance=2
simulation-distance=2
pause-when-empty-seconds=-1
max-players=1
spawn-protection=0
enable-query=false
enable-rcon=false
motd=Cubve isolated chunk benchmark
''')
    (directory / 'config').mkdir()
    (directory / 'config/paper-global.yml').write_text(
        f'_version: 31\nchunk-system:\n  worker-threads: {worker_threads}\n')


class Server:
    def __init__(self, java, jar, directory, label, jfr=False):
        self.directory, self.lines = directory, queue.Queue()
        self.log = (directory / f'console-{label}.log').open('w')
        flags = JVM_FLAGS + (['-Dcubve.profile.ticks=true',
            f'-XX:StartFlightRecording=filename={label}.jfr,settings={directory / "profile.jfc"},dumponexit=true,maxsize=256m'] if jfr else [])
        try:
            self.process = subprocess.Popen([java, *flags, '-jar', str(jar), '--nogui'], cwd=directory,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        except BaseException:
            self.log.close()
            raise
        def reader():
            for line in self.process.stdout:
                self.log.write(line); self.log.flush(); self.lines.put(ANSI.sub('', line))
        self.reader = threading.Thread(target=reader, daemon=True)
        self.reader.start()
        try:
            self.wait('Done (', 240)
        except BaseException:
            self.close()
            raise

    def wait(self, text, timeout=600):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f'Server exited {self.process.returncode}; inspect {self.directory}')
            try:
                line = self.lines.get(timeout=min(1, max(.01, deadline - time.monotonic())))
            except queue.Empty:
                continue
            if text in line:
                return line
        raise TimeoutError(f'Waiting for {text}; inspect {self.directory}')

    def send(self, command):
        self.process.stdin.write(command + '\n'); self.process.stdin.flush()

    def barrier(self, timeout=600):
        marker = 'CUBVE_CHUNK_' + uuid.uuid4().hex
        self.send('say ' + marker)
        self.wait('[Server] ' + marker, timeout)

    def freeze(self):
        for command in ['tick freeze', 'gamerule minecraft:random_tick_speed 0',
                        'gamerule minecraft:spawn_mobs false', 'gamerule minecraft:advance_time false',
                        'time set day', 'gamerule minecraft:advance_weather false', 'weather clear']:
            self.send(command)
        self.barrier()

    def load(self, area, keep_loaded=False):
        x, z, width = area
        arguments = f'{x * 16} {z * 16} {(x + width) * 16 - 1} {(z + width) * 16 - 1}'
        self.barrier()  # Clear previous feedback before the timer begins.
        start_ms = int(time.time() * 1000)
        start = time.perf_counter()
        self.send('forceload add ' + arguments)
        # ServerLevel.setChunkForced calls getChunk synchronously before this feedback.
        self.wait(f'Marked {width * width} chunks in minecraft:overworld ')
        duration = time.perf_counter() - start
        self.last_phase = {'startMs':start_ms, 'endMs':int(time.time() * 1000)}
        self.barrier()
        if not keep_loaded:
            self.send('forceload remove ' + arguments)
            self.barrier()
        return duration

    def wait_entity_ticking(self, coordinates, deadline):
        started = time.monotonic()
        attempts, counts, files = 0, [], []
        while True:
            self.send('paper debug chunks')
            prefix = 'Writing chunk information dump to '
            line = self.wait(prefix, max(.01, deadline - time.monotonic()))
            self.wait('Successfully written chunk information!', max(.01, deadline - time.monotonic()))
            self.barrier(timeout=max(.01, deadline - time.monotonic()))
            path = (self.directory / line.split(prefix, 1)[1].strip()).resolve()
            if not path.is_relative_to(self.directory.resolve()):
                raise ValueError('Native chunk debug dump path escapes the fixture')
            pending = pending_entity_ticking(json.loads(path.read_text()), coordinates)
            attempts += 1
            counts.append(len(pending)); files.append(str(path))
            if not pending:
                return {'statusAttempts':attempts, 'statusElapsedSeconds':time.monotonic() - started,
                        'statusPendingChunkCounts':counts, 'statusDumpFiles':files}
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f'{len(pending)} requested chunks have not reached ENTITY_TICKING before save: {pending[:8]}')
            if attempts == 1 or counts[-1] != counts[-2] or attempts % 10 == 0:
                print(f'Waiting for native chunk completion: {len(pending)} requested chunks pending (status attempt {attempts})', flush=True)
            time.sleep(min(1, remaining))

    def save(self, coordinates, timeout=120):
        # FULL completion precedes BLOCK_TICKING promotion and its generation postprocessing.
        # Check actual flushed NBT rather than assuming a delay proves completion.
        started = time.monotonic()
        deadline = started + timeout
        # ENTITY_TICKING follows the BLOCK_TICKING callback that completes generation
        # postprocessing. Verify it before the first save, while generated chunks remain
        # unsaved; clearing postprocessing alone does not mark an already-saved chunk dirty.
        status = self.wait_entity_ticking(coordinates, deadline)
        attempts, counts = 0, []
        while True:
            self.send('save-all flush')
            self.wait('Saved the game', max(.01, deadline - time.monotonic()))
            self.barrier(timeout=max(.01, deadline - time.monotonic()))
            attempts += 1
            pending = []
            for x, z in coordinates:
                chunk = read_chunk(self.directory / 'world', x, z)
                if (chunk.get('xPos'), chunk.get('zPos')) != (x, z) or chunk.get('Status') != 'minecraft:full':
                    raise ValueError(f'Missing or incomplete saved chunk {x},{z}')
                if not postprocessing_completed(chunk):
                    pending.append([x, z])
            counts.append(len(pending))
            if not pending:
                return {'attempts':attempts, 'elapsedSeconds':time.monotonic() - started,
                        'pendingChunkCounts':counts, **status}
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f'{len(pending)} requested chunks still have pending generation postprocessing after {timeout}s: {pending[:8]}')
            if attempts == 1 or counts[-1] != counts[-2] or attempts % 10 == 0:
                print(f'Waiting for generation postprocessing: {len(pending)} requested chunks pending (save attempt {attempts})', flush=True)
            time.sleep(min(1, remaining))

    def verify_no_forced_chunks(self):
        self.send('forceload query')
        self.wait('No force loaded chunks were found in minecraft:overworld', 120)
        self.barrier()

    def release_forced_chunks_and_save(self):
        self.send('forceload remove all')
        self.barrier()
        self.verify_no_forced_chunks()
        # Persist ticket removal so no target loads and ticks before restart freeze.
        self.send('save-all flush')
        self.wait('Saved the game', 120)
        self.barrier()

    def close(self):
        try:
            if self.process.poll() is None:
                self.send('stop')
                try:
                    self.process.wait(timeout=180)
                except subprocess.TimeoutExpired:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        self.process.kill(); self.process.wait()
                    raise
            if self.process.returncode != 0:
                raise RuntimeError(f'Server exited {self.process.returncode}; inspect {self.directory}')
        finally:
            self.reader.join(timeout=5)
            self.process.stdin.close(); self.process.stdout.close(); self.log.close()


def compare_reports(current, baseline):
    if current.get('fixturePolicy') != baseline.get('fixturePolicy'):
        raise ValueError('Cannot compare different chunk fixture policies')
    if current.get('generationWorkerThreads', -1) != baseline.get('generationWorkerThreads', -1):
        raise ValueError('Cannot compare different generation worker policies')
    for name in ('schemaVersion', 'seed', 'coordinates', 'restartGenerationCoordinates', 'hashPolicy'):
        if current[name] != baseline[name]:
            raise ValueError(f'Cannot compare different {name}')
    expected = {f'{x},{z}' for x, z in current['coordinates']}
    new_expected = {f'{x},{z}' for x, z in current['restartGenerationCoordinates']}
    for report in (baseline, current):
        before, after = report['terrainBeforeRestart'], report['terrainAfterRestart']
        if set(before) != expected or set(after) != expected:
            raise ValueError('Missing or unexpected terrain fingerprints')
        if before != after:
            raise ValueError('Terrain changed after save/restart')
        if set(report['terrainAfterRestartGeneration']) != new_expected:
            raise ValueError('Missing newly generated terrain after restart')
        combined = {**after, **report['terrainAfterRestartGeneration']}
        if combined != report['terrainFinal']:
            raise ValueError('Final terrain fingerprints do not match their source groups')
    differences = [key for key in sorted(expected | new_expected)
                   if current['terrainFinal'][key] != baseline['terrainFinal'][key]]
    if differences:
        raise ValueError(f'Terrain differs in {len(differences)} chunks: {", ".join(differences[:8])}')
    return {'terrainIdentical':True, 'comparedChunks':len(expected | new_expected),
            'baselineChunksPerSecond':baseline['requestedChunksPerSecond'],
            'currentChunksPerSecond':current['requestedChunksPerSecond'],
            'throughputRatio':current['requestedChunksPerSecond'] / baseline['requestedChunksPerSecond'],
            'performancePassThreshold':None}


def run(args):
    jar = Path(args.jar).resolve()
    if not jar.is_file():
        raise ValueError(f'Missing server JAR: {jar}')
    if not args.accept_eula:
        raise ValueError('Minecraft EULA acceptance is required: use --accept-eula')
    label = re.sub(r'[^a-zA-Z0-9_-]', '-', args.label)[:32]
    directory = Path(args.output).resolve() / ('chunks-' + label + '-' +
        datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6])
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    prepare(directory, args.seed, True, port, args.worker_threads)
    print('Work directory:', directory, flush=True)
    if args.jfr:
        subprocess.run([args.java, str(ROOT / 'scripts/MakeSurvivalJfc.java'), str(directory / 'profile.jfc')], check=True)
    areas = regions(args.batches, args.batch_width)
    restart_area = regions(args.batches + 1, args.batch_width)[-1]
    warmup = regions(args.warmup_batches, args.batch_width, warmup=True)
    report = {'schemaVersion':1, 'label':args.label, 'seed':args.seed, 'coordinates':chunk_coordinates(areas),
              'fixturePolicy':FIXTURE_POLICY,
              'restartGenerationCoordinates':chunk_coordinates([restart_area]),
              'warmupCoordinates':chunk_coordinates(warmup), 'hashPolicy':HASH_POLICY,
              'jar':str(jar), 'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
              'javaVersion':subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr.strip(),
              'host':platform.platform(), 'jvmFlags':JVM_FLAGS + (['-Dcubve.profile.ticks=true',
                   f'-XX:StartFlightRecording=filename=generation.jfr,settings={directory / "profile.jfc"},dumponexit=true,maxsize=256m'] if args.jfr else []),
              'jfrEnabled':args.jfr,
              'generationWorkerThreads':args.worker_threads,
              'players':0, 'plugins':[], 'ticksFrozen':True, 'port':port, 'workDirectory':str(directory),
              'method':__doc__.strip(), 'batches':[]}
    server = None
    try:
        server = Server(args.java, jar, directory, 'generation', args.jfr)
        server.freeze()
        for area in warmup:
            server.load(area)
        for index, area in enumerate(areas):
            print(f'Measuring batch {index + 1}/{len(areas)}: {area}', flush=True)
            duration = server.load(area, keep_loaded=True)
            report['batches'].append({'index':index, 'area':list(area), 'seconds':duration, **server.last_phase,
                                      'requestedChunks':args.batch_width ** 2,
                                      'requestedChunksPerSecond':args.batch_width ** 2 / duration})
        report['generationSave'] = server.save(report['coordinates'])
        server.release_forced_chunks_and_save()
        server.close(); server = None
        snapshot = preserve_before_restart(directory)
        report['terrainBeforeRestart'] = fingerprints(snapshot, report['coordinates'])
        report['postprocessingCompletedBeforeSnapshot'] = True
        server = Server(args.java, jar, directory, 'restart', args.jfr)
        server.freeze()
        server.verify_no_forced_chunks()
        for area in areas:
            server.load(area, keep_loaded=True)
        print('Generating a new area after restart:', restart_area, flush=True)
        report['restartGenerationSeconds'] = server.load(restart_area, keep_loaded=True)
        report['restartSave'] = server.save(report['coordinates'] + report['restartGenerationCoordinates'])
        server.release_forced_chunks_and_save()
        server.close(); server = None
        report['terrainAfterRestart'] = fingerprints(directory / 'world', report['coordinates'])
        report['terrainAfterRestartGeneration'] = fingerprints(directory / 'world', report['restartGenerationCoordinates'])
        report['terrainFinal'] = {**report['terrainAfterRestart'], **report['terrainAfterRestartGeneration']}
        if report['terrainBeforeRestart'] != report['terrainAfterRestart']:
            raise ValueError('Terrain changed after save/restart')
        count = len(report['coordinates'])
        report.update(requestedChunks=count, completedRequestedChunks=len(report['terrainBeforeRestart']),
                      measuredSeconds=sum(batch['seconds'] for batch in report['batches']),
                      saveRestartVerified=True, forcedTicketsRemovedBeforeShutdown=True)
        report['requestedChunksPerSecond'] = count / report['measuredSeconds']
        if args.compare_summary:
            report['comparison'] = compare_reports(report, json.loads(Path(args.compare_summary).read_text()))
    except BaseException as error:
        report['error'] = str(error)
        raise
    finally:
        try:
            if server is not None:
                server.close()
        finally:
            (directory / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Results: {directory / "summary.json"}', flush=True)
    print(f'{report["completedRequestedChunks"]} requested full chunks; '
          f'{report["requestedChunksPerSecond"]:.2f} requested chunks/s; saved terrain identical after restart.', flush=True)
    if 'comparison' in report:
        print(f'Same-seed terrain matches baseline; throughput ratio {report["comparison"]["throughputRatio"]:.3f} '
              '(diagnostic measurement, no automatic performance pass threshold).', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-25-openjdk')) / 'bin/java'))
    parser.add_argument('--seed', type=int, default=8142026)
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--label', default='measurement')
    parser.add_argument('--output', default=str(ROOT / 'run/benchmarks'), help='Parent directory; a unique new fixture is always created.')
    parser.add_argument('--batches', type=int, default=4)
    parser.add_argument('--batch-width', type=int, default=4)
    parser.add_argument('--warmup-batches', type=int, default=2)
    parser.add_argument('--worker-threads', type=int, choices=[-1, 1], default=1,
                        help='Generation-worker policy in this fixture: 1 limits scheduling variance; -1 uses Paper automatic selection.')
    parser.add_argument('--compare-summary')
    parser.add_argument('--jfr', action='store_true', help='Record chunk-generation/tick events using the existing profiling JFC helper.')
    args = parser.parse_args()
    if not 4 <= args.batches <= 32 or not 2 <= args.batch_width <= 16 or not 0 <= args.warmup_batches <= 8:
        parser.error('Use 4–32 measured batches, width 2–16, and 0–8 warm-up batches.')
    run(args)
