# iOS v0.6 Durable Drafts + Offline Mutation Queue Parity

This document records the architecture, durability invariants, recovery semantics, and parity alignment with Android (Room/WorkManager) and backend (Spring Boot/PostgreSQL/S3) contracts for Phokarta iOS v0.6.

---

## 1. Architectural Overview

Phokarta iOS v0.6 brings full offline resilience to Visit publishing and media handling:
- **Durable Drafts**: Automatically saved to SQLite on edit (400ms debounce) with photos stored in app-owned storage. Expire automatically after 30 days.
- **Durable Media Store**: Photos are normalized, EXIF GPS stripped, written atomically via `.part` rename into `Application Support/Phokarta/visit-media/<safeUserId>/`, and marked excluded from iCloud backups.
- **Offline Mutation Queue**: Process-death durable FIFO queue in SQLite with single-flight serial sync engine, atomic draft-to-mutation commit, compare-and-swap state claims, and crashed `SYNCING` recovery.
- **Idempotent Sync Engine**: Multi-step media upload pipeline (Intent → S3 Direct PUT → Confirm) followed by Visit publish with `clientMutationId` replay. Survives network drops, killed processes, and lost ACKs with zero duplicate creation.
- **Failed Visit Recovery**: User can recover failed visits for editing (restoring into draft and assigning a new mutation ID on subsequent publish) or retry transient failures with the exact same mutation ID.

---

## 2. Invariants

| ID | Invariant | Implementation Enforcement |
|:---|:---|:---|
| **I1** | Pre-ACK Privacy | Pending visits exist only in the local mutation queue and Place Detail pending section. They are never rendered in global/friend feeds or merged into `VisitStore.rows` until backend HTTP 201/200 ACK. |
| **I2** | Media S3 Direct PUT | S3 uploads use presigned URLs with isolated `URLRequest` instances. Bearer tokens are NEVER attached to object storage PUT requests. |
| **I3** | Atomic Draft Handoff | `commitVisit` executes inside an atomic SQLite transaction: the draft and draft photos are deleted in the same transaction that inserts the pending mutation, payload, dimensions, and pending photos. |
| **I4** | Lost-ACK Replay | A mutation in `QUEUED` or retried state retains its original `clientMutationId`. If the server processed the visit but client lost the ACK, replay returns the existing Visit (idempotent 201/200). |
| **I5** | Failed Visit Edit Identity | Recovering a failed visit for editing restores it into an editable draft and deletes the failed mutation $M_1$. The subsequent publish generates a fresh $M_2$, preventing fingerprint conflicts. |
| **I6** | Confirmed Media Reuse | Photos in `READY_REMOTE` state are never re-uploaded on retry. Only unconfirmed photos are uploaded. |
| **I7** | Media Ordering | Photos maintain strict 0-indexed position (`position ASC`). S3 upload confirms are mapped back to their original position, and `mediaIds` passed to `CreateVisitRequest` preserve that exact order. |
| **I8** | Account Isolation | All drafts, mutations, photos, and media files are strictly partitioned by `userId`. Users cannot see or access other accounts' drafts, queue entries, or local media. |
| **I9** | 30-Day Draft Expiry | Drafts older than 30 days ($T_0 + 30 \times 86400 \times 1000$ ms) are considered expired, excluded from restoration queries, and pruned during maintenance. |
| **I10** | Single-Flight Drain | `MutationSyncEngine.drain()` uses an actor-isolated `isDraining` flag to ensure only one sync pass runs at any time. |
| **I11** | CAS Claim & Generation | Claims use atomic `UPDATE pending_mutations SET state = 'SYNCING', generation = generation + 1 WHERE id = ? AND state IN ('QUEUED', 'FAILED_RETRYABLE')`. Concurrent claims return `false`. |
| **I12** | Interrupted Syncing Recovery | On drain start, any mutation found in `SYNCING` state from a prior crashed run is automatically recovered back to `QUEUED` with an incremented generation. |
| **I13** | Atomic Media Writes | Media files are written to `.part` files first and atomically renamed to their final path via `FileManager.moveItem`. |
| **I14** | EXIF GPS Stripping | User photos are stripped of GPS location metadata before being written to disk via `VisitMediaPreparation.prepareForUpload`. |
| **I15** | Orphan Media Sweeper | Unreferenced media files older than the 5-minute grace period are swept by `MediaFileReconciler`. Actively referenced files in drafts or pending mutations are preserved. |

---

## 3. Recovery Semantics Matrix

