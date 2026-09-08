# Phokarta iOS v0.9 — Maps + Final Cross-Platform Parity Report

## Executive Summary
This document provides the definitive architectural, behavioural, and invariant record for the **Phokarta iOS v0.9 Maps & Final Parity** milestone. With this release, Phokarta achieves feature parity with the reference Android implementation across discovery, social, offline persistence, media publishing, safety controls, and location-aware MapKit place discovery.

---

## 1. Reference Audits

### 1.1 Android Map Audit
- **Architecture**: In Android, the map discovery interface is implemented via `MapScreen.kt` and `MapViewModel.kt` utilizing Google Maps Compose. It sits on the primary bottom navigation bar as `Route.Map`.
- **Viewport & Camera**:
  - Center: Default fallback is `LatLng(37.085, 27.53)` (Bodrum center) with zoom `11.2f`.
  - Span: `north/south ±0.35`, `east/west ±0.45`.
  - In-memory persistence across screen pushes and tab switching.
- **Filters**:
  1. `Category`: Canonical `PlaceCategory` (enum).
  2. `9+`: Highly rated filter matching `communityScore >= 9.0`. Sent to backend as `minRating = 9.0`.
  3. `Friends`: Requires distinct mutual friends visited count > 0 (`POST /api/v1/me/places/friend-metrics`).
  4. `Visited`: Client-side check against owner visit store (`visitedPlaceIds`).
  5. `Want to Go`: Client-side check against saved place store (`savedPlaceIds`).
- **"Search this area"**:
  - Triggers only when candidate camera viewport moves meaningfully from the applied viewport:
    $$\Delta\text{zoom} \ge 0.55 \quad\lor\quad \sqrt{\left(\frac{\Delta\text{lat}}{\text{latSpan}}\right)^2 + \left(\frac{\Delta\text{lon}}{\text{lonSpan}}\right)^2} \ge 0.18$$
  - Avoids firing on tiny camera jitter or programmatic centering.
- **List & Sheet Interaction**:
  - Bottom sheet scaffold with peek height `186.dp` expanding to `470.dp`.
  - Synchronized selection: tapping pin highlights list item and auto-scrolls; tapping item highlights pin and centers camera.
  - Tapping an already-selected card opens `PlaceDetailScreen`.

### 1.2 Backend Geo Contract Audit
- **Nearby Endpoint**:
  - `GET /api/v1/places/nearby`
  - Query parameters: `lat`, `lon`, `radiusMeters` (default 5000, max 50000), `category` (optional), `minRating` (optional), `limit` (default 50, max 200).
  - Returns `List<NearbyPlaceResponse>` (`{ place: PlaceSummaryResponse, distanceMeters: Double }`).
  - Sorted by ascending geodesic distance in meters.
- **Bounds Endpoint**:
  - `GET /api/v1/places/bounds`
  - Query parameters: `west`, `south`, `east`, `north`, `category` (optional), `minRating` (optional), `limit` (default 50, max 200).
  - Validation: `south < north`, `west < east` (antimeridian crossing boxes rejected with validation exception).
  - Coordinates formatted with POSIX locale (strictly dot decimals).
  - Returns `List<PlaceSummaryResponse>`.
  - Sorted by Community `averageScore DESC NULLS LAST, p.id ASC`.
- **Friend Metrics Companion**:
  - `POST /api/v1/me/places/friend-metrics`
  - Payload: `{ "placeIds": ["uuid", ...] }` (capped at 200 IDs).
  - Returns: `List<FriendPlaceMetricsResponse>` (`placeId`, `friendAverageScore`, `friendsVisitedCount`).
  - Strict mutual friend visibility: only distinct mutual friends with PUBLIC or FRIENDS visits count; self and PRIVATE visits are excluded.

---

## 2. iOS MapKit Architecture

### 2.1 Component Structure
```
ios/Phokarta/Features/Map/
├── MapModels.swift                # Pure value types, viewport math & local filters
├── LocationService.swift          # CoreLocation abstraction and MockLocationService
├── MapController.swift            # Observable controller, generation tokens & concurrency
├── MapScreen.swift                # SwiftUI MapKit Map, gestures, and safe area layout
└── Components/
    ├── PlaceAnnotationView.swift  # Semantic pin displaying category, score & badges
    ├── MapFilterBar.swift         # Header, category menu, and filter chip buttons
    ├── SearchThisAreaButton.swift # Inverted pill button triggering bounds reload
    └── MapPlaceSheet.swift        # Interactive bottom sheet with synchronized cards
```

### 2.2 Invariant Verification (M1–M18)

