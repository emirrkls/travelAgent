# Operations runbook

## Service objectives and health

- `GET /health/live`: Caddy proxies the backend Actuator liveness probe. A 200 proves the process is accepting requests, not that the database is usable.
- `GET /health/ready`: Caddy proxies the Actuator readiness group, which includes database health.
- Docker backend health uses the private readiness endpoint on port 8081.
- Prometheus is available only at the backend's private management port (`:8081/actuator/prometheus`). Scrape it from the internal network with access controls; never add a public Caddy route.

Suggested starting alerts, to tune from measured baselines:

- readiness fails twice over 2 minutes: page
- 5xx rate above 2% for 5 minutes: page; above 0.5% for 15 minutes: ticket
- p95 API latency above 1 second for 10 minutes: warn
- host or database disk above 80%: warn; above 90%: page
- memory above 85% for 10 minutes, OOM/restart count above zero, or restart loop: page
- database connections above 80% of limit, replication/backup failure, or backup older than 26 hours: page
- media upload-intent/confirm storage errors above baseline, orphan cleanup failures, or provider availability alarm: page
- unexpected provider object-count or bucket-byte growth: warn and investigate orphan cleanup/abuse
- object backup/replication failure or object recovery point outside the approved RPO: page
- TLS certificate expiry below 21 days: warn; below 7 days: page

Monitor volume growth, inode use, WAL, database bloat, Docker JSON log use, Caddy certificate storage, backup storage, JVM memory/GC, query latency, connection saturation, 4xx/5xx rates, and container restarts. For media, monitor provider-side object count, total bucket bytes, request/error/throttle rates, latency, replication/backup status, and lifecycle/version growth. Compare object growth with attached and expiring `media_assets` rows. Log rotation limits local files but is not durable log retention.

## Logs and privacy

Applications and Caddy write to stdout/stderr. Use:

```sh
cd backend
docker compose --env-file ../.env.production -f compose.production.yml \
  logs --since=30m backend caddy
```

Ship structured logs to access-controlled centralized storage with UTC timestamps, retention, and alerts. Never log authorization headers, JWTs, refresh tokens, passwords, private memories, precise private locations, full request bodies, database URLs containing credentials, storage access/secret keys, or signed upload/read URLs. Treat the entire query string of an object URL as secret and redact it, including every `X-Amz-*` signature parameter. Treat user IDs, IP addresses, search text, media object keys, and photo URLs as personal data. Restrict access and document deletion/retention policy. Disable URL/query capture in proxies, tracing, analytics, crash reporting, and support tooling before enabling media traffic.

## Backup policy

Recommended minimum: retain 7 daily and 4 weekly encrypted backups off-host, subject to legal/privacy requirements and recovery objectives. Provider snapshots complement but do not replace logical backups and restore tests. Record checksum, size, start/end time, PostgreSQL version, encryption/storage location, and result.

PostgreSQL dumps contain media metadata and object keys only; **they do not contain image bytes**. A complete recovery plan separately protects the private object bucket through managed-provider durability/backup/replication or tested self-hosted storage backups. Record object recovery points and test coordinated database/object restores. Bucket versioning is recommended where retention, privacy deletion, and cost policies permit, with lifecycle rules for noncurrent versions.

The script requires PostgreSQL client tools and standard network access to the database:

```sh
export PGHOST=db.example.com
export PGPORT=5432
export PGSSLMODE=verify-full
export POSTGRES_DB=phokarta
export PHOKARTA_DB_USER=backup_role
export PHOKARTA_DB_PASSWORD='from-secret-manager'
export BACKUP_DIR=/srv/phokarta-backups
sh ./scripts/backup-postgres.sh
unset PHOKARTA_DB_PASSWORD
```

`BACKUP_RETENTION_DAYS` is optional. Retention deletes only dumps with this script's sidecar marker; still use a dedicated backup directory. Prefer lifecycle rules in encrypted off-host storage for daily/weekly retention. Never paste secret-bearing command output into tickets.

## Restore drill

Run at least monthly and after PostgreSQL/PostGIS upgrades. Use an isolated server or cluster with enough free space. The drill creates a unique temporary database, restores the custom dump, verifies PostGIS and successful Flyway rows, then drops only that generated database.

