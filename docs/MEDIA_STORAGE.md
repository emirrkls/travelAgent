# Media storage

## Architecture and data flow

Visit images use a provider-neutral `ObjectStorageService` implemented with the AWS S3 SDK. It supports AWS S3 and compatible providers such as MinIO through configurable region, endpoint, credentials, and path-style addressing. The bucket must be private: objects are never public and the backend grants short-lived, per-object signed URLs only after authorization.

The native Android flow is:

1. The picker URI is copied into app-private `filesDir/visit-media/<owner>/<clientMediaId>.<ext>`. The app records metadata and a `LOCAL_ONLY` Room row.
2. Publishing moves the draft photo rows into the durable pending mutation in the same Room transaction, then schedules synchronization.
3. The worker sends the metadata and stable `clientMediaId` to `POST /api/v1/me/media/upload-intents`.
4. The backend creates or reuses a `PENDING_UPLOAD` asset and returns a signed PUT URL plus every required signed header.
5. Android streams the app-private file directly to object storage. Image bytes do not pass through Spring or Caddy.
6. Android calls `POST /api/v1/me/media/{mediaId}/confirm`. The backend performs `HEAD`, verifies exact byte length and, when supplied by the provider, exact content type, then marks the asset `READY`.
7. Android creates the Visit with ordered `mediaIds`. In the Visit transaction, the backend verifies every asset is owned by the caller, `READY`, unique, and unattached, creates ordered `visit_media` rows, and marks each asset `ATTACHED`.
8. Visit responses contain media descriptors with short-lived signed GET URLs. `GET /api/v1/media/{mediaId}/access` refreshes one URL after applying the current Visit visibility rule.

Native Android does not need browser CORS configuration for either direct PUTs or signed reads. Caddy's 10 MB request-body limit remains appropriate for the JSON API because image bytes bypass Caddy; the 15 MiB object limit is enforced in the app, backend metadata validation, database constraint, signed PUT content length, and confirmation.

## Limits and object identity

- Maximum size: **15 MiB** (`15 * 1024 * 1024`, 15,728,640 bytes).
- Exact accepted MIME types: `image/jpeg`, `image/png`, `image/webp`.
- Maximum images per Visit: **20**. Nulls and duplicate `mediaIds` are rejected.
- Width and height are optional positive metadata. The backend does not decode the image to verify dimensions or content.
- Object key: `users/<owner-user-uuid>/media/<media-uuid>/original`.
- The bucket name is not part of the persisted key.

The backend identity is the generated `mediaId`; a signed URL is only a temporary access credential. `(owner_user_id, client_media_id)` is unique, so the same `clientMediaId` may be used independently by different users. Reusing it by the same owner with identical MIME type, size, width, and height returns the existing asset and refreshes a PUT authorization while it is pending. Different metadata returns `409`.

An asset can be attached once. Database uniqueness prevents attaching one asset to two Visits, and service checks prevent cross-user attachment. The caller cannot supply an owner ID.

## State and access rules

Normal server states are `PENDING_UPLOAD -> READY -> ATTACHED`. Expired unattached assets enter the internal cleanup state `DELETING`.

- `PENDING_UPLOAD`: intent exists, but the server has not verified an object. It has an orphan expiry.
- `READY`: object metadata passed confirmation, but it is not attached. It remains subject to orphan expiry.
- `ATTACHED`: belongs to exactly one Visit, has no orphan expiry, and reads inherit that Visit's visibility.
- `DELETING`: durably claimed for orphan removal, cannot be attached or read, and remains retryable if provider deletion fails.

Access is exact:

| Server state / Visit visibility | Owner | Mutual friend | Other authenticated user | Anonymous |
|---|---:|---:|---:|---:|
| `PENDING_UPLOAD` | not readable (404) | not readable (404) | not readable (404) | not readable (404) |
| `DELETING` | not readable | not readable | not readable | not readable |
| `READY` and unattached | allowed | forbidden | forbidden | forbidden |
| `ATTACHED` + `PUBLIC` | allowed | allowed | allowed | allowed |
| `ATTACHED` + `FRIENDS` | allowed | allowed | forbidden | forbidden |
| `ATTACHED` + `PRIVATE` | allowed | forbidden | forbidden | forbidden |

`FRIENDS` means mutual follows, not a one-way follow. Missing assets return 404; known but unauthorized non-pending assets return 403. Attachment authorization is checked again when a refreshed read URL is requested.

## APIs and DTOs

All timestamps are UTC ISO-8601 values.

`POST /api/v1/me/media/upload-intents` (bearer required):

```json
{
  "clientMediaId": "uuid",
  "contentType": "image/jpeg",
  "byteSize": 123456,
  "width": 1920,
  "height": 1080
}
```

Response:

```json
{
  "mediaId": "uuid",
  "status": "PENDING_UPLOAD",
  "uploadUrl": "<short-lived signed PUT URL>",
  "requiredHeaders": {
    "Content-Type": "image/jpeg",
    "Content-Length": "123456"
  },
  "expiresAt": "UTC timestamp"
}
```

