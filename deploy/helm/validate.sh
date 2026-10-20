#!/usr/bin/env bash
# Offline checks of the Northline chart (S-14) and of the dry run's data services chart (northline-data): helm lint + helm template | kubeconform -strict for every
# environment × cloud (and the dry run on Google Cloud and OVHcloud), the kind values, the Gateway API and edge (S-17) variants. No cluster needed. CI runs this
# (.github/workflows/deploy.yml, ci/gitlab/deploy.yml); locally: deploy/helm/validate.sh
#
# Needs helm (3.14+) and kubeconform (0.6+). KUBE_VERSION is the Kubernetes version the schemas are checked
# against (default 1.33.0, the oldest the clusters run); KUBECONFORM_SCHEMAS adds schema locations (air-gapped
# mirrors); the CRD catalog covers Gateway API, cert-manager and Envoy Gateway (S-17). External Secrets (S-6) comes
# first from deploy/helm/schemas, generated from the operator version Argo CD installs (make helm-crd-schemas): the
# catalog's main branch changes under us (its SecretStore schema stopped compiling in kubeconform in Oct 2026).
set -euo pipefail

cd "$(dirname "$0")"
CHART=northline
KUBE_VERSION=${KUBE_VERSION:-1.33.0}
CRD_CATALOG='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
LOCAL_SCHEMAS="$PWD/schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"
schema_args=(-schema-location default -schema-location "$LOCAL_SCHEMAS" -schema-location "$CRD_CATALOG")
for s in ${KUBECONFORM_SCHEMAS:-}; do schema_args+=(-schema-location "$s"); done

failed=0
refuse() {
  local label=$1; shift
  if helm template northline "$CHART" "$@" >/dev/null 2>&1; then echo "FAIL $label accepted"; failed=1; else echo "ok   $label refused"; fi
}
check() {
  local label=$1; shift
  if ! helm lint --quiet --strict "$CHART" "$@" >/tmp/helm-lint.$$ 2>&1; then
    echo "FAIL lint     $label"; cat /tmp/helm-lint.$$; failed=1; return
  fi
  if ! helm template northline "$CHART" --namespace northline-test --kube-version "$KUBE_VERSION" "$@" \
      | kubeconform -strict -summary -kubernetes-version "$KUBE_VERSION" "${schema_args[@]}" >/tmp/kubeconform.$$ 2>&1; then
    echo "FAIL template $label"; cat /tmp/kubeconform.$$; failed=1; return
  fi
  echo "ok   $label  ($(tail -1 /tmp/kubeconform.$$))"
}

for env in dev staging prod; do
  for cloud in aws gcp azure; do
    check "$env × $cloud" -f "$CHART/values-$env.yaml" -f "$CHART/values-$cloud.yaml" -f "test-values/identities-$cloud.yaml"
  done
done
check "prod × aws + partners (S-30)" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml -f test-values/partners.yaml
check "staging × aws + internal docs behind an IP allowlist (S-126)" -f "$CHART/values-staging.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml --set apps.docs-internal.enabled=true --set "edge.docsInternal.allowedCIDRs={203.0.113.0/24}"
check "local-kind" -f "$CHART/values-local-kind.yaml"
check "local-kind + External Secrets (fake)" -f "$CHART/values-local-kind.yaml" -f "$CHART/values-local-kind-eso.yaml"
check "local-kind + edge (local CA)" -f "$CHART/values-local-kind.yaml" -f "$CHART/values-local-kind-edge.yaml"
check "dev × aws, plain Secret (no ESO)" -f "$CHART/values-dev.yaml" -f "$CHART/values-aws.yaml" --set externalSecrets.enabled=false
check "dev × gcp, existing Gateway (no edge)" -f "$CHART/values-dev.yaml" -f "$CHART/values-gcp.yaml" -f test-values/identities-gcp.yaml -f test-values/gateway.yaml
# S-17: the edge variants.
for cloud in aws gcp azure; do
  check "prod × $cloud, wildcard DNS-01 + custom domain" -f "$CHART/values-prod.yaml" -f "$CHART/values-$cloud.yaml" -f "test-values/identities-$cloud.yaml" -f test-values/edge-wildcard.yaml
