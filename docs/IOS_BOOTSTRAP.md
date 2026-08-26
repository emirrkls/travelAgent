# iOS Mac bootstrap

The iOS tree was authored on Windows. **Xcode was not run.** Do not treat source inspection as a compile.

## Prerequisites

- macOS with Xcode and the iOS 17+ SDK
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen` is typical on a Mac)
- This git repository
- Optional: Phokarta backend running on the same Mac if you want Simulator login

## Flow

1. Clone the repository.
2. Install Xcode and the iOS SDK. Open Xcode once to accept licenses.
3. Install XcodeGen.
4. `cd ios`
5. `cp Config/Local.xcconfig.example Config/Local.xcconfig`
6. Set `PHOKARTA_API_BASE_URL` in `Local.xcconfig`:
   - Simulator + backend on this Mac: `http:/$()/127.0.0.1:8080/`
   - Physical device: the Mac/LAN IP, still using the `http:/$()` xcconfig slash trick
   - Windows `localhost` / Android `10.0.2.2` will not work here
7. `xcodegen generate`
8. `open Phokarta.xcodeproj`
9. Select a development team on the Phokarta target (Signing & Capabilities).
10. Change the bundle identifier only if Apple requires a distinct iOS App ID. Default is `com.emirrkls.phokarta` via `PHOKARTA_BUNDLE_IDENTIFIER`.
11. Product → Test (scheme `Phokarta`) to run `PhokartaTests`.
12. Product → Run on an iOS 17+ Simulator.

## First Xcode Cloud onboarding

Xcode Cloud needs an Xcode project/workspace to attach a workflow. After the first successful `xcodegen generate` on a Mac, **commit `Phokarta.xcodeproj`** (shared scheme included, no `xcuserdata`):

```sh
cd ios
xcodegen generate
git add -f Phokarta.xcodeproj
# verify no certificates, profiles, or xcuserdata
git commit -m "chore: add generated ios xcodeproj for cloud onboarding"
```

See [XCODE_CLOUD.md](XCODE_CLOUD.md) for the tradeoff.

## Keychain accessibility

Production store uses `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and `kSecAttrSynchronizable=false`. Tokens are not written to iCloud Keychain. Validate process-restart restore on a real device/simulator.

## Accessibility QA (cannot be done from Windows)

- VoiceOver labels on email/password/submit
- Dynamic Type: no clipped auth text
- Keyboard: email, password content types, submit actions
- Light and dark appearance using the Phokarta sand/coral/ink palette

## ATS

Info.plist enables `NSAllowsLocalNetworking` only (not `NSAllowsArbitraryLoads`). Release still requires HTTPS in `AppConfig`. Internet HTTP is not allowed by this exception.
