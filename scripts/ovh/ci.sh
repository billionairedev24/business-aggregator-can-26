#!/usr/bin/env bash
# The pipeline's helpers for the OVHcloud targets (.github/workflows/ovh.yml; docs/runbooks/ovh.md § Today). GitHub
# Actions only: they write $GITHUB_ENV / $GITHUB_STEP_SUMMARY. From a Mac the make targets do the same steps.
#
#   scripts/ovh/ci.sh tfvars TARGET           Terraform variables from the environment's variables (TF_VAR_* into
#                                             $GITHUB_ENV; OVH_TFVARS → <root>/ci.auto.tfvars, git-ignored)
#   scripts/ovh/ci.sh plan-summary TARGET F   the saved plan F as a table of addresses and actions (no values)
#   scripts/ovh/ci.sh open TARGET install|kubeconfig
#       ovh-vps: the deploy key and known_hosts from the secrets, the runner's address/32 into the Network Firewall
#                for this run (ci_ssh_cidr), then scripts/ovh/bootstrap-k3s.sh (install: k3s idempotently; kubeconfig:
#                only the kubeconfig) with the API through the SSH tunnel
#       ovh-mks: the runner's address added to api_allowed_cidrs for this run (when a restriction is set), the
#                kubeconfig from Terraform's output
#       → KUBECONFIG in $GITHUB_ENV
#   scripts/ovh/ci.sh close TARGET            undo open: the runner's rule out, tunnel closed, key and kubeconfig gone
#   scripts/ovh/ci.sh teardown TARGET         before destroy: the releases out of the cluster (ovh-mks: its load
#                                             balancer and volumes) and the buckets emptied (fake data only)
set -euo pipefail
. "$(dirname "$0")/common.sh"

cmd=${1:-}; ovh_target "${2:-}"
: "${GITHUB_ENV:?GITHUB_ENV: scripts/ovh/ci.sh runs in GitHub Actions (from a Mac: the make targets, docs/runbooks/ovh.md)}"
KUBECONFIG_OUT=${RUNNER_TEMP:-/tmp}/kubeconfig-$OVH_TARGET
KEY="$HOME/.ssh/northline-deploy"

setenv() { printf '%s=%s\n' "$1" "$2" >>"$GITHUB_ENV"; export "$1=$2"; }

# list_json "a, b c" → ["a","b","c"]
list_json() { printf '%s\n' "${1:-}" | tr ',' ' ' | awk '{ printf "["; for (i = 1; i <= NF; i++) printf "%s\"%s\"", (i > 1 ? "," : ""), $i; printf "]" }'; }

