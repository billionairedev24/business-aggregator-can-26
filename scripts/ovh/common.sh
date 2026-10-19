#!/usr/bin/env bash
# Shared by scripts/ovh/*.sh (docs/runbooks/ovh.md). Sourced, never run. bash 3.2 (macOS) compatible: no associative
# arrays, no mapfile, no ${var,,}.

OVH_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVH_NAMESPACE=${OVH_NAMESPACE:-northline-dryrun}

die() { echo "$*" >&2; exit 1; }

# ovh_target <name> — checks the target and sets OVH_TARGET, OVH_TF (Terraform root), OVH_ENV_FILE (secrets file).
ovh_target() {
  case "${1:-}" in
    ovh-vps) OVH_TF="$OVH_REPO/infra/terraform/envs/ovh/vps" ;;
    ovh-mks) OVH_TF="$OVH_REPO/infra/terraform/envs/ovh/mks" ;;
    *) die "TARGET must be ovh-vps or ovh-mks (got '${1:-}')" ;;
  esac
  OVH_TARGET=$1
  OVH_ENV_FILE=${OVH_SECRETS_FILE:-$OVH_REPO/.ovh/$OVH_TARGET.env}
}

# tf_out <output> [jq filter] — a Terraform output of the target's root as JSON (or filtered with jq -r).
tf_out() {
  local json
  json=$(terraform -chdir="$OVH_TF" output -json "$1") || die "terraform output $1 failed in $OVH_TF (terraform apply first)"
  if [ -n "${2:-}" ]; then printf '%s' "$json" | jq -r "$2"; else printf '%s\n' "$json"; fi
}

# env_get <KEY> — a value from the secrets file (KEY=value lines; the value is taken literally, never expanded:
# bcrypt hashes contain $).
env_get() {
  [ -f "$OVH_ENV_FILE" ] || return 0
  awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$OVH_ENV_FILE"
}

need() {
  local c
  for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "$c is needed (not found on PATH) — docs/runbooks/ovh.md § What you need"; done
}
