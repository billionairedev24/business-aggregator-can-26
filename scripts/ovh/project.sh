#!/usr/bin/env bash
# The Public Cloud project (S-161), OPTIONAL: the default path is a project id given as OVH_SERVICE_NAME. With none,
#
#   scripts/ovh/project.sh ensure          → the project id on stdout: the account's project described
#                                            OVH_PROJECT_DESCRIPTION [northline-dryrun] if there is one (adopted), else
#                                            ordered once with infra/terraform/envs/ovh/project (local state in
#                                            .ovh/project-<location>.tfstate, git-ignored)
#
# Environment: OVH_LOCATION [us-east-va], the API credentials (scripts/ovh/api.sh). Ordering needs the token's order
# rights and the account's default payment method (a project costs nothing by itself). Progress goes to stderr.
set -euo pipefail
. "$(dirname "$0")/common.sh"
. "$(dirname "$0")/api.sh"

[ "${1:-}" = ensure ] || die "usage: $0 ensure"
ovh_location "${OVH_LOCATION:-}"
desc=${OVH_PROJECT_DESCRIPTION:-northline-dryrun}
ovh_api_init
id=$(project_find "$desc") || die "could not list the Public Cloud projects (the token's GET /cloud/* right?)"
if [ -n "$id" ]; then
  echo "   Public Cloud project $desc exists ($id): reused" >&2
  printf '%s\n' "$id"
  exit 0
fi
need terraform
tf="$OVH_REPO/infra/terraform/envs/ovh/project"
mkdir -p "$OVH_REPO/.ovh"
echo "   ordering the Public Cloud project $desc on the $(loc_get site) account (free; the default payment method)…" >&2
terraform -chdir="$tf" init -input=false -reconfigure -backend-config="path=$OVH_REPO/.ovh/project-$OVH_LOCATION.tfstate" >&2
terraform -chdir="$tf" apply -input=false -auto-approve -var "ovh_location=$OVH_LOCATION" -var "description=$desc" >&2 \
  || die "the order failed: the token needs the order rights (docs/runbooks/ovh.md § Today, step 3) and the account a default payment method"
id=$(terraform -chdir="$tf" output -raw project_id)
echo "   Public Cloud project $desc ordered ($id)" >&2
printf '%s\n' "$id"
