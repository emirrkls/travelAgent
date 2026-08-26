# Xcode Cloud bootstrap

Xcode Cloud cannot be created from Windows. This file is the intended Mac-side plan. It is not a live workflow.

## Why `project.yml` is not enough to click “Enable Xcode Cloud”

Apple attaches Cloud workflows to an Xcode project or workspace. A repo that only contains `ios/project.yml` has nothing for Xcode’s Cloud UI to select until a Mac generates `Phokarta.xcodeproj`.

## Chosen strategy (least fragile)

**A. Generate and commit the Xcode project once from a Mac, then keep it.**

1. On a Mac: `cd ios && xcodegen generate`
2. Commit `ios/Phokarta.xcodeproj` **without** `xcuserdata`, certificates, or profiles.
3. In Xcode: Product → Xcode Cloud → Create Workflow, pointed at this repository.
4. Create the App Store Connect app record when you are ready (not done in v0.1).
5. Workflow should run unit tests and an app build on relevant branch pushes (`master` / later release branches).

After that, `project.yml` remains the source of truth for structural edits. When `project.yml` changes, regenerate on a Mac and commit the project diff.

**B. Regenerate in CI (optional later).**

`ios/ci_scripts/ci_post_clone.sh` will:

- regenerate if `xcodegen` is already on the runner
- otherwise keep a committed `.xcodeproj`
- only attempt `brew install xcodegen` if there is no project and Homebrew exists

Homebrew is commonly present on Xcode Cloud Macs but **not an Apple guarantee**. Do not vendor an XcodeGen binary. Do not fail a Cloud build that already has a committed project just because brew/xcodegen is missing.

## Script location

Apple looks for `ci_scripts/ci_post_clone.sh` next to the Xcode project/workspace. That is `ios/ci_scripts/ci_post_clone.sh` for `ios/Phokarta.xcodeproj`.

Xcode Cloud runs the script with `ci_scripts` as the working directory. The script uses `CI_PRIMARY_REPOSITORY_PATH` when set.

## Workflow sketch (created later from Xcode)

- Start condition: push to `master` (and later release tags/branches)
- Actions: Test (PhokartaTests) + Archive/Build
- Environment: latest stable Xcode that supports iOS 17
- Pre-build: the post-clone script above
- Do not upload to App Store until a later release milestone

## Still required on a Mac (not done)

- App Store Connect app record
- Enable Xcode Cloud from Xcode
- Signing / bundle ID confirmation
- First green Cloud build
