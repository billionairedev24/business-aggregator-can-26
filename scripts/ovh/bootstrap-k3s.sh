#!/usr/bin/env bash
# ovh-vps (docs/runbooks/ovh.md § ovh-vps): turn the freshly ordered OVHcloud VPS (Ubuntu 24.04) into a one-node k3s
# cluster, from your machine over SSH — no Ansible, no cloud-init. Then (with REGISTRY and IMAGE_TAG set) install the
# add-ons and both charts through scripts/ovh/deploy.sh.
#
#   scripts/ovh/bootstrap-k3s.sh [user@host]     (make ovh-bootstrap; host default: terraform output vps.ssh)
#   scripts/ovh/bootstrap-k3s.sh tunnel [user@host]   (make ovh-tunnel) only open the SSH tunnel to the k3s API
#   scripts/ovh/bootstrap-k3s.sh kubeconfig [user@host]   only fetch the kubeconfig and open the tunnel (k3s installed)
#
# On the VPS (as root through sudo; idempotent, re-runnable):
#   - host firewall (ufw): SSH only from ADMIN_CIDRS, HTTP and HTTPS from anywhere, the pod/service networks inside;
#     everything else in is dropped (the OVHcloud Network Firewall from Terraform does the same in front of the box)
#   - sshd: keys only (no passwords, no root login)
#   - swap off; k3s from its install script on channel K3S_CHANNEL (Traefik disabled: Envoy Gateway is the edge, as on
#     Google Cloud; k3s's ServiceLB serves its LoadBalancer on the VPS's ports 80/443; local-path volumes on the NVMe;
#     secrets encrypted at rest; the API on 127.0.0.1:6443 only reachable through SSH)
#   - Helm HELM_VERSION in /usr/local/bin (handy on the box; this script uses your local helm)
# On your machine: the kubeconfig in KUBECONFIG_OUT (server https://127.0.0.1:LOCAL_PORT through the SSH tunnel),
# the tunnel itself, then scripts/ovh/deploy.sh ovh-vps.
#
# Environment: ADMIN_CIDRS (space-separated; default: terraform var admin_cidrs via output vps.admin_cidrs),
# K3S_CHANNEL [v1.33], HELM_VERSION [v3.18.4], LOCAL_PORT [16443], KUBECONFIG_OUT [~/.kube/northline-ovh-vps],
# REGISTRY + IMAGE_TAG (deploy right after; without them the script stops after k3s and prints the next step),
# SSH_STRICT [accept-new] (StrictHostKeyChecking; the pipeline sets yes with a known_hosts from its secrets),
# SSH_FROM_ANYWHERE [0] (1: ufw lets SSH in from any address, rate-limited, and leaves who may connect to the OVHcloud
# Network Firewall in front — the pipeline's runners have a new address every run, which the firewall opens for that
# run only; see docs/runbooks/ovh.md § Today).
set -euo pipefail
. "$(dirname "$0")/common.sh"
ovh_target ovh-vps

mode=install
case "${1:-}" in (tunnel | kubeconfig) mode=$1; shift ;; esac
host=${1:-}
need ssh terraform jq
[ -n "$host" ] || host=$(tf_out vps .ssh)
[ -n "$host" ] && [ "$host" != null ] || die "no VPS host: pass user@host, or set vps_service_name in $OVH_TF/terraform.tfvars and terraform apply"
LOCAL_PORT=${LOCAL_PORT:-16443}
KUBECONFIG_OUT=${KUBECONFIG_OUT:-$HOME/.kube/northline-ovh-vps}
ssh_opts="-o StrictHostKeyChecking=${SSH_STRICT:-accept-new} -o ServerAliveInterval=30 -o BatchMode=yes"
mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"

tunnel() {
  if ssh -O check -S "$HOME/.ssh/northline-ovh-vps.sock" "$host" 2>/dev/null; then echo "tunnel already open (127.0.0.1:$LOCAL_PORT)"; return; fi
  # shellcheck disable=SC2086
  ssh $ssh_opts -fN -M -S "$HOME/.ssh/northline-ovh-vps.sock" -o ExitOnForwardFailure=yes -L "$LOCAL_PORT:127.0.0.1:6443" "$host"
  echo "tunnel open: 127.0.0.1:$LOCAL_PORT → k3s API (close: ssh -O exit -S ~/.ssh/northline-ovh-vps.sock $host)"
}

