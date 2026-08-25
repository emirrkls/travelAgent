#!/usr/bin/env sh
set -eu

umask 077

: "${PGHOST:?set PGHOST to the PostgreSQL host}"
: "${PHOKARTA_DB_USER:?set PHOKARTA_DB_USER}"
: "${PHOKARTA_DB_PASSWORD:?set PHOKARTA_DB_PASSWORD}"

command -v pg_dump >/dev/null 2>&1 || {
  echo "pg_dump is required" >&2
  exit 1
}

PGPORT="${PGPORT:-5432}"
PGDATABASE="${POSTGRES_DB:-phokarta}"
PGSSLMODE="${PGSSLMODE:-prefer}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_PREFIX="${BACKUP_PREFIX:-phokarta}"
timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
backup_path="${BACKUP_DIR}/${BACKUP_PREFIX}-${timestamp}.dump"
temporary_path="${backup_path}.partial"
marker_path="${backup_path}.managed-by-phokarta-backup"

mkdir -p "$BACKUP_DIR"
trap 'rm -f "$temporary_path"' EXIT HUP INT TERM

export PGPORT PGDATABASE PGSSLMODE
export PGUSER="$PHOKARTA_DB_USER"
export PGPASSWORD="$PHOKARTA_DB_PASSWORD"

pg_dump \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-privileges \
  --file="$temporary_path"

mv "$temporary_path" "$backup_path"
: > "$marker_path"
trap - EXIT HUP INT TERM

echo "Backup created: $backup_path"

if [ -n "${BACKUP_RETENTION_DAYS:-}" ]; then
  case "$BACKUP_RETENTION_DAYS" in
    *[!0-9]*|'')
      echo "BACKUP_RETENTION_DAYS must be a non-negative integer" >&2
      exit 1
      ;;
  esac

  # Only remove dumps carrying this script's sidecar marker; unrelated user files are untouched.
  find "$BACKUP_DIR" -type f -name "${BACKUP_PREFIX}-*.dump.managed-by-phokarta-backup" \
    -mtime "+${BACKUP_RETENTION_DAYS}" -exec sh -c '
      for marker do
        dump=${marker%.managed-by-phokarta-backup}
        [ -f "$dump" ] && rm -f -- "$dump"
        rm -f -- "$marker"
      done
    ' sh {} +
fi
