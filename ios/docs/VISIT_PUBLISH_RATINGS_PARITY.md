# iOS v0.4 Visit Publish + Ratings parity

This document records the repository-audited contract used by the native iOS
implementation. Backend source is the wire authority; Android is the product
behavior reference.

## Backend contract

- Create: authenticated `POST /api/v1/visits`, returning the canonical
  `VisitOwnerResponse` with HTTP 201.
- Owner history: authenticated `GET /api/v1/me/visits?page={page}&size={size}`.
  Pages allow 1–100 rows and are ordered by `visitedAt DESC`, then `createdAt
  DESC`, then `id DESC`.
- Create fields: optional legacy-compatible `clientMutationId`; required
  `placeId`, past-or-present `visitedAt`, `overallRating`, and `visibility`;
  optional `dimensions`, `publicReview`, and `privateMemory`. v0.4 omits legacy
  `photos` and managed `mediaIds`.
- Owner response: `id`, `place`, `visitedAt`, `overallRating`, `dimensions`,
  `publicReview`, `privateMemory`, legacy photo/media descriptors, `visibility`,
  and `verificationStatus`. iOS decodes media-independent owner fields only;
  unknown response fields remain forward compatible.
- Overall and dimension scores are finite JSON numbers from 0.0 through 10.0.
  Android exposes 0.1 steps; iOS matches that interaction and wire precision.
- Dimensions are optional, at most 20, unique by key, and each submitted key
  must be valid for the Place category. Public review and private memory are
  independently optional with a 4,000-character maximum. Whitespace-only iOS
  text is omitted rather than sent as content.
- Visibility wire values are `PUBLIC`, `FRIENDS`, and `PRIVATE`. PUBLIC is
  community- and friend-readable; FRIENDS is mutual-friend-readable but not
  community-readable; PRIVATE is owner-only.
- UGC creation calls `UgcPolicyService.requireAccepted`. A missing/currently
  stale acceptance returns 403 `POLICY_ACCEPTANCE_REQUIRED` with the required
  version where available.

## Idempotency and payload fingerprint

`clientMutationId` is scoped to the authenticated owner. The backend locks the
owner/key pair and fingerprints place, visit date, overall rating, dimensions
(sorted by key), public review, private memory, legacy photos, visibility, and
managed media IDs. An identical replay returns the original canonical Visit.
Reusing the key with a different fingerprint returns HTTP 409 and never creates
a second row.

iOS generates one UUID for one logical publish. A network failure or lost ACK
keeps that UUID in the in-memory composer so retrying an unchanged payload is an
idempotent replay. After any request has been sent, a material edit causes the
next publish to receive a new UUID. Draft edits before the first send retain the
initial UUID. A backend mismatch conflict is surfaced deliberately; it is not
automatically looped. Mutation identity is intentionally not durable in v0.4,
so process-death lost-ACK recovery is not guaranteed.

## Android behavior reference and iOS UX

Android `RatingScreen`, `RatingViewModel`, `VisitDraft`, and
`VisitVisibilityUi` establish the product terms and defaults: “Record a visit,”
8.0 initial overall score, 0.1 score steps, optional detail scores initialized
from overall when added, today as visit date, PUBLIC visibility, public review,
private memory, a protected publish action, and discard confirmation for a
meaningful draft. Android's media, durable draft, and mutation queue behaviors
are deliberately not ported in this online-first milestone.

iOS presents a native SwiftUI sheet containing a scrollable Form, DatePicker,
accessible Sliders, optional dimension rows, TextEditors, and a visibility
Picker. Publish is disabled during an in-flight request. A meaningful draft
requires confirmation before Cancel and blocks gesture dismissal. Dynamic Type
and keyboard reachability use native Form behavior.

## Dimension source of truth

The mapping is copied exactly from backend `RatingDimensionRegistry` and is
covered by wire-key tests:

- BEACH: SEA, ATMOSPHERE, SERVICE, CLEANLINESS, VALUE, CROWD
- RESTAURANT / CAFE: FOOD, SERVICE, ATMOSPHERE, VALUE, PRESENTATION
- HOTEL: CLEANLINESS, LOCATION, ROOM, SERVICE, BREAKFAST, VALUE
- BAR / NIGHTLIFE: DRINKS, MUSIC, ATMOSPHERE, SERVICE, VALUE
- ATTRACTION: EXPERIENCE, ACCESS, ATMOSPHERE, VALUE
- ACTIVITY: EXPERIENCE, SAFETY, GUIDE, VALUE
- NATURE: SCENERY, ACCESS, CLEANLINESS, TRANQUILITY

Unknown future categories show no dimensions and do not crash. Wire keys never
use localized labels.

## State, refresh, privacy, and account isolation

The explicit composer state is idle, publishing, retryable failure, validation
failure, policy required, or success. Retryable, validation, rate-limit, and
policy errors preserve all in-memory fields and never fabricate a Visit.
Double-taps observe publishing state before the first suspension and therefore
send only one request.

Canonical success is inserted at the front of the account-scoped `VisitStore`,
so visited state, latest “You” score, and append-only “Your visits” update
immediately. Place Detail reloads server detail/reviews/friend summary after
publish; Community and Friends aggregates are never calculated from visible
reviews. Personal score means the latest owner Visit because both backend owner
ordering and Android `PlaceDetailScreen` use the first newest Visit.

Only `OwnerVisit` decodes or renders `privateMemory`. Public/friend
`ReviewSummary` structurally has no private-memory field. The network logger
records method/path/status/request ID only and never logs Visit bodies, review
text, private memory, or auth tokens.

`VisitStore` is activated for the signed-in UUID and is cleared by the global
session-change/logout purge alongside Saved and Collections. Root signed-in UI
replacement destroys any composer owned by the previous account. A newly
created composer always starts from defaults, so account B cannot observe
account A's unsent draft or mutation identity. Existing `APIClient` and
`TokenRefreshCoordinator` provide the normal single-flight expired-access-token
retry; Visit adds no auth mechanism and policy-required does not log out.

## Intentional v0.4 gaps

- No Visit photo/media selection or upload.
- No SwiftData, Core Data, UserDefaults draft, or other durable draft storage.
- No durable mutation queue, background sync, or automatic retry.
- No process-death recovery for an unacknowledged successful publish.
- No map/location, social safety, account deletion, or full policy acceptance UI.
