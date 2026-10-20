#!/usr/bin/env bash
# S-150: make backup-codes-reset — every seeded persona's backup codes (scripts/dev/personas.json) unused again, and
# each one's seeded authenticator key back in place (in case Settings › Security replaced it). Local databases only:
# refuses a DB_URL whose host isn't this machine. The database is DB_URL / DB_USER / DB_PASSWORD (environment, else
# server/.env, else localhost:5432/northline), reached through the compose postgres container when it serves that
# port, else with psql. bash 3.2 (macOS), no GNU-only tools.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
. "$ROOT/scripts/local-env.sh"
die() { printf '%s\n' "backup-codes-reset: $*" >&2; exit 1; }

DB_URL=$(appcfg DB_URL jdbc:postgresql://localhost:5432/northline)
DB_USER=$(appcfg DB_USER northline); DB_PASSWORD=$(appcfg DB_PASSWORD northline)
rest="${DB_URL#jdbc:postgresql://}"; hostport="${rest%%/*}"; db="${rest#*/}"; db="${db%%\?*}"
case "$hostport" in *:*) host="${hostport%:*}"; port="${hostport##*:}" ;; *) host="$hostport"; port=5432 ;; esac
case "$host" in
  localhost | 127.0.0.1 | ::1 | '[::1]' | host.docker.internal) ;;
  *) die "DB_URL points at $host, not this machine — refused (the dev personas exist only in local databases)" ;;
esac

pghost="${host#[}"; pghost="${pghost%]}"
sql="$(node "$ROOT/scripts/dev/creds.mjs" --reset-sql)"
if [ "$port" = "$(cfg PG_PORT 5432)" ] && [ -n "$(docker compose ps -q postgres 2>/dev/null || true)" ]; then
  printf '%s\n' "$sql" | docker compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$db"
elif command -v psql >/dev/null 2>&1; then
  printf '%s\n' "$sql" | PGPASSWORD="$DB_PASSWORD" psql -q -v ON_ERROR_STOP=1 -h "$pghost" -p "$port" -U "$DB_USER" -d "$db"
else
  die "no psql and no compose postgres container — install psql (brew install libpq) or start the stand-in (make up)"
fi
echo "Backup codes reset for every persona (and their authenticator keys restored). make creds lists them."