For an existing `READY` or `ATTACHED` asset, `uploadUrl` and `expiresAt` are null and `requiredHeaders` is empty.

`POST /api/v1/me/media/{mediaId}/confirm` (bearer required) returns:

```json
{"mediaId":"uuid","status":"READY"}
```

Confirmation is owner-only and idempotently returns the current state once no longer pending.

`POST /api/v1/visits` accepts ordered `mediaIds` in addition to the legacy `photos` field. New clients must omit `photos` or send an empty list; non-empty `photos` is rejected as read-only legacy input. Visit media descriptors are:

```json
{
  "id": "uuid",
  "sortOrder": 0,
  "accessUrl": "<short-lived signed GET URL>",
  "accessExpiresAt": "UTC timestamp"
}
```

`GET /api/v1/media/{mediaId}/access` is optionally authenticated and returns `{"url":"...","expiresAt":"..."}` with `Cache-Control: no-store`.

Defaults are a **15 minute upload URL TTL** and **10 minute read URL TTL**. They are authorization lifetimes, not retention periods.

## Idempotency and crash recovery

`clientMediaId` makes intent creation safe to retry after timeout, process death, or a lost response. If the PUT succeeded but confirmation was lost, retrying the intent returns another PUT URL while still pending; a repeated PUT replaces the same key, and confirmation transitions it to `READY`. If confirmation succeeded, retrying the intent returns the same `mediaId` and `READY`.

Visit creation has a separate `clientMutationId`. A retry by the same user with the same canonical payload, including ordered `mediaIds`, returns the original Visit. Reordering or otherwise changing the payload under the same key returns `409`. Visit persistence, attachment, and media state transition share one database transaction, so they commit or roll back together. Android keeps the pending mutation and app-private files until a successful canonical Visit response is stored; it then deletes the mutation transactionally and removes local files afterward. A crash before local file deletion can leave harmless local files; a crash before mutation deletion retries idempotently.

If an intent unexpectedly reports `ATTACHED` before this pending Visit owns it, Android assigns that photo a new `clientMediaId` and retries. Permanent failed mutations can be restored into a draft without copying the files; the same owner-scoped paths and upload state are transferred. Deleting a failed mutation deletes its owned local files.

## Android Room v7 and local files

Room v7 adds owner-scoped `visit_draft_photos`, durable `pending_visit_photos`, and cached `visit_media` descriptors. Draft commit transfers photo metadata from draft to pending rows in one database transaction; files remain in app-private storage so WorkManager can resume after process death or reboot.

Paths are relative and accepted only under the sanitized current owner's `visit-media/<owner>/` directory. Absolute paths, traversal, backslashes, cross-owner access, session/row owner mismatches, missing files, and size changes fail safely. Draft reads, writes, imports, removal, failed-mutation recovery, and pending mutation observation are session/owner scoped. Logout retains Room and local files, but a different account cannot see or use them.

Drafts expire after 30 days. Draft removal and expiry delete local files before deleting their rows. Successful publish and permanent-failure removal also delete files. A startup and 12-hour WorkManager reconciliation deletes **stale** `.part` files and unreferenced files under the private media root after a 5-minute grace period, while retaining draft/pending references for every account. Import, draft file mutation, and reconciliation share one process-local mutex so an in-flight copy cannot be deleted before Room records ownership. Cached signed read URLs may be stored with their expiry as a refreshable display cache; they are never object identity.

## EXIF policy

Android strips JPEG GPS EXIF tags during import before upload. It does not strip non-GPS EXIF, and PNG/WebP metadata is not sanitized. The backend does not rewrite or inspect EXIF. This is a deliberate narrow privacy control, not complete metadata removal.

## Orphan cleanup

Both `PENDING_UPLOAD` and unattached `READY` assets expire **48 hours** after creation/confirmation by default. A scheduled job runs every hour, selects at most **100** expired rows, atomically commits a `DELETING` claim, deletes the provider object, and then deletes the database row. A crash or provider failure leaves an unattachable `DELETING` row for leased retry. `ATTACHED` objects are never selected.

Cleanup claims are atomic and leased, making repeated and overlapping runs idempotent. The current beta still uses the application scheduler rather than a dedicated distributed scheduling product. Monitor `phokarta.media.cleanup` outcomes (`deleted`/`failed`) and provider-side object/byte growth.

Account deletion copies owned storage keys into `account_deletion_media_jobs` before the user and `media_assets` rows disappear, then deletes objects asynchronously. See [Account deletion](ACCOUNT_DELETION.md). Already issued signed read URLs may remain usable until their short TTL expires.

## Signed URL security model

Signed PUT and GET URLs are bearer credentials: anyone holding one can perform the signed operation until expiry. Do not log, persist outside the documented short-lived Android display cache, place in analytics/crash reports, copy to tickets, or share them. Redact the entire query string, especially `X-Amz-*` parameters, from reverse-proxy, client, tracing, and exception logs. Do not key a shared cache by a signed URL or let a CDN cache private responses. The access authorization response is `no-store`; object-provider cache behavior must not be treated as authorization.

