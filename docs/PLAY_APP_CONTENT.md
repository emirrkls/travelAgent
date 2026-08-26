# Play App Content notes

Operator notes for Play Console **App content**. Not a submission. Do not overstate. Re-check each Console question at fill time.

## Ads

**No.** The app does not include an ads SDK or in-app ad placements.

## In-app purchases / subscriptions

**None** for the current closed beta. Do not describe a future business model as present.

## App access

Login is required for Visits, saved places, collections, social, block/report, and account deletion. Catalog/map browsing after login still works if location permission is denied.

Reviewer/tester instructions (fill in Console; do not put real passwords in Git):

1. Create an account with email + username + password, or use a dedicated reviewer account created on the live beta backend.
2. Backend must be reachable over HTTPS (`PHOKARTA_API_BASE_URL`).
3. Location permission is optional (map “my location” only).
4. Sample credentials: create after the beta API exists. Do not commit them.

Password reset and email verification are **not** implemented. If a reviewer is locked out, an operator must create a new test account.

## Content rating questionnaire — human confirmation needed

Do **not** guess the final rating. Likely relevant IARC/Play topics based on the product:

| Topic | Code/product fact | Confirm in questionnaire |
|---|---|---|
| User interaction | Accounts, follows, public/friends Visits, collections | Yes, users interact |
| UGC | Reviews, photos, private memory, profiles | Yes |
| Location sharing | Optional device location; place coordinates in catalog | Describe as optional / place data, not live tracking |
| Violence (game) | No game combat | No |
| Gambling | None | No |
| Sexual content | Possible in UGC photos/text; not a purpose of the app | UGC risk; moderation via report |
| Hate / harassment | Possible in UGC; report + block exist | UGC risk |
| Alcohol / tobacco | Place categories include bar/nightlife; user photos/reviews may mention | Catalog of real-world venues, not a drinking game |
| Age | Not child-directed | Owner chooses target age in Console |

## Target audience

Product intent: general travel / local discovery. **Not** designed for children. Do not enroll in Designed for Families / Kids unless the owner later decides that (they should not, given UGC). Final age bands remain a Console decision.

## Government / financial / health

Not a government app, not a financial service, not a health app, based on current product scope.

## UGC

Phokarta hosts user-generated Visits, reviews, photos, profiles, and collections.

In-app today:

- Report Visit
- Report user
- Block user (symmetric visibility barrier; not an auto-ban)
- Account deletion

Play UGC policy also expects terms/user policy **accepted before creating/uploading UGC**, plus ongoing moderation.

**Technical gate (in repo):** authenticated `GET /api/v1/me/policy-status` and `POST /api/v1/me/policy-acceptance` record a versioned acceptance (`2026-08-beta` by default). Create Visit, media upload-intent/confirm, collection create, and add-place-to-collection return `403 POLICY_ACCEPTANCE_REQUIRED` until that version is accepted. The Android client shows an unchecked checkbox + explicit Accept before those writes. Report, block, account deletion, login, and browsing are not gated. Register still has no Terms checkbox.

**Hosted legal (external):** final Terms of Use / User Policy and Community Guidelines pages are **not** published. In-app draft guidelines are labeled owner/legal review required and are not final legal text. Settings legal rows stay on “not published yet” until HTTPS URLs are supplied. See [COMMUNITY_GUIDELINES_REQUIREMENTS.md](COMMUNITY_GUIDELINES_REQUIREMENTS.md).

Reports do **not** auto-hide content or ban users. Operators inspect OPEN reports privately. No admin UI. Manual moderation can be acceptable for a small closed beta if an owner actually reviews the queue.

## Block / report

- Reports: `OPEN` on create; no target notification
- Duplicate OPEN report for same reporter+target returns the existing row
- Rate limit: 10 reports/user/hour; 60 block operations/user/hour
- Need a real support/escalation **owner** before inviting testers

## Account deletion

- In-app: Settings → Account → Delete account (password re-entry for password accounts)
- Server: `DELETE /api/v1/me` hard-deletes owned product data; media cleanup is durable/async
- Play User Data policy also requires a **web resource** where users can request deletion **without reinstalling the app**. That public URL is **MISSING** (no domain yet). See [ACCOUNT_DELETION.md](ACCOUNT_DELETION.md) and [ACCOUNT_DELETION_WEB.md](ACCOUNT_DELETION_WEB.md).

## Privacy policy

Required in Console and in-app (link or text). No live URL. Do not paste a fabricated policy into Console.

## Data safety

See [PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md). Reconcile with the questionnaire at submission.
