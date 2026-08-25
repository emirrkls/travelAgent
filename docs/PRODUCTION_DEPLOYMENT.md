# Production deployment

This is a provider-neutral, single-node reference deployment. Adapt availability, networking, backups, and observability to the selected provider before accepting production traffic. Closed-beta staging uses the same artifacts with `APP_ENVIRONMENT=staging`; see [Staging provisioning](STAGING.md).

## Topology

Internet traffic reaches Caddy on TCP 80/443 and UDP 443. Caddy terminates HTTPS and proxies to the backend over a private Compose network. The backend reaches either:

- the optional PostGIS 16 container on an internal-only network and durable `postgres-data` volume; or
- an external managed PostgreSQL service through `PHOKARTA_DB_URL`.

The backend also reaches a private S3-compatible object-storage bucket directly for metadata checks, deletion, and signed URL generation. Android uploads and reads image bytes directly against the provider using short-lived signed URLs; bytes do not traverse Caddy or the backend.

The backend and database have no host-published ports. Caddy exposes only the minimal Actuator liveness and database-aware readiness probes as `/health/live` and `/health/ready`. Object storage is not currently part of readiness, so a green readiness response does not prove media health. Actuator and Prometheus remain on private port 8081 and Caddy has no public Prometheus route. Caddy and application logs go to stdout/stderr with Docker rotation.

## Prerequisites and sizing

- Linux host with Docker Engine and the Compose plugin
- DNS A/AAAA records for the API hostname
- inbound firewall: TCP 22 from trusted administration addresses, TCP 80/443 and UDP 443 from the internet; deny database, backend, and metrics ports
- outbound DNS, HTTPS/ACME, registry, managed-database, and object-storage access as required
- initial single-node estimate: 2 vCPU, 4 GB RAM, and 40 GB SSD for a small workload; measure heap, connection count, query latency, database size, object count, bucket bytes, and media traffic before resizing
- synchronized UTC clock (NTP); containers and JVM are configured for UTC

Managed Visit images are private objects described by PostgreSQL metadata. Legacy Visit photo URLs and place cover/photo URL fields remain for compatibility, but new Visit writes use managed `mediaIds`. Do not place image binaries in the application container filesystem. See [Media storage](MEDIA_STORAGE.md).

## Prepare configuration

```sh
cp .env.production.example .env.production
chmod 600 .env.production
```

Replace every `CHANGE_ME`. Generate independent random database, JWT, and (when static credentials are required) storage credentials. Set `PHOKARTA_CORS_ALLOWED_ORIGINS` to explicit HTTPS browser origins (no wildcard with credentials). Native Android direct uploads do not require CORS. `APP_ENVIRONMENT` is required and `PHOKARTA_RELEASE` should identify the immutable image SHA in structured logs.

Use an immutable image reference, preferably a registry digest or a tag containing the source SHA:

```dotenv
PHOKARTA_IMAGE=registry.example.com/phokarta/backend:sha-0123456789abcdef
```

Never deploy `latest`. Record source SHA, image digest, migration set, operator, and deployment time.

## Database choices

### Optional self-hosted PostGIS

The database profile is explicit:

```sh
cd backend
docker compose --env-file ../.env.production -f compose.production.yml \
  --profile self-hosted-db up -d
```

The named volume survives container replacement. A Compose volume is not a backup; copy tested backups to encrypted off-host storage.

### Managed PostgreSQL

Set `PHOKARTA_DB_URL` to the provider JDBC endpoint and omit `--profile self-hosted-db`:

```dotenv
PHOKARTA_DB_URL=jdbc:postgresql://db.example.com:5432/phokarta?sslmode=require
```

Use the provider's CA-verifying TLS mode when available (`verify-full` with a trusted root is stronger than `require`). Restrict ingress to the application network. Enable PostGIS before first application startup if the app role cannot create extensions.

Use separate roles where the provider permits:

- migration owner: temporary deployment use; schema DDL and Flyway history
- application role: connect, schema usage, and required table/sequence DML only
- backup role: read access needed by `pg_dump`

The current application runs Flyway at startup using its datasource credentials, so that role presently needs migration privileges. Moving migrations to a separate release job requires an application change/deployment procedure and must be tested first.

## Object storage

Provision the bucket before application startup:

