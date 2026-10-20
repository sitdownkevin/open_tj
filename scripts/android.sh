#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/apps/android"

case "${1:-build}" in
  test) ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug ;;
  build)
    ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
    mkdir -p "$ROOT/dist"
    VERSION="$(tr -d '\n' < "$ROOT/VERSION")"
    APK="OpenTJ-$VERSION-android-preview.apk"
    cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/dist/$APK"
    (cd "$ROOT/dist" && shasum -a 256 "$APK" > "$APK.sha256")
    printf 'Android preview: %s/dist/%s\n' "$ROOT" "$APK"
    ;;
  release) ./gradlew :core:test :app:lintRelease :app:assembleRelease ;;
  *) echo "Usage: $0 [build|test|release]" >&2; exit 1 ;;
esac
