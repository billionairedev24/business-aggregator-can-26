#!/usr/bin/env bash
# Everything on one machine, with the services you already run (make up-all; docs/runbooks/local.md § 6a):
#
#   scripts/local-all.sh up       check your own Postgres / Valkey / Grafana, start every stand-in you don't bring and
#                                 the observability stack, migrate + seed, start every app exporting telemetry, print
#                                 the status table
#   scripts/local-all.sh check    only the checks of your own services (and of the ports the stand-ins need)
#   scripts/local-all.sh status   the status table: every URL and whether it answers
#
# Settings (environment, else the root .env): BYO_SERVICES (or BYO=… for one run) = the stand-ins you run yourself,
# any of db, cache, events, search, mail, storage, payments, grafana — e.g. BYO_SERVICES=db,cache,grafana. Your
# Postgres and Valkey are the ones in server/.env (DB_URL / DB_USER / DB_PASSWORD, REDIS_HOST / REDIS_PORT / …); your
# Grafana is GRAFANA_URL with GRAFANA_TOKEN (or GRAFANA_USER + GRAFANA_PASSWORD), provisioned when credentials are set.
# SERVICES = the apps (default: every app but Storybook and the docs site). SKIP_DB=1 skips migrate + seed.
# make down stops it all (your own services are never touched). bash 3.2 (macOS), no GNU-only tools.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
. "$ROOT/scripts/local-env.sh"
MAKE=${MAKE:-make}
APPS_DEFAULT="auth api bff bff-consumer bff-console worker studio consumer console"
KNOWN_BYO="db cache events search mail storage payments grafana"

c_dim=$'\033[2m'; c_ok=$'\033[32m'; c_bad=$'\033[31m'; c_warn=$'\033[33m'; c_b=$'\033[1m'; c_off=$'\033[0m'
[ -t 1 ] || { c_dim=; c_ok=; c_bad=; c_warn=; c_b=; c_off=; }
say() { printf '%s\n' "$*"; }
ok() { say "  ${c_ok}✓${c_off} $*"; }
warn() { say "  ${c_warn}!${c_off} $*"; }
die() { say "${c_bad}✗ $*${c_off}" >&2; exit 1; }

for b in $BYO_LIST; do
  case " $KNOWN_BYO " in *" $b "*) ;; *) die "BYO_SERVICES: unknown '$b' (one of: $KNOWN_BYO)" ;; esac
done

# --- settings -------------------------------------------------------------------------------------------------------
PG_PORT=$(cfg PG_PORT 5432); VALKEY_PORT=$(cfg VALKEY_PORT 6379); KAFKA_PORT=$(cfg KAFKA_PORT 9092)
ES_PORT=$(cfg ES_PORT 9200); MAILPIT_SMTP_PORT=$(cfg MAILPIT_SMTP_PORT 1025); MAILPIT_UI_PORT=$(cfg MAILPIT_UI_PORT 8025)
STORAGE_PORT=$(cfg STORAGE_PORT 9100); STORAGE_CONSOLE_PORT=$(cfg STORAGE_CONSOLE_PORT 9101)
STRIPE_MOCK_PORT=$(cfg STRIPE_MOCK_PORT 12111); KAFKA_UI_PORT=$(cfg KAFKA_UI_PORT 8190)
OTEL_GRPC_PORT=$(cfg OTEL_GRPC_PORT 4317); OTEL_HTTP_PORT=$(cfg OTEL_HTTP_PORT 4318)
PROMETHEUS_PORT=$(cfg PROMETHEUS_PORT 9090); LOKI_PORT=$(cfg LOKI_PORT 3110); TEMPO_PORT=$(cfg TEMPO_PORT 3210)
ALERTMANAGER_PORT=$(cfg ALERTMANAGER_PORT 9093); GRAFANA_PORT=$(cfg GRAFANA_PORT 3300)
GRAFANA_URL=$(cfg GRAFANA_URL http://localhost:3000)

DB_URL=$(appcfg DB_URL jdbc:postgresql://localhost:5432/northline)
DB_USER=$(appcfg DB_USER northline); DB_PASSWORD=$(appcfg DB_PASSWORD northline)
REDIS_HOST=$(appcfg REDIS_HOST localhost); REDIS_PORT=$(appcfg REDIS_PORT 6379)
REDIS_USERNAME=$(appcfg REDIS_USERNAME); REDIS_PASSWORD=$(appcfg REDIS_PASSWORD); REDIS_SSL=$(appcfg REDIS_SSL false)

# jdbc:postgresql://host[:port]/db[?…] → PGH PGP PGD
pg_parse() {
  local rest="${DB_URL#jdbc:postgresql://}" hostport
  hostport="${rest%%/*}"; PGD="${rest#*/}"; PGD="${PGD%%\?*}"
  case "$hostport" in *:*) PGH="${hostport%:*}"; PGP="${hostport##*:}" ;; *) PGH="$hostport"; PGP=5432 ;; esac
}
pg_parse
is_local_host() { case "$1" in localhost | 127.0.0.1 | ::1 | '[::1]' | host.docker.internal) return 0 ;; esac; return 1; }

