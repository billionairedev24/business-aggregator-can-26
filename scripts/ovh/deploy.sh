#!/usr/bin/env bash
# OVHcloud targets (docs/runbooks/ovh.md): install or upgrade everything in the cluster of kubectl's current context —
#   1. the Secrets from .ovh/<target>.env (scripts/ovh/secrets.sh apply)
#   2. the add-ons, with the values Argo CD uses elsewhere (deploy/argocd/addons): cert-manager (Let's Encrypt over
#      HTTP-01 through the Gateway), Envoy Gateway + GatewayClass + this target's EnvoyProxy, CloudNativePG (in-cluster
#      Postgres only). No External Secrets (no secrets manager) and no external-dns (records by hand).
#   3. Managed PostgreSQL (ovh-mks): the extensions postgis, citext, pgcrypto and the app user's CREATE grant, once,
#      as avnadmin from a short-lived pod
#   4. deploy/helm/northline-data with Terraform's data_values
#   5. deploy/helm/northline with values-dryrun.yaml + values-<target>.yaml + Terraform's helm_values
#
#   scripts/ovh/deploy.sh ovh-vps|ovh-mks          (make deploy TARGET=ovh-vps|ovh-mks)
#
# Environment: REGISTRY, IMAGE_TAG (required: where the images are and their tag — docs/runbooks/ovh.md § Images),
# ADDONS=0 skips step 2, RESTART=1 restarts every app afterwards (after a secret changed: Helm can't see Secrets).
# Twilio and SendGrid are switched on here when their keys are in the secrets file; without STRIPE_SECRET_KEY the api
# runs on its fake payment gateway with identity and age checks on the local fakes (a first demo without Stripe).
# OVH_DOMAIN [demo.northline.ca]: the zone of the public hosts (values-dryrun.yaml's urls) — another domain the operator
# controls moves every host under it (OVH_DOMAIN itself is the consumer site, auth./api./studio./… below it).
# OVH_SECRETS_SOURCE=env: every secret from environment variables (the pipeline, scripts/ovh/secrets.sh).
set -euo pipefail
. "$(dirname "$0")/common.sh"
ovh_target "${1:-}"
need kubectl helm terraform jq
: "${REGISTRY:?REGISTRY: the image registry path (e.g. ghcr.io/<org>/northline) — docs/runbooks/ovh.md § Images}"
: "${IMAGE_TAG:?IMAGE_TAG: the tag of the images (git rev-parse --short=12 HEAD when you built them)}"
NS=$OVH_NAMESPACE
A="$OVH_REPO/deploy/argocd/addons"
H="$OVH_REPO/deploy/helm"

kubectl get nodes >/dev/null || die "kubectl can't reach the cluster (ovh-vps: make ovh-tunnel and KUBECONFIG=~/.kube/northline-ovh-vps; ovh-mks: terraform output kubernetes)"
ctx=$(kubectl config current-context)
echo "== $OVH_TARGET → context $ctx, namespace $NS, images $REGISTRY:$IMAGE_TAG"

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
tf_out helm_values >"$work/values.json"
tf_out data_values >"$work/data.json"
in_pg=$(tf_out in_cluster .postgres)

echo "== 1. secrets"
"$(dirname "$0")/secrets.sh" apply "$OVH_TARGET"

if [ "${ADDONS:-1}" != 0 ]; then
  echo "== 2. add-ons"
  one=(); [ "$OVH_TARGET" = ovh-vps ] && one=(--set webhook.replicaCount=1 --set podDisruptionBudget.enabled=false)
  helm repo add jetstack https://charts.jetstack.io --force-update >/dev/null
  helm upgrade --install cert-manager jetstack/cert-manager -n cert-manager --create-namespace --version v1.18.2 \
    -f "$A/cert-manager/values.yaml" ${one[@]+"${one[@]}"} --wait
  replicas=2; [ "$OVH_TARGET" = ovh-vps ] && replicas=1
  helm upgrade --install envoy-gateway oci://registry-1.docker.io/envoyproxy/gateway-helm -n envoy-gateway-system \
    --create-namespace --version v1.5.1 -f "$A/envoy-gateway/values.yaml" --set deployment.replicas=$replicas --wait
  kubectl apply -f "$A/envoy-gateway/manifests/common" -f "$A/envoy-gateway/manifests/$OVH_TARGET"
  if [ "$in_pg" = true ]; then
    helm repo add cnpg https://cloudnative-pg.github.io/charts --force-update >/dev/null
    helm upgrade --install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace --version 0.26.0 --wait
  fi
fi