done
check "staging × aws, Ingress + cert-manager" -f "$CHART/values-staging.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml -f test-values/edge-ingress.yaml --set edge.domainReconciler.enabled=false
# The search read model on Meilisearch (the production dry run's hosted instance): the key reaches the api, the worker and
# the search-indices Job.
check "prod × aws, search on Meilisearch" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml -f test-values/search-meilisearch.yaml
if helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml \
    -f test-values/search-meilisearch.yaml --show-only templates/search-indices-job.yaml \
    | grep -q 'secretKey: MEILISEARCH_API_KEY'; then echo "ok   search-indices Job maps MEILISEARCH_API_KEY"
else echo "FAIL search-indices Job without MEILISEARCH_API_KEY"; failed=1; fi
check "defaults" 
# S-111: the Collector with any OTLP backend (Grafana Cloud) instead of the cloud's own exporters.
check "prod × aws, Collector → OTLP backend" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml -f test-values/observability-otlp.yaml
if helm template northline "$CHART" -f "$CHART/values-dev.yaml" --set observability.collector.enabled=true --set 'observability.collector.pipelines.traces={nowhere}' >/dev/null 2>&1; then
  echo "FAIL Collector pipeline with an undefined exporter accepted"; failed=1
else echo "ok   Collector pipelines only use defined exporters"; fi

# S-113: alerting — the rules and the severity routing in each format, with each paging provider.
check "prod × aws + alerting: PrometheusRule, AlertmanagerConfig (PagerDuty page, webhook ticket)" -f "$CHART/values-prod.yaml" \
  -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml -f test-values/alerting.yaml
check "prod × gcp + alerting: GMP Rules, AlertmanagerConfig (Opsgenie)" -f "$CHART/values-prod.yaml" -f "$CHART/values-gcp.yaml" \
  -f test-values/identities-gcp.yaml -f test-values/alerting.yaml --set alerting.rules.format=gmpRules \
  --set alerting.routing.page.provider=opsgenie --set alerting.routing.ticket.provider=opsgenie
check "staging × azure + alerting: rule files and alertmanager.yml in ConfigMaps (webhooks)" -f "$CHART/values-staging.yaml" \
  -f "$CHART/values-azure.yaml" -f test-values/identities-azure.yaml -f test-values/alerting.yaml \
  --set alerting.rules.format=configMap --set alerting.routing.format=configMap --set alerting.routing.page.provider=webhook
