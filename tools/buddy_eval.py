#!/usr/bin/env python3
"""퍼레 자동 점검, off the phone: the scenarios in app/src/main/assets/buddy/scenarios.json through the app's own words
layer and the very request the voice talk sends (tools/BuddyEval.java over the app's plain-Java classes: BuddyPrompt,
BuddyTools, BuddyCheck, core/ChatStream), to NVIDIA NIM's quick model, one at a time. Prints a pass/fail table.

  python3 tools/buddy_eval.py                 # words + the voice talk's brain
  python3 tools/buddy_eval.py --path all      # also 퍼레와 대화 (the text form, NVIDIA's chat model: slower)
  python3 tools/buddy_eval.py --only 유튜브    # only the scenarios whose words contain this
  python3 tools/buddy_eval.py --words-only    # no network

The key: NIM_API_KEY (or NVIDIA_API_KEY) when set; in the cloud session the proxy puts it in, so a dummy goes. No key or
no network (GitHub CI): it says so and checks the words alone. Exit 1 when a scenario failed, 2 when the file is broken.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]


def json_jar():
    """The org.json jar the core tests use (tools/build.py fetches it, checked against tools/dependency-lock.json)."""
    jar = ROOT / '.deps/json-20250107.jar'
    if jar.is_file():
        return jar
    spec = importlib.util.spec_from_file_location('gyeot_build', ROOT / 'tools/build.py')
    build = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(build)
    return build.dependencies()[-1]


def javac():
    found = shutil.which('javac')
    return [found] if found else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--path', choices=['voice', 'chat', 'all'], default='voice')
    parser.add_argument('--only', help='only scenarios whose words contain this')
    parser.add_argument('--words-only', action='store_true')
    parser.add_argument('--verbose', action='store_true', help='every words-layer row, not only the failures')
    parser.add_argument('--raw', action='store_true', help='each brain answer as it came (its words and tool calls)')
    parser.add_argument('--model', help='another NIM model id (default: the app\'s, BuddyPrompt.NIM_MODEL)')
    parser.add_argument('--scenarios', type=Path, default=ROOT / 'app/src/main/assets/buddy/scenarios.json')
    parser.add_argument('--json', type=Path, default=ROOT / 'build/buddy-eval/result.json')
    args = parser.parse_args()

    jar = json_jar()
    out = ROOT / 'build/buddy-eval/classes'
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    # Only what BuddyEval reaches is compiled (-sourcepath): the app's plain-Java classes, never an Android one.
    subprocess.run([*javac(), '-encoding', 'UTF-8', '-nowarn', '-d', str(out), '-cp', str(jar),
                    '-sourcepath', str(ROOT / 'app/src/main/java'), str(ROOT / 'tools/BuddyEval.java')], check=True)

    proxy = []
    for protocol in ('https', 'http'):
        address = urllib.parse.urlsplit(os.environ.get(protocol.upper() + '_PROXY', ''))
        if address.hostname:
            proxy += [f'-D{protocol}.proxyHost={address.hostname}', f'-D{protocol}.proxyPort={address.port or 80}']
    command = ['java', *proxy, '-cp', os.pathsep.join([str(out), str(jar)]), 'dev.jun.gyeot.BuddyEval',
               '--scenarios', str(args.scenarios), '--path', args.path, '--json', str(args.json)]
    if args.only:
        command += ['--only', args.only]
    if args.words_only:
        command.append('--words-only')
    if args.verbose:
        command.append('--verbose')
    if args.raw:
        command.append('--raw')
    if args.model:
        command += ['--model', args.model]
    result = subprocess.run(command, cwd=ROOT)
    print(f'\n결과 파일: {args.json.relative_to(ROOT) if args.json.is_relative_to(ROOT) else args.json}', flush=True)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
