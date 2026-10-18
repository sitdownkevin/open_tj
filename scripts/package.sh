#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export ARCHS="${ARCHS:-arm64 x86_64}"
bash scripts/build.sh
mkdir -p dist
VERSION="$(tr -d '\n' < VERSION)"
LABEL="$(echo "$ARCHS" | tr ' ' '-')"
ZIP="dist/OpenTJ-$VERSION-$LABEL.zip"
rm -f "$ZIP"
ditto -c -k --sequesterRsrc --keepParent 'build/Open TJ.app' "$ZIP"
shasum -a 256 "$ZIP" > "$ZIP.sha256"
printf 'Packaged: %s\n' "$ZIP"
