# Xcode Cloud bootstrap

The repository-side Cloud bootstrap uses the committed generated project. This
keeps Apple compilation available to Windows development without making every
cloud build install XcodeGen or Homebrew.

## Project strategy

- `ios/project.yml` is the authoritative structural definition.
- `ios/Phokarta.xcodeproj` and its shared `Phokarta` scheme are committed.
- When structure changes: edit `project.yml`, run `xcodegen generate` on a Mac,
  and commit both diffs.
- Do not commit `xcuserdata`, local xcconfig, certificates, provisioning
  profiles, DerivedData, archives, or result bundles.

## Post-clone behavior

Apple finds `ios/ci_scripts/ci_post_clone.sh` next to the project. The hook
uses `CI_PRIMARY_REPOSITORY_PATH`, verifies that `project.yml` and the committed
project exist, and exits without installing or running XcodeGen.

Cloud requires XcodeGen: **NO**.

## Intended workflow

- Name: `iOS CI - Master`
- Repository: `emirrkls/travelAgent`
- Trigger: push to `master`
- Actions: app Build and `PhokartaTests` Test
- Environment: modern Xcode/iOS Simulator supporting iOS 17+
- Distribution: none
- TestFlight/App Store deployment: disabled

## Apple-side status

Apple-side onboarding was completed with the repository owner's authorized
Emircan Keleş team on 2026-08-31:

- Automatic signing is configured for `com.emirrkls.phokarta`.
- The explicit App ID and App Store Connect record named `Phokarta` exist.
- The GitHub Xcode Cloud app is scoped to include `emirrkls/travelAgent`.
- Workflow `iOS CI - Master` is active with push-to-`master` and manual-start
  conditions, Build (iOS), and required Test (iOS) actions.
- The workflow uses Latest Release (currently Xcode 26.6 / macOS 26.6.2),
  Recommended iPhones, and the latest iOS runtime from the selected Xcode.
- There are no TestFlight deployments or other post-actions.

A real push-triggered run completed successfully for commit `82bc7a7` using
Xcode 26.6 (17F113) on macOS Tahoe 26.6.2. Both Build - iOS and Test - iOS
succeeded, with 68 of 68 tests passed. Later pushes to `master` use the same
required build-and-test gate.

## Windows continuation

After the first green cloud run, normal development is:

Windows/Cursor → edit Swift → commit → push `master` → Xcode Cloud build/test
feedback.

The Mac is then needed only for XcodeGen project regeneration, simulator/device
QA, signing, and later distribution work—not ordinary Swift source edits.
