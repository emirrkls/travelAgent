# Staging readiness

Closed-beta staging reuses the production Compose stack, Caddyfile, `prod` Spring profile, backup scripts, and media architecture. Staging is **not** a second application profile. Set `APP_ENVIRONMENT=staging` on the same `prod` artifacts.

**Current status: repository ready; live staging is not provisioned.**

This milestone does not create a VPS, domain, DNS record, TLS certificate, or S3-compatible bucket. Localhost MinIO and a local `mvn` process are not live staging.

Production procedures remain in [Production deployment](PRODUCTION_DEPLOYMENT.md), [Operations runbook](OPERATIONS_RUNBOOK.md), and [Media storage](MEDIA_STORAGE.md).

## Future host needs

A later live host only needs:

- Linux with Docker Engine and the Compose plugin
- a public IPv4 (IPv6 optional)
- a non-production DNS name such as `api-staging.<domain>`
- PostgreSQL 16 / PostGIS (self-hosted Compose profile or managed TLS endpoint)
- a private HTTPS S3-compatible bucket with staging-only credentials

Suggested starting size from production docs: 2 vCPU, 4 GB RAM, 40 GB SSD, current Ubuntu LTS, UTC/NTP. Kubernetes and multiple backend replicas are not required.

Compose, Caddy, and env names are provider-neutral. Do not bind the application to one cloud vendor.

## Future live-staging checklist

Do **not** run this until the operator has supplied real resources. Do not invent a domain.

1. **Create VPS** — Linux host, public IP, Docker Engine + Compose, SSH keys, firewall: 22 (admin), 80, 443 public; 8080, 8081, 5432, metrics closed.
2. **Create DNS record** — A/AAAA for the staging API hostname only. Do not overwrite a production name.
3. **Configure bucket** — private staging bucket, HTTPS endpoint, no public-read, least-privilege keys (`PutObject` / `GetObject` / `HeadObject` / `DeleteObject`). Enable versioning if the provider makes it inexpensive.
4. **Place secrets** — copy [`.env.staging.example`](../.env.staging.example) to `/opt/phokarta/staging/.env.staging`, `chmod 600`, replace every `CHANGE_ME`. Generate staging-only JWT and database passwords. Set `PHOKARTA_DOMAIN`, `ACME_EMAIL`, media endpoint/region/path-style for the chosen provider.
5. **Deploy exact image SHA** — build or pull `phokarta-backend:e6502d7df9cc45193c92496db8ca70d243340f32` (do not use `latest`). `scripts/deploy-staging.sh --profile self-hosted-db /opt/phokarta/staging/.env.staging` (omit the profile when using managed PostgreSQL).
6. **Verify TLS** — publicly trusted certificate for the staging hostname; HTTP redirects to HTTPS; no self-signed staging cert.
7. **Run smoke tests** — `/health/live`, `/health/ready`, public places, auth, PostGIS nearby/bounds, Visit create + idempotency, media intent → direct HTTPS PUT → confirm → attach → signed GET, bucket remains private, backup script.

Android later: `.\gradlew.bat assembleRelease -PPHOKARTA_API_BASE_URL=https://<real-staging-host>/` (trailing slash required). No extra Gradle flavor. No secrets in the APK beyond the public API URL.

## Topology

```
Public Internet
        ↓
DNS A/AAAA for api-staging.<domain>
        ↓
Caddy :80 (ACME HTTP-01) and :443 (HTTPS + HTTP/3)
        ↓
backend :8080 (private Compose network)
        ↓
PostgreSQL 16 / PostGIS (private; self-hosted volume or managed TLS endpoint)

Backend ──HTTPS──► private S3-compatible bucket (presign / HEAD / delete)

Android ──HTTPS──► Caddy/backend  (auth, intent, confirm, Visit)
Android ──HTTPS──► object storage  (direct PUT and signed GET)
```

Publicly exposed: TCP 22 from admin addresses if practical, TCP 80, TCP 443, UDP 443.

