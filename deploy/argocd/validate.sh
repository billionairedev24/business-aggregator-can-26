#!/usr/bin/env bash
# Offline checks of the GitOps definitions (S-15). No cluster needed; CI runs this by hand only
# (.github/workflows/deploy.yml input `gitops`, GitLab PIPELINE_PART=gitops or all).
#
#  1. helm lint + helm template of the app of apps for every environment × cloud | kubeconform -strict against the
#     Argo CD CRD schemas (Application, AppProject);
#  2. the policy: only dev (and the kind rehearsal) sync by themselves, staging/prod refuse sync.automated, every
#     destination is a named namespace, the northline project whitelists kinds;
#  3. the northline chart rendered exactly as each Application renders it (its value files, in its order) |
#     kubeconform -strict — including the refusal to render staging/prod without promoted digests;
#  4. with kubectl: the Argo CD install kustomization builds and validates.
#
# Needs helm 3.14+, kubeconform 0.6+ (and kubectl for 4). KUBE_VERSION / KUBECONFORM_SCHEMAS as deploy/helm/validate.sh.
set -euo pipefail

cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
AOA=app-of-apps
KUBE_VERSION=${KUBE_VERSION:-1.33.0}
CRD_CATALOG='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
# External Secrets first from the schemas pinned to the installed operator (deploy/helm/schemas, make helm-crd-schemas)
LOCAL_SCHEMAS="$(cd .. && pwd)/helm/schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"
schema_args=(-schema-location default -schema-location "$LOCAL_SCHEMAS" -schema-location "$CRD_CATALOG")
for s in ${KUBECONFORM_SCHEMAS:-}; do schema_args+=(-schema-location "$s"); done
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

failed=0
ok() { echo "ok   $*"; }
fail() { echo "FAIL $*"; failed=1; }

