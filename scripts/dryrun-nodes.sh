#!/usr/bin/env bash
# GCP dry run (docs/runbooks/dryrun-gcp.md § Pay only while demoing): scale the cluster's node pool to 0 between demos
# and back afterwards, so the credit is spent only while the nodes run. Reads the cluster, zone and project from the
# Terraform outputs of infra/terraform/envs/gcp/dryrun (run `terraform apply` there first).
#   scripts/dryrun-nodes.sh down | up [N] | status        (make dryrun-down, dryrun-up, dryrun-status)
# down turns the pool's autoscaling off and resizes it to 0; up resizes it to N (default 2) and turns autoscaling back
# on with Terraform's bounds, so a later `terraform apply` finds what it expects. Volumes, the load balancer and the
# data in Postgres/Kafka/Meilisearch stay; the pods come back with the nodes.
set -euo pipefail
cd "$(dirname "$0")/.."
TF=infra/terraform/envs/gcp/dryrun
POOL=${DRYRUN_POOL:-general}

cmd=$(terraform -chdir="$TF" output -json kubernetes | jq -r .kubeconfig_command)
read -r cluster location project <<<"$(sed -E 's/.*get-credentials ([^ ]+) --location ([^ ]+) --project ([^ ]+).*/\1 \2 \3/' <<<"$cmd")"
[[ -n $cluster && -n $location && -n $project ]] || { echo "no cluster in terraform output kubernetes ($TF)" >&2; exit 1; }
gc=(--cluster "$cluster" --location "$location" --project "$project")

case ${1:-status} in
  down)
    gcloud container node-pools update "$POOL" "${gc[@]}" --no-enable-autoscaling --quiet
    gcloud container clusters resize "$cluster" --node-pool "$POOL" --num-nodes 0 --location "$location" --project "$project" --quiet
    echo "node pool $POOL at 0 — the load balancer, disks and DNS keep billing (≈ \$0.04/h); 'make dryrun-up' to resume" ;;
  up)
    n=${2:-2}
    gcloud container clusters resize "$cluster" --node-pool "$POOL" --num-nodes "$n" --location "$location" --project "$project" --quiet
    gcloud container node-pools update "$POOL" "${gc[@]}" --enable-autoscaling --min-nodes "$n" --max-nodes "$n" --quiet
    echo "node pool $POOL at $n; pods are scheduling: kubectl -n northline-dryrun get pods -w" ;;
  status)
    gcloud container node-pools describe "$POOL" "${gc[@]}" --format 'value(config.machineType,config.spot,autoscaling.enabled,autoscaling.minNodeCount,autoscaling.maxNodeCount)'
    gcloud compute instances list --project "$project" --filter "name~^gke-$cluster-$POOL" --format 'table(name,status,zone.basename())' ;;
  *) echo "usage: $0 down | up [N] | status" >&2; exit 2 ;;
esac