if [ "$OVH_TARGET" = ovh-mks ] && [ "$(tf_out postgres .mode)" = managed ]; then
  echo "== 3. Managed PostgreSQL: extensions and grant (as avnadmin)"
  user=$(tf_out config_env .DB_USER)
  kubectl -n "$NS" create secret generic northline-pg-admin --from-literal=PGURI="$(tf_out postgres_credentials .admin_uri)" \
    --dry-run=client -o yaml | kubectl -n "$NS" apply -f - >/dev/null
  overrides=$(jq -n --arg user "$user" '{spec: {containers: [{name: "psql", image: "postgres:17-alpine",
    command: ["sh", "-c", ("psql \"$PGURI\" -v ON_ERROR_STOP=1 -c \"CREATE EXTENSION IF NOT EXISTS postgis; CREATE EXTENSION IF NOT EXISTS citext; CREATE EXTENSION IF NOT EXISTS pgcrypto; GRANT CONNECT, CREATE, TEMPORARY ON DATABASE northline TO " + $user + "; GRANT USAGE, CREATE ON SCHEMA public TO " + $user + ";\"")],
    envFrom: [{secretRef: {name: "northline-pg-admin"}}],
    securityContext: {runAsNonRoot: true, runAsUser: 70, allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}, seccompProfile: {type: "RuntimeDefault"}}}]}}')
  kubectl -n "$NS" run northline-pg-init --rm -i --restart=Never --image=postgres:17-alpine --overrides="$overrides" \
    --pod-running-timeout=3m || { kubectl -n "$NS" delete secret northline-pg-admin >/dev/null; die "the extensions could not be created"; }
  kubectl -n "$NS" delete secret northline-pg-admin >/dev/null
fi

echo "== 4. northline-data"
# .ovh/<target>-data.yaml (optional, git-ignored): the operator's lasting overrides, e.g. after a restore
# (docs/runbooks/ovh.md § Backups and restore).
data_extra=(); [ -f "$OVH_REPO/.ovh/$OVH_TARGET-data.yaml" ] && data_extra=(-f "$OVH_REPO/.ovh/$OVH_TARGET-data.yaml")
helm upgrade --install northline-data "$H/northline-data" -n "$NS" -f "$work/data.json" ${data_extra[@]+"${data_extra[@]}"} \
  --wait --timeout 15m

echo "== 5. northline"
extra=()
if [ -n "$(env_get SMS_AUTH_TOKEN)" ] && [ -n "$(env_get SMS_ACCOUNT_ID)" ] && [ -n "$(env_get SMS_FROM)" ]; then
  extra+=(--set apps.auth.env.SMS_PROVIDER=twilio --set-string "apps.auth.env.SMS_FROM=$(env_get SMS_FROM)"
    --set-string "apps.auth.env.SMS_ACCOUNT_ID=$(env_get SMS_ACCOUNT_ID)" --set apps.auth.secretEnv.SMS_AUTH_TOKEN=true)
  echo "   SMS codes through Twilio"
fi
if [ -n "$(env_get EMAIL_API_KEY)" ]; then
  extra+=(--set apps.api.env.EMAIL_PROVIDER=sendgrid --set apps.worker.env.EMAIL_PROVIDER=sendgrid
    --set apps.api.secretEnv.EMAIL_API_KEY=true --set apps.worker.secretEnv.EMAIL_API_KEY=true)
  echo "   emails through SendGrid"
fi
if [ -n "$(env_get KAFKA_SASL_JAAS_CONFIG)" ]; then
  extra+=(--set apps.api.secretEnv.KAFKA_SASL_JAAS_CONFIG=true --set apps.auth.secretEnv.KAFKA_SASL_JAAS_CONFIG=true
    --set apps.worker.secretEnv.KAFKA_SASL_JAAS_CONFIG=true)
fi
if [ -z "$(env_get STRIPE_SECRET_KEY)" ]; then
  extra+=(--set apps.api.env.IDENTITY_PROVIDER=local --set apps.api.env.AGE_VERIFICATION_PROVIDER=local)
  echo "   no Stripe key: fake payment gateway, identity and age checks on the local fakes"
fi
for k in STRIPE_SECRET_KEY STRIPE_PUBLISHABLE_KEY STRIPE_WEBHOOK_SECRET STRIPE_CONNECT_WEBHOOK_SECRET; do
  [ -n "$(env_get "$k")" ] || extra+=(--set "apps.api.secretEnv.$k=false")   # optional key: the pod starts without it
done
domain=${OVH_DOMAIN:-demo.northline.ca}
if [ "$domain" != demo.northline.ca ]; then
  jq -n --arg d "$domain" '{urls: {auth: "https://auth.\($d)", api: "https://api.\($d)", studio: "https://studio.\($d)",
      consumer: "https://\($d)", console: "https://console.\($d)", pages: "https://pages.\($d)", webauthnRpId: $d},
    apps: ({api: {env: {EMAIL_FROM: "Northline demo <no-reply@\($d)>"}}, auth: {env: {EMAIL_FROM: "Northline demo <no-reply@\($d)>"}},
      worker: {env: {EMAIL_FROM: "Northline demo <no-reply@\($d)>"}}})}' >"$work/domain.json"
  extra+=(-f "$work/domain.json")
  echo "   hosts under $domain"
fi
helm upgrade --install northline "$H/northline" -n "$NS" \
  -f "$H/northline/values-dryrun.yaml" -f "$H/northline/values-$OVH_TARGET.yaml" -f "$work/values.json" \
  --set global.image.registry="$REGISTRY" --set global.image.tag="$IMAGE_TAG" ${extra[@]+"${extra[@]}"} --wait --timeout 20m

if [ "${RESTART:-0}" = 1 ]; then kubectl -n "$NS" rollout restart deployment -l app.kubernetes.io/part-of=northline; fi
echo
kubectl -n "$NS" get pods
kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=northline -o wide 2>/dev/null || true
echo "Next: DNS records → the address above (docs/runbooks/ovh.md § DNS), then the smoke test."
