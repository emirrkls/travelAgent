# Offline mutation sync

Phokarta keeps unfinished Visit drafts separate from committed mutations. Pressing Publish
atomically snapshots the validated Visit into the Room mutation queue and deletes its draft.
Saved Place taps atomically update the Room membership and coalesce one desired-state mutation
per user and Place.

## State machine

`PENDING` is claimable. A worker claims it as `SYNCING`; interrupted `SYNCING` rows are reset at
the start of the next unique drain. Transport failures, 408, 429, 5xx, and an ultimately failed
401 are `FAILED_RETRYABLE`. Validation, forbidden, not found, and conflict responses are
`FAILED_PERMANENT`. Manual retry returns either failure state to `PENDING`. Successful rows and
their cascade-owned typed payload are deleted after local reconciliation.

One WorkManager chain named `phokarta_mutation_sync` drains batches with a connected-network
constraint and exponential backoff. `APPEND_OR_REPLACE` retains a successor trigger when an
enqueue races an active drain. Tokens and private content never enter WorkManager input data.

## Visit idempotency

New clients always send their immutable UUID `clientMutationId`. The backend stores it with a
canonical SHA-256 request fingerprint and enforces a partial unique index on
`(user_id, client_mutation_id)`. A PostgreSQL transaction advisory lock serializes concurrent
first delivery. An identical retry returns the original Visit; reuse with a different payload
returns 409. The nullable column keeps legacy clients additive and backward compatible.

## Saved generations

A Saved mutation row is mutable by design. Each tap increments its generation and records only
the latest desired boolean. An ACK applies and deletes only when its captured generation still
matches the queue row, so an in-flight stale save cannot erase a newer unsave. Saved-list refresh
reapplies pending local intents in the same Room transaction. The optimistic timestamp is the
local intent time until a matching save ACK replaces it with canonical server `savedAt`.

All rows are user-scoped. Logout retains them; workers process only the currently authenticated
owner. Pending Visits are owner-only local presentation and never enter Community, Friends,
review, score, activity, or map inputs before server acknowledgement.
