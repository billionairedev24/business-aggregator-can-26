#!/usr/bin/env bash
# End-to-end suite (S-117, web/e2e): the whole stack under the `local` profile, then Playwright, then everything stops.
# A deployed environment instead: `make e2e-target ENV=staging` (no stack here; docs/runbooks/e2e.md).
# `make e2e` runs `ci/e2e.sh run` inside `flock` (one full stack per machine). Runbook: docs/runbooks/e2e.md.
#
#   ci/e2e.sh run      up → playwright → down (exit code = the suite's)
#   ci/e2e.sh up       only start the stack (keeps running; for writing tests: `pnpm --filter @northline/e2e e2e`)
#   ci/e2e.sh test     only the suite, against a stack that is already up (`up` in another terminal)
#   ci/e2e.sh down     stop what `up` started (process groups recorded in $E2E_OUT/pids, the Postgres container)
#
# What `up` starts: a DISPOSABLE PostGIS container (tmpfs, unless DB_HOST is given) migrated with the dev seed +
# categories, api :8080, northline-auth :9000, studio-bff :8082, consumer-bff :8081, console-bff :8083 (boot jars,
# profile local), and the Studio :3100, consumer site :3000 and console :3200 dev servers going through their BFFs (no
# dev auth). The ports are the ones the local OAuth clients are registered with, so they are fixed.
#
# Environment (defaults in brackets):
#   E2E_OUT [<repo>/e2e-out]  logs, Playwright report, traces, screenshots, videos
#   DB_HOST [unset = start the container]  DB_PORT [55117]  DB_NAME/DB_USER/DB_PASSWORD [northline]
#   SKIP_BUILD [unset]  1 = use the boot jars already in server/*/build/libs
#   GRADLE_ARGS [--max-workers=2]  extra Gradle flags (e.g. -Dorg.gradle.daemon.registry.base=…)
#   E2E_ARGS [unset]  extra `playwright test` arguments, e.g. "--grep @smoke" or "journeys/payout.spec.ts"
#   CHROMIUM [Playwright's own, else /opt/pw-browsers/chromium]
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
E2E_OUT="${E2E_OUT:-$repo/e2e-out}"
DB_PORT="${DB_PORT:-55117}" DB_NAME="${DB_NAME:-northline}" DB_USER="${DB_USER:-northline}" DB_PASSWORD="${DB_PASSWORD:-northline}"
GRADLE_ARGS="${GRADLE_ARGS:---max-workers=2}"
PIDS="$E2E_OUT/pids"
PG_CONTAINER="northline-e2e-pg-$DB_PORT"
LOGS="$E2E_OUT/logs"

# name port health-url
SERVICES=(
  "api 8080 http://localhost:8080/actuator/health"
  "auth 9000 http://localhost:9000/actuator/health"
  "studio-bff 8082 http://localhost:8082/actuator/health"
  "consumer-bff 8081 http://localhost:8081/actuator/health"
  "console-bff 8083 http://localhost:8083/actuator/health"
  "studio 3100 http://localhost:3100/sign-in"
  "consumer 3000 http://localhost:3000/"
  "console 3200 http://localhost:3200/sign-in"
)

