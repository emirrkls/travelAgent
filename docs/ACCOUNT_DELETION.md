# Account deletion

Self-service account deletion permanently removes a Phokarta account and all user-owned product data. It is a hard delete: there is no deactivated or anonymized leftover profile. Object-storage bytes are removed asynchronously after the account is already inaccessible.

This document describes product and operational behavior. It is not a legal policy.

## Endpoint

`DELETE /api/v1/me`

- Authenticated principal only. The caller cannot supply a `userId`.
- Password accounts must send `{ "currentPassword": "..." }`. The password is never logged, echoed, or stored.
- External/Google identities are not implemented yet. If a future account has no password hash, the authenticated session plus Android confirmation is sufficient.
- Success: `204 No Content`. The account is inaccessible immediately.
- Wrong password: `400 INVALID_CURRENT_PASSWORD`.
- Missing/unauthenticated: `401`.
- Rate limit: `429 RATE_LIMITED` using the existing in-memory limiter, action `account_delete`, keyed by user id.

A second delete with the old access token returns `401` because the user row no longer exists. That is the idempotent/lost-ack recovery path: the client treats it as success and purges local data.

## What is deleted

| Category | Classification | Behavior |
|---|---|---|
| `users` identity (email, username, display name, bio, avatar, password hash, enabled) | MUST DELETE | Hard-deleted |
| `auth_identities` (LOCAL/GOOGLE/APPLE subjects) | MUST DELETE | Cascade from user |
| `refresh_sessions` (hashes, families) | MUST DELETE | Cascade from user; refresh fails afterward |
| Visits (PUBLIC, FRIENDS, PRIVATE), `public_review`, `private_memory`, dimension scores | MUST DELETE | Hard-deleted; Community/Friends/Activity update dynamically |
| `saved_places` owned by the user | MUST DELETE | Hard-deleted; Places remain |
| Collections owned by the user and `collection_places` | MUST DELETE | Hard-deleted; not collaborative |
| `user_follows` in both directions | MUST DELETE | Mutual friends disappear |
| `user_blocks` involving the user (either direction) | MUST DELETE | Cascade from `users`; no leftover block graph |
| `reports.reporter_user_id` / `reports.target_user_id` / `reports.target_visit_id` | SAFETY RETAIN | Report **row remains**. FKs are `ON DELETE SET NULL`. Reason, details, timestamps, `target_type`, and status are kept as abuse/safety records. Direct reporter/target identity is removed. This is a deliberate exception: not all user-entered text is deleted. |
| `media_assets` metadata and `visit_media` | MUST DELETE | Rows removed in the same transaction after keys are copied |
| Places, community catalog, other users' rows | SHARED / OTHER-USER-OWNED | Retained |
| Android drafts, pending mutations, local photos, session, WorkManager | MUST DELETE | Purged after server success or terminal auth loss |
| Language/theme | Device preference | Retained |

Aggregates (community score/count, friend score, friends-who-visited, activity) are computed from live Visit/follow rows. Deleting the contributor removes the contribution. No persisted community cache is kept.

## What is retained

- Global Place rows.
- Other users' accounts, Visits, Saved Places, and collections.
- Durable `account_deletion_media_jobs` rows until **final** object cleanup succeeds: `id`, `deletion_id` (random batch UUID), `storage_key`, timestamps, attempt count, and `last_error_category`. No email, username, review, or privateMemory.
- Abuse/safety `reports` rows: after the user is deleted, reporter and target FKs are null. `reason`, optional `details`, `target_type`, `status`, and timestamps remain. Details are not copied into logs. There is no automated expiration yet.
- Already issued object-storage signed **GET** URLs until their short TTL expires (bearer capability; default read TTL 10 minutes). New signed read URLs are not issued after deletion.
- Application metrics with low-cardinality outcome tags only.

Same email may register again. Registration creates a new user UUID. Old Visits, Saved, social graph, and media do not reattach.

Google/Apple account deletion, if those providers are added later, deletes only the Phokarta account and stored provider subject. It does not delete the user's Google/Apple account.

## Transaction boundary

One database transaction, with no object-storage network I/O inside it:

1. Advisory-lock the account (shared with Visit create and media upload intent).
2. Load the user; verify current password when a password hash exists.
3. Copy owned `media_assets.storage_key` values into `account_deletion_media_jobs`.
4. Delete `visit_media` for the user's visits/media (required because `visit_media.media_id` is `ON DELETE RESTRICT`).
5. `DELETE FROM users` — PostgreSQL cascades visits, scores, saved places, collections, follows, blocks, auth identities, refresh sessions, and media metadata. Report FKs that pointed at the user or their visits become NULL; the report rows remain.
6. Commit.
7. After commit, trigger a cleanup pass. Failures stay on the job table for retry.

If the transaction fails, the account remains intact. Partial product deletion does not commit.

Visit create, media upload intent, and media confirm take the same account lock, so a concurrent mutation either finishes before deletion (and is then cascaded) or waits and then fails because the user is gone. No new owned row can commit after the deletion boundary. After-commit object cleanup uses `REQUIRES_NEW` so it does not join the just-finished deletion transaction.

## Auth invalidation

`JwtAuthenticationFilter` already requires `users.existsById(sub)`. After deletion:

