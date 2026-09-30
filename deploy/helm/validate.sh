#!/usr/bin/env bash
# Offline checks of the Northline chart (S-14): helm lint + helm template | kubeconform -strict for every
# environment × cloud, the kind values and the Gateway API variant. No cluster needed. CI runs this
# (.github/workflows/deploy.yml, ci/gitlab/deploy.yml); locally: deploy/helm/validate.sh
#
# Needs helm (3.14+) and kubeconform (0.6+). KUBE_VERSION is the Kubernetes version the schemas are checked
# against (default 1.33.0, the oldest the clusters run); KUBECONFORM_SCHEMAS adds schema locations (air-gapped
# mirrors); the CRD catalog covers Gateway API (and External Secrets, S-6).
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
check "local-kind" -f "$CHART/values-local-kind.yaml"
check "dev × gcp, Gateway API" -f "$CHART/values-dev.yaml" -f "$CHART/values-gcp.yaml" -f test-values/gateway.yaml
check "defaults" 

# Refusals the chart must keep: secrets from values outside local, http URLs in prod.
if helm template northline "$CHART" -f "$CHART/values-prod.yaml" --set secrets.create=true >/dev/null 2>&1; then
  echo "FAIL secrets.create accepted outside local"; failed=1
else echo "ok   secrets.create refused outside local"; fi
if helm template northline "$CHART" -f "$CHART/values-prod.yaml" --set urls.auth=http://auth.example >/dev/null 2>&1; then
  echo "FAIL http URL accepted in prod"; failed=1
else echo "ok   http URLs refused in staging/prod"; fi

rm -f /tmp/helm-lint.$$ /tmp/kubeconform.$$
exit $failed