out=$(helm template northline "$CHART" --namespace northline-prod -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" \
  -f test-values/identities-aws.yaml -f test-values/alerting.yaml)
keys=$(awk '/^  name: northline-alerting$/{e=1} e && /secretKey:/{print $3} /^---/{e=0}' <<<"$out" | sort | tr '\n' ' ')
if [[ $keys == "ALERTING_PAGERDUTY_ROUTING_KEY ALERTING_TICKET_WEBHOOK_URL " ]] \
    && grep -q 'namespace: northline-prod' <<<"$(awk '/^kind: PrometheusRule$/{r=1} r' <<<"$out")" \
    && ! grep -q 'runbook_url: docs/' <<<"$out" && ! grep -qE '^kind: Secret$' <<<"$out"; then
  echo "ok   alerting: only the selected providers' keys, alerts labelled with the namespace, runbook URLs absolute"
else echo "FAIL alerting render: keys [$keys]"; failed=1; fi
if [[ -n "$(helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml \
    --show-only templates/alerting.yaml 2>/dev/null)" ]]; then echo "FAIL alerting renders while alerting.enabled=false"; failed=1
else echo "ok   no alerting objects unless alerting.enabled"; fi
refuse "an unknown paging provider" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/alerting.yaml \
  --set alerting.routing.page.provider=pigeon
refuse "an unknown rules format" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/alerting.yaml \
  --set alerting.rules.format=thanos

# Refusals the chart must keep: secrets from values outside local, http URLs in prod.
if helm template northline "$CHART" -f "$CHART/values-prod.yaml" --set secrets.create=true >/dev/null 2>&1; then
  echo "FAIL secrets.create accepted outside local"; failed=1
else echo "ok   secrets.create refused outside local"; fi
if helm template northline "$CHART" -f "$CHART/values-prod.yaml" --set urls.auth=http://auth.example >/dev/null 2>&1; then
  echo "FAIL http URL accepted in prod"; failed=1
else echo "ok   http URLs refused in staging/prod"; fi
if helm template northline "$CHART" -f "$CHART/values-dev.yaml" --set externalSecrets.enabled=true --set externalSecrets.provider=fake >/dev/null 2>&1; then
  echo "FAIL fake secret store accepted outside local"; failed=1
else echo "ok   fake secret store refused outside local"; fi
# No secret value may appear in a cloud render (S-6): only remote key names.
for cloud in aws gcp azure; do
  if helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-$cloud.yaml" -f "test-values/identities-$cloud.yaml" \
      | grep -qE '^kind: Secret$'; then echo "FAIL prod × $cloud renders a Secret"; failed=1
  else echo "ok   prod × $cloud renders no Secret (External Secrets only)"; fi
done

# S-127: the api host routes the MCP server and its protected resource metadata; apps.api.mcp=false removes both.
mcp_out=$(helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml)
no_mcp=$(helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml --set apps.api.mcp=false)
if grep -q 'value: /mcp }' <<<"$mcp_out" && grep -q 'value: /.well-known/oauth-protected-resource }' <<<"$mcp_out" \
    && ! grep -q 'value: /mcp }' <<<"$no_mcp"; then echo "ok   api host routes /mcp and its metadata (apps.api.mcp)"
else echo "FAIL MCP routes on the api host"; failed=1; fi

# S-17: every deployed environment serves every host over TLS with HSTS, plain HTTP only redirects, and the refusals.
for env in dev staging prod; do
  for cloud in aws gcp azure; do
    out=$(helm template northline "$CHART" -f "$CHART/values-$env.yaml" -f "$CHART/values-$cloud.yaml" -f "test-values/identities-$cloud.yaml")
    hosts=$(grep -E '^  hostnames: \[' <<<"$out" | sed -E 's/.*\["(.*)"\]/\1/' | sort)
    certs=$(awk '/^kind: Certificate$/{c=1} c && /^    - /{print $2; c=0}' <<<"$out" | sort)
    listeners=$(grep -cE '^      protocol: HTTPS$' <<<"$out")
    hsts=$(grep -c 'name: Strict-Transport-Security' <<<"$out")
    rules=$(grep -cE '^    - matches:$' <<<"$out")
    redirect=$(grep -c 'requestRedirect: { scheme: https, port: 443, statusCode: 301 }' <<<"$out")
    if [[ -n $hosts && $hosts == "$certs" && $listeners -eq $(wc -l <<<"$hosts") && $hsts -eq $rules && $redirect -eq 1 ]]; then
      echo "ok   $env × $cloud: $(wc -l <<<"$hosts") hosts, each with a certificate, an HTTPS listener and HSTS; HTTP redirects"
    else echo "FAIL $env × $cloud edge: hosts [$hosts] certs [$certs] listeners $listeners hsts $hsts/$rules redirect $redirect"; failed=1; fi
  done
done
refuse "wildcard without DNS-01" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" --set edge.certManager.wildcard=true
refuse "DNS-01 without the cloud's solver" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" --set edge.certManager.issuer.solver=dns01
refuse "a CA issuer in prod" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" --set edge.certManager.issuer.type=ca --set edge.certManager.issuer.caSecretName=x
refuse "edge without routes" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" --set gateway.enabled=false
refuse "API documentation routes in prod (S-125)" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" --set apps.api.docsRoutes=true
refuse "internal docs site without an IP allowlist (S-126)" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" --set apps.docs-internal.enabled=true

# S-31: merchants' own domains. The api reconciles them (Role limited to Gateways, HTTPRoutes and Certificates in its
# namespace, token mounted only in the api), certificates from Let's Encrypt staging outside prod, and the refusals.
for env in dev staging prod; do
  for cloud in aws gcp azure; do
    out=$(helm template northline "$CHART" --namespace "northline-$env" -f "$CHART/values-$env.yaml" -f "$CHART/values-$cloud.yaml" -f "test-values/identities-$cloud.yaml")
    rules=$(awk '/^kind: Role$/{r=1} r && /^  - apiGroups:/{print} r && /^    resources:/{print} /^---/{r=0}' <<<"$out" | tr -d ' ' | tr '\n' ' ')
    expected='-apiGroups:[gateway.networking.k8s.io] resources:[gateways,httproutes] -apiGroups:[cert-manager.io] resources:[certificates] '
    mounted=$(grep -c 'automountServiceAccountToken: true' <<<"$out")
    server=$(awk '/name: northline-acme-custom$/{i=1} i && /server:/{print $2; exit}' <<<"$out")
    want=https://acme-staging-v02.api.letsencrypt.org/directory; [[ $env == prod ]] && want=https://acme-v02.api.letsencrypt.org/directory
    if [[ $rules == "$expected" && $mounted -eq 1 && $server == "$want" ]] \
        && grep -q 'DOMAINS_EDGE_PROVIDER: "kubernetes"' <<<"$out" \
        && grep -q "DOMAINS_EDGE_NAMESPACE: \"northline-$env\"" <<<"$out" \
        && grep -q 'DOMAINS_TARGET_HOST: "pages\.' <<<"$out" \
        && grep -q 'name: northline-api$' <<<"$(awk '/^kind: RoleBinding$/{r=1} r' <<<"$out")"; then
      echo "ok   $env × $cloud: domain reconciler (RBAC in its namespace, token in the api only, issuer ${server#https://})"
    else echo "FAIL $env × $cloud domain reconciler: rules [$rules] mounted $mounted server $server"; failed=1; fi
  done
done
refuse "Let's Encrypt production for merchants outside prod" -f "$CHART/values-staging.yaml" -f "$CHART/values-aws.yaml" \
  --set edge.domainReconciler.issuer.server=https://acme-v02.api.letsencrypt.org/directory
refuse "more than 64 listeners per shard Gateway" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" \
  --set edge.domainReconciler.listenersPerGateway=65
refuse "the domain reconciler without the consumer app" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" \
  --set apps.consumer.enabled=false
refuse "the domain reconciler without the chart's Gateway" -f "$CHART/values-staging.yaml" -f "$CHART/values-aws.yaml" \
  -f test-values/identities-aws.yaml -f test-values/edge-ingress.yaml
refuse "the in-memory DNS in a cluster" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" \
  --set edge.domainReconciler.dns.provider=local
if helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml \
    -f test-values/edge-wildcard.yaml | awk '/name: northline-book-example-ca$/{r=1} r && /Strict-Transport-Security/{getline; print; exit}' \
    | grep -qE 'value: "?max-age=63072000"?$'; then
  echo "ok   a merchant's domain gets HSTS without includeSubDomains"
else echo "FAIL a merchant's domain gets HSTS with includeSubDomains"; failed=1; fi

# S-16: the migration Job never names the dev seed in any deployed render.
for env in dev staging prod; do
  if helm template northline "$CHART" -f "$CHART/values-$env.yaml" -f "$CHART/values-aws.yaml" | grep -q 'seed-dev\|devSeed'; then
    echo "FAIL $env render mentions the dev seed"; failed=1
  else echo "ok   $env: no dev seed in the migration Job"; fi
done

# S-25: the topics Job applies the catalogue on MSK / Managed Kafka and only reports on Event Hubs (Terraform owns it).
for cloud in aws gcp azure; do
  expected=apply; [[ $cloud == azure ]] && expected=plan
  # (rendered first: `grep -q` closing the pipe early would fail helm under pipefail)
  job=$(helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-$cloud.yaml" -f "test-values/identities-$cloud.yaml" \
      --show-only templates/kafka-topics-job.yaml)
  if grep -q "\"ca.northline.worker.topics.TopicsCommand\", \"$expected\"" <<<"$job"; then
    echo "ok   prod × $cloud: Kafka topics Job runs $expected"
  else echo "FAIL prod × $cloud: Kafka topics Job does not run $expected"; failed=1; fi
done

# S-42: the search-indices Job runs the command of the worker image before every release, named by what it runs.
job=$(helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml \
    --show-only templates/search-indices-job.yaml)
if grep -q '"ca.northline.worker.search.SearchIndicesCommand", "apply"' <<<"$job"; then
  echo "ok   prod × aws: search-indices Job runs apply"
else echo "FAIL prod × aws: search-indices Job does not run apply"; failed=1; fi
if helm template northline "$CHART" -f "$CHART/values-local-kind.yaml" --show-only templates/search-indices-job.yaml >/dev/null 2>&1; then
  echo "FAIL local-kind renders the search-indices Job without Elasticsearch"; failed=1
else echo "ok   local-kind: no search-indices Job"; fi

# S-71: the reindex Job exists only while a run id is set, and refuses a run id that isn't a DNS label.
if [[ -n "$(helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml \
    --show-only templates/search-reindex-job.yaml 2>/dev/null)" ]]; then
  echo "FAIL the search reindex Job renders without a run id"; failed=1
else echo "ok   no search reindex Job without a run id"; fi
job=$(helm template northline "$CHART" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml \
    --set searchReindex.runId=2026-10-01 --show-only templates/search-reindex-job.yaml)
if grep -q 'name: northline-search-reindex-2026-10-01' <<<"$job" && grep -q '"ca.northline.worker.search.SearchReindexCommand"' <<<"$job"; then
  echo "ok   searchReindex.runId renders the reindex Job"
else echo "FAIL searchReindex.runId does not render the reindex Job"; failed=1; fi
if helm template northline "$CHART" -f "$CHART/values-prod.yaml" --set searchReindex.runId=Not_A_Label >/dev/null 2>&1; then
  echo "FAIL a run id that isn't a DNS label accepted"; failed=1
else echo "ok   search reindex run id must be a DNS label"; fi
check "prod × aws + search reindex Job (S-71)" -f "$CHART/values-prod.yaml" -f "$CHART/values-aws.yaml" -f test-values/identities-aws.yaml --set searchReindex.runId=2026-10-01 --set searchReindex.keepOld=true

# GCP dry run (docs/runbooks/dryrun-gcp.md): the northline chart on values-gcp + values-dryrun with Terraform's values
# (every data service in the cluster), and the in-cluster data services chart (deploy/helm/northline-data).
check "dryrun × gcp" -f "$CHART/values-gcp.yaml" -f "$CHART/values-dryrun.yaml" -f test-values/infra-dryrun.yaml
out=$(helm template northline "$CHART" --namespace northline-dryrun -f "$CHART/values-gcp.yaml" -f "$CHART/values-dryrun.yaml" -f test-values/infra-dryrun.yaml)
if grep -q 'SPRING_PROFILES_ACTIVE: "dev"' <<<"$out" && ! grep -q 'redis-ca' <<<"$out" && grep -q 'SearchIndicesCommand' <<<"$out" && ! grep -q 'otel-collector' <<<"$out" \
    && grep -q 'secretKey: MEILISEARCH_API_KEY' <<<"$out" && grep -q 'KAFKA_REPLICATION_FACTOR: "1"' <<<"$out" \
    && ! grep -qE '^kind: Secret$' <<<"$out"; then
  echo "ok   dryrun × gcp: dev profile, no Memorystore CA, search-indices Job on Meilisearch, no Collector, Meilisearch key mapped, topics RF 1, no Secret"
else echo "FAIL dryrun × gcp render"; failed=1; fi
check "dryrun × gcp + Collector (flag)" -f "$CHART/values-gcp.yaml" -f "$CHART/values-dryrun.yaml" -f test-values/infra-dryrun.yaml \
  --set observability.collector.enabled=true
refuse "dryrun without a Spring profile" -f "$CHART/values-gcp.yaml" -f "$CHART/values-dryrun.yaml" -f test-values/infra-dryrun.yaml --set global.springProfiles=
refuse "dryrun over http" -f "$CHART/values-gcp.yaml" -f "$CHART/values-dryrun.yaml" -f test-values/infra-dryrun.yaml --set urls.auth=http://auth.example

# OVHcloud (docs/runbooks/ovh.md): the same dry-run values with the OVHcloud overlays instead of values-gcp.yaml —
# one plain Secret (no External Secrets), local signing keys on the data chart's volume (auth: Recreate), S3 keys for
# OVHcloud Object Storage.
for t in vps mks; do
  check "dryrun × ovh-$t" -f "$CHART/values-dryrun.yaml" -f "$CHART/values-ovh-$t.yaml" -f "test-values/infra-ovh-$t.yaml"
  out=$(helm template northline "$CHART" --namespace northline-dryrun -f "$CHART/values-dryrun.yaml" -f "$CHART/values-ovh-$t.yaml" -f "test-values/infra-ovh-$t.yaml")
  auth=$(awk '/^kind: Deployment$/{d=1} d && /^  name: northline-auth$/{a=1} a && /^---/{exit} a' <<<"$out")
  if grep -q 'SPRING_PROFILES_ACTIVE: "dev"' <<<"$out" && ! grep -qE '^kind: (ExternalSecret|SecretStore|Secret)$' <<<"$out" \
      && grep -q 'name: northline-secrets' <<<"$out" && ! grep -q 'northline-.*-secrets$' <<<"$out" \
      && grep -q 'key: STORAGE_ACCESS_KEY' <<<"$out" && grep -q 'key: KMS_LOCAL_KEY' <<<"$out" \
      && grep -q 'STORAGE_ENDPOINT: "https://s3.bhs.io.cloud.ovh.net"' <<<"$out" && grep -q 'KMS_PROVIDER: "local"' <<<"$out" \
      && grep -q 'type: Recreate' <<<"$auth" && grep -q 'claimName: northline-auth-signing-keys' <<<"$auth" \
      && grep -q -- '- name: northline-registry' <<<"$out" && ! grep -q 'googlecloud' <<<"$out" && ! grep -q 'redis-ca' <<<"$out"; then
    echo "ok   dryrun × ovh-$t: dev profile, one plain Secret (no External Secrets), S3 keys and local KMS key mapped, auth Recreate on its key volume"
  else echo "FAIL dryrun × ovh-$t render"; failed=1; fi
  check "dryrun × ovh-$t + Collector (flag)" -f "$CHART/values-dryrun.yaml" -f "$CHART/values-ovh-$t.yaml" -f "test-values/infra-ovh-$t.yaml" \
    --set observability.collector.enabled=true
done
# S-155: what scripts/ovh/deploy.sh adds for a first demo without Stripe on the operator's own domain (OVH_DOMAIN):
# identity and age checks on the local fakes, the Stripe keys optional, every host under that domain.
nostripe=(--set apps.api.env.IDENTITY_PROVIDER=local --set apps.api.env.AGE_VERIFICATION_PROVIDER=local
  --set apps.api.secretEnv.STRIPE_SECRET_KEY=false --set apps.api.secretEnv.STRIPE_PUBLISHABLE_KEY=false
  --set apps.api.secretEnv.STRIPE_WEBHOOK_SECRET=false --set apps.api.secretEnv.STRIPE_CONNECT_WEBHOOK_SECRET=false
  --set urls.auth=https://auth.demo.example --set urls.api=https://api.demo.example --set urls.studio=https://studio.demo.example
  --set urls.consumer=https://demo.example --set urls.console=https://console.demo.example --set urls.pages=https://pages.demo.example
  --set urls.webauthnRpId=demo.example --set-string "apps.api.env.EMAIL_FROM=Demo <no-reply@demo.example>"
  --set-string "apps.auth.env.EMAIL_FROM=Demo <no-reply@demo.example>" --set-string "apps.worker.env.EMAIL_FROM=Demo <no-reply@demo.example>")
check "dryrun × ovh-vps, no Stripe, own domain" -f "$CHART/values-dryrun.yaml" -f "$CHART/values-ovh-vps.yaml" -f test-values/infra-ovh-vps.yaml "${nostripe[@]}"
out=$(helm template northline "$CHART" -f "$CHART/values-dryrun.yaml" -f "$CHART/values-ovh-vps.yaml" -f test-values/infra-ovh-vps.yaml "${nostripe[@]}")
if grep -q 'IDENTITY_PROVIDER: "local"' <<<"$out" && grep -A4 'key: STRIPE_SECRET_KEY' <<<"$out" | grep -q 'optional: true' \
    && grep -q 'https://studio.demo.example' <<<"$out" && ! grep -q 'demo.northline.ca' <<<"$out"; then
  echo "ok   dryrun × ovh-vps without Stripe: local identity, Stripe keys optional, hosts under the operator's domain"
else echo "FAIL dryrun × ovh-vps without Stripe / own domain"; failed=1; fi
if [[ $(helm template northline "$CHART" -f "$CHART/values-dryrun.yaml" -f "$CHART/values-ovh-mks.yaml" -f test-values/infra-ovh-mks.yaml \
    | awk '/^kind: Deployment$/{d=1} d && /^  name: northline-api$/{a=1} a && /^  replicas:/{print $2; exit}') == 2 ]]; then
  echo "ok   dryrun × ovh-mks: two replicas of the stateless apps"
else echo "FAIL dryrun × ovh-mks: api not at two replicas"; failed=1; fi

DATA=northline-data
check_data() {
  local label=$1; shift
  if ! helm lint --quiet --strict "$DATA" "$@" >/tmp/helm-lint.$$ 2>&1; then
    echo "FAIL lint     $label"; cat /tmp/helm-lint.$$; failed=1; return
  fi
  if ! helm template northline-data "$DATA" --namespace northline-dryrun --kube-version "$KUBE_VERSION" "$@" \
      | kubeconform -strict -summary -kubernetes-version "$KUBE_VERSION" "${schema_args[@]}" >/tmp/kubeconform.$$ 2>&1; then
    echo "FAIL template $label"; cat /tmp/kubeconform.$$; failed=1; return
  fi
  echo "ok   $label  ($(tail -1 /tmp/kubeconform.$$))"
}
check_data "northline-data: every service in the cluster" -f test-values/data-dryrun.yaml
check_data "northline-data: Postgres only (Valkey, Meilisearch, Kafka external)" -f test-values/data-dryrun.yaml \
  --set valkey.enabled=false --set meilisearch.enabled=false --set kafka.enabled=false
data_out=$(helm template northline-data "$DATA" --namespace northline-dryrun -f test-values/data-dryrun.yaml)
pinned=$(grep -c 'topology.kubernetes.io/zone: "northamerica-northeast1-a"' <<<"$data_out")
if [[ $pinned -eq 4 ]] && grep -q 'destinationPath: gs://northline-dryrun-pg-backups' <<<"$data_out" \
    && grep -q 'CREATE EXTENSION IF NOT EXISTS postgis;' <<<"$data_out" && ! grep -qE '^kind: Secret$' <<<"$data_out"; then
  echo "ok   northline-data: 4 services pinned to the zone, Postgres backups to GCS, PostGIS created, no Secret"
else echo "FAIL northline-data render (zone-pinned: $pinned)"; failed=1; fi
if helm template northline-data "$DATA" --set zone=northamerica-northeast1-a --set externalSecrets.gcp.projectID=p \
    --set-json 'externalSecrets.remoteKeys={"DB_PASSWORD":"a","REDIS_PASSWORD":"b","MEILISEARCH_API_KEY":"c"}' >/dev/null 2>&1; then
  echo "FAIL northline-data accepted Postgres backups without a bucket"; failed=1
else echo "ok   northline-data refuses Postgres backups without a bucket"; fi
if helm template northline-data "$DATA" -f test-values/data-dryrun.yaml --set zone= >/dev/null 2>&1; then
  echo "FAIL northline-data accepted no zone"; failed=1
else echo "ok   northline-data refuses to render without the zone"; fi
if helm template northline-data "$DATA" -f test-values/data-dryrun.yaml --set postgres.instances=2 >/dev/null 2>&1; then
  echo "FAIL northline-data accepted 2 Postgres instances"; failed=1
else echo "ok   northline-data: one Postgres instance only"; fi

# OVHcloud: no zone pinning, credentials from Secrets the script writes (no SecretStore/ExternalSecret), Postgres
# backups to Object Storage over S3, the auth signing-key volume; ovh-mks with Managed PostgreSQL has no Cluster.
check_data "northline-data: ovh-vps (everything on the VPS, S3 backups)" -f test-values/data-ovh-vps.yaml
check_data "northline-data: ovh-mks (Managed PostgreSQL)" -f test-values/data-ovh-mks.yaml
data_out=$(helm template northline-data "$DATA" --namespace northline-dryrun -f test-values/data-ovh-vps.yaml)
if ! grep -q 'topology.kubernetes.io/zone' <<<"$data_out" && ! grep -qE '^kind: (ExternalSecret|SecretStore|Secret)$' <<<"$data_out" \
    && grep -q 'destinationPath: s3://northline-ovh-vps-pg-backups/' <<<"$data_out" && grep -q 'endpointURL: https://s3.bhs.io.cloud.ovh.net' <<<"$data_out" \
    && grep -q 'name: northline-pg-backup-s3, key: ACCESS_KEY_ID' <<<"$data_out" && ! grep -q 'serviceAccountTemplate' <<<"$data_out" \
    && grep -q '^kind: PersistentVolumeClaim$' <<<"$data_out" && grep -q '^  name: northline-auth-signing-keys$' <<<"$data_out" && grep -q 'storageClassName: local-path' <<<"$data_out"; then
  echo "ok   northline-data ovh-vps: no zone pinning, no External Secrets, S3 backups with the script's keys, auth key volume"
else echo "FAIL northline-data ovh-vps render"; failed=1; fi
if helm template northline-data "$DATA" --namespace northline-dryrun -f test-values/data-ovh-mks.yaml | grep -qE '^kind: (Cluster|ScheduledBackup)$'; then
  echo "FAIL northline-data ovh-mks renders a Postgres cluster next to Managed PostgreSQL"; failed=1
else echo "ok   northline-data ovh-mks: no in-cluster Postgres"; fi
if helm template northline-data "$DATA" -f test-values/data-ovh-vps.yaml --set externalSecrets.provider=aws >/dev/null 2>&1; then
  echo "FAIL northline-data accepted an unknown secrets provider"; failed=1
else echo "ok   northline-data: secrets provider gcp or none only"; fi
if helm template northline-data "$DATA" -f test-values/data-ovh-vps.yaml --set postgres.backup.provider=azure >/dev/null 2>&1; then
  echo "FAIL northline-data accepted an unknown backup provider"; failed=1
else echo "ok   northline-data: Postgres backups to gcs or s3 only"; fi
# Restore: a new cluster bootstrapped from the backups, archiving into a new folder of the bucket.
check_data "northline-data: restore from the S3 backups (recovery)" -f test-values/data-ovh-vps.yaml --set postgres.recovery.enabled=true \
  --set postgres.backup.serverName=northline-pg-2 --set postgres.recovery.targetTime=2026-10-20T14:00:00Z
if helm template northline-data "$DATA" -f test-values/data-ovh-vps.yaml --set postgres.recovery.enabled=true >/dev/null 2>&1; then
  echo "FAIL northline-data accepted a restore archiving into the folder it restores from"; failed=1
else echo "ok   northline-data: a restored cluster archives into a new folder"; fi
if helm template northline-data "$DATA" -f test-values/data-ovh-vps.yaml --set postgres.backup.s3.endpointURL= >/dev/null 2>&1; then
  echo "FAIL northline-data accepted S3 backups without an endpoint"; failed=1
else echo "ok   northline-data refuses S3 backups without an endpoint"; fi

rm -f /tmp/helm-lint.$$ /tmp/kubeconform.$$
exit $failed
