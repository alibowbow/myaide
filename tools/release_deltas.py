"""Build at most two verified ARM64 deltas; never publish or change credentials."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import select
import shutil
import subprocess
import tempfile
import time

import apk_delta

MAX_RELEASES = 20
MAX_CANDIDATES = 2
DOWNLOAD_TIMEOUT = 120
METADATA_LIMIT = 4 * 1024 * 1024


def version_tuple(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d+\.\d+\.\d+', value):
        raise ValueError('Invalid semantic release version')
    parts = tuple(map(int, value.split('.')))
    if any(part > 2147483647 for part in parts):
        raise ValueError('Release version outside supported range')
    return parts


def _gh_stream(arguments, output, limit, timeout):
    """Stream gh stdout with byte/time limits; never print stderr/credentials."""
    process = subprocess.Popen(['gh', *arguments], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + timeout
    length = 0
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('GitHub download timed out')
            ready, _, _ = select.select([process.stdout], [], [], min(remaining, 1))
            if not ready:
                continue
            chunk = os.read(process.stdout.fileno(), 65536)
            if not chunk:
                break
            length += len(chunk)
            if length > limit:
                raise ValueError('GitHub response exceeded size limit')
            output.write(chunk)
        if process.wait(timeout=max(0.01, deadline - time.monotonic())):
            raise ValueError('GitHub request failed')
        return length
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        process.stdout.close()


def list_releases(repo):
    output = io.BytesIO()
    _gh_stream(['api', f'repos/{repo}/releases?per_page={MAX_RELEASES}'], output, METADATA_LIMIT, 30)
    result = json.loads(output.getvalue())
    if not isinstance(result, list):
        raise ValueError('Invalid GitHub releases response')
    return result[:MAX_RELEASES]


def _expected_hash(asset, release):
    digest = asset.get('digest')
    if isinstance(digest, str) and re.fullmatch(r'sha256:[0-9a-fA-F]{64}', digest):
        return digest.split(':')[1].lower()
    body = release.get('body') or ''
    if not isinstance(body, str):
        return None
    matches = re.findall(r'SHA-256\s*[:=]?\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])', body)
    return matches[0].lower() if len(matches) == 1 else None


def candidates(releases, target_version):
    target = version_tuple(target_version)
    found = []
    seen = set()
    for release in releases[:MAX_RELEASES]:
        if not isinstance(release, dict) or release.get('draft') or not release.get('published_at'):
            continue
        tag = release.get('tag_name')
        match = re.fullmatch(r'v(\d+\.\d+\.\d+)-test', tag) if isinstance(tag, str) else None
        if not match:
            continue
        version = match[1]
        try:
            number = version_tuple(version)
        except ValueError:
            continue
        if number >= target or number in seen:
            continue
        assets = release.get('assets')
        if not isinstance(assets, list):
            continue
        expected_name = f'gyeot-{version}-debug.apk'
        choices = [a for a in assets if isinstance(a, dict) and a.get('name') == expected_name]
        if len(choices) != 1:
            continue
        asset = choices[0]
        size, identifier = asset.get('size'), asset.get('id')
        checksum = _expected_hash(asset, release)
        if (type(size) is not int or not 0 < size <= apk_delta.MAX_APK
                or type(identifier) is not int or identifier <= 0 or not checksum):
            continue
        seen.add(number)
        found.append((number, version, identifier, size, checksum))
    return sorted(found, reverse=True)[:MAX_CANDIDATES]


def download(repo, identifier, size, destination):
    with open(destination, 'wb') as output:
        count = _gh_stream(['api', '-H', 'Accept: application/octet-stream',
                            f'repos/{repo}/releases/assets/{identifier}'], output, size, DOWNLOAD_TIMEOUT)
    if count != size:
        raise ValueError('GitHub asset size mismatch')


def build(version, apk, output, repo='alibowbow/myaide'):
    version_tuple(version)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Invalid repository')
    apk, output = Path(apk), Path(output)
    target_size = apk_delta._size(apk)
    # Invalid local target is a build failure even when no base release exists.
    apk_delta._payloads(apk, time.monotonic() + apk_delta.TIMEOUT)
    target_hash = apk_delta.digest(apk).hex()
    output.mkdir(parents=True, exist_ok=True)
    try:
        choices = candidates(list_releases(repo), version)
    except (OSError, ValueError, TimeoutError, subprocess.SubprocessError):
        return []  # Entire APK remains available when GitHub is unavailable.
    reports = []
    with tempfile.TemporaryDirectory(prefix='gyeot-deltas-') as directory:
        directory = Path(directory)
        for _, base_version, identifier, size, checksum in choices:
            source = directory / 'base.apk'
            try:
                download(repo, identifier, size, source)
                if apk_delta.digest(source).hex() != checksum:
                    continue
                # Old/corrupt/non-APK assets are unavailable bases, not CI failures.
                apk_delta._payloads(source, time.monotonic() + apk_delta.TIMEOUT)
            except (OSError, ValueError, TimeoutError, subprocess.SubprocessError, apk_delta.zipfile.BadZipFile):
                continue
            name = f'gyeot-{version}-arm64-from-{checksum}.gdelta'
            patch = directory / name
            # Generator/local verification failures must stop CI, not publish bad data.
            report = apk_delta.create(source, apk, patch)
            restored = directory / 'verified.apk'
            apk_delta.apply(source, patch, restored, target_hash)
            if restored.stat().st_size != target_size or apk_delta.digest(restored).hex() != target_hash:
                raise ValueError('Delta reconstruction verification failed')
            if report['patchSize'] * 10 >= target_size * 9:
                continue
            # The temporary directory can be on another filesystem than the output (GitHub runners mount /tmp
            # separately), where a rename fails with EXDEV. Copy next to the target, then rename there atomically.
            staged = output / (name + '.partial')
            shutil.copyfile(patch, staged)
            staged.replace(output / name)
            patch.unlink()
            reports.append(dict(report, name=name, baseVersion=base_version, targetVersion=version, abi='arm64-v8a'))
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repo', default='alibowbow/myaide')
    args = parser.parse_args()
    print(json.dumps({'deltas': build(args.version, args.apk, args.output, args.repo)}, sort_keys=True))


if __name__ == '__main__':
    main()
