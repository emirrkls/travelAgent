# Closed-beta release checklist

Phokarta Android closed testing / Play Console preparation. This is not a production launch and does not provision live infrastructure.

Status language: `[x]` done in repo, `[ ]` still required. External items stay unchecked until an operator supplies them.

## Code

- [x] applicationId / namespace `com.emirrkls.phokarta`
- [x] Release HTTPS API URL injection (no HTTP fallback)
- [x] Placeholder `https://api.phokarta.invalid/` when no URL is supplied
- [x] Debug-only HTTP logging (`HttpLoggingInterceptor` BASIC)
- [x] Photo Picker (`PickVisualMedia`); no `READ_MEDIA_IMAGES`
- [x] No background location; map works without permission
- [x] Account deletion in-app (Profile → Settings → Account)
- [x] Block user + report user + report Visit
- [x] Encrypted session store excluded from backup/device transfer
- [x] Portrait-only `MainActivity`
- [x] No ads, IAP, Crashlytics, Firebase Analytics, or ad SDK
- [x] Demo catalog no longer overlays Profile identity (bio/avatar/taste)
- [ ] Live `PHOKARTA_API_BASE_URL` for the beta host
- [ ] Restricted production/release Maps Android key
- [ ] Adaptive launcher icon + Play 512×512 icon (current asset is a 48dp vector)
- [ ] Confirm targetSdk **36** if uploading a **new** app to Play on/after **31 August 2026** (see policy notes). Today the repo targets API **35**, which is compliant for new apps **until** that date.

## Build

- [x] `bundleRelease` path exists
- [x] R8/minify documented as off
- [x] Release compile / unit / lint gates
- [ ] Upload-signed AAB (blocked on keystore)
- [ ] SHA-256 of the final upload-signed AAB

## Signing

- [x] Provider-neutral `PHOKARTA_UPLOAD_*` configuration
- [x] Fail-fast on partial signing secrets
- [x] No keystore in Git
- [ ] Create and back up a local upload keystore (offline, operator)
- [ ] Enable Play App Signing in Play Console
- [ ] Record upload-cert SHA-1/SHA-256 for Maps restriction

## Backend

- [x] Staging Compose / prod profile / Flyway V11 documented
- [x] Swagger disabled outside `dev`
- [x] Account deletion + durable media cleanup
- [x] Block/report APIs
- [ ] Live HTTPS API
- [ ] PostGIS
- [ ] Private object storage
- [ ] DNS + TLS
- [ ] Production-like secrets
- [ ] Verified backup + restore drill on the **beta** host
- [ ] Dedicated beta test accounts (no passwords in repo)

Preferred environment: **one** beta backend (`staging` / `beta`). Do not create a second production stack until public launch. Upgrade path: same Compose artifacts, new DNS/secrets/bucket, `APP_ENVIRONMENT=production`.

## Store

- [x] App name: Phokarta
- [x] Draft short + full descriptions EN/TR
- [x] Category recommendation: Travel & Local
- [ ] Play Developer account
- [ ] Identity verification
- [ ] Support email
- [ ] Privacy policy public URL
- [ ] Account deletion web URL
- [ ] Feature graphic 1024×500
- [ ] Phone screenshots (minimum two; four 1080px recommended)
- [ ] Store icon 512×512 PNG
- [ ] Closed testing track + tester list

## Policy

- [x] Technical Data Safety inventory
- [x] App Content notes (ads/IAP/UGC/audience)
- [x] UGC: in-app report + block present
- [ ] Legal privacy policy (owner/counsel)
- [ ] Terms of use / Community Guidelines published and accepted at UGC creation (Play UGC policy)
- [ ] Data Safety form reconciled with current Play Console questionnaire
- [ ] Content rating questionnaire completed in Console
- [ ] Target audience declared in Console (not Kids)

## Assets

See [PLAY_STORE_ASSETS.md](PLAY_STORE_ASSETS.md).

## Testers

- [ ] Recruited opted-in closed testers
- [ ] Feedback path (support email or later form)
- [ ] Testers told beta data **may be reset** before public launch
- [ ] No real customer PII in seed data
- If the Play account is a **personal** account created after 13 November 2023: **12 testers opted in continuously for 14 days** before applying for **production** access. Closed beta itself can start with fewer testers; the 12/14 rule is a production-access prerequisite for those accounts, not a closed-track creation blocker. Organization accounts: confirm in Console; official Help Center scopes the 12/14 rule to those personal accounts.

## Rollout sequence (do not perform in this milestone)

1. Create app in Play Console  
2. Enable Play App Signing  
3. Upload signed AAB  
4. Complete App Content  
5. Data Safety  
6. Privacy policy URL  
7. Content rating  
8. Target audience  
9. Ads declaration  
10. App access / reviewer instructions  
11. Store listing  
12. Closed testing track  
13. Add testers  
14. Roll out beta  

## Post-release monitoring

- [ ] Backend errors / auth failures
- [ ] OPEN report backlog
- [ ] Account-deletion media cleanup backlog
- [ ] DB disk and backup verification
- [ ] Play Android Vitals (no third-party crash SDK in this beta)

## Decisions recorded

| Topic | Decision |
|---|---|
| Crash reporting | **A** — none. Use Play Android Vitals + tester reports. Add a vendor SDK only after privacy review. |
| Analytics | None. Do not add Firebase Analytics for beta. |
| Ads | No |
| IAP | None for current beta |
| R8 | Off |
| Environment | Single beta/staging backend first |
| Data reset | Testers must be told data may be reset; no silent wipe after beta begins |
| Password reset / email verification / Google Sign-In | Not implemented; do not advertise. Password reset is a **usability limitation** for password accounts; manage testers manually for closed beta. |
| Kids/Families | Not intended |