```
[User Publishes Visit]
         │
         ▼
[Draft deleted + Pending Mutation M1 queued (Atomic Transaction)]
         │
         ▼
[Sync Engine claims M1 (state = SYNCING, generation++)]
         │
         ├─► [Upload unconfirmed photos to S3] ──(Failure)──► Mark FAILED_RETRYABLE / FAILED_PERMANENT
         │
         ├─► [POST /api/v1/visits with M1]
         │        │
         │        ├─► [201 / 200 Success] ──────────────────► Canonical Visit reconciled; M1 deleted from queue
         │        │
         │        ├─► [Network Drop / Lost ACK] ────────────► Mark FAILED_RETRYABLE (M1 unchanged)
         │        │                                                    │
         │        │                                                    ▼
         │        │                                           [User Retries / Auto-Drain]
         │        │                                                    │
         │        │                                                    ▼
         │        │                                           [Replay POST with exact same M1]
         │        │                                                    │
         │        │                                                    ▼
         │        │                                           [Backend deduplicates; returns 201/200]
         │        │
         │        ├─► [400 / 403 Permanent Failure] ────────► Mark FAILED_PERMANENT
         │                                                             │
         │                                                             ▼
         │                                                    [User taps Edit and Retry]
         │                                                             │
         │                                                             ▼
         │                                                    [M1 deleted; restored to Draft]
         │                                                             │
         │                                                             ▼
         │                                                    [User edits & taps Publish]
         │                                                             │
         │                                                             ▼
         │                                                    [New Mutation M2 queued]
         │
         └─► [Process Killed while SYNCING]
                  │
                  ▼
         [Next Process Launch / Network Online]
                  │
                  ▼
         [recoverStaleSyncing: reset to QUEUED, generation++]
                  │
                  ▼
         [Sync Engine drains normally]
```

---

## 4. Parity with Android Implementation

| Feature | Android (Room + WorkManager) | iOS (SQLite3 + MutationSyncEngine) | Parity Status |
|:---|:---|:---|:---|
| **Draft Storage** | `visit_drafts` + `visit_draft_photos` tables | `visit_drafts` + `visit_draft_photos` tables | **100% Identical Schema** |
| **Draft Expiry** | 30 days (`draft.isExpired(clock)`) | 30 days (`draft.isExpired(clock)`) | **Exact Match** |
| **Autosave** | 400ms debounce in ViewModel | 400ms debounce in `VisitComposerController` | **Exact Match** |
| **Draft Photos** | `ownerUserId`, `placeId`, `position`, `relativePath`, `clientMediaId` | `ownerUserId`, `placeId`, `position`, `relativePath`, `clientMediaId` | **Exact Match** |
| **Pending Mutation** | `pending_mutations`, `pending_visit_payloads`, `pending_dimension_scores`, `pending_photos` | `pending_mutations`, `pending_visit_payloads`, `pending_dimension_scores`, `pending_photos` | **100% Identical Schema** |
| **Claim CAS** | SQL `UPDATE ... WHERE state IN ('QUEUED', 'FAILED_RETRYABLE')` | SQL `UPDATE ... WHERE state IN ('QUEUED', 'FAILED_RETRYABLE')` | **Exact Match** |
| **Generation Tracking** | `generation` column incremented on claim/reset | `generation` column incremented on claim/reset | **Exact Match** |
| **Stale Recovery** | WorkManager startup / timeout recovery | Startup drain + network online drain recovery | **Exact Match** |
| **Media Directory** | `files/visit-media/<safeUserId>/` | `Application Support/Phokarta/visit-media/<safeUserId>/` | **Platform Native Match** |
| **Media Safety** | EXIF GPS stripped, `.part` atomic rename, backup exclusion | EXIF GPS stripped, `.part` atomic rename, backup exclusion | **Exact Match** |
| **Orphan Cleaner** | `MediaFileReconciler` with 5-minute grace period | `MediaFileReconciler` with 5-minute grace period | **Exact Match** |
| **Lost-ACK Handling** | Idempotent replay with unchanged `clientMutationId` | Idempotent replay with unchanged `clientMutationId` | **Exact Match** |
| **Failed Recovery** | `recoverFailedVisitForEditing` restores draft, creates new M2 | `recoverFailedVisitForEditing` restores draft, creates new M2 | **Exact Match** |
| **Place Detail Pending UI** | Pending visits listed first under Your Visits with status badge | Pending visits listed first under Your Visits with status badge | **Exact Match** |