port_open() { (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null; }
http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$1" 2>/dev/null || true; }
compose_running() { [ -n "$(docker compose ps -q --status running "$1" 2>/dev/null)" ]; }
url_port() { local p="${1#*://}"; p="${p%%/*}"; case "$p" in *:*) echo "${p##*:}" ;; *) case "$1" in https:*) echo 443 ;; *) echo 80 ;; esac ;; esac; }

# --- your own services ----------------------------------------------------------------------------------------------
# psql from PATH, else the postgis image's psql (network host on Linux; host.docker.internal on macOS/Windows).
pg_query() {
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$DB_PASSWORD" PGCONNECT_TIMEOUT=5 psql -h "$PGH" -p "$PGP" -U "$DB_USER" -d "$PGD" -XAtq -c "$1"
  elif command -v docker >/dev/null 2>&1; then
    local host="$PGH" net="--network=host"
    if [ "$(uname -s)" != Linux ]; then net="--add-host=host.docker.internal:host-gateway"; is_local_host "$host" && host=host.docker.internal; fi
    docker run --rm $net -e PGPASSWORD="$DB_PASSWORD" -e PGCONNECT_TIMEOUT=5 postgis/postgis:17-3.5 \
      psql -h "$host" -p "$PGP" -U "$DB_USER" -d "$PGD" -XAtq -c "$1"
  else
    return 127
  fi
}

check_postgres() {
  is_local_host "$PGH" || die "DB_URL points at $PGH: make up-all migrates and seeds the dev personas, which only runs against a local database (S-16)"
  port_open "$PGH" "$PGP" || die "your Postgres does not answer on $PGH:$PGP (DB_URL in server/.env). Start it, or drop db from BYO_SERVICES to use the Docker one"
  local out rc=0
  out=$(pg_query "select current_setting('server_version_num'), current_setting('server_version'),
      coalesce((select extversion from pg_extension where extname = 'postgis'), '-'),
      coalesce((select default_version from pg_available_extensions where name = 'postgis'), '-'),
      (select rolsuper from pg_roles where rolname = current_user)" 2>&1) || rc=$?
  if [ $rc = 127 ]; then warn "Postgres answers on $PGH:$PGP; no psql or Docker to check the version and PostGIS"; return; fi
  [ $rc = 0 ] || die "cannot sign in to $DB_URL as $DB_USER: ${out}
    Create the role, database and extensions once from server/.env (as a superuser; PGPASSWORD for its password):
      make db-create MIGRATE=0       # then make up-all again (docs/runbooks/local.md § 3)"
  local num ver installed available super
  IFS='|' read -r num ver installed available super <<EOF
$out
EOF
  if [ "$available" = - ]; then
    die "PostGIS is not installed on your Postgres $ver ($PGH:$PGP): macOS brew install postgis; Debian/Ubuntu apt install postgresql-${num:0:2}-postgis-3; then run make up-all again"
  fi
  if [ "$installed" = - ] && [ "$super" != t ]; then
    die "PostGIS is available but not created in '$PGD', and $DB_USER may not create it (not a trusted extension). Once, as a superuser:
      make db-create MIGRATE=0       # adds the extensions; changes nothing else that exists"
  fi
  if [ "$num" -lt 170000 ]; then warn "your Postgres is $ver — 17 is recommended (the cloud, CI and the Docker stand-in run 17)"; fi
  ok "your Postgres $ver at $PGH:$PGP/$PGD, PostGIS ${installed/#-/available $available}"
}

check_valkey() {
  local reply=""
  if command -v valkey-cli >/dev/null 2>&1 || command -v redis-cli >/dev/null 2>&1; then
    local cli; cli=$(command -v valkey-cli || command -v redis-cli)
    local tls=""; [ "$REDIS_SSL" = true ] && tls="--tls"
    reply=$(REDISCLI_AUTH="$REDIS_PASSWORD" "$cli" $tls -h "$REDIS_HOST" -p "$REDIS_PORT" ${REDIS_USERNAME:+--user "$REDIS_USERNAME"} ping 2>&1 || true)
  elif [ "$REDIS_SSL" = true ]; then
    port_open "$REDIS_HOST" "$REDIS_PORT" && reply=PONG
  else
    reply=$( (exec 3<>"/dev/tcp/$REDIS_HOST/$REDIS_PORT" || exit 1
      if [ -n "$REDIS_PASSWORD" ]; then
        if [ -n "$REDIS_USERNAME" ]; then printf 'AUTH %s %s\r\n' "$REDIS_USERNAME" "$REDIS_PASSWORD" >&3; else printf 'AUTH %s\r\n' "$REDIS_PASSWORD" >&3; fi
        IFS= read -r -t 3 line <&3 || true
        # (pattern) form: bash 3.2 (macOS) ends the $( ) at a bare pattern's ')' and fails to parse
        case "$line" in (+OK*) ;; (*) printf '%s' "$line"; exit 0 ;; esac
      fi
      printf 'PING\r\n' >&3; IFS= read -r -t 3 line <&3 || true; printf '%s' "${line%$'\r'}") 2>/dev/null || true)
  fi
  case "$reply" in
    *PONG*) ok "your Valkey at $REDIS_HOST:$REDIS_PORT answers PING" ;;
    "") die "your Valkey does not answer on $REDIS_HOST:$REDIS_PORT (REDIS_HOST / REDIS_PORT in server/.env). Start it, or drop cache from BYO_SERVICES to use the Docker one" ;;
    *) die "your Valkey at $REDIS_HOST:$REDIS_PORT answered '$reply' — check REDIS_USERNAME / REDIS_PASSWORD in server/.env" ;;
  esac
}

