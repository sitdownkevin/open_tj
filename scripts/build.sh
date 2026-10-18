#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(tr -d '\n' < VERSION)"
ARCHS="${ARCHS:-$(uname -m)}"
IDENTITY="${SIGN_IDENTITY:--}"
BUILD_NUMBER="${BUILD_NUMBER:-1}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid VERSION' >&2; exit 1; }
[[ "$BUILD_NUMBER" =~ ^[0-9]+$ ]] || { echo 'Invalid BUILD_NUMBER' >&2; exit 1; }
APP="$ROOT/build/Open TJ.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$ROOT/build/objects"
swift_bins=(); go_bins=()
for arch in $ARCHS; do
  case "$arch" in arm64) goarch=arm64;; x86_64) goarch=amd64;; *) echo "Unsupported architecture: $arch" >&2; exit 1;; esac
  (cd backend && CGO_ENABLED=0 GOOS=darwin GOARCH="$goarch" go build -trimpath -o "$ROOT/build/objects/motionvpn-$arch" ./cmd/motionvpn)
  swiftc -target "$arch-apple-macos14.0" -parse-as-library -O -framework SwiftUI -framework AppKit apps/macos/OpenTJ.swift -o "build/objects/OpenTJ-$arch"
  swift_bins+=("build/objects/OpenTJ-$arch"); go_bins+=("build/objects/motionvpn-$arch")
done
lipo -create "${swift_bins[@]}" -output "$APP/Contents/MacOS/OpenTJ"
lipo -create "${go_bins[@]}" -output "$APP/Contents/Resources/motionvpn"
bash scripts/icon.sh
cp build/OpenTJ.icns "$APP/Contents/Resources/"
cp -R licenses "$APP/Contents/Resources/"
cp LICENSE THIRD_PARTY_NOTICES.md "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>OpenTJ</string>
<key>CFBundleIdentifier</key><string>local.motionvpn.prototype</string>
<key>CFBundleName</key><string>Open TJ</string>
<key>CFBundleDisplayName</key><string>Open TJ</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleIconFile</key><string>OpenTJ</string>
<key>CFBundleShortVersionString</key><string>$VERSION</string>
<key>CFBundleVersion</key><string>$BUILD_NUMBER</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
sign_args=(--force --sign "$IDENTITY" --options runtime)
if [[ "$IDENTITY" != - ]]; then sign_args+=(--timestamp); fi
codesign "${sign_args[@]}" "$APP/Contents/Resources/motionvpn"
codesign "${sign_args[@]}" "$APP"
codesign --verify --deep --strict "$APP"
printf 'Built: %s (%s)\n' "$APP" "$ARCHS"
