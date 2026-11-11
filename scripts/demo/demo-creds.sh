#!/usr/bin/env bash
# S-194: the demo accounts' credentials (docs/runbooks/demo.md § One-click demo data) — for each account its email, app,
# password, a current authenticator (TOTP) code and the key itself (to add to an authenticator app: "enter a setup
# key", time-based, 6 digits). Printed to YOUR terminal only; never run this in CI.
#
#   make demo-creds ENV=local      .demo/local-accounts.env (made by make demo-seed ENV=local)
#   make demo-creds ENV=ovh-vps    secret/northline-demo-accounts read over SSH on the server:
#                                  ssh -i .ovh/ovh-vps-deploy-key ubuntu@<IPv4>; the IPv4 from OVH_HOST, else Terraform's
#                                  output `vps` (infra/terraform/envs/ovh/vps, after make ovh-tf-init or the like)
#   make demo-creds ENV=ovh-mks    the same Secret through kubectl's current context (KUBECONFIG from Terraform's output
#                                  kubernetes)
#
# OVH_NAMESPACE [northline-dryrun]; OVH_SSH_KEY [.ovh/ovh-vps-deploy-key]. bash 3.2 compatible.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLAN="$ROOT/server/api/src/tools/resources/demo-seed/plan.json"
SECRET=northline-demo-accounts
NS=${OVH_NAMESPACE:-northline-dryrun}

die() { echo "demo-creds: $*" >&2; exit 1; }
need() { local c; for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "$c is needed (not on PATH)"; done; }
[ -z "${CI:-}" ] || die "refusing to print credentials with CI set (they would land in a log)"
need node

# secret_json — the Secret as JSON ({data: {KEY: base64}}).
case "${1:-}" in
  local)
    file="$ROOT/.demo/local-accounts.env"
    [ -s "$file" ] || die "no $file yet: make demo-seed ENV=local"
    creds=$(cat "$file")
    ;;
  ovh-vps)
    host=${OVH_HOST:-}
    if [ -z "$host" ]; then
      need terraform jq
      host=$(terraform -chdir="$ROOT/infra/terraform/envs/ovh/vps" output -json vps 2>/dev/null | jq -r '.ipv4 // empty') \
        || true
      [ -n "$host" ] || die "set OVH_HOST=<the server's IPv4> (or initialise infra/terraform/envs/ovh/vps so terraform output vps works)"
    fi
    key=${OVH_SSH_KEY:-$ROOT/.ovh/ovh-vps-deploy-key}
    [ -f "$key" ] || die "no SSH key $key (make ovh-gh-setup TARGET=ovh-vps wrote it; OVH_SSH_KEY=… for another)"
    json=$(ssh -i "$key" -o IdentitiesOnly=yes -o BatchMode=yes "ubuntu@${host#*@}" \
      "sudo k3s kubectl -n $NS get secret $SECRET -o json") \
      || die "could not read secret/$SECRET on $host (seeded yet? the pipeline's action seed-demo)"
    creds=$(printf '%s' "$json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const d=JSON.parse(s).data||{};
      for (const [k,v] of Object.entries(d)) console.log(k+"="+Buffer.from(v,"base64").toString())})')
    ;;
  ovh-mks)
    need kubectl
    json=$(kubectl -n "$NS" get secret "$SECRET" -o json) || die "could not read secret/$SECRET (KUBECONFIG? seeded yet?)"
    creds=$(printf '%s' "$json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const d=JSON.parse(s).data||{};
      for (const [k,v] of Object.entries(d)) console.log(k+"="+Buffer.from(v,"base64").toString())})')
    ;;
  *) die "usage: $0 local|ovh-vps|ovh-mks   (make demo-creds ENV=…)" ;;
esac

printf '%s\n' "$creds" | node -e '
  const { createHmac } = require("node:crypto");
  const plan = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"));
  const lines = require("node:fs").readFileSync(0, "utf8").split("\n");
  const env = Object.fromEntries(lines.filter(l => l.includes("=")).map(l => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
  const b32 = (s) => { const a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; let bits = 0, v = 0; const out = [];
    for (const ch of s.toUpperCase().replace(/[\s=]/g, "")) { v = (v << 5) | a.indexOf(ch); bits += 5;
      if (bits >= 8) { out.push((v >>> (bits - 8)) & 255); bits -= 8; } } return Buffer.from(out); };
  const totp = (key, step) => { const c = Buffer.alloc(8); c.writeBigUInt64BE(BigInt(step));
    const mac = createHmac("sha1", b32(key)).update(c).digest(); const o = mac[mac.length - 1] & 15;
    return String((mac.readUInt32BE(o) & 0x7fffffff) % 1e6).padStart(6, "0"); };
  const step = Math.floor(Date.now() / 30000), left = 30 - Math.floor(Date.now() / 1000) % 30;
  for (const [k, a] of Object.entries(plan.accounts)) {
    const K = k.toUpperCase(), pw = env[`DEMO_ACCOUNT_${K}_PASSWORD`], key = env[`DEMO_ACCOUNT_${K}_TOTP`];
    console.log(`${k.padEnd(10)} ${a.email}  (${a.app}: ${a.role})`);
    if (!pw || !key) { console.log("           not seeded yet"); continue; }
    console.log(`           password  ${pw}`);
    console.log(`           code      ${totp(key, step)}  (valid ${left} s more; next ${totp(key, step + 1)})`);
    console.log(`           key       ${key}`);
  }' "$PLAN"
