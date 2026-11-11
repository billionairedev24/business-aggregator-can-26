#!/usr/bin/env bash
# S-194 one-click demo data (docs/runbooks/demo.md § One-click demo data): a whole marketplace — every category the
# consumer site links to with live, verified businesses, priced listings and photos, kitchens open now, history,
# reviews and work waiting in the Studio and the console — written straight into the demo database by the api image's
# DemoSeed tool (server/api/src/tools/java/ca/northline/tools/demo), with no manual step before it.
#
#   scripts/demo/seed-demo.sh local [--reset]      make demo-seed ENV=local [RESET=true]   the stack on this machine
#   scripts/demo/seed-demo.sh ovh-vps [--reset]    make demo-seed ENV=ovh-vps …            kubectl's current context
#   scripts/demo/seed-demo.sh ovh-mks [--reset]    (the pipeline: .github/workflows/ovh.yml action seed-demo)
#
# Idempotent: a re-run updates the same rows (ids come from the plan's keys) and moves today's jobs and tonight's run to
# the new today. --reset wipes the demo rows first (and what people did with them), then seeds again.
#
# Accounts: the seed signs nobody up — it writes six accounts (console admin, trust & safety reviewer, owner,
# applicant, two customers) with a password and an authenticator key made HERE, once, and kept:
#   local    .demo/local-accounts.env (mode 600, git-ignored)
#   ovh-*    Kubernetes Secret northline-demo-accounts in the target's namespace (never in the repository, an artifact
#            or a log; the Job reads it as environment variables)
# They survive --reset; ROTATE=1 makes new ones. Read them with make demo-creds ENV=<env> (scripts/demo/demo-creds.sh).
#
# Environment: DEMO_MARKET (a market id; default: the region configuration's default market), OVH_NAMESPACE
# [northline-dryrun], DEMO_SUMMARY_FILE (where the run's Markdown summary goes; the pipeline's step summary).
# bash 3.2 compatible.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# The plan's sign-in accounts (server/api/src/tools/resources/demo-seed/plan.json `accounts`; DemoSeedPlanTest checks
# this list against it).
ACCOUNTS="admin reviewer owner applicant customer customer2"
SECRET=northline-demo-accounts

die() { echo "seed-demo: $*" >&2; exit 1; }
need() { local c; for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "$c is needed (not on PATH)"; done; }
upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

ENV_NAME=${1:-}
MODE=seed
[ "${2:-}" = --reset ] && MODE=reset
case "${RESET:-}" in (true | 1 | yes) MODE=reset ;; esac

# new_credentials — KEY=value lines for every account (a 24-character password, a 160-bit base32 TOTP key). Made by
# Node's CSPRNG (node is needed for the web apps anyway; macOS has no base32 tool).
new_credentials() {
  need node
  node -e '
    const { randomBytes } = require("node:crypto");
    const b32 = (buf) => { const a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; let bits = 0, v = 0, out = "";
      for (const byte of buf) { v = (v << 8) | byte; bits += 8; while (bits >= 5) { out += a[(v >>> (bits - 5)) & 31]; bits -= 5; } }
      return out; };
    for (const k of process.argv.slice(1)) {
      const pw = randomBytes(18).toString("base64").replace(/[+/=]/g, "x") + "-9a";
      console.log(`DEMO_ACCOUNT_${k.toUpperCase()}_PASSWORD=${pw}`);
      console.log(`DEMO_ACCOUNT_${k.toUpperCase()}_TOTP=${b32(randomBytes(20))}`);
    }' $ACCOUNTS
}

seed_local() {
  local file="$ROOT/.demo/local-accounts.env" key
  mkdir -p "$ROOT/.demo"
  if [ ! -s "$file" ] || [ "${ROTATE:-0}" = 1 ]; then
    (umask 077; new_credentials >"$file")
    echo "seed-demo: new demo account credentials in .demo/local-accounts.env (make demo-creds ENV=local)"
  fi
  chmod 600 "$file"
  # northline-auth's local TOTP key (server/auth application-local.yml) unless TOTP_KEY is set: the seed seals the
  # authenticator keys with the key auth opens them with.
  key=${TOTP_KEY:-$(awk '/^ *totp-key:/ { print $2; exit }' "$ROOT/server/auth/src/main/resources/application-local.yml")}
  [ -n "$key" ] || die "no TOTP_KEY (server/auth/src/main/resources/application-local.yml has none)"
  set -a
  # shellcheck disable=SC1090
  . "$file"
  set +a
  local flags="--max-workers=2"
  [ "$MODE" = reset ] && flags="$flags -Pdemo.reset=true"
  # shellcheck disable=SC2086
  (cd "$ROOT/server" && NORTHLINE_DEMO_SEED=true TOTP_KEY="$key" ./gradlew --init-script "$ROOT/ci/gradle/maven-mirror.init.gradle.kts" \
    -q $flags :api:demoSeed)
}

