#!/usr/bin/env bash
# OVHcloud targets (docs/runbooks/ovh.md § Today): Terraform state in an OVHcloud Object Storage bucket (S3 backend),
# so the pipeline (.github/workflows/ovh.yml) and a Mac share one state and no state ever sits in the repository, a
# workflow artifact or a runner's disk after the job.
#
#   scripts/ovh/tf-state.sh ensure [TARGET]      create the state bucket once (idempotent; versioning on) — aws CLI
#   scripts/ovh/tf-state.sh init ovh-vps|ovh-mks terraform init of the target's root on that bucket
#                                                (make ovh-tf-init TARGET=…)
#
# Environment:
#   TF_STATE_BUCKET      the bucket (e.g. northline-tfstate-1a2b3c4d). Empty: `init` keeps the local state (the
#                        runbook's default for a single Mac) and `ensure` does nothing.
#   TF_STATE_ACCESS_KEY, TF_STATE_SECRET_KEY   S3 keys of an Object Storage user (Public Cloud › Object Storage › Users,
#                        role "ObjectStore operator"); or AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY already exported.
#                        Every later terraform command of that shell needs them too (the backend reads the state).
#   OVH_LOCATION [us-east-va]  the state bucket lives in the same location as the rest (S-160, locations.json):
#                        TF_STATE_ENDPOINT and TF_STATE_REGION default to its S3 endpoint and region
#                        (us-east-va: https://s3.us-east-va.io.cloud.ovh.us / us-east-va; ca-bhs: …bhs.io.cloud.ovh.net / bhs).
#   MIGRATE=1            init -migrate-state: move an existing local state into the bucket once.
#
# The backend block is written at run time to <root>/backend_override.tf (git-ignored): without TF_STATE_BUCKET the
# roots stay on local state exactly as before. No state locking (OVHcloud's support for S3 conditional writes is
# unverified): the pipeline runs one job per target at a time (its concurrency group); from a Mac, don't run Terraform
# while a pipeline run of the same target is going.
set -euo pipefail
. "$(dirname "$0")/common.sh"

cmd=${1:-}
# The location: OVH_LOCATION, else the target's .ovh/<target>.env (a Mac), else us-east-va.
if [ -z "${OVH_LOCATION:-}" ] && [ -n "${2:-}" ]; then ovh_target "$2"; OVH_LOCATION=$(env_get OVH_LOCATION); fi
ovh_location "${OVH_LOCATION:-}"
TF_STATE_ENDPOINT=${TF_STATE_ENDPOINT:-$(loc_get s3_endpoint)}
TF_STATE_REGION=${TF_STATE_REGION:-$(loc_get s3_region)}
if [ -n "${TF_STATE_ACCESS_KEY:-}" ]; then export AWS_ACCESS_KEY_ID="$TF_STATE_ACCESS_KEY"; fi
if [ -n "${TF_STATE_SECRET_KEY:-}" ]; then export AWS_SECRET_ACCESS_KEY="$TF_STATE_SECRET_KEY"; fi
# Third-party S3: send checksums only when an operation requires them (newer AWS CLIs add them by default).
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required

need_keys() {
  [ -n "${AWS_ACCESS_KEY_ID:-}" ] && [ -n "${AWS_SECRET_ACCESS_KEY:-}" ] \
    || die "TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY: the state bucket's S3 keys (docs/runbooks/ovh.md § Today, step 4)"
}

ensure() {
  [ -n "${TF_STATE_BUCKET:-}" ] || { echo "TF_STATE_BUCKET empty: local Terraform state"; return 0; }
  need aws
  need_keys
  local s3=(aws --endpoint-url "$TF_STATE_ENDPOINT" --region "$TF_STATE_REGION")
  if "${s3[@]}" s3api head-bucket --bucket "$TF_STATE_BUCKET" >/dev/null 2>&1; then
    echo "state bucket $TF_STATE_BUCKET exists"
  else
    "${s3[@]}" s3api create-bucket --bucket "$TF_STATE_BUCKET" \
      --create-bucket-configuration "LocationConstraint=$TF_STATE_REGION" >/dev/null \
      || die "could not create $TF_STATE_BUCKET (taken by someone else? pick another TF_STATE_BUCKET)"
    echo "state bucket $TF_STATE_BUCKET created in $OVH_LOCATION ($TF_STATE_ENDPOINT)"
  fi
  # Versioning: an earlier state can be restored after a bad apply (Object Storage › the bucket › versions).
  "${s3[@]}" s3api put-bucket-versioning --bucket "$TF_STATE_BUCKET" --versioning-configuration Status=Enabled >/dev/null \
    || echo "warning: could not turn versioning on for $TF_STATE_BUCKET (turn it on in the console)" >&2
}

init() {
  ovh_target "${1:-}"
  need terraform
  local override="$OVH_TF/backend_override.tf" flags=(-input=false)
  if [ -z "${TF_STATE_BUCKET:-}" ]; then
    [ ! -f "$override" ] || die "$override exists but TF_STATE_BUCKET is empty — set it, or delete the file for local state"
    terraform -chdir="$OVH_TF" init "${flags[@]}"
    return
  fi
  need_keys
  printf '%s\n' '# Written by scripts/ovh/tf-state.sh (git-ignored): the S3 backend on OVHcloud Object Storage.' \
    'terraform {' '  backend "s3" {}' '}' >"$override"
  conf=$(mktemp); trap 'rm -f "${conf:-}"' EXIT
  # No secret in here: the keys come from AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY.
  cat >"$conf" <<EOF
bucket                      = "$TF_STATE_BUCKET"
key                         = "ovh/${OVH_TARGET#ovh-}/terraform.tfstate"
region                      = "$TF_STATE_REGION"
endpoints                   = { s3 = "$TF_STATE_ENDPOINT" }
skip_credentials_validation = true
skip_region_validation      = true
skip_requesting_account_id  = true
skip_metadata_api_check     = true
skip_s3_checksum            = true
use_path_style              = true
EOF
  if [ "${MIGRATE:-0}" = 1 ]; then flags+=(-migrate-state); else flags+=(-reconfigure); fi
  terraform -chdir="$OVH_TF" init "${flags[@]}" -backend-config="$conf"
  echo "state: s3://$TF_STATE_BUCKET/ovh/${OVH_TARGET#ovh-}/terraform.tfstate"
}

case "$cmd" in
  ensure) ensure ;;   # [TARGET] only picks the location (above)
  init) init "${2:-}" ;;
  *) die "usage: $0 ensure [ovh-vps|ovh-mks] | init ovh-vps|ovh-mks" ;;
esac
