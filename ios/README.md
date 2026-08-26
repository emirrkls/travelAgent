# Phokarta iOS

Native Swift/SwiftUI client for Phokarta. This tree is authored so it can be generated and compiled on macOS later. It is **not** an Android source translation.

Current milestone: **v0.2 — Explore + Place Detail**.

- iOS deployment target: **17.0**
- Bundle ID: `com.emirrkls.phokarta` (overridable via xcconfig)
- Project generation: **XcodeGen** (`project.yml`)
- `.xcodeproj` is **not** committed from Windows

`iOS Xcode build: NOT RUN` from the Windows authoring environment.

## What v0.1 includes

- URLSession API client
- Keychain session store
- Auth register / login / refresh / logout / session restore
- Concurrency-safe single-flight refresh
- Login + Register SwiftUI screens
- English + Turkish strings
- XCTest sources (not executed on Windows)

## What v0.2 includes

- Authenticated TabView: Explore + Profile placeholder
- Explore list with remote search, category chips, pull-to-refresh, pagination
- Place cards with Community / Friends / Personal scores and read-only saved/visited
- Place Detail loaded by UUID, scores, dimension aggregates, recent reviews, friends preview
- `PlaceService` on the existing `APIClient` + `TokenRefreshCoordinator` path
- XCTest sources for DTO decoding, Explore concurrency, Place Detail, and auth reuse

See [docs/EXPLORE_PLACE_DETAIL_PARITY.md](docs/EXPLORE_PLACE_DETAIL_PARITY.md).

## What v0.2 does not include

Saved mutation, Collections mutation/UI, Visit publish, rating editor, media upload, map, location permission, offline persistence, social follow/block/report UI, account deletion UI, policy acceptance UI, live backend provisioning, App Store submission.

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

## Explore / Place Detail contract

See [docs/EXPLORE_PLACE_DETAIL_PARITY.md](docs/EXPLORE_PLACE_DETAIL_PARITY.md).

## Tests

XCTest sources live in `PhokartaTests/`. They require Xcode on macOS:

```sh
cd ios
xcodegen generate
xcodebuild test -scheme Phokarta -destination 'platform=iOS Simulator,name=iPhone 16'
```

Auth, Explore, and Place Detail tests are **authored, not executed** until Apple tooling is available.

## Xcode Cloud

See [../docs/XCODE_CLOUD.md](../docs/XCODE_CLOUD.md). Initial Cloud onboarding needs a generated Xcode project from a Mac.

## Mac QA still required

- Real Swift/iOS compilation
- Simulator login/register/logout
- Explore search / category / pagination
- Place Detail scores, reviews, unavailable
- Keychain persistence across process restart
- Dynamic Type / VoiceOver
- AsyncImage loading of catalog cover URLs
- Signing / development team
- Xcode Cloud workflow
