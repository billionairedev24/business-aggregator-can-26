#!/usr/bin/env bash
# OVHcloud targets (docs/runbooks/ovh.md § Between demos): stop and start the apps between demos.
#   scripts/ovh/scale.sh down|up|status ovh-vps|ovh-mks      (make ovh-down / ovh-up / ovh-status TARGET=…)
#
# ovh-vps: the VPS is billed by the month whatever runs on it, so "down" saves no money — it only takes the apps off
#   the internet (Deployments of the northline chart at 0; the data services keep running and keep their data).
# ovh-mks: "down" also scales the node pool to 0 through Terraform (node_count=0; hourly nodes stop billing, the free
#   control plane, the volumes, the load balancer, the gateway and Managed PostgreSQL keep billing). "up" applies
#   terraform.tfvars's node_count again, then brings the apps back. Both ask before applying (AUTO_APPROVE=1 doesn't).
# Each Deployment's replica count is kept in an annotation on the way down and restored on the way up.
set -euo pipefail
. "$(dirname "$0")/common.sh"
cmd=${1:-status}; ovh_target "${2:-}"
need kubectl
NS=$OVH_NAMESPACE
ANN=northline.ca/replicas-before-down
approve=(); [ "${AUTO_APPROVE:-0}" = 1 ] && approve=(-auto-approve)

apps_down() {
  local d r
  for d in $(kubectl -n "$NS" get deploy -l app.kubernetes.io/part-of=northline -o name); do
    r=$(kubectl -n "$NS" get "$d" -o jsonpath='{.spec.replicas}')
    [ "$r" = 0 ] && continue
    kubectl -n "$NS" annotate "$d" "$ANN=$r" --overwrite >/dev/null
    kubectl -n "$NS" scale "$d" --replicas=0 >/dev/null
    echo "$d: $r → 0"
  done
}

apps_up() {
  local d r
  for d in $(kubectl -n "$NS" get deploy -l app.kubernetes.io/part-of=northline -o name); do
    r=$(kubectl -n "$NS" get "$d" -o jsonpath="{.metadata.annotations.northline\.ca/replicas-before-down}")
    [ -n "$r" ] || continue
    kubectl -n "$NS" scale "$d" --replicas="$r" >/dev/null
    kubectl -n "$NS" annotate "$d" "$ANN-" >/dev/null
    echo "$d: 0 → $r"
  done
}

case "$cmd" in
  down)
    if [ "$OVH_TARGET" = ovh-mks ]; then
      apps_down
      need terraform
      terraform -chdir="$OVH_TF" apply -var node_count=0 ${approve[@]+"${approve[@]}"}
      echo "node pool at 0 — the volumes, load balancer, gateway and Managed PostgreSQL keep billing; 'make ovh-up TARGET=ovh-mks' to resume"
    else
      apps_down
      echo "apps at 0 (the VPS is billed monthly either way; the data services keep running) — 'make ovh-up TARGET=ovh-vps'"
    fi ;;
  up)
    if [ "$OVH_TARGET" = ovh-mks ]; then
      need terraform
      terraform -chdir="$OVH_TF" apply ${approve[@]+"${approve[@]}"}
      echo "waiting for the nodes…"; kubectl wait --for=condition=Ready nodes --all --timeout=15m
    fi
    apps_up
    echo "pods are starting: kubectl -n $NS get pods -w" ;;
  status)
    kubectl get nodes -o wide
    kubectl -n "$NS" get deploy,statefulset -o wide
    if [ "$OVH_TARGET" = ovh-mks ]; then need terraform; tf_out kubernetes '"node pool: \(.node_count) × \(.node_flavor), Kubernetes \(.version)"'; fi ;;
  *) die "usage: $0 down|up|status ovh-vps|ovh-mks" ;;
esac
