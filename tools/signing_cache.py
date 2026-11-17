#!/usr/bin/env python3
"""Fail-closed CI cache boundaries; never reads, copies, prints, or deletes a signing key."""
import argparse
import json
import os
from pathlib import Path
import re
import runpy
import stat
import subprocess
import sys

# Never restore extracted SDK/Gradle executables. The builder validates this ZIP
# against its pinned SHA-256 before extracting it into the fresh private workspace.
SDK_PATHS = ('gradle-9.8.2-bin.zip',)
JARS = ('Java-WebSocket-1.6.0.jar', 'slf4j-api-2.0.17.jar',
        'slf4j-nop-2.0.17.jar', 'json-20250107.jar')
KEY_NAME = 'gyeot-local-test.keystore'
BLOCKED_SUFFIXES = ('.keystore', '.jks', '.p12', '.pfx', '.pem', '.key')
BLOCKED_NAMES = ('.env', 'credentials', 'id_rsa', 'id_ed25519')


def fail(message):
    # Do not echo paths supplied by a cache or any file contents into workflow logs.
    raise ValueError(message)


def no_links(path):
    path = Path(path).absolute()
    for part in (*reversed(path.parents), path):
        if part.is_symlink():
            fail('Cache/signing paths must not contain symbolic links.')
    return path


def roots(workspace, temporary, private):
    if not all(Path(path).is_absolute() for path in (workspace, temporary, private)):
        fail('Workspace, runner temporary and signing paths must be absolute.')
    workspace, temporary, private = map(no_links, (workspace, temporary, private))
    if not workspace.is_dir() or not temporary.is_dir():
        fail('Existing workspace and runner temporary directories are required.')
    if private.parent != temporary or private.name != 'myaide-signing-build':
        fail('The isolated build directory must be a direct runner-temp child.')
    if private.is_relative_to(workspace) or workspace.is_relative_to(private):
        fail('The signing workspace must be outside the repository.')
    return workspace, private


def cache_paths(workspace, private):
    return [private / item for item in SDK_PATHS] + [workspace / '.deps' / item for item in JARS]


def inspect_public(path):
    """Metadata only: reject links, special files and credential-like filenames."""
    path = no_links(path)
    if not path.exists():
        return
    pending = [path]
    while pending:
        entry = pending.pop()
        info = entry.lstat()
        name = entry.name.lower()
        if name.endswith(BLOCKED_SUFFIXES) or name in BLOCKED_NAMES:
            fail('A credential-like file was found in a public dependency cache; nothing was deleted.')
        if stat.S_ISLNK(info.st_mode):
            fail('Symbolic links are forbidden in public dependency caches.')
        if stat.S_ISDIR(info.st_mode):
            pending.extend(entry.iterdir())
        elif not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            fail('Only ordinary, unlinked dependency files may enter the public cache.')


def prepare(workspace, temporary, private):
    workspace, private = roots(workspace, temporary, private)
    if private.exists():
        fail('A fresh signing workspace is required; existing files were left untouched.')
    inspect_public(workspace / '.cache')
    inspect_public(workspace / '.deps')
    private.mkdir(mode=0o700)
    return cache_paths(workspace, private)


def scan(workspace, temporary, private, require_key=False):
    workspace, private = roots(workspace, temporary, private)
    if not private.is_dir() or private.stat().st_mode & 0o077:
        fail('The signing workspace must be a private directory (0700).')
    # A legacy broad cache must never be silently reused or automatically removed.
    inspect_public(workspace / '.cache')
    inspect_public(workspace / '.deps')
    for path in cache_paths(workspace, private):
        inspect_public(path)
        if path.exists() and not path.is_file():
            fail('Each allowlisted dependency must be an ordinary archive file.')
    key = no_links(private / KEY_NAME)
    if not require_key:
        if key.exists():
            fail('A signing key appeared before the explicit restore step.')
        if any(entry.name not in SDK_PATHS for entry in private.iterdir()):
            fail('Unexpected restored build files; extracted executables must not come from cache.')
        return
    if not key.exists():
        fail('The existing signing key is required; generating a replacement is forbidden.')
    info = key.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size == 0 or info.st_mode & 0o077:
        fail('The restored signing key must be a nonempty private ordinary file (0600).')


