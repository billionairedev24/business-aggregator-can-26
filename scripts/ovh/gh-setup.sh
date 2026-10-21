#!/usr/bin/env bash
# OVHcloud pipeline set-up, run ONCE per target on the owner's Mac (docs/runbooks/ovh.md § Today): creates the GitHub
# Environment ovh-vps or ovh-mks (required reviewer: you; deployments only from develop and main) and sets every
# secret and variable .github/workflows/ovh.yml reads, from .ovh/<target>.env when it holds them, else by asking.
# Re-run it to change a value (it shows which keys are set, never their values).
#
#   scripts/ovh/gh-setup.sh ovh-vps|ovh-mks          (make ovh-gh-setup TARGET=…)
#
# Needs the GitHub CLI (brew install gh; gh auth login — an account with admin rights on the repository), jq (macOS 15
# has it; else brew install jq), and for ovh-vps ssh, ssh-keygen and ssh-keyscan (macOS has them). bash 3.2 compatible.
#
# Nothing else in the console (S-161): with the API values it finds or creates the Public Cloud user terraform-state
# (role ObjectStore operator) and its S3 key through the OVHcloud API (scripts/ovh/api.sh) and stores them as
# TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY — never printed; a re-run reuses the same user and key, and a state bucket
# northline-tfstate-* already in the project is adopted. The pipeline creates the bucket on its first run. ovh-vps:
# the server is a Public Cloud instance the pipeline makes (OVH_SERVER=instance, the default); this script makes its SSH
# key pair (private half: secret OVH_VPS_SSH_PRIVATE_KEY, public half: variable OVH_SSH_PUBLIC_KEY). Without a project
# id it adopts the project named northline-dryrun or orders one (scripts/ovh/project.sh; optional).
#
# It asks first for the location (OVH_LOCATION, S-160: us-east-va — the default —, us-west-or or ca-bhs) and shows the
# token page of that location's OVHcloud entity (US and Canada accounts are separate).
#
# Environment: GH_REPO [the repository of this checkout], OVH_GH_BRANCHES ["develop main"] (branches allowed to deploy),
# OVH_GH_REVIEWER [you] (GitHub login of the required reviewer), ASK_OPTIONAL=0 skips the optional questions.
#
# Values in .ovh/<target>.env (KEY=value, as scripts/ovh/secrets.sh writes it) are used as they are: the generated keys
# of `make ovh-secrets-init` (TOTP_KEY, KMS_LOCAL_KEY, …) become secrets too, so the pipeline and a Mac deploy share
# them. Without them the pipeline generates them once in the cluster (secret/northline-generated).
set -euo pipefail
. "$(dirname "$0")/common.sh"
. "$(dirname "$0")/api.sh"
ovh_target "${1:-}"
need gh
T=$OVH_TARGET
REPO=${GH_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}
[ -n "$REPO" ] || die "no repository: run inside the checkout or set GH_REPO=owner/repo"
gh auth status >/dev/null 2>&1 || die "gh is not signed in: gh auth login"
echo "== $REPO, environment $T"

