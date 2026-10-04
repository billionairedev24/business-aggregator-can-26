#!/usr/bin/env bash
# Pilot onboarding dry run (S-120): 12 FAKE businesses — providers, sellers and kitchens — go from a staff invite to live
# on a local market through the real api: invite link, Account and Business steps, Stripe Identity (the local fake),
# Stripe Connect (the fake gateway + a signed account.updated webhook), registry and identity reviews, kitchen visits,
# approval, listings, vetting, the page, and the public pages customers see. Ends by printing the pilot board.
#
# Everything runs in throwaway pieces it starts and stops itself: a PostGIS container (or DB_URL's database, which must
# be DISPOSABLE) and the api boot jar under the `local` profile. Nothing real is touched: no Stripe, no email (the api
# logs that Mailpit isn't there and retries), no registry.
#
#   make pilot-dry-run                 (STACK_LOCK=path wraps it in flock when several people share a machine)
#   KEEP=1 make pilot-dry-run          leaves the database container running for a look in psql
#
# Environment (defaults in brackets): PG_PORT [55120]  API_PORT [8120]  PILOT_MARKET [the region model's first live
# market]  OUT [<repo>/pilot-dry-run]  DB_URL/DB_USER/DB_PASSWORD (use that database instead of a container)
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
PG_PORT="${PG_PORT:-55120}" API_PORT="${API_PORT:-8120}" OUT="${OUT:-$repo/pilot-dry-run}"
CONTAINER="northline-pilot-dry-run-$PG_PORT"
WEBHOOK_SECRET="whsec_pilot_dry_run_not_a_real_secret"
mkdir -p "$OUT"

pids=()
cleanup() {
  for p in "${pids[@]}"; do kill -- "-$p" 2>/dev/null || kill "$p" 2>/dev/null || true; done
  if [ -z "${DB_URL:-}" ] && [ -z "${KEEP:-}" ]; then docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

if [ -z "${DB_URL:-}" ]; then
  echo "--- PostGIS in a throwaway container ($CONTAINER, port $PG_PORT)"
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$CONTAINER" -p "127.0.0.1:$PG_PORT:5432" -e POSTGRES_DB=northline -e POSTGRES_USER=northline \
    -e POSTGRES_PASSWORD=northline postgis/postgis:17-3.5 >/dev/null
  for _ in $(seq 60); do docker exec "$CONTAINER" pg_isready -U northline -d northline -q 2>/dev/null && break; sleep 1; done
  sleep 2
  DB_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/northline" DB_USER=northline DB_PASSWORD=northline
  PSQL="docker exec -i $CONTAINER psql -U northline -d northline -v ON_ERROR_STOP=1 -qAt"
else
  PSQL="psql ${PSQL_URL:?set PSQL_URL=postgresql://… for the same database as DB_URL} -v ON_ERROR_STOP=1 -qAt"
fi
export PSQL

gradle=(--init-script "$repo/ci/gradle/maven-mirror.init.gradle.kts" --max-workers=2 --console=plain -q
  "-Pdb.url=$DB_URL" "-Pdb.user=$DB_USER" "-Pdb.password=$DB_PASSWORD")
echo "--- migrate + dev seed + categories, api boot jar"
(cd "$repo/server" && ./gradlew :api:flywayMigrate -Pdb.devSeed=true "${gradle[@]}" \
  && ./gradlew :api:seedCategories :api:bootJar "${gradle[@]}") >"$OUT/build.log" 2>&1 || { tail -30 "$OUT/build.log"; exit 1; }

# The pilot market: PILOT_MARKET, else the region model's first market that is live or at pilot (V345 starts production
# at pilot; the local dev seed V349 sets it live again) — data, never a name in this script.
MARKET="${PILOT_MARKET:-$($PSQL -c "select id from region.regions where kind = 'market' and stage in ('live', 'pilot') order by sort, id limit 1")}"
[ -n "$MARKET" ] || { echo "No live market in the region model; set PILOT_MARKET"; exit 1; }
echo "--- pilot market $MARKET: kitchens there need a passed kitchen visit (region.regions.kitchen_visit)"
$PSQL -c "update region.regions set kitchen_visit = 'required' where id = '$MARKET'"

echo "--- api (local profile) on :$API_PORT"
DB_URL="$DB_URL" DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD" SERVER_PORT="$API_PORT" SPRING_PROFILES_ACTIVE=local \
  SPRING_FLYWAY_LOCATIONS="classpath:db/migration,filesystem:$repo/db/seed-dev" \
  STRIPE_CONNECT_WEBHOOK_SECRET="$WEBHOOK_SECRET" API_PUBLIC_URL="http://localhost:$API_PORT" \
  setsid java -Xmx768m -jar "$repo/server/api/build/libs/api.jar" >"$OUT/api.log" 2>&1 &
pids+=($!)
for _ in $(seq 180); do curl -sf -o /dev/null "http://localhost:$API_PORT/actuator/health" && break; sleep 1; done
curl -sf -o /dev/null "http://localhost:$API_PORT/actuator/health" || { echo "api did not start"; tail -40 "$OUT/api.log"; exit 1; }

echo "--- dry run"
API="http://localhost:$API_PORT" MARKET="$MARKET" WEBHOOK_SECRET="$WEBHOOK_SECRET" OUT="$OUT" \
  node "$repo/scripts/pilot/dry-run.mjs" | tee "$OUT/result.txt"
