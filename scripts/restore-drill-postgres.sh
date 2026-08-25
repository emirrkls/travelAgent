#!/usr/bin/env sh
set -eu

umask 077

: "${RESTORE_FILE:?set RESTORE_FILE to a custom-format dump}"
: "${PGHOST:?set PGHOST to the PostgreSQL host}"
: "${PHOKARTA_DB_USER:?set PHOKARTA_DB_USER}"
: "${PHOKARTA_DB_PASSWORD:?set PHOKARTA_DB_PASSWORD}"

[ -r "$RESTORE_FILE" ] || {
  echo "Restore file is not readable: $RESTORE_FILE" >&2
  exit 1
}

for tool in createdb dropdb pg_restore psql; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "$tool is required" >&2
    exit 1
  }
done

PGPORT="${PGPORT:-5432}"
PGSSLMODE="${PGSSLMODE:-prefer}"
PGMAINTENANCE_DB="${PGMAINTENANCE_DB:-postgres}"
drill_db="${RESTORE_DATABASE_PREFIX:-phokarta_restore_drill}_$(date -u '+%Y%m%dT%H%M%SZ')_$$"
created=0

export PGPORT PGSSLMODE
export PGUSER="$PHOKARTA_DB_USER"
export PGPASSWORD="$PHOKARTA_DB_PASSWORD"

cleanup() {
  if [ "$created" -eq 1 ] && [ "${RESTORE_KEEP_DATABASE:-0}" != "1" ]; then
    dropdb --maintenance-db="$PGMAINTENANCE_DB" --if-exists "$drill_db"
    echo "Temporary drill database removed: $drill_db"
  fi
}
trap cleanup EXIT HUP INT TERM

createdb --maintenance-db="$PGMAINTENANCE_DB" "$drill_db"
created=1
echo "Created temporary drill database: $drill_db"

pg_restore \
  --dbname="$drill_db" \
  --exit-on-error \
  --no-owner \
  --no-privileges \
  "$RESTORE_FILE"

psql --dbname="$drill_db" --no-psqlrc --set=ON_ERROR_STOP=1 --tuples-only <<'SQL'
SELECT extversion FROM pg_extension WHERE extname = 'postgis';
SELECT count(*) FROM flyway_schema_history WHERE success;
SELECT json_build_object(
  'users', (SELECT count(*) FROM users),
  'places', (SELECT count(*) FROM places),
  'visits', (SELECT count(*) FROM visits)
);
SQL

echo "Restore drill passed: $drill_db"
if [ "${RESTORE_KEEP_DATABASE:-0}" = "1" ]; then
  echo "Temporary drill database retained by request: $drill_db"
fi
