#!/usr/bin/env bash
# make db-create: create the apps' role and database on a LOCAL Postgres when they are missing (never drops anything),
# add the extensions the migrations need (postgis, citext, pgcrypto), then migrate and seed (MIGRATE=0 skips that).
#
# Where: the compose `postgres` container when it runs, else your own Postgres through psql.
# What: host, port and database from DB_URL; the role and password from DB_USER / DB_PASSWORD — environment first,
# then server/.env, then the defaults (localhost:5432/northline, northline/northline). DB_NAME / DB_OWNER override.
# Who: psql connects as PGUSER (default postgres), which must be a superuser — PostGIS is not a trusted extension.
# Portable: bash 3.2, BSD or GNU userland.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SQL="$ROOT/db/local/create.sql"

# KEY's value from the environment, else server/.env (last assignment wins), else $2
setting() {
  local value="${!1:-}"
  if [ -z "$value" ] && [ -f "$ROOT/server/.env" ]; then
    # as the apps read it (Spring's .env[.properties] import): the text after '=', quotes included
    value="$(sed -n "s/^$1=//p" "$ROOT/server/.env" | tail -n 1)"
  fi
  printf '%s' "${value:-$2}"
}

url="$(setting DB_URL jdbc:postgresql://localhost:5432/northline)"
rest="${url#jdbc:postgresql://}"
hostport="${rest%%/*}"
dbpart="${rest#*/}"
host="${hostport%%:*}"
port=5432
case "$hostport" in *:*) port="${hostport##*:}" ;; esac
db="${DB_NAME:-${dbpart%%\?*}}"
owner="${DB_OWNER:-$(setting DB_USER northline)}"
password="$(setting DB_PASSWORD northline)"

case "$host" in
  localhost | 127.0.0.1 | ::1 | host.docker.internal) ;;
  *) echo "db-create: $host is not a local host — refused (create remote databases with your provider's tools)" >&2; exit 1 ;;
esac

if (cd "$ROOT" && [ -n "$(docker compose ps -q postgres 2>/dev/null)" ]); then
  echo "db-create: '$db' owned by '$owner' in the compose postgres container"
  (cd "$ROOT" && docker compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "${PG_USER:-northline}" -d postgres \
    -v owner="$owner" -v password="$password" -v db="$db" -f - < "$SQL")
else
  command -v psql > /dev/null || { echo "db-create: psql not found (install the PostgreSQL client, or run make standins-up PROFILES=db)" >&2; exit 1; }
  echo "db-create: '$db' owned by '$owner' on $host:$port (as ${PGUSER:-postgres})"
  PGHOST="$host" PGPORT="$port" PGUSER="${PGUSER:-postgres}" \
    psql -q -v ON_ERROR_STOP=1 -d postgres -v owner="$owner" -v password="$password" -v db="$db" -f "$SQL" || {
    echo "db-create: failed. PGUSER must be a superuser (PGPASSWORD for its password); PostGIS must be installed on the server (e.g. postgresql-17-postgis-3, or Homebrew's postgis)." >&2
    exit 1
  }
fi
echo "db-create: role, database and extensions ready"
