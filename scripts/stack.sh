#!/usr/bin/env bash
# Local app runner behind the Makefile (S-124): start, stop and inspect the Northline apps on one machine.
# The stand-ins (Postgres, Valkey, Kafka, …) are docker compose's job (make up / make down); this script runs the apps.
#
#   scripts/stack.sh up [service…]      start services in the background and wait until each answers (default: api studio)
#   scripts/stack.sh down [service…]    stop them (default: every running one)
#   scripts/stack.sh status             what runs, where, and whether it answers; the useful URLs
#   scripts/stack.sh logs [service…]    follow the logs, one prefix per service
#   scripts/stack.sh dev [service…]     up + logs in the foreground; Ctrl-C stops what this run started
#
# Services: api auth bff bff-consumer worker studio consumer storybook  ("all" = every one of them)
#   bff = studio-bff (:8082), bff-consumer = the same jar with the `consumer` profile (:8081).
#   studio / consumer use dev auth (DEV_USER / CONSUMER_DEV_USER, no sign-in, only the api needed) unless their BFF is
#   started with them or already runs; DEV_AUTH=1 or DEV_AUTH=0 forces it either way.
#
# Each service runs in its own session / process group (setsid on Linux, Perl's POSIX::setsid on macOS) and is stopped
# by that group id only, never by name, so a stop can't hit anything this script didn't start. PIDs and logs live in
# .run/ (git-ignored). bash 3.2 compatible (macOS): no mapfile, no associative arrays.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${RUN_DIR:-$ROOT/.run}"
LOG_DIR="$RUN_DIR/logs"
SPRING_PROFILE="${SPRING_PROFILE:-local}"
GRADLE_FLAGS="${GRADLE_FLAGS:---max-workers=2}"
DEV_USER="${DEV_USER:-01J9ZD3V00000000000000RAV1}"                   # Ravi Sandhu, owner of the three seeded businesses
CONSUMER_DEV_USER="${CONSUMER_DEV_USER:-01J9ZD3V0000000000000C0001}" # Amara Osei, the seeded consumer
DEV_AUTH="${DEV_AUTH:-auto}"

ALL_SERVICES="auth api bff bff-consumer worker studio consumer storybook"
DEFAULT_SERVICES="api studio"
JAVA_SERVICES="auth api bff bff-consumer worker"

c_dim=$'\033[2m'; c_ok=$'\033[32m'; c_bad=$'\033[31m'; c_warn=$'\033[33m'; c_off=$'\033[0m'
[ -t 1 ] || { c_dim=; c_ok=; c_bad=; c_warn=; c_off=; }
say() { printf '%s\n' "$*"; }
die() { say "${c_bad}✗ $*${c_off}" >&2; exit 1; }

port_of() {
  case "$1" in
    api) echo 8080 ;; auth) echo 9000 ;; bff) echo 8082 ;; bff-consumer) echo 8081 ;; worker) echo 8084 ;;
    studio) echo 3100 ;; consumer) echo 3000 ;; storybook) echo 6006 ;;
    *) die "unknown service '$1' (services: $ALL_SERVICES, or all)" ;;
  esac
}
health_url() {
  case "$1" in
    api | auth | bff | bff-consumer | worker) echo "http://localhost:$(port_of "$1")/actuator/health" ;;
    *) echo "http://localhost:$(port_of "$1")/" ;;
  esac
}
is_java() { case " $JAVA_SERVICES " in *" $1 "*) return 0 ;; esac; return 1; }
contains() { case " $1 " in *" $2 "*) return 0 ;; esac; return 1; }
pid_file() { echo "$RUN_DIR/$1.pid"; }
running() { local f; f="$(pid_file "$1")"; [ -f "$f" ] && kill -0 "$(cat "$f")" 2>/dev/null; }