- Old access JWTs remain cryptographically valid until expiry but authenticated API calls fail with `401` because the user row is gone (one existence check per authenticated request).
- Old refresh tokens fail (`INVALID_REFRESH_TOKEN`); session rows are gone.
- Password login with the deleted email fails with the same `INVALID_CREDENTIALS` as an unknown account.
- Re-registration of that email creates a new UUID.

## Media cleanup

A scheduled worker (same interval as orphan media cleanup, default 1 hour) claims due jobs with `FOR UPDATE SKIP LOCKED` and deletes the object. S3 `DeleteObject` is idempotent: a missing object is success. Temporary storage failures keep the job and retry later. The account is never restored.

Jobs are part of PostgreSQL backup. A process/repository restart resumes pending deletes from the job table; there is no in-memory-only cleanup state.

### Lifecycle

1. Account deletion copies storage keys and commits. The user is already inaccessible. No S3 I/O runs inside that transaction.
2. **Initial delete:** the worker deletes the object and retains the job. `last_error_category` is set to `awaiting_final` (V10 has no phase column; this sentinel is not an error category). `next_attempt_at` is set to the final-verify instant. `attempt_count` is only a lease/retry counter.
3. **Capability wait:** the job is not due again until that instant.
4. **Final delete:** the worker deletes the same key again (absent = success) and only then removes the job.

Physical bytes are usually gone after the initial delete (typically immediately after commit via the after-commit trigger, otherwise on the next due cleanup pass). The job itself remains until the final pass so a late upload cannot become a permanent untracked object.

### Timing

Final cleanup is due at:

`job.created_at + phokarta.media.upload-ttl + phokarta.media.deletion-verify-grace`

- `created_at` is account-deletion time (job insert).
- `upload-ttl` is the configured presigned **PUT** lifetime (default 15 minutes, `PHOKARTA_MEDIA_UPLOAD_TTL`). Do not hardcode a second unrelated 15-minute constant.
- `deletion-verify-grace` is a small safety margin for clock skew (default 2 minutes, `PHOKARTA_MEDIA_DELETION_VERIFY_GRACE`).

That window covers a PUT issued immediately before deletion. If the first successful delete already happens after this instant (cleanup delayed), the worker treats that delete as final and removes the job.

### Signed GET vs signed PUT

These are different capabilities:

| Capability | After account deletion |
|---|---|
| New signed **GET** | Not issued. `/api/v1/media/{mediaId}/access` returns 404 (metadata is gone). |
| Already issued signed **GET** | May remain valid until the short **read** TTL (default 10 minutes). Phokarta does not revoke outstanding read URLs. |
| Already issued signed **PUT** | May remain usable until the **upload** TTL. Durable cleanup stays active beyond that window and deletes the same key again, so a late PUT cannot leave a permanent untracked object. |

A late PUT can recreate persistent bytes; a late GET cannot. That is why PUT is closed with delayed final delete and GET is left to TTL expiry.

### Bounded cleanup

There is no permanent tombstone. After final delete the job row is removed. Retryable storage failures keep a single job per storage key until success. Backlog remaining for `upload-ttl + deletion-verify-grace` after a deletion is expected, not a stall.

## Android

Path: Profile → Settings → Account → Delete account.

Confirmation dialog explains immediate inaccessibility and delayed physical media removal. Password users enter the current password (ephemeral ViewModel state only). Offline deletion is not queued; a connection error asks the user to connect.

On confirmed success (or `401`/`404` meaning the account is already gone):

1. Cancel unique mutation WorkManager work.
2. Delete that user's Room rows (visits, drafts, saved, collections, pending mutations).
3. Delete `filesDir/visit-media/<owner>/`.
4. Clear Coil memory and disk caches (full app image cache; per-user eviction is not practical).
5. Clear auth tokens/session and return to the auth screen.

Language preference is kept. Other users' Room rows are scoped by `userId`/`ownerUserId` and are not deleted.

If the client never sees `204` but the server committed, the next refresh or `/me` call fails. Refresh `401`/`403` and restore-session terminal auth failure purge that user's local data so lost-ack recovery does not leave drafts.

Play also requires a **public web** deletion request path for apps that create accounts. That URL is not hosted yet. Template: [ACCOUNT_DELETION_WEB.md](ACCOUNT_DELETION_WEB.md).

## Operational verification

- `phokarta.account.deletion` outcome: `success`, `invalid_password`, `already_gone`, `failure`
- `phokarta.account.media_cleanup` outcome: `deleted` (job removed after final verify), `awaiting_final` (initial object delete succeeded, job retained), `failed`
- `phokarta.account.media_cleanup.backlog` gauge: pending job count (includes jobs waiting for the upload-capability window)

Warn if backlog grows across several cleanup intervals after the capability window. Do not fail readiness because a cleanup job is retrying.

Logs may include request id, `deletionId`, and phase/outcome. Do not log email, password, JWT, storage keys, signed URLs, or review/privateMemory.

## Flyway

Latest schema migration: **V12** (`user_policy_acceptances`). V1–V11 are unchanged. Production applies schema locations only. Fresh V1–V12 and V11→V12 upgrades are both supported. Report rows survive account deletion via `ON DELETE SET NULL` on reporter/target/visit FKs; block rows and policy acceptances cascade away. Policy acceptance is not retained as report evidence.
