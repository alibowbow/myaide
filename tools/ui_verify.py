#!/usr/bin/env python3
"""Render and exercise actual Android views with Robolectric, without loading Gecko or account APIs.

Run tools/build.py once to prepare the pinned SDK and Gradle. Test dependencies are
added only to an isolated temporary copy; the shipped APK does not include them.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/ui-verification')
    parser.add_argument('--tests', action='append', help='Optional Gradle test pattern; repeat to target multiple suites.')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    cache = Path(os.environ.get('GYEOT_BUILD_CACHE', ROOT / '.cache')).resolve()
    gradle = cache / 'gradle-9.8.2/bin/gradle'
    if not gradle.is_file():
        raise SystemExit('Run python3 tools/build.py first to prepare the pinned SDK and Gradle.')
    with tempfile.TemporaryDirectory(prefix='gyeot-ui-') as temporary:
        target = Path(temporary)
        shutil.copytree(ROOT, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns(
            '.git', '.cache', '.deps', '.gradle', '.jdk', '.java-home', '.android-home',
            'build', 'node_modules', '__pycache__'))
        (target / '.cache').symlink_to(cache, target_is_directory=True)
        tests = target / 'app/src/test/java/dev/jun/gyeot'
        tests.mkdir(parents=True, exist_ok=True)
        # Routing behavior uses the same native fixtures and must participate in
        # the aggregate suite, not remain outside the tests/ui-only glob.
        for suite in ('ui', 'routing'):
            for source in (ROOT / 'tests' / suite).glob('*.java'):
                destination = tests / source.name
                if destination.exists():
                    raise SystemExit('Duplicate native test source: ' + source.name)
                shutil.copy2(source, destination)
        with (target / 'app/build.gradle').open('a') as script:
            script.write('''
android { testOptions { unitTests.includeAndroidResources = true; unitTests.all {
    // Robolectric downloads its Android runtime jar itself, at test time. ui_verify picks the repository per attempt.
    systemProperty 'robolectric.dependency.repo.url', System.getProperty('gyeot.robolectric.repo', 'https://repo.maven.apache.org/maven2')
    systemProperty 'robolectric.dependency.repo.id', System.getProperty('gyeot.robolectric.repo.id', 'central')
    ['http.proxyHost','http.proxyPort','https.proxyHost','https.proxyPort','ui.output'].each {
        key -> if (System.getProperty(key)) systemProperty key, System.getProperty(key)
    }
    // Host-only syscall fixture inspects its own FileChannel descriptor for real fd-anchored tests.
    // This is not packaged in the APK and does not grant Android storage access.
    jvmArgs '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '--add-opens=java.base/java.io=ALL-UNNAMED'
    maxHeapSize = '3g'
} } }
dependencies {
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.robolectric:robolectric:4.16.1'
}
''')
        env = dict(os.environ, ANDROID_HOME=str(cache), ANDROID_SDK_ROOT=str(cache))
        proxy_args = []
        for protocol in ('https', 'http'):
            proxy = urllib.parse.urlsplit(env.get(protocol.upper() + '_PROXY', ''))
            if proxy.hostname:
                proxy_args += [f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 80}']
        test_args = [argument for pattern in (args.tests or ['dev.jun.gyeot.*Tests']) for argument in ('--tests', pattern)]
        # Maven Central answers bursts of large jar downloads with 429 Too Many Requests: Gradle's dependency
        # downloads and Robolectric's own runtime-jar download at test time. Gradle retries a download with backoff
        # (longer than its 3 tries from 125 ms). Robolectric fetches from Google's mirror of Maven Central first and
        # from Maven Central on the next attempt. A run that ends on a failed download waits a minute and runs
        # again, up to twice; test failures are never retried.
        retry_args = ['-Dorg.gradle.internal.repository.max.retries=8', '-Dorg.gradle.internal.repository.initial.backoff=2000']
        for attempt in range(3):
            repo, repo_id = ROBOLECTRIC_REPOS[attempt % len(ROBOLECTRIC_REPOS)]
            command = [str(gradle), *proxy_args, *retry_args, f'-Dgyeot.robolectric.repo={repo}',
                f'-Dgyeot.robolectric.repo.id={repo_id}', f'-Dui.output={output}', '--no-daemon', '--console=plain',
                ':app:testDebugUnitTest', *test_args]
            result, download_failed = run_streamed(command, target, env)
            if result == 0 or not download_failed or attempt == 2:
                break
            print('A dependency download failed (rate limit or network); retrying in 60 s.', flush=True)
            time.sleep(60)
        for name, path in [('results', 'test-results/testDebugUnitTest'), ('report', 'reports/tests/testDebugUnitTest')]:
            source = target / 'app/build' / path
            if source.exists():
                shutil.copytree(source, output / name, dirs_exist_ok=True)
        raise SystemExit(result)


ROBOLECTRIC_REPOS = [('https://maven-central.storage-download.googleapis.com/maven2', 'google-maven-central'),
                     ('https://repo.maven.apache.org/maven2', 'central')]
# Gradle's failed download, or Robolectric's (every test class then fails at its runtime-jar fetch).
DOWNLOAD_FAILURE = ('status code 429', 'Too Many Requests', 'Could not GET', 'Could not download', 'MavenArtifactFetcher')


def run_streamed(command, cwd, env):
    """Run Gradle with its output passed through as it comes, and tell whether a dependency download failed."""
    download_failed = False
    with subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, errors='replace', bufsize=1) as process:
        for line in process.stdout:
            sys.stdout.write(line)
            if any(marker in line for marker in DOWNLOAD_FAILURE):
                download_failed = True
        sys.stdout.flush()
        return process.wait(), download_failed


if __name__ == '__main__':
    main()