# Value files of the Application named $2 in the rendered app of apps $1, one per line, as written.
value_files() {
  awk -v app="$2" '
    /^kind: Application$/ { inapp = 0 }
    /^  name: / && prev ~ /^metadata:/ { inapp = ($2 == app) }
    { prev = $0 }
    inapp && /valueFiles:/ { invf = 1; match($0, /^ */); depth = RLENGTH; next }
    inapp && invf && /^ *#/ { next }
    inapp && invf && /^ *- / { match($0, /^ */); if (RLENGTH > depth) { sub(/^ *- /, ""); sub(/ *#.*/, ""); print; next } }
    invf { invf = 0 }
  ' "$1"
}

# Renders deploy/helm/northline the way Application northline-<env> does; extra arguments are appended.
render_northline() {
  local rendered=$1 env=$2 cloud=$3; shift 3
  local args=() f path
  while IFS= read -r f; do
    path="$ROOT/deploy/helm/northline/$f"      # value files are relative to the chart
    # Terraform's helm_values stand-in while the environment's infra.yaml is still empty.
    if [[ $f == */infra.yaml && $cloud != kind ]] && ! grep -qv '^\s*\(#.*\)\?$\|^{}$' "$path"; then
      path="$ROOT/deploy/helm/test-values/identities-$cloud.yaml"
    fi
    [[ -f $path ]] || { echo "missing value file $f" >&2; return 1; }
    args+=(-f "$path")
  done < <(value_files "$rendered" "northline-$env")
  helm template northline "$ROOT/deploy/helm/northline" --namespace "northline-$env" --kube-version "$KUBE_VERSION" \
    "${args[@]}" "$@"
}

for env in dev staging prod local; do
  clouds="aws gcp azure"; [[ $env == local ]] && clouds=kind
  for cloud in $clouds; do
    label="$env × $cloud"
    out="$tmp/$env-$cloud.yaml"
    if ! helm lint --quiet --strict "$AOA" -f "envs/$env/env.yaml" --set cloud="$cloud" >"$tmp/lint" 2>&1; then
      fail "lint     $label"; cat "$tmp/lint"; continue
    fi
    if ! helm template root "$AOA" -f "envs/$env/env.yaml" --set cloud="$cloud" >"$out" 2>"$tmp/err"; then
      fail "template $label"; cat "$tmp/err"; continue
    fi
    if kubeconform -strict -summary -kubernetes-version "$KUBE_VERSION" "${schema_args[@]}" "$out" >"$tmp/kc" 2>&1; then
      ok "app of apps $label  ($(tail -1 "$tmp/kc"))"
    else fail "app of apps $label"; cat "$tmp/kc"; fi

    # Policy.
    auto=$(awk '/^kind: Application$/{a=1} a && /^  name: /{n=$2} /^    automated:/{print n}' "$out" | grep -v -- '-root$' || true)
    case $env in
      dev | local) if [[ -n $auto ]]; then ok "  $env syncs by itself: ${auto//$'\n'/ }"; else fail "  $env should sync automatically"; fi ;;
      *) if [[ -z $auto ]]; then ok "  $env: manual sync only"; else fail "  $env has automated sync: $auto"; fi ;;
    esac
    if grep -qE "namespace: ['\"]?\*" "$out"; then fail "  wildcard destination namespace"; fi
    if grep -qE "server: ['\"]?\*" "$out"; then fail "  wildcard destination server"; fi

    # The chart as Argo CD renders it.
    if [[ $env == staging || $env == prod || $env == dev ]]; then
      if render_northline "$out" "$env" "$cloud" >/dev/null 2>"$tmp/err"; then
        # Already promoted: validate as is.
        :
      elif grep -q 'requireDigest' "$tmp/err"; then
        ok "  $env × $cloud refuses to render without promoted digests"
      else fail "  chart render $label"; cat "$tmp/err"; continue; fi
      extra=(-f "$ROOT/deploy/helm/test-values/promoted-images.yaml")
    else
      extra=()
    fi
    if render_northline "$out" "$env" "$cloud" "${extra[@]}" >"$tmp/chart.yaml" 2>"$tmp/err" &&
       kubeconform -strict -summary -kubernetes-version "$KUBE_VERSION" "${schema_args[@]}" "$tmp/chart.yaml" >"$tmp/kc" 2>&1; then
      ok "  chart as Application northline-$env renders it  ($(tail -1 "$tmp/kc"))"
      if [[ ${#extra[@]} -gt 0 ]] && grep -E '^\s+image: ' "$tmp/chart.yaml" | grep -qv '@sha256:'; then
        fail "  an image without digest in $label"
      fi
      # Every kind the chart renders is whitelisted by the environment's project (else Argo CD refuses the sync).
      allowed=$(awk '/namespaceResourceWhitelist:/{w=1; next} w && /^  [a-zA-Z]/{w=0} w && /kind:/{print $NF}' "$out" | sort -u)
      rendered=$(grep -E '^kind: ' "$tmp/chart.yaml" | awk '{print $2}' | sort -u | grep -vx Namespace || true)
      missing=$(comm -23 <(echo "$rendered") <(echo "$allowed"))
      if [[ -z $missing ]]; then ok "  every kind the chart renders is whitelisted in the project"
      else fail "  kinds not whitelisted in the project: ${missing//$'\n'/ }"; fi
    else fail "  chart render $label"; cat "$tmp/err" "$tmp/kc" 2>/dev/null; fi
  done
done

# Plain manifests the add-ons carry (GatewayClass, EnvoyProxy per cloud, S-17).
if kubeconform -strict -summary -kubernetes-version "$KUBE_VERSION" "${schema_args[@]}" addons/*/manifests >"$tmp/kc" 2>&1; then
  ok "add-on manifests  ($(tail -1 "$tmp/kc"))"
else fail "add-on manifests"; cat "$tmp/kc"; fi

# Refusals.
if helm template root "$AOA" -f envs/prod/env.yaml --set sync.automated=true >/dev/null 2>&1; then
  fail "automated sync accepted for prod"; else ok "automated sync refused for prod"; fi
if helm template root "$AOA" -f envs/staging/env.yaml --set sync.automated=true >/dev/null 2>&1; then
  fail "automated sync accepted for staging"; else ok "automated sync refused for staging"; fi
if helm template root "$AOA" -f envs/dev/env.yaml --set repoURL= >/dev/null 2>&1; then
  fail "empty repoURL accepted"; else ok "repoURL required"; fi
# GitLab works the same: only the URL changes.
if helm template root "$AOA" -f envs/dev/env.yaml --set repoURL=https://gitlab.com/northline/northline.git \
    | grep -q 'repoURL: "https://gitlab.com/northline/northline.git"'; then ok "GitLab repoURL"; else fail "GitLab repoURL"; fi

# The Argo CD installation (downloads the pinned upstream manifest).
if command -v kubectl >/dev/null; then
  # The default schema location has no CustomResourceDefinition schema; the CRDs are upstream's own.
  if kubectl kustomize install >"$tmp/install.yaml" 2>"$tmp/err" &&
     kubeconform -strict -summary -skip CustomResourceDefinition -kubernetes-version "$KUBE_VERSION" "${schema_args[@]}" "$tmp/install.yaml" >"$tmp/kc" 2>&1; then
    ok "install kustomization  ($(tail -1 "$tmp/kc"))"
  else fail "install kustomization"; cat "$tmp/err" "$tmp/kc" 2>/dev/null | tail -20; fi
else echo "skip install kustomization (no kubectl)"; fi

exit $failed
