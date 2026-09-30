#!/usr/bin/env bash
# S-31 kind rehearsal of merchants' custom domains (docs/runbooks/custom-domains.md § 9): the api's reconciler against a
# real API server, cert-manager and Envoy Gateway, as the chart's northline-api ServiceAccount.
#
# Needs a kind cluster (current kubectl context) with cert-manager 1.18 (--enable-certificate-owner-ref) and Envoy
# Gateway 1.5 running — `deploy/argocd/kind/rehearse.sh up edge` sets both up — plus helm, openssl, curl and JDK 25.
#
#   deploy/kind/custom-domains.sh         the chart's edge + RBAC, then: domains added (5 on shards of 2 listeners),
#                                         certificates issued by the local CA, each domain served over TLS with HSTS,
#                                         what the api may and may not do, then everything removed again
#   NS=northline-local HOSTS=5 LISTENERS=2 KEEP=1   namespace, number of domains, listeners per shard Gateway (≤ 64),
#                                         keep the domains at the end
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NS=${NS:-northline-local}
HOSTS=${HOSTS:-5}
LISTENERS=${LISTENERS:-2}
STATE=${STATE:-$HOME/.cache/northline-custom-domains}
mkdir -p "$STATE"
step() { echo; echo "== $*"; }

step "GatewayClass envoy + EnvoyProxy (mergeGateways) from the add-on manifests"
kubectl apply -f "$ROOT/deploy/argocd/addons/envoy-gateway/manifests/common" -f "$ROOT/deploy/argocd/addons/envoy-gateway/manifests/kind"

step "namespace $NS, a local CA (the kind Issuers are type ca), a stand-in consumer app"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -f - <<YAML
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata: { name: rehearsal-selfsigned }
spec: { selfSigned: {} }
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: { name: northline-local-ca, namespace: $NS }
spec:
  isCA: true
  commonName: Northline kind rehearsal CA
  secretName: northline-local-ca
  privateKey: { algorithm: ECDSA, size: 256 }
  issuerRef: { name: rehearsal-selfsigned, kind: ClusterIssuer }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: northline-consumer, namespace: $NS }
spec:
  selector: { matchLabels: { app: rehearsal-consumer } }
  template:
    metadata: { labels: { app: rehearsal-consumer } }
    spec:
      containers:
        - name: echo
          image: ${ECHO_IMAGE:-hashicorp/http-echo:1.0}
          args: ["-listen=:3000", "-text=storefront by host"]
          ports: [{ containerPort: 3000 }]
---
apiVersion: v1
kind: Service
metadata: { name: northline-consumer, namespace: $NS }
spec:
  selector: { app: rehearsal-consumer }
  ports: [{ name: http, port: 3000, targetPort: 3000 }]
YAML
kubectl -n "$NS" wait certificate/northline-local-ca --for condition=Ready --timeout 2m
kubectl -n "$NS" get secret northline-local-ca -o jsonpath='{.data.ca\.crt}' | base64 -d >"$STATE/ca.crt"

step "the chart's edge (Gateway, Issuers, ClientTrafficPolicies, Role + RoleBinding) and the api's ServiceAccount"
helm template northline "$ROOT/deploy/helm/northline" --namespace "$NS" \
  -f "$ROOT/deploy/helm/northline/values-local-kind.yaml" -f "$ROOT/deploy/helm/northline/values-local-kind-edge.yaml" \
  --show-only templates/edge.yaml | kubectl -n "$NS" apply -f -
kubectl -n "$NS" create serviceaccount northline-api --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" wait issuer --all --for condition=Ready --timeout 2m

step "what the api's ServiceAccount may do"
as=system:serviceaccount:$NS:northline-api
for check in "create gateways.gateway.networking.k8s.io" "patch httproutes.gateway.networking.k8s.io" \
    "delete certificates.cert-manager.io" "list secrets" "get secrets" "create roles.rbac.authorization.k8s.io" \
    "create gateways.gateway.networking.k8s.io -n default"; do
  printf '  %-58s %s\n' "$check" "$(kubectl auth can-i --as "$as" -n "$NS" $check || true)"
done

# The pod's view: the API server, the cluster CA and a ServiceAccount token.
kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d >"$STATE/cluster-ca.crt"
kubectl -n "$NS" create token northline-api --duration 1h >"$STATE/token"
api=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
run() {
  (cd "$ROOT/server" && NL_KIND_API=$api NL_KIND_NAMESPACE=$NS NL_KIND_TOKEN=$STATE/token NL_KIND_CA=$STATE/cluster-ca.crt \
    NL_KIND_HOSTS=$HOSTS NL_KIND_LISTENERS=$LISTENERS NL_KIND_STEP=$1 \
    ./gradlew -q :api:test --tests '*CustomDomainsKindRehearsal' --rerun >"$STATE/gradle-$1.log" 2>&1) \
    || { tail -40 "$STATE/gradle-$1.log"; exit 1; }
  grep -ho 'REHEARSAL[^<]*' "$ROOT"/server/api/build/test-results/test/TEST-*CustomDomainsKindRehearsal.xml | cut -c1-400
}

step "the reconciler adds $HOSTS domains (shards of $LISTENERS listeners) and waits for their certificates"
run add
kubectl -n "$NS" get gateways,certificates,httproutes -l northline.ca/custom-domain=true

step "served over TLS through the one merged Envoy Service"
svc=$(kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gatewayclass=envoy -o name | head -1)
echo "  $svc — Envoy Services for the class: $(kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gatewayclass=envoy -o name | wc -l)"
kubectl -n envoy-gateway-system rollout status deploy -l gateway.envoyproxy.io/owning-gatewayclass=envoy --timeout 3m >/dev/null
kubectl -n envoy-gateway-system port-forward "$svc" 38443:443 38080:80 >/dev/null 2>&1 &
pf=$!
trap 'kill $pf 2>/dev/null || true' EXIT
sleep 3
for i in $(seq 0 $((HOSTS < 5 ? HOSTS - 1 : 4))) $((HOSTS - 1)); do
  host=shop$i.merchant.kind.test
  out=$(curl --noproxy '*' -sS --cacert "$STATE/ca.crt" --resolve "$host:38443:127.0.0.1" "https://$host:38443/" -D - -o "$STATE/body" -w '%{http_code}' || true)
  printf '  %-28s %s | %s | %s\n' "$host" "$(tail -1 <<<"$out")" "$(grep -i '^strict-transport-security' <<<"$out" | tr -d '\r')" "$(cat "$STATE/body" 2>/dev/null)"
done
printf '  %-28s %s\n' "http:// → " "$(curl --noproxy '*' -sS --resolve shop0.merchant.kind.test:38080:127.0.0.1 http://shop0.merchant.kind.test:38080/x -o /dev/null -w '%{http_code} %{redirect_url}' || true)"
printf '  %-28s %s\n' "unknown SNI" "$(curl --noproxy '*' -sS --cacert "$STATE/ca.crt" --resolve other.kind.test:38443:127.0.0.1 https://other.kind.test:38443/ -o /dev/null -w '%{http_code}' 2>&1 | tail -1 || true)"

if [[ -z ${KEEP:-} ]]; then
  step "the reconciler removes them all; cert-manager deletes the TLS Secrets with their Certificates"
  run remove
  sleep 5
  echo "  left: $(kubectl -n "$NS" get gateways,certificates,httproutes -l northline.ca/custom-domain=true -o name | wc -l) objects," \
    "$(kubectl -n "$NS" get secrets -o name | grep -c 'nl-cd-' || true) nl-cd-* Secrets"
fi