# dev_auth <bff service> <services being started>: true when the web app should use dev auth
dev_auth() {
  case "$DEV_AUTH" in 1 | true | yes) return 0 ;; 0 | false | no) return 1 ;; esac
  contains "$2" "$1" && return 1
  running "$1" && return 1
  return 0
}

gradle() { echo "cd '$ROOT/server' && ./gradlew $GRADLE_FLAGS --console=plain $*"; }
command_of() { # command_of <service> <services being started>
  local profile="--spring.profiles.active=$SPRING_PROFILE"
  case "$1" in
    api) gradle ":api:bootRun --args='$profile'" ;;
    auth) gradle ":auth:bootRun --args='$profile'" ;;
    bff) gradle ":bff:bootRun --args='$profile'" ;;
    bff-consumer) gradle ":bff:bootRun --args='$profile,consumer'" ;;
    worker) if [ "$SPRING_PROFILE" = local ]; then gradle ":worker:bootRun"; else gradle ":worker:bootRun --args='$profile'"; fi ;;
    studio)
      if dev_auth bff "$2"; then echo "cd '$ROOT/web' && NL_DEV_USER=$DEV_USER VITE_NL_DEV_STEP_UP=1 pnpm --filter @northline/studio dev"
      else echo "cd '$ROOT/web' && pnpm --filter @northline/studio dev"; fi ;;
    consumer)
      if dev_auth bff-consumer "$2"; then echo "cd '$ROOT/web' && NL_DEV_USER=$CONSUMER_DEV_USER NL_BFF_URL=http://localhost:8080 pnpm --filter @northline/consumer dev"
      else echo "cd '$ROOT/web' && pnpm --filter @northline/consumer dev"; fi ;;
    storybook) echo "cd '$ROOT/web' && pnpm --filter @northline/ui storybook --no-open" ;;
  esac
}