# ---- the catalogue: name|secret or var|req, opt or gen|both, vps or mks|default|what it is ---------------------------
CATALOGUE='OVH_SERVICE_NAME|var|req|both||Public Cloud project id (32 hex characters, top left of the project page; empty = find or order one named northline-dryrun)
TF_STATE_BUCKET|var|req|both||Object Storage bucket for the Terraform state (the pipeline creates it in the location; just a name)
TF_STATE_ACCESS_KEY|secret|auto|both||S3 access key of the state user (made through the API, step 4a; or from .ovh/<target>.env)
TF_STATE_SECRET_KEY|secret|auto|both||S3 secret key of that user
OVH_DOMAIN|var|req|both|demo.northline.ca|DNS zone of the public hosts (you create its A records)
OVH_SERVER|var|req|vps|instance|the server: instance (a Public Cloud instance the pipeline makes, hourly — nothing to order) or vps (a VPS you order in the console, cheaper by the month)
OVH_INSTANCE_FLAVOR|var|opt|vps|b3-32|Public Cloud flavor of the instance (b3-32: 8 vCPU / 32 GB)
OVH_VPS_SERVICE_NAME|var|req|vps||the VPS service name (its page under Bare Metal Cloud › VPS, e.g. vps-xxxxxxxx.vps.ovh.us; .vps.ovh.ca in Canada)
OVH_ADMIN_CIDRS|var|req|vps||who may SSH to the VPS besides the pipeline (your IP/32; up to 3, space-separated)
OVH_EDGE_FIREWALL|var|opt|vps|true|OVHcloud Network Firewall in front of the VPS (true or false)
OVH_API_ALLOWED_CIDRS|var|opt|mks||who may reach the Kubernetes API (your IP/32; empty = anyone with the kubeconfig)
OVH_NODE_COUNT|var|opt|mks|2|nodes in the pool
OVH_NODE_FLAVOR|var|opt|mks|b3-16|node flavor
OVH_BUCKET_SUFFIX|var|opt|both||suffix for the bucket names if northline-<target>-uploads is taken (e.g. -a1b2)
OVH_TFVARS|var|opt|both||extra Terraform variables as HCL lines (e.g. data_services = { postgres = { mode = "in-cluster" } })
REGISTRY_PULL_USERNAME|var|opt|both||GitHub login that owns the pull token (empty = the run'"'"'s actor)
REGISTRY_PULL_TOKEN|secret|opt|both||classic personal access token with read:packages only (the cluster pulls ghcr.io with it; empty = the run'"'"'s token, which expires after the run)
STRIPE_SECRET_KEY|secret|opt|both||Stripe TEST secret key sk_test_… (empty = fake payment gateway)
STRIPE_PUBLISHABLE_KEY|secret|opt|both||Stripe TEST publishable key pk_test_…
STRIPE_WEBHOOK_SECRET|secret|opt|both||whsec_… of the platform webhook (after the webhooks step)
STRIPE_CONNECT_WEBHOOK_SECRET|secret|opt|both||whsec_… of the Connect webhook
SMS_AUTH_TOKEN|secret|opt|both||Twilio auth token (needed for anyone to sign up under dev)
SMS_ACCOUNT_ID|var|opt|both||Twilio account SID AC…
SMS_FROM|var|opt|both||Twilio sender: +1… number or a Messaging Service MG…
RESEND_API_KEY|secret|opt|both||Resend API key re_… with sending access: every email over HTTPS, sign-up codes included (OVHcloud filters outbound SMTP; wins over SMTP_HOST — docs/runbooks/ovh.md § Email)
SMTP_HOST|var|opt|both||SMTP relay for every email, sign-up codes included (e.g. smtp-relay.brevo.com, smtp.gmail.com, ssl0.ovh.net — docs/runbooks/ovh.md § Email)
SMTP_PORT|var|opt|both|587|SMTP port (587 with STARTTLS; 465 is not supported)
SMTP_USERNAME|var|opt|both||SMTP login
SMTP_PASSWORD|secret|opt|both||SMTP password, SMTP key or app password
SMTP_STARTTLS|var|opt|both|true|STARTTLS (true whenever there is a login)
EMAIL_FROM|var|opt|both||sender on the domain verified at Resend, or one the relay accepts, e.g. Northline demo <you@example.com> (no commas)
OVH_DEMO_MAILPIT|var|opt|both|false|true = the demo inbox at mail.<domain> instead of delivering email (private demos)
EMAIL_API_KEY|secret|opt|both||SendGrid API key (empty = emails are logged)
KAFKA_SASL_JAAS_CONFIG|secret|opt|both||external Kafka with SASL only: the whole JAAS line
TOTP_KEY|secret|gen|both||
WEBHOOK_SECRET_KEY|secret|gen|both||
EMAIL_UNSUBSCRIBE_KEY|secret|gen|both||
KMS_LOCAL_KEY|secret|gen|both||
STUDIO_BFF_SECRET|secret|gen|both||
STUDIO_BFF_SECRET_HASH|secret|gen|both||
CONSUMER_BFF_SECRET|secret|gen|both||
CONSUMER_BFF_SECRET_HASH|secret|gen|both||
CONSOLE_BFF_SECRET|secret|gen|both||
CONSOLE_BFF_SECRET_HASH|secret|gen|both||
DB_PASSWORD|secret|gen|both||
REDIS_PASSWORD|secret|gen|both||
MEILISEARCH_API_KEY|secret|gen|both||
MAILPIT_PASSWORD|secret|gen|both||'

# ---- helpers ---------------------------------------------------------------------------------------------------------
ask() {          # ask <prompt> <default> [secret] → the answer on stdout (from the terminal, not the catalogue)
  local a
  if [ "${3:-}" = secret ]; then read -r -s -p "$1: " a </dev/tty; echo >/dev/tty
  else read -r -p "$1${2:+ [$2]}: " a </dev/tty
  fi
  printf '%s' "${a:-$2}"
}
yes_no() { local a; read -r -p "$1 [y/N]: " a </dev/tty; case "$a" in (y | Y | yes) return 0 ;; esac; return 1; }
from_file() {    # a key of .ovh/<target>.env, with the older registry names mapped
  local v
  v=$(env_get "$1")
  if [ -z "$v" ]; then
    case "$1" in
      (REGISTRY_PULL_TOKEN) v=$(env_get REGISTRY_PASSWORD) ;;
      (REGISTRY_PULL_USERNAME) v=$(env_get REGISTRY_USERNAME) ;;
    esac
  fi
  printf '%s' "$v"
}
existing_secrets=$(gh api "repos/$REPO/environments/$T/secrets" --paginate -q '.secrets[].name' 2>/dev/null || true)
existing_vars=$(gh api "repos/$REPO/environments/$T/variables" --paginate -q '.variables[].name' 2>/dev/null || true)
is_set() { printf '%s\n' "$existing_secrets" "$existing_vars" | grep -qx "$1"; }
put() {          # put <secret|var> <name> <value>
  if [ "$1" = secret ]; then printf '%s' "$3" | gh secret set "$2" --env "$T" --repo "$REPO" >/dev/null
  else gh variable set "$2" --env "$T" --repo "$REPO" --body "$3" >/dev/null
  fi
  echo "   set $1 $2"
}

# ---- 1. the environment ----------------------------------------------------------------------------------------------
reviewer=${OVH_GH_REVIEWER:-$(gh api user -q .login)}
reviewer_id=$(gh api "users/$reviewer" -q .id)
body=$(printf '{"reviewers":[{"type":"User","id":%s}],"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' "$reviewer_id")
if printf '%s' "$body" | gh api -X PUT "repos/$REPO/environments/$T" --input - >/dev/null 2>&1; then
  echo "environment $T: required reviewer $reviewer"
else
  # Required reviewers on a private repository need a paid GitHub plan: the environment without them.
  printf '%s' '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' \
    | gh api -X PUT "repos/$REPO/environments/$T" --input - >/dev/null \
    || die "GitHub refused to create the environment $T (admin rights? Environments in a private repository need a paid plan — else use the local alternative, docs/runbooks/ovh.md § Today)"
  echo "warning: GitHub refused a required reviewer on $T (plan?) — anyone with write access can run apply; destroy still needs the typed confirmation" >&2
fi
for b in ${OVH_GH_BRANCHES:-develop main}; do
  gh api -X POST "repos/$REPO/environments/$T/deployment-branch-policies" -f name="$b" -f type=branch >/dev/null 2>&1 || true
done
echo "environment $T: deployments from ${OVH_GH_BRANCHES:-develop main}"

# ---- 2. where: the location (S-160) — it decides the API endpoint, the regions and the token page --------------------
echo "== location"
loc=$(from_file OVH_LOCATION)
if [ -z "$loc" ]; then
  current=$(gh variable get OVH_LOCATION --env "$T" --repo "$REPO" 2>/dev/null || true)
  cat >/dev/tty <<'TXT'
   Where Northline runs (OVH_LOCATION):
     us-east-va  Vint Hill, Virginia   — OVHcloud US (us.ovhcloud.com), the default for now
     us-west-or  Hillsboro, Oregon     — OVHcloud US
     ca-bhs      Beauharnois, Québec   — OVHcloud Canada (ca.ovhcloud.com)
   OVHcloud US and OVHcloud Canada are separate companies: separate accounts, logins, API endpoints and tokens. The
   API token and the project below must come from the account of the location you pick.
   A US location holds fake demo data only (dev profile): real Canadian customer data must be on ca-bhs before launch.
TXT
  loc=$(ask "OVH_LOCATION" "${current:-us-east-va}")
  if [ -n "$current" ] && [ "$loc" != "$current" ]; then
    echo "   moving $T from $current to $loc: destroy the $current deployment first (its state is in the old bucket) and give the new account's values below — docs/runbooks/ovh.md § Canada later" >&2
    yes_no "Continue with $loc?" || die "stopped; nothing changed for the location"
  fi
fi
ovh_location "$loc"
put var OVH_LOCATION "$OVH_LOCATION"
echo "   $OVH_LOCATION: $(loc_get label) — account at $(loc_get site), API $(loc_get api_endpoint)"
if is_set OVH_ENDPOINT; then   # the S-155 setting: the location decides the endpoint now
  gh variable delete OVH_ENDPOINT --env "$T" --repo "$REPO" >/dev/null && echo "   removed the old variable OVH_ENDPOINT (the location decides it)"
fi
if [ "$(loc_get country)" != CA ]; then
  echo "   note: outside Canada — demo data only; the deploy refuses staging/prod values there (docs/runbooks/ovh.md § Data residency)"
fi

# ---- 3. OVHcloud API credentials (one of the two kinds) -------------------------------------------------------------
echo "== OVHcloud API credentials ($(loc_get site) account)"
# The rights of the default design (a given project, a Public Cloud instance): /cloud/* only. Extras, appended to the
# address, for the options: a VPS ordered in the console (OVH_SERVER=vps) and ordering the project here.
token_url="$(loc_get token_url)?GET=/cloud/*&POST=/cloud/*&PUT=/cloud/*&DELETE=/cloud/*"
token_vps='&GET=/vps/*&GET=/ip/*&POST=/ip/*&PUT=/ip/*&DELETE=/ip/*'
token_order='&GET=/me&GET=/me/*&POST=/me/order/*&GET=/order/*&POST=/order/*&DELETE=/order/*'
ak=$(from_file OVH_APPLICATION_KEY); cid=$(from_file OVH_CLIENT_ID)
if [ -n "$ak" ]; then kind=a
elif [ -n "$cid" ]; then kind=s
elif is_set OVH_APPLICATION_KEY || is_set OVH_CLIENT_ID; then kind=keep
else
  echo "   token page (signed in to $(loc_get site), rights pre-filled): $token_url"
  echo "   append $token_vps for OVH_SERVER=vps, and $token_order if you have no project yet"
  kind=$(ask "Application key set from that page (a) or service account (s)" a)
fi
case "$kind" in
  (a)
    for k in OVH_APPLICATION_KEY OVH_APPLICATION_SECRET OVH_CONSUMER_KEY; do
      v=$(from_file "$k"); [ -n "$v" ] || v=$(ask "$k" "" secret)
      [ -n "$v" ] || die "$k is required"; put secret "$k" "$v"; export "$k=$v"   # 4a calls the API with them
    done ;;
  (s)
    for k in OVH_CLIENT_ID OVH_CLIENT_SECRET; do
      v=$(from_file "$k"); [ -n "$v" ] || v=$(ask "$k" "" secret)
      [ -n "$v" ] || die "$k is required"; put secret "$k" "$v"; export "$k=$v"
    done ;;
  (keep) echo "   OVHcloud credentials already set (re-run with them in .ovh/$T.env to replace)" ;;
  (*) die "a or s" ;;
esac

# ---- 4. the catalogue ------------------------------------------------------------------------------------------------
echo "== variables and secrets"
my_ip=$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true)
pid=$(from_file OVH_SERVICE_NAME)
server=instance
while IFS='|' read -r name kind need scope default help <&3; do
  case "$scope" in (both) ;; (vps) [ "$T" = ovh-vps ] || continue ;; (mks) [ "$T" = ovh-mks ] || continue ;; esac
  [ "$need" != auto ] || continue   # the state user's keys: 4a
  case "$name" in
    (OVH_VPS_SERVICE_NAME | OVH_EDGE_FIREWALL) [ "$server" = vps ] || continue ;;
    (OVH_INSTANCE_FLAVOR) [ "$server" = instance ] || continue ;;
  esac
  v=$(from_file "$name")
  if [ -z "$v" ]; then
    case "$need" in
      (gen) continue ;;   # generated in the cluster by the pipeline unless the file has them
      (opt) if is_set "$name" || [ "${ASK_OPTIONAL:-1}" = 0 ]; then continue; fi ;;
      (req)
        if is_set "$name"; then
          echo "   keep $name"
          [ "$name" != OVH_SERVER ] || server=$(gh variable get OVH_SERVER --env "$T" --repo "$REPO" 2>/dev/null || echo instance)
          continue
        fi ;;
    esac
    case "$name" in
      (OVH_ADMIN_CIDRS) default=${my_ip:+$my_ip/32} ;;
      (TF_STATE_BUCKET)
        # A state bucket already in the project (one made by hand) is adopted.
        default=''
        if ovh_api_has_creds && [ -n "$pid" ]; then ovh_api_init; default=$(OVH_SERVICE_NAME=$pid state_bucket_find); fi
        [ -n "$default" ] || default=northline-tfstate-$(printf '%s' "$pid" | cut -c1-8) ;;
    esac
    if [ "$need" = opt ] && [ -z "$default" ]; then
      yes_no "Set $name now? ($help)" || continue
      v=$(ask "$name" "" "$kind")
    else
      v=$(ask "$name — $help" "$default" "$kind")
    fi
    if [ -z "$v" ] && [ "$name" = OVH_SERVICE_NAME ]; then
      ovh_api_has_creds || die "OVH_SERVICE_NAME is required (no API values this run to find or order a project)"
      yes_no "No project id: use the project named northline-dryrun, or order one (free; the token needs the order rights)?" \
        || die "OVH_SERVICE_NAME is required"
      v=$("$OVH_REPO/scripts/ovh/project.sh" ensure) || die "no project: give its id (top left of the project page)"
    fi
    if [ -z "$v" ]; then [ "$need" = req ] && die "$name is required"; continue; fi
  fi
  case "$name" in
    (OVH_SERVER) case "$v" in (instance | vps) server=$v ;; (*) die "OVH_SERVER must be instance or vps" ;; esac ;;
  esac
  [ "$name" != OVH_SERVICE_NAME ] || pid=$v
  case "$name" in
    (STRIPE_SECRET_KEY) case "$v" in (sk_test_*) ;; (*) die "STRIPE_SECRET_KEY must be a TEST key (sk_test_…)" ;; esac ;;
  esac
  put "$kind" "$name" "$v"
done 3<<EOF
$CATALOGUE
EOF

# ---- 4a. the Terraform state's S3 user and keys: through the API, no console step (S-161) ---------------------------
echo "== Terraform state user ($OVH_STATE_USER)"
sak=$(from_file TF_STATE_ACCESS_KEY); ssk=$(from_file TF_STATE_SECRET_KEY)
if [ -n "$sak" ] && [ -n "$ssk" ]; then
  # The manual fallback (a user made in the console, docs/runbooks/ovh.md § Today, Manual fallback): its keys from the file.
  put secret TF_STATE_ACCESS_KEY "$sak"; put secret TF_STATE_SECRET_KEY "$ssk"
elif ovh_api_has_creds; then
  [ -n "$pid" ] || pid=$(gh variable get OVH_SERVICE_NAME --env "$T" --repo "$REPO" 2>/dev/null || true)
  [ -n "$pid" ] || die "OVH_SERVICE_NAME is needed to find or create the state user"
  ovh_api_init
  OVH_SERVICE_NAME=$pid state_keys
  put secret TF_STATE_ACCESS_KEY "$STATE_ACCESS_KEY"; put secret TF_STATE_SECRET_KEY "$STATE_SECRET_KEY"
  unset STATE_SECRET_KEY
elif is_set TF_STATE_ACCESS_KEY; then
  echo "   keep TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY (the API values were not given this time)"
else
  echo "   the API values were kept from an earlier run, so they are not here: the pipeline finds or creates the state user on its first run (or put the API values in .ovh/$T.env and re-run)"
fi

# ---- 4b. the demo inbox's password: made here so you know it (user demo) ----------------------------------------
mp=$(from_file OVH_DEMO_MAILPIT)
[ -n "$mp" ] || mp=$(gh variable get OVH_DEMO_MAILPIT --env "$T" --repo "$REPO" 2>/dev/null || true)
if [ "$mp" = true ]; then
  pw=$(from_file MAILPIT_PASSWORD)
  if [ -n "$pw" ]; then put secret MAILPIT_PASSWORD "$pw"
  elif ! is_set MAILPIT_PASSWORD; then
    need openssl
    pw=$(openssl rand -hex 12)
    put secret MAILPIT_PASSWORD "$pw"
    echo "   the demo inbox https://mail.<your domain>: user demo, password $pw (shown once — keep it)"
  fi
fi

# ---- 5. ovh-vps: the pipeline's own SSH key — for the instance Terraform makes, or installed on your VPS ------------
if [ "$T" = ovh-vps ]; then
  echo "== SSH for the pipeline"
  need ssh ssh-keygen ssh-keyscan
  keyfile="$OVH_REPO/.ovh/ovh-vps-deploy-key"
  if [ ! -f "$keyfile" ]; then
    mkdir -p "$(dirname "$keyfile")"
    ssh-keygen -q -t ed25519 -N '' -C "northline-ovh-pipeline" -f "$keyfile"
    echo "   new deploy key $keyfile (+ .pub)"
  fi
fi
if [ "$T" = ovh-vps ] && [ "$server" = instance ]; then
  # The pipeline's Terraform registers the public half with the instance it makes; the host key is learnt on the
  # first connection (the instance doesn't exist yet). Your own access: ssh -i .ovh/ovh-vps-deploy-key ubuntu@<IPv4>.
  put secret OVH_VPS_SSH_PRIVATE_KEY "$(cat "$keyfile")"
  put var OVH_SSH_PUBLIC_KEY "$(cat "$keyfile.pub")"
  if is_set OVH_VPS_KNOWN_HOSTS; then   # a VPS's host key from before would refuse the instance
    gh secret delete OVH_VPS_KNOWN_HOSTS --env "$T" --repo "$REPO" >/dev/null && echo "   removed OVH_VPS_KNOWN_HOSTS (the VPS's host key)"
  fi
elif [ "$T" = ovh-vps ]; then
  ip=$(ask "The VPS's IPv4 (its page in the console; ordered in $(loc_get vps_datacenter))" "")
  [ -n "$ip" ] || die "the VPS's IPv4 is needed for its host key"
  if yes_no "Install the deploy key on ubuntu@$ip now (uses your own SSH access)?"; then
    ssh -o StrictHostKeyChecking=accept-new "ubuntu@$ip" \
      "umask 077; mkdir -p ~/.ssh; grep -qxF '$(cat "$keyfile.pub")' ~/.ssh/authorized_keys 2>/dev/null || echo '$(cat "$keyfile.pub")' >> ~/.ssh/authorized_keys"
    echo "   deploy key installed"
  else
    echo "   later: ssh ubuntu@$ip and append $(basename "$keyfile").pub to ~/.ssh/authorized_keys"
  fi
  kh=$(ssh-keyscan -t ed25519 "$ip" 2>/dev/null)
  [ -n "$kh" ] || die "ssh-keyscan $ip returned nothing (is the VPS up?)"
  echo "   host key: $(printf '%s\n' "$kh" | ssh-keygen -lf - | awk '{ print $2 }') — compare with the console's (VPS › KVM: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub)"
  put secret OVH_VPS_SSH_PRIVATE_KEY "$(cat "$keyfile")"
  put secret OVH_VPS_KNOWN_HOSTS "$kh"
fi

# ---- 6. a private registry instead of ghcr.io (repository-level: the image build job has no environment) ------------
reg=$(from_file REGISTRY)
if [ -n "$reg" ]; then
  gh variable set REGISTRY --repo "$REPO" --body "$reg" >/dev/null && echo "   set repository variable REGISTRY"
  echo "   the image build pushes there with the repository secrets REGISTRY_USERNAME / REGISTRY_PASSWORD (gh secret set …)"
fi

echo
echo "Done ($T in $OVH_LOCATION). Next: Actions › ovh › Run workflow — target $T, action plan, then provision-and-deploy (docs/runbooks/ovh.md § Today)."
