# iOS v0.3 Saved + Collections parity

This document records the production backend contract and the intentionally
online-first iOS v0.3 implementation. The backend and Android production source
were audited but not changed.

## Backend contract

Saved Places is owner-only and authenticated:

- `GET /api/v1/me/saved-places?page={page}&size={size}` returns
  `PageResponse<SavedPlaceResponse>` newest first. Each row contains an enriched
  `PlaceSummaryResponse`, `savedAt`, optional `friendAverageScore`, and
  `friendsVisitedCount`.
- `POST /api/v1/me/saved-places/{placeId}` is an idempotent desired-state Save
  and returns the canonical `SavedPlaceResponse`.
- `DELETE /api/v1/me/saved-places/{placeId}` is an idempotent desired-state
  Unsave and returns `204` even when already absent.

Collections uses canonical UUIDs:

- `GET /api/v1/me/collections?page={page}&size={size}` returns the current
  user's paginated summaries.
- `POST /api/v1/me/collections` requires `title` (nonblank, max 120),
  `description` (max 1000), `visibility` (`PRIVATE`, `FRIENDS`, or `PUBLIC`), and
  `coverImage` (nonblank, max 500). It returns `201` with canonical detail.
- `GET /api/v1/collections/{collectionId}` returns collection metadata and
  enriched place summaries, display order, and added timestamps. iOS therefore
  does not issue Place Detail N+1 requests.
- `POST /api/v1/collections/{collectionId}/places/{placeId}` is owner-only,
  returns canonical detail, and returns `409 CONFLICT` for a duplicate.
- `DELETE /api/v1/collections/{collectionId}/places/{placeId}` is owner-only,
  returns `204`, and returns `404` for an absent relationship.

Create and add require the current UGC policy acceptance. The server returns
`403 POLICY_ACCEPTANCE_REQUIRED` with `requiredVersion`. Remove is not gated.
Collection delete is available neither in this iOS slice nor required by v0.3.

## Android behavior referenced

Android's Want to Go screens, collection browser/detail, create sheet,
Place Detail picker, create-and-add behavior, add/remove handling, and policy
mapping were used as product references. Android also uses row-level membership
progress and treats duplicate add as canonical reconciliation. The Android
standalone create flow currently supplies an empty default cover even though the
backend requires a nonblank cover. iOS exposes the real required cover URL for
standalone create and reuses the current Place cover for picker create-and-add.

## iOS behavior

`SavedPlaceService` and `CollectionService` use the existing `APIClient`, auth
headers, error decoder, and single-flight `TokenRefreshCoordinator`. Both honor
real pagination and add no second networking or refresh stack.

`SavedPlaceStore` is a small account-scoped, in-memory source of truth. It keeps
confirmed server state separate from the latest desired UI state. Save/Unsave is
optimistic because both backend operations are idempotent desired-state writes.
One serialized mutation loop exists per Place UUID; unrelated places are not
blocked. A rapid Save → Unsave → Save sequence drives the server toward the last
intent. On failure the desired overlay is removed, the last confirmed state is
restored, and a localized retryable error is shown. Saved refreshes capture a
mutation revision and cannot erase a newer confirmed write.

Explore and Place Detail read live Saved state from the same store. The Saved
tab provides loading, content, empty, error, pull-to-refresh, and Place Detail
navigation without restarting the app.

`CollectionStore` keeps account-scoped summaries and canonical details in
memory. Create and canonical add responses update both views. A `204` remove is
followed by targeted detail reload rather than fabricated membership/count
state. Per collection/place busy guards suppress duplicate taps. List refreshes
merge canonical items changed after the request began, so a stale pre-create
response cannot erase a new collection. Detail request generations and mutation
revisions prevent stale pre-add/pre-remove responses from reverting membership.

The Collections tab provides loading, content, empty, error, refresh, native
create sheet, detail navigation, and swipe-to-remove. Place Detail provides a
native picker with membership state, row-level progress, add/remove, and
create-and-add. The list contract has no membership projection, so the picker
loads collection details to obtain authoritative membership; it does not fetch
Place Detail for contained places.

## Account isolation and authentication

Stores activate for the signed-in `CurrentUser.id`. Login, registration,
logout, terminal refresh failure, or a different account ID clears Saved rows,
Collection summaries/details, desired state, errors, and relation progress.
No data from account A is retained for account B.

A mutation `401` is handled only by the existing API client refresh path. A
terminal refresh failure uses the existing global sign-out flow and clears both
stores. `POLICY_ACCEPTANCE_REQUIRED` is a dedicated non-auth error: the user
stays signed in, no local success is added, and iOS shows targeted EN/TR copy.
Full policy acceptance UI is intentionally not part of v0.3.

## Online-only limitations and intentional gaps

There is no SwiftData, Core Data, disk cache, durable mutation queue, background
retry, or background sync. Offline writes fail cleanly and restore confirmed
state. Visit publishing, rating/dimension editing, media upload, map/location,
social safety screens, account deletion UI, full policy acceptance UI,
TestFlight, App Store release, and store assets remain outside v0.3.
