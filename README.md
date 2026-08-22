# Phokarta

Phokarta is a native Kotlin/Jetpack Compose prototype for social travel discovery, personal travel memory, category-aware ratings, and trusted recommendations.

**Android application identity:** `com.emirrkls.phokarta`

## Run locally

1. Open the repository in Android Studio.
2. Use JDK 17 or the Android Studio bundled runtime.
3. Let Gradle sync and run the `app` configuration on an Android 8.0+ emulator or device.

### Google Maps API key

The Map tab uses Google Maps Compose (`maps-compose` 6.4.1). API credentials are intentionally excluded from version control.

1. Enable **Maps SDK for Android** in a Google Cloud project.
2. Create `secrets.properties` in the repository root (next to `settings.gradle.kts`).
3. Add your key:

   ```properties
   MAPS_API_KEY=your_android_maps_key
   ```

4. Restrict the key to Android apps:
   - **Package name:** `com.emirrkls.phokarta`
   - **Certificate:** debug SHA-1 from `./gradlew signingReport` (Windows: `.\gradlew.bat signingReport`)
   - **API restriction:** Maps SDK for Android
5. Sync Gradle and run the app. A build without `secrets.properties` still succeeds using the non-secret placeholder in `local.defaults.properties`, but Google map tiles will not authenticate.

Both `secrets.properties` and `local.properties` are ignored. Never commit either file or paste a real key into the manifest, Gradle files, or `local.defaults.properties`.

The prototype uses remote demo photography, so `INTERNET` permission is enabled. Place and social discovery content remain mocked; user-generated state is durable and local.

## Architecture

- `core/model`: backend-compatible domain entities. `Place` and `Visit` are intentionally separate.
- `core/data`: `DefaultTravelRepository` combines the static mock place catalog with a Room-backed local user-state source.
- `core/database`: Room v1 entities, normalized relationships, DAOs, domain mappers, and exported schema.
- `core/di`: Hilt bindings for the database, DAOs, data sources, and repository.
- `feature/*`: screens and StateFlow ViewModels organized by product capability. The Map feature owns viewport, selection, and filter state while observing Room-backed user state through `TravelRepository`.
- `ui/components` and `ui/theme`: shared Compose design system.

The Map tab renders the existing mock catalog on Google Maps. Its category, rating, trusted, visited, and Want-to-Go filters are local and deterministic; moving the camera far enough reveals **Search this area**, which applies the visible map bounds without any backend request.

### MapViewModel scope

Bottom navigation uses `popUpTo(startDestination) { saveState = true }` with `restoreState = true`, which removes tab back-stack entries when switching destinations. To preserve map viewport, filters, and selection across tab switches, `MapViewModel` is intentionally scoped to the host `ComponentActivity` via `hiltViewModel(viewModelStoreOwner = LocalActivity.current)`.

This is a deliberate trade-off for the current navigation architecture. Revisit the scope if navigation is refactored to keep Map destinations on the back stack without destroying their ViewModel store.

## Local persistence

Room stores Visits, optional per-Visit rating dimensions, saved/Want-to-Go place IDs, collections, and collection membership. The mock place catalog keeps deterministic IDs and is not copied into the database. Demo user state is inserted only when the database is first created, using stable IDs; social Activity remains mocked. Onboarding completion remains a lightweight application preference.

## Backend v0.4

`backend/` is an independent Java 21/Maven Spring Boot service. It intentionally does not share the Android Gradle build. Its pragmatic layers keep JPA entities in `domain/entity`, database access in `repository`, business behavior in `service`, and public contracts in `api/dto`; controllers never expose persistence entities.

The backend uses Spring Boot 3.5, Spring Web, Spring Data JPA, Hibernate Spatial/JTS, Bean Validation, PostgreSQL/PostGIS, Flyway, springdoc OpenAPI, JUnit 5, and Testcontainers.

### Prerequisites

- Java 21
- Maven 3.9+
- Docker Desktop (for local PostGIS and integration tests)

