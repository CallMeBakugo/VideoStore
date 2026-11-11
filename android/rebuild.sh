#!/usr/bin/env bash
# Run on the build host, never in the restricted editing environment.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
ANDROID_DIR=$PWD
command -v mise >/dev/null || { echo 'mise is required' >&2; exit 1; }
JAVA_HOME=$(mise where java@temurin-17)
PYTHON_HOME=$(mise where python@3.12)
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PYTHON_HOME/bin:$PATH"
"$PYTHON_HOME/bin/python3" -c 'import sys; assert sys.version_info[:2] == (3, 12), sys.version'
"$JAVA_HOME/bin/java" -version
unset PYTHONHOME PYTHONPATH VIRTUAL_ENV
# Proven release path from the v0.2.x builds on this host: buildozer and
# cython run from the mise Python 3.12 host interpreter, NEVER inside a venv —
# p4a shells out to `pip install --user`, which aborts inside virtualenvs
# ("Can not perform a '--user' install", observed Oct 2026). Clean PATH: no
# mise shims, no host venv, Java first.
export PATH="$JAVA_HOME/bin:$PYTHON_HOME/bin:/usr/bin:/bin"
"$PYTHON_HOME/bin/pip" show buildozer >/dev/null 2>&1 || "$PYTHON_HOME/bin/pip" install buildozer
"$PYTHON_HOME/bin/pip" show Cython   >/dev/null 2>&1 || "$PYTHON_HOME/bin/pip" install Cython
"$PYTHON_HOME/bin/cython" --version
"$PYTHON_HOME/bin/buildozer" version
[[ -f buildozer.secrets ]] || { echo 'Missing android/buildozer.secrets' >&2; exit 1; }
# This trusted, gitignored file exports the release signing variables. No xtrace.
source "$ANDROID_DIR/buildozer.secrets"
# The secrets file exports the friendly VS_* names; p4a reads only the
# P4A_RELEASE_* names — map them here, tolerating either naming.
: "${P4A_RELEASE_KEYSTORE:=${VS_KEYSTORE:?secrets file defines neither P4A nor VS keystore name}}"
: "${P4A_RELEASE_KEYALIAS:=${VS_KEYSTORE_ALIAS:?secrets file defines neither P4A nor VS key alias}}"
: "${P4A_RELEASE_KEYSTORE_PASSWD:=${VS_KEYSTORE_PASS:?secrets file defines neither P4A nor VS keystore password}}"
: "${P4A_RELEASE_KEYALIAS_PASSWD:=${VS_KEY_PASS:?secrets file defines neither P4A nor VS key password}}"
export P4A_RELEASE_KEYSTORE P4A_RELEASE_KEYSTORE_PASSWD
export P4A_RELEASE_KEYALIAS P4A_RELEASE_KEYALIAS_PASSWD

# Delete the whole project cache: build-* (all architectures), dists,
# bootstrap builds, recipe builds, and the pinned p4a checkout itself.
# Keep the host's downloaded SDK/NDK, but discard global recipe archives
# and any global p4a build/dist/bootstrap caches as well.
rm -rf -- "$ANDROID_DIR/.buildozer"
rm -rf -- "$HOME/.buildozer/android/packages"
shopt -s nullglob
for cache in "$HOME"/.buildozer/android/platform/build-* \
             "$HOME"/.buildozer/android/platform/python-for-android \
             "$HOME"/.buildozer/android/platform/dists \
             "$HOME"/.buildozer/android/platform/bootstrap_builds; do
    rm -rf -- "$cache"
done
find "$ANDROID_DIR/app" -type d -name __pycache__ -prune -exec rm -rf -- {} +
# Old release files must not be mistaken for this build's output.
mkdir -p bin
marker=$(mktemp)
build_log="$marker.build.log"
trap 'rm -f -- "$marker" "$build_log"' EXIT
# A fresh p4a hostpython build omits compiled stdlib extensions from Lib/.
# Let pass 1 build them, then stage them outside the reset p4a checkout.
if "$PYTHON_HOME/bin/buildozer" android release 2>&1 | tee "$build_log"; then
    :
