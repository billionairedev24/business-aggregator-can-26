#!/usr/bin/env bash
# make grafana-move-port [PORT=3001]: move YOUR Grafana off :3000 (the consumer web app's port) and point .env at it.
# Finds a Homebrew Grafana (grafana.ini under $(brew --prefix)/etc/grafana, restarted with brew services) or a Docker
# container publishing :3000 (re-created with the same image, env, volumes and name on PORT). Anything else: prints the
# one setting to change. Then sets GRAFANA_URL=http://localhost:PORT in the root .env. Safe to run again.
# bash 3.2 (macOS), BSD or GNU userland.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-3001}"
say() { printf '%s\n' "$*"; }
die() { printf '✗ %s\n' "$*" >&2; exit 1; }

answers() { curl -fsS -m 3 "http://localhost:$1/api/health" >/dev/null 2>&1; }

set_env_url() {
  local f="$ROOT/.env" line="GRAFANA_URL=http://localhost:$PORT"
  [ -f "$f" ] || { [ -f "$ROOT/.env.example" ] && cp "$ROOT/.env.example" "$f" || : > "$f"; }
  if grep -qE '^[[:space:]]*(export[[:space:]]+)?GRAFANA_URL[[:space:]]*=' "$f"; then
    sed -i.bak -E "s#^[[:space:]]*(export[[:space:]]+)?GRAFANA_URL[[:space:]]*=.*#$line#" "$f" && rm -f "$f.bak"
  else
    printf '%s\n' "$line" >> "$f"
  fi
  say "✓ $f: $line"
}

wait_for() { local i; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do answers "$PORT" && return 0; sleep 1; done; return 1; }

if answers "$PORT"; then
  say "✓ a Grafana already answers on :$PORT"
  set_env_url
  exit 0
fi

# Homebrew
if command -v brew >/dev/null 2>&1 && ini="$(brew --prefix 2>/dev/null)/etc/grafana/grafana.ini" && [ -f "$ini" ]; then
  say "Homebrew Grafana: $ini"
  cp "$ini" "$ini.before-northline"
  if grep -qE '^[;#]?[[:space:]]*http_port[[:space:]]*=' "$ini"; then
    sed -i.bak -E "s/^[;#]?[[:space:]]*http_port[[:space:]]*=.*/http_port = $PORT/" "$ini" && rm -f "$ini.bak"
  else
    # no http_port line at all: add it under [server]
    awk -v p="$PORT" '{print} /^\[server\]/{print "http_port = " p}' "$ini" > "$ini.tmp" && mv "$ini.tmp" "$ini"
  fi
  say "  http_port = $PORT (the old file is $ini.before-northline)"
  brew services restart grafana >/dev/null
  wait_for || die "Grafana did not come back on :$PORT — brew services info grafana; the log is in $(brew --prefix)/var/log/grafana"
  say "✓ Grafana answers on http://localhost:$PORT"
  set_env_url
  exit 0
fi

# Docker container publishing :3000
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  name="$(docker ps --filter publish=3000 --format '{{.Names}}' | head -n 1)"
  if [ -n "$name" ] && docker inspect -f '{{.Config.Image}}' "$name" | grep -qi grafana; then
    say "Docker Grafana: container $name"
    image="$(docker inspect -f '{{.Config.Image}}' "$name")"
    args=()
    while IFS= read -r e; do [ -n "$e" ] && args+=(-e "$e"); done <<EOF
$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$name")
EOF
    while IFS= read -r m; do [ -n "$m" ] && args+=(-v "$m"); done <<EOF
$(docker inspect -f '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}}:{{.Destination}}{{else}}{{.Source}}:{{.Destination}}{{end}}{{println}}{{end}}' "$name")
EOF
    restart="$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$name")"
    [ -n "$restart" ] && [ "$restart" != no ] && args+=(--restart "$restart")
    docker rm -f "$name" >/dev/null
    docker run -d --name "$name" -p "$PORT:3000" "${args[@]+"${args[@]}"}" "$image" >/dev/null
    wait_for || die "the re-created container did not answer on :$PORT — docker logs $name"
    say "✓ Grafana ($name) answers on http://localhost:$PORT (same image, environment and volumes)"
    set_env_url
    exit 0
  fi
fi

die "no Homebrew or Docker Grafana found on :3000. Set http_port = $PORT under [server] in your grafana.ini (or GF_SERVER_HTTP_PORT=$PORT), restart Grafana, then run this again"
