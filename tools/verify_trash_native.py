#!/usr/bin/env python3
"""Build/inspect the pinned trash JNI library; no APK assembly, signing, or publication."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NDK_VERSION = '28.2.13676358'
NDK_ARCHIVE = 'android-ndk-r28c-linux.zip'
NDK_SHA1 = 'a7b54a5de87fecd125a17d54f73c446199e72a64'
NDK_SIZE = 722261334
NDK_URL = 'https://dl.google.com/android/repository/' + NDK_ARCHIVE
ABIS = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86_64': (2, 62)}
SYMBOL = 'Java_dev_jun_gyeot_TrashNative_moveNoReplaceNative'
LIBRARY = 'libgyeot_trash.so'


def valid_ndk(path):
    props = path / 'source.properties'
    return props.is_file() and any(line.strip() == 'Pkg.Revision = ' + NDK_VERSION for line in props.read_text().splitlines()) and (path / 'ndk-build').is_file()


def extract_archive(archive, destination):
    # Fresh staging only. NDK has relative symlinks; never extract through one.
    destination.mkdir(parents=True, exist_ok=False)
    expected = destination / 'android-ndk-r28c'
    with zipfile.ZipFile(archive) as source:
        links = {}; members = source.infolist()
        names = [info.filename for info in members]
        if len(names) != len(set(names)): raise ValueError('Duplicate NDK archive member')
        for info in members:
            target = destination / info.filename
            if not target.resolve().is_relative_to(expected.resolve()): raise ValueError('Unsafe NDK archive member')
            if stat.S_ISLNK(info.external_attr >> 16):
                value = source.read(info).decode('utf-8')
                if len(value) > 4096 or Path(value).is_absolute() or not (target.parent / value).resolve().is_relative_to(expected.resolve()):
                    raise ValueError('Unsafe NDK symlink')
                links[info.filename.rstrip('/')] = value
        for info in members:
            parts = Path(info.filename).parts
            if any(str(Path(*parts[:i])) in links for i in range(1, len(parts))): raise ValueError('Archive member descends through symlink')
            if info.filename.rstrip('/') in links: continue
            target = destination / info.filename
            if info.is_dir(): target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(info) as incoming, target.open('xb') as output: shutil.copyfileobj(incoming, output)
                target.chmod(0o755 if (info.external_attr >> 16) & 0o111 else 0o644)
        for name, value in links.items():
            target = destination / name; target.parent.mkdir(parents=True, exist_ok=True); target.symlink_to(value)


def prepare_ndk(cache, explicit=None):
    target = cache / 'ndk' / NDK_VERSION
    candidates = [Path(explicit)] if explicit else [target]
    if not explicit:
        for key in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT'):
            if os.environ.get(key): candidates.append(Path(os.environ[key]))
        for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
            if os.environ.get(key): candidates.append(Path(os.environ[key]) / 'ndk' / NDK_VERSION)
    for candidate in candidates:
        if valid_ndk(candidate):
            if candidate.resolve() != target.resolve():
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists() or target.is_symlink(): raise ValueError('Existing NDK target is not the requested pinned version')
                target.symlink_to(candidate.resolve(), target_is_directory=True)
            return target
    if explicit: raise ValueError('Explicit NDK must be pinned ' + NDK_VERSION)
    if target.exists() or target.is_symlink(): raise ValueError('Existing NDK target is invalid; refusing to replace it')
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='trash-ndk-', dir=target.parent) as tmp:
        stage = Path(tmp); archive = stage / NDK_ARCHIVE
        digest = hashlib.sha1(); count = 0
        with urllib.request.urlopen(NDK_URL, timeout=120) as incoming, archive.open('wb') as output:
            while block := incoming.read(1024 * 1024):
                count += len(block)
                if count > NDK_SIZE: raise ValueError('Unexpected NDK archive size')
                digest.update(block); output.write(block)
        if count != NDK_SIZE or digest.hexdigest() != NDK_SHA1: raise ValueError('Pinned NDK archive checksum/size mismatch')
        extract_archive(archive, stage / 'unpacked')
        unpacked = stage / 'unpacked/android-ndk-r28c'
        if not valid_ndk(unpacked): raise ValueError('Pinned NDK metadata missing')
        unpacked.rename(target)
    return target


def verify_elf(path, abi):
    data = path.read_bytes()
    kind, machine = ABIS[abi]
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[4] != kind or data[5] != 1 or struct.unpack_from('<H', data, 18)[0] != machine:
        raise ValueError('Wrong ELF ABI: ' + abi)
    if struct.unpack_from('<H', data, 16)[0] != 3: raise ValueError('Expected shared library')
    if kind == 2:
        offset = struct.unpack_from('<Q', data, 32)[0]; width, count = struct.unpack_from('<HH', data, 54)
        align_offset, align_format, minimum = 48, '<Q', 56
    else:
        offset = struct.unpack_from('<I', data, 28)[0]; width, count = struct.unpack_from('<HH', data, 42)
        align_offset, align_format, minimum = 28, '<I', 32
    if width < minimum or count == 0 or offset + width * count > len(data): raise ValueError('Invalid ELF program headers')
    loads = []
    for i in range(count):
        at = offset + i * width
        if struct.unpack_from('<I', data, at)[0] == 1: loads.append(struct.unpack_from(align_format, data, at + align_offset)[0])
    if not loads: raise ValueError('ELF has no LOAD segment')
    if abi in ('arm64-v8a', 'x86_64') and any(align < 16384 for align in loads): raise ValueError('64-bit library lacks 16 KiB LOAD alignment')


def inspect_libraries(ndk, directory):
    present = {p.parent.name for p in directory.glob('*/' + LIBRARY)}
    if present != set(ABIS): raise ValueError('Unexpected or missing trash library ABI set')
    nm = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm'
    for abi in ABIS:
        path = directory / abi / LIBRARY
        verify_elf(path, abi)
        symbols = subprocess.run([str(nm), '-D', '--defined-only', str(path)], check=True, capture_output=True, text=True).stdout
        if not any(line.split()[-1:] == [SYMBOL] for line in symbols.splitlines()): raise ValueError('JNI entry missing: ' + abi)
        print(abi + ': ELF ABI, JNI export and page alignment verified')


def verify_gecko_abis(directory):
    present = {p.parent.name for p in directory.glob('*/libxul.so')}
    if present != set(ABIS): raise ValueError('Gecko and trash JNI ABI sets must match exactly')
    for abi, (kind, machine) in ABIS.items():
        with (directory / abi / 'libxul.so').open('rb') as library: header = library.read(20)
        if len(header) < 20 or header[:4] != b'\x7fELF' or header[4] != kind or header[5] != 1 or struct.unpack_from('<H', header, 18)[0] != machine:
            raise ValueError('Wrong Gecko ELF ABI: ' + abi)
    print('Merged Gecko and trash JNI share the exact supported ABI set')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--skip-gradle', action='store_true', help='Only direct NDK compile; does NOT verify Android Gradle packaging')
    args = parser.parse_args()
    cache = Path(os.environ.get('GYEOT_BUILD_CACHE', ROOT / '.cache')).resolve(); cache.mkdir(parents=True, exist_ok=True)
    if not shutil.which("gcc"): raise RuntimeError("Host gcc is required for the production JNI syscall regression; do not skip it")
    ndk = prepare_ndk(cache, args.ndk)
    if not (ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/jni.h").is_file():
        raise RuntimeError("Pinned JNI headers missing; do not skip the native syscall regression")
    output = ROOT / 'build/trash-native-verification'; output.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(ndk / 'ndk-build'), 'NDK_PROJECT_PATH=null', 'APP_BUILD_SCRIPT=' + str(ROOT / 'app/src/main/jni/Android.mk'),
        'NDK_APPLICATION_MK=' + str(ROOT / 'app/src/main/jni/Application.mk'), 'NDK_OUT=' + str(output / 'obj'),
        'NDK_LIBS_OUT=' + str(output / 'lib'), '-j2'], cwd=ROOT, check=True)
    inspect_libraries(ndk, output / 'lib')
    if args.skip_gradle:
        print('Direct native verification passed; Gradle packaging was explicitly NOT run.')
        return
    import browser_build
    command, env = browser_build.gradle_command(cache)
    subprocess.run([*command, ':app:externalNativeBuildDebug', ':app:mergeDebugNativeLibs'], cwd=ROOT, env=env, check=True)
    merged = ROOT / 'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib'
    inspect_libraries(ndk, merged)
    verify_gecko_abis(merged)
    print('Native compile and Android Gradle merged-library verification passed; no APK signed or published.')

if __name__ == '__main__': main()
