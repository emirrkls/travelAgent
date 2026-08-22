# Phokarta v0.5

Phokarta is a native Kotlin/Jetpack Compose prototype for travel discovery, personal travel memory, category-aware ratings, saved places, and collections. Android application ID: `com.emirrkls.phokarta`.

## Stack and architecture

Android uses JDK 17, Kotlin, Compose, Hilt, Room 2.7.2, Google Maps Compose 6.4.1, Retrofit 3.0.0, OkHttp 4.12.0, and kotlinx serialization 1.8.1. The backend is an independent Java 21/Spring Boot 3.5 service backed by PostgreSQL/PostGIS and Flyway.

Data flows through one boundary:

`Remote API (places and synchronized owner data) + Room (device user state) -> TravelRepository -> StateFlow ViewModels -> Compose UI`

- `core/network` owns Retrofit APIs, DTOs, serialization, safe error conversion, and remote data sources.
- `core/database` owns Room v3 entities, DAOs, mappers, migrations, and exported schemas.
- `core/data/DefaultTravelRepository` maps and combines remote place data with local visits, saved IDs, and collections.
- `feature/*` owns screen state. Map viewport, filters, selection, stale-request cancellation, and last-good markers live in `MapViewModel`.
- Social Activity remains prototype/demo content; social scores are not fabricated from the backend.

## Start the backend with demo data

Prerequisites: Java 21, Maven 3.9+, and Docker Desktop.

```powershell
cd backend
Copy-Item .env.example .env
docker compose up -d db
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
```

The `dev` profile is required for deterministic backend seed data. The default profile creates a production-style empty database. Swagger UI is at `http://localhost:8080/swagger-ui.html`.

### Android emulator

The debug build defaults to `http://10.0.2.2:8080/`, Android Emulator's alias for the host:

```powershell
.\gradlew.bat installDebug
```

### Physical device over USB

Connect the device with USB debugging enabled, reverse the backend port, and build with the loopback URL:

```powershell
adb reverse tcp:8080 tcp:8080
.\gradlew.bat installDebug -PPHOKARTA_API_BASE_URL=http://127.0.0.1:8080/
```

For a LAN endpoint, the phone must be able to route to the host, the firewall must allow the port, and the URL cannot use `localhost`. Debug cleartext is intentionally allowlisted only for `10.0.2.2`, `127.0.0.1`, and `localhost`; use HTTPS for LAN testing or explicitly add a debug-only host rule. Never broaden production cleartext policy.

Release builds default to a non-routable HTTPS placeholder. CI or the release command must inject the real HTTPS endpoint:

```powershell
.\gradlew.bat assembleRelease -PPHOKARTA_API_BASE_URL=https://api.example.com/
```

The network-security manifest overlay exists only in `src/debug`. Production has no cleartext opt-in and therefore keeps the Android platform's HTTPS-only posture.

## Google Maps key

Enable Maps SDK for Android, then create ignored `secrets.properties` in the repository root:

```properties
MAPS_API_KEY=your_android_maps_key
```

Restrict it to package `com.emirrkls.phokarta`, the signing certificate, and Maps SDK for Android. Obtain the debug SHA-1 with `.\gradlew.bat signingReport`. A placeholder allows compilation without a key, but map tiles will not authenticate. Never commit keys, `.env`, `local.properties`, or `secrets.properties`.

## Remote and local responsibilities

Remote APIs provide the authoritative place catalog, search pages, place detail, bounds, nearby results, owner visits, saved-place synchronization, and collections. Room stores visits and rating dimensions, saved/Want-to-Go IDs, collections, membership, and minimal read-only Place snapshots needed to render that user state after process death.

Offline behavior is intentionally limited:

- Existing Room visits, saved IDs, collections, and previously seen Place summaries remain available after process restart.
- The Place snapshot table is a display fallback, not an offline query or synchronization engine; online discovery remains backend-authoritative.
- There is no offline mutation queue.
- Failed optimistic save mutations roll Room state back. Explore and Map surface the failure; Activity currently only reflects rolled-back state because adding a separate Activity error surface would be disproportionate for v0.5.

Fresh installs do not insert a local mock catalog or mock user-state seed. All newly persisted place references use backend UUIDs.

### Room v1 to v3

`MIGRATION_1_2` is a controlled pre-production data reset. It clears visits, dimensions, saved places, collections, and membership because old `p...` mock IDs have no safe one-to-one mapping to backend UUIDs. The database schema itself is migrated normally; there is no `fallbackToDestructiveMigration`. This makes the loss explicit and testable while ensuring every v2 reference is a canonical backend UUID.

`MIGRATION_2_3` is non-destructive. It preserves canonical user state and adds `cached_places`, a minimal UUID-keyed Place summary table populated only from successful backend responses. This closes the cold-offline rendering gap without introducing mutation queues or conflict resolution.

## Demo identity and authentication boundary

The temporary demo user UUID is centralized in `DemoUserProvider`:

`11111111-1111-1111-1111-111111111111`

There is no authentication in v0.5. Owner endpoints accept this client-supplied UUID, which is not authorization. v0.6 must replace it with identity from an authenticated principal and must not preserve client-selected ownership.

## Contract mapping

- Backend UUID strings map directly to canonical domain and Room IDs.
- Nullable backend `averageScore` maps to nullable `communityScore`; unrated places stay unrated.
- Uppercase backend rating dimensions map to typed domain dimensions.
- `publicReview` maps to the public review; owner-only `privateMemory` maps to the local personal note.
- `PublicVisitDto` has no `privateMemory`; `CreateVisitDto` and owner responses are the only network types that carry it.
- Friends/similar-user scores and social signals remain hidden/null because the remote contract has no such fields.
- Nearby and collection responses use nested place DTOs. List/search/owner endpoints use `content`, `page`, `size`, `totalElements`, `totalPages`, and `hasNext`.
- Catalog and owner refreshes consume all pages, deduplicate canonical IDs, and publish only after every page succeeds. Search intentionally exposes one page and its metadata.
- Coordinates are WGS84. Bounds use `west,south,east,north`; nearby distances are meters.

## Implemented flows and errors

Implemented remote-backed flows include Explore catalog, debounced Search, Place Detail, Map bounds with explicit **Search this area**, Nearby, Visit publishing, saved places, and collections. Search and bounds cancel or ignore obsolete responses while retaining last-good results.

Network failures are normalized into offline, timeout, validation, not-found, conflict, server, and unknown categories. Screens use lightweight inline errors or snackbars and retries where appropriate.

## Privacy policy

- Public visit payloads never serialize `privateMemory`.
- Private memory is sent only in the owner create request and stored in owner-local state.
- OkHttp debug logging is `BASIC` only: request/response bodies and headers are never logged.
- Application code must not log DTOs, reviews, private memory, API keys, credentials, or full user payloads.
- Release builds do not install the debug logging interceptor.

## Build and test

Run from the repository root:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat compileDebugAndroidTestKotlin
git diff --check
```

Backend tests:

```powershell
cd backend
mvn test
```