Not publicly exposed: backend 8080, management 8081, PostgreSQL 5432, Prometheus, any object-storage admin console.

Object bytes must not traverse Caddy. A green `/health/ready` proves process + database, not bucket health.

## Resource inventory

Fill this table only after real resources exist. Do not invent hostnames.

| Resource | Staging choice |
| --- | --- |
| Compute | *not provisioned* (single Linux VPS/VM, Docker Engine + Compose plugin) |
| Database | *not provisioned* (self-hosted `postgis/postgis:16-3.5` on the VPS is the first-staging default) |
| Object storage | *not provisioned* (private HTTPS S3-compatible bucket; not localhost MinIO) |
| DNS hostname | *not provisioned* (`api-staging.<domain>` or equivalent non-production name) |
| TLS | Caddy automatic certificates via Let's Encrypt |
| Backend image | build or pull immutable tag `e6502d7df9cc45193c92496db8ca70d243340f32` |
| Android API URL | `https://<staging-host>/` via `-PPHOKARTA_API_BASE_URL` |

## Environment

On the future host, keep secrets outside Git:

```sh
umask 077
mkdir -p /opt/phokarta/staging
cp .env.staging.example /opt/phokarta/staging/.env.staging
chmod 600 /opt/phokarta/staging/.env.staging
```

Required values (see the example file):

```dotenv
APP_ENVIRONMENT=staging
SPRING_PROFILES_ACTIVE=prod
PHOKARTA_RELEASE=e6502d7df9cc45193c92496db8ca70d243340f32
PHOKARTA_IMAGE=phokarta-backend:e6502d7df9cc45193c92496db8ca70d243340f32
PHOKARTA_DOMAIN=api-staging.example.com
```

Generate a staging-only JWT secret (≥32 random bytes) and a staging-only database password. Do not reuse the local `backend/.env` values or a future production secret.

`PHOKARTA_CORS_ALLOWED_ORIGINS` must be an explicit HTTPS origin list, never `*`. Native Android does not need CORS. Do not add permissive bucket CORS for Android PUT.

The production profile rejects `prod,dev` together, blank `APP_ENVIRONMENT`, blank datasource credentials, and a non-HTTPS media endpoint. Swagger stays disabled.

The application role currently runs Flyway at startup, so DDL on that role is acceptable for first staging. Do not introduce a separate migration account unless the app is changed to support it.

## Image

No container registry is required. On the staging host, from the media milestone:

```sh
git -C /opt/phokarta/staging/src fetch --all
git -C /opt/phokarta/staging/src checkout e6502d7df9cc45193c92496db8ca70d243340f32
docker build -t phokarta-backend:e6502d7df9cc45193c92496db8ca70d243340f32 \
  /opt/phokarta/staging/src/backend
```

Never deploy `latest`. Record the source SHA in `PHOKARTA_RELEASE` so ECS logs identify `environment=staging` and the release.

## Deploy

```sh
cd /opt/phokarta/staging/src/backend
docker compose --env-file /opt/phokarta/staging/.env.staging \
  -f compose.production.yml --profile self-hosted-db up -d --no-build
```

Omit `--profile self-hosted-db` when using managed PostgreSQL.

Helper: `scripts/deploy-staging.sh /opt/phokarta/staging/.env.staging`. The script validates required names, refuses `latest`, waits for readiness, and curls live/ready/places. Pass `--backup` only when PostgreSQL client tools can reach the staging database. It does not prune volumes or restore dumps.

Expected first boot: Flyway schema `V1`, `V3`, `V5`, `V6`, `V8`, `V9`, `V10` (dev seeds `V2`/`V4`/`V7` are not on the prod classpath), Hibernate `validate`, media config, no demo user `demo@phokarta.local`. After an empty schema is healthy, take a baseline backup, then insert synthetic places with `scripts/staging-seed-places.sql` over an SSH tunnel or the private `db` network. Do not enable the `dev` profile to load demo seed. There is no public Place-create API and no public staging-reset endpoint.

## Android

