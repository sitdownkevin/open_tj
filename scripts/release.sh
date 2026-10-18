#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${SIGN_IDENTITY:?Set SIGN_IDENTITY to your Developer ID Application identity}"
: "${NOTARY_PROFILE:?Set NOTARY_PROFILE to a notarytool Keychain profile}"
[[ "$SIGN_IDENTITY" == 'Developer ID Application:'* ]] || { echo 'Developer ID Application certificate required' >&2; exit 1; }
export ARCHS="${ARCHS:-arm64 x86_64}"
bash scripts/test.sh
bash scripts/package.sh
VERSION="$(tr -d '\n' < VERSION)"
LABEL="$(echo "$ARCHS" | tr ' ' '-')"
ZIP="dist/OpenTJ-$VERSION-$LABEL.zip"
xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARY_PROFILE" --wait
xcrun stapler staple 'build/Open TJ.app'
xcrun stapler validate 'build/Open TJ.app'
spctl --assess --type execute --verbose=2 'build/Open TJ.app'
rm -f "$ZIP"
ditto -c -k --sequesterRsrc --keepParent 'build/Open TJ.app' "$ZIP"
shasum -a 256 "$ZIP" > "$ZIP.sha256"
echo "Notarized release: $ZIP"