check_grafana() {
  local code; code=$(http_code "$GRAFANA_URL/api/health")
  if [ "$(url_port "$GRAFANA_URL")" = 3000 ] && contains_app consumer; then
    die "your Grafana is on :3000 (GRAFANA_URL), which the consumer web app needs (its sign-in redirects are registered
    for localhost:3000). Move Grafana to another port — e.g. http_port = 3001 in grafana.ini ([server]), or
    GF_SERVER_HTTP_PORT=3001 — and set GRAFANA_URL=http://localhost:3001 in .env. (Or leave the consumer app out:
    SERVICES=\"auth api bff bff-console worker studio console\".)"
  fi
  case "$code" in
    200) ok "your Grafana answers at $GRAFANA_URL" ;;
    *) warn "your Grafana does not answer at $GRAFANA_URL (GRAFANA_URL in .env) — provision it later: make obs-grafana-provision" ;;
  esac
}

# A port a stand-in needs, already held by something that isn't that stand-in: say which BYO entry or setting fixes it.
check_port() { # check_port <service> <port> <setting> [byo-name]
  compose_running "$1" && return 0
  port_open 127.0.0.1 "$2" || return 0
  if [ -n "${4:-}" ]; then
    die "port $2 for the $1 stand-in is taken — probably your own $1. Add $4 to BYO_SERVICES in .env to use yours, or move the stand-in: $3=<free port> in .env"
  fi
  die "port $2 for $1 is taken by something else — set $3=<free port> in .env"
}

# --- what to start --------------------------------------------------------------------------------------------------
standin_services() {
  local s=""
  byo db || s="$s postgres"
  byo cache || s="$s valkey"
  byo events || s="$s kafka kafka-topics"
  byo search || s="$s elasticsearch"
  byo mail || s="$s mailpit"
  byo storage || s="$s storage storage-bucket"
  byo payments || s="$s stripe-mock"
  echo "${s# }"
}

APPS="${SERVICES:-$APPS_DEFAULT}"
contains_app() { case " $APPS " in *" $1 "* | *" all "*) return 0 ;; esac; return 1; }

cmd_check() {
  say "${c_b}Your own services${c_off} ${c_dim}(BYO_SERVICES=${BYO_LIST:-none})${c_off}"
  if byo db; then check_postgres; else check_port postgres "$PG_PORT" PG_PORT db; fi
  if byo cache; then check_valkey; else check_port valkey "$VALKEY_PORT" VALKEY_PORT cache; fi
  if byo grafana; then check_grafana; else check_port grafana "$GRAFANA_PORT" GRAFANA_PORT grafana; fi
  byo events || check_port kafka "$KAFKA_PORT" KAFKA_PORT events
  byo search || check_port elasticsearch "$ES_PORT" ES_PORT search
  byo mail || { check_port mailpit "$MAILPIT_SMTP_PORT" MAILPIT_SMTP_PORT mail; check_port mailpit "$MAILPIT_UI_PORT" MAILPIT_UI_PORT mail; }
  byo storage || check_port storage "$STORAGE_PORT" STORAGE_PORT storage
  byo payments || check_port stripe-mock "$STRIPE_MOCK_PORT" STRIPE_MOCK_PORT payments
  check_port prometheus "$PROMETHEUS_PORT" PROMETHEUS_PORT
  check_port loki "$LOKI_PORT" LOKI_PORT
  check_port tempo "$TEMPO_PORT" TEMPO_PORT
  check_port alertmanager "$ALERTMANAGER_PORT" ALERTMANAGER_PORT
  check_port otel-collector "$OTEL_HTTP_PORT" OTEL_HTTP_PORT
  [ -z "$BYO_LIST" ] && say "  ${c_dim}none — every stand-in runs in Docker${c_off}"
  return 0
}