expand() { # expand [service…] → space-separated list in start order
  local wanted="" s out=""
  if [ $# -eq 0 ]; then wanted="$DEFAULT_SERVICES"; else
    for s in "$@"; do
      if [ "$s" = all ]; then wanted="$wanted $ALL_SERVICES"; else port_of "$s" >/dev/null; wanted="$wanted $s"; fi
    done
  fi
  for s in $ALL_SERVICES; do contains "$wanted" "$s" && out="$out $s"; done
  echo "${out# }"
}

# launch <log> <script>: run a bash script detached in a new session (setsid on Linux, Perl's POSIX::setsid where
# util-linux is missing, e.g. macOS).
launch() {
  if command -v setsid >/dev/null 2>&1; then
    setsid -f bash -c "$2" >"$1" 2>&1 </dev/null
  else
    perl -MPOSIX -e 'my $p = fork; die "fork: $!\n" unless defined $p; exit 0 if $p; POSIX::setsid() == -1 and die "setsid: $!\n"; exec @ARGV' \
      bash -c "$2" >"$1" 2>&1 </dev/null
  fi
}

port_listening() {
  if command -v lsof >/dev/null 2>&1; then lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; return; fi
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

java_major() { "$1/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ { print $2 }'; }

STARTED=""
start_one() { # start_one <service> <services being started>
  local s="$1" port cmd pidf
  port="$(port_of "$s")"
  cmd="$(command_of "$s" "$2")"
  if running "$s"; then
    if [ "$(cat "$RUN_DIR/$s.cmd" 2>/dev/null)" = "$cmd" ]; then
      say "${c_dim}• $s already running (pid $(cat "$(pid_file "$s")"))${c_off}"; return
    fi
    say "${c_warn}↻${c_off} $s is running with different settings — restarting it"
    stop_one "$s"
  fi
  if port_listening "$port"; then
    local who="something this script did not start"
    if command -v lsof >/dev/null 2>&1; then
      local held; held="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1" (pid "$2")"}')"
      [ -n "$held" ] && who="$held"
    fi
    die "port $port ($s) is taken by $who — stop it first"
  fi
  mkdir -p "$LOG_DIR"
  pidf="$(pid_file "$s")"; rm -f "$pidf"
  printf '%s' "$cmd" >"$RUN_DIR/$s.cmd"
  # The service's shell becomes a session (and process-group) leader and records its own pid: the group `down` signals.
  launch "$LOG_DIR/$s.log" "echo \$\$ > '$pidf' && $cmd"
  local i; for i in $(seq 1 50); do [ -s "$pidf" ] && break; sleep 0.1; done
  [ -s "$pidf" ] || die "$s did not start — see .run/logs/$s.log"
  STARTED="$STARTED $s"
  say "${c_dim}… $s starting (log: .run/logs/$s.log)${c_off}"
}

wait_healthy() {
  local s="$1" url tries=0 max=120
  url="$(health_url "$s")"
  is_java "$s" && max=360 # the first start compiles, migrates and seeds
  while ! curl -sf -o /dev/null --max-time 2 "$url"; do
    running "$s" || { say "${c_bad}✗ $s exited — last lines of .run/logs/$s.log:${c_off}"; tail -n 25 "$LOG_DIR/$s.log" >&2; return 1; }
    tries=$((tries + 1)); [ "$tries" -ge "$max" ] && { say "${c_bad}✗ $s did not answer on $url${c_off}"; return 1; }
    sleep 1
  done
  say "${c_ok}✓${c_off} $s  ${c_dim}$url${c_off}"
}

stop_one() {
  local s="$1" pid i port
  running "$s" || { rm -f "$(pid_file "$s")" "$RUN_DIR/$s.cmd"; return 0; }
  pid="$(cat "$(pid_file "$s")")"
  port="$(port_of "$s")"
  kill -TERM -- "-$pid" 2>/dev/null || true
  for i in $(seq 1 40); do kill -0 -- "-$pid" 2>/dev/null || break; sleep 0.5; done
  if kill -0 -- "-$pid" 2>/dev/null; then
    kill -KILL -- "-$pid" 2>/dev/null || true
    for i in $(seq 1 20); do kill -0 -- "-$pid" 2>/dev/null || break; sleep 0.25; done
  fi
  for i in $(seq 1 40); do port_listening "$port" || break; sleep 0.25; done
  rm -f "$(pid_file "$s")" "$RUN_DIR/$s.cmd"
  say "${c_dim}■ $s stopped${c_off}"
}

ensure_js_deps() {
  local marker="$ROOT/web/node_modules/.modules.yaml"
  if [ ! -f "$marker" ] || [ "$ROOT/web/pnpm-lock.yaml" -nt "$marker" ]; then
    say "web/pnpm-lock.yaml changed since the last install — running pnpm install…"
    (cd "$ROOT/web" && pnpm install --frozen-lockfile) || die "pnpm install failed — run it yourself to see why"
  fi
}

cmd_up() {
  local services s java="" tasks=""
  services="$(expand "$@")"
  for s in $services; do is_java "$s" && java="$java $s"; done
  if [ -n "$java" ]; then
    [ -n "${JAVA_HOME:-}" ] && [ "$(java_major "$JAVA_HOME")" = 25 ] || die "Java 25 not found: install it and set JAVA_HOME (make doctor)"
    # One compile before the apps start: several Gradle builds compiling the same projects at once would race.
    for s in $java; do case "$s" in bff-consumer) s=bff ;; esac; contains "$tasks" ":$s:classes" || tasks="$tasks :$s:classes"; done
    say "${c_dim}… compiling$tasks${c_off}"
    (cd "$ROOT/server" && ./gradlew $GRADLE_FLAGS -q $tasks) || die "the server does not compile (above)"
  fi
  [ "$services" = "${java# }" ] || { command -v pnpm >/dev/null || die "pnpm is missing (Node 22: corepack enable)"; ensure_js_deps; }
  mkdir -p "$RUN_DIR"
  for s in $services; do start_one "$s" "$services"; done
  local failed=0
  for s in $services; do wait_healthy "$s" || failed=1; done
  cmd_status
  return $failed
}

cmd_down() {
  local s services="$ALL_SERVICES"
  [ $# -eq 0 ] || services="$(expand "$@")"
  for s in $services; do stop_one "$s"; done
}

cmd_status() {
  local s state any=0
  say ""
  for s in $ALL_SERVICES; do
    running "$s" || continue
    any=1
    if curl -sf -o /dev/null --max-time 2 "$(health_url "$s")"; then state="${c_ok}up${c_off}      "; else state="${c_warn}starting${c_off}"; fi
    printf '  %-14s %s  http://localhost:%s\n' "$s" "$state" "$(port_of "$s")"
  done
  [ $any = 1 ] || { say "  No app started by make runs. make up (api + studio) · make up SERVICES=all"; say ""; return 0; }
  say ""
  if running api; then
    say "  api        curl -H 'X-Dev-User: $DEV_USER' localhost:8080/api/v1/me/businesses   (dev auth: local profile only)"
  fi
  if running studio; then
    if grep -q NL_DEV_USER "$RUN_DIR/studio.cmd" 2>/dev/null; then
      say "  Studio     http://localhost:3100 — signed in as Ravi Sandhu (dev auth)"
    else
      say "  Studio     http://localhost:3100 — sign in: ravi.sandhu@example.com, authenticator key NORTHLINERAVIDEVTOTPSECRET234567"
      say "             (oathtool --totp -b <key>) or backup code ravis-00001 (README § Local sign-in)"
    fi
  fi
  if running consumer; then
    if grep -q NL_DEV_USER "$RUN_DIR/consumer.cmd" 2>/dev/null; then say "  Consumer   http://localhost:3000 — as Amara Osei (dev auth)"
    else say "  Consumer   http://localhost:3000 (through the consumer-bff)"; fi
  fi
  running auth && say "  Auth       http://localhost:9000/.well-known/openid-configuration  (SMS codes: grep 'Verification code' .run/logs/auth.log)"
  running storybook && say "  Storybook  http://localhost:6006"
  say ""
}

cmd_logs() {
  local s services="$ALL_SERVICES" files=""
  [ $# -eq 0 ] || services="$(expand "$@")"
  for s in $services; do [ -f "$LOG_DIR/$s.log" ] && files="$files $LOG_DIR/$s.log"; done
  [ -n "$files" ] || die "no logs yet — run make up first"
  local line name
  # tail prints "==> file <==" headers only for several files: start with the first one's name.
  name="${files# }"; name="${name%% *}"; name="${name##*/}"; name="${name%.log}"
  # shellcheck disable=SC2086 # files is a list of paths without spaces (.run/logs/<service>.log)
  tail -n 20 -F $files 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      "==> "*" <==") name="${line#==> }"; name="${name% <==}"; name="${name##*/}"; name="${name%.log}" ;;
      "") ;;
      *) printf '%-13s| %s\n' "$name" "$line" ;;
    esac
  done
}

cmd_dev() {
  local services
  services="$(expand "$@")"
  trap 'say ""; for s in $STARTED; do stop_one "$s"; done; exit 0' INT TERM
  if ! cmd_up $services; then
    local alive="" s
    for s in $services; do running "$s" && alive="$alive $s"; done
    [ -n "$alive" ] || { say "${c_bad}Nothing is running.${c_off}"; exit 1; }
  fi
  [ -n "$STARTED" ] || say "${c_dim}Nothing new was started (already running): following the logs; Ctrl-C leaves them running, make down stops them.${c_off}"
  # shellcheck disable=SC2086
  cmd_logs $services
}

cmd="${1:-status}"; [ $# -gt 0 ] && shift
case "$cmd" in
  up) cmd_up "$@" ;;
  down) cmd_down "$@" ;;
  status) cmd_status ;;
  logs) cmd_logs "$@" ;;
  dev) cmd_dev "$@" ;;
  *) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