```sh
export RESTORE_FILE=/srv/phokarta-backups/phokarta-YYYYMMDDTHHMMSSZ.dump
export PGHOST=restore-db.example.com
export PGSSLMODE=verify-full
export PHOKARTA_DB_USER=restore_operator
export PHOKARTA_DB_PASSWORD='from-secret-manager'
export PGMAINTENANCE_DB=postgres
sh ./scripts/restore-drill-postgres.sh
unset PHOKARTA_DB_PASSWORD
```

Set `RESTORE_KEEP_DATABASE=1` only when inspection is required, then remove the generated `phokarta_restore_drill_*` database manually. Record elapsed time and verify representative row counts, geospatial queries, authentication data, and application startup against the restored database. Never restore over production during a drill.

The database drill alone does not validate media recovery. In a separate isolated drill, restore or mount the corresponding object backup, compare a sample of `media_assets.storage_key` values with provider objects, verify authorized reads, and report missing and unreferenced objects without logging signed URLs.

## Routine deploy and smoke

Follow [Production deployment](PRODUCTION_DEPLOYMENT.md). During rollout:

1. confirm recent backup and restore drill;
2. record current and target image digests;
3. pull and start the target;
4. watch Flyway and startup logs;
5. check live, ready, public place listing, authentication, and one owner-scoped workflow;
6. check provider health and, in staging, a media intent/PUT/confirm/attach/read workflow;
7. watch errors, latency, memory, connections, disk, object count/bucket bytes, and restarts;
8. record outcome.

## Incident: readiness failure

1. Check liveness. If both fail, inspect Caddy/container state, DNS, certificate, host resources, and ports 80/443.
2. If live passes and ready fails, inspect backend logs/status and database reachability.
3. Check database TLS/allowlist, credentials, connection limit, locks, disk, PostGIS, and query latency.
4. Confirm the deployed image and environment names without printing secret values.
5. Stop repeated restarts if they amplify load; preserve logs and timestamps.
6. Restore traffic only after readiness and a representative query pass.

Readiness includes the database but not object storage. If ordinary API reads pass while media operations fail, follow the media incident procedure rather than treating green readiness as proof that storage works.

## Incident: media storage or signing failure

1. Determine which phase fails: intent signing, direct PUT, confirm `HEAD`, Visit response signing, access refresh, or cleanup delete.
2. Check provider status, DNS/TLS, endpoint and region, path-style setting, bucket existence/private policy, credential validity/permissions, throttling/quota, and UTC/NTP drift. Confirm configuration names without printing values.
3. Check `phokarta.media.upload_intent`, `phokarta.media.confirm`, and `phokarta.media.cleanup` outcome counters and correlate UTC timestamps with provider request metrics.
4. Preserve HTTP status/error class, media ID, and request time. Do not preserve signed URLs, signed headers, query strings, credentials, or image bytes in tickets/logs.
5. Do not make the bucket public or proxy bytes through Caddy as a workaround. Existing signed URLs may work until expiry; generation and confirmation return 503 on storage failures.
6. Restore provider access, then verify intent, direct PUT, confirm, attach, and authorized/unauthorized reads in staging. Run a production write only with explicit incident approval.
7. If credentials may be exposed, rotate using overlap where possible and revoke the old credential after the new path is verified.

## Incident: orphan accumulation or cleanup failure

1. Confirm the scheduler is enabled and progressing. Cleanup uses bounded, leased `DELETING` claims so overlapping runs remain idempotent.
2. Check cleanup `failed` outcomes, provider delete errors, permission changes, throttling, and clock drift.
3. Query counts of expired `PENDING_UPLOAD`/`READY` rows and leased `DELETING` rows, then compare with provider object count/bytes. The default expiry is 48 hours, interval 1 hour, and batch size 100.
4. Cleanup commits `DELETING` before provider deletion, then removes the database row. If provider deletion or finalization fails, retain the unattachable row for retry; do not manually restore it to `READY`.
5. Never bulk-delete keys based only on age. Exclude `ATTACHED` assets and verify bucket/prefix and ownership. Take/export an inventory before approved manual remediation.
6. If backlog exceeds normal batch throughput, temporarily tune interval/batch only after provider limits and database load are reviewed. Observe failure rate and object count until the backlog clears.

## Incident: missing media object

