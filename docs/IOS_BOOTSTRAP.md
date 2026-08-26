# iOS Mac bootstrap

The Windows-authored iOS tree received its first Apple-tooling bootstrap on
2026-08-26.

## Verified toolchain and result

- macOS 26.2 (25C56)
- Xcode 26.4.1 (17E202)
- Apple Swift 6.3.1 compiler; project language mode Swift 5.10
- iOS/iOS Simulator SDK 26.4
- iPhone 17 Pro, iOS 26.4 Simulator
- Debug simulator build: PASS
- XCTest: 68 discovered, 68 executed, 68 passed, 0 failed, 0 skipped

## Prerequisites

- macOS with Xcode and an iOS 17+ SDK/runtime
- XcodeGen
- This repository
- Optional local Phokarta backend for runtime auth testing

XcodeGen 2.46.0 was built from the official upstream source in a temporary
directory for the initial bootstrap because this shared Mac had neither
XcodeGen nor Homebrew. Nothing was installed system-wide or vendored into the
repository.

## Generate the project

```sh
cd ios
xcodegen generate
open Phokarta.xcodeproj
```

`project.yml` is authoritative. For structural configuration changes, edit
`project.yml`, regenerate on a Mac, review the diff, and commit both the
definition and `Phokarta.xcodeproj`. Do not hand-edit generated configuration
that can be expressed in XcodeGen.

The generated project contains the `Phokarta` app, `PhokartaTests`, the shared
`Phokarta` scheme, Debug/Release xcconfig linkage, Info.plist, the String
Catalog, asset resources, and the app-to-test target dependency.

## Reproduce the verified build and tests

```sh
cd ios
xcodebuild -project Phokarta.xcodeproj -scheme Phokarta \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
xcodebuild test -project Phokarta.xcodeproj -scheme Phokarta \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO
```

The first compiler pass found one `@MainActor` isolation error in Explore
enrichment fallbacks. The fix snapshots fallback state before starting the
`async let` operations. The test target also required awaited store reads to be
evaluated before XCTest assertion autoclosures. No assertion was weakened.

## Simulator smoke scope

Verified: launch, Login, Register, Login/Register navigation, English and
Turkish resources, light appearance, dark appearance, and no immediate crash.

Not run: real backend auth, Keychain persistence across relaunch, Explore
runtime, Place Detail runtime, and physical-device QA.

## Optional local backend

Copy `Config/Local.xcconfig.example` to ignored `Config/Local.xcconfig` only
when an override is needed. Simulator and a backend on this Mac may use
`http:/$()/127.0.0.1:8080/`. Do not use Android's `10.0.2.2`, and never put
credentials in xcconfig.

## Signing and security

Automatic signing is enabled for `com.emirrkls.phokarta`. Simulator builds do
not need a team; device and Xcode Cloud work require selection of an authorized
team. Do not commit certificates, profiles, Apple credentials, `Local.xcconfig`,
`xcuserdata`, DerivedData, archives, or result bundles.

The production Keychain store uses
`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` with synchronization
disabled. Runtime persistence remains to be tested with a real auth flow.
