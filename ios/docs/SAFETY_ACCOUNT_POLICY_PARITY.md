# iOS v0.8 Safety, Account Lifecycle, and Policy Parity

This document records the verified production backend contract, the Android parity semantics, and the native iOS v0.8 implementation for Safety (Block, Report), Account Deletion, and UGC Policy Acceptance.

---

## 1. Verified Real Backend Contracts

All contracts were verified directly against Spring Boot backend source code in `com.emirrkls.phokarta.backend`.

### Block Endpoints
- `PUT /api/v1/me/blocks/{userId}`:
  - Idempotent block command (`INSERT INTO user_blocks ... ON CONFLICT DO NOTHING`).
  - Atomically removes any follow edges between `blocker` and `target` in **both directions** within the same transaction (`user_follows` cleanup).
  - Self-block is rejected with `400 BAD_REQUEST` (`CANNOT_BLOCK_SELF`).
  - Target not found returns `404 NOT_FOUND`.
  - Rate limited via `SafetyRateLimiter` (default 60/hour) → `429 RATE_LIMITED`.
  - Returns `204 NO_CONTENT`.
- `DELETE /api/v1/me/blocks/{userId}`:
  - Idempotent unblock command (`DELETE FROM user_blocks WHERE blocker_user_id = ? AND blocked_user_id = ?`).
  - Explicitly does **not** restore prior follow connections or friendship.
  - Returns `204 NO_CONTENT`.
- `GET /api/v1/me/blocks?page={page}&size={size}`:
  - Returns paginated `PageResponse<BlockedUserResponse>` containing: `userId`, `username`, `displayName`, `avatarUrl`, `blockedAt`.
  - Ordered by `blocked_at DESC`.

### Report Endpoints
- `POST /api/v1/reports`:
  - Unified report endpoint for both users and visits.
  - Request DTO:
    - `targetType`: `USER` | `VISIT` (`ReportTargetType`)
    - `targetId`: UUID of target user or visit
    - `reason`: `SPAM`, `HARASSMENT`, `HATE_OR_ABUSE`, `SEXUAL_CONTENT`, `VIOLENCE_OR_THREAT`, `IMPERSONATION`, `PRIVACY`, `OTHER` (`ReportReason`)
    - `details`: Optional text, trimmed, `@Size(max = 2000)`
  - Self-report rejected with `400 BAD_REQUEST` (`CANNOT_REPORT_SELF`).
  - Target user/visit not found returns `404 NOT_FOUND`.
  - Visit report eligibility: PUBLIC visits reportable by anyone. FRIENDS visits require current friendship. PRIVATE visits cannot be reported.
  - Duplicate OPEN suppression: If an `OPEN` report already exists from the same reporter for the same target, the backend returns the existing report with status `200 OK` (new report creation returns `201 CREATED`).
  - Rate limited via `SafetyRateLimiter` (default 10/hour) → `429 REPORT_RATE_LIMITED`.
  - Moderation behavior: Submitting a report does **not** auto-hide content, ban users, or notify the target.
  - Response DTO: `ReportResponse` (`id`, `targetType`, `reason`, `status`, `createdAt`).

### Account Deletion Endpoints
- `DELETE /api/v1/me`:
  - Hard deletion of user account.
  - Request body: Optional `DeleteAccountRequest(currentPassword: String?)`.
  - Password re-authentication: Required only if `user.getPasswordHash() != null` (password accounts). OAuth-only accounts omit password.
  - Incorrect password returns `400 BAD_REQUEST` (`INVALID_CURRENT_PASSWORD`).
  - Deletion transaction:
    1. Locks user row (`SELECT ... FOR UPDATE`).
    2. Enqueues async S3 media deletion cleanup jobs (`account_deletion_media_jobs`).
    3. Deletes owned visit media records (`visit_media`).
    4. Deletes user record (`users`), cascading deletion of all owned visits, collections, saved places, follows, blocks, and draft records.
    5. After transaction commit, schedules async media blob cleanup.
  - Returns `204 NO_CONTENT`.
  - Lost-ACK idempotency: A subsequent call after deletion succeeds returns `401 UNAUTHORIZED` (user does not exist in DB).

### UGC Policy Acceptance Endpoints
- `GET /api/v1/me/policy-status`:
  - Returns `PolicyStatusResponse`:
    - `requiredVersion`: Server required policy version (default `"2026-08-beta"`).
    - `acceptedVersion`: Highest policy version accepted by the user (or `null`).
    - `accepted`: Boolean indicating whether `acceptedVersion` satisfies `requiredVersion`. Older versions do **not** satisfy newer required versions.
