import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('extended_height', Path(__file__).resolve().parents[1] / 'run-extended-height-validation.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ExtendedHeightIntegrityTests(unittest.TestCase):
    def chunk(self):
        return {'sections': [{'Y': y, 'block_states': {'palette': [{'Name': 'minecraft:air'}]},
                              'biomes': {'palette': ['minecraft:plains']}, 'SkyLight': [255]}
                             for y in range(-64, 64)],
                'Heightmaps': {'WORLD_SURFACE': [0]},
                'block_entities': [{'id': 'minecraft:chest', 'x': 1025, 'y': 990, 'z': 1025,
                                    'Items': [{'count': 13}], 'keepPacked': 1}]}

    def fingerprint(self, chunk):
        with patch.object(MODULE.CHUNKS, 'read_chunk', return_value=copy.deepcopy(chunk)):
            return MODULE.fingerprint(Path('/unused-world'))

    def test_all_128_sections_are_required_without_duplicates(self):
        original = self.chunk()
        self.fingerprint(original)
        for changed in [original['sections'][:-1], original['sections'] + [original['sections'][0]]]:
            broken = self.chunk()
            broken['sections'] = changed
            with self.assertRaises(AssertionError):
                self.fingerprint(broken)

    def test_saved_blocks_biomes_heightmaps_and_chest_items_affect_fingerprint(self):
        original = self.chunk()
        baseline = self.fingerprint(original)
        for mutation in ('block', 'biome', 'height', 'item'):
            changed = copy.deepcopy(original)
            if mutation == 'block': changed['sections'][-1]['block_states']['palette'][0]['Name'] = 'minecraft:stone'
            elif mutation == 'biome': changed['sections'][0]['biomes']['palette'][0] = 'minecraft:desert'
            elif mutation == 'height': changed['Heightmaps']['WORLD_SURFACE'][0] = 1
            else: changed['block_entities'][0]['Items'][0]['count'] = 12
            self.assertNotEqual(baseline, self.fingerprint(changed), mutation)

    def test_only_documented_volatile_fields_are_ignored(self):
        original = self.chunk()
        changed = copy.deepcopy(original)
        changed['sections'][0]['SkyLight'] = [0]
        changed['block_entities'][0].pop('keepPacked')
        self.assertEqual(self.fingerprint(original), self.fingerprint(changed))

    def test_native_readiness_waits_for_zero_pending_targets(self):
        with patch.object(MODULE, 'probe', side_effect=[{'pending': 2}, {'pending': 1}, {'pending': 0}]) as probe, \
             patch.object(MODULE.time, 'sleep') as sleep:
            result = MODULE.wait_ready(object())
        self.assertEqual(3, probe.call_count)
        self.assertEqual(2, sleep.call_count)
        self.assertEqual(3, result['attempts'])

    def test_probe_failure_cannot_be_treated_as_a_successful_measurement(self):
        class Server:
            def send(self, command): pass
            def wait(self, marker, timeout): return '[Probe] CUBVE_HEIGHT_FAIL remove bad 1000'
        with self.assertRaises(RuntimeError):
            MODULE.probe(Server(), 'remove', 'bad', 1000)


if __name__ == '__main__':
    unittest.main()
