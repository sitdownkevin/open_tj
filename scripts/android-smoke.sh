#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '\r\n' < "$ROOT/apps/android/VERSION")"
APK="${1:-$ROOT/dist/android/release/OpenTJ-$VERSION-android.apk}"
APP_ID="io.github.sitdownkevin.opentj"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

# Never run destructive test installation on a connected personal phone.
[[ "${ANDROID_SERIAL:-}" == emulator-* ]] || {
  echo 'Set ANDROID_SERIAL to a disposable emulator (emulator-*)' >&2
  exit 1
}
test -s "$APK"
BADGING="$("$SDK/build-tools/35.0.0/aapt2" dump badging "$APK")"
[[ "$BADGING" == *"package: name='$APP_ID'"* ]]
[[ "$BADGING" == *"versionName='$VERSION'"* ]]
[[ "$BADGING" == *"sdkVersion:'26'"* ]]
[[ "$BADGING" != *"application-debuggable"* ]]

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$APP_ID/.MainActivity"
adb shell pidof "$APP_ID" >/dev/null
adb shell uiautomator dump /sdcard/opentj-smoke.xml
UI="$(adb shell cat /sdcard/opentj-smoke.xml)"
[[ "$UI" == *"package=\"$APP_ID\""* && "$UI" == *'text="Open TJ"'* ]]

# Installing the same signed artifact again must keep the existing app installation.
adb install -r "$APK"
adb shell am force-stop "$APP_ID"
adb shell am start -W -n "$APP_ID/.MainActivity"
adb shell pidof "$APP_ID" >/dev/null
if adb logcat -d -b crash | grep -q 'FATAL EXCEPTION'; then
  adb logcat -d -b crash
  exit 1
fi
printf 'Release install, launch and reinstall passed on Android API %s\n' "$(adb shell getprop ro.build.version.sdk)"