say() { printf '\033[1m▸ %s\033[0m\n' "$*"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

wait_for() { # url name seconds [pid]
  local url=$1 name=$2 secs=$3 pid=${4:-}
  for _ in $(seq "$secs"); do
    if curl -sf -o /dev/null --max-time 3 "$url"; then echo "  ✓ $name ($url)"; return 0; fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      echo "  ✗ $name exited — last lines of $LOGS/$name.log:" >&2; tail -n 30 "$LOGS/$name.log" >&2; return 1
    fi
    sleep 1
  done
  echo "  ✗ $name did not answer within ${secs}s ($url) — see $LOGS/$name.log" >&2; return 1
}

# start <name> <command…>: detached in its own session (process group), recorded for `down`
start() {
  local name=$1; shift
  setsid bash -c 'echo $$ > "$0"; exec "$@"' "$PIDS/$name.pid" "$@" >"$LOGS/$name.log" 2>&1 </dev/null &
  for _ in $(seq 50); do [ -s "$PIDS/$name.pid" ] && break; sleep 0.1; done
}

db_up() {
  if [ -n "${DB_HOST:-}" ]; then echo "  using the database at $DB_HOST:$DB_PORT/$DB_NAME (it must be disposable)"; return; fi
  DB_HOST=localhost
  command -v docker >/dev/null || fail "docker is needed for the disposable Postgres (or set DB_HOST to a disposable database)"
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --rm --name "$PG_CONTAINER" -p "127.0.0.1:$DB_PORT:5432" --tmpfs /var/lib/postgresql/data \
    -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    postgis/postgis:17-3.5 >/dev/null
  echo "$PG_CONTAINER" >"$PIDS/postgres.container"
  for _ in $(seq 60); do
    docker exec "$PG_CONTAINER" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" 2>/dev/null && { echo "  ✓ postgres :$DB_PORT ($PG_CONTAINER)"; return; }
    sleep 1
  done
  fail "Postgres did not start (docker logs $PG_CONTAINER)"
}

cmd_up() {
  mkdir -p "$PIDS" "$LOGS"
  for s in "${SERVICES[@]}"; do set -- $s; listening "$2" && fail "port $2 ($1) is in use — stop it first (make down, or ci/e2e.sh down)"; done
  say "database"
  db_up
  local jdbc="jdbc:postgresql://${DB_HOST:-localhost}:$DB_PORT/$DB_NAME"
  local gradle=(./gradlew --init-script "$repo/ci/gradle/maven-mirror.init.gradle.kts" $GRADLE_ARGS --console=plain -q
    "-Pdb.url=$jdbc" "-Pdb.user=$DB_USER" "-Pdb.password=$DB_PASSWORD")
  say "migrate + dev seed + categories${SKIP_BUILD:+ (jars kept)}"
  (cd "$repo/server" && "${gradle[@]}" :api:flywayMigrate -Pdb.devSeed=true >"$LOGS/gradle.log" 2>&1 \
    && "${gradle[@]}" :api:seedCategories $([ -z "${SKIP_BUILD:-}" ] && echo :api:bootJar :auth:bootJar :bff:bootJar) >>"$LOGS/gradle.log" 2>&1) \
    || { tail -n 40 "$LOGS/gradle.log" >&2; fail "Gradle failed (log: $LOGS/gradle.log)"; }

  # The boot jars don't contain db/seed-dev (S-16): the local profile reads it from the repository.
  local common=(env DB_URL="$jdbc" DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD"
    SPRING_FLYWAY_LOCATIONS="classpath:db/migration,filesystem:$repo/db/seed-dev" NORTHLINE_DOTENV_DIR="$E2E_OUT")
  local libs="$repo/server"
  say "api, auth and the three BFFs (profile local)"
  # Payments jobs every 5 s instead of every minute: escrow releases and payout states show up while a test waits.
  start api "${common[@]}" SPRING_PROFILES_ACTIVE=local NORTHLINE_PAYMENTS_JOBS_INTERVAL=PT5S java -Xmx768m -XX:+UseSerialGC -jar "$libs/api/build/libs/api.jar"
  wait_for http://localhost:8080/actuator/health api 240 "$(cat "$PIDS/api.pid")"
  start auth "${common[@]}" SPRING_PROFILES_ACTIVE=local AUTH_ISSUER=http://localhost:9000 \
    java -Xmx384m -XX:+UseSerialGC -jar "$libs/auth/build/libs/auth.jar"
  start studio-bff env SPRING_PROFILES_ACTIVE=local NORTHLINE_DOTENV_DIR="$E2E_OUT" java -Xmx192m -XX:+UseSerialGC -jar "$libs/bff/build/libs/bff.jar"
  start consumer-bff env SPRING_PROFILES_ACTIVE=local,consumer NORTHLINE_DOTENV_DIR="$E2E_OUT" java -Xmx192m -XX:+UseSerialGC -jar "$libs/bff/build/libs/bff.jar"
  start console-bff env SPRING_PROFILES_ACTIVE=local,console NORTHLINE_DOTENV_DIR="$E2E_OUT" java -Xmx192m -XX:+UseSerialGC -jar "$libs/bff/build/libs/bff.jar"

  say "web apps (dev servers through their BFFs)"
  (cd "$repo/web" && pnpm --filter @northline/tokens build >"$LOGS/tokens.log" 2>&1) || fail "tokens build failed ($LOGS/tokens.log)"
  # Empty NL_DEV_USER / NL_DEV_GUEST beat a developer's .env: every app signs in for real.
  local web=(env NL_DEV_USER= NL_DEV_GUEST= VITE_NL_DEV_STEP_UP= BROWSER=none)
  start studio "${web[@]}" bash -c "cd '$repo/web/apps/studio' && exec node_modules/.bin/vite --port 3100 --strictPort"
  start consumer "${web[@]}" bash -c "cd '$repo/web/apps/consumer' && exec node_modules/.bin/vite --port 3000 --strictPort"
  start console "${web[@]}" bash -c "cd '$repo/web/apps/console' && exec node_modules/.bin/vite --port 3200 --strictPort"
  for s in "${SERVICES[@]}"; do set -- $s; wait_for "$3" "$1" 240 "$(cat "$PIDS/$1.pid")"; done
  say "stack is up — logs in $LOGS"
}

cmd_down() {
  [ -d "$PIDS" ] || return 0
  local f pid
  for f in "$PIDS"/*.pid; do
    [ -e "$f" ] || continue
    pid="$(cat "$f")"
    kill -TERM -- "-$pid" 2>/dev/null || true
  done
  for f in "$PIDS"/*.pid; do
    [ -e "$f" ] || continue
    pid="$(cat "$f")"
    for _ in $(seq 40); do kill -0 -- "-$pid" 2>/dev/null || break; sleep 0.25; done
    kill -KILL -- "-$pid" 2>/dev/null || true
    rm -f "$f"
  done
  if [ -f "$PIDS/postgres.container" ]; then
    docker rm -f "$(cat "$PIDS/postgres.container")" >/dev/null 2>&1 || true
    rm -f "$PIDS/postgres.container"
  fi
  echo "  ■ e2e stack stopped"
}

cmd_test() {
  say "playwright"
  cd "$repo/web"
  # A machine with Playwright's browsers outside the default cache (this repo's sandboxes: /opt/pw-browsers) — its
  # Chromium and ffmpeg (videos) are found there; never `playwright install` over them.
  if [ -z "${PLAYWRIGHT_BROWSERS_PATH:-}" ] && [ -d /opt/pw-browsers ]; then export PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers; fi
  # shellcheck disable=SC2086
  E2E_MODE=local E2E_OUT="$E2E_OUT" pnpm --filter @northline/e2e exec playwright test ${E2E_ARGS:-}
}

case "${1:-run}" in
  up) cmd_up ;;
  down) cmd_down ;;
  test) cmd_test ;;
  run)
    trap cmd_down EXIT
    cmd_up
    started=$(date +%s)
    status=0
    cmd_test || status=$?
    echo "  suite took $(( $(date +%s) - started ))s (exit $status); report: $E2E_OUT/report/index.html"
    exit $status
    ;;
  *) echo "usage: ci/e2e.sh [run|up|down|test]" >&2; exit 2 ;;
esac
