# Phokarta iOS

Native Swift/SwiftUI client for Phokarta. Current milestone: **v0.2 — Explore +
Place Detail**.

- Deployment target: iOS 17.0
- Bundle ID: `com.emirrkls.phokarta` (xcconfig-overridable)
- Project definition: XcodeGen `project.yml`
- Generated project: committed `Phokarta.xcodeproj` for Xcode Cloud
- Signing: automatic; development team not yet selected

## Apple tooling status

Bootstrapped on 2026-08-26 with macOS 26.2, Xcode 26.4.1, Apple Swift
6.3.1 (Swift 5.10 language mode), and the iOS 26.4 SDK/runtime.

- Debug build on iPhone 17 Pro / iOS 26.4 Simulator: **PASS**
- XCTest: **68 discovered, 68 executed, 68 passed, 0 failed, 0 skipped**
- Simulator smoke: Login/Register navigation, EN/TR, light/dark, no immediate crash
- Real backend auth, Keychain relaunch, Explore runtime, Place Detail runtime: **NOT RUN**

## Included scope

v0.1 provides URLSession networking, Keychain session storage, auth flows,
single-flight token refresh, EN/TR auth UI, and XCTest coverage. v0.2 adds the
authenticated shell, Explore/search/category/pagination, Place Detail, scores,
review aggregates, friends previews, and read-only saved/visited state.

Saved/Collections mutation, Visit publishing, ratings, media, maps, offline
persistence, live infrastructure, and store submission are outside this
milestone.

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
[Explore/Place Detail parity](docs/EXPLORE_PLACE_DETAIL_PARITY.md).

## Remaining Mac/Apple QA

- Authorized development-team selection and Xcode Cloud onboarding
- Real backend auth and Keychain persistence across relaunch
- Explore and Place Detail runtime with a real backend
- Physical-device, Dynamic Type, VoiceOver, and remote-image QA
- Later distribution, TestFlight, and App Store work