else
    build_status=$?
    if ! grep -Fq "No module named 'math'" "$build_log"; then
        echo 'Build pass 1 failed without the expected missing-math error; stopping.' >&2
        exit "$build_status"
    fi
fi

host_libs=("$ANDROID_DIR"/.buildozer/android/platform/build-*/build/other_builds/hostpython3/desktop/hostpython3/Lib)
if (( ${#host_libs[@]} == 0 )); then
    echo 'No staged hostpython3 Lib directory found after build pass 1.' >&2
    exit 1
fi
for host_lib in "${host_libs[@]}"; do
    [[ -d "$host_lib/lib-dynload" ]] && continue
    extensions=("$host_lib"/../native-build/build/lib.*/*.so)
    if (( ${#extensions[@]} == 0 )); then
        echo "No compiled hostpython3 extensions found for $host_lib" >&2
        exit 1
    fi
    mkdir -- "$host_lib/lib-dynload"
    cp -- "${extensions[@]}" "$host_lib/lib-dynload/"
done
# The cached hostpython binary skips rebuilding, preserving lib-dynload.
"$PYTHON_HOME/bin/buildozer" android release

python - "$ANDROID_DIR" "$marker" <<'PY'
import pathlib
import sys
import tarfile
import tempfile
import zipfile

root = pathlib.Path(sys.argv[1])
started = pathlib.Path(sys.argv[2]).stat().st_mtime_ns
apks = [p for p in (root / 'bin').glob('*.apk') if p.stat().st_mtime_ns >= started]
if len(apks) != 1:
    raise SystemExit(f'Expected exactly one fresh APK, found: {apks}')
apk = apks[0]
with tempfile.TemporaryDirectory(prefix='videostore-verify-') as directory:
    target = pathlib.Path(directory)
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            path = pathlib.PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts:
                raise SystemExit(f'Unsafe APK member: {name}')
        archive.extractall(target)
    libraries = list(target.rglob('*.so'))
    if not libraries:
        raise SystemExit('APK has no native libraries')
    for library in libraries:
        if b'libpython3.14.so' in library.read_bytes():
            raise SystemExit(f'Stale Python 3.14 reference: {library.relative_to(target)}')
    mains = list(target.rglob('libmain.so'))
    if not mains or any(b'libpython3.11.so' not in p.read_bytes() for p in mains):
        raise SystemExit('Every libmain.so must reference libpython3.11.so')
    if not list(target.rglob('libpython3.11.so')):
        raise SystemExit('Missing packaged Python 3.11 runtime')
    private = target / 'assets' / 'private.tar'
    with tarfile.open(private, 'r:*') as archive:
        if not any(pathlib.PurePosixPath(n).as_posix().removeprefix('./') ==
                   'videostore/main.pyc' for n in archive.getnames()):
            raise SystemExit('private.tar is missing videostore/main.pyc')
print(f'VERIFIED APK: {apk}')
PY
cat <<'STEPS'
Next steps:
  1. Review and commit the release changes, then create NEW tag v-mobile-0.3.0:
       git tag v-mobile-0.3.0
       git push origin v-mobile-0.3.0
     If the tag exists, STOP and use a new version/tag. Never force or replace it.
  2. Create the GitHub release for that tag and upload the VERIFIED release APK.
     Obtainium detects updates from tags.
  3. Install via Obtainium. Allow all-files access when Settings opens to save
     in Download; otherwise VideoStore uses its external app files directory.
  4. If launch still fails, capture adb logcat during launch:
       adb logcat -c
       adb logcat > videostore-launch.log
     Launch VideoStore, then stop logcat with Ctrl-C. Also collect
     Android/data/org.callmebakugo.videostore/files/videostore-crash.log
     if Python was able to start (use adb or the app's displayed log path).
STEPS