# The Job: the api Deployment's pod (its image, environment, volumes, labels for the data services' network policies)
# with the seed as the command, the Secret's credentials, and northline-auth's TOTP key (from the auth Deployment, so
# the authenticator keys are sealed with the key auth opens them with).
seed_cluster() {
  need kubectl jq
  local ns=${OVH_NAMESPACE:-northline-dryrun} tmp job api auth totp totp_id
  kubectl -n "$ns" get deployment northline-api >/dev/null 2>&1 \
    || die "no deployment northline-api in namespace $ns (deploy first: the pipeline's action deploy) — context $(kubectl config current-context 2>/dev/null)"
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  if ! kubectl -n "$ns" get secret "$SECRET" >/dev/null 2>&1 || [ "${ROTATE:-0}" = 1 ]; then
    (umask 077; new_credentials >"$tmp/accounts.env")
    kubectl -n "$ns" create secret generic "$SECRET" --from-env-file="$tmp/accounts.env" --dry-run=client -o json \
      | jq '.metadata.labels = {"app.kubernetes.io/part-of": "northline", "app.kubernetes.io/component": "demo-seed"}' \
      | kubectl -n "$ns" apply -f - >/dev/null
    rm -f "$tmp/accounts.env"
    echo "seed-demo: new demo account credentials in secret/$SECRET (make demo-creds ENV=$ENV_NAME)"
  fi
  api=$(kubectl -n "$ns" get deployment northline-api -o json)
  auth=$(kubectl -n "$ns" get deployment northline-auth -o json)
  totp=$(printf '%s' "$auth" | jq -c '[.spec.template.spec.containers[0].env[]? | select(.name == "TOTP_KEY")][0] // empty')
  [ -n "$totp" ] || die "deployment northline-auth has no TOTP_KEY variable: can't seal the authenticator keys"
  totp_id=$(printf '%s' "$auth" | jq -c '[.spec.template.spec.containers[0].env[]? | select(.name == "TOTP_KEY_ID")][0] // empty')
  job="northline-demo-seed-$(date +%s)"
  printf '%s' "$api" | jq --arg job "$job" --arg mode "$MODE" --arg secret "$SECRET" --argjson totp "$totp" \
    --argjson totpId "${totp_id:-null}" --arg market "${DEMO_MARKET:-}" '
    .spec.template as $t
    | ($t.spec.containers[0]) as $c
    | {
        apiVersion: "batch/v1", kind: "Job",
        metadata: { name: $job, labels: { "app.kubernetes.io/part-of": "northline", "app.kubernetes.io/component": "demo-seed" } },
        spec: {
          backoffLimit: 0, activeDeadlineSeconds: 1800, ttlSecondsAfterFinished: 3600,
          template: {
            metadata: { labels: { "app.kubernetes.io/part-of": "northline", "app.kubernetes.io/component": "demo-seed",
                                  "app.kubernetes.io/name": "northline-demo-seed" } },
            spec: ($t.spec | del(.containers, .initContainers) | .restartPolicy = "Never"
              | .containers = [ ($c | del(.ports, .livenessProbe, .readinessProbe, .startupProbe, .lifecycle)
                | .name = "demo-seed"
                | .command = ["java", "-XX:MaxRAMPercentage=75", "-Dlogback.configurationFile=/app/tools/logback-tools.xml",
                              "-cp", "/app/tools:/app/tools/lib/*:/app/resources:/app/classes:/app/libs/*",
                              "ca.northline.tools.demo.DemoSeed", $mode]
                | del(.args)
                | .env = ([(.env // [])[] | select(.name != "TOTP_KEY" and .name != "TOTP_KEY_ID" and .name != "JAVA_TOOL_OPTIONS")]
                          + [{name: "NORTHLINE_DEMO_SEED", value: "true"}, $totp] + (if $totpId then [$totpId] else [] end)
                          + (if $market != "" then [{name: "DEMO_MARKET", value: $market}] else [] end))
                | .envFrom = ((.envFrom // []) + [{ secretRef: { name: $secret }, prefix: "" }])) ])
          }
        }
      }' >"$tmp/job.json"
  kubectl -n "$ns" apply -f "$tmp/job.json" >/dev/null
  echo "seed-demo: job/$job ($MODE) started in $ns — following its log"
  local pod="" i
  for i in $(seq 1 120); do
    pod=$(kubectl -n "$ns" get pods -l job-name="$job" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
    if [ -n "$pod" ] && kubectl -n "$ns" get pod "$pod" -o jsonpath='{.status.phase}' 2>/dev/null | grep -qE 'Running|Succeeded|Failed'; then break; fi
    sleep 5
  done
  [ -n "$pod" ] || die "job/$job made no pod (kubectl -n $ns describe job $job)"
  kubectl -n "$ns" logs -f "$pod" >"$tmp/log" 2>&1 || true
  kubectl -n "$ns" wait --for=condition=complete "job/$job" --timeout=30m >/dev/null 2>&1 &
  local ok=$!
  kubectl -n "$ns" wait --for=condition=failed "job/$job" --timeout=30m >/dev/null 2>&1 &
  local failed=$!
  while kill -0 "$ok" 2>/dev/null && kill -0 "$failed" 2>/dev/null; do sleep 2; done
  kill "$ok" "$failed" 2>/dev/null || true
  # The log holds counts and emails only (the tool never prints a credential).
  cat "$tmp/log"
  if [ "$(kubectl -n "$ns" get job "$job" -o jsonpath='{.status.succeeded}')" != 1 ]; then
    die "job/$job failed (log above; kubectl -n $ns describe job $job)"
  fi
  if [ -n "${DEMO_SUMMARY_FILE:-}" ]; then
    sed -n '/^### Demo data/,$p' "$tmp/log" >"$DEMO_SUMMARY_FILE"
  fi
  # The search read model catches up by itself: the worker's reconcile sweep re-indexes merchants whose rows changed
  # (docs/runbooks/search.md § 7), within a minute or two of the commit.
}

case "$ENV_NAME" in
  local) seed_local ;;
  ovh-vps | ovh-mks) seed_cluster ;;
  *) die "usage: $0 local|ovh-vps|ovh-mks [--reset]   (make demo-seed ENV=… [RESET=true])" ;;
esac
