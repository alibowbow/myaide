#!/usr/bin/env python3
"""Build a signed personal-test APK with JDK 17; downloads Google's official SDK tools.

No API key is required or embedded. The pinned Gradle builder packages GeckoView and its dependencies.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('GYEOT_BUILD_CACHE', ROOT / '.cache')).resolve()
DEPS = ROOT / '.deps'
BUILD = ROOT / 'build'
# Maven Central answers on both hosts; the second is tried when the first refuses or rate-limits.
MAVEN_MIRRORS = ('https://repo1.maven.org/maven2', 'https://repo.maven.apache.org/maven2')


def gradle_config():
    """app/build.gradle is the single source for version and SDK levels."""
    text = (ROOT / 'app/build.gradle').read_text()
    def field(name, pattern):
        match = re.search(name + r"\s+" + pattern, text)
        if not match:
            raise ValueError('app/build.gradle is missing ' + name)
        return match.group(1)
    return {
        'versionName': field('versionName', r"'([^']+)'"),
        'versionCode': field('versionCode', r'(\d+)'),
        'minSdk': field('minSdk', r'(\d+)'),
        'targetSdk': field('targetSdk', r'(\d+)'),
    }


def fetch(url, output):
    with urllib.request.urlopen(url, timeout=60) as response:
        shutil.copyfileobj(response, output)


def retry_wait(error, round_):
    """Seconds to wait before the next round of downloads: the server's Retry-After when it gives one, else a growing wait."""
    asked = getattr(error, 'headers', None)
    asked = asked.get('Retry-After') if asked is not None else None
    if asked and str(asked).strip().isdigit():
        return max(1, min(120, int(str(asked).strip())))
    return min(60, 5 * 3 ** round_)


def download(url, destination, checksum=None, algorithm='sha256', mirrors=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and (not checksum or hashlib.new(algorithm, destination.read_bytes()).hexdigest() == checksum):
        return destination
    partial = destination.with_suffix(destination.suffix + '.partial')
    print('Downloading', url.rsplit('/', 1)[-1], flush=True)
    candidates = [url] if not mirrors else [base + url for base in mirrors]
    # Rate limits (429) and network hiccups are transient: cycle through the mirrors with a growing wait (5s, 15s, 45s,
    # then a minute), or as long as the server asks for, up to two minutes.
    attempts = [(round_, candidate) for round_ in range(5) for candidate in candidates]
    for index, (round_, candidate) in enumerate(attempts):
        try:
            with partial.open('wb') as output:
                fetch(candidate, output)
            break
        except OSError as error:
            if index == len(attempts) - 1:
                raise
            print('  retrying after:', error, flush=True)
            if candidate == candidates[-1]:
                time.sleep(retry_wait(error, round_))
    if checksum and hashlib.new(algorithm, partial.read_bytes()).hexdigest() != checksum:
        partial.unlink()
        raise ValueError('Dependency checksum mismatch: ' + destination.name)
    partial.replace(destination)
    return destination


def sdk():
    android = CACHE / 'platforms/android-35/android.jar'
    tools = CACHE / 'build-tools/35.0.0'
    if android.exists() and (tools / 'aapt2').exists() and (tools / 'lib/d8.jar').exists():
        return android, tools
    manifest = ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml', timeout=30).read())
    for package in manifest:
        name = package.attrib.get('path')
        if name not in ('platforms;android-35', 'build-tools;35.0.0'):
            continue
        target = CACHE / name.replace(';', '/')
        expected = target / ('android.jar' if name.startswith('platforms') else 'aapt2')
        if expected.exists():
            continue
        for archive in package.iter():
            if archive.tag.rsplit('}', 1)[-1] != 'archive':
                continue
            fields = {x.tag.rsplit('}', 1)[-1]: (x.text or '').strip() for x in archive.iter()}
            if fields.get('host-os', 'linux') != 'linux' or not fields.get('url'):
                continue
            zipped = download('https://dl.google.com/android/repository/' + fields['url'], CACHE / fields['url'], fields.get('checksum'), 'sha1')
            staging = CACHE / ('unpack-' + name.replace(';', '-'))
            staging.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(zipped) as source:
                for item in source.infolist():
                    resolved = (staging / item.filename).resolve()
                    if not resolved.is_relative_to(staging):
                        raise ValueError('Unexpected SDK archive path')
                    source.extract(item, staging)
                    if item.external_attr >> 16:
                        resolved.chmod((item.external_attr >> 16) & 0o777)
            candidates = list(staging.rglob(expected.name))
            if not candidates:
                raise ValueError('SDK archive is missing ' + expected.name)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(candidates[0].parent), str(target))
            shutil.rmtree(staging, ignore_errors=True)
            break
    return CACHE / 'platforms/android-35/android.jar', CACHE / 'build-tools/35.0.0'