tfvars() {
  [ -n "${OVH_SERVICE_NAME:-}" ] || die "variable OVH_SERVICE_NAME (the Public Cloud project id) is not set in the $OVH_TARGET environment"
  ovh_location "${OVH_LOCATION:-}"
  # OVH_ENDPOINT was the S-155 setting; the location decides the endpoint now — a stale one would mean the wrong account.
  if [ -n "${OVH_ENDPOINT:-}" ] && [ "$OVH_ENDPOINT" != "$(loc_get api_endpoint)" ]; then
    die "variable OVH_ENDPOINT=$OVH_ENDPOINT contradicts OVH_LOCATION=$OVH_LOCATION ($(loc_get api_endpoint)): delete OVH_ENDPOINT from the $OVH_TARGET environment (make ovh-gh-setup does) or set OVH_LOCATION"
  fi
  setenv OVH_LOCATION "$OVH_LOCATION"
  setenv OVH_ENDPOINT "$(loc_get api_endpoint)"
  setenv TF_VAR_ovh_location "$OVH_LOCATION"
  setenv TF_VAR_service_name "$OVH_SERVICE_NAME"
  setenv TF_VAR_dns_zone_name "${OVH_DOMAIN:-demo.northline.ca}"
  [ -z "${OVH_BUCKET_SUFFIX:-}" ] || setenv TF_VAR_bucket_name_suffix "$OVH_BUCKET_SUFFIX"
  if [ "$OVH_TARGET" = ovh-vps ]; then
    setenv TF_VAR_server "${OVH_SERVER:-instance}"
    setenv TF_VAR_vps_service_name "${OVH_VPS_SERVICE_NAME:-}"
    setenv TF_VAR_ssh_public_key "${OVH_SSH_PUBLIC_KEY:-}"
    [ -z "${OVH_INSTANCE_FLAVOR:-}" ] || setenv TF_VAR_instance_flavor "$OVH_INSTANCE_FLAVOR"
    setenv TF_VAR_admin_cidrs "$(list_json "${OVH_ADMIN_CIDRS:-}")"
    [ -z "${OVH_EDGE_FIREWALL:-}" ] || setenv TF_VAR_edge_firewall "$OVH_EDGE_FIREWALL"
  else
    setenv TF_VAR_api_allowed_cidrs "$(list_json "${OVH_API_ALLOWED_CIDRS:-}")"
    [ -z "${OVH_NODE_COUNT:-}" ] || setenv TF_VAR_node_count "$OVH_NODE_COUNT"
    [ -z "${OVH_NODE_FLAVOR:-}" ] || setenv TF_VAR_node_flavor "$OVH_NODE_FLAVOR"
  fi
  if [ -n "${OVH_TFVARS:-}" ]; then printf '%s\n' "$OVH_TFVARS" >"$OVH_TF/ci.auto.tfvars"; echo "OVH_TFVARS → ci.auto.tfvars"; fi
  echo "Terraform variables for $OVH_TARGET set (location $OVH_LOCATION — $(loc_get label); project $OVH_SERVICE_NAME, zone ${OVH_DOMAIN:-demo.northline.ca})"
  if [ "$(loc_get country)" != CA ]; then
    echo "::notice::$OVH_LOCATION is outside Canada: fake demo data on the dev profile only (the chart refuses staging/prod there) — docs/runbooks/ovh.md § Data residency"
  fi
}

plan_summary() {
  local plan=$1 out=${GITHUB_STEP_SUMMARY:-/dev/stdout}
  {
    echo "### Terraform plan — $OVH_TARGET"
    echo
    terraform -chdir="$OVH_TF" show -json "$plan" | jq -r '
      [.resource_changes[]? | select(.change.actions != ["no-op"] and .change.actions != ["read"])] as $c
      | if ($c | length) == 0 then "No changes."
        else "| resource | action |\n|---|---|\n" + ($c | map("| `\(.address)` | \(.change.actions | join(" → ")) |") | join("\n"))
          + "\n\n" + ($c | map(.change.actions | join("-")) | group_by(.) | map("\(length) × \(.[0])") | join(", "))
        end'
    echo
  } >>"$out"
}