| Invariant | Description | Verification / Enforcement |
|:---|:---|:---|
| **M1** | Canonical Place Identity | Annotations use `place.id` (UUID). No string coordinates or indices. |
| **M2** | Stale Bounds Race | `boundsRequestId` incremented per fetch; Task cancellation prevents late responses from overwriting newer queries. |
| **M3** | Meaningful Pan Threshold | `viewportMovedEnough` enforces $\Delta\text{zoom} \ge 0.55$ or normalized distance $\ge 0.18$. Micro-jitter ignored. |
| **M4** | Late Location Intent Guard | `hasPannedManually` flag prevents late GPS callbacks from overriding manual map exploration. |
| **M5** | Usable Without Location | When location is denied or restricted, map gracefully defaults to Bodrum region; manual searches operate normally. |
| **M6** | Minimal Permission | Only `NSLocationWhenInUseUsageDescription` is declared in `Info.plist`. Zero background tracking. |
| **M7** | Zero Precise Location Storage | No GPS coordinates are stored in SQLite or logged to console. |
| **M8** | Detail Round-Trip | MapController maintains viewport, filters, places, and selection when navigating to Detail and returning. |
| **M9** | Shared Saved Store | Want to Go filter and card indicators observe live `SavedPlaceStore`. |
| **M10** | Shared Visit Store | Visited filter and card indicators observe live `VisitStore` (including owner's local pending visits). |
| **M11** | Backend-Authoritative Friends | Friends filter requires `friendsVisitedCount > 0` loaded via `POST /api/v1/me/places/friend-metrics`. |
| **M12** | Block/Social Invalidation | Block or follow changes trigger `invalidateFriendMetrics()`; community score is never altered locally. |
| **M13** | Community Score Authority | `averageScore` is authoritative from backend PUBLIC visits only. |
| **M14** | Pre-ACK Privacy | Pending local visits affect owner's local visited badge only, never public community scores or friend metrics. |
| **M15** | Account Isolation | Account switch purges viewer-specific state (`friendMetrics`, `selectedPlaceId`, filters). |
| **M16** | Zero Regressions | All 199 prior tests pass; strict concurrency remains complete with zero warnings. |
| **M17** | Parity Alignment | Documented feature matrix aligns iOS with Android across all workflows. |
| **M18** | Release Boundaries | Out-of-scope and production infra items clearly documented in `BETA_READINESS.md`. |

---

## 3. Cross-Platform Feature Parity Matrix

| Domain | Feature Slice | Android | iOS | Status | Notes |
|:---|:---|:---|:---|:---|:---|
| **Auth** | Login / Register / Restore | Compose | SwiftUI | **PARITY** | Single-flight refresh, Keychain/EncryptedSharedPreferences |
| **Explore** | Place Catalog & Search | LazyColumn | List + Searchable | **PARITY** | Category filters, community ratings, debounced query |
| **Detail** | Place Detail & Dimensions | Screen | Screen | **PARITY** | Dimension score breakdowns, recent public reviews |
| **Saved** | Want to Go | Screen | Screen | **PARITY** | Optimistic mutations, shared store invalidation |
| **Collections** | Custom Place Collections | Screen | Screen | **PARITY** | Create, view, add/remove places, visibility |
| **Visits** | Publishing & Drafts | Composer | Composer | **PARITY** | Rating dimensions, private memory, clientMutationId |
| **Media** | Media Uploads | Storage | Storage | **PARITY** | EXIF GPS stripping, HEIC->JPEG, presigned upload |
| **Offline** | Queue & Sync Engine | Room + SQLite | SQLite + WAL | **PARITY** | CAS claiming, lost ACK retry, process-death recovery |
| **Social** | Profiles & Follows | Screen | Screen | **PARITY** | Mutual friendship indicator, followers/following lists |
| **Feed** | Activity Stream | Screen | Screen | **PARITY** | Friend activity cards, place/author routing |
| **Safety** | Block & Report | Screen | Screen | **PARITY** | Rate-limited reporting, block list in settings |
| **Account** | Deletion & Local Purge | Service | Service | **PARITY** | Password confirmation, complete SQLite & media wipe |
| **Policy** | UGC Gate & Acceptance | Dialog | Sheet Gate | **PARITY** | Version change re-prompt, queued mutation resume |
| **Map** | MapKit / Google Maps | Google Maps | Apple MapKit | **PARITY** | Native MapKit pins, bottom sheet, filters, dirty area |
| **Map** | Search This Area | Yes | Yes | **PARITY** | Shared mathematical movement threshold |
| **Map** | Location Permission | Contextual | Contextual | **PARITY** | When-In-Use only, graceful denied UX |
| **Shell** | Tab Architecture | 4 tabs + FAB | 5 tabs (HIG) | **INTENTIONAL** | Saved Places + Collections consolidated in Saved tab |

---

## 4. Platform-Specific Implementation Details
- **Apple MapKit vs Google Maps**: On iOS, Apple MapKit (`Map(position:selection:)`) is used natively without third-party SDK dependencies (no Google Maps SDK or Mapbox), preserving app binary size and battery efficiency.
- **Tab Layout**: In Android, "Add" is a floating center button, and Want to Go / Collections are pushed screens. On iOS, adhering to Human Interface Guidelines (3 to 5 tabs), Tab 4 (`Saved`) presents a segmented control toggling between `Places` and `Collections`. Map occupies dedicated Tab 2.
