#!/usr/bin/env bash
# OVHcloud targets (docs/runbooks/ovh.md § Today): Terraform state in an OVHcloud Object Storage bucket (S3 backend),
# so the pipeline (.github/workflows/ovh.yml) and a Mac share one state and no state ever sits in the repository, a
# workflow artifact or a runner's disk after the job. Nothing of it is made by hand (S-161): the state's S3 user and
# key come from the OVHcloud API credentials (scripts/ovh/api.sh), the bucket from those keys.
#
#   scripts/ovh/tf-state.sh keys [TARGET]        the state's S3 keys: TF_STATE_ACCESS_KEY/SECRET_KEY when set and
#                                                working, else the Public Cloud user terraform-state (role ObjectStore
#                                                operator) and its S3 key, found or created through the API. In the
#                                                pipeline they go masked into $GITHUB_ENV; on a Mac, as export lines
#                                                for eval "$(…)" (never onto a terminal)
#   scripts/ovh/tf-state.sh ensure [TARGET]      create the state bucket once (idempotent; versioning on) — aws CLI
#   scripts/ovh/tf-state.sh init ovh-vps|ovh-mks terraform init of the target's root on that bucket
#                                                (make ovh-tf-init TARGET=…)
#   scripts/ovh/tf-state.sh teardown TARGET      after terraform destroy: the target's state (every version) out of the
#                                                bucket; when no other target's state is left, the bucket and then the
#                                                state user too — last
#
# Environment:
#   TF_STATE_BUCKET      the bucket (e.g. northline-tfstate-1a2b3c4d). Empty: `init` keeps the local state (the
#                        runbook's default for a single Mac) and `ensure` does nothing.
#   TF_STATE_ACCESS_KEY, TF_STATE_SECRET_KEY   the state user's S3 keys (make ovh-gh-setup stores them); or
#                        AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY already exported. Empty: taken from the API with
#                        OVH_APPLICATION_KEY/SECRET + OVH_CONSUMER_KEY (or OVH_CLIENT_ID/SECRET) and OVH_SERVICE_NAME.
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
. "$(dirname "$0")/api.sh"

cmd=${1:-}
# The location: OVH_LOCATION, else the target's .ovh/<target>.env (a Mac), else us-east-va.
if [ -n "${2:-}" ]; then
  ovh_target "$2"
  [ -n "${OVH_LOCATION:-}" ] || OVH_LOCATION=$(env_get OVH_LOCATION)
  [ -n "${OVH_SERVICE_NAME:-}" ] || OVH_SERVICE_NAME=$(env_get OVH_SERVICE_NAME)
fi
ovh_location "${OVH_LOCATION:-}"
TF_STATE_ENDPOINT=${TF_STATE_ENDPOINT:-$(loc_get s3_endpoint)}
TF_STATE_REGION=${TF_STATE_REGION:-$(loc_get s3_region)}
if [ -n "${TF_STATE_ACCESS_KEY:-}" ]; then export AWS_ACCESS_KEY_ID="$TF_STATE_ACCESS_KEY"; fi
if [ -n "${TF_STATE_SECRET_KEY:-}" ]; then export AWS_SECRET_ACCESS_KEY="$TF_STATE_SECRET_KEY"; fi
# Third-party S3: send checksums only when an operation requires them (newer AWS CLIs add them by default).
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required

have_keys() { [ -n "${AWS_ACCESS_KEY_ID:-}" ] && [ -n "${AWS_SECRET_ACCESS_KEY:-}" ]; }

# from_api — the state user's keys through the OVHcloud API (found or created; scripts/ovh/api.sh) into AWS_*.
from_api() {
  ovh_api_has_creds || return 1
  [ -n "${OVH_SERVICE_NAME:-}" ] || die "OVH_SERVICE_NAME (the Public Cloud project id) is needed to find the state user"
  echo "state user, from the OVHcloud API ($(loc_get site) account, project $OVH_SERVICE_NAME):" >&2
  ovh_api_init
  state_keys
  if [ -n "${GITHUB_ENV:-}" ]; then echo "::add-mask::$STATE_SECRET_KEY"; fi
  export AWS_ACCESS_KEY_ID="$STATE_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$STATE_SECRET_KEY"
}

need_keys() {
  have_keys || from_api \
    || die "no keys for the state bucket: TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY, or the OVHcloud API credentials that make them (docs/runbooks/ovh.md § Today, step 4)"
}

s3() { aws --endpoint-url "$TF_STATE_ENDPOINT" --region "$TF_STATE_REGION" "$@"; }