- `POST /api/v1/me/policy-acceptance`:
  - Request body: `PolicyAcceptanceRequest(policyVersion: String)`.
  - Version check: `policyVersion` must match the server's current `requiredVersion`. Version mismatch returns `400 BAD_REQUEST` (`VALIDATION_ERROR`).
  - Idempotent: Subsequent submissions for the same version return `200 OK` without error.
  - Returns updated `PolicyStatusResponse`.
- Gated UGC endpoints:
  - Visit creation (`POST /api/v1/visits`), media intent (`POST /api/v1/media/upload-intent`), media confirm, and collection creation reject with `403 FORBIDDEN` (`code = "POLICY_ACCEPTANCE_REQUIRED"`, `requiredVersion = "..."`) if the user has not accepted the current policy version.
  - Reads, saved places, and social follows are **not** policy-gated.

---

## 2. Storage and Visibility Model

| Aspect | Backend Behavior | iOS v0.8 Implementation |
|---|---|---|
| **Block storage** | Directed: `(blocker_id, blocked_id)` row in `user_blocks` | Directed `BlockService.block(userId:)` |
| **Visibility barrier** | **Symmetric**: `ViewerAccessPolicy.isBlockedEitherDirection` hides visits, profiles, collections, media | On block: immediate `SocialStateStore.invalidateUser`, profile switches to `phase = .unavailable` |
| **Follow cleanup** | Directed follows deleted in **both directions** in DB transaction | `SocialStateStore.invalidateUser` removes follow & friendship, fires `onFriendshipChanged(userId, false)` |
| **Unblock restore** | Unblock only removes block row; prior follows are **never** restored | `BlockService.unblock` leaves follow/friend state `false` |
| **Report effect** | Report records created for moderation; content is **not** hidden or blocked | Submitting report preserves current follow/block state |
| **Report duplicate** | Existing OPEN report returned with `200 OK` | `ReportController` handles duplicate response as success without loop |
| **Rate limits** | 60 blocks/hour (`RATE_LIMITED`), 10 reports/hour (`REPORT_RATE_LIMITED`) | Mapped to `AppError.rateLimited`; UI transitions to safe try-later state with no retry loops |

---

## 3. Account Deletion Flow

1. **User Initiation**:
   - Accessed from Settings (`SettingsScreen` → `DeleteAccountScreen`).
   - Warning banner explains permanent destruction of profile, visits, saved places, collections, social connections, and uploaded media.
   - User must enter current password to confirm.
2. **Backend Execution**:
   - `DELETE /api/v1/me` with `currentPassword`.
   - Backend verifies bcrypt password, locks user, deletes DB records with CASCADE, enqueues S3 deletion jobs, returns `204 NO_CONTENT`.
3. **Local Teardown Sequence**:
   - In-memory stores cleared (`saved`, `collections`, `visits`, `socialState`, `policyStore`).
   - Durable SQLite purge: `SQLiteLocalAccountPurger.purgeLocalData(userId:)` executes `DELETE FROM visit_drafts WHERE userId = ?` and `DELETE FROM pending_mutations WHERE userId = ?` (cascades to draft photos, dimension scores, payloads).
   - Durable Media Store purge: `mediaStore.deleteAllOwned(userId: userId)` deletes owner directory on disk.
   - Keychain session wiped via `auth.logout()`.
   - Root UI transitions to `.signedOut` (login/register screen).
4. **Lost-ACK Convergence**:
   - If the `204` response packet drops after the backend deleted the account, the next API request or token refresh returns `401 UNAUTHORIZED`.
   - `DeleteAccountController` catches `unauthorized`, recognizes user is gone, and completes local purge and session teardown, converging to `.signedOut`.
5. **Re-registration Isolation**:
   - If a new account is created with the same email, the server assigns a new UUID.
   - Because all local SQLite rows and media files are strictly partitioned by UUID, the new account cannot observe or recover any data from the deleted UUID.

---

## 4. Policy Acceptance Flow

1. **Version-Aware Check**:
   - `PolicyStatusStore` tracks `requiredVersion`, `acceptedVersion`, and `accepted`.
   - If server updates required version from `v1` to `v2`, `accepted` becomes `false`.
2. **UGC Gating**:
   - When a queued visit or media mutation fails with `403 POLICY_ACCEPTANCE_REQUIRED`:
     - Mutation is marked `failedRetryable` with category `POLICY_ACCEPTANCE_REQUIRED`.
     - Error does **not** trigger session logout (`AppError.isTerminalAuth` is `false`).
