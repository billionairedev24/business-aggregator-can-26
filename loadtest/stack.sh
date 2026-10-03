#!/usr/bin/env bash
# S-119: a dedicated local stack for load tests — its own compose project (northline-load) and ports, so it never
# touches your `make up` stack or its data. docs/runbooks/load-testing.md § Local.
#
#   loadtest/stack.sh up      Postgres + Elasticsearch (+ Kafka for the reindex only), the api jar (`local` profile,
#                             dev auth, INFO logging, search on Elasticsearch, the load generator exempt from the
#                             search rate limit), the load-test data (seed/seed.sh), the search indices filled
#   loadtest/stack.sh api     (re)start only the api
#   loadtest/stack.sh search  (re)create the search indices and reindex from Postgres (after loadtest/seed/seed.sh)
#   loadtest/stack.sh statements [n]   the n statements that took the most database time (pg_stat_statements)
#   loadtest/stack.sh status  what runs
#   loadtest/stack.sh down    stop the api and remove the containers and their volumes
#
# Needs the boot jars (`cd server && ./gradlew :api:bootJar :worker:bootJar`), Docker, psql and curl. Sizes: SEED_*
# (seed/seed.sh). Knobs (defaults): LOAD_API_PORT (18080), LOAD_PG_PORT (55432), LOAD_ES_PORT (59200),
# LOAD_KAFKA_PORT (59092), LOAD_API_HEAP (1g), LOAD_DB_POOL_SIZE (10), LOAD_ES_HEAP (512m), LOAD_LIVE_BUS (memory).
# Run it inside the full-stack lock when other people share the machine (make load-stack-up does).
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
here="$repo/loadtest"
run="$here/.data"
mkdir -p "$run"

export PG_PORT="${LOAD_PG_PORT:-55432}" ES_PORT="${LOAD_ES_PORT:-59200}" KAFKA_PORT="${LOAD_KAFKA_PORT:-59092}"
export ES_HEAP="${LOAD_ES_HEAP:-512m}" VALKEY_PORT="${LOAD_VALKEY_PORT:-56379}"
API_PORT="${LOAD_API_PORT:-18080}"
compose=(docker compose -p northline-load -f "$repo/docker-compose.yml")
jdbc="jdbc:postgresql://localhost:$PG_PORT/northline"
api_jar="$repo/server/api/build/libs/api.jar"
worker_jar="$repo/server/worker/build/libs/worker.jar"

psql_local() { PGPASSWORD=northline psql -h localhost -p "$PG_PORT" -U northline -d northline -qAt "$@"; }

wait_for() { # url, name, seconds
  for _ in $(seq "$3"); do
    if curl -sf -o /dev/null "$1"; then return 0; fi
    sleep 1
  done
  echo "$2 did not answer within $3 s ($1)" >&2
  return 1
}

stop_api() {
  if [ -f "$run/api.pid" ]; then
    kill "$(cat "$run/api.pid")" 2>/dev/null || true
    for _ in $(seq 30); do kill -0 "$(cat "$run/api.pid")" 2>/dev/null || break; sleep 1; done
    rm -f "$run/api.pid"
  fi
}

start_api() {
  stop_api
  [ -f "$api_jar" ] || { echo "No $api_jar: cd server && ./gradlew :api:bootJar" >&2; exit 1; }
  local bus=()
  if [ "${LOAD_LIVE_BUS:-memory}" = redis ]; then
    bus=(LIVE_BUS=redis REDIS_HOST=localhost REDIS_PORT="$VALKEY_PORT")
  fi
  # the log rolls at 50 MB, 150 MB at most: an overloaded api logs every shed request (a stress run filled a disk)
  echo "api (local profile) on :$API_PORT — log $run/api.log"
  # the boot jar has no dev seed (S-16): the local profile reads it from the repository
  env DB_URL="$jdbc" DB_USER=northline DB_PASSWORD=northline SERVER_PORT="$API_PORT" SPRING_PROFILES_ACTIVE=local \
    SPRING_FLYWAY_LOCATIONS="classpath:db/migration,filesystem:$repo/db/seed-dev" \
    SEARCH_PROVIDER=elasticsearch ES_URIS="http://localhost:$ES_PORT" \
    SEARCH_RATE_LIMIT_EXEMPT="127.0.0.1,::1" DB_POOL_SIZE="${LOAD_DB_POOL_SIZE:-10}" \
    LOGGING_LEVEL_CA_NORTHLINE=info LOGGING_LEVEL_CA_NORTHLINE_CONFIG_DEVAUTHFILTER=info "${bus[@]}" \
    LOGGING_FILE_NAME="$run/api.log" LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE=50MB \
    LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP=150MB LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY=1 \
    setsid java -Xms"${LOAD_API_HEAP:-1g}" -Xmx"${LOAD_API_HEAP:-1g}" -XX:+ExitOnOutOfMemoryError \
    -jar "$api_jar" --northline.loadtest.port="$API_PORT" >/dev/null 2>&1 &
  wait_for "http://localhost:$API_PORT/actuator/health" api 240
  # the java process itself (setsid may fork): loadtest/sample.sh reads its heap with jstat
  pgrep -f -- "--northline.loadtest.port=$API_PORT" | head -1 >"$run/api.pid"
}