runner_ip() {
  local ip
  ip=$(curl -fsS --max-time 10 https://api.ipify.org || curl -fsS --max-time 10 https://ifconfig.me)
  case "$ip" in (*[!0-9.]* | "") die "could not find the runner's public IPv4 (got '$ip')" ;; esac
  printf '%s' "$ip"
}

# tf_target_apply <address> — a targeted apply with the variables of this shell (the runner's access rule only).
tf_target_apply() { terraform -chdir="$OVH_TF" apply -input=false -auto-approve -target="$1" >/dev/null; }

open_vps() {
  local mode=$1 host ip i
  host=$(tf_out vps .ssh)
  [ -n "$host" ] && [ "$host" != null ] || die "no server in Terraform's state: run the workflow with action apply (OVH_SERVER=instance makes it; OVH_SERVER=vps needs OVH_VPS_SERVICE_NAME)"
  [ -n "${OVH_VPS_SSH_PRIVATE_KEY:-}" ] || die "secret OVH_VPS_SSH_PRIVATE_KEY is not set (make ovh-gh-setup TARGET=ovh-vps)"
  mkdir -p "$HOME/.ssh"; chmod 700 "$HOME/.ssh"
  (umask 077; printf '%s\n' "$OVH_VPS_SSH_PRIVATE_KEY" >"$KEY")
  printf 'Host *\n  IdentityFile %s\n  IdentitiesOnly yes\n' "$KEY" >"$HOME/.ssh/config"
  if [ -n "${OVH_VPS_KNOWN_HOSTS:-}" ]; then
    printf '%s\n' "$OVH_VPS_KNOWN_HOSTS" >"$HOME/.ssh/known_hosts"
  else
    if [ "$(tf_out vps .server)" = instance ]; then
      echo "::notice::the instance's host key is trusted on first sight (ssh-keyscan; it is made by Terraform, so there is no key to record beforehand)"
    else
      echo "::warning::secret OVH_VPS_KNOWN_HOSTS is not set: trusting the VPS's host key on first sight (ssh-keyscan). Set it: make ovh-gh-setup TARGET=ovh-vps"
    fi
    for i in $(seq 1 36); do   # a new instance boots for a minute or two before sshd answers
      ssh-keyscan -t ed25519 "${host#*@}" 2>/dev/null >"$HOME/.ssh/known_hosts" || true
      [ ! -s "$HOME/.ssh/known_hosts" ] || break
      [ "$i" -lt 36 ] || die "no SSH host key from ${host#*@} after 3 minutes (is the server up?)"
      sleep 5
    done
  fi
  setenv SSH_STRICT yes

  if [ "$(tf_out vps .edge_firewall)" = true ]; then
    ip=$(runner_ip)
    setenv TF_VAR_ci_ssh_cidr "$ip/32"
    echo "Network Firewall: SSH from this runner for this run"
    tf_target_apply 'ovh_ip_firewall_rule.box["9-ssh-ci"]'
  else
    echo "::warning::no Network Firewall in front of the server: SSH is open to every address (keys only, rate-limited by ufw)"
  fi
  for i in $(seq 1 36); do
    if ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=yes "$host" true 2>/dev/null; then break; fi
    [ "$i" -lt 36 ] || die "no SSH to $host after 3 minutes (firewall rule, deploy key on the VPS, known_hosts?)"
    sleep 5
  done

  if [ "$mode" = install ]; then
    # k3s idempotently; REGISTRY/IMAGE_TAG unset so the script stops after k3s (the deploy step follows).
    env -u REGISTRY -u IMAGE_TAG KUBECONFIG_OUT="$KUBECONFIG_OUT" SSH_FROM_ANYWHERE=1 \
      "$(dirname "$0")/bootstrap-k3s.sh" "$host"
  else
    KUBECONFIG_OUT="$KUBECONFIG_OUT" "$(dirname "$0")/bootstrap-k3s.sh" kubeconfig "$host"
  fi
}

open_mks() {
  local ip cidrs
  cidrs=${TF_VAR_api_allowed_cidrs:-[]}
  if [ "$(printf '%s' "$cidrs" | jq 'length')" -gt 0 ]; then
    ip=$(runner_ip)
    setenv OVH_CI_API_CIDRS_BEFORE "$cidrs"
    setenv TF_VAR_api_allowed_cidrs "$(printf '%s' "$cidrs" | jq -c --arg ip "$ip/32" '. + [$ip] | unique')"
    echo "Kubernetes API: this runner allowed for this run"
    tf_target_apply 'ovh_cloud_project_kube_iprestrictions.api'
  fi
  (umask 077; terraform -chdir="$OVH_TF" output -raw kubeconfig >"$KUBECONFIG_OUT")
}

open() {
  case "${1:-}" in (install | kubeconfig) ;; (*) die "usage: $0 open TARGET install|kubeconfig" ;; esac
  need terraform jq kubectl
  if [ "$OVH_TARGET" = ovh-vps ]; then open_vps "$1"; else open_mks; fi
  setenv KUBECONFIG "$KUBECONFIG_OUT"
  local i
  for i in $(seq 1 30); do
    if kubectl get nodes >/dev/null 2>&1; then break; fi
    [ "$i" -lt 30 ] || die "kubectl can't reach the $OVH_TARGET cluster"
    sleep 10
  done
  kubectl get nodes -o wide
}

close() {
  local host bad=0
  if [ "$OVH_TARGET" = ovh-vps ]; then
    host=$(tf_out vps .ssh 2>/dev/null || true)
    if [ -n "$host" ] && [ "$host" != null ]; then ssh -O exit -S "$HOME/.ssh/northline-ovh-vps.sock" "$host" 2>/dev/null || true; fi
    if [ -n "${TF_VAR_ci_ssh_cidr:-}" ]; then
      TF_VAR_ci_ssh_cidr="" tf_target_apply 'ovh_ip_firewall_rule.box["9-ssh-ci"]' \
        && echo "Network Firewall: the runner's SSH rule removed" \
        || { echo "::error::could not remove the runner's SSH rule (9-ssh-ci): run the workflow with action apply to reconcile"; bad=1; }
    fi
  elif [ -n "${OVH_CI_API_CIDRS_BEFORE:-}" ]; then
    TF_VAR_api_allowed_cidrs="$OVH_CI_API_CIDRS_BEFORE" tf_target_apply 'ovh_cloud_project_kube_iprestrictions.api' \
      && echo "Kubernetes API: the runner's address removed" \
      || { echo "::error::could not restore api_allowed_cidrs: run the workflow with action apply to reconcile"; bad=1; }
  fi
  rm -f "$KEY" "$KUBECONFIG_OUT"
  return $bad
}

teardown() {
  need terraform jq
  if [ "$OVH_TARGET" = ovh-mks ]; then
    if (open_mks) && KUBECONFIG="$KUBECONFIG_OUT" kubectl get namespaces >/dev/null 2>&1; then
      export KUBECONFIG="$KUBECONFIG_OUT"
      helm uninstall northline northline-data -n "$OVH_NAMESPACE" --wait --timeout 10m 2>/dev/null || true
      # Envoy's LoadBalancer Service owns the OVHcloud load balancer; the namespaces own the volumes.
      kubectl delete namespace "$OVH_NAMESPACE" envoy-gateway-system --ignore-not-found --wait --timeout=10m || true
      echo "releases, load balancer and volumes removed"
    else
      echo "::warning::cluster unreachable: check Public Cloud › Load Balancer and Block Storage for leftovers after destroy"
    fi
  fi
  # Buckets must be empty for destroy (fake data). Each bucket with its own user's keys.
  local buckets endpoint region k b
  buckets=$(tf_out storage '.buckets | to_entries[] | "\(.key)=\(.value)"' 2>/dev/null || true)
  endpoint=$(tf_out storage .s3_endpoint 2>/dev/null || true)
  region=$(tf_out storage .s3_region 2>/dev/null || true)
  if [ -n "$buckets" ] && command -v aws >/dev/null 2>&1; then
    for k in $buckets; do
      b=${k#*=}; k=${k%%=*}
      AWS_ACCESS_KEY_ID=$(tf_out s3_credentials ".\"$k\".access_key_id") \
      AWS_SECRET_ACCESS_KEY=$(tf_out s3_credentials ".\"$k\".secret_access_key") \
      AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required \
        aws --endpoint-url "$endpoint" --region "$region" s3 rm "s3://$b" --recursive --only-show-errors \
        && echo "bucket $b emptied" || echo "::warning::could not empty $b: empty it in the console (Object Storage › the bucket)"
    done
  fi
}

case "$cmd" in
  tfvars) tfvars ;;
  plan-summary) plan_summary "${3:?plan file}" ;;
  open) open "${3:-}" ;;
  close) close ;;
  teardown) teardown ;;
  *) die "usage: $0 tfvars|plan-summary|open|close|teardown TARGET …" ;;
esac
