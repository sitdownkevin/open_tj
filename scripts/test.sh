#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$ROOT/backend" && go test -race ./... && go vet ./...)
TEST_DIR="$(mktemp -d -t opentj-tests)"
trap 'rm -rf "$TEST_DIR"' EXIT
swiftc -target "$(uname -m)-apple-macos14.0" -parse-as-library -Onone -framework SwiftUI -framework AppKit "$ROOT/apps/macos/OpenTJ.swift" -o "$TEST_DIR/OpenTJTests"
"$TEST_DIR/OpenTJTests" --self-test