# The worker's search commands, from its boot jar (no Gradle): SearchIndicesCommand / SearchReindexCommand.
worker_command() { # main class, args…
  local main=$1
  shift
  env DB_URL="$jdbc" DB_USER=northline DB_PASSWORD=northline ES_URIS="http://localhost:$ES_PORT" \
    KAFKA_BOOTSTRAP="localhost:$KAFKA_PORT" \
    java -Xmx512m -cp "$worker_jar" -Dloader.main="$main" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}

case "${1:-}" in
  up)
    [ -f "$worker_jar" ] || { echo "No $worker_jar: cd server && ./gradlew :api:bootJar :worker:bootJar" >&2; exit 1; }
    profiles=(--profile db --profile search --profile events)
    if [ "${LOAD_LIVE_BUS:-memory}" = redis ]; then profiles+=(--profile cache); fi
    "${compose[@]}" "${profiles[@]}" up -d --wait
    # pg_stat_statements: run.sh lists the statements that took the most time in each local run (hot spots, N+1s)
    psql_local -c "alter system set shared_preload_libraries = 'pg_stat_statements'" >/dev/null
    psql_local -c "alter system set max_wal_size = '256MB'" >/dev/null # the seed writes ~2 GB of WAL otherwise
    # a laptop's disk is often over Elasticsearch's 90 % watermark, which leaves the indices unassigned (red)
    curl -sf -XPUT "http://localhost:$ES_PORT/_cluster/settings" -H 'Content-Type: application/json' \
      -d '{"persistent":{"cluster.routing.allocation.disk.threshold_enabled":false}}' >/dev/null
    "${compose[@]}" restart postgres >/dev/null
    until psql_local -c "select 1" >/dev/null 2>&1; do sleep 1; done
    psql_local -c "create extension if not exists pg_stat_statements" >/dev/null
    start_api # migrates the database and applies the dev seed
    (cd "$repo/server" && ./gradlew -q --max-workers=2 :api:seedCategories -Pdb.url="$jdbc" -Pdb.user=northline \
      -Pdb.password=northline)
    PGPORT="$PG_PORT" "$here/seed/seed.sh"
    worker_command ca.northline.worker.search.SearchIndicesCommand apply
    worker_command ca.northline.worker.search.SearchReindexCommand
    "${compose[@]}" stop kafka >/dev/null # only the reindex needed it; the api runs `local` (no Kafka)
    start_api                             # forgets the fake gateway's in-memory state of the seeding
    echo "Ready: TARGET=local API_URL=http://localhost:$API_PORT (make load-smoke)"
    ;;
  api) start_api ;;
  search) # (re)create the indices and fill them from Postgres (after a re-seed)
    "${compose[@]}" --profile events up -d --wait kafka
    worker_command ca.northline.worker.search.SearchIndicesCommand apply
    worker_command ca.northline.worker.search.SearchReindexCommand
    "${compose[@]}" stop kafka >/dev/null
    ;;
  statements) # the statements that took the most database time since the last reset (run.sh resets before each run)
    psql_local -F ' | ' -c "select calls, round(total_exec_time)::int as total_ms, round(mean_exec_time::numeric, 2) as mean_ms,
                                   rows, left(regexp_replace(query, '\s+', ' ', 'g'), 220)
                              from pg_stat_statements where query not like '%pg_stat_statements%'
                             order by total_exec_time desc limit ${2:-25}"
    ;;
  reset-statements) psql_local -c "select pg_stat_statements_reset(), pg_stat_reset()" >/dev/null ;;
  status)
    "${compose[@]}" ps
    if [ -f "$run/api.pid" ] && kill -0 "$(cat "$run/api.pid")" 2>/dev/null; then
      echo "api pid $(cat "$run/api.pid") on :$API_PORT"
    else
      echo "api not running"
    fi
    ;;
  down)
    stop_api
    "${compose[@]}" --profile all down -v
    ;;
  *)
    echo "usage: loadtest/stack.sh up|api|search|statements|status|down" >&2
    exit 2
    ;;
esac
