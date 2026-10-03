#!/usr/bin/env bash
# S-119: runs a load-test profile against a target and writes its results. docs/runbooks/load-testing.md.
#
#   loadtest/run.sh smoke|load|stress|soak
#
#   TARGET=local (default) | staging     loadtest/targets/<TARGET>.env: API_URL, AUTH_MODE, MANIFEST, TOKENS_FILE
#   SCENARIOS=all | search,checkout,kds,badges,…   LOAD_SCALE=1   LOAD_DURATION=10m   SOAK_DURATION=2h (local: 15m)
#   LOAD_LOCK=<file>   local runs wait for this lock (flock) so only one full-stack run uses the machine at a time
#
# Results: loadtest/results/<UTC time>-<target>-<profile>/ — summary.json (k6), timeline.csv.gz (every sample),
# report.md (per scenario: rate, p95, p99, errors, pass/fail against the SLOs; per minute: rate, p95, errors), and for a
# local run server.csv (the api's heap after GC, threads, RSS, database connections every 15 s) and statements.txt
# (the statements that took the most database time, pg_stat_statements). Exit code: k6's
# (0 = every threshold held, 99 = an SLO threshold failed).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
profile="${1:-smoke}"
target="${TARGET:-local}"
case "$profile" in smoke | load | stress | soak) ;; *) echo "usage: loadtest/run.sh smoke|load|stress|soak" >&2; exit 2 ;; esac
case "$target" in
  prod | production) echo "Load tests never run against prod (docs/runbooks/load-testing.md)." >&2; exit 1 ;;
esac
env_file="$here/targets/$target.env"
[ -f "$env_file" ] || { echo "No $env_file (targets: $(ls "$here/targets" | sed 's/\.env$//' | tr '\n' ' '))" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
. "$env_file"
set +a
if [ "$target" = local ] && [ "$profile" = soak ]; then export SOAK_DURATION="${SOAK_DURATION:-15m}"; fi
export PROFILE="$profile" MANIFEST="${MANIFEST:-$here/.data/manifest.json}"
case "$MANIFEST" in /*) ;; *) MANIFEST="$here/$MANIFEST" ;; esac
[ -f "$MANIFEST" ] || { echo "No manifest $MANIFEST: loadtest/stack.sh up (local) or the staging one (runbook)" >&2; exit 1; }
if [ -n "${TOKENS_FILE:-}" ]; then case "$TOKENS_FILE" in /*) ;; *) export TOKENS_FILE="$here/$TOKENS_FILE" ;; esac; fi

k6="$("$here/k6.sh")"
out="$here/results/$(date -u +%Y%m%dT%H%M%SZ)-$target-$profile"
mkdir -p "$out"
echo "k6 $profile → $API_URL (scenarios ${SCENARIOS:-all}, scale ${LOAD_SCALE:-1}); results in $out"

run_k6() {
  local sampler="" stack=""
  if [ "$target" = local ] && [ -f "$here/.data/api.pid" ]; then
    stack=1
    "$here/stack.sh" reset-statements 2>/dev/null || true
    "$here/sample.sh" "$(cat "$here/.data/api.pid")" "$out/server.csv" &
    sampler=$!
  fi
  set +e
  "$k6" run --quiet --summary-export "$out/summary.json" --out "csv=$out/timeline.csv.gz" "$here/main.js" \
    2>&1 | tee "$out/k6.log"
  local code=${PIPESTATUS[0]}
  set -e
  if [ -n "$sampler" ]; then kill "$sampler" 2>/dev/null || true; fi
  if [ -n "$stack" ]; then "$here/stack.sh" statements 30 >"$out/statements.txt" 2>/dev/null || true; fi
  python3 "$here/report.py" "$out" >"$out/report.md" || echo "report.py failed (the raw results are in $out)" >&2
  echo "report: $out/report.md (k6 exit $code)"
  return "$code"
}

if [ "$target" = local ] && [ -n "${LOAD_LOCK:-}" ] && command -v flock >/dev/null 2>&1; then
  export -f run_k6
  export here out k6 target
  exec flock "$LOAD_LOCK" bash -c run_k6
fi
run_k6