No extra Gradle flavor is required. When a real hostname exists, build a release-like APK that points at it:

```powershell
.\gradlew.bat assembleRelease -PPHOKARTA_API_BASE_URL=https://api-staging.example.com/
```

Release `PHOKARTA_API_BASE_URL` must be an absolute `https://` URL with a trailing slash. Debug still defaults to `http://10.0.2.2:8080/` and permits cleartext only for `10.0.2.2`, `127.0.0.1`, and `localhost`. Release builds reject HTTP API URLs at configuration time and reject HTTP presigned media URLs at runtime (`INSECURE_UPLOAD_URL`). If the signing keystore is unavailable, a debug-signed APK with the HTTPS property is acceptable for internal validation only.

Use synthetic staging accounts and a harmless tiny JPEG. Do not upload personal photos.

## Schema compatibility / rollback

Current staging schema target is Flyway **V10** (`account_deletion_media_jobs` plus V9 media tables).

`ff83b29` (`feat: harden backend for production operations`) does not map media entities. Extra V9 tables would likely pass Hibernate `validate`, but media APIs, Android direct upload, and Visit `mediaIds` would not work. Do **not** roll staging back to `ff83b29`. The media commit is the minimum compatible deployment baseline.

Rollback only to an image known to include the V9 mappings. Forward-fix is the default. Restoring a dump is a separate destructive incident action and must never target the live staging database during a drill (`scripts/restore-drill-postgres.sh` creates `phokarta_restore_drill_*`).

`pg_dump` does not contain object bytes. Bucket loss is not recovered by database restore.

## Disaster recovery (document only)

- **Host lost:** rebuild host → restore latest database dump → reconnect the existing private bucket → redeploy the immutable image.
- **Database lost:** restore latest dump; reconcile `media_assets.storage_key` against the bucket.
- **Bucket lost:** database restore alone does not restore photos.

Do not simulate destructive loss on the only staging copy.

## Validation (when live)

Run these against the public HTTPS hostname, not localhost:

1. DNS resolves only the staging name; TLS is publicly trusted; HTTP redirects to HTTPS.
2. From off-host: 443 open; 8080/8081/5432 closed; `/actuator/prometheus` not on Caddy.
3. `GET /health/live` and `GET /health/ready` return 200 with no details.
4. Optional: stop DB briefly → live 200, ready 503 → restore → ready 200.
5. Logs: ECS JSON, `environment=staging`, release SHA, request id, method/path/status/duration; no JWT, passwords, `privateMemory`, storage keys, or signed URLs.
6. Register/login/refresh/`GET /me` with a synthetic user.
7. Nearby or bounds returns the seeded PostGIS place.
8. Visit create; same `clientMutationId` + payload returns the same Visit; changed payload returns 409.
9. Media: intent → direct HTTPS PUT to the bucket → confirm READY → Visit attach ATTACHED → signed GET; anonymous raw object denied; PRIVATE denied to anonymous/other user; PUBLIC API access allowed while the unsigned object stays private.
10. Restart backend: data and readiness persist; Flyway does not rewrite data.
11. Database backup with `scripts/backup-postgres.sh` into a host path that is not world-readable.

Caddy 2.10 redacts `Authorization`, `Cookie`, `Set-Cookie`, and `Proxy-Authorization` unless `log_credentials` is enabled. Do not enable it.

If no uptime product exists, monitor `https://<host>/health/ready` from any existing checker. Suggested pages: ready down 2–5 minutes, restart loop, disk > 80%, backup older than 26 hours. Scrape Prometheus only on the private management port (`backend:8081/actuator/prometheus`). Media metric labels are outcome/action only — never userId or mediaId.

## Closed-beta gate

Live staging is ready for closed-beta traffic only when all of the following are true on real infrastructure:

- public HTTPS API
- live PostGIS nearby/bounds
- real private bucket with direct Android PUT/GET
- auth, Visit, Visit idempotency
- PRIVATE/PUBLIC media authorization
- backup taken; secrets not in Git/logs
- private ports remain private
