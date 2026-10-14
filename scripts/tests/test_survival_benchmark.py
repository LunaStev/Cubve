import importlib.util
import io
from pathlib import Path
import queue
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('survival_benchmark', Path(__file__).resolve().parents[1] / 'run-survival-benchmark.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SurvivalFixtureTest(unittest.TestCase):
    def test_inventory_counts_survive_colored_native_snbt_output(self):
        line = 'has the following block data: [{\x1b[96mcount\x1b[0m: \x1b[33m58\x1b[0m}, {count: 6}]'
        self.assertEqual(64, module.parse_item_count(line))
        self.assertEqual(0, module.parse_item_count('has the following block data: []'))

    def test_command_barrier_ignores_input_echo_until_server_confirms_execution(self):
        server = module.Server.__new__(module.Server)
        server.process = SimpleNamespace(stdin=io.StringIO(), poll=lambda: None)
        server.lines = queue.Queue()
        server.lines.put('> say CUBVE_MARKER_test\n')
        server.lines.put('[INFO]: [Not Secure] [Server] CUBVE_MARKER_test\n')
        with patch.object(module.uuid, 'uuid4', return_value=SimpleNamespace(hex='test')):
            server.barrier()
        self.assertTrue(server.lines.empty())
        self.assertEqual('say CUBVE_MARKER_test\n', server.process.stdin.getvalue())

    def test_preparation_is_local_survival_and_does_not_accept_eula_implicitly(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'world'
            module.prepare(directory, 8142026, False)
            props = (directory / 'server.properties').read_text()
            self.assertIn('level-seed=8142026\n', props)
            self.assertIn('server-ip=127.0.0.1\n', props)
            self.assertIn('gamemode=survival\n', props)
            self.assertIn('enable-rcon=false\n', props)
            self.assertEqual('eula=false\n', (directory / 'eula.txt').read_text())

    def test_existing_world_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'world'
            module.prepare(directory, 1, False)
            with self.assertRaises(FileExistsError):
                module.prepare(directory, 2, True)
            self.assertIn('level-seed=1\n', (directory / 'server.properties').read_text())
            self.assertEqual('eula=false\n', (directory / 'eula.txt').read_text())