fetch_kubeconfig() {
  echo "== kubeconfig → $KUBECONFIG_OUT"
  mkdir -p "$(dirname "$KUBECONFIG_OUT")"
  (
    umask 077
    # shellcheck disable=SC2086
    ssh $ssh_opts "$host" sudo cat /etc/rancher/k3s/k3s.yaml \
      | sed -e "s#https://127.0.0.1:6443#https://127.0.0.1:$LOCAL_PORT#" -e 's/: default$/: northline-ovh-vps/' >"$KUBECONFIG_OUT"
  )
  [ -s "$KUBECONFIG_OUT" ] || die "no kubeconfig from $host (is k3s installed? make ovh-bootstrap)"
}

if [ "$mode" = tunnel ]; then tunnel; exit 0; fi
if [ "$mode" = kubeconfig ]; then fetch_kubeconfig; tunnel; exit 0; fi

ADMIN_CIDRS=${ADMIN_CIDRS:-$(tf_out vps '.admin_cidrs | join(" ")')}
[ -n "$ADMIN_CIDRS" ] || die "ADMIN_CIDRS is empty: SSH would be closed to everyone (set admin_cidrs in terraform.tfvars, or ADMIN_CIDRS='203.0.113.7/32')"

echo "== $host: firewall, k3s ${K3S_CHANNEL:-v1.33}, helm ${HELM_VERSION:-v3.18.4}"
# shellcheck disable=SC2086
ssh $ssh_opts "$host" "sudo bash -s -- '${K3S_CHANNEL:-v1.33}' '${HELM_VERSION:-v3.18.4}' '$ADMIN_CIDRS' '${SSH_FROM_ANYWHERE:-0}'" <<'REMOTE'
set -euo pipefail
channel=$1 helm_version=$2 admin_cidrs=$3 ssh_anywhere=$4
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq && apt-get install -y -qq curl ufw >/dev/null

# Host firewall. "routed" stays allowed: k3s forwards 80/443 (ServiceLB) and pod traffic through the FORWARD chain;
# what reaches the box from outside is decided by the rules below and by the OVHcloud Network Firewall in front.
ufw --force reset >/dev/null
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw default allow routed >/dev/null
for c in $admin_cidrs; do ufw allow proto tcp from "$c" to any port 22 >/dev/null; done
if [ "$ssh_anywhere" = 1 ]; then ufw limit 22/tcp >/dev/null; fi   # the Network Firewall decides who (pipeline runs)
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw allow from 10.42.0.0/16 >/dev/null   # k3s pods
ufw allow from 10.43.0.0/16 >/dev/null   # k3s services
ufw --force enable >/dev/null
if [ "$ssh_anywhere" = 1 ]; then echo "ufw: SSH from anywhere (rate-limited; the Network Firewall filters), 80, 443"
else echo "ufw: SSH from $admin_cidrs, 80, 443"; fi

# Keys only: the box is reached with the key it was ordered with (or the pipeline's deploy key).
cat >/etc/ssh/sshd_config.d/00-northline.conf <<'SSHD'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
SSHD
sshd -t && { systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true; }

swapoff -a
sed -i '/\sswap\s/s/^[^#]/#&/' /etc/fstab

if ! command -v k3s >/dev/null 2>&1; then
  curl -sfL https://get.k3s.io | INSTALL_K3S_CHANNEL="$channel" sh -s - server \
    --disable traefik --write-kubeconfig-mode 600 --secrets-encryption --node-name northline >/dev/null
fi
for i in $(seq 1 60); do
  if k3s kubectl get nodes 2>/dev/null | grep -q ' Ready'; then break; fi
  [ "$i" -lt 60 ] || { echo "k3s node not Ready after 5 minutes: journalctl -u k3s" >&2; exit 1; }
  sleep 5
done
echo "k3s: $(k3s --version | head -1)"

if ! command -v helm >/dev/null 2>&1; then
  tmp=$(mktemp -d)
  curl -fsSL "https://get.helm.sh/helm-$helm_version-linux-amd64.tar.gz" | tar -xz -C "$tmp"
  install -m 755 "$tmp/linux-amd64/helm" /usr/local/bin/helm
  rm -rf "$tmp"
fi
echo "helm: $(helm version --short)"
REMOTE

fetch_kubeconfig
tunnel
export KUBECONFIG="$KUBECONFIG_OUT"
kubectl get nodes -o wide

if [ -n "${REGISTRY:-}" ] && [ -n "${IMAGE_TAG:-}" ]; then
  "$(dirname "$0")/deploy.sh" ovh-vps
else
  echo
  echo "k3s is up. Next (docs/runbooks/ovh.md): export KUBECONFIG=$KUBECONFIG_OUT"
  echo "  make ovh-secrets TARGET=ovh-vps     # after filling .ovh/ovh-vps.env"
  echo "  make deploy TARGET=ovh-vps REGISTRY=… IMAGE_TAG=…"
fi