### Start PostGIS

```powershell
cd backend
Copy-Item .env.example .env
docker compose up -d db
```

The checked-in defaults create a `phokarta` database and user, and bind PostgreSQL only to `127.0.0.1`. Change them through `.env` or environment variables when needed; `.env` is ignored and must not contain production secrets.

Spring Boot reads:

- `PHOKARTA_DB_URL` (default `jdbc:postgresql://localhost:5432/phokarta`)
- `PHOKARTA_DB_USER` (default `phokarta`)
- `PHOKARTA_DB_PASSWORD` (default `phokarta`)
- `SERVER_PORT` (default `8080`)

Flyway runs automatically at startup, enables PostGIS, and creates the schema and spatial indexes. The default profile loads only production-safe schema migrations. The `dev` profile additionally loads deterministic demo data from a separate migration location. Hibernate uses `ddl-auto: validate` and does not create or destroy the schema.

### Run and test

```powershell
cd backend
mvn test
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
```

Integration tests activate the `dev` profile and use the real `postgis/postgis:16-3.4` image; H2 is not used and Docker is required. Start without `SPRING_PROFILES_ACTIVE=dev` for a production-style empty database with no demo user or places. Swagger UI is available at `http://localhost:8080/swagger-ui.html` and the OpenAPI document at `http://localhost:8080/v3/api-docs`.

Demo user ID: `11111111-1111-1111-1111-111111111111`.

### API overview

- `GET /api/v1/places` — paginated search/filter/sort
- `GET /api/v1/places/{placeId}` — detail, community aggregate, dimension breakdown and recent public reviews
- `GET /api/v1/places/nearby` — radius search ordered by geodesic distance
- `GET /api/v1/places/bounds` — map viewport discovery
- `POST /api/v1/visits` — append a Visit; verification is server-controlled and starts as `UNVERIFIED`
- `GET /api/v1/users/{userId}/visits` — temporary owner-oriented history
- `GET|POST|DELETE /api/v1/users/{userId}/saved-places[...]`
- `GET|POST /api/v1/users/{userId}/collections`
- `GET /api/v1/collections/{collectionId}`
- `POST|DELETE /api/v1/collections/{collectionId}/places/{placeId}`

Normal lists use `page`, `size`, and a safe `sort` allowlist and return stable pagination metadata including `hasNext`. Map endpoints use a bounded `limit`.

### Geo convention

Coordinates are WGS84/SRID 4326. API parameters use `lat`/`latitude` and `lon`/`longitude`; JTS/PostGIS points are always created in **longitude, latitude** (`x`, `y`) order. Nearby distances are geodesic meters and are calculated by PostGIS with `ST_DWithin`/`ST_Distance`, not in Java. Bounds are `west,south,east,north`; antimeridian-crossing boxes are not supported in v0.4.

### Authentication boundary

There is no authentication in v0.4. Owner-oriented demo endpoints temporarily accept a client-supplied `userId`; this is not authorization.

> In the authentication milestone, user identity must come from the authenticated principal, not client-supplied ownership IDs.

Public Visit DTOs do not contain `privateMemory`; owner and public responses are separate types. Both response types may expose `verificationStatus`, but create requests cannot set it.

### Android contract notes

The backend is the API authority, but Android remains on its local mock repository in v0.4. The next milestone must map:

- Android `communityScore: Double` to backend nullable `averageScore` plus `ratingCount`
- Android `coverImage`/`review`/`personalNote` to backend `coverImage`, `publicReview`, and `privateMemory`
- Android title-case rating labels to backend uppercase dimension keys
- Android map filters to backend category/min-rating/nearby/bounds query parameters
- Android local IDs and state to backend UUIDs and owner/public Visit DTOs
- Android-only friends/similar-user scores and social signals to no backend field until those features exist

The exact next milestone is **Phokarta Android + Backend v0.5 — authenticated-ready Android API integration and offline cache mapping**, without adding authentication itself unless separately scoped.
