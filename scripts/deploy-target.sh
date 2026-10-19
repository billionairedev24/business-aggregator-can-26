#!/usr/bin/env bash
# One entry point for the dry-run targets (make deploy TARGET=…): install or upgrade the data services chart and the
# Northline chart into the cluster of kubectl's current context, with that target's values.
#
#   gcp-dryrun   Google Cloud GKE (docs/runbooks/dryrun-gcp.md): steps 9–10 — northline-data and northline with
#                values-gcp.yaml + values-dryrun.yaml + Terraform's outputs of infra/terraform/envs/gcp/dryrun (the
#                add-ons and secrets of steps 6–7 stay as the runbook says)
#   ovh-vps      OVHcloud VPS with k3s (docs/runbooks/ovh.md): scripts/ovh/deploy.sh ovh-vps
#   ovh-mks      OVHcloud Managed Kubernetes (docs/runbooks/ovh.md): scripts/ovh/deploy.sh ovh-mks
#
# Needs REGISTRY and IMAGE_TAG (the images, built and pushed first — each runbook's "images" step).
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
target=${1:-}
: "${REGISTRY:?REGISTRY: the image registry path}"
: "${IMAGE_TAG:?IMAGE_TAG: the tag of the images}"

case "$target" in
  gcp-dryrun)
    tf="$repo/infra/terraform/envs/gcp/dryrun"
    work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
    terraform -chdir="$tf" output -json helm_values >"$work/values.json"
    terraform -chdir="$tf" output -json data_values >"$work/data.json"
    kubectl get namespace northline-dryrun >/dev/null 2>&1 || kubectl create namespace northline-dryrun
    helm upgrade --install northline-data "$repo/deploy/helm/northline-data" -n northline-dryrun -f "$work/data.json" \
      --wait --timeout 15m
    helm upgrade --install northline "$repo/deploy/helm/northline" -n northline-dryrun \
      -f "$repo/deploy/helm/northline/values-gcp.yaml" -f "$repo/deploy/helm/northline/values-dryrun.yaml" -f "$work/values.json" \
      --set global.image.registry="$REGISTRY" --set global.image.tag="$IMAGE_TAG" --wait --timeout 20m ;;
  ovh-vps | ovh-mks)
    exec "$repo/scripts/ovh/deploy.sh" "$target" ;;
  *) echo "TARGET must be gcp-dryrun, ovh-vps or ovh-mks (got '$target')" >&2; exit 2 ;;
esac
