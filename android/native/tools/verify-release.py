#!/usr/bin/env python3
"""Read-only release gates. Does not install or claim Android runtime testing."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
import xml.etree.ElementTree as ET

if os.environ.get('JAVA_HOME'):
    os.environ['PATH'] = str(Path(os.environ['JAVA_HOME']) / 'bin') + os.pathsep + os.environ.get('PATH', '')

native = Path(__file__).resolve().parents[1]
android = native.parent
sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / '.buildozer/android/platform/android-sdk'))
analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
signer = sdk / 'build-tools/34.0.0/apksigner'
apk = android / 'bin/videostore-0.4.0-arm64-v8a-release.apk'
previous = android / 'bin/videostore-0.2.1-arm64-v8a_armeabi-v7a-release.apk'

def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True).strip()

def manifest(field):
    return run(analyzer, 'manifest', field, apk)

assert manifest('application-id') == 'org.callmebakugo.videostore'
assert manifest('version-code') == '1040000'
assert manifest('version-name') == '0.4.0'
assert manifest('debuggable') == 'false'
permissions = set(manifest('permissions').splitlines())
assert permissions == {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE', 'android.permission.MANAGE_EXTERNAL_STORAGE',
                       'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_DATA_SYNC'}, permissions
xml = ET.fromstring(manifest('print'))
a = '{http://schemas.android.com/apk/res/android}'
app = xml.find('application')
assert app.get(a + 'extractNativeLibs') == 'true'
service = app.find('service')
assert service.get(a + 'foregroundServiceType') in {'dataSync', '0x1', '1'}
assert service.get(a + 'exported') == 'false'
assert app.get(a + 'allowBackup') == 'false'
verification = run(signer, 'verify', '--verbose', '--print-certs', apk)
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in verification
old_verification = run(signer, 'verify', '--print-certs', previous)
pattern = r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)'
certificate = re.search(pattern, verification).group(1)
assert certificate == re.search(pattern, old_verification).group(1), 'Release signer changed'
with zipfile.ZipFile(apk) as archive:
    libs = [name for name in archive.namelist() if name.startswith('lib/')]
    assert {name.split('/')[1] for name in libs} == {'arm64-v8a'}
    assert {'libpython.so', 'libpython.zip.so', 'libffmpeg.so', 'libffmpeg.zip.so', 'libqjs.so'} <= {Path(name).name for name in libs}
for source in (android / 'app/res').rglob('*'):
    if source.is_file():
        target = native / 'app/src/main/res' / source.relative_to(android / 'app/res')
        assert source.read_bytes() == target.read_bytes(), f'Launcher resource differs: {source}'
audit_hashes = {
    'ENGINE-AUDIT.md': 'e23107f4096055aa8750a0f7ea4dd7df30247058262dd05b37292dfa6232982a',
    'audit/engine-0.18.1.json': 'c4277d7c82dbbfafa876c50cfb951086abed10bce2bdb51cebcda0aa62c8edc0',
}
for name, digest in audit_hashes.items():
    assert hashlib.sha256((native / name).read_bytes()).hexdigest() == digest, f'Audit record changed: {name}'
report = {
    'apk': str(apk.relative_to(android.parent)),
    'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'bytes': apk.stat().st_size,
    'package': manifest('application-id'),
    'versionCode': int(manifest('version-code')),
    'versionName': manifest('version-name'),
    'minSdk': int(manifest('min-sdk')),
    'targetSdk': int(manifest('target-sdk')),
    'debuggable': False,
    'abis': ['arm64-v8a'],
    'permissions': sorted(permissions),
    'signatureV2': True,
    'signerSha256': certificate,
    'signerMatchesPublished021': True,
    'launcherSourceAssetsMatch': True,
    'auditRecordsUnchanged': True,
    'deviceAcceptance': 'NOT RUN: no adb device connected',
}
(native / 'release-verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
