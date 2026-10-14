"""Exercise the selective update policy against local Git repositories."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'update-upstream.sh'


class UpstreamPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.paper = self.root / 'paper'
        self.fork = self.root / 'fork'
        self.paper.mkdir()
        self.git(self.paper, 'init', '-b', 'main')
        self.configure(self.paper)
        (self.paper / 'gradle.properties').write_text('mcVersion=26.3\n')
        self.base = self.commit(self.paper, 'Base')
        self.git(self.root, 'clone', str(self.paper), str(self.fork))
        self.configure(self.fork)
        (self.fork / 'build-data').mkdir()
        (self.fork / 'build-data/upstream.properties').write_text(
            f'repository={self.paper}\nbranch=main\ncommit={self.base}\nminecraftVersion=26.3\n'
        )
        (self.fork / 'build-data/upstream-fixes.txt').write_text('# Reviewed fixes\n')
        (self.fork / 'scripts').mkdir()
        shutil.copyfile(SCRIPT, self.fork / 'scripts/update-upstream.sh')
        self.commit(self.fork, 'Fork setup')

    def git(self, cwd, *args):
        return subprocess.run(['git', *args], cwd=cwd, text=True,
                              capture_output=True, check=True).stdout.strip()

    def configure(self, cwd):
        self.git(cwd, 'config', 'user.name', 'Policy test')
        self.git(cwd, 'config', 'user.email', 'policy@example.invalid')
        self.git(cwd, 'config', 'commit.gpgsign', 'false')

    def commit(self, cwd, message):
        self.git(cwd, 'add', '.')
        self.git(cwd, 'commit', '-m', message)
        return self.git(cwd, 'rev-parse', 'HEAD')

    def run_update(self, *args):
        return subprocess.run(['bash', 'scripts/update-upstream.sh', *args],
                              cwd=self.fork, text=True, capture_output=True)

    def test_selected_fix_excludes_general_change_and_records_provenance(self):
        (self.paper / 'general-feature.txt').write_text('not selected\n')
        self.commit(self.paper, 'General feature')
        (self.paper / 'security-fix.txt').write_text('selected\n')
        fix = self.commit(self.paper, 'Security fix')
        result = self.run_update('fixes', 'security', fix, fix)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.fork / 'general-feature.txt').exists())
        self.assertTrue((self.fork / 'security-fix.txt').exists())
        self.assertIn(fix, self.git(self.fork, 'log', '--format=%B'))
        records = (self.fork / 'build-data/upstream-fixes.txt').read_text()
        self.assertEqual(1, records.count(fix))
        self.assertIn(f'{fix} security', records)
        self.assertIn(self.base, (self.fork / 'build-data/upstream.properties').read_text())
        before = self.git(self.fork, 'rev-parse', 'HEAD')
        repeated = self.run_update('fixes', 'security', fix)
        self.assertEqual(0, repeated.returncode, repeated.stderr)
        self.assertEqual(before, self.git(self.fork, 'rev-parse', 'HEAD'))

    def test_dirty_checkout_is_untouched(self):
        (self.fork / 'gradle.properties').write_text('mcVersion=26.3\n# local work\n')
        before = self.git(self.fork, 'rev-parse', 'HEAD')
        result = self.run_update('version', 'main')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Commit or stash', result.stderr)
        self.assertEqual(before, self.git(self.fork, 'rev-parse', 'HEAD'))
        self.assertIn('# local work', (self.fork / 'gradle.properties').read_text())

    def test_fix_from_another_version_is_rejected_before_changes(self):
        (self.paper / 'gradle.properties').write_text('mcVersion=26.4\n')
        fix = self.commit(self.paper, 'New version')
        before = self.git(self.fork, 'rev-parse', 'HEAD')
        result = self.run_update('fixes', 'crash', fix)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('another Minecraft version', result.stderr)
        self.assertEqual(before, self.git(self.fork, 'rev-parse', 'HEAD'))
        self.assertEqual('', self.git(self.fork, 'status', '--porcelain'))

    def test_same_version_cannot_be_blanket_merged(self):
        (self.paper / 'feature.txt').write_text('general feature\n')
        self.commit(self.paper, 'Feature')
        result = self.run_update('version', 'main')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.fork / 'feature.txt').exists())

    def test_version_upgrade_merges_full_base_and_updates_record(self):
        (self.paper / 'feature.txt').write_text('included with new version\n')
        (self.paper / 'gradle.properties').write_text('mcVersion=26.4\n')
        target = self.commit(self.paper, 'New version base')
        result = self.run_update('version', 'main')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.fork / 'feature.txt').exists())
        record = (self.fork / 'build-data/upstream.properties').read_text()
        self.assertIn(f'commit={target}', record)
        self.assertIn('minecraftVersion=26.4', record)
        self.assertEqual('', self.git(self.fork, 'status', '--porcelain'))

    def test_unknown_category_and_options_as_commits_are_rejected(self):
        for args in [('fixes', 'feature', self.base), ('fixes', 'security', '--abort')]:
            with self.subTest(args=args):
                result = self.run_update(*args)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual('', self.git(self.fork, 'status', '--porcelain'))


if __name__ == '__main__':
    unittest.main()
