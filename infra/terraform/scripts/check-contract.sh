#!/usr/bin/env bash
# Checks the module contract: every capability under modules/ has aws, gcp and azure implementations that declare
# the same variables (identical blocks, except the region list in the context validation) and the same outputs.
set -euo pipefail
cd "$(dirname "$0")/../modules"

status=0
normalise_vars() {
  # Drop the cloud-specific region list and its message; everything else must match byte for byte.
  sed -E -e '/contains\(\[.*\], var\.context\.region\)/d' -e '/Canadian data residency/d' "$1/variables.tf"
}
output_names() {
  grep -hoE '^output "[^"]+"' "$1"/*.tf | sort
}

for cap in */; do
  cap=${cap%/}
  failed=0
  for cloud in aws gcp azure; do
    if [[ ! -f "$cap/$cloud/variables.tf" ]]; then
      echo "FAIL $cap: missing $cap/$cloud/variables.tf"; status=1; continue 2
    fi
  done
  if ! diff -u <(normalise_vars "$cap/aws") <(normalise_vars "$cap/gcp") >/dev/null ||
     ! diff -u <(normalise_vars "$cap/aws") <(normalise_vars "$cap/azure") >/dev/null; then
    echo "FAIL $cap: variables differ between implementations"
    diff -u <(normalise_vars "$cap/aws") <(normalise_vars "$cap/gcp") || true
    diff -u <(normalise_vars "$cap/aws") <(normalise_vars "$cap/azure") || true
    failed=1
  fi
  if ! diff <(output_names "$cap/aws") <(output_names "$cap/gcp") >/dev/null ||
     ! diff <(output_names "$cap/aws") <(output_names "$cap/azure") >/dev/null; then
    echo "FAIL $cap: outputs differ between implementations"
    diff <(output_names "$cap/aws") <(output_names "$cap/gcp") || true
    diff <(output_names "$cap/aws") <(output_names "$cap/azure") || true
    failed=1
  fi
  if [[ $failed -eq 1 ]]; then status=1; else echo "ok   $cap ($(output_names "$cap/aws" | wc -l) outputs)"; fi
done
exit $status
