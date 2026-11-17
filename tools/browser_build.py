"""Pinned Gradle build for GeckoView's native engine, resources, and transitive dependencies."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import urllib.parse
import zipfile
import xml.etree.ElementTree as ET

VERSION = '9.8.2'
SHA256 = '21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607'

def platform_metadata(target, package=None):
    """Install the official repository's metadata alongside the flattened SDK archive."""
    if (target / 'package.xml').exists(): return
    if package is None:
        manifest = ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=60).read())
        package = next(p for p in manifest if p.attrib.get('path') == 'platforms;android-37.2')
    root = ET.Element('common:repository', {
        'xmlns:common': 'http://schemas.android.com/repository/android/common/02',
        'xmlns:sdk': 'http://schemas.android.com/sdk/android/repo/repository2/04',
        'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance',
    })
    local = ET.SubElement(root, 'localPackage', {'path': 'platforms;android-37.2', 'obsolete': 'false'})
    details = ET.SubElement(local, 'type-details', {'xsi:type': 'sdk:platformDetailsType'})
    source_details = next(p for p in package if p.tag.rsplit('}', 1)[-1] == 'type-details')
    for child in source_details: details.append(ET.fromstring(ET.tostring(child)))
    for child in package:
        if child.tag.rsplit('}', 1)[-1] in ('revision', 'display-name'):
            local.append(ET.fromstring(ET.tostring(child)))
    ET.ElementTree(root).write(target / 'package.xml', encoding='utf-8', xml_declaration=True)

def phone_apk(source, destination):
    """ARM64 phone package for the legacy 100MiB updater; retain a universal APK separately."""
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, 'w') as target:
        for entry in original.infolist():
            name = entry.filename
            if name.startswith('lib/') and not name.startswith('lib/arm64-v8a/'): continue
            if name.startswith('META-INF/') and (name.endswith(('.RSA', '.DSA', '.EC', '.SF')) or name == 'META-INF/MANIFEST.MF'): continue
            # ART extracts ordinary DEX files during installation; compress them on the wire too.
            # resources.arsc remains stored so Android can memory-map it after zipalign.
            dex = '/' not in name and name.startswith('classes') and name.endswith('.dex')
            compression = zipfile.ZIP_DEFLATED if name.startswith('lib/') or dex else entry.compress_type
            target.writestr(entry, original.read(name), compress_type=compression, compresslevel=9)
    with zipfile.ZipFile(destination) as apk:
        names = set(apk.namelist())
        for required in ('AndroidManifest.xml', 'assets/browser/manifest.json', 'assets/browser/content.js', 'lib/arm64-v8a/libxul.so', 'lib/arm64-v8a/libmozglue.so'):
            if required not in names: raise RuntimeError('Phone APK is missing ' + required)
    if destination.stat().st_size > 100 * 1024 * 1024 - 1024 * 1024:
        raise RuntimeError('Phone APK cannot fit the legacy 100MiB updater with signing overhead')
    return destination

def platform(cache):
    target = cache / 'platforms/android-37.2'
    if (target / 'android.jar').exists() and (target / 'build.prop').exists():
        platform_metadata(target)
        return
    package = None
    nested = target / 'android-37.2'
    if not (nested / 'android.jar').exists():
        manifest = ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=60).read())
        package = next(p for p in manifest if p.attrib.get('path') == 'platforms;android-37.2')
        archive = next(e for e in package.iter() if e.tag.rsplit('}', 1)[-1] == 'archive')
        values = {e.tag.rsplit('}', 1)[-1]: (e.text or '').strip() for e in archive.iter()}
        path = cache / 'android-37.2.zip'
        with urllib.request.urlopen('https://dl.google.com/android/repository/' + values['url'], timeout=60) as source, path.open('wb') as output: shutil.copyfileobj(source, output)
        if hashlib.sha1(path.read_bytes()).hexdigest() != values['checksum']: raise ValueError('Android SDK checksum mismatch')
        with zipfile.ZipFile(path) as z:
            for item in z.infolist():
                if not (target / item.filename).resolve().is_relative_to(target.resolve()): raise ValueError('Invalid SDK archive path')
            z.extractall(target)
    # Some SDK archives contain more than one platform root. Install only the requested SDK.
    if not (nested / 'android.jar').exists(): raise ValueError('Android 37.2 platform missing')
    for item in nested.iterdir():
        destination = target / item.name
        if destination.exists():
            if destination.is_dir(): shutil.rmtree(destination)
            else: destination.unlink()
        shutil.move(str(item), destination)
    nested.rmdir()
    platform_metadata(target, package)

def gradle_command(cache):
    """The pinned Gradle and the SDK it builds against: returns the command prefix and its environment."""
    archive = cache / f'gradle-{VERSION}-bin.zip'
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        partial = archive.with_suffix('.partial')
        with urllib.request.urlopen('https://services.gradle.org/distributions/' + archive.name, timeout=60) as source, partial.open('wb') as output:
            shutil.copyfileobj(source, output)
        if hashlib.sha256(partial.read_bytes()).hexdigest() != SHA256:
            raise ValueError('Gradle distribution checksum mismatch')
        partial.replace(archive)
    gradle = cache / f'gradle-{VERSION}/bin/gradle'
    if not gradle.exists():
        with zipfile.ZipFile(archive) as z:
            for item in z.infolist():
                if not (cache / item.filename).resolve().is_relative_to(cache.resolve()): raise ValueError('Invalid Gradle archive path')
            z.extractall(cache)
    gradle.chmod(0o755)
    platform(cache)
    licenses = cache / 'licenses'; licenses.mkdir(exist_ok=True)
    (licenses / 'android-sdk-license').write_text('8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n')
    env = dict(os.environ, ANDROID_HOME=str(cache), ANDROID_SDK_ROOT=str(cache))
    proxy_args = []
    for protocol in ('https', 'http'):
        proxy = urllib.parse.urlsplit(env.get(protocol.upper() + '_PROXY', ''))
        if proxy.hostname:
            proxy_args.extend([f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 80}'])
    return [str(gradle), *proxy_args, '--no-daemon', '--console=plain'], env


def compile_sources(root, cache):
    """Compile every Android source, screens included, against the real SDK and GeckoView. Nothing is packaged or signed."""
    command, env = gradle_command(cache)
    subprocess.run([*command, ':app:compileDebugJavaWithJavac'], cwd=root, env=env, check=True)


def assemble(root, cache):
    command, env = gradle_command(cache)
    subprocess.run([*command, ':app:assembleDebug'], cwd=root, env=env, check=True)
    apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
    if not apk.exists(): raise RuntimeError('Gradle did not produce an APK')
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        if any(n.startswith('lib/') and not n.endswith('.so') for n in names): raise RuntimeError('Unexpected temporary native file in APK')
        for required in ('assets/browser/manifest.json', 'assets/browser/content.js', 'lib/arm64-v8a/libxul.so', 'lib/arm64-v8a/libmozglue.so'):
            if required not in names: raise RuntimeError('APK is missing ' + required)
    return apk
