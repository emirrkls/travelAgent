# Phokarta v0.6

Phokarta is a native Kotlin/Jetpack Compose travel discovery app with secure email/password authentication, personal travel memory, category-aware ratings, saved places, and collections. Android application ID: `com.emirrkls.phokarta`.

## Stack and architecture

Android uses JDK 17, Kotlin, Compose, Hilt, Room 2.7.2, EncryptedSharedPreferences (`androidx.security:security-crypto`), Google Maps Compose, Retrofit, OkHttp, and kotlinx serialization. The backend is Java 21 / Spring Boot 3.5 with Spring Security, JWT access tokens, opaque refresh sessions, PostgreSQL/PostGIS, Flyway, and private S3-compatible storage for managed Visit media.

All new user-facing Android copy must use string resources with English (default) + Turkish (`values-tr`) translations. Keep API/enum values unlocalized.

Data flows through one boundary:

`Auth session + Remote API + Room (owner-scoped user state) -> TravelRepository -> StateFlow ViewModels -> Compose UI`

- `core/auth` owns TokenStore, SessionManager, AuthRepository, AuthInterceptor, and TokenAuthenticator.
- `core/network` owns Retrofit APIs, DTOs, and remote data sources. Authenticated calls receive `Authorization: Bearer <access>` automatically.
- `core/database` owns Room v7 entities/DAOs with `ownerUserId` / `userId` scoping for private state and durable offline media mutations.
- Owner resources use `/api/v1/me/**` and never accept client-supplied ownership IDs.

## Production operations

- [Production deployment](docs/PRODUCTION_DEPLOYMENT.md)
- [Operations runbook](docs/OPERATIONS_RUNBOOK.md)
- [Media storage](docs/MEDIA_STORAGE.md)
- [Staging provisioning](docs/STAGING.md)

Use the provider-neutral Docker Compose reference in `backend/compose.production.yml` with a private managed PostgreSQL/PostGIS service or the optional self-hosted PostGIS profile. Start from `.env.production.example`; never commit the resulting `.env.production`.

Production also requires a pre-provisioned private S3-compatible bucket and independently tested object backup/restore. The production Compose file forwards the documented `PHOKARTA_MEDIA_*` values; provide credentials through the environment file or platform secret manager.

## Authentication (v0.6)

### Backend

- Spring Security filter chain with JWT bearer authentication.
- BCrypt password hashing (never returned or logged).
- Access tokens: short-lived JWT (`sub` = user UUID), default **15 minutes** (`PHOKARTA_JWT_ACCESS_TTL`).
- Refresh tokens: opaque high-entropy values; only SHA-256 hashes stored in `refresh_sessions`. Default lifetime **30 days** (`PHOKARTA_JWT_REFRESH_TTL`). Rotated on every refresh; reuse of a rotated token revokes the session family.
- External providers are prepared via `auth_identities` (`LOCAL` | `GOOGLE` | `APPLE`) without implementing Google/Apple flows yet.
- JWT signing secret from `PHOKARTA_JWT_SECRET` (min 32 characters). See `backend/.env.example`.

### Auth API

| Method | Path | Auth |
|--------|------|------|
| POST | `/api/v1/auth/register` | public |
| POST | `/api/v1/auth/login` | public (`identifier` = email or username) |
| POST | `/api/v1/auth/refresh` | public (refresh token body) |
| POST | `/api/v1/auth/logout` | public (revokes refresh session) |
| GET | `/api/v1/me` | bearer |
| GET/POST/DELETE | `/api/v1/me/visits`, `/me/saved-places`, `/me/collections` | bearer |
| POST | `/api/v1/me/places/friend-metrics` | bearer (batch viewer-relative friend aggregates for map) |
| POST | `/api/v1/visits` | bearer (userId from principal) |

Public: place discovery/detail, public reviews, PUBLIC collections. Community `averageScore` / `ratingCount` (and minRating / rating sorts) use **PUBLIC Visits only** — FRIENDS/PRIVATE ratings never affect community discovery. Friends discovery (activity, reviews, friends-summary) uses mutual follows + **friend-readable Visits (PUBLIC or FRIENDS)**; PRIVATE is excluded. `GET /me/saved-places` enriches each saved row with the same friend score/count (batch, no N+1). Map keeps public `GET /places/bounds` community-only and enriches via authenticated `POST /me/places/friend-metrics` (same batch aggregate, max 200 IDs). Owner `/me/visits` returns all of the owner's Visits. Collection visibility (PUBLIC / FRIENDS / PRIVATE) is separate from Visit visibility.

### Development demo account

Dev profile seed only (never for production):

- Email: `demo@phokarta.local`
- Username: `emir_demo`
- Password: `DemoPass123!`

### Android session layer

- Tokens stored in EncryptedSharedPreferences (Keystore-backed MasterKey).
- Cold start restores refresh token → refresh access → `GET /me` before entering the main app.
- Onboarding (first install) → auth screens → main app. Logged-out users go to Sign In.
- 401 handling: TokenAuthenticator performs **one** single-flight refresh and retries the request once. Concurrent 401s await the same refresh. Refresh uses a dedicated OkHttp client without the authenticator (no deadlock). Refresh failure clears the local session without wiping owner-scoped Room rows.
- Logout: best-effort backend revoke, always clear local tokens, navigate to auth. Network failure still logs out locally.

### Room ownership

- Visits and collections filter by `userId`.
- Saved places use composite `(ownerUserId, placeId)`.
- Place cache remains shared/global.
- `MIGRATION_3_4` recreates `saved_places` with ownership columns (pre-auth orphaned rows are dropped). No destructive fallback.
- Logout does not delete Room data; the next account cannot see another owner's rows.

## Start the backend

Prerequisites: Java 21, Maven 3.9+, Docker Desktop.

```powershell
cd backend
Copy-Item .env.example .env
docker compose up -d db minio
docker compose run --rm minio-init
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
```

Swagger (dev): `http://localhost:8080/swagger-ui.html` — use Authorize with a Bearer access token.

### Android emulator

```powershell
.\gradlew.bat installDebug
```

Debug API base URL defaults to `http://10.0.2.2:8080/`.

### Physical device over USB

```powershell
adb reverse tcp:8080 tcp:8080
.\gradlew.bat installDebug -PPHOKARTA_API_BASE_URL=http://127.0.0.1:8080/
```

## Google Maps key

Create ignored `secrets.properties`:

```properties
MAPS_API_KEY=your_android_maps_key
```

Never commit keys, `.env`, `local.properties`, or `secrets.properties`.

## Privacy

- Passwords are hashed with BCrypt; never logged or returned.
- Access/refresh tokens are never logged (OkHttp debug uses BASIC level only).
- `privateMemory` appears only on owner Visit DTOs; public review endpoints omit the field entirely.
- Saved places are private to the authenticated owner.

## Future (deferred)

- Google Sign-In / Apple Sign-In via `auth_identities`
- Password reset and email verification delivery
- Redis-backed auth rate limiting for multi-node production
- Media malware scanning, thumbnails/transcoding, CDN integration, and distributed orphan-cleanup coordination

## Build and test

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
cd backend
mvn test
```
