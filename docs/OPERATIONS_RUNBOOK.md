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
- TLS certificate expiry below 21 days: warn; below 7 days: page

Monitor volume growth, inode use, WAL, database bloat, Docker JSON log use, Caddy certificate storage, backup storage, JVM memory/GC, query latency, connection saturation, 4xx/5xx rates, and container restarts. Log rotation limits local files but is not durable log retention.

## Logs and privacy

Applications and Caddy write to stdout/stderr. Use:

```sh
cd backend
docker compose --env-file ../.env.production -f compose.production.yml \
  logs --since=30m backend caddy
```

Ship structured logs to access-controlled centralized storage with UTC timestamps, retention, and alerts. Never log authorization headers, JWTs, refresh tokens, passwords, private memories, precise private locations, full request bodies, or database URLs containing credentials. Treat user IDs, IP addresses, search text, and photo URLs as personal data. Restrict access and document deletion/retention policy.

## Backup policy

Recommended minimum: retain 7 daily and 4 weekly encrypted backups off-host, subject to legal/privacy requirements and recovery objectives. Provider snapshots complement but do not replace logical backups and restore tests. Record checksum, size, start/end time, PostgreSQL version, encryption/storage location, and result.

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

## Routine deploy and smoke

Follow [Production deployment](PRODUCTION_DEPLOYMENT.md). During rollout:

1. confirm recent backup and restore drill;
2. record current and target image digests;
3. pull and start the target;
4. watch Flyway and startup logs;
5. check live, ready, public place listing, authentication, and one owner-scoped workflow;
6. watch errors, latency, memory, connections, disk, and restarts;
7. record outcome.

## Incident: readiness failure

1. Check liveness. If both fail, inspect Caddy/container state, DNS, certificate, host resources, and ports 80/443.
2. If live passes and ready fails, inspect backend logs/status and database reachability.
3. Check database TLS/allowlist, credentials, connection limit, locks, disk, PostGIS, and query latency.
4. Confirm the deployed image and environment names without printing secret values.
5. Stop repeated restarts if they amplify load; preserve logs and timestamps.
6. Restore traffic only after readiness and a representative query pass.

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
- ACME email/domain changes: validate DNS and certificate issuance before removing the prior route.

Never reuse secrets across environments. Audit access. Managed DB connections must validate TLS per provider guidance. Keep application privileges least-privileged while accounting for the current startup-Flyway requirement.

## Capacity and maintenance

- Maintain at least 20% free disk and enough temporary space for backup/restore.
- Patch host, Docker, Caddy, JRE base image, PostgreSQL, and PostGIS on a tested schedule.
- Use immutable image SHA tags/digests and retain the previous compatible image.
- Review backup age daily and restore-drill evidence monthly.
- Verify UTC/NTP after host changes.
- For self-hosted PostgreSQL, plan vacuum/analyze, WAL growth, major-version upgrade, and volume recovery; Compose restart is not high availability.

## Incident checklist

- [ ] Declared impact, start time in UTC, and incident owner
- [ ] Preserved logs, image digest, migration history, and recent changes
- [ ] Checked live/ready, resources, disk, network, TLS, and database
- [ ] Avoided printing or copying secrets/private payloads
- [ ] Chosen forward fix, compatible binary rollback, or approved DB recovery
- [ ] Completed smoke tests and monitoring observation
- [ ] Rotated exposed credentials and revoked old access if needed
- [ ] Documented timeline, root cause, data impact, and follow-up actions