# export_default KEY VALUE: for the apps, unless the environment or server/.env already decides KEY
WIRED=""
export_default() {
  appcfg_set "$1" && return 0
  export "$1=$2"; WIRED="$WIRED $1=$2"
}

cmd_up() {
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || die "Docker is not running (the stand-ins and the observability stack need it)"
  cmd_check
  local services; services="$(standin_services)"
  say ""; say "${c_b}Stand-ins${c_off} ${c_dim}$services${c_off}"
  # shellcheck disable=SC2086 # a list of compose service names
  docker compose up -d --wait $services
  # Kafka UI is a convenience: a failed pull (Docker Hub rate limits) must not stop the stack.
  byo events || docker compose up -d kafka-ui >/dev/null 2>&1 || warn "Kafka UI did not start (docker compose up -d kafka-ui to see why); Kafka itself runs"
  say ""; say "${c_b}Observability${c_off}"
  BYO="$BYO_LIST" "$ROOT/scripts/observability.sh" up

  if [ -z "${SKIP_DB:-}" ]; then
    say ""; say "${c_b}Database${c_off} ${c_dim}migrate + dev personas + categories → $DB_URL${c_off}"
    "$MAKE" -C "$ROOT" --no-print-directory db-migrate db-seed
  fi
  if ! byo search; then
    say ""; say "${c_b}Search indices${c_off} ${c_dim}(synonym sets + listings_en / listings_fr)${c_off}"
    "$MAKE" -C "$ROOT" --no-print-directory search-indices
  fi

  # The apps: telemetry to the Collector, and each stand-in actually used — unless server/.env says otherwise.
  export OTEL_EXPORT_ENABLED=true OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:$OTEL_HTTP_PORT"
  export OTEL_RESOURCE_ATTRIBUTES="deployment.environment.name=local"
  export_default LIVE_BUS redis                                     # Studio live signals + courier positions over Valkey
  # auth + BFF sessions, auth's rate limits and replay ids in Valkey (application-valkey.yml), unless SPRING_PROFILE is given
  [ -n "${SPRING_PROFILE:-}" ] || export SPRING_PROFILE=local,valkey
  export_default SEARCH_PROVIDER elasticsearch                       # the api searches the indices the worker fills
  export_default SPRING_MODULITH_EVENTS_EXTERNALIZATION_ENABLED true # api/auth events reach Kafka, the worker consumes them
  [ -n "$WIRED" ] && say "" && say "${c_dim}For the apps (not in server/.env):$WIRED${c_off}"

  say ""; say "${c_b}Apps${c_off} ${c_dim}$APPS${c_off}"
  # shellcheck disable=SC2086
  local rc=0
  "$ROOT/scripts/stack.sh" up $APPS || rc=$?
  cmd_status
  [ $rc = 0 ] || say "${c_bad}Some apps did not start — the table shows which; make logs SERVICES=<app>, then make up-all again${c_off}"
  return $rc
}

# --- the status table -----------------------------------------------------------------------------------------------
row() { # row <name> <url> <check: http URL | tcp host:port | -> [note]
  local state code
  case "$3" in
    -) state="${c_dim}·${c_off}       " ;;
    tcp:*) local hp="${3#tcp:}"; if port_open "${hp%:*}" "${hp##*:}"; then state="${c_ok}up${c_off}      "; else state="${c_bad}down${c_off}    "; fi ;;
    *) code=$(http_code "$3"); case "$code" in 2* | 3* | 401 | 403) state="${c_ok}up${c_off}      " ;; *) state="${c_bad}down${c_off}    " ;; esac ;;
  esac
  printf '  %-22s %s %-52s %s\n' "$1" "$state" "$2" "${c_dim}${4:-}${c_off}"
}
yours() { byo "$1" && echo "(yours)" || echo "(Docker)"; }

