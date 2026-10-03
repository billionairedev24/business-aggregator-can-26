#!/usr/bin/env bash
# Go-live rehearsal (S-118, docs/runbooks/go-live.md § Rehearsal): on a throwaway local stack, S-120's pilot dry run
# brings 12 FAKE businesses live in the region model's first live market; then the market is put back to pilot (as
# production starts), the go-live check runs (manual gates pending), the gates are recorded, two admins switch the
# market to live, public discovery is checked, the market is rolled back, switched again, and hypercare is planned.
#
#   make go-live-rehearsal            (STACK_LOCK=path wraps it in flock when several people share a machine)
#   KEEP=1 make go-live-rehearsal     leaves the database container running
#
# Environment: PG_PORT [55118]  API_PORT [8118]  OUT [<repo>/go-live-rehearsal]  PILOT_MARKET (as the dry run)
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
export PG_PORT="${PG_PORT:-55118}" API_PORT="${API_PORT:-8118}"
OUT="${OUT:-$repo/go-live-rehearsal}"
CONTAINER="northline-pilot-dry-run-$PG_PORT"
mkdir -p "$OUT"

pids=()
cleanup() {
  for p in "${pids[@]}"; do kill -- "-$p" 2>/dev/null || kill "$p" 2>/dev/null || true; done
  if [ -z "${KEEP:-}" ]; then docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

echo "=== 1. pilot dry run (S-120): 12 fake businesses in the market"
KEEP=1 OUT="$OUT/pilot" "$repo/scripts/pilot/dry-run.sh" >"$OUT/pilot-dry-run.log" 2>&1 \
  || { tail -30 "$OUT/pilot-dry-run.log"; exit 1; }
tail -3 "$OUT/pilot-dry-run.log"
export PSQL="docker exec -i $CONTAINER psql -U northline -d northline -v ON_ERROR_STOP=1 -qAt"
MARKET="${PILOT_MARKET:-$($PSQL -c "select market_id from merchants.pilot_businesses order by created_at limit 1")}"

echo "=== 2. api (local profile) on :$API_PORT, same database"
DB_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/northline" DB_USER=northline DB_PASSWORD=northline SERVER_PORT="$API_PORT" \
  SPRING_PROFILES_ACTIVE=local SPRING_FLYWAY_LOCATIONS="classpath:db/migration,filesystem:$repo/db/seed-dev" \
  API_PUBLIC_URL="http://localhost:$API_PORT" \
  setsid java -Xmx768m -jar "$repo/server/api/build/libs/api.jar" >"$OUT/api.log" 2>&1 &
pids+=($!)
for _ in $(seq 180); do curl -sf -o /dev/null "http://localhost:$API_PORT/actuator/health" && break; sleep 1; done
curl -sf -o /dev/null "http://localhost:$API_PORT/actuator/health" || { echo "api did not start"; tail -40 "$OUT/api.log"; exit 1; }

echo "=== 3. rehearsal in $MARKET"
API="http://localhost:$API_PORT" MARKET="$MARKET" OUT="$OUT" node "$repo/scripts/go-live/rehearsal.mjs" | tee "$OUT/rehearsal.txt"