keys() {
  if have_keys; then
    # Keys from the environment that no longer work (the state user was deleted by a destroy, or replaced) are
    # replaced by the API's when the API credentials are there.
    if ovh_api_has_creds && command -v aws >/dev/null 2>&1 && ! s3 s3api list-buckets >/dev/null 2>&1; then
      echo "warning: TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY are refused by $TF_STATE_ENDPOINT — using the state user's keys from the API instead (re-run make ovh-gh-setup to store them)" >&2
      unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
      from_api
    else
      echo "state keys: from TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY" >&2
    fi
  else
    need_keys
  fi
  if [ -n "${GITHUB_ENV:-}" ]; then
    printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SECRET_ACCESS_KEY=%s\n' "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY" >>"$GITHUB_ENV"
    echo "state keys: in AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY for the next steps of this job (masked)" >&2
  elif [ -t 1 ]; then
    die "keys prints the secret key: use it as  eval \"\$(scripts/ovh/tf-state.sh keys ${OVH_TARGET:-ovh-vps})\""
  else
    printf 'export AWS_ACCESS_KEY_ID=%q AWS_SECRET_ACCESS_KEY=%q\n' "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY"
  fi
}

ensure() {
  [ -n "${TF_STATE_BUCKET:-}" ] || { echo "TF_STATE_BUCKET empty: local Terraform state"; return 0; }
  need aws
  need_keys
  if s3 s3api head-bucket --bucket "$TF_STATE_BUCKET" >/dev/null 2>&1; then
    echo "state bucket $TF_STATE_BUCKET exists"
  else
    s3 s3api create-bucket --bucket "$TF_STATE_BUCKET" \
      --create-bucket-configuration "LocationConstraint=$TF_STATE_REGION" >/dev/null \
      || die "could not create $TF_STATE_BUCKET (taken by someone else? pick another TF_STATE_BUCKET)"
    echo "state bucket $TF_STATE_BUCKET created in $OVH_LOCATION ($TF_STATE_ENDPOINT)"
  fi
  # Versioning: an earlier state can be restored after a bad apply (Object Storage › the bucket › versions).
  s3 s3api put-bucket-versioning --bucket "$TF_STATE_BUCKET" --versioning-configuration Status=Enabled >/dev/null \
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

# delete_versions <prefix> — every version and delete marker under the prefix (the bucket is versioned).
delete_versions() {
  local list batch
  while :; do
    list=$(s3 s3api list-object-versions --bucket "$TF_STATE_BUCKET" --prefix "$1" --max-items 1000 --output json) || return 1
    [ -n "$list" ] || return 0
    batch=$(printf '%s' "$list" | jq -c '{Objects: [(.Versions // [])[], (.DeleteMarkers // [])[] | {Key, VersionId}], Quiet: true}')
    [ "$(printf '%s' "$batch" | jq '.Objects | length')" -gt 0 ] || return 0
    s3 s3api delete-objects --bucket "$TF_STATE_BUCKET" --delete "$batch" >/dev/null || return 1
  done
}

teardown() {
  ovh_target "${1:-}"
  [ -n "${TF_STATE_BUCKET:-}" ] || { echo "TF_STATE_BUCKET empty: local state, nothing to remove"; return 0; }
  need aws jq
  need_keys
  local key="ovh/${OVH_TARGET#ovh-}/" left
  s3 s3api head-bucket --bucket "$TF_STATE_BUCKET" >/dev/null 2>&1 || { echo "state bucket $TF_STATE_BUCKET is gone already"; left=0; }
  if [ -z "${left:-}" ]; then
    delete_versions "$key" || die "could not delete s3://$TF_STATE_BUCKET/$key (Object Storage › the bucket)"
    echo "state of $OVH_TARGET deleted (s3://$TF_STATE_BUCKET/${key}terraform.tfstate, every version)"
    left=$(s3 s3api list-object-versions --bucket "$TF_STATE_BUCKET" --max-items 1 --output json) || die "could not list s3://$TF_STATE_BUCKET"
    left=$(printf '%s' "${left:-null}" | jq '[(.Versions // [])[], (.DeleteMarkers // [])[]] | length')
    if [ "$left" -gt 0 ]; then
      echo "the bucket still holds another target's state (ovh/vps or ovh/mks): bucket and state user kept — the last target's destroy removes them"
      return 0
    fi
    s3 s3api delete-bucket --bucket "$TF_STATE_BUCKET" || die "could not delete the bucket $TF_STATE_BUCKET"
    echo "state bucket $TF_STATE_BUCKET deleted"
  fi
  # Last: the user whose keys opened the bucket.
  if ovh_api_has_creds && [ -n "${OVH_SERVICE_NAME:-}" ]; then
    ovh_api_init
    state_user_delete || die "could not delete the Public Cloud user $OVH_STATE_USER (Public Cloud › Users & Roles)"
    [ -z "${GITHUB_ENV:-}" ] || echo "the environment's TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY are now stale: the next run makes a new user and uses its keys (make ovh-gh-setup stores them)"
  else
    echo "no OVHcloud API credentials: delete the Public Cloud user $OVH_STATE_USER in the console (Users & Roles)"
  fi
}

case "$cmd" in
  keys) keys ;;       # [TARGET] only picks the location and project (above)
  ensure) ensure ;;   # [TARGET] only picks the location (above)
  init) init "${2:-}" ;;
  teardown) teardown "${2:-}" ;;
  *) die "usage: $0 keys [ovh-vps|ovh-mks] | ensure [ovh-vps|ovh-mks] | init ovh-vps|ovh-mks | teardown ovh-vps|ovh-mks" ;;
esac
