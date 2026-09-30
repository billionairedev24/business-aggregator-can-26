#!/usr/bin/env bash
# Studio smoke sweep for CI (S-5): migrate + seed an EMPTY Postgres/PostGIS, start the api (`local` profile) and the
# studio dev server (dev auth as Ravi Sandhu), then run scripts/studio-smoke.mjs against every Studio screen.
# Used by .github/workflows/web.yml and ci/gitlab/web.yml; also runs on a laptop (see docs/runbooks/ci.md).
#
# Needs: JDK 25 (JAVA_HOME), Node 22 + pnpm with web/ dependencies installed, a Playwright Chromium (CHROMIUM=path),
# and a reachable Postgres whose database is disposable.
#
# Environment (defaults in brackets):
#   DB_HOST [localhost]  DB_PORT [5432]  DB_NAME [northline]  DB_USER [northline]  DB_PASSWORD [northline]
#   API_PORT [8080]  AUTH_PORT [9000]  STUDIO_PORT [3100]  CHROMIUM [Playwright's default]  SMOKE_OUT [<repo>/smoke-out]
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5432}" DB_NAME="${DB_NAME:-northline}"
DB_USER="${DB_USER:-northline}" DB_PASSWORD="${DB_PASSWORD:-northline}"
API_PORT="${API_PORT:-8080}" AUTH_PORT="${AUTH_PORT:-9000}" STUDIO_PORT="${STUDIO_PORT:-3100}"
SMOKE_OUT="${SMOKE_OUT:-$repo/smoke-out}"
DEV_USER=01J9ZD3V00000000000000RAV1 # Ravi Sandhu, owner of the three seeded businesses (db/seed-dev)
jdbc="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
mkdir -p "$SMOKE_OUT"

pids=() # process-group leaders (setsid), so children such as vite stop too
cleanup() { for p in "${pids[@]}"; do kill -- "-$p" 2>/dev/null || kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

wait_for() { # url, name, seconds
  local url=$1 name=$2 secs=$3
  for _ in $(seq "$secs"); do
    if curl -sf -o /dev/null "$url"; then echo "$name is up ($url)"; return 0; fi
    sleep 1
  done
  echo "::error::$name did not come up within ${secs}s ($url)"; return 1
}

gradle_args=(--init-script "$repo/ci/gradle/maven-mirror.init.gradle.kts" --max-workers=2 --console=plain
  "-Pdb.url=$jdbc" "-Pdb.user=$DB_USER" "-Pdb.password=$DB_PASSWORD")

echo "--- migrate + seed $jdbc"
cd "$repo/server"
./gradlew :api:flywayMigrate -Pdb.devSeed=true "${gradle_args[@]}"
./gradlew :api:seedCategories :api:bootJar :auth:bootJar "${gradle_args[@]}"

# The boot jars don't contain db/seed-dev (S-16): the local profile reads it from the repository instead.
seed_locations="classpath:db/migration,filesystem:$repo/db/seed-dev"

echo "--- api (local profile) on :$API_PORT"
DB_URL="$jdbc" DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD" SERVER_PORT="$API_PORT" SPRING_PROFILES_ACTIVE=local \
  SPRING_FLYWAY_LOCATIONS="$seed_locations" \
  setsid java -Xmx768m -jar api/build/libs/api.jar >"$SMOKE_OUT/api.log" 2>&1 &
pids+=($!)

echo "--- northline-auth (local profile) on :$AUTH_PORT — Settings › Security talks to it"
DB_URL="$jdbc" DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD" SERVER_PORT="$AUTH_PORT" AUTH_ISSUER="http://localhost:$AUTH_PORT" SPRING_PROFILES_ACTIVE=local \
  SPRING_FLYWAY_LOCATIONS="$seed_locations" \
  NORTHLINE_AUTH_ALLOWED_ORIGINS="http://localhost:$STUDIO_PORT" \
  setsid java -Xmx512m -jar auth/build/libs/auth.jar >"$SMOKE_OUT/auth.log" 2>&1 &
pids+=($!)
wait_for "http://localhost:$API_PORT/actuator/health" api 180
wait_for "http://localhost:$AUTH_PORT/.well-known/openid-configuration" auth 180

echo "--- studio dev server on :$STUDIO_PORT (dev auth as $DEV_USER)"
cd "$repo/web"
NL_DEV_USER="$DEV_USER" NL_API="http://localhost:$API_PORT" VITE_NL_DEV_STEP_UP=1 VITE_NL_AUTH_ORIGIN="http://localhost:$AUTH_PORT" \
  setsid pnpm --filter @northline/studio dev --port "$STUDIO_PORT" --strictPort >"$SMOKE_OUT/studio.log" 2>&1 &
pids+=($!)
wait_for "http://localhost:$STUDIO_PORT/" studio 120

echo "--- smoke sweep"
# The sweep imports playwright-core; give it a tiny node_modules of its own next to a copy of the script.
runner="$SMOKE_OUT/runner"
mkdir -p "$runner" && cp "$repo/scripts/studio-smoke.mjs" "$runner/"
npm install --prefix "$runner" --no-save --no-package-lock --no-audit --no-fund playwright-core@1.56 >/dev/null
if [ -z "${CHROMIUM:-}" ]; then # the Chromium that `playwright install chromium` put in place
  CHROMIUM="$(cd "$runner" && node -e "import('playwright-core').then(p => console.log(p.chromium.executablePath()))")"
fi
cd "$SMOKE_OUT" # the sweep writes screenshots to ./shots
CHROMIUM="$CHROMIUM" STUDIO_URL="http://localhost:$STUDIO_PORT" node "$runner/studio-smoke.mjs" | tee "$SMOKE_OUT/sweep.txt"
