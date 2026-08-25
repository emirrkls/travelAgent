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
| `media_assets` metadata and `visit_media` | MUST DELETE | Rows removed in the same transaction after keys are copied |
| Places, community catalog, other users' rows | SHARED / OTHER-USER-OWNED | Retained |
| Android drafts, pending mutations, local photos, session, WorkManager | MUST DELETE | Purged after server success or terminal auth loss |
| Language/theme | Device preference | Retained |

Aggregates (community score/count, friend score, friends-who-visited, activity) are computed from live Visit/follow rows. Deleting the contributor removes the contribution. No persisted community cache is kept.

## What is retained

- Global Place rows.
- Other users' accounts, Visits, Saved Places, and collections.
- Durable `account_deletion_media_jobs` rows until object delete succeeds: `id`, `deletion_id` (random batch UUID), `storage_key`, timestamps, attempt count, optional error category. No email, username, review, or privateMemory.
- Already issued object-storage signed GET URLs until their short TTL expires (bearer capability; default read TTL 10 minutes). New signed URLs are not issued after deletion.
- Application metrics with low-cardinality outcome tags only.

Same email may register again. Registration creates a new user UUID. Old Visits, Saved, social graph, and media do not reattach.

Google/Apple account deletion, if those providers are added later, deletes only the Phokarta account and stored provider subject. It does not delete the user's Google/Apple account.

## Transaction boundary

One database transaction, with no object-storage network I/O inside it:

1. Advisory-lock the account (shared with Visit create and media upload intent).
2. Load the user; verify current password when a password hash exists.
3. Copy owned `media_assets.storage_key` values into `account_deletion_media_jobs`.
4. Delete `visit_media` for the user's visits/media (required because `visit_media.media_id` is `ON DELETE RESTRICT`).
5. `DELETE FROM users` — PostgreSQL cascades visits, scores, saved places, collections, follows, auth identities, refresh sessions, and media metadata.
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

A scheduled worker (same interval as orphan media cleanup, default 1 hour) claims due jobs with `FOR UPDATE SKIP LOCKED`, deletes the object, and removes the job. S3 `DeleteObject` is idempotent: a missing object is success. Temporary storage failures keep the job and retry later. The account is never restored.

Jobs are part of PostgreSQL backup. A restart resumes pending deletes.

A previously issued signed read URL may work until TTL expiry. Authorization endpoints stop issuing new URLs immediately because media metadata and the user are gone. The same bearer-capability caveat applies to a previously issued upload PUT URL: if the object is written before cleanup runs, the durable job still deletes that key. A PUT that arrives after the job has already completed can leave an untracked object until bucket lifecycle or operational remediation; upload TTLs are short.

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

## Operational verification

- `phokarta.account.deletion` outcome: `success`, `invalid_password`, `already_gone`, `failure`
- `phokarta.account.media_cleanup` outcome: `deleted`, `failed`
- `phokarta.account.media_cleanup.backlog` gauge: pending job count

Warn if backlog grows across several cleanup intervals. Do not fail readiness because a cleanup job is retrying.

Logs may include request id and `deletionId`. Do not log email, password, JWT, storage keys, signed URLs, or review/privateMemory.

## Flyway

Latest schema migration: **V10** (`account_deletion_media_jobs`). V1–V9 are unchanged. Production applies schema locations only. Fresh V1–V10 and V9→V10 upgrades are both supported.
