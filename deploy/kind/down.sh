#!/usr/bin/env bash
# Removes the kind rehearsal created by deploy/kind/up.sh (cluster and the Postgres container).
set -euo pipefail
CLUSTER=${KIND_CLUSTER:-northline}
kind delete cluster --name "$CLUSTER"
docker rm -f "${CLUSTER}-postgres" >/dev/null 2>&1 || true
