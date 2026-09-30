#!/bin/sh
# Toolchain check for `make setup` / `make doctor` (S-124). POSIX sh: runs on macOS (bash 3.2 as sh, BSD tools) and
# Linux. Required tools fail the check; optional ones only warn, with what they are needed for.
#
#   make/toolchain.sh            # everything
#   make/toolchain.sh --required # only JDK 25, Node 22+, pnpm (exit 1 when one is missing)
#
# JAVA_HOME is the JDK the Gradle wrapper runs on (the Makefile finds one when it is unset).
set -u

red() { printf '\033[31m%s\033[0m' "$1"; }
green() { printf '\033[32m%s\033[0m' "$1"; }
yellow() { printf '\033[33m%s\033[0m' "$1"; }

missing=0
ok() { printf '  %s %-14s %s\n' "$(green ok)" "$1" "$2"; }
bad() { printf '  %s %-14s %s\n' "$(red !!)" "$1" "$2"; missing=1; }
warn() { printf '  %s %-14s %s\n' "$(yellow --)" "$1" "$2"; }

# major version: first run of digits in "$1" (e.g. "v22.12.0" → 22, "25.0.1" → 25, "1.16.4" → 1)
major() { printf '%s' "$1" | sed -E 's/^[^0-9]*([0-9]+).*/\1/'; }
# minor version: second run of digits ("1.16.4" → 16)
minor() { printf '%s' "$1" | sed -E 's/^[^0-9]*[0-9]+[^0-9]+([0-9]+).*/\1/'; }

echo "Required"

# --- JDK 25 --------------------------------------------------------------------------------------------------------
java_bin=java
[ -n "${JAVA_HOME:-}" ] && java_bin="$JAVA_HOME/bin/java"
if command -v "$java_bin" >/dev/null 2>&1; then
  jv=$("$java_bin" -version 2>&1 | sed -n -E 's/.*version "([^"]+)".*/\1/p' | head -1)
  if [ "$(major "$jv")" = 25 ]; then
    ok JDK "$jv (${JAVA_HOME:-java on PATH})"
  else
    bad JDK "found $jv at ${JAVA_HOME:-PATH}; need 25 — install Temurin 25 and export JAVA_HOME (macOS: /usr/libexec/java_home -v 25)"
  fi
else
  bad JDK "not found — install JDK 25 (Temurin) and export JAVA_HOME"
fi

# --- Node 22+ and pnpm (corepack) ---------------------------------------------------------------------------------
if command -v node >/dev/null 2>&1; then
  nv=$(node --version)
  if [ "$(major "$nv")" -ge 22 ]; then ok Node "$nv"; else bad Node "$nv; need 22 or later"; fi
else
  bad Node "not found — install Node.js 22 (nvm, fnm, brew install node@22, or nodejs.org)"
fi
if command -v pnpm >/dev/null 2>&1; then
  ok pnpm "$(pnpm --version 2>/dev/null) (web/package.json pins the version corepack uses)"
elif command -v corepack >/dev/null 2>&1; then
  bad pnpm "not on PATH — run: corepack enable"
else
  bad pnpm "not found — install Node 22 (it ships corepack), then: corepack enable"
fi

[ "${1:-}" = --required ] && exit $missing

echo
echo "Local stack (optional: only for the stand-ins you don't run yourself — docs/runbooks/local.md)"
if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then
    ok Docker "$(docker version --format '{{.Server.Version}}' 2>/dev/null)"
  else
    warn Docker "installed, but the daemon is not reachable — start Docker Desktop / colima / dockerd"
  fi
  cv=$(docker compose version --short 2>/dev/null || true)
  if [ -z "$cv" ]; then
    warn Compose "docker compose (v2) not found — needed by make up / down"
  elif [ "$(major "$cv")" -gt 2 ] || { [ "$(major "$cv")" = 2 ] && [ "$(minor "$cv")" -ge 20 ]; }; then
    ok Compose "$cv"
  else
    warn Compose "$cv; need 2.20 or later (profiles, --wait)"
  fi
else
  warn Docker "not found — needed for make up (stand-ins), the server tests (Testcontainers) and images"
fi
if command -v psql >/dev/null 2>&1; then ok psql "$(psql --version | sed -E 's/^[^0-9]*//')"; else warn psql "not found — make db-psql / db-reset use the compose container instead"; fi

echo
echo "Operators (optional: deploy, gitops and infra checks)"
if command -v helm >/dev/null 2>&1; then
  hv=$(helm version --short 2>/dev/null)
  if [ "$(major "$hv")" -ge 3 ] && { [ "$(major "$hv")" -gt 3 ] || [ "$(minor "$hv")" -ge 14 ]; }; then ok helm "$hv"; else warn helm "$hv; need 3.14 or later"; fi
else
  warn helm "not found — make helm-validate / argocd-validate (CI pins v3.19.0)"
fi
if command -v kubeconform >/dev/null 2>&1; then ok kubeconform "$(kubeconform -v 2>/dev/null)"; else warn kubeconform "not found — make helm-validate / argocd-validate (CI pins v0.7.0)"; fi
if command -v terraform >/dev/null 2>&1; then
  tv=$(terraform version 2>/dev/null | head -1 | sed -E 's/^[^0-9]*//')
  if [ "$(major "$tv")" -ge 1 ] && [ "$(minor "$tv")" -ge 9 ]; then ok terraform "$tv"; else warn terraform "$tv; need 1.9 or later (CI pins 1.16.4)"; fi
else
  warn terraform "not found — make tf-validate (CI pins 1.16.4)"
fi
if command -v tflint >/dev/null 2>&1; then ok tflint "$(tflint --version 2>/dev/null | head -1 | sed -E 's/^[^0-9]*//')"; else warn tflint "not found — make tf-lint (CI pins v0.64.0)"; fi
for t in kubectl kind; do
  if command -v $t >/dev/null 2>&1; then ok $t "found"; else warn $t "not found — make kind-up (local Helm rehearsal)"; fi
done
bv=$(bash -c 'echo ${BASH_VERSINFO[0]}' 2>/dev/null)
if [ "${bv:-0}" -ge 4 ]; then ok bash "$bv"; else warn bash "${bv:-?}; infra/terraform/scripts/validate.sh needs bash 4+ and GNU find (macOS: brew install bash findutils)"; fi

echo
if [ $missing -ne 0 ]; then
  echo "$(red 'Missing required tools') (above). Everything else is optional."
else
  echo "$(green 'Required tools present.')"
fi
exit $missing
