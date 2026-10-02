#!/usr/bin/env bash
# S-104 — the security scans behind `make security-*` (docs/security/README.md). Each part runs the tool when it is
# installed (PATH, or the pinned copies `make security-tools` puts in .cache/security-tools) and says "skipped" when it
# isn't, so a laptop without network still runs what it has. Reports go to build/security/.
#
#   scripts/security/scan.sh secrets|deps|sast|iac|all
#
# Gating: secrets (any finding fails) and deps (any known vulnerability fails). sast and iac only report: their rule sets
# include style and hardening advice that is triaged by hand (docs/security/findings.md).
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TOOLS=${SECURITY_TOOLS_DIR:-$ROOT/.cache/security-tools}
OUT=${SECURITY_REPORTS:-$ROOT/build/security}
mkdir -p "$OUT"
PATH="$TOOLS/bin:$TOOLS/venv/bin:$PATH"
status=0

have() { command -v "$1" >/dev/null 2>&1; }
skip() { printf '  skipped: %s\n' "$*"; }
fail() { printf '  FAILED: %s\n' "$*"; status=1; }

secrets() {
  echo "== secrets in the git history (gitleaks, .gitleaks.toml)"
  if ! have gitleaks; then skip "gitleaks not installed (make security-tools)"; return; fi
  gitleaks git --no-banner --redact --config "$ROOT/.gitleaks.toml" --report-format json \
    --report-path "$OUT/gitleaks.json" "$ROOT" || fail "gitleaks found secrets — $OUT/gitleaks.json"
}

deps() {
  echo "== dependencies (CycloneDX SBOMs of api/auth/bff/worker + pnpm lockfiles, osv-scanner)"
  if ! have osv-scanner; then skip "osv-scanner not installed (make security-tools)"; return; fi
  local sboms=()
  if (cd "$ROOT/server" && ./gradlew ${GRADLE_FLAGS:-} -q :api:cyclonedxDirectBom :auth:cyclonedxDirectBom \
      :bff:cyclonedxDirectBom :worker:cyclonedxDirectBom); then
    for app in api auth bff worker; do sboms+=(--sbom "$ROOT/server/$app/build/reports/cyclonedx-direct/bom.json"); done
  else
    fail "the Gradle SBOMs could not be built"
  fi
  # OSV_OFFLINE=1: the vulnerability database downloaded once (OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY), no API calls
  local offline=()
  if [ "${OSV_OFFLINE:-0}" = 1 ]; then offline=(--experimental-offline --experimental-download-offline-databases); fi
  osv-scanner ${offline[@]+"${offline[@]}"} --format json ${sboms[@]+"${sboms[@]}"} -L "$ROOT/web/pnpm-lock.yaml" -L "$ROOT/mobile/pnpm-lock.yaml" \
    > "$OUT/osv.json" || fail "known vulnerabilities — $OUT/osv.json (triage: docs/security/findings.md)"
}

sast() {
  echo "== static analysis (semgrep: java, typescript, react, secrets)"
  if ! have semgrep; then skip "semgrep not installed (make security-tools)"; return; fi
  # SEMGREP_RULES: a checkout of github.com/semgrep/semgrep-rules for offline use; otherwise the registry packs
  local configs=()
  if [ -n "${SEMGREP_RULES:-}" ]; then
    for dir in java typescript javascript generic/secrets dockerfile yaml/kubernetes; do
      configs+=(--config "$SEMGREP_RULES/$dir")
    done
  else
    configs=(--config p/java --config p/typescript --config p/react --config p/secrets)
  fi
  (cd "$ROOT" && semgrep scan --metrics=off --disable-version-check ${configs[@]+"${configs[@]}"} --exclude node_modules \
    --exclude build --exclude src/test --exclude __tests__ --exclude '*.test.ts' --exclude '*.test.tsx' \
    --json -o "$OUT/semgrep.json" server web/apps web/packages mobile/apps mobile/packages deploy web/Dockerfile) \
    || skip "semgrep could not run (no registry access? set SEMGREP_RULES)"
}

iac() {
  echo "== infrastructure (checkov: Terraform and the rendered chart; kube-score)"
  local helm_bin=${HELM:-helm}
  if have "$helm_bin"; then
    for env in staging prod; do
      "$helm_bin" template northline "$ROOT/deploy/helm/northline" -f "$ROOT/deploy/helm/northline/values-$env.yaml" \
        > "$OUT/chart-$env.yaml" || fail "helm template ($env)"
    done
  else
    skip "helm not installed: the chart is not rendered"
  fi
  if have checkov; then
    # BC_SKIP_MAPPING: no call to Prisma Cloud for guideline links (offline); stderr to a log
    BC_SKIP_MAPPING=TRUE checkov -d "$ROOT/infra/terraform" --framework terraform -o json --quiet --compact \
      > "$OUT/checkov-terraform.json" 2> "$OUT/checkov.log" || true
    [ -f "$OUT/chart-prod.yaml" ] && { BC_SKIP_MAPPING=TRUE checkov -f "$OUT/chart-prod.yaml" --framework kubernetes \
      -o json --quiet --compact > "$OUT/checkov-chart.json" 2>> "$OUT/checkov.log" || true; }
  else
    skip "checkov not installed (make security-tools)"
  fi
  if have kube-score && [ -f "$OUT/chart-prod.yaml" ]; then
    kube-score score "$OUT/chart-prod.yaml" -o ci > "$OUT/kube-score.txt" || true
  else
    skip "kube-score not installed, or no rendered chart"
  fi
}

case "${1:-all}" in
  secrets) secrets ;;
  deps) deps ;;
  sast) sast ;;
  iac) iac ;;
  all) secrets; deps; sast; iac ;;
  *) echo "usage: $0 secrets|deps|sast|iac|all" >&2; exit 2 ;;
esac
echo "Reports: $OUT"
exit $status