1. Record media ID, owner/Visit relationship, state, storage key, first observed UTC time, and affected audience without recording a signed URL.
2. Use provider metadata/inventory to verify whether the exact key exists and whether it was deleted, versioned, transitioned, quarantined, or inaccessible. A failed signed GET can also be expiry, clock, permission, or provider failure.
3. If the database row is `PENDING_UPLOAD`, a missing object is expected before PUT and confirmation returns validation failure. For `READY` or `ATTACHED`, treat absence as data loss.
4. Check audit logs, cleanup metrics, lifecycle rules, credential activity, deployment changes, and object/database restore history. Cleanup should never select `ATTACHED`.
5. Restore the exact object/version from provider backup when available and verify byte size/content type plus authorized reads. Do not silently attach a different object to the key.
6. If unrecoverable, preserve evidence, assess affected users/Visits, follow the privacy/incident notification policy, and repair metadata/UI only through an approved data-correction plan.

## Signed URL exposure

1. Revoke or rotate storage credentials if exposure scope warrants it; already issued signed URLs generally remain usable until their short TTL expires.
2. Remove/redact URLs from logs, traces, analytics, crash reports, tickets, and caches while preserving an access-controlled audit trail of the redaction.
3. Determine operation type, object scope, TTL, access evidence, and whether the object was read or overwritten.
4. Do not paste a URL into diagnostic tools. Reproduce with a newly issued authorization after logging controls are fixed.

## Incident: Flyway/startup failure

1. Keep the failed instance out of traffic and preserve complete startup/Flyway logs.
2. Inspect `flyway_schema_history`, database locks, privileges, extension availability, checksum mismatch, and free disk.
3. Determine whether the migration committed, partially changed data outside a transaction, or never ran.
4. Do not edit an applied migration, delete history rows, or run `repair` reflexively.
5. Prefer a reviewed forward-fix migration. Roll back the binary only if its schema compatibility is proven.
6. If destructive recovery is approved, state the recovery point and expected lost writes before restoring.

## Database and binary rollback

Binary rollback:

```sh
# Set PHOKARTA_IMAGE to the recorded previous immutable digest, then:
cd backend
docker compose --env-file ../.env.production -f compose.production.yml up -d --no-build
```

This does not roll back schema. Database point-in-time recovery or dump restore is a separate incident procedure requiring authorization, traffic quiescence, evidence preservation, and validation. Use expand/contract migrations so old and new binaries overlap safely.

## Secrets and rotation

Store production secrets in the provider secret manager or a root-readable file outside Git. Rotate after suspected disclosure, staff/access changes, and on the organization's schedule.

- Database password: create/validate a new credential, update the app, restart, verify, then revoke the old credential.
- JWT secret: changing it immediately invalidates all access tokens; coordinate a forced re-login. Existing opaque refresh sessions are database-backed, but test complete behavior before rotation.
- Object-storage credential: create a least-privilege replacement, deploy and verify sign/PUT/HEAD/read/delete, then revoke the old credential. Prefer workload identity over long-lived keys.
- ACME email/domain changes: validate DNS and certificate issuance before removing the prior route.

Never reuse secrets across environments. Audit access. Managed DB connections must validate TLS per provider guidance. Keep application privileges least-privileged while accounting for the current startup-Flyway requirement.

## Capacity and maintenance

- Maintain at least 20% free disk and enough temporary space for backup/restore.
- Patch host, Docker, Caddy, JRE base image, PostgreSQL, and PostGIS on a tested schedule.
- Use immutable image SHA tags/digests and retain the previous compatible image.
- Review backup age daily and restore-drill evidence monthly.
- Review object count, bucket bytes, lifecycle/version growth, orphan backlog, and object backup age daily.
- Verify UTC/NTP after host changes.
- For self-hosted PostgreSQL, plan vacuum/analyze, WAL growth, major-version upgrade, and volume recovery; Compose restart is not high availability.
- For self-hosted object storage, back up data off-host and test recovery; a Docker named volume is not high availability or a backup.

## Incident checklist

- [ ] Declared impact, start time in UTC, and incident owner
- [ ] Preserved logs, image digest, migration history, and recent changes
- [ ] Checked live/ready, resources, disk, network, TLS, and database
- [ ] Avoided printing or copying secrets/private payloads
- [ ] Chosen forward fix, compatible binary rollback, or approved DB recovery
- [ ] Completed smoke tests and monitoring observation
- [ ] Rotated exposed credentials and revoked old access if needed
- [ ] Documented timeline, root cause, data impact, and follow-up actions
