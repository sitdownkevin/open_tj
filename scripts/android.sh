#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/apps/android"
VERSION="$(tr -d '\r\n' < VERSION)"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]{1,2}\.[0-9]{1,2}$ ]] || {
  echo 'Invalid apps/android/VERSION' >&2
  exit 1
}

package_apk() {
  local source="$1" kind="$2" name="$3"
  local directory="$ROOT/dist/android/$kind"
  mkdir -p "$directory"
  cp "$source" "$directory/$name"
  (cd "$directory" && shasum -a 256 "$name" > "$name.sha256")
  printf 'Android %s APK: %s/%s\n' "$kind" "$directory" "$name"
}

case "${1:-build}" in
  test) ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug ;;
  build)
    ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
    package_apk app/build/outputs/apk/debug/app-debug.apk debug "OpenTJ-$VERSION-android-debug.apk"
    ;;
  release)
    ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
    package_apk app/build/outputs/apk/release/app-release-unsigned.apk unsigned "OpenTJ-$VERSION-android-unsigned.apk"
    ;;
  *) echo "Usage: $0 [build|test|release]" >&2; exit 1 ;;
esac