3. **Policy Acceptance & Queue Resume**:
   - User accepts current policy via `PolicyAcceptanceScreen`.
   - Server records acceptance and returns `accepted = true`.
   - `PolicyAcceptanceScreen` invokes `syncEngine.drain()`.
   - MutationSyncEngine picks up the `failedRetryable` mutation, preserving exact `clientMutationId`, place, payload, and media order.
   - Remote creation succeeds with no duplicate mutations.

---

## 5. Android Parity & Behavioral Differences

| Area | Android Status | iOS Implementation |
|---|---|---|
| **Post-block navigation** | Missing comprehensive backstack pop | Profile screen switches to `.unavailable` immediately; lists remove blocked target |
| **Unblock UI** | Missing from Android settings | Present in iOS `SettingsScreen` → `BlockedUsersListScreen` |
| **Lost-ACK deletion** | Partially handled | Fully handled: `DeleteAccountController` catches 401 and executes full local teardown |
| **Policy version comparison** | Sheet receives version from error | Full required-version tracking in `PolicyStatusStore` |
| **Queued Visit resume** | Manual retry required | Automatic `syncEngine.drain()` upon policy acceptance |
| **Password reconfirm** | Hardcodes `requiresPassword = true` | Validates password for password-hash accounts |

---

## 6. Critical Safety & Lifecycle Invariants Audit (P1–P18)

| Invariant | Description | Verification Method | Status |
|---|---|---|---|
| **P1** | Block removes follow relationships both directions in same transaction | `BlockSocialIntegrationTests.testBlockRemovesFollowRelationsAndFriendship` | **PASS** |
| **P2** | Unblock never restores prior follows or friendship | `BlockSocialIntegrationTests.testUnblockDoesNotRestoreFollowsOrFriendship` | **PASS** |
| **P3** | Blocked target's unauthorized cached social content is invalidated | `BlockSocialIntegrationTests.testBlockInvalidatesAllCachedSocialStateForTarget` | **PASS** |
| **P4** | Community aggregate remains backend-authoritative after block | `BlockSocialIntegrationTests.testCommunityAggregateRemainsBackendAuthoritativeAfterBlock` | **PASS** |
| **P5** | Report does not auto-block, auto-ban, or hide content | `ReportFeatureTests.testSubmittingReportDoesNotMutateFollowOrBlock` | **PASS** |
| **P6** | Duplicate OPEN report returns 200 without local repeat loop | `ReportFeatureTests.testDuplicateOpenReportReturnsExistingWithoutLoop` | **PASS** |
| **P7** | Report rate limit (429) maps to safe try-later state with no retry storm | `ReportFeatureTests.testReportRateLimitTransitionsToSafeTryLater` | **PASS** |
| **P8** | Account delete success always terminates signed out with local purge | `AccountDeletionFeatureTests.testCanonicalAccountDeletionSuccess` | **PASS** |
| **P9** | Lost-ACK deletion converges to signed out with local purge | `AccountDeletionFeatureTests.testLostAckDeleteConvergesToSignedOut` | **PASS** |
| **P10** | Deleted account's durable data cannot leak to new UUID (same email) | `AccountDeletionFeatureTests.testSameEmailNewUUIDCannotAccessOldAccountData` | **PASS** |
| **P11** | Old queue worker cannot resurrect deleted account state | `AccountDeletionFeatureTests.testSqliteAccountPurgeDiskBackedIsolation` | **PASS** |
| **P12** | Policy state is required-version aware; stale responses cannot override newer acceptance | `PolicyFeatureTests.testStalePolicyResponseCannotOverrideNewerAcceptance`, `testPolicyVersionBumpRequiresReacceptance` | **PASS** |
| **P13** | Policy-required (403) never causes logout or session loss | `PolicyFeatureTests.testPolicyRequiredErrorDoesNotTriggerLogout` | **PASS** |
| **P14** | Policy acceptance resumes blocked queued Visit with same mutation identity | `PolicyFeatureTests.testQueuedVisitResumesAfterPolicyAcceptanceWithSameIdentity` | **PASS** |
| **P15** | Policy state is account scoped; switches clear unaccepted state | `PolicyFeatureTests.testAccountPolicyIsolation` | **PASS** |
| **P16** | Account purge affects only target user's local data; other accounts intact | `AccountDeletionFeatureTests.testLocalMediaIsolationAfterPurge` | **PASS** |
| **P17** | Passwords and private memories are redacted and never logged | `AccountDeletionFeatureTests.testDeleteAccountRequestDTORedactsPassword` | **PASS** |
| **P18** | All v0.6/v0.7 privacy and offline queue invariants remain intact | Full existing test suite verification | **PASS** |
