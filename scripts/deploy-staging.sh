#!/usr/bin/env sh
# Staging deploy helper. Does not create cloud resources, prune volumes, or restore dumps.
# Copy .env.staging.example to a chmod 600 env file first.
# Usage: scripts/deploy-staging.sh [--backup] [--profile self-hosted-db] ENV_FILE
set -eu

umask 077

BACKUP=0
COMPOSE_PROFILE=""
ENV_FILE=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --backup)
      BACKUP=1
      shift
      ;;
    --profile)
      [ "$#" -ge 2 ] || { echo "missing --profile value" >&2; exit 1; }
      COMPOSE_PROFILE="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "unknown option: $1" >&2
      exit 1
      ;;
    *)
      ENV_FILE="$1"
      shift
      break
      ;;
  esac
done

[ -n "$ENV_FILE" ] || {
  echo "usage: $0 [--backup] [--profile self-hosted-db] ENV_FILE" >&2
  exit 1
}
[ -r "$ENV_FILE" ] || {
  echo "env file is not readable" >&2
  exit 1
}

repo_root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
compose_file="$repo_root/backend/compose.production.yml"
[ -f "$compose_file" ] || {
  echo "missing $compose_file" >&2
  exit 1
}

required_names="APP_ENVIRONMENT SPRING_PROFILES_ACTIVE PHOKARTA_IMAGE PHOKARTA_RELEASE PHOKARTA_DOMAIN ACME_EMAIL PHOKARTA_DB_URL PHOKARTA_DB_USER PHOKARTA_DB_PASSWORD PHOKARTA_JWT_SECRET PHOKARTA_CORS_ALLOWED_ORIGINS PHOKARTA_MEDIA_BUCKET PHOKARTA_MEDIA_REGION"

missing=0
for name in $required_names; do
  value="$(awk -F= -v key="$name" '
    $1 == key {
      sub(/^[^=]+=/, "")
      print
      exit
    }
  ' "$ENV_FILE")"
  if [ -z "$value" ]; then
    echo "missing required variable: $name" >&2
    missing=1
  fi
done
[ "$missing" -eq 0 ] || exit 1

app_environment="$(awk -F= '$1=="APP_ENVIRONMENT"{sub(/^[^=]+=/,""); print; exit}' "$ENV_FILE")"
spring_profiles="$(awk -F= '$1=="SPRING_PROFILES_ACTIVE"{sub(/^[^=]+=/,""); print; exit}' "$ENV_FILE")"
image="$(awk -F= '$1=="PHOKARTA_IMAGE"{sub(/^[^=]+=/,""); print; exit}' "$ENV_FILE")"
domain="$(awk -F= '$1=="PHOKARTA_DOMAIN"{sub(/^[^=]+=/,""); print; exit}' "$ENV_FILE")"

[ "$app_environment" = "staging" ] || {
  echo "APP_ENVIRONMENT must be staging" >&2
  exit 1
}
[ "$spring_profiles" = "prod" ] || {
  echo "SPRING_PROFILES_ACTIVE must be prod" >&2
  exit 1
}
case "$image" in
  *latest*)
    echo "PHOKARTA_IMAGE must not use latest" >&2
    exit 1
    ;;
esac

compose() {
  if [ -n "$COMPOSE_PROFILE" ]; then
    docker compose --env-file "$ENV_FILE" -f "$compose_file" --profile "$COMPOSE_PROFILE" "$@"
  else
    docker compose --env-file "$ENV_FILE" -f "$compose_file" "$@"
  fi
}

if [ "$BACKUP" -eq 1 ]; then
  command -v pg_dump >/dev/null 2>&1 || {
    echo "pg_dump is required for --backup" >&2
    exit 1
  }
  echo "Taking pre-deploy backup"
  sh "$repo_root/scripts/backup-postgres.sh"
fi

echo "Starting staging compose"
compose up -d --no-build

echo "Waiting for backend readiness"
ready=0
i=0
while [ "$i" -lt 40 ]; do
  if compose exec -T backend wget -q -T 5 -O /dev/null 'http://127.0.0.1:8081/actuator/health/readiness'; then
    ready=1
    break
  fi
  i=$((i + 1))
  sleep 3
done
[ "$ready" -eq 1 ] || {
  echo "backend readiness timed out" >&2
  exit 1
}

echo "Smoke checking public health"
curl --fail --silent --show-error "https://${domain}/health/live" >/dev/null
curl --fail --silent --show-error "https://${domain}/health/ready" >/dev/null
curl --fail --silent --show-error "https://${domain}/api/v1/places?size=1" >/dev/null

echo "Staging deploy smoke passed"
