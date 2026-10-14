"""Release-number, provenance and interrupted-publication tests; no network writes."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import urllib.error
import urllib.parse
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('cubve_release', Path(__file__).resolve().parents[1]/'release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FakeGitHub:
    def __init__(self, commit):
        self.head = commit
        self.tag = None
        self.release = None
        self.calls = []
        self.fail_checksum_once = False
        self.corrupt_digest = False
        self.next_asset = 1

    def request(self, method, path, body=None, *, missing=False, upload=None):
        self.calls.append((method, path))
        base = '/repos/'+module.REPOSITORY
        if '/git/ref/tags/' in path:
            return {'object':{'type':'commit', 'sha':self.tag}} if self.tag else None
        if path == base+'/git/ref/heads/master':
            return {'object':{'type':'commit', 'sha':self.head}}
        if method == 'GET' and '/releases?per_page=' in path:
            return [copy.deepcopy(self.release)] if self.release else []
        if method == 'POST' and path == base+'/git/refs':
            self.tag = body['sha']
            return {}
        if method == 'POST' and path == base+'/releases':
            self.release = {**body, 'id':1, 'assets':[], 'html_url':'https://github.com/LunaStev/Cubve/releases/test',
                            'upload_url':'https://uploads.github.com/repos/LunaStev/Cubve/releases/1/assets{?name,label}'}
            return copy.deepcopy(self.release)
        if method == 'GET' and path == base+'/releases/1':
            return copy.deepcopy(self.release)
        if method == 'DELETE' and '/releases/assets/' in path:
            asset_id = int(path.rsplit('/', 1)[1])
            self.release['assets'] = [a for a in self.release['assets'] if a['id'] != asset_id]
            return None
        if upload:
            name = urllib.parse.parse_qs(urllib.parse.urlsplit(upload).query)['name'][0]
            if name == 'SHA256SUMS' and self.fail_checksum_once:
                self.fail_checksum_once = False
                raise RuntimeError('Simulated interrupted upload')
            digest = module.hashlib.sha256(body).hexdigest() if not self.corrupt_digest else '0'*64
            self.release['assets'].append({'id':self.next_asset, 'name':name, 'state':'uploaded', 'digest':'sha256:'+digest})
            self.next_asset += 1
            return {}
        if method == 'PATCH' and path == base+'/releases/1':
            self.release.update(body)
            return copy.deepcopy(self.release)
        raise AssertionError((method, path))


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-b', 'master')
        self.git('config', 'user.name', 'Release Test')
        self.git('config', 'user.email', 'release@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        (self.root/'gradle.properties').write_text('mcVersion=26.3\n')
        self.base = self.commit('Inherited Paper history')
        (self.root/'build-data').mkdir()
        (self.root/'build-data/release-base.properties').write_text('initialCommit='+self.base+'\n')
        self.sha = self.commit('feat: initialize Cubve')

    def git(self, *args):
        return module.git(self.root, *args)

    def commit(self, subject):
        self.git('add', '.')
        self.git('commit', '--allow-empty', '-m', subject)
        return self.git('rev-parse', 'HEAD')

    def plan(self, release=True):
        return module.plan_release(self.root, release)

    def distribution(self, release=True):
        plan = self.plan(release)
        libs = self.root/'paper-server/build/libs'
        libs.mkdir(parents=True, exist_ok=True)
        mc, number = plan['minecraft'], plan['patch']
        version = f'{mc}.build.{number}-stable' if number else f'{mc}.local-SNAPSHOT'
        with zipfile.ZipFile(libs/f'cubve-server-{version}.jar', 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nBrand-Id: cubve:cubve\r\n'
                         f'Build-Number: {number or ""}\r\nGit-Commit: {self.sha[:7]}\r\n'
                         f'Implementation-Version: {mc}-{number or "DEV"}-{self.sha[:7]}\r\n\r\n')
        name = plan['filename'] if number else f'cubve-paperclip-{version}.jar'
        with zipfile.ZipFile(libs/name, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nMain-Class: io.papermc.paperclip.Main\r\n\r\n')
        directory = self.root/'dist'
        module.package(self.root, plan, directory)
        return plan, directory

    def test_first_patch_is_one_and_failed_planning_consumes_no_number(self):
        self.git('tag', '26.3')
        self.git('tag', 'cubve-26.3-0')
        self.git('tag', 'cubve-26.3-preview')
        before = self.git('show-ref', '--tags')
        first, repeated = self.plan(), self.plan()
        self.assertEqual('cubve-26.3-1.jar', first['filename'])
        self.assertEqual(first, repeated)
        self.assertEqual(before, self.git('show-ref', '--tags'))

    def test_patch_increment_is_numeric_and_rerun_reuses_annotated_tag(self):
        self.git('tag', 'cubve-26.3-2', self.base)
        self.git('tag', 'cubve-26.3-12', self.base)
        self.assertEqual(13, self.plan()['patch'])
        self.git('tag', '-a', 'cubve-26.3-13', '-m', 'Released', self.sha)
        self.assertEqual(13, self.plan()['patch'])

    def test_new_minecraft_version_starts_at_one_with_previous_changes(self):
        self.git('tag', 'cubve-26.3-12')
        (self.root/'gradle.properties').write_text('mcVersion=26.4\n')
        self.sha = self.commit('feat: upgrade Minecraft')
        plan = self.plan()
        self.assertEqual(1, plan['patch'])
        self.assertEqual('cubve-26.4-1.jar', plan['filename'])
        self.assertEqual('cubve-26.3-12', plan['previousTag'])

    def test_development_build_has_no_release_number(self):
        plan = self.plan(False)
        self.assertFalse(plan['publish'])
        self.assertIsNone(plan['patch'])
        self.assertIsNone(plan['tag'])

    def test_notes_list_only_new_commits_and_link_full_changelog(self):
        first = module.release_notes(self.root, self.plan())
        self.assertIn("## What's Changed", first)
        self.assertIn('feat: initialize Cubve by Release Test', first)
        self.assertNotIn('Inherited Paper history', first)
        self.git('tag', 'cubve-26.3-1')
        self.sha = self.commit('fix: preserve item counts')
        second = module.release_notes(self.root, self.plan())
        self.assertIn('fix: preserve item counts', second)
        self.assertNotIn('feat: initialize Cubve', second)
        self.assertIn('cubve-26.3-1...cubve-26.3-2', second)

    def test_notes_do_not_expand_merged_upstream_history(self):
        self.git('tag', 'cubve-26.3-1')
        self.git('checkout', '-b', 'paper')
        self.commit('Upstream implementation detail')
        self.git('checkout', 'master')
        self.commit('fix: Cubve behavior')
        self.git('merge', '--no-ff', 'paper', '-m', 'fix: incorporate reviewed Paper crash fix')
        notes = module.release_notes(self.root, self.plan())
        self.assertIn('incorporate reviewed Paper crash fix', notes)
        self.assertNotIn('Upstream implementation detail', notes)

    def test_invalid_filename_identity_and_versions_are_rejected(self):
        plan = self.plan()
        plan['filename'] = '../outside.jar'
        with self.assertRaises(ValueError):
            module.validate_plan(plan)
        (self.root/'gradle.properties').write_text('mcVersion=../../unsafe\n')
        with self.assertRaises(ValueError):
            self.plan()

    def test_checksum_mismatch_aborts_before_any_github_request(self):
        plan, directory = self.distribution()
        with (directory/plan['filename']).open('ab') as jar:
            jar.write(b'changed')
        api = FakeGitHub(self.sha)
        with self.assertRaises(ValueError):
            module.publish(api, directory, plan)
        self.assertEqual([], api.calls)

    def test_new_release_uploads_verified_files_before_publication(self):
        plan, directory = self.distribution()
        api = FakeGitHub(self.sha)
        result = module.publish(api, directory, plan)
        self.assertEqual('published', result['status'])
        self.assertEqual(self.sha, api.tag)
        self.assertFalse(api.release['draft'])
        self.assertIn("## What's Changed", api.release['body'])
        self.assertEqual({plan['filename'], 'SHA256SUMS'}, {a['name'] for a in api.release['assets']})
        self.assertEqual('PATCH', api.calls[-1][0])

    def test_interrupted_draft_resumes_without_duplicate_tag_or_asset(self):
        plan, directory = self.distribution()
        api = FakeGitHub(self.sha)
        api.fail_checksum_once = True
        with self.assertRaises(RuntimeError):
            module.publish(api, directory, plan)
        self.assertTrue(api.release['draft'])
        self.assertEqual(self.sha, api.tag)
        self.assertEqual('published', module.publish(api, directory, plan)['status'])
        self.assertEqual(1, api.calls.count(('POST', '/repos/'+module.REPOSITORY+'/git/refs')))
        self.assertEqual(2, len(api.release['assets']))

    def test_published_release_is_not_overwritten_on_rerun(self):
        plan, directory = self.distribution()
        api = FakeGitHub(self.sha)
        module.publish(api, directory, plan)
        api.calls.clear()
        self.assertEqual('already-published', module.publish(api, directory, plan)['status'])
        self.assertTrue(all(method == 'GET' for method, _ in api.calls))

    def test_wrong_uploaded_digest_leaves_draft_and_can_be_repaired(self):
        plan, directory = self.distribution()
        api = FakeGitHub(self.sha)
        api.corrupt_digest = True
        with self.assertRaises(ValueError):
            module.publish(api, directory, plan)
        self.assertTrue(api.release['draft'])
        api.corrupt_digest = False
        self.assertEqual('published', module.publish(api, directory, plan)['status'])
        self.assertEqual(2, len(api.release['assets']))

    def test_superseded_commit_does_not_reserve_a_release_number(self):
        plan, directory = self.distribution()
        api = FakeGitHub('b'*40)
        self.assertEqual('superseded', module.publish(api, directory, plan)['status'])
        self.assertIsNone(api.tag)
        self.assertIsNone(api.release)

    def test_conflicting_tag_fails_without_mutation(self):
        plan, directory = self.distribution()
        api = FakeGitHub(self.sha)
        api.tag = 'b'*40
        with self.assertRaises(ValueError):
            module.publish(api, directory, plan)
        self.assertTrue(all(method == 'GET' for method, _ in api.calls))

    def test_development_distribution_cannot_be_published(self):
        plan, directory = self.distribution(False)
        api = FakeGitHub(self.sha)
        with self.assertRaises(ValueError):
            module.publish(api, directory, plan)
        self.assertEqual([], api.calls)

    def test_api_permission_error_is_not_treated_as_missing_release(self):
        api = module.GitHub('test-token')
        error = urllib.error.HTTPError('https://api.github.com/test', 403, 'Forbidden', {}, None)
        with patch.object(module.urllib.request, 'urlopen', side_effect=error):
            with self.assertRaisesRegex(RuntimeError, 'HTTP 403'):
                api.request('GET', '/test', missing=True)


if __name__ == '__main__':
    unittest.main()
