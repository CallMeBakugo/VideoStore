#!/usr/bin/env bash
set -euo pipefail
native_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
# Do not enable shell tracing: signing passwords must stay private.
set -a
source "$native_dir/../buildozer.secrets"
set +a
export JAVA_HOME
JAVA_HOME=$(mise where java@temurin-17)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.buildozer/android/platform/android-sdk}"
cd "$native_dir"
./gradlew assembleRelease --console=plain
install -m 644 app/build/outputs/apk/release/app-release.apk ../bin/videostore-0.5.0-arm64-v8a-release.apk
