#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '\r\n' < "$ROOT/apps/android/VERSION")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
TOOLS="$SDK/build-tools/35.0.0"
INPUT="${1:-$ROOT/dist/android/unsigned/OpenTJ-$VERSION-android-unsigned.apk}"
OUTPUT="$ROOT/dist/android/release"
NAME="OpenTJ-$VERSION-android.apk"

: "${ANDROID_KEYSTORE_FILE:?Set ANDROID_KEYSTORE_FILE}"
: "${ANDROID_KEYSTORE_PASSWORD:?Set ANDROID_KEYSTORE_PASSWORD}"
: "${ANDROID_KEY_ALIAS:?Set ANDROID_KEY_ALIAS}"
: "${ANDROID_KEY_PASSWORD:?Set ANDROID_KEY_PASSWORD}"
test -s "$ANDROID_KEYSTORE_FILE"
test -s "$INPUT"
test -x "$TOOLS/apksigner"
test -x "$TOOLS/zipalign"
test -s "$ROOT/apps/android/release-certificate.sha256"
BADGING="$("$TOOLS/aapt2" dump badging "$INPUT")"
if [[ "$BADGING" == *"application-debuggable"* ]]; then
  echo 'Refusing to publish a debuggable APK' >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
"$TOOLS/zipalign" -P 16 -f 4 "$INPUT" "$WORK/aligned.apk"
"$TOOLS/apksigner" sign \
  --ks "$ANDROID_KEYSTORE_FILE" --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$WORK/$NAME" "$WORK/aligned.apk"
"$TOOLS/zipalign" -P 16 -c 4 "$WORK/$NAME"
"$TOOLS/apksigner" verify --verbose --print-certs --min-sdk-version 26 "$WORK/$NAME" > "$WORK/signature.txt"
EXPECTED="$(tr -d '[:space:]' < "$ROOT/apps/android/release-certificate.sha256")"
ACTUAL="$(awk -F ': ' '/^Signer #1 certificate SHA-256 digest:/ {print $2}' "$WORK/signature.txt")"
if [[ ! "$EXPECTED" =~ ^[a-f0-9]{64}$ || "$ACTUAL" != "$EXPECTED" ]] ||
    grep -q 'CN=Android Debug' "$WORK/signature.txt"; then
  echo 'Signing certificate does not match the pinned release certificate' >&2
  exit 1
fi

mkdir -p "$OUTPUT"
cp "$WORK/$NAME" "$OUTPUT/$NAME"
cp "$ROOT/apps/android/release-certificate.sha256" "$OUTPUT/release-certificate.sha256"
(cd "$OUTPUT" && shasum -a 256 "$NAME" > "$NAME.sha256")
cat "$WORK/signature.txt"
printf 'Signed release APK: %s/%s\n' "$OUTPUT" "$NAME"
