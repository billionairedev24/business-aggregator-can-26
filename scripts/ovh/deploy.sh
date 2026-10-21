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
# Twilio and the email provider (demo inbox, Resend, SMTP relay or SendGrid — below) are switched on from the secrets file; without STRIPE_SECRET_KEY the api
# runs on its fake payment gateway with identity and age checks on the local fakes (a first demo without Stripe).
# OVH_DOMAIN [demo.northline.ca]: the zone of the public hosts (values-dryrun.yaml's urls) — another domain the operator
# controls moves every host under it (OVH_DOMAIN itself is the consumer site, auth./api./studio./… below it).
# OVH_SECRETS_SOURCE=env: every secret from environment variables (the pipeline, scripts/ovh/secrets.sh).
# OVH_EXTRA_VALUES: one more values file layered last (and in the data-residency pre-check: a US location refuses
# staging/prod values — S-160, docs/runbooks/ovh.md § Data residency).
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

# A failed or timed-out step (Helm's "context deadline exceeded" says only that something never became ready): print
# what isn't ready and why — pods, volumes, the Postgres cluster, recent events, the logs of pods that aren't ready —
# in the job's log, and the status part (no logs) in the run's summary.
diagnose() {
  echo
  echo "== the deploy failed — what isn't ready in $NS"
  local pods bad
  pods=$(kubectl -n "$NS" get pods -o wide 2>&1 || true)
  printf '%s\n' "$pods"
  kubectl -n "$NS" get pvc 2>&1 || true
  kubectl -n "$NS" get clusters.postgresql.cnpg.io 2>/dev/null || true
  echo "-- recent events"
  kubectl -n "$NS" get events --sort-by=.lastTimestamp 2>&1 | tail -40 || true
  bad=$(kubectl -n "$NS" get pods --no-headers \
    -o custom-columns="N:.metadata.name,R:.status.containerStatuses[*].ready,P:.status.phase" 2>/dev/null \
    | awk '$3 != "Succeeded" && ($2 ~ /false/ || $2 == "<none>" || $3 != "Running") { print $1 }' || true)
  for p in $bad; do
    echo "-- $p"
    kubectl -n "$NS" describe pod "$p" 2>&1 | sed -n '/^Conditions:/,$p' | tail -30 || true
    kubectl -n "$NS" logs "$p" --all-containers --tail=60 2>&1 || true
    kubectl -n "$NS" logs "$p" --all-containers --previous --tail=30 2>/dev/null || true
  done
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### The deploy failed: what isn't ready in \`$NS\`"
      echo '```'
      printf '%s\n' "$pods"
      echo
      kubectl -n "$NS" get pvc 2>&1 || true
      kubectl -n "$NS" get clusters.postgresql.cnpg.io 2>/dev/null || true
      echo
      kubectl -n "$NS" get events --sort-by=.lastTimestamp --field-selector type=Warning 2>&1 | tail -25 || true
      echo '```'
      echo "Not ready: ${bad:-none}. Their descriptions and logs are in this step's log."
    } >>"$GITHUB_STEP_SUMMARY"
  fi
}
work=$(mktemp -d); # shellcheck disable=SC2154 # rc is set inside the trap
trap 'rc=$?; if [ "$rc" != 0 ]; then diagnose; fi; rm -rf "$work"' EXIT
tf_out helm_values >"$work/values.json"
tf_out data_values >"$work/data.json"
in_pg=$(tf_out in_cluster .postgres)

# Where (S-160, Terraform's ovh_location) and Canadian data residency: outside Canada only the dev profile with fake
# demo data. The chart refuses staging/prod there (global.hostingCountry); render it first with the same values so a
# refusal stops the deploy before anything is installed.
loc=$(tf_out location .name 2>/dev/null || true)
country=$(jq -r '.global.hostingCountry // ""' "$work/values.json")
echo "   location ${loc:-not in the state yet (terraform apply)}, country ${country:-not declared}"
helm template northline "$H/northline" -n "$NS" -f "$H/northline/values-dryrun.yaml" -f "$H/northline/values-$OVH_TARGET.yaml" \
  -f "$work/values.json" ${OVH_EXTRA_VALUES:+-f "$OVH_EXTRA_VALUES"} --set global.image.registry="$REGISTRY" --set global.image.tag="$IMAGE_TAG" >/dev/null \
  || die "the northline chart refuses these values (above) — nothing was changed. Outside Canada (OVH_LOCATION=${loc:-?}) only the dev profile runs: docs/runbooks/ovh.md § Data residency"
if [ -n "$country" ] && [ "$country" != CA ]; then
  echo "   outside Canada ($country): fake demo data only — real customer data goes to ca-bhs (docs/runbooks/ovh.md § Data residency)"
fi

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
# No `helm --wait` here: it also waits for every PersistentVolumeClaim to be Bound, and the auth signing keys' volume
# binds only when auth (step 5) mounts it — on a WaitForFirstConsumer storage class (k3s's local-path, GKE's
# standard-rwo) the wait could never finish ("context deadline exceeded"). Wait for the data services themselves.
helm upgrade --install northline-data "$H/northline-data" -n "$NS" -f "$work/data.json" ${data_extra[@]+"${data_extra[@]}"}
for sts in $(kubectl -n "$NS" get statefulsets -l app.kubernetes.io/instance=northline-data -o name 2>/dev/null); do
  kubectl -n "$NS" rollout status "$sts" --timeout 15m
done
if kubectl -n "$NS" get clusters.postgresql.cnpg.io >/dev/null 2>&1; then
  for c in $(kubectl -n "$NS" get clusters.postgresql.cnpg.io -o name); do
    kubectl -n "$NS" wait "$c" --for=condition=Ready --timeout 15m
  done
