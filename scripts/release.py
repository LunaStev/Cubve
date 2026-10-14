#!/usr/bin/env python3
"""Plan, package and publish verified Cubve builds; numbering lives in Git tags."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPOSITORY = 'LunaStev/Cubve'
MAX_PATCH = 2_147_483_647


def git(root, *args):
    return subprocess.run(['git', *args], cwd=root, text=True, capture_output=True, check=True).stdout.strip()


def minecraft_version(root):
    values = re.findall(r'^mcVersion\s*=\s*(\S+)\s*$', (root/'gradle.properties').read_text(), re.MULTILINE)
    if len(values) != 1 or not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z._-]{0,63}', values[0]):
        raise ValueError('Expected one safe mcVersion in gradle.properties')
    return values[0]


def plan_release(root, release):
    mc = minecraft_version(root)
    commit = git(root, 'rev-parse', 'HEAD')
    tags = []
    for tag in git(root, 'tag', '--list', f'cubve-{mc}-*').splitlines():
        match = re.fullmatch(re.escape(f'cubve-{mc}-') + r'([1-9][0-9]*)', tag)
        if match:
            tags.append((int(match[1]), tag, git(root, 'rev-list', '-n', '1', tag)))
    same = [number for number, _, target in tags if target == commit]
    if len(same) > 1:
        raise ValueError('Multiple Cubve release tags for this commit and Minecraft version')
    patch = (same[0] if same else max((n for n, _, _ in tags), default=0) + 1) if release else None
    if patch is not None and patch > MAX_PATCH:
        raise ValueError('Patch number exceeds the server build-number range')
    tag = f'cubve-{mc}-{patch}' if patch is not None else None
    history = git(root, 'rev-list', '--first-parent', commit).splitlines()
    positions = {sha:index for index, sha in enumerate(history)}
    previous = []
    for candidate in git(root, 'tag', '--list', 'cubve-*').splitlines():
        if re.fullmatch(r'cubve-[0-9A-Za-z][0-9A-Za-z._-]*-[1-9][0-9]*', candidate) and candidate != tag:
            target = git(root, 'rev-list', '-n', '1', candidate)
            if target in positions and target != commit:
                previous.append((positions[target], candidate, target))
    if previous:
        _, previous_tag, base = min(previous)
    else:
        previous_tag = None
        properties = root/'build-data/release-base.properties'
        base = properties.read_text().strip().removeprefix('initialCommit=') if properties.exists() else commit
        if not re.fullmatch(r'[0-9a-f]{40}', base):
            raise ValueError('Invalid initial Cubve release base')
        subprocess.run(['git', 'merge-base', '--is-ancestor', base, commit], cwd=root, check=True)
    return {'minecraft':mc, 'patch':patch, 'tag':tag, 'commit':commit, 'publish':release,
            'previousTag':previous_tag, 'notesBase':base,
            'filename':f'{tag}.jar' if tag else f'cubve-{mc}-dev.jar'}


def validate_plan(plan):
    mc, patch = plan['minecraft'], plan['patch']
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z._-]{0,63}', mc):
        raise ValueError('Invalid Minecraft version')
    if not re.fullmatch(r'[0-9a-f]{40}', plan['commit']):
        raise ValueError('Invalid source commit')
    if type(plan['publish']) is not bool or plan['publish'] != (patch is not None):
        raise ValueError('Inconsistent publication metadata')
    if patch is not None and (type(patch) is not int or not 1 <= patch <= MAX_PATCH):
        raise ValueError('Invalid patch number')
    tag = f'cubve-{mc}-{patch}' if patch is not None else None
    if plan['tag'] != tag or plan['filename'] != (f'{tag}.jar' if tag else f'cubve-{mc}-dev.jar'):
        raise ValueError('Artifact name does not match release identity')


def manifest(jar):
    with zipfile.ZipFile(jar) as archive:
        raw = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n', '\n')
    unfolded = raw.replace('\n ', '')
    return dict(line.split(': ', 1) for line in unfolded.split('\n\n', 1)[0].splitlines() if ': ' in line)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def markdown(text):
    return re.sub(r'([\\`*_\[\]<>])', r'\\\1', text)


def release_notes(root, plan):
    lines = [f'Minecraft {plan["minecraft"]} · Patch {plan["patch"]} · Java 25', '']
    highlights = root/'release-notes'/f'{plan["tag"]}.md'
    if highlights.exists():
        lines += [highlights.read_text().strip(), '']
    lines += ["## What's Changed", '']
    commits = git(root, 'log', '--first-parent', '--reverse', '--format=%H%x00%an%x00%s',
                  f'{plan["notesBase"]}..{plan["commit"]}')
    for record in commits.splitlines():
        sha, author, subject = record.split('\0', 2)
        lines.append(f'- {markdown(subject)} by {markdown(author)} in '
                     f'[{sha[:7]}](https://github.com/{REPOSITORY}/commit/{sha})')
    if not commits:
        lines.append('- No additional commits in this comparison range.')
    base = plan['previousTag'] or plan['notesBase']
    lines += ['', f'**Full Changelog**: https://github.com/{REPOSITORY}/compare/{base}...{plan["tag"]}', '',
              '## Download', '', f'Download `{plan["filename"]}` and run:', '',
              f'```sh\njava -jar {plan["filename"]} --nogui\n```', '',
              'SHA256SUMS is included to verify the download.', '',
              f'Source: `{plan["commit"]}`. Published after CI checks and the runnable-JAR smoke test.']
    return '\n'.join(lines)+'\n'


def package(root, plan, directory):
    validate_plan(plan)
    if minecraft_version(root) != plan['minecraft'] or git(root, 'rev-parse', 'HEAD') != plan['commit']:
        raise ValueError('Source checkout does not match build plan')
    mc, patch = plan['minecraft'], plan['patch']
    version = f'{mc}.build.{patch}-stable' if patch is not None else f'{mc}.local-SNAPSHOT'
    libs = root/'paper-server/build/libs'
    server = libs/f'cubve-server-{version}.jar'
    runnable = libs/(plan['filename'] if patch is not None else f'cubve-paperclip-{version}.jar')
    attributes = manifest(server)
    expected_number = str(patch) if patch is not None else ''
    short_commit = attributes.get('Git-Commit', '')
    if (attributes.get('Brand-Id') != 'cubve:cubve' or attributes.get('Build-Number') != expected_number
        or len(short_commit) < 7 or not plan['commit'].startswith(short_commit)
        or not attributes.get('Implementation-Version', '').startswith(f'{mc}-{patch if patch is not None else "DEV"}-')):
        raise ValueError('Server manifest does not match the Cubve release plan')
    if manifest(runnable).get('Main-Class') != 'io.papermc.paperclip.Main':
        raise ValueError('Expected the self-contained runnable Paperclip JAR')
    directory.mkdir(parents=True, exist_ok=False)
    target = directory/plan['filename']
    shutil.copyfile(runnable, target)
    (directory/'SHA256SUMS').write_text(f'{sha256(target)}  {target.name}\n')
    (directory/'release-plan.json').write_text(json.dumps(plan, indent=2)+'\n')
    if plan['publish']:
        (directory/'release-notes.md').write_text(release_notes(root, plan))


def verify_distribution(directory, plan):
    validate_plan(plan)
    if (directory/'SHA256SUMS').read_text() != f'{sha256(directory/plan["filename"])}  {plan["filename"]}\n':
        raise ValueError('Distribution checksum mismatch')
    if manifest(directory/plan['filename']).get('Main-Class') != 'io.papermc.paperclip.Main':
        raise ValueError('Distribution is not a runnable Cubve JAR')


def smoke(directory, java='java'):
    plan = json.loads((directory/'release-plan.json').read_text())
    verify_distribution(directory, plan)
    with tempfile.TemporaryDirectory(prefix='cubve-jar-smoke-') as work:
        result = subprocess.run([java, '-jar', str((directory/plan['filename']).resolve()), '--version'],
                                cwd=work, text=True, capture_output=True, timeout=300, check=True)
        expected = f'{plan["minecraft"]}-{plan["patch"] if plan["patch"] is not None else "DEV"}-'
        if expected not in result.stdout + result.stderr:
            raise ValueError('Runnable JAR reports the wrong release version')
        print(result.stdout + result.stderr)


class GitHub:
    def __init__(self, token):
        self.token = token

    def request(self, method, path, body=None, *, missing=False, upload=None):
        url = 'https://api.github.com' + path if upload is None else upload
        if urllib.parse.urlsplit(url).hostname not in {'api.github.com', 'uploads.github.com'}:
            raise ValueError('Unexpected GitHub API host')
        data = body if upload else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(url, data=data, method=method, headers={
            'Authorization':'Bearer '+self.token, 'Accept':'application/vnd.github+json',
            'X-GitHub-Api-Version':'2026-03-10', 'User-Agent':'Cubve-release',
            'Content-Type':'application/octet-stream' if upload else 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise RuntimeError(f'GitHub {method} {path}: HTTP {error.code}') from None


def tag_commit(api, base, tag):
    ref = api.request('GET', base+'/git/ref/tags/'+urllib.parse.quote(tag, safe=''), missing=True)
    if ref is None:
        return None
    obj = ref['object']
    for _ in range(10):
        if obj['type'] == 'commit':
            return obj['sha']
        if obj['type'] != 'tag':
            break
        obj = api.request('GET', base+'/git/tags/'+obj['sha'])['object']
    raise ValueError('Release tag does not resolve to a commit')


def publish(api, directory, plan):
    verify_distribution(directory, plan)
    if not plan['publish']:
        raise ValueError('Development artifacts cannot be published')
    base = '/repos/'+REPOSITORY
    target = tag_commit(api, base, plan['tag'])
    if target is not None and target != plan['commit']:
        raise ValueError('Release tag already points at a different commit')
    release = None
    page = 1
    while True:
        batch = api.request('GET', f'{base}/releases?per_page=100&page={page}')
        release = next((r for r in batch if r['tag_name'] == plan['tag']), None)
        if release is not None or len(batch) < 100:
            break
        page += 1
    files = [directory/plan['filename'], directory/'SHA256SUMS']
    if release is not None and not release['draft']:
        names = {a['name'] for a in release['assets'] if a['state'] == 'uploaded'}
        if target != plan['commit'] or not {p.name for p in files} <= names:
            raise ValueError('Published release is incomplete; published assets are never overwritten')
        return {'url':release['html_url'], 'status':'already-published'}
    head = api.request('GET', base+'/git/ref/heads/master')['object']['sha']
    if head != plan['commit']:
        return {'url':'', 'status':'superseded'}
    if target is None:
        api.request('POST', base+'/git/refs', {'ref':'refs/tags/'+plan['tag'], 'sha':plan['commit']})
    if release is None:
        release = api.request('POST', base+'/releases', {
            'tag_name':plan['tag'], 'target_commitish':plan['commit'], 'draft':True, 'prerelease':False,
            'name':f'Cubve {plan["minecraft"]}-{plan["patch"]}',
            'body':(directory/'release-notes.md').read_text()})
    release_path = base+'/releases/'+str(release['id'])
    for path in files:
        current = api.request('GET', release_path)
        if not current['draft']:
            raise ValueError('Release was published during upload; refusing to replace assets')
        digest = 'sha256:'+sha256(path)
        asset = next((a for a in current['assets'] if a['name'] == path.name), None)
        if asset is not None and asset['state'] == 'uploaded' and asset.get('digest') == digest:
            continue
        if asset is not None:
            api.request('DELETE', base+'/releases/assets/'+str(asset['id']))
        upload = current['upload_url'].split('{', 1)[0] + '?' + urllib.parse.urlencode({'name':path.name})
        api.request('POST', release_path+'/assets', path.read_bytes(), upload=upload)
    current = api.request('GET', release_path)
    uploaded = {a['name']:a for a in current['assets']}
    for path in files:
        asset = uploaded.get(path.name, {})
        if asset.get('state') != 'uploaded' or asset.get('digest') != 'sha256:'+sha256(path):
            raise ValueError('Uploaded asset digest mismatch; release remains a draft')
    if not current['draft']:
        raise ValueError('Release state changed before publication')
    published = api.request('PATCH', release_path, {'draft':False, 'prerelease':False, 'make_latest':'true'})
    return {'url':published['html_url'], 'status':'published'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    commands = parser.add_subparsers(dest='command', required=True)
    plan = commands.add_parser('plan')
    plan.add_argument('--release', action='store_true')
    plan.add_argument('--output', type=Path, required=True)
    plan.add_argument('--github-output', type=Path)
    package_parser = commands.add_parser('package')
    package_parser.add_argument('--plan', type=Path, required=True)
    package_parser.add_argument('--directory', type=Path, required=True)
    publish_parser = commands.add_parser('publish')
    publish_parser.add_argument('--directory', type=Path, required=True)
    smoke_parser = commands.add_parser('smoke')
    smoke_parser.add_argument('--directory', type=Path, required=True)
    smoke_parser.add_argument('--java', default='java')
    args = parser.parse_args()
    if args.command == 'plan':
        result = plan_release(args.root, args.release)
        args.output.write_text(json.dumps(result, indent=2)+'\n')
        if args.github_output:
            with args.github_output.open('a') as output:
                output.write(f'publish={str(result["publish"]).lower()}\npatch={result["patch"] or ""}\n'
                             f'filename={result["filename"]}\n')
        print(json.dumps(result))
    elif args.command == 'package':
        package(args.root, json.loads(args.plan.read_text()), args.directory)
    elif args.command == 'smoke':
        smoke(args.directory, args.java)
    else:
        if (os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_EVENT_NAME') != 'push'
            or os.environ.get('GITHUB_REF') != 'refs/heads/master'
            or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY):
            raise SystemExit('Publication is restricted to verified master pushes in '+REPOSITORY)
        result = json.loads((args.directory/'release-plan.json').read_text())
        if result['commit'] != os.environ.get('GITHUB_SHA') or git(args.root, 'rev-parse', 'HEAD') != result['commit']:
            raise SystemExit('Publication source does not match this CI run')
        print(json.dumps(publish(GitHub(os.environ['GH_TOKEN']), args.directory, result)))


if __name__ == '__main__':
    main()
