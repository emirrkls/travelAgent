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

Automatic signing is configured for `com.emirrkls.phokarta`, but no development
team is selected. Workflow creation, any required App Store Connect app record,
and the first cloud run remain pending until the repository owner's authorized
Apple team is confirmed. Do not invent an SKU, legal entity, app ownership, or
distribution setting.

When a workflow is created, record its actual Xcode version, simulator/runtime,
commit SHA, build result, test result, and exact test count here. A pending run
must remain documented as pending; only a completed successful run is green.

## Windows continuation

After the first green cloud run, normal development is:

Windows/Cursor → edit Swift → commit → push `master` → Xcode Cloud build/test
feedback.

The Mac is then needed only for XcodeGen project regeneration, simulator/device
QA, signing, and later distribution work—not ordinary Swift source edits.