fi

echo "== 5. northline"
extra=()
if [ -n "$(env_get SMS_AUTH_TOKEN)" ] && [ -n "$(env_get SMS_ACCOUNT_ID)" ] && [ -n "$(env_get SMS_FROM)" ]; then
  extra+=(--set apps.auth.env.SMS_PROVIDER=twilio --set-string "apps.auth.env.SMS_FROM=$(env_get SMS_FROM)"
    --set-string "apps.auth.env.SMS_ACCOUNT_ID=$(env_get SMS_ACCOUNT_ID)" --set apps.auth.secretEnv.SMS_AUTH_TOKEN=true)
  echo "   SMS codes through Twilio"
fi
# Email for api, auth (account codes, S-154) and worker — the first that applies (docs/runbooks/ovh.md § Email):
#   OVH_DEMO_MAILPIT=true  the demo inbox in the namespace (chart demoMail): every email lands there, nothing is
#                          delivered; the inbox is https://mail.<domain> behind basic auth (user demo, MAILPIT_PASSWORD)
#   RESEND_API_KEY         Resend's HTTPS API (EMAIL_PROVIDER=resend; secrets.sh stores the key as EMAIL_API_KEY) — the
#                          choice on OVHcloud, which filters outbound SMTP ports (587/2587 time out), HTTPS is not
#   SMTP_HOST              any SMTP relay on port 587 with STARTTLS (OVHcloud email, Brevo, Gmail, SendGrid SMTP…)
#   EMAIL_API_KEY          SendGrid's API
#   none                   EMAIL_PROVIDER=local: no SMTP server answers in the cluster, so each email's text — the
#                          6-digit sign-up codes included — is written to that app's log at WARN (kubectl logs)
domain=${OVH_DOMAIN:-demo.northline.ca}
mail_apps="api auth worker"
mailpit=${OVH_DEMO_MAILPIT:-$(env_get OVH_DEMO_MAILPIT)}
if [ "$mailpit" = true ]; then
  extra+=(--set demoMail.enabled=true --set-string "demoMail.url=https://mail.$domain")
  for a in $mail_apps; do
    extra+=(--set "apps.$a.env.EMAIL_PROVIDER=smtp" --set-string "apps.$a.env.SMTP_HOST=northline-mailpit"
      --set-string "apps.$a.env.SMTP_PORT=1025" --set-string "apps.$a.env.SMTP_STARTTLS=false")
  done
  [ -z "$(env_get SMTP_HOST)" ] || echo "   warning: OVH_DEMO_MAILPIT=true wins over SMTP_HOST — nothing is delivered"
  echo "   emails into the demo inbox https://mail.$domain (user demo)"
elif [ -n "$(env_get RESEND_API_KEY)" ]; then
  for a in $mail_apps; do
    extra+=(--set "apps.$a.env.EMAIL_PROVIDER=resend" --set "apps.$a.secretEnv.EMAIL_API_KEY=true")
  done
  [ -z "$(env_get SMTP_HOST)" ] || echo "   warning: RESEND_API_KEY wins over SMTP_HOST"
  [ -n "$(env_get EMAIL_FROM)" ] || echo "   warning: no EMAIL_FROM — the sender is no-reply@$domain, refused unless Resend verified $domain"
  echo "   emails through Resend's API (EMAIL_FROM must be on a domain verified at Resend)"
elif [ -n "$(env_get SMTP_HOST)" ]; then
  for a in $mail_apps; do
    extra+=(--set "apps.$a.env.EMAIL_PROVIDER=smtp" --set-string "apps.$a.env.SMTP_HOST=$(env_get SMTP_HOST)"
      --set-string "apps.$a.env.SMTP_PORT=$(env_get SMTP_PORT | sed 's/^$/587/')"
      --set-string "apps.$a.env.SMTP_STARTTLS=$(env_get SMTP_STARTTLS | sed 's/^$/true/')")
    if [ -n "$(env_get SMTP_USERNAME)" ]; then
      extra+=(--set-string "apps.$a.env.SMTP_USERNAME=$(env_get SMTP_USERNAME)" --set "apps.$a.secretEnv.SMTP_PASSWORD=true")
    fi
  done
  echo "   emails through SMTP $(env_get SMTP_HOST)"
elif [ -n "$(env_get EMAIL_API_KEY)" ]; then
  for a in $mail_apps; do
    extra+=(--set "apps.$a.env.EMAIL_PROVIDER=sendgrid" --set "apps.$a.secretEnv.EMAIL_API_KEY=true")
  done
  echo "   emails through SendGrid"
else
  echo "   no email provider: emails (sign-up codes included) only in the apps' logs — kubectl -n $NS logs deploy/northline-auth"
fi
if [ -n "$(env_get EMAIL_FROM)" ]; then
  for a in $mail_apps; do extra+=(--set-string "apps.$a.env.EMAIL_FROM=$(env_get EMAIL_FROM)"); done
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
  ${OVH_EXTRA_VALUES:+-f "$OVH_EXTRA_VALUES"} \
  --set global.image.registry="$REGISTRY" --set global.image.tag="$IMAGE_TAG" ${extra[@]+"${extra[@]}"} --wait --timeout 20m

if [ "${RESTART:-0}" = 1 ]; then kubectl -n "$NS" rollout restart deployment -l app.kubernetes.io/part-of=northline; fi
echo
kubectl -n "$NS" get pods
kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=northline -o wide 2>/dev/null || true
echo "Next: DNS records → the address above (docs/runbooks/ovh.md § DNS), then the smoke test."
