# Phokarta iOS

Native Swift/SwiftUI client for Phokarta. Current milestone: **v0.4 — Visit
Publish + Ratings**.

- Deployment target: iOS 17.0
- Bundle ID: `com.emirrkls.phokarta` (xcconfig-overridable)
- Project definition: XcodeGen `project.yml`
- Generated project: committed `Phokarta.xcodeproj` for Xcode Cloud
- Signing: automatic; authorized development team configured

## Apple tooling status

Bootstrapped on 2026-08-26 with macOS 26.2, Xcode 26.4.1, Apple Swift
6.3.1 (Swift 5.10 language mode), and the iOS 26.4 SDK/runtime.

- Debug build on iPhone 17 Pro / iOS 26.4 Simulator: **PASS**
- XCTest: **68 discovered, 68 executed, 68 passed, 0 failed, 0 skipped**
- Simulator smoke: Login/Register navigation, EN/TR, light/dark, no immediate crash
- Real backend auth, Keychain relaunch, Explore runtime, Place Detail runtime: **NOT RUN**

The Apple/Xcode Cloud bootstrap is complete. The shared `Phokarta` scheme uses
the committed project directly. Xcode Cloud build 12 for v0.4 (`115d179`)
passed Build - iOS and Test - iOS on Xcode 26.6 (17F113) / macOS Tahoe 26.6.2
(25G83): 111/111 XCTest tests passed, with 0 failures, 0 skipped, 0 warnings,
0 analysis issues, and 0 build errors shown.

## Included scope

v0.1 provides URLSession networking, Keychain session storage, auth flows,
single-flight token refresh, EN/TR auth UI, and XCTest coverage. v0.2 adds the
authenticated shell, Explore/search/category/pagination, Place Detail, scores,
review aggregates, friends previews, and read-only saved/visited state.

v0.3 adds online-first Save/Unsave, a live Saved tab, Collections list/create/
detail, Place Detail collection picker with create-and-add, canonical add,
targeted-reload remove, revision-safe refresh reconciliation, and account-scoped
in-memory state. It reuses the existing API client and single-flight refresh.

v0.4 adds online-first Visit authoring from Place Detail: overall and optional
category dimensions, public review, owner-only private memory, visit date,
visibility, idempotent publish/replay, canonical owner history, live visited and
latest personal-score state, account isolation, EN/TR copy, and XCTest coverage.

Visit media, maps/location, durable draft/offline persistence, a durable
mutation queue, full policy acceptance UI, live infrastructure changes, and
store submission remain outside this milestone.

## Generate, build, and test

Install XcodeGen through an approved Mac toolchain, then:

```sh
cd ios
xcodegen generate
xcodebuild -project Phokarta.xcodeproj -scheme Phokarta \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
xcodebuild test -project Phokarta.xcodeproj -scheme Phokarta \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO
```

`Config/Local.xcconfig` is optional and gitignored. Use it only to override
`PHOKARTA_API_BASE_URL`, for example when the backend runs on the same Mac as
Simulator. Never put credentials in xcconfig.

## Project regeneration rule

`project.yml` is authoritative. For structural project changes:

1. Edit `project.yml`.
2. Run `xcodegen generate` from `ios/` on a Mac.
3. Commit both `project.yml` and the generated `Phokarta.xcodeproj` diff.

Do not hand-maintain generated project configuration that belongs in XcodeGen.
The committed shared `Phokarta` scheme builds the app and runs
`PhokartaTests`.

## Configuration and contracts

| Build | API URL | HTTP |
|---|---|---|
| Debug | `http://127.0.0.1:8080/` unless locally overridden | local networking allowed |
| Release | `https://api.phokarta.invalid/` placeholder | HTTPS required |

See [Mac bootstrap](../docs/IOS_BOOTSTRAP.md),
[Xcode Cloud](../docs/XCODE_CLOUD.md),
[auth contract](docs/AUTH_CONTRACT.md), and
[Explore/Place Detail parity](docs/EXPLORE_PLACE_DETAIL_PARITY.md), and
[Saved/Collections parity](docs/SAVED_COLLECTIONS_PARITY.md), and
[Visit Publish/Ratings parity](docs/VISIT_PUBLISH_RATINGS_PARITY.md).

## Remaining Mac/Apple QA

- Real backend auth and Keychain persistence across relaunch
- Explore and Place Detail runtime with a real backend
- Physical-device, Dynamic Type, VoiceOver, and remote-image QA
- Later distribution, TestFlight, and App Store work
