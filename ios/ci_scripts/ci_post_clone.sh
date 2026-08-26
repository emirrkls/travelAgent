#!/bin/sh
# Xcode Cloud post-clone hook. The committed project is intentionally used
# directly; project.yml remains authoritative for regeneration on a Mac.
set -e

ROOT="${CI_PRIMARY_REPOSITORY_PATH:-$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)}"
IOS="$ROOT/ios"

echo "Phokarta iOS post-clone"
echo "ROOT=$ROOT"

if [ ! -f "$IOS/project.yml" ]; then
  echo "error: ios/project.yml not found"
  exit 1
fi

if [ ! -f "$IOS/Phokarta.xcodeproj/project.pbxproj" ]; then
  echo "error: committed ios/Phokarta.xcodeproj is missing"
  echo "Regenerate it from ios/project.yml on a Mac and commit the result."
  exit 1
fi

echo "Using committed ios/Phokarta.xcodeproj (XcodeGen is not required in CI)"