def dependencies():
    entries = [
        ('org/java-websocket/Java-WebSocket/1.6.0', 'Java-WebSocket-1.6.0.jar'),
        ('org/slf4j/slf4j-api/2.0.17', 'slf4j-api-2.0.17.jar'),
        ('org/slf4j/slf4j-nop/2.0.17', 'slf4j-nop-2.0.17.jar'),
        ('org/json/json/20250107', 'json-20250107.jar'),
    ]
    lock = json.loads((ROOT / 'tools/dependency-lock.json').read_text()) if (ROOT / 'tools/dependency-lock.json').exists() else {}
    jars = []
    for path, filename in entries:
        relative = f'/{path}/{filename}'
        # Maven's published digest accompanies each exact-version artifact.
        if filename in lock:
            jars.append(download(relative, DEPS / filename, lock[filename], 'sha256', MAVEN_MIRRORS))
        else:
            checksum = urllib.request.urlopen(MAVEN_MIRRORS[0] + relative + '.sha1', timeout=30).read().decode().strip().split()[0]
            jars.append(download(relative, DEPS / filename, checksum, 'sha1', MAVEN_MIRRORS))
    return jars


def run(command):
    subprocess.run([str(x) for x in command], cwd=ROOT, check=True)


def javac(args):
    executable = shutil.which('javac')
    if executable:
        run([executable, *args])
    else:
        run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', *args])