- keep it private and block all public access;
- grant the application least-privilege access to the configured bucket/prefix for signed PUT/GET generation, `HEAD`, and delete;
- select and document provider durability/availability, encryption, retention, and restore capabilities;
- enable versioning when compatible with privacy retention and cost requirements, with lifecycle rules for noncurrent versions;
- choose `PHOKARTA_MEDIA_REGION`, bucket, endpoint, and path-style settings for the provider;
- leave `PHOKARTA_MEDIA_ENDPOINT` blank for AWS S3; compatible providers require an absolute HTTPS endpoint;
- set both static access/secret keys or leave both blank to use the AWS SDK default credential chain/workload identity;
- keep host/container clocks synchronized because signed URLs are time-sensitive.

The checked-in `compose.production.yml` forwards the documented `PHOKARTA_MEDIA_*` values into the backend container. Supply them through the environment file or platform secret manager, then verify the Java process received the names without printing values. Do not bake credentials into an image.

Rotate storage credentials by issuing a new credential, deploying it, verifying intent/PUT/confirm/read/cleanup, and only then revoking the old credential. Prefer workload identity or overlapping credentials so rotation does not interrupt signed URL creation.

`pg_dump` backs up media rows and object keys, not object bytes. Configure and test provider-side object backup/replication or self-hosted volume backup independently, preferably encrypted and off-host. Coordinate database and bucket recovery points and test reconciliation for database rows with missing objects and provider objects with no row.

## Flyway policy

Production loads only `classpath:db/migration/schema`; never enable the `dev` profile. `V1` executes `CREATE EXTENSION postgis`, which may require provider administration.

- Treat every applied migration as immutable; never edit or renumber it.
- Add a new migration to correct an old one.
- Test migrations against a recent restored production copy before deployment.
- Prefer expand/contract: add nullable/new structures, deploy compatible code, backfill, then remove old structures in a later release.
- Back up and verify recoverability before risky DDL.
- Do not use `flyway repair` as a routine fix; investigate checksum/history differences and preserve evidence.

## Deploy

1. Confirm database and object backup/restore-drill status, free disk, database and storage health, private bucket policy, DNS, secrets, image digest, and migration review.
2. Pull the immutable image:

   ```sh
   cd backend
   docker compose --env-file ../.env.production -f compose.production.yml pull
   ```

3. Start managed-DB mode:

   ```sh
   docker compose --env-file ../.env.production -f compose.production.yml up -d --no-build
   ```

   For self-hosted DB, add `--profile self-hosted-db`.

4. Watch startup and Flyway output without printing the environment:

   ```sh
   docker compose --env-file ../.env.production -f compose.production.yml logs -f backend caddy
   ```

5. Smoke test:

   ```sh
   curl --fail --silent --show-error https://api.example.com/health/live
   curl --fail --silent --show-error https://api.example.com/health/ready
   curl --fail --silent --show-error 'https://api.example.com/api/v1/places?size=1'
   ```

6. Verify TLS, expected security headers, login/refresh/logout, an authenticated read, latency, errors, database connections, and storage-provider health. In staging, verify the complete media intent, direct PUT, confirm, Visit attach, and authorized signed-read path. Run write-path smoke tests in production only when explicitly approved.

## Rollback truth

A binary rollback is safe only while the database remains compatible with the previous binary. Flyway does not automatically reverse migrations. Forward-fix is normally safer. Restoring a database is a separate, data-destructive incident action that loses writes after the backup and must not be presented as an ordinary application rollback.

Before rollout, explicitly identify:

- previous image digest;
- whether new and old binaries can both use the expanded schema;
- point of no return for destructive migrations/backfills;
- recovery point and approved data-loss window.

## Deployment checklist

- [ ] Reviewed immutable image SHA/digest and SBOM/vulnerability results
- [ ] Replaced placeholders; secrets are outside Git with restricted permissions
- [ ] Confirmed managed DB TLS, PostGIS, role privileges, and network allowlist
- [ ] Provisioned a private media bucket and verified least-privilege storage access
- [ ] Confirmed media endpoint/region/path-style settings and credential rotation plan
- [ ] Tested migrations plus database and object restore drills on recent backups
- [ ] Confirmed 7 daily/4 weekly off-host database and object backup policy
- [ ] Documented storage durability, versioning/lifecycle, RPO, and RTO
- [ ] Checked disk, memory, certificate, DNS, and UTC/NTP
- [ ] Confirmed firewall exposes only SSH, HTTP, and HTTPS
- [ ] Captured pre-deploy metrics and rollback compatibility
- [ ] Passed readiness and application smoke tests
- [ ] Recorded image digest, migrations, time, operator, and outcome