def run_build(workspace):
    """Do not let legacy subprocess exceptions echo signing command arguments."""
    saved = sys.argv
    try:
        sys.argv = [str(Path(workspace) / 'tools/build.py')]
        runpy.run_path(sys.argv[0], run_name='__main__')
    except subprocess.CalledProcessError as error:
        print('Build command failed; command arguments omitted to protect signing inputs.', file=sys.stderr)
        raise SystemExit(error.returncode if 0 < error.returncode < 256 else 1) from None
    except Exception:
        print('Build failed; exception details omitted to protect signing inputs.', file=sys.stderr)
        raise SystemExit(1) from None
    finally:
        sys.argv = saved


def release_may_be_created(repository, version):
    """Read metadata only. Existing releases/tags are immutable; any lookup error stops publication."""
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9][A-Za-z0-9_.-]*', repository):
        fail('Invalid repository metadata; publication blocked.')
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){2}(?:[-+][A-Za-z0-9.-]+)?', version):
        fail('Invalid release version; publication blocked.')
    def query(arguments):
        try:
            result = subprocess.run(['gh', 'api', *arguments], capture_output=True, text=True, timeout=60)
        except (OSError, subprocess.TimeoutExpired):
            fail('Release metadata lookup failed; no existing release will be changed.')
        if result.returncode:
            fail('Release metadata lookup failed; no existing release will be changed.')
        try:
            return json.loads(result.stdout)
        except (TypeError, ValueError):
            fail('Invalid release metadata response; publication blocked.')
    tag = 'v' + version + '-test'
    pages = query(['--paginate', '--slurp', f'repos/{repository}/releases?per_page=100'])
    if not isinstance(pages, list) or not pages or any(not isinstance(page, list) for page in pages):
        fail('Invalid release list; publication blocked.')
    existing = False
    for page in pages:
        for release in page:
            if not isinstance(release, dict) or not isinstance(release.get('tag_name'), str):
                fail('Invalid release entry; publication blocked.')
            existing |= release['tag_name'] == tag
    if existing:
        return False
    refs = query([f'repos/{repository}/git/matching-refs/tags/{tag}'])
    if not isinstance(refs, list) or any(not isinstance(ref, dict) or not isinstance(ref.get('ref'), str) for ref in refs):
        fail('Invalid tag metadata; publication blocked.')
    return not any(ref['ref'] == 'refs/tags/' + tag for ref in refs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'restored', 'signing-ready', 'run-build', 'release-gate'))
    args = parser.parse_args()
    if args.command == 'release-gate':
        publish = release_may_be_created(os.environ['GITHUB_REPOSITORY'], os.environ['VERSION'])
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('publish=' + str(publish).lower() + '\n')
        print('New release permitted.' if publish else 'Existing release/tag preserved; publication skipped.')
        return
    workspace = Path(os.environ['GITHUB_WORKSPACE'])
    temporary = Path(os.environ['RUNNER_TEMP'])
    private = Path(os.environ['GYEOT_BUILD_CACHE'])
    if args.command == 'prepare':
        paths = prepare(workspace, temporary, private)
        # These are controlled metadata paths, not secret contents.
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('paths<<GYEOT_PUBLIC_DEPENDENCIES\n')
            output.write('\n'.join(map(str, paths)))
            output.write('\nGYEOT_PUBLIC_DEPENDENCIES\n')
    else:
        scan(workspace, temporary, private, require_key=args.command in ('signing-ready', 'run-build'))
        if args.command == 'run-build':
            run_build(workspace)


if __name__ == '__main__':
    main()
