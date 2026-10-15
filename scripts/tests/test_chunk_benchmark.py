import copy
import gzip
import importlib.util
import io
import json
from pathlib import Path
import queue
import struct
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zlib

spec = importlib.util.spec_from_file_location('chunk_benchmark', Path(__file__).resolve().parents[1] / 'run-chunk-benchmark.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def string(value):
    encoded = value.encode('utf-8')
    return struct.pack('>H', len(encoded)) + encoded


def tag(kind, name, payload):
    return bytes([kind]) + string(name) + payload


def compound(*tags):
    return b'\x0a\x00\x00' + b''.join(tags) + b'\0'


def packed(indices, bits):
    per_long = 64 // bits
    result = []
    for start in range(0, len(indices), per_long):
        value = sum(index << (offset * bits) for offset, index in enumerate(indices[start:start + per_long]))
        result.append(value if value < (1 << 63) else value - (1 << 64))
    return result


def chunk():
    return {'xPos':256, 'zPos':256, 'yPos':-4, 'Status':'minecraft:full',
            'sections':[{'Y':y, 'block_states':{'palette':[{'Name':'minecraft:stone'}]},
                         'biomes':{'palette':['minecraft:plains']}} for y in range(-4, 20)],
            'Heightmaps':{'WORLD_SURFACE':[1, 2]}, 'structures':{'References':{'minecraft:village':[9, 2]}, 'starts':{}},
            'block_entities':[{'id':'minecraft:chest', 'x':4096, 'y':60, 'z':4096,
                               'LootTable':'minecraft:chests/simple_dungeon', 'LootTableSeed':127}],
            'LastUpdate':100, 'InhabitedTime':20, 'DataVersion':5000,
            'block_ticks':[], 'fluid_ticks':[], 'PostProcessing':[], 'isLightOn':1}


def report():
    return {'schemaVersion':1, 'seed':8142026, 'coordinates':[[256, 256]],
            'restartGenerationCoordinates':[[384, 384]], 'hashPolicy':module.HASH_POLICY,
            'terrainBeforeRestart':{'256,256':'old'}, 'terrainAfterRestart':{'256,256':'old'},
            'terrainAfterRestartGeneration':{'384,384':'new'}, 'terrainFinal':{'256,256':'old', '384,384':'new'},
            'requestedChunksPerSecond':20}


def status_dump(state='ENTITY_TICKING'):
    return {'data-version':2, 'worlds':[{'name':'minecraft:overworld', 'chunk-holder-manager':{
        'chunkholders':[{'chunkX':256, 'chunkZ':256, 'current_chunk_full_status':state}]}}]}


class ChunkFixtureTest(unittest.TestCase):
    def test_nbt_parses_every_payload_type_and_signed_arrays(self):
        data = compound(
            tag(1, 'byte', struct.pack('>b', -9)), tag(2, 'short', struct.pack('>h', -800)),
            tag(3, 'int', struct.pack('>i', -123456)), tag(4, 'long', struct.pack('>q', -(1 << 50))),
            tag(5, 'float', struct.pack('>f', 1.25)), tag(6, 'double', struct.pack('>d', -3.5)),
            tag(7, 'bytes', struct.pack('>i', 2) + b'\0\xff'), tag(8, 'string', string('minecraft:stone')),
            tag(9, 'list', b'\x03' + struct.pack('>i', 2) + struct.pack('>ii', 3, -4)),
            tag(10, 'compound', tag(8, 'child', string('value')) + b'\0'),
            tag(11, 'ints', struct.pack('>i', 2) + struct.pack('>ii', -1, 4)),
            tag(12, 'longs', struct.pack('>i', 2) + struct.pack('>qq', -(1 << 63), (1 << 63) - 1)))
        result = module.read_nbt(data)
        self.assertEqual(-9, result['byte'])
        self.assertEqual(-800, result['short'])
        self.assertEqual(-123456, result['int'])
        self.assertEqual(-(1 << 50), result['long'])
        self.assertEqual(1.25, result['float'])
        self.assertEqual(-3.5, result['double'])
        self.assertEqual([0, 255], result['bytes'])
        self.assertEqual('minecraft:stone', result['string'])
        self.assertEqual([3, -4], result['list'])
        self.assertEqual({'child':'value'}, result['compound'])
        self.assertEqual([-1, 4], result['ints'])
        self.assertEqual([-(1 << 63), (1 << 63) - 1], result['longs'])

    def test_modified_utf8_null_and_surrogate_pair(self):
        modified = b'a\xc0\x80\xed\xa0\xbd\xed\xb8\x80'
        data = compound(tag(8, 'text', struct.pack('>H', len(modified)) + modified))
        self.assertEqual('a\0😀', module.read_nbt(data)['text'])

    def test_heterogeneous_list_unwraps_strings_and_preserves_real_compounds(self):
        elements = [tag(8, '', string('minecraft:stone')) + b'\0',
                    tag(8, 'Name', string('minecraft:granite')) + b'\0',
                    tag(8, '', string('kept')) + tag(3, 'other', struct.pack('>i', 2)) + b'\0']
        data = compound(tag(9, 'palette', b'\x0a' + struct.pack('>i', len(elements)) + b''.join(elements)))
        self.assertEqual(['minecraft:stone', {'Name':'minecraft:granite'}, {'':'kept', 'other':2}],
                         module.read_nbt(data)['palette'])

    def test_list_wrapper_is_unwrapped_once_without_recursive_compound_unwrapping(self):
        nested = tag(10, '', tag(8, '', string('minecraft:stone')) + b'\0') + b'\0'
        data = compound(tag(9, 'palette', b'\x0a' + struct.pack('>i', 1) + nested))
        self.assertEqual([{'':'minecraft:stone'}], module.read_nbt(data)['palette'])

    def test_empty_key_in_root_and_ordinary_compound_is_preserved(self):
        self.assertEqual({'':'root'}, module.read_nbt(compound(tag(8, '', string('root')))))
        data = compound(tag(10, 'ordinary', tag(8, '', string('child')) + b'\0'))
        self.assertEqual({'ordinary':{'':'child'}}, module.read_nbt(data))

    def test_supported_compression_is_equivalent(self):
        data = compound(tag(3, 'xPos', struct.pack('>i', -20)))
        for compression, encoded in [(1, gzip.compress(data)), (2, zlib.compress(data)), (3, data)]:
            with self.subTest(compression=compression):
                self.assertEqual({'xPos':-20}, module.decompress_nbt(encoded, compression))
        with self.assertRaisesRegex(ValueError, 'compression'):
            module.decompress_nbt(data, 4)

    def test_nbt_rejects_truncation_negative_lengths_duplicates_and_trailing_data(self):
        invalid = [b'\x0a\x00', compound(tag(7, 'bytes', struct.pack('>i', -1))),
                   compound(tag(9, 'list', b'\0' + struct.pack('>i', 1))),
                   compound(tag(1, 'same', b'\1'), tag(1, 'same', b'\2')),
                   compound() + b'\0']
        for data in invalid:
            with self.subTest(data=data):
                with self.assertRaises(ValueError):
                    module.read_nbt(data)

    def test_anvil_reads_negative_coordinates_and_external_payload(self):
        data = compound(tag(3, 'xPos', struct.pack('>i', -1)))
        with tempfile.TemporaryDirectory() as temp:
            world = Path(temp)
            region_dir = world / 'region'
            region_dir.mkdir()
            index = 31 + 31 * 32
            header = bytearray(8192)
            header[index * 4:index * 4 + 4] = ((2 << 8) | 1).to_bytes(4, 'big')
            payload = b'\2' + zlib.compress(data)
            body = len(payload).to_bytes(4, 'big') + payload
            region = region_dir / 'r.-1.-1.mca'
            region.write_bytes(header + body.ljust(4096, b'\0'))
            self.assertEqual({'xPos':-1}, module.read_chunk(world, -1, -1))
            (region_dir / 'c.-1.-1.mcc').write_bytes(zlib.compress(data))
            region.write_bytes(header + (b'\0\0\0\1\x82').ljust(4096, b'\0'))
            self.assertEqual({'xPos':-1}, module.read_chunk(world, -1, -1))
            with self.assertRaisesRegex(ValueError, 'Missing saved chunk'):
                module.read_chunk(world, -2, -1)

    def test_modern_dimension_region_directory_takes_priority_over_legacy(self):
        with tempfile.TemporaryDirectory() as temp:
            world = Path(temp)
            legacy = world / 'region'
            legacy.mkdir()
            self.assertEqual(legacy, module.region_directory(world))
            modern = world / 'dimensions/minecraft/overworld/region'
            modern.mkdir(parents=True)
            self.assertEqual(modern, module.region_directory(world))
            data = compound(tag(3, 'xPos', struct.pack('>i', 256)))
            header = bytearray(8192)
            header[0:4] = ((2 << 8) | 1).to_bytes(4, 'big')
            payload = b'\2' + zlib.compress(data)
            body = len(payload).to_bytes(4, 'big') + payload
            (modern / 'r.8.8.mca').write_bytes(header + body.ljust(4096, b'\0'))
            (legacy / 'r.8.8.mca').write_bytes(b'invalid stale region')
            self.assertEqual({'xPos':256}, module.read_chunk(world, 256, 256))

    def test_hash_ignores_volatile_metadata_light_sections_and_loading_marker(self):
        original = chunk()
        changed = copy.deepcopy(original)
        changed.update(LastUpdate=1000, InhabitedTime=200, DataVersion=5001, entities=[{'id':'minecraft:pig'}],
                       block_ticks=[1], fluid_ticks=[2], PostProcessing=[[12]], isLightOn=0, ChunkBukkitValues={'x':'y'})
        changed['sections'][0].update(SkyLight=[255] * 2048, BlockLight=[0] * 2048)
        changed['sections'].append({'Y':-5, 'SkyLight':[1]})
        changed['block_entities'][0]['keepPacked'] = 1
        self.assertEqual(module.terrain_fingerprint(original, 256, 256), module.terrain_fingerprint(changed, 256, 256))

    def test_hash_catches_blocks_biomes_heightmaps_structures_and_loot_changes(self):
        original = chunk()
        baseline = module.terrain_fingerprint(original, 256, 256)
        for field in ['blocks', 'biomes', 'heightmaps', 'structures', 'loot']:
            changed = copy.deepcopy(original)
            if field == 'blocks': changed['sections'][0]['block_states']['palette'][0]['Name'] = 'minecraft:dirt'
            if field == 'biomes': changed['sections'][0]['biomes']['palette'][0] = 'minecraft:desert'
            if field == 'heightmaps': changed['Heightmaps']['WORLD_SURFACE'][0] += 1
            if field == 'structures': changed['structures']['starts']['minecraft:village'] = {'ChunkX':1}
            if field == 'loot': changed['block_entities'][0]['LootTableSeed'] += 1
            with self.subTest(field=field):
                self.assertNotEqual(baseline, module.terrain_fingerprint(changed, 256, 256))

    def test_hash_is_independent_of_compound_palette_and_structure_reference_order(self):
        original = chunk()
        original['sections'][0]['block_states'] = {'palette':[{'Name':'minecraft:stone'}, {'Name':'minecraft:air'}],
                                                  'data':packed([i % 2 for i in range(4096)], 4)}
        changed = copy.deepcopy(original)
        changed['sections'][0]['block_states'] = {'palette':[{'Name':'minecraft:air'}, {'Name':'minecraft:stone'}],
                                                 'data':packed([1 - i % 2 for i in range(4096)], 4)}
        changed['structures']['References']['minecraft:village'].reverse()
        changed = dict(reversed(list(changed.items())))
        self.assertEqual(module.terrain_fingerprint(original, 256, 256), module.terrain_fingerprint(changed, 256, 256))

    def test_palette_decoder_includes_last_block_and_biome_and_ignores_unused_bits(self):
        states = {'palette':[{'Name':'minecraft:stone'}, {'Name':'minecraft:air'}], 'data':packed([0] * 4096, 4)}
        changed = copy.deepcopy(states)
        changed['data'][-1] = 1 << 60
        self.assertNotEqual(module.palette_hash(states, 4096, 4), module.palette_hash(changed, 4096, 4))
        biomes = {'palette':['minecraft:plains', 'minecraft:desert'], 'data':[0]}
        changed_biomes = {**biomes, 'data':[-(1 << 63)]}
        self.assertNotEqual(module.palette_hash(biomes, 64, 1), module.palette_hash(changed_biomes, 64, 1))
        padding = {'palette':[{'Name':f'test:{i}'} for i in range(17)], 'data':packed([0] * 4096, 5)}
        changed_padding = copy.deepcopy(padding)
        changed_padding['data'][0] = -(1 << 63)
        self.assertEqual(module.palette_hash(padding, 4096, 4), module.palette_hash(changed_padding, 4096, 4))

    def test_invalid_missing_or_incomplete_terrain_does_not_hash_successfully(self):
        for field in ['coordinates', 'status', 'heightmaps', 'sections', 'biomes', 'packed',
                      'missing-both', 'missing-middle', 'yPos', 'missing-yPos']:
            changed = chunk()
            if field == 'coordinates': changed['xPos'] = 257
            if field == 'status': changed['Status'] = 'minecraft:noise'
            if field == 'heightmaps': del changed['Heightmaps']
            if field == 'sections': changed['sections'] = []
            if field == 'biomes': del changed['sections'][0]['biomes']
            if field == 'packed': changed['sections'][0]['block_states'] = {'palette':['a', 'b'], 'data':[]}
            if field == 'missing-both':
                del changed['sections'][12]['block_states']
                del changed['sections'][12]['biomes']
            if field == 'missing-middle': del changed['sections'][12]
            if field == 'yPos': changed['yPos'] = 0
            if field == 'missing-yPos': del changed['yPos']
            with self.subTest(field=field):
                with self.assertRaises(ValueError): module.terrain_fingerprint(changed, 256, 256)

    def test_fingerprints_reject_pending_generation_postprocessing(self):
        for pending in [[[1]], [[], [0]], [None, [12]]]:
            saved = chunk()
            saved['PostProcessing'] = pending
            with self.subTest(pending=pending), patch.object(module, 'read_chunk', return_value=saved):
                with self.assertRaisesRegex(ValueError, 'pending generation postprocessing'):
                    module.fingerprints(Path('/world'), [[256, 256]])
        for completed in [[], [None, [], None], None]:
            saved = chunk()
            saved['PostProcessing'] = completed
            with self.subTest(completed=completed), patch.object(module, 'read_chunk', return_value=saved):
                self.assertEqual({'256,256':module.terrain_fingerprint(saved, 256, 256)},
                                 module.fingerprints(Path('/world'), [[256, 256]]))
        with self.assertRaisesRegex(ValueError, 'Missing generation postprocessing'):
            module.postprocessing_completed({})
        with self.assertRaisesRegex(ValueError, 'Invalid generation postprocessing'):
            module.postprocessing_completed({'PostProcessing':1})
        with self.assertRaisesRegex(ValueError, 'Invalid generation postprocessing offset'):
            module.postprocessing_completed({'PostProcessing':[['invalid']]})

    def test_native_dump_requires_exact_target_entity_ticking_status(self):
        self.assertEqual([], module.pending_entity_ticking(status_dump(), [[256, 256]]))
        for state in ['INACCESSIBLE', 'FULL', 'BLOCK_TICKING']:
            with self.subTest(state=state):
                self.assertEqual([[256, 256]], module.pending_entity_ticking(status_dump(state), [[256, 256]]))
        self.assertEqual([[257, 256]], module.pending_entity_ticking(status_dump(), [[256, 256], [257, 256]]))

    def test_native_dump_rejects_wrong_schema_missing_world_and_duplicate_holders(self):
        for field in ['version', 'world', 'holders', 'duplicate', 'status']:
            dump = status_dump()
            if field == 'version': dump['data-version'] = 3
            if field == 'world': dump['worlds'][0]['name'] = 'minecraft:the_nether'
            if field == 'holders': del dump['worlds'][0]['chunk-holder-manager']['chunkholders']
            if field == 'duplicate': dump['worlds'][0]['chunk-holder-manager']['chunkholders'] *= 2
            if field == 'status': dump['worlds'][0]['chunk-holder-manager']['chunkholders'][0]['current_chunk_full_status'] = 'unknown'
            with self.subTest(field=field):
                with self.assertRaises(ValueError): module.pending_entity_ticking(dump, [[256, 256]])

    def test_comparison_includes_newly_generated_area_after_restart_without_performance_threshold(self):
        baseline = report()
        current = copy.deepcopy(baseline)
        current['requestedChunksPerSecond'] = 10
        compared = module.compare_reports(current, baseline)
        self.assertTrue(compared['terrainIdentical'])
        self.assertEqual(2, compared['comparedChunks'])
        self.assertEqual(.5, compared['throughputRatio'])
        self.assertIsNone(compared['performancePassThreshold'])
        current['terrainAfterRestartGeneration']['384,384'] = 'changed'
        current['terrainFinal']['384,384'] = 'changed'
        with self.assertRaisesRegex(ValueError, 'Terrain differs'):
            module.compare_reports(current, baseline)

    def test_comparison_rejects_restart_changes_missing_hashes_seed_and_coordinate_mismatches(self):
        baseline = report()
        for field in ['seed', 'coordinates', 'restart', 'missing', 'final']:
            current = copy.deepcopy(baseline)
            if field == 'seed': current['seed'] += 1
            if field == 'coordinates': current['coordinates'] = [[1, 2]]
            if field == 'restart': current['terrainAfterRestart']['256,256'] = 'changed'
            if field == 'missing': del current['terrainBeforeRestart']['256,256']
            if field == 'final': current['terrainFinal'] = {}
            with self.subTest(field=field):
                with self.assertRaises(ValueError): module.compare_reports(current, baseline)

    def test_comparison_requires_same_worker_policy_and_treats_old_reports_as_automatic(self):
        baseline, current = report(), report()
        self.assertTrue(module.compare_reports(current, baseline)['terrainIdentical'])
        current['generationWorkerThreads'] = -1
        self.assertTrue(module.compare_reports(current, baseline)['terrainIdentical'])
        current['generationWorkerThreads'] = 1
        with self.assertRaisesRegex(ValueError, 'worker policies'):
            module.compare_reports(current, baseline)
        baseline['generationWorkerThreads'] = 1
        self.assertTrue(module.compare_reports(current, baseline)['terrainIdentical'])

    def test_comparison_rejects_mixing_old_and_new_fixture_policies(self):
        baseline, current = report(), report()
        current['fixturePolicy'] = module.FIXTURE_POLICY
        with self.assertRaisesRegex(ValueError, 'fixture policies'):
            module.compare_reports(current, baseline)
        baseline['fixturePolicy'] = module.FIXTURE_POLICY
        self.assertTrue(module.compare_reports(current, baseline)['terrainIdentical'])

    def test_regions_are_disjoint_chunk_aligned_and_warmup_is_separate(self):
        areas = module.regions(5, 4)
        measured = module.chunk_coordinates(areas)
        warmup = module.chunk_coordinates(module.regions(2, 4, warmup=True))
        self.assertEqual(80, len({tuple(pos) for pos in measured}))
        self.assertFalse(set(map(tuple, measured)) & set(map(tuple, warmup)))
        self.assertEqual(64, len(module.chunk_coordinates(areas[:4])))

    def test_barrier_ignores_command_echo_and_drains_stale_forceload_feedback(self):
        server = module.Server.__new__(module.Server)
        server.process = SimpleNamespace(stdin=io.StringIO(), poll=lambda: None)
        server.lines = queue.Queue()
        server.lines.put('[INFO]: Marked 16 chunks in minecraft:overworld from [0, 0] to [3, 3]\n')
        server.lines.put('> say CUBVE_CHUNK_test\n')
        server.lines.put('[INFO]: [Not Secure] [Server] CUBVE_CHUNK_test\n')
        with patch.object(module.uuid, 'uuid4', return_value=SimpleNamespace(hex='test')):
            server.barrier()
        self.assertTrue(server.lines.empty())
        self.assertEqual('say CUBVE_CHUNK_test\n', server.process.stdin.getvalue())

    def test_load_waits_for_actual_completion_feedback_then_removes_same_chunks(self):
        server = module.Server.__new__(module.Server)
        server.directory, server.lines = Path('/fixture'), queue.Queue()
        server.lines.put('> forceload add 4096 4096 4159 4159\n')
        server.lines.put('[INFO]: Marked 16 chunks in minecraft:overworld from [256, 256] to [259, 259] to be force loaded\n')
        server.send = unittest.mock.Mock()
        server.barrier = unittest.mock.Mock()
        server.process = SimpleNamespace(poll=lambda: None)
        with patch.object(module.time, 'perf_counter', side_effect=[1.0, 2.5]):
            self.assertEqual(1.5, server.load((256, 256, 4)))
        self.assertEqual([unittest.mock.call('forceload add 4096 4096 4159 4159'),
                          unittest.mock.call('forceload remove 4096 4096 4159 4159')], server.send.call_args_list)
        self.assertEqual(3, server.barrier.call_count)
        self.assertTrue(server.lines.empty())

    def test_measured_load_keeps_force_load_ticket_until_snapshot(self):
        server = module.Server.__new__(module.Server)
        server.directory, server.lines = Path('/fixture'), queue.Queue()
        server.lines.put('[INFO]: Marked 16 chunks in minecraft:overworld from [256, 256] to [259, 259] to be force loaded\n')
        server.send, server.barrier = unittest.mock.Mock(), unittest.mock.Mock()
        server.process = SimpleNamespace(poll=lambda: None)
        with patch.object(module.time, 'perf_counter', side_effect=[1.0, 2.5]):
            self.assertEqual(1.5, server.load((256, 256, 4), keep_loaded=True))
        server.send.assert_called_once_with('forceload add 4096 4096 4159 4159')
        self.assertEqual(2, server.barrier.call_count)

    def test_save_checks_flushed_postprocessing_without_unnecessary_delay(self):
        server = module.Server.__new__(module.Server)
        server.directory = Path('/fixture')
        server.barrier, server.send, server.wait = unittest.mock.Mock(), unittest.mock.Mock(), unittest.mock.Mock()
        server.wait_entity_ticking = unittest.mock.Mock(return_value={'statusAttempts':1})
        with patch.object(module.time, 'sleep') as sleep, patch.object(module, 'read_chunk', return_value=chunk()):
            result = server.save([[256, 256]])
        sleep.assert_not_called()
        self.assertEqual(1, server.barrier.call_count)
        server.send.assert_called_once_with('save-all flush')
        self.assertEqual('Saved the game', server.wait.call_args.args[0])
        self.assertEqual(1, result['attempts'])
        self.assertEqual([0], result['pendingChunkCounts'])
        self.assertEqual(1, result['statusAttempts'])

    def test_save_retries_only_pending_postprocessing_until_actual_completion(self):
        server = module.Server.__new__(module.Server)
        server.directory = Path('/fixture')
        server.barrier, server.send, server.wait = unittest.mock.Mock(), unittest.mock.Mock(), unittest.mock.Mock()
        server.wait_entity_ticking = unittest.mock.Mock(return_value={'statusAttempts':1})
        pending = chunk()
        pending['PostProcessing'] = [[12]]
        with patch.object(module.time, 'sleep') as sleep, patch.object(module, 'read_chunk', side_effect=[pending, chunk()]):
            result = server.save([[256, 256]])
        sleep.assert_called_once_with(1)
        self.assertEqual(2, server.barrier.call_count)
        self.assertEqual(2, result['attempts'])
        self.assertEqual([1, 0], result['pendingChunkCounts'])

    def test_save_does_not_retry_missing_or_corrupt_saved_data(self):
        server = module.Server.__new__(module.Server)
        server.directory = Path('/fixture')
        server.barrier, server.send, server.wait = unittest.mock.Mock(), unittest.mock.Mock(), unittest.mock.Mock()
        server.wait_entity_ticking = unittest.mock.Mock(return_value={'statusAttempts':1})
        with patch.object(module.time, 'sleep') as sleep, patch.object(module, 'read_chunk', side_effect=FileNotFoundError('missing region')):
            with self.assertRaises(FileNotFoundError): server.save([[256, 256]])
        sleep.assert_not_called()
        corrupt = chunk()
        corrupt['PostProcessing'] = 'invalid'
        with patch.object(module.time, 'sleep') as sleep, patch.object(module, 'read_chunk', return_value=corrupt):
            with self.assertRaisesRegex(ValueError, 'Invalid generation postprocessing'):
                server.save([[256, 256]])
        sleep.assert_not_called()

    def test_save_timeout_for_pending_postprocessing_is_bounded(self):
        server = module.Server.__new__(module.Server)
        server.directory = Path('/fixture')
        server.barrier, server.send, server.wait = unittest.mock.Mock(), unittest.mock.Mock(), unittest.mock.Mock()
        server.wait_entity_ticking = unittest.mock.Mock(return_value={'statusAttempts':1})
        pending = chunk()
        pending['PostProcessing'] = [[12]]
        with patch.object(module.time, 'monotonic', side_effect=[0, 0, 0, 121]), patch.object(module.time, 'sleep') as sleep, patch.object(module, 'read_chunk', return_value=pending):
            with self.assertRaisesRegex(TimeoutError, 'after 120s'):
                server.save([[256, 256]])
        sleep.assert_not_called()

    def test_native_status_poll_waits_for_dump_completion_and_retries_before_save(self):
        server = module.Server.__new__(module.Server)
        server.directory = Path('/fixture')
        server.send, server.barrier = unittest.mock.Mock(), unittest.mock.Mock()
        server.wait = unittest.mock.Mock(side_effect=[
            '[INFO]: Writing chunk information dump to ./debug/chunks-first.txt\n',
            '[INFO]: Successfully written chunk information!\n',
            '[INFO]: Writing chunk information dump to ./debug/chunks-second.txt\n',
            '[INFO]: Successfully written chunk information!\n'])
        with patch.object(module.time, 'sleep') as sleep, patch.object(Path, 'read_text', side_effect=[
                json.dumps(status_dump('FULL')), json.dumps(status_dump())]):
            result = server.wait_entity_ticking([[256, 256]], module.time.monotonic() + 120)
        sleep.assert_called_once_with(1)
        self.assertEqual(2, result['statusAttempts'])
        self.assertEqual([1, 0], result['statusPendingChunkCounts'])
        self.assertEqual(['/fixture/debug/chunks-first.txt', '/fixture/debug/chunks-second.txt'], result['statusDumpFiles'])
        self.assertEqual([unittest.mock.call('paper debug chunks')] * 2, server.send.call_args_list)
        self.assertEqual(2, server.barrier.call_count)

    def test_native_status_dump_path_cannot_escape_fixture(self):
        server = module.Server.__new__(module.Server)
        server.directory = Path('/fixture')
        server.send, server.barrier = unittest.mock.Mock(), unittest.mock.Mock()
        server.wait = unittest.mock.Mock(side_effect=[
            '[INFO]: Writing chunk information dump to ../outside.txt\n',
            '[INFO]: Successfully written chunk information!\n'])
        with patch.object(Path, 'read_text') as read:
            with self.assertRaisesRegex(ValueError, 'escapes the fixture'):
                server.wait_entity_ticking([[256, 256]], module.time.monotonic() + 120)
        read.assert_not_called()

    def test_native_status_timeout_does_not_allow_first_save(self):
        server = module.Server.__new__(module.Server)
        server.directory = Path('/fixture')
        server.send, server.barrier = unittest.mock.Mock(), unittest.mock.Mock()
        server.wait = unittest.mock.Mock(side_effect=[
            '[INFO]: Writing chunk information dump to ./debug/chunks.txt\n',
            '[INFO]: Successfully written chunk information!\n'])
        with patch.object(module.time, 'monotonic', side_effect=[0, 0, 0, 0, 121]), patch.object(Path, 'read_text', return_value=json.dumps(status_dump('FULL'))):
            with self.assertRaisesRegex(TimeoutError, 'ENTITY_TICKING'):
                server.wait_entity_ticking([[256, 256]], 120)
        server.send.assert_called_once_with('paper debug chunks')

    def test_force_tickets_are_removed_verified_empty_and_flushed_before_stop(self):
        server = module.Server.__new__(module.Server)
        calls = []
        server.send = lambda command: calls.append(('send', command))
        server.wait = lambda text, timeout=600: calls.append(('wait', text))
        server.barrier = lambda: calls.append(('barrier',))
        server.release_forced_chunks_and_save()
        self.assertEqual([('send', 'forceload remove all'), ('barrier',),
                          ('send', 'forceload query'),
                          ('wait', 'No force loaded chunks were found in minecraft:overworld'), ('barrier',),
                          ('send', 'save-all flush'), ('wait', 'Saved the game'), ('barrier',)], calls)

    def test_preparation_is_isolated_and_never_overwrites_existing_world(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'fixture'
            module.prepare(directory, 8142026, False)
            props = (directory / 'server.properties').read_text()
            self.assertIn('level-type=minecraft:normal\n', props)
            self.assertIn('server-ip=127.0.0.1\n', props)
            self.assertIn('online-mode=true\n', props)
            self.assertIn('view-distance=2\n', props)
            self.assertEqual('_version: 31\nchunk-system:\n  worker-threads: 1\n',
                             (directory / 'config/paper-global.yml').read_text())
            self.assertEqual('eula=false\n', (directory / 'eula.txt').read_text())
            with self.assertRaises(FileExistsError): module.prepare(directory, 1, True)
            self.assertIn('level-seed=8142026\n', (directory / 'server.properties').read_text())
            self.assertIn('worker-threads: 1\n', (directory / 'config/paper-global.yml').read_text())

    def test_preparation_can_explicitly_select_paper_automatic_workers(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'fixture'
            module.prepare(directory, 8142026, False, worker_threads=-1)
            self.assertEqual('_version: 31\nchunk-system:\n  worker-threads: -1\n',
                             (directory / 'config/paper-global.yml').read_text())

    def test_closed_world_snapshot_preserves_regions_and_metadata_without_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            world = directory / 'world'
            region = world / 'dimensions/minecraft/overworld/region'
            region.mkdir(parents=True)
            (world / 'level.dat').write_bytes(b'level metadata')
            (region / 'r.8.8.mca').write_bytes(b'original saved region')
            snapshot = module.preserve_before_restart(directory)
            self.assertEqual(b'level metadata', (snapshot / 'level.dat').read_bytes())
            self.assertEqual(b'original saved region', (module.region_directory(snapshot) / 'r.8.8.mca').read_bytes())
            (region / 'r.8.8.mca').write_bytes(b'after restart')
            self.assertEqual(b'original saved region', (module.region_directory(snapshot) / 'r.8.8.mca').read_bytes())
            with self.assertRaises(FileExistsError): module.preserve_before_restart(directory)

    def test_run_never_launches_without_explicit_eula_acceptance(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / 'test.jar'
            jar.write_bytes(b'test')
            with patch.object(module, 'Server') as server:
                with self.assertRaisesRegex(ValueError, 'EULA acceptance'):
                    module.run(SimpleNamespace(jar=str(jar), accept_eula=False))
                server.assert_not_called()

    def test_stuck_process_is_terminated_and_resources_close_even_on_timeout(self):
        server = module.Server.__new__(module.Server)
        server.directory = Path('/fixture')
        server.process = unittest.mock.Mock()
        server.process.poll.return_value = None
        server.process.wait.side_effect = [subprocess.TimeoutExpired('java', 180), None]
        server.reader, server.log = unittest.mock.Mock(), unittest.mock.Mock()
        with self.assertRaises(subprocess.TimeoutExpired):
            server.close()
        server.process.terminate.assert_called_once()
        server.process.kill.assert_not_called()
        server.process.stdin.close.assert_called_once()
        server.process.stdout.close.assert_called_once()
        server.log.close.assert_called_once()