cmd_status() {
  local g_url g_note
  if byo grafana; then g_url="$GRAFANA_URL"; g_note="(yours) folder Northline — make obs-grafana-provision"; else g_url="http://localhost:$GRAFANA_PORT"; g_note="(Docker) admin / admin, folder Northline"; fi
  say ""
  say "${c_b}Apps${c_off}"
  row "Studio" "http://localhost:3100" "http://localhost:3100/" "ravi.sandhu@example.com · backup code ravis-00001"
  row "Consumer web" "http://localhost:3000" "http://localhost:3000/" "through the consumer-bff"
  row "Console" "http://localhost:3200" "http://localhost:3200/" "priya.natarajan@example.com · backup code priya-n-00001"
  row "api" "http://localhost:8080/api/v1" "http://localhost:8080/actuator/health"
  row "  Swagger UI" "http://localhost:8080/swagger-ui.html" "http://localhost:8080/swagger-ui.html"
  row "  Scalar" "http://localhost:8080/docs/scalar" "http://localhost:8080/docs/scalar"
  row "  Redoc" "http://localhost:8080/docs/redoc" "http://localhost:8080/docs/redoc"
  row "northline-auth" "http://localhost:9000" "http://localhost:9000/actuator/health" "API docs :9000/docs · SMS codes in .run/logs/auth.log"
  row "studio-bff" "http://localhost:8082" "http://localhost:8082/actuator/health" "API docs :8082/bff/docs"
  row "consumer-bff" "http://localhost:8081" "http://localhost:8081/actuator/health" "API docs :8081/bff/docs"
  row "console-bff" "http://localhost:8083" "http://localhost:8083/actuator/health"
  row "worker" "http://localhost:8084/actuator/health" "http://localhost:8084/actuator/health"
  say "${c_b}Stand-ins${c_off}"
  row "Postgres" "$PGH:$PGP/$PGD" "tcp:$PGH:$PGP" "$(yours db)"
  row "Valkey" "$REDIS_HOST:$REDIS_PORT" "tcp:$REDIS_HOST:$REDIS_PORT" "$(yours cache) LIVE_BUS=$(appcfg LIVE_BUS redis)"
  row "Kafka" "localhost:$KAFKA_PORT" "tcp:localhost:$KAFKA_PORT" "$(yours events)"
  byo events || row "Kafka UI" "http://localhost:$KAFKA_UI_PORT" "http://localhost:$KAFKA_UI_PORT/"
  row "Elasticsearch" "http://localhost:$ES_PORT" "http://localhost:$ES_PORT/" "$(yours search)"
  row "Mailpit" "http://localhost:$MAILPIT_UI_PORT" "http://localhost:$MAILPIT_UI_PORT/" "$(yours mail) app email + alert emails"
  row "RustFS (S3) console" "http://localhost:$STORAGE_CONSOLE_PORT" "http://localhost:$STORAGE_CONSOLE_PORT/" "$(yours storage) S3 API :$STORAGE_PORT · northline / northline-dev-secret"
  row "stripe-mock" "http://localhost:$STRIPE_MOCK_PORT" "tcp:localhost:$STRIPE_MOCK_PORT" "$(yours payments) STRIPE_API_BASE to use it"
  say "${c_b}Observability${c_off}"
  row "Grafana" "$g_url" "$g_url/api/health" "$g_note"
  row "Prometheus" "http://localhost:$PROMETHEUS_PORT" "http://localhost:$PROMETHEUS_PORT/-/ready" "alerts: /alerts · rules: /rules"
  row "Alertmanager" "http://localhost:$ALERTMANAGER_PORT" "http://localhost:$ALERTMANAGER_PORT/-/ready" "receiver: Mailpit (never pages)"
  row "Loki" "http://localhost:$LOKI_PORT" "http://localhost:$LOKI_PORT/ready" "query API (Grafana → Explore)"
  row "Tempo" "http://localhost:$TEMPO_PORT" "http://localhost:$TEMPO_PORT/ready" "query API (Grafana → Explore)"
  row "OTLP (Collector)" "http://localhost:$OTEL_HTTP_PORT" "tcp:localhost:$OTEL_HTTP_PORT" "gRPC :$OTEL_GRPC_PORT"
  say ""
  say "${c_dim}make status · make logs SERVICES=api · make obs-fire-test-alert · make down (stops apps and stand-ins; never your own services)${c_off}"
}

cmd="${1:-status}"
case "$cmd" in
  up) cmd_up ;;
  check) cmd_check ;;
  status) cmd_status ;;
  *) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
