#!/usr/bin/env bash
# Offline checks for infra/terraform (no cloud credentials, no remote state):
#   terraform fmt -check, the module contract check, init -backend=false + validate for every module and root, and
#   `terraform test` (plan against mocked providers) for every env root.
# Usage: scripts/validate.sh [all|aws|gcp|azure]     (CI: .github/workflows/infra.yml, ci/gitlab/infra.yml)
# Set TF_PLUGIN_CACHE_DIR to share provider downloads between the roots.
set -euo pipefail
cloud=${1:-all}
cd "$(dirname "$0")/.."

echo "== terraform fmt -check"
terraform fmt -check -recursive -diff .

echo "== module contract"
scripts/check-contract.sh

case $cloud in
  all) pattern='aws|gcp|azure' ;;
  aws | gcp | azure) pattern=$cloud ;;
  *) echo "usage: $0 [all|aws|gcp|azure]" >&2; exit 2 ;;
esac

# Every module, stack, bootstrap and env root.
mapfile -t dirs < <(
  find modules stacks bootstrap envs -name '*.tf' -printf '%h\n' | sort -u | grep -E "(^|/)($pattern)(/|$)"
)

failed=()
for dir in "${dirs[@]}"; do
  printf '== validate %-40s ' "$dir"
  if out=$(terraform -chdir="$dir" init -backend=false -input=false -no-color 2>&1) &&
     out=$(terraform -chdir="$dir" validate -no-color 2>&1) &&
     # Env roots carry tests/plan.tftest.hcl: a full plan against mocked providers (no credentials).
     { [[ ! -d "$dir/tests" ]] || out=$(terraform -chdir="$dir" test -no-color 2>&1); }; then
    echo ok
  else
    echo FAILED
    echo "$out"
    failed+=("$dir")
  fi
done

if ((${#failed[@]})); then
  echo "validate failed in: ${failed[*]}" >&2
  exit 1
fi
echo "all ${#dirs[@]} configurations valid"
