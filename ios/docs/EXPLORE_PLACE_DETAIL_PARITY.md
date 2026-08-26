# iOS v0.2 — Explore + Place Detail parity

Android is the behavior reference. The Spring Boot API is the contract source of truth. This document is the iOS v0.2 read slice, not a pixel-for-pixel Compose port.

`iOS Xcode build: NOT RUN`. XCTest sources are authored, not executed.

## Android behavior referenced

- `ExploreScreen` / `ExploreViewModel`: catalog refresh, local category chips, place cards, saved/visited badges. Android Explore filters **locally** on a cached catalog and mutates save. iOS v0.2 has no Room cache and **does not mutate** saved state.
- `SearchScreen` / `SearchViewModel`: remote `GET /api/v1/places` with `search` + `category`, 300ms debounce, `collectLatest`-style stale-response protection. iOS Explore uses this remote search/filter model because the API supports it and iOS has no offline catalog.
- `PlaceDetailScreen` / `PlaceDetailViewModel`: detail by id, Community / Friends / You scores, dimension bars, friends preview, community/friends reviews, not-found, read-only visit history. iOS shows read-only saved/visited and omits save, rate, collections, share, report, and visit publish.
- Score copy: `Not rated` when `averageScore` is null. Format is one decimal (`8.7`, `9.0`). Community is PUBLIC Visit aggregate and is **not** averaged from the visible review list.
- Friends count `0`: show “No friend visits yet”, never coerce a missing friends score to `0.0`.
- Personal / You: shown from the current user’s visits when present; otherwise Not rated.

## Backend endpoints consumed

No backend production changes.

| Use | Method | Path | Auth |
|-----|--------|------|------|
| Explore / search / category | `GET` | `/api/v1/places?search&category&sort=averageScore,desc&page&size` | Bearer (signed-in shell) |
| Place Detail | `GET` | `/api/v1/places/{id}` | Bearer |
| Community / friends reviews | `GET` | `/api/v1/places/{id}/reviews?scope=community\|friends&page&size` | Bearer |
| Friends aggregate + preview | `GET` | `/api/v1/places/{id}/friends-summary` | Bearer |
| Explore friend metrics | `POST` | `/api/v1/me/places/friend-metrics` | Bearer |
| Saved IDs (display only) | `GET` | `/api/v1/me/saved-places` | Bearer |
| Personal visits (display only) | `GET` | `/api/v1/me/visits` | Bearer |

Not used in v0.2 (map / location milestone): `GET /api/v1/places/nearby`, `GET /api/v1/places/bounds`.

Query parameter names match `PlaceController` / Android `PlaceApi` exactly: `category`, `city` (unused), `search`, `minRating` (unused), `sort`, `page`, `size`.

Category wire values are backend enum names: `BEACH`, `RESTAURANT`, `CAFE`, `HOTEL`, `BAR`, `NIGHTLIFE`, `ATTRACTION`, `ACTIVITY`, `NATURE`. Localized labels are never sent.

## Implemented iOS behavior

- Signed-in `TabView`: Explore + Profile placeholder (logout). Android’s Map / Saved / Activity tabs are not cloned as empty shells.
- Explore is behind login, matching the Android product shell. Backend GET places is public; iOS still sends the session token so block-aware filtering and enrichment apply.
- Remote search + category together. Typing does not clear category. Category change does not clear query.
- Debounced search (300ms, `Task.sleep` + cancellation). Request generation token drops stale responses.
- Pagination via `hasNext` / `page`. Deduplicated by place UUID.
- Place identity: backend UUID. Navigation: `AppRoute.placeDetail(UUID)`. Detail reloads by id; summary is not the source of truth.
- Scores stay 0–10, nullable → localized Not rated. Community / Friends / You remain distinct.
- Community score is `PlaceDetail.averageScore` / `PlaceSummary.averageScore` from the backend aggregate. Visible reviews are never averaged on the client.
- Saved / visited are read-only indicators. No save/unsave, no visit create.
- `privateMemory` is not decoded on public reviews and is ignored on owner-visit JSON.
- Images: SwiftUI `AsyncImage` for catalog `coverImage` / `photos`. HTTPS only in Release. No third-party image library.
- Offline: network error + Retry. No Room/SwiftData cache.
- 429: localized rate-limit message, no automatic retry.
- Terminal auth after failed refresh: existing v0.1 `TokenRefreshCoordinator` → signed out. Explore does not loop Retry.

## Image source (beta concern)

Dev seed `backend/src/main/resources/db/migration/dev/V2__seed_demo_data.sql` stores **Unsplash HTTPS URLs** on `cover_image` / `photos`. Those are demo placeholders, not product-owned media. iOS does not add new Unsplash fallbacks. Production/beta catalog assets still need a first-party source. Visit media (`accessUrl`) is a separate signed-read architecture and is not used for Place catalog images.

## Intentional v0.2 gaps

- Saved mutation
- Collections mutation / UI
- Visit publish / rating editor
- Media upload and signed visit-media access
- Map / device location (`nearby`, `bounds`, Info.plist location usage)
- Offline persistence / sync
- Social follow / block / report actions
- Account deletion UI
- Policy acceptance UI
- Android Explore’s local catalog sections (“Hidden gems”, Want to Go carousel, collections CTA)
- Android Search extra filters (saved-only, visited-only, 9+, sort menu)

Parity gap vs Android Explore: Android category filter is local-on-cache; iOS category filter is remote because v0.2 has no persistence. Combined search+category still matches the Android Search remote contract.