def test(jars):
    output = BUILD / 'tests'; output.mkdir(parents=True, exist_ok=True)
    sources = list((ROOT / 'app/src/main/java/dev/jun/gyeot/core').glob('*.java')) + [ROOT / 'app/src/main/java/dev/jun/gyeot/BrowserDecisionEvidence.java', ROOT / 'app/src/main/java/dev/jun/gyeot/GeminiSession.java', ROOT / 'app/src/main/java/dev/jun/gyeot/TranslationSession.java', ROOT / 'app/src/main/java/dev/jun/gyeot/ApkDelta.java', ROOT / 'app/src/main/java/dev/jun/gyeot/GeminiAudioClient.java'] + list((ROOT / 'tests').glob('*.java'))
    json_jar = jars[-1]
    classpath = os.pathsep.join(map(str, jars))
    javac(['-encoding', 'UTF-8', '-d', output, '-cp', classpath, *sources])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.CoreTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MainChatContextTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MainRequestTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MainAttachmentsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.NaverPlacesTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WakeWordTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.UsageTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MainSearchTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MemoryBackupTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MemoryEverywhereTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.RichBlocksTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ProfilePictureTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ChatImagesTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MainStreamingTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MediaPathsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MediaToolsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.VideoEditTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BotAvatarTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AvatarCharacterTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AvatarSpecTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AvatarMoodTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ProfileMotionTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.GoogleDriveTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ConnectionIdentityTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WorkSchedulesTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WorkBudgetTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserCompletionEvidenceTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.BrowserDecisionEvidenceTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AutomationGateTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.DecisionPhoneTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AndroidRegexTests', str(ROOT / 'app/src/main/java/dev/jun/gyeot/core')])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WorkAuthorityTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneDecisionRiskTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'VoiceBrowserRequestTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneDecisionEvidenceTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneCompletionTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneDispatchGuardTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserReadNavigationTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.FileOrganizationPlanTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserJobsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserArtifactPolicyTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserWorkJobsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.GeminiAudioCancellationTests'])
    run(['sh', str(ROOT / 'tests/browser-work/run.sh')])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserPersistenceTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserConversationTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserDraftsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserTaskContextTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.BrowserPromptsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.GenerationPoolTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.SessionTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.TranslationTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.ApkDeltaTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.DeltaUpdateTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ScreenConversationTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.VoiceNetworkTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.SpeechTextTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.GreetingTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ConnectedVoiceTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.VoiceWorkTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.DiagnosticsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.VoiceLatencyTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.VoiceToolLogTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.OpenAiTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.CodexImagesTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AppNavigationTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.DeviceActionsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.CalendarQueryTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PagesTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneRiskTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WakeRestTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.VoiceRouteTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AppImagesTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AppGalleryTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.GrokVideoTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.GrokWorkTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.McpOAuthTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.NimTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ModelIdsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AppVideosTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WorkPlansTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneProtocolTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneSpeedTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.AdaptiveUiTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.PhoneSocketTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ReasoningTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.GptSpeedTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.ClaudeTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WorkTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.GitHubReposTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.SettingsIndexTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WorkInstructionsTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WorkCheckpointTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.MergeTaskTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.WorkQueueTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'dev.jun.gyeot.core.TeamTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'WorkRunStateTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'WorkRoomPoolTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'WorkRoomStateTests'])
    run(['java', '-ea', '-cp', str(output) + os.pathsep + classpath, 'RequestAbortTests'])
    # The real work client against host-only account/network boundaries, kept out of the Android source set.
    flow_output = BUILD / 'work-client-tests'; flow_output.mkdir(parents=True, exist_ok=True)
    flow_sources = [ROOT / 'app/src/main/java/dev/jun/gyeot/WorkTools.java', ROOT / 'app/src/main/java/dev/jun/gyeot/WorkLedger.java', ROOT / 'app/src/main/java/dev/jun/gyeot/WorkClient.java', ROOT / 'app/src/main/java/dev/jun/gyeot/GrokWorkClient.java', ROOT / 'app/src/main/java/dev/jun/gyeot/NimClient.java', ROOT / 'app/src/main/java/dev/jun/gyeot/NimAccount.java', ROOT / 'app/src/main/java/dev/jun/gyeot/GitHubRunner.java', ROOT / 'app/src/main/java/dev/jun/gyeot/TeamRepository.java', *sorted((ROOT / 'tests/work-client').glob('*.java'))]
    flow_sources.append(ROOT / 'app/src/main/java/dev/jun/gyeot/TeamChanges.java')
    flow_sources.append(ROOT / 'app/src/main/java/dev/jun/gyeot/TeamTools.java')
    flow_classpath = str(output) + os.pathsep + classpath
    javac(['-encoding', 'UTF-8', '-d', flow_output, '-cp', flow_classpath, *flow_sources])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.WorkClientFlowTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.WorkSteeringFlowTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.GitHubRunnerTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.WorkLedgerJournalTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.GrokWorkClientTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.NimClientTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.WorkMemoryPrefixTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.NimAccountTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.TeamClientTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.TeamChangesTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.TeamRunnerFlowTests'])
    run(['java', '-ea', '-cp', str(flow_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.TeamDiagnosticsTests'])
    # Other providers use the same merge continuation contract, against isolated offline boundaries.
    provider_output = BUILD / 'provider-merge-tests'; provider_output.mkdir(parents=True, exist_ok=True)
    provider_sources = [ROOT / 'app/src/main/java/dev/jun/gyeot/WorkTools.java', ROOT / 'app/src/main/java/dev/jun/gyeot/ClaudeClient.java', ROOT / 'app/src/main/java/dev/jun/gyeot/GeminiWorkClient.java', ROOT / 'app/src/main/java/dev/jun/gyeot/NimClient.java', *sorted((ROOT / 'tests/provider-merge').glob('*.java'))]
    javac(['-encoding', 'UTF-8', '-d', provider_output, '-cp', flow_classpath, *provider_sources])
    run(['java', '-ea', '-cp', str(provider_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.ProviderMergeTests'])
    run(['java', '-ea', '-cp', str(provider_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.WorkMemoryPrefixProviderTests'])
    run(['java', '-ea', '-cp', str(provider_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.ClaudeInterruptTests'])
    run(['java', '-ea', '-cp', str(provider_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.ClaudeOutputsTests'])
    # The real browser planner loop, with host-only page/model boundaries in its own classpath.
    browser_output = BUILD / 'browser-agent-tests'; browser_output.mkdir(parents=True, exist_ok=True)
    browser_sources = [ROOT / 'app/src/main/java/dev/jun/gyeot/BrowserModels.java', ROOT / 'app/src/main/java/dev/jun/gyeot/BrowserAgent.java', *sorted((ROOT / 'tests/browser-agent').glob('*.java'))]
    javac(['-encoding', 'UTF-8', '-d', browser_output, '-cp', flow_classpath, *browser_sources])
    run(['java', '-ea', '-cp', str(browser_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.BrowserAgentFlowTests'])
    run(['java', '-ea', '-cp', str(browser_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.BrowserPlannerPreferencesTests'])
    run(['java', '-ea', '-cp', str(browser_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.BrowserGateFlowTests'])
    run(['java', '-ea', '-cp', str(browser_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.BrowserDecisionFlowTests'])
    run(['java', '-ea', '-cp', str(browser_output) + os.pathsep + flow_classpath, 'dev.jun.gyeot.BrowserLiveTurnTests'])
    # The real browser startup/navigation loop, without native Gecko or Android device timing claims.
    engine_output = BUILD / 'browser-engine-tests'; engine_output.mkdir(parents=True, exist_ok=True)
    engine_sources = [ROOT / 'app/src/main/java/dev/jun/gyeot/BrowserEngine.java', ROOT / 'app/src/main/java/dev/jun/gyeot/BrowserRuntime.java', *sorted((ROOT / 'tests/browser-engine').rglob('*.java'))]
    javac(['-encoding', 'UTF-8', '-d', engine_output, '-cp', str(output) + os.pathsep + classpath, *engine_sources])
    run(['java', '-ea', '-cp', str(engine_output) + os.pathsep + str(output) + os.pathsep + classpath, 'dev.jun.gyeot.BrowserEngineFlowTests'])
    run(['java', '-ea', '-cp', str(engine_output) + os.pathsep + str(output) + os.pathsep + classpath, 'dev.jun.gyeot.BrowserReturnHistoryTests'])
    subprocess.run([sys.executable, str(ROOT / 'tests/browser-engine/service-flow/run.py')], cwd=ROOT, check=True)
    for suite in ('file-explorer', 'file-search', 'music-download', 'dictation'):
        subprocess.run(['sh', str(ROOT / 'tests' / suite / 'run.sh')], cwd=ROOT, env=dict(os.environ, JSON_JAR=str(json_jar)), check=True)


def compile_app():
    """test() covers core classes only. This compiles MainActivity, Ui, BrowserActivity and every other Android source
    against the real SDK and GeckoView, so a screen code error fails the pull request check before merging instead of
    the release build after it. Nothing is packaged, signed or published."""
    sdk()
    import importlib.util
    spec = importlib.util.spec_from_file_location('browser_build', ROOT / 'tools/browser_build.py')
    browser_build = importlib.util.module_from_spec(spec); spec.loader.exec_module(browser_build)
    browser_build.compile_sources(ROOT, CACHE)


def build(android, tools, jars):
    config = gradle_config()
    import importlib.util
    spec = importlib.util.spec_from_file_location('browser_build', ROOT / 'tools/browser_build.py')
    browser_build = importlib.util.module_from_spec(spec); spec.loader.exec_module(browser_build)
    universal = browser_build.assemble(ROOT, CACHE)
    phone = browser_build.phone_apk(universal, BUILD / 'phone-unaligned.apk')
    aligned = BUILD / 'phone-aligned.apk'
    run([tools / 'zipalign', '-f', '4', phone, aligned])
    keystore = CACHE / 'gyeot-local-test.keystore'
    if not keystore.exists():
        java_path = Path(shutil.which('java')).resolve()
        keytool = java_path.parent / 'keytool'
        run([keytool, '-genkeypair', '-keystore', keystore, '-storepass', 'android', '-keypass', 'android', '-alias', 'gyeot-local-test', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Gyeot Local Test,O=Personal Prototype,C=KR'])
        keystore.chmod(0o600)
    for suffix, source in (('', aligned), ('-universal', universal)):
        output = BUILD / f"gyeot-{config['versionName']}{suffix}-debug.apk"
        run(['java', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', keystore, '--ks-key-alias', 'gyeot-local-test', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, source])
        verify = subprocess.run([str(x) for x in ['java', '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', output]],
                                cwd=ROOT, check=True, capture_output=True, text=True)
        print(verify.stdout, end='', flush=True)
        expected = os.environ.get('GYEOT_SIGNING_CERT', '').strip().lower()
        if expected:
            found = re.findall(r'certificate SHA-256 digest: ([0-9a-f]{64})', verify.stdout)
            if found != [expected]:
                output.unlink()
                raise SystemExit(f'APK signed with {found or "no certificate"}, expected {expected}: refusing release.')
        if not suffix and output.stat().st_size > 100 * 1024 * 1024: raise SystemExit('Signed APK exceeds the legacy updater size limit')
        run([tools / 'aapt2', 'dump', 'badging', output])
        print('APK:', output, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--test-only', action='store_true')
    # With --test-only, also compile the Android screens. Pull request checks turn this on by themselves.
    parser.add_argument('--compile-app', action='store_true')
    args = parser.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True); BUILD.mkdir(parents=True, exist_ok=True)
    jars = dependencies()
    # The checks run where the tests folder is present; without it only the app is compiled and built.
    if (ROOT / 'tests').is_dir(): test(jars)
    if args.test_only and (args.compile_app or os.environ.get('GITHUB_EVENT_NAME') == 'pull_request'):
        compile_app()
    if not args.test_only:
        android, tools = sdk(); build(android, tools, jars)





