#!/usr/bin/env bash
# Offline checks of the Northline chart (S-14): helm lint + helm template | kubeconform -strict for every
# environment × cloud, the kind values, the Gateway API and edge (S-17) variants. No cluster needed. CI runs this
# (.github/workflows/deploy.yml, ci/gitlab/deploy.yml); locally: deploy/helm/validate.sh
#
# Needs helm (3.14+) and kubeconform (0.6+). KUBE_VERSION is the Kubernetes version the schemas are checked
# against (default 1.33.0, the oldest the clusters run); KUBECONFORM_SCHEMAS adds schema locations (air-gapped
# mirrors); the CRD catalog covers Gateway API, External Secrets (S-6), cert-manager and Envoy Gateway (S-17).
set -euo pipefail

cd "$(dirname "$0")"
CHART=northline
KUBE_VERSION=${KUBE_VERSION:-1.33.0}
CRD_CATALOG='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
schema_args=(-schema-location default -schema-location "$CRD_CATALOG")
for s in ${KUBECONFORM_SCHEMAS:-}; do schema_args+=(-schema-location "$s"); done

failed=0
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
check "defaults" 

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
refuse() {
  local label=$1; shift
  if helm template northline "$CHART" "$@" >/dev/null 2>&1; then echo "FAIL $label accepted"; failed=1; else echo "ok   $label refused"; fi
}
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

rm -f /tmp/helm-lint.$$ /tmp/kubeconform.$$
exit $failed
