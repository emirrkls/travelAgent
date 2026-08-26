# Phokarta iOS

Native Swift/SwiftUI client for Phokarta. This tree is authored so it can be generated and compiled on macOS later. It is **not** an Android source translation.

Current milestone: **v0.1 — codebase bootstrap + native auth foundation**.

- iOS deployment target: **17.0**
- Bundle ID: `com.emirrkls.phokarta` (overridable via xcconfig)
- Project generation: **XcodeGen** (`project.yml`)
- `.xcodeproj` is **not** committed from Windows

## What v0.1 includes

- URLSession API client
- Keychain session store
- Auth register / login / refresh / logout / session restore
- Concurrency-safe single-flight refresh
- Login + Register SwiftUI screens
- Temporary signed-in shell
- English + Turkish strings
- XCTest sources (not executed on Windows)

## What v0.1 does not include

Explore, Place Detail, Maps, Saved, Collections, Visit publish, media upload, social, block/report, account deletion UI, policy acceptance UI, live infrastructure, App Store submission.

## Mac bootstrap

See [../docs/IOS_BOOTSTRAP.md](../docs/IOS_BOOTSTRAP.md). Short version:

```sh
cd ios
cp Config/Local.xcconfig.example Config/Local.xcconfig
# set PHOKARTA_API_BASE_URL for the Mac that will run Simulator
xcodegen generate
open Phokarta.xcodeproj
```

Then select a development team, run unit tests, and run the Simulator.

`iOS Xcode build: NOT RUN` from the Windows authoring environment.

## Configuration

| Build | API URL | HTTP |
|-------|---------|------|
| Debug | `http://127.0.0.1:8080/` unless `Local.xcconfig` overrides | allowed |
| Release | `https://api.phokarta.invalid/` placeholder | HTTPS required |

`127.0.0.1` is only valid when the backend runs on the **same Mac** as Simulator. It will not reach a Windows-hosted backend. Do not use Android’s `10.0.2.2`.

Copy `Config/Local.xcconfig.example` → `Config/Local.xcconfig` (gitignored). No secrets belong in xcconfig.

Release builds refuse to talk to the `.invalid` placeholder host. They also reject non-HTTPS URLs at config parse.

## Auth contract

See [docs/AUTH_CONTRACT.md](docs/AUTH_CONTRACT.md).

## Tests

XCTest sources live in `PhokartaTests/`. They require Xcode on macOS:

```sh
cd ios
xcodegen generate
xcodebuild test -scheme Phokarta -destination 'platform=iOS Simulator,name=iPhone 16'
```

## Xcode Cloud

See [../docs/XCODE_CLOUD.md](../docs/XCODE_CLOUD.md). Initial Cloud onboarding needs a generated Xcode project from a Mac.

## Mac QA still required

- Real Swift/iOS compilation
- Simulator login/register/logout
- Keychain persistence across process restart
- Dynamic Type / VoiceOver on auth forms
- Signing / development team
- Xcode Cloud workflow
