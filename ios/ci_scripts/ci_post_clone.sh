#!/bin/sh
# Xcode Cloud post-clone hook (ios/ci_scripts/ci_post_clone.sh).
# Working directory when Cloud runs this file is ci_scripts/.
set -e

ROOT="${CI_PRIMARY_REPOSITORY_PATH:-$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)}"
IOS="$ROOT/ios"

echo "Phokarta iOS post-clone"
echo "ROOT=$ROOT"

if [ ! -f "$IOS/project.yml" ]; then
  echo "error: ios/project.yml not found"
  exit 1
fi

cd "$IOS"

generate() {
  xcodegen generate --spec "$IOS/project.yml"
}

if command -v xcodegen >/dev/null 2>&1; then
  echo "Regenerating Phokarta.xcodeproj with xcodegen"
  generate
  exit 0
fi

if [ -d "$IOS/Phokarta.xcodeproj" ]; then
  echo "xcodegen not installed; using committed Phokarta.xcodeproj"
  exit 0
fi

if command -v brew >/dev/null 2>&1; then
  echo "xcodegen missing and no committed project; trying Homebrew (not guaranteed)"
  brew install xcodegen
  generate
  exit 0
fi

echo "error: no Phokarta.xcodeproj and xcodegen is unavailable."
echo "Generate and commit the Xcode project from a Mac first. See docs/XCODE_CLOUD.md"
exit 1
