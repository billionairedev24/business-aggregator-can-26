#!/usr/bin/env bash
# Local Helm rehearsal on kind (docs/runbooks/deploy.md § Local: kind).
#
#   deploy/kind/up.sh            # cluster + Postgres + Valkey + the chart with values-local-kind.yaml
#   deploy/kind/down.sh          # removes all of it
#
# Needs docker, kind, kubectl, helm, and the images built locally as northline/<app>:$IMAGE_TAG
# (cd server && ./gradlew jibDockerBuild; docker build --target studio|consumer|console web). Postgres runs next to the
# cluster (a container on the kind network, reached through a selector-less Service — the shape of a managed
# database); Valkey runs in the cluster. Variables: KIND_CLUSTER (northline), NAMESPACE (northline-local),
# IMAGE_TAG (dev), APPS (images to load), PG_IMAGE, VALKEY_IMAGE, HELM_EXTRA_ARGS, SKIP_HELM (stand-ins only).
set -euo pipefail

CLUSTER=${KIND_CLUSTER:-northline}
NS=${NAMESPACE:-northline-local}
TAG=${IMAGE_TAG:-dev}
APPS=${APPS-"api auth bff worker studio consumer console"}   # APPS="" skips loading (already in the node)
PG_IMAGE=${PG_IMAGE:-postgis/postgis:17-3.5}
VALKEY_IMAGE=${VALKEY_IMAGE:-valkey/valkey:8-alpine}
PG_CONTAINER="${CLUSTER}-postgres"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CHART="$ROOT/deploy/helm/northline"

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --config "$ROOT/deploy/kind/cluster.yaml" --wait 180s
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

# Postgres 17 + PostGIS beside the cluster.
if ! docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then
  docker run -d --name "$PG_CONTAINER" --network kind \
    -e POSTGRES_DB=northline -e POSTGRES_USER=northline -e POSTGRES_PASSWORD=northline "$PG_IMAGE" >/dev/null
fi
for _ in $(seq 1 60); do docker exec "$PG_CONTAINER" pg_isready -U northline -q && break; sleep 1; done
PG_IP=$(docker inspect -f '{{(index .NetworkSettings.Networks "kind").IPAddress}}' "$PG_CONTAINER")

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Service
metadata: { name: postgres }
spec:
  ports: [{ name: postgres, port: 5432, targetPort: 5432 }]
---
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: postgres-1
  labels: { kubernetes.io/service-name: postgres }
addressType: IPv4
ports: [{ name: postgres, port: 5432 }]
endpoints: [{ addresses: ["$PG_IP"] }]
---
# Kafka and Elasticsearch are not started (memory, disk). These names resolve but nothing answers, so clients retry in
# the background instead of failing at start-up on an unknown host.
apiVersion: v1
kind: Service
metadata: { name: kafka }
spec:
  ports: [{ name: kafka, port: 9092 }]
---
apiVersion: v1
kind: Service
metadata: { name: elasticsearch }
spec:
  ports: [{ name: http, port: 9200 }]
YAML

# Images into the node(s). Streams one platform only: with Docker's containerd image store, `kind load docker-image`
# fails on multi-platform images pulled for a single platform ("content digest … not found").
PLATFORM="linux/$(docker version -f '{{.Server.Arch}}')"
load_image() {
  local node
  for node in $(kind get nodes --name "$CLUSTER"); do
    docker save --platform "$PLATFORM" "$1" \
      | docker exec -i "$node" ctr --namespace=k8s.io images import --digests --snapshotter=overlayfs - >/dev/null
  done
  echo "loaded $1"
}
docker image inspect "$VALKEY_IMAGE" >/dev/null 2>&1 || docker pull -q "$VALKEY_IMAGE"
load_image "$VALKEY_IMAGE"
kubectl -n "$NS" apply -f "$ROOT/deploy/kind/valkey.yaml" >/dev/null
for app in $APPS; do load_image "northline/$app:$TAG"; done

# SKIP_HELM=1: stand-ins and images only — Argo CD installs the chart (deploy/argocd/kind/rehearse.sh, S-15).
if [[ -n ${SKIP_HELM:-} ]]; then echo "stand-ins ready in $NS (SKIP_HELM)"; exit 0; fi

# shellcheck disable=SC2086 # HELM_EXTRA_ARGS holds several arguments
helm upgrade --install northline "$CHART" -n "$NS" \
  -f "$CHART/values-local-kind.yaml" --set global.image.tag="$TAG" \
  --wait --timeout 10m ${HELM_EXTRA_ARGS:-}

kubectl -n "$NS" get pods
cat <<MSG

Ready. Port-forward and check:
  kubectl -n $NS port-forward svc/northline-api 18081:8080 &   curl localhost:18081/actuator/health/readiness
  kubectl -n $NS port-forward svc/northline-auth 19000:9000 &  curl localhost:19000/.well-known/openid-configuration
  kubectl -n $NS port-forward svc/northline-bff 18082:8082 &   curl localhost:18082/actuator/health/readiness
  kubectl -n $NS port-forward svc/northline-studio 18080:8080 & open http://localhost:18080
  kubectl -n $NS port-forward svc/northline-console 13200:8080 & open http://localhost:13200   # S-90
MSG
