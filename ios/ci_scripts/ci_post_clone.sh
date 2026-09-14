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

fail() {
  echo "error: $1"
  exit 1
}

require_file() {
  [ -f "$1" ] || fail "required packaging file is missing: $1"
}

require_text() {
  grep -Fq "$2" "$1" || fail "$3"
}

reject_text() {
  if grep -Fq "$2" "$1"; then
    fail "$3"
  fi
}

PROJECT="$IOS/project.yml"
PBXPROJ="$IOS/Phokarta.xcodeproj/project.pbxproj"
RELEASE_CONFIG="$IOS/Config/Release.xcconfig"
INFO_PLIST="$IOS/Phokarta/Resources/Info.plist"
PRIVACY_MANIFEST="$IOS/Phokarta/Resources/PrivacyInfo.xcprivacy"
APP_ICON_CONTENTS="$IOS/Phokarta/Resources/Assets.xcassets/AppIcon.appiconset/Contents.json"
APP_ICON="$IOS/Phokarta/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"

require_file "$RELEASE_CONFIG"
require_file "$INFO_PLIST"
require_file "$PRIVACY_MANIFEST"
require_file "$APP_ICON_CONTENTS"
require_file "$APP_ICON"

require_text "$RELEASE_CONFIG" 'PHOKARTA_API_BASE_URL = https:/$()/api.phokarta.com/' \
  "Release API URL must be https://api.phokarta.com/"
reject_text "$RELEASE_CONFIG" 'api.phokarta.invalid' \
  "Release API URL must not use the .invalid placeholder"
reject_text "$RELEASE_CONFIG" '127.0.0.1' \
  "Release API URL must not use localhost"

require_text "$PROJECT" 'MARKETING_VERSION: "0.9.0"' \
  "project.yml marketing version must be 0.9.0"
require_text "$PROJECT" 'CURRENT_PROJECT_VERSION: "1"' \
  "project.yml build number must be 1"
[ "$(grep -Fc 'MARKETING_VERSION = 0.9.0;' "$PBXPROJ")" -eq 2 ] || \
  fail "committed Xcode project marketing versions must match project.yml"
[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 1;' "$PBXPROJ")" -eq 2 ] || \
  fail "committed Xcode project build numbers must match project.yml"

plutil -lint "$INFO_PLIST" >/dev/null || fail "Info.plist is invalid"
plutil -lint "$PRIVACY_MANIFEST" >/dev/null || fail "PrivacyInfo.xcprivacy is invalid"
[ "$(plutil -extract ITSAppUsesNonExemptEncryption raw -o - "$INFO_PLIST")" = "false" ] || \
  fail "export-compliance exemption must be declared"
[ "$(plutil -extract NSPrivacyTracking raw -o - "$PRIVACY_MANIFEST")" = "false" ] || \
  fail "privacy manifest tracking declaration must be false"
[ "$(grep -Fc '<key>NSPrivacyCollectedDataType</key>' "$PRIVACY_MANIFEST")" -eq 6 ] || \
  fail "privacy manifest must declare the six audited data categories"
require_text "$PRIVACY_MANIFEST" '<string>C617.1</string>' \
  "privacy manifest must declare the audited file-timestamp reason"
require_text "$PBXPROJ" 'PrivacyInfo.xcprivacy in Resources' \
  "privacy manifest must be included in the app resources phase"

plutil -lint "$APP_ICON_CONTENTS" >/dev/null || fail "AppIcon Contents.json is invalid"
require_text "$APP_ICON_CONTENTS" '"filename" : "AppIcon-1024.png"' \
  "App Store icon catalog must reference AppIcon-1024.png"
ICON_PROPERTIES="$(sips -g pixelWidth -g pixelHeight -g hasAlpha "$APP_ICON" 2>/dev/null)" || \
  fail "App Store icon could not be inspected"
printf '%s\n' "$ICON_PROPERTIES" | grep -Fq 'pixelWidth: 1024' || \
  fail "App Store icon width must be 1024"
printf '%s\n' "$ICON_PROPERTIES" | grep -Fq 'pixelHeight: 1024' || \
  fail "App Store icon height must be 1024"
printf '%s\n' "$ICON_PROPERTIES" | grep -Fq 'hasAlpha: no' || \
  fail "App Store icon must not contain an alpha channel"

echo "Using committed ios/Phokarta.xcodeproj (XcodeGen is not required in CI)"
echo "iOS release packaging contract: PASS"
