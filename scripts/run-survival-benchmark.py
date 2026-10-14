#!/usr/bin/env python3
"""Run deterministic, plugin-free loads in a fresh isolated survival server."""
import argparse
import datetime
import hashlib
import json
import os
import platform
from pathlib import Path
import queue
import re
import socket
import subprocess
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def prepare(directory, seed, accept, port=25575):
    directory.mkdir(parents=True, exist_ok=False)
    (directory / 'eula.txt').write_text('eula=' + str(accept).lower() + '\n')
    (directory / 'server.properties').write_text(f'''level-seed={seed}
level-name=world
level-type=minecraft:normal
gamemode=survival
difficulty=normal
server-ip=127.0.0.1
server-port={port}
online-mode=true
view-distance=6
simulation-distance=4
pause-when-empty-seconds=-1
max-players=1
spawn-protection=0
enable-query=false
enable-rcon=false
motd=Cubve isolated survival benchmark
''')


class Server:
    def __init__(self, java, jar, directory, recording):
        self.directory = directory
        self.lines = queue.Queue()
        self.log = (directory / ('console-' + recording + '.log')).open('w')
        self.process = subprocess.Popen([
            java, '-Xms2g', '-Xmx2g', '-Dcubve.profile.ticks=true',
            f'-XX:StartFlightRecording=filename={recording}.jfr,settings={directory / "profile.jfc"},dumponexit=true,maxsize=256m',
            '-jar', str(jar), '--nogui'], cwd=directory, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        def reader():
            for line in self.process.stdout:
                self.log.write(line); self.log.flush(); self.lines.put(line)
        self.reader = threading.Thread(target=reader, daemon=True)
        self.reader.start()
        try:
            self.wait('Done (', 180)
        except BaseException:
            self.process.terminate()
            try: self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill(); self.process.wait()
            self.reader.join(timeout=5); self.log.close()
            raise

    def wait(self, text, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f'Server exited with {self.process.returncode}; inspect {self.directory}')
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if text in line:
                return line
        raise TimeoutError(f'Timed out waiting for {text}; inspect {self.directory}')

    def send(self, command):
        self.process.stdin.write(command + '\n'); self.process.stdin.flush()

    def barrier(self):
        marker = 'CUBVE_MARKER_' + uuid.uuid4().hex
        self.send('say ' + marker)
        self.wait('[Server] ' + marker)

    def close(self):
        try:
            if self.process.poll() is None:
                self.send('stop')
                try:
                    self.process.wait(timeout=180)
                except subprocess.TimeoutExpired:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        self.process.kill(); self.process.wait()
                    raise
            if self.process.returncode != 0:
                raise RuntimeError(f'Server exited with {self.process.returncode}; inspect {self.directory}')
        finally:
            self.reader.join(timeout=5)
            self.log.close()


def parse_item_count(line):
    line = re.sub(r'\x1b\[[0-9;]*m', '', line)
    return sum(int(count) for count in re.findall(r'\b(?:count|Count):\s*(\d+)', line))


def item_count(server, target):
    server.barrier()
    server.send(f'data get {target} Items')
    line = server.wait('has the following', 30)
    return parse_item_count(line)


def frozen_item_counts(server, targets):
    server.send('tick freeze'); server.barrier()
    try:
        return [item_count(server, target) for target in targets]
    finally:
        server.send('tick unfreeze'); server.barrier()


def validate_hoppers(server):
    """Check transfers, replacement, redstone, holder containers and minecarts without plugins."""
    def commands(*values):
        for command in values:
            server.send(command)
        server.barrier()

    def conserved(source, targets, amount):
        counts = frozen_item_counts(server, [source, *targets])
        source_count, destination_count = counts[0], sum(counts[1:])
        if source_count + destination_count != amount or destination_count == 0:
            raise RuntimeError(f'Item conservation/transfer failed: {source_count} + {destination_count} != {amount}')
        return source_count, destination_count

    print('Validating hopper transfers and item conservation', flush=True)
    commands('fill 32 119 0 63 119 31 stone', 'fill 32 120 0 63 122 31 air',
             'setblock 47 120 4 hopper[facing=east]',
             'setblock 48 120 4 chest[facing=north,type=left]',
             'setblock 49 120 4 chest[facing=north,type=right]',
             'item replace block 47 120 4 container.0 with minecraft:cobblestone 64')
    source = 'block 47 120 4'
    chests = ['block 48 120 4', 'block 49 120 4']
    time.sleep(2)
    conserved(source, chests, 64)
    marker = 'CUBVE_DOUBLE_CHEST_' + uuid.uuid4().hex
    server.send('execute if block 48 120 4 chest[type=left] if block 49 120 4 chest[type=right] run say ' + marker)
    server.wait('[Server] ' + marker, 30)
    commands('setblock 47 120 3 redstone_block')
    time.sleep(.5)
    locked = conserved(source, chests, 64)
    time.sleep(2)
    if conserved(source, chests, 64) != locked:
        raise RuntimeError('Powered hopper moved items')
    commands('setblock 47 120 3 air')
    time.sleep(2)
    if conserved(source, chests, 64)[0] >= locked[0]:
        raise RuntimeError('Hopper did not resume after redstone removal')

    commands('setblock 47 120 3 redstone_block', 'setblock 48 120 4 barrel',
             'setblock 49 120 4 air', 'kill @e[type=minecraft:item]',
             'item replace block 47 120 4 container.0 with minecraft:cobblestone 64',
             'setblock 47 120 3 air')
    time.sleep(2)
    conserved(source, ['block 48 120 4'], 64)

    commands('setblock 40 120 12 hopper[facing=east]', 'setblock 41 120 12 stone',
             'setblock 40 121 12 composter[level=8]')
    time.sleep(2)
    if item_count(server, 'block 40 120 12') != 1:
        raise RuntimeError('Composter output was not collected')
    commands('setblock 40 120 20 hopper[facing=east]', 'setblock 41 120 20 stone',
             'setblock 40 121 20 rail',
             'summon minecraft:chest_minecart 40.5 121.0625 20.5 {Tags:["cubve_hopper_test"],NoGravity:1b}',
             'item replace entity @e[type=minecraft:chest_minecart,tag=cubve_hopper_test,limit=1] container.0 with minecraft:cobblestone 64')
    time.sleep(2)
    conserved('entity @e[type=minecraft:chest_minecart,tag=cubve_hopper_test,limit=1]', ['block 40 120 20'], 64)
    return {'doubleChestAcrossChunkBoundary':True, 'redstoneLockAndResume':True,
            'replacementDestination':True, 'composterOutput':True, 'minecartSource':True,
            'itemConservation':True}


def run(args):
    jar = Path(args.jar).resolve()
    if not jar.is_file():
        raise SystemExit(f'Build the server first: {jar}')
    if not args.accept_eula and not args.prepare_only:
        raise SystemExit('Review the Minecraft EULA and use --accept-eula, or use --prepare-only.')
    directory = ROOT / 'run/benchmarks' / ('survival-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6])
    port = 25575
    if not args.prepare_only:
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
    prepare(directory, args.seed, args.accept_eula, port)
    print('Work directory:', directory, flush=True)
    if args.prepare_only:
        return
    subprocess.run([args.java, str(ROOT/'scripts/MakeSurvivalJfc.java'), str(directory/'profile.jfc')], check=True)
    phases = []
    validation = None
    server = None
    try:
        server = Server(args.java, jar, directory, 'profile')
        for command in ['gamerule minecraft:advance_time false', 'time set day',
                        'gamerule minecraft:advance_weather false', 'weather clear', 'forceload add 0 0 63 63']:
            server.send(command)
        server.barrier()
        time.sleep(args.warmup)

        def phase(name, commands):
            print('Measuring:', name, flush=True)
            start = int(time.time() * 1000)
            for command in commands: server.send(command)
            server.barrier()
            time.sleep(args.seconds)
            server.barrier()
            time.sleep(.2)
            phases.append({'name':name, 'startMs':start, 'endMs':int(time.time()*1000)})

        phase('idle', [])
        phase('new-chunks', ['forceload add 4096 4096 4159 4159'])
        server.send('forceload remove 4096 4096 4159 4159')
        server.barrier(); time.sleep(args.warmup)
        for command in ['fill 0 119 0 31 119 31 stone', 'fill 0 120 0 31 120 31 hopper[facing=east]']:
            server.send(command)
        for x in range(32):
            for z in range(32):
                server.send(f'item replace block {x} 120 {z} container.0 with minecraft:cobblestone 64')
        server.barrier(); time.sleep(args.warmup)
        phase('1024-hoppers', [])
        server.send('fill 0 120 0 31 120 31 air')
        server.barrier(); time.sleep(args.warmup)
        # No player is simulated. This covers passive entity bookkeeping, not active mob AI or networking.
        for i in range(256):
            x,z = 2*(i%16)+.5, 2*(i//16)+.5
            server.send(f'summon minecraft:cow {x} 122 {z} {{NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Tags:["cubve_bench"]}}')
        server.barrier(); time.sleep(args.warmup)
        phase('256-passive-entities', [])
        if args.validate_hoppers:
            validation = validate_hoppers(server)
        server.send('setblock 0 120 0 minecraft:hopper')
        server.send('save-all flush'); server.barrier()
        server.close(); server = None
        # Verify saved terrain/block entities after a clean restart of the same world.
        print('Verifying clean restart and saved hopper', flush=True)
        server = Server(args.java, jar, directory, 'restart')
        marker = 'CUBVE_PERSISTENCE_' + uuid.uuid4().hex
        server.send('execute if block 0 120 0 minecraft:hopper run say ' + marker)
        server.wait('[Server] ' + marker, 30)
        if validation is not None:
            total = sum(frozen_item_counts(server, ['block 47 120 4', 'block 48 120 4']))
            if total != 64:
                raise RuntimeError(f'Items changed across save/restart: {total} != 64')
            validation['itemsPersistedAcrossRestart'] = True
        server.send('server'); server.barrier()
        server.close(); server = None
    finally:
        if server is not None: server.close()
        (directory / 'phases.json').write_text(json.dumps(phases, indent=2)+'\n')
    summaries = []
    for phase in phases:
        result = subprocess.run([args.java, str(ROOT/'scripts/ReadSurvivalJfr.java'), str(directory/'profile.jfr'), str(phase['startMs']), str(phase['endMs'])], text=True, capture_output=True, check=True)
        summary = json.loads(result.stdout)
        if summary['ticks'] == 0:
            raise RuntimeError('No per-tick samples; use a Cubve build with the profiling hook.')
        summaries.append({**phase, **summary})
    report = {'seed':args.seed, 'seconds':args.seconds, 'warmupSeconds':args.warmup,
              'players':0, 'plugins':[], 'port':port, 'jar':str(jar), 'workDirectory':str(directory),
              'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
              'javaVersion':subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr.strip(),
              'host':platform.platform(), 'jvmFlags':['-Xms2g', '-Xmx2g', '-Dcubve.profile.ticks=true'],
              'persistenceRestartVerified':True, 'hopperValidation':validation, 'scenarios':summaries}
    (directory/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Results:', directory/'summary.json', flush=True)
    for r in summaries:
        print(f"{r['name']}: {r['ticks']} ticks, mean {r['meanMs']:.3f} ms, p95 {r['p95Ms']:.3f}, p99 {r['p99Ms']:.3f}",flush=True)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',default=str(Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-25-openjdk'))/'bin/java'))
    parser.add_argument('--jar',default=str(ROOT/'paper-server/build/libs/cubve-paperclip-26.3.local-SNAPSHOT.jar'))
    parser.add_argument('--seed',type=int,default=8142026)
    parser.add_argument('--seconds',type=int,default=30)
    parser.add_argument('--warmup',type=int,default=15)
    parser.add_argument('--accept-eula',action='store_true')
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--validate-hoppers',action='store_true',help='After measurement, check native hopper transfers and saved item counts.')
    args=parser.parse_args()
    if not 10 <= args.seconds <= 300 or not 0 <= args.warmup <= 300: parser.error('Use 10–300 seconds and 0–300 warm-up seconds.')
    run(args)
