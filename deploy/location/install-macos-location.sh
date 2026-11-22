#!/bin/bash
set -euo pipefail
LOCATION_PROJECT_ROOT="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
LOCATION_RUNTIME="${1:-$HOME/Library/Application Support/Closure/Location}"
if [[ "$(uname -s)" != Darwin ]]; then
  echo "The system location helper requires macOS." >&2
  exit 1
fi
if [[ "$LOCATION_RUNTIME" == --help ]]; then
  echo "Usage: bash deploy/location/install-macos-location.sh [runtime-directory]"
  exit 0
fi
xcrun --find swiftc >/dev/null
mkdir -p "$LOCATION_RUNTIME"
LOCATION_STAGE="$(mktemp -d "$LOCATION_RUNTIME/.build-XXXXXX")"
trap 'rm -rf "$LOCATION_STAGE"' EXIT
LOCATION_APP="$LOCATION_STAGE/ClosureLocation.app"
mkdir -p "$LOCATION_APP/Contents/MacOS"
cp -X "$LOCATION_PROJECT_ROOT/deploy/location/Info.plist" "$LOCATION_APP/Contents/Info.plist"
# Do not inherit a newer SDK/environment's future macOS deployment target.
xcrun swiftc -swift-version 5 -O -target "$(uname -m)-apple-macosx13.0" \
  -module-cache-path "$LOCATION_RUNTIME/module-cache" \
  "$LOCATION_PROJECT_ROOT/deploy/location/ClosureLocation.swift" \
  -framework AppKit -framework CoreLocation \
  -o "$LOCATION_APP/Contents/MacOS/closure-location"
# Remove Finder resource metadata only from this freshly built staging bundle.
# Keep security/quarantine attributes intact.
xattr -dr com.apple.FinderInfo "$LOCATION_APP" 2>/dev/null || true
xattr -dr com.apple.ResourceFork "$LOCATION_APP" 2>/dev/null || true
codesign --force --sign - --identifier app.closure.weather-location "$LOCATION_APP"
codesign --verify --strict "$LOCATION_APP"
if [[ -e "$LOCATION_RUNTIME/ClosureLocation.app" ]]; then
  LOCATION_BACKUP="$(mktemp -d "$LOCATION_RUNTIME/previous-XXXXXX")"
  mv "$LOCATION_RUNTIME/ClosureLocation.app" "$LOCATION_BACKUP/ClosureLocation.app"
fi
mv "$LOCATION_APP" "$LOCATION_RUNTIME/ClosureLocation.app"
# Validate the final installed path as well as the staging bundle.
xattr -dr com.apple.FinderInfo "$LOCATION_RUNTIME/ClosureLocation.app" 2>/dev/null || true
xattr -dr com.apple.ResourceFork "$LOCATION_RUNTIME/ClosureLocation.app" 2>/dev/null || true
codesign --verify --strict "$LOCATION_RUNTIME/ClosureLocation.app"
echo "Installed macOS weather location helper."