Keep the bucket private with public access blocked. The application credential needs only the configured bucket/key prefix operations required for presign, `HEAD`, and delete. Rotate credentials using provider overlap where possible: issue the new credential, deploy and verify intent/confirm/read/delete, then revoke the old one. Clock synchronization is required because signature validity depends on time.

## Local MinIO development

`backend/docker-compose.yml` defines PostGIS, MinIO API on `127.0.0.1:9000`, MinIO console on `127.0.0.1:9001`, a named `phokarta_minio_data` volume, and a one-shot `minio-init` container that creates the configured private bucket.

PowerShell:

```powershell
cd backend
docker compose up -d db minio
docker compose run --rm minio-init
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
```

The dev profile defaults match Compose: endpoint `http://localhost:9000`, bucket `phokarta-local-media`, region `us-east-1`, path-style addressing, and development-only MinIO credentials. To inspect health and logs:

```powershell
docker compose ps
docker compose logs minio minio-init
```

To stop services without deleting data, use `docker compose down`. `docker compose down -v` permanently deletes both local database and MinIO named volumes.

Backend integration tests do **not** use MinIO or live cloud credentials. `mvn verify` uses a PostgreSQL Testcontainer and an in-memory `ObjectStorageService` test double.

## Production configuration

Application binding uses:

- `PHOKARTA_MEDIA_ENABLED` (keep `true` for production managed media)
- `PHOKARTA_MEDIA_BUCKET` (required private bucket)
- `PHOKARTA_MEDIA_REGION` (required)
- `PHOKARTA_MEDIA_ENDPOINT` (blank for AWS; absolute HTTPS endpoint in production; HTTP is dev/test-only)
- `PHOKARTA_MEDIA_PATH_STYLE` (`false` for normal AWS virtual-host style; provider-dependent)
- `PHOKARTA_MEDIA_ACCESS_KEY` and `PHOKARTA_MEDIA_SECRET_KEY` (both set or both blank; blank uses the AWS SDK default credential chain)
- `PHOKARTA_MEDIA_MAX_BYTES`, `PHOKARTA_MEDIA_MAX_PER_VISIT`
- `PHOKARTA_MEDIA_UPLOAD_TTL`, `PHOKARTA_MEDIA_READ_TTL`
- `PHOKARTA_MEDIA_UNATTACHED_TTL`
- `PHOKARTA_MEDIA_CLEANUP_BATCH_SIZE`, `PHOKARTA_MEDIA_CLEANUP_INTERVAL`

The production profile declares media enabled. A production release should keep `PHOKARTA_MEDIA_ENABLED=true` and provision the private bucket before startup. The checked-in production Compose file forwards the documented `PHOKARTA_MEDIA_*` values into the backend container; pass them through the environment file or the platform secret manager without printing their values.

## Backup, durability, and disaster recovery

`pg_dump` contains media metadata, ownership, state, keys, and Visit relations; it contains **no image bytes**. Database-only restore can therefore produce rows whose objects are missing, while bucket-only restore can produce unreferenced objects.

- Managed storage: choose an explicit durability/availability class, enable provider backup or replication appropriate to the RPO/RTO, protect deletion, and test object restore.
- Self-hosted MinIO: back up the data volume or use supported replication/snapshot procedures to encrypted off-host storage. A named Docker volume is not a backup.
- Enable bucket versioning where cost and privacy-retention rules permit. Versioning helps accidental overwrite/delete recovery but requires lifecycle rules and deletion procedures for noncurrent versions.
- Coordinate database and object-storage recovery points as closely as possible. After a restore, reconcile database keys against provider inventory and investigate both missing and unreferenced objects.

Record and test RPO, RTO, retention, encryption, restore permissions, and credential availability. A database backup success must never be reported as a complete media backup.

## Outages and readiness

Storage failures during presign, `HEAD`, or signed-read generation return `503 MEDIA_STORAGE_UNAVAILABLE`; disabled media returns `503 MEDIA_UNAVAILABLE`. Direct PUT failures are classified by Android for retry. Existing API operations that do not need storage can continue.

The production readiness group currently checks application readiness and the database, **not object storage or bucket access**. A green `/health/ready` therefore does not prove media health. Monitor a non-secret synthetic media workflow in staging and provider health/error metrics in production; do not continuously upload production objects from every replica.

## Legacy compatibility and current limitations

Legacy `visits.photos` URL data remains in backend response data under `legacyPhotoUrls` and the deprecated `photos` alias. Room v6 pending URL rows migrate into v7 as `legacyUrl` and the mutation becomes `FAILED_PERMANENT` with `LEGACY_MEDIA_RESELECT_REQUIRED`; Edit & retry preserves the Visit text/ratings and lets the owner remove or reselect the photo instead of resending an untrusted URL. New Visit writes use `mediaIds`. Existing place cover/photo URL fields are separate from managed Visit media.

Genuine current gaps:

- no malware scanning or image-content verification;
- no thumbnail/resize/transcode pipeline;
- no CDN integration;
- no live S3-compatible provider integration test in CI (storage behavior uses a test double);
- no object-storage readiness indicator;
- no complete EXIF stripping and no PNG/WebP metadata sanitization;
