#!/usr/bin/env bash
# Demo marketplace (docs/runbooks/demo.md): ~11 FAKE businesses across categories — a bakery, a grocer, a coffee
# roaster, a wine shop (age-restricted), cleaners, a barber, a caterer, tutors, music lessons, a handyman, a
# photographer/DJ — each onboarded and approved through the staff flow, with their public page, listings and pictures;
# three customers with orders, bookings and reviews. Everything goes THROUGH THE PRODUCT (northline-auth sign-up and
# sign-in, the Studio's, consumer site's and console's BFFs) — never the dev seed, whose credentials are public.
#
#   make demo-data ENV=local             against the stack on this machine (make up-all, or ci/e2e.sh up)
#   make demo-data ENV=dryrun            against the GCP dry run (docs/runbooks/dryrun-gcp.md; also ENV=gcp-dryrun)
#   make demo-data ENV=ovh-vps|ovh-mks   against an OVHcloud target (docs/runbooks/ovh.md) — its own state file
#   make demo-data ENV=… RESET=1         after a demo: hide what the live demo added (listings outside the plan, the
#                                        applicant's business's listings) and put the plan's prices and stock back
#   make demo-data ENV=… RESET=all       hide every demo listing (the marketplace looks empty); a plain run restores it
#   make demo-data ENV=… ONLY=bakery,wine   only these businesses (keys in scripts/demo/data/marketplace.json)
#
# Re-runs are idempotent: it reuses what exists (the state file's ids, the listings' DEMO- SKUs, business names) and
# makes the orders/bookings/reviews once. A step only a person can do on a deployed environment (Stripe's hosted test
# pages) is listed at the end — do it, run again (exit code 3 until nothing is left).
#
# Credentials (S-159, on S-154's accounts): every demo account is an email + a random password + an authenticator app.
# The password is made here, the authenticator key (and backup codes) by northline-auth at sign-up; all are written ONLY
# to the state file (mode 600, git-ignored), which the operator keeps: DEMO_STATE [<repo>/.demo/<env>.json]. Nothing
# secret is printed; with CI=true not even the identifiers. The emailed code is read from Mailpit — no Twilio needed.
#
# Environment (defaults in brackets):
#   DEMO_MARKET [the region model's configured market: GET /api/v1/geo/markets → fallback]  a market id or city
#   DEMO_STUDIO_URL / DEMO_CONSUMER_URL / DEMO_CONSOLE_URL / DEMO_AUTH_URL  [local: localhost:3100/3000/3200/9000;
#       dryrun: the urls of deploy/helm/northline/values-dryrun.yaml; ovh-*: values-ovh-*.yaml's urls if it sets
#       them, else values-dryrun.yaml's]
#   DEMO_STAFF_IDENTIFIER + DEMO_STAFF_PASSWORD + DEMO_STAFF_TOTP_SECRET (or DEMO_STAFF_BACKUP_CODES=a,b,…)  the console
#       admin that approves (the approver; the script registers the second console account, the reviewer, and invites
#       it as trust & safety) [local: the dev seed's reviewer from web/e2e/data/local.json — local only; deployed:
#       required, the first admin; DEMO_STAFF_PASSWORD when that account has a password (S-154)]
#   DEMO_MAILPIT_URL [local: http://localhost:8025 when it answers; ovh-*: https://mail.<OVH_DOMAIN> when the target has
#       OVH_DEMO_MAILPIT=true]  the inbox the new accounts' codes are read from (Mailpit's API)
#   DEMO_MAILPIT_PASSWORD [ovh-*: MAILPIT_PASSWORD from the environment or .ovh/<target>.env]  its basic auth (user demo)
#   DEMO_EMAIL_CODE_CMD  without an inbox: prints the code emailed to $1 (else it is asked for in the terminal)
#   DEMO_EMAIL_<KEY>  another email for an account (the plan's are @demo.northline.test) — the inbox must receive it
#   DEMO_PHONE_<KEY>  optional: a mobile for an account (OWNER, APPLICANT, …), stored unverified (no SMS is sent)
#   DEMO_STRIPE_PUBLISHABLE_KEY  deployed: pk_test_… — the customers' past orders are paid with Stripe's test card
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
env_name=local reset= reset_all= only=
while [ $# -gt 0 ]; do
  case "$1" in
    --env) env_name="${2:?--env needs local, dryrun, ovh-vps or ovh-mks}"; shift 2 ;;
    --reset) reset=1; shift ;;
    --reset-all) reset=1; reset_all=1; shift ;;
    --only) only="${2:?--only needs merchant keys}"; shift 2 ;;
    -h | --help) awk 'NR > 1 && /^set -euo/ { exit } NR > 1' "$0"; exit 0 ;;
    *) echo "unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
done

command -v node >/dev/null || { echo "Node 22 is needed (node not found)" >&2; exit 1; }

# A URL from a values file's `urls:` block (two-space keys under it): the target's overlay first (OVHcloud), then
# values-dryrun.yaml — the hosts every dry-run target shares unless its overlay changes them.
values_url() {
  local f url=
  for f in ${overlay:+"$repo/deploy/helm/northline/$overlay"} "$repo/deploy/helm/northline/values-dryrun.yaml"; do
    url=$(awk -v key="$1" '/^urls:/ { on = 1; next } on && /^[^ #]/ { on = 0 } on && $1 == key ":" { print $2; exit }' "$f")
    if [ -n "$url" ]; then echo "$url"; return; fi
  done
}

case "$env_name" in
  local)
    : "${DEMO_STUDIO_URL:=http://localhost:3100}" "${DEMO_CONSUMER_URL:=http://localhost:3000}"
    : "${DEMO_CONSOLE_URL:=http://localhost:3200}" "${DEMO_AUTH_URL:=http://localhost:9000}"
    if [ -z "${DEMO_STAFF_IDENTIFIER:-}" ]; then
      # the dev seed's console reviewer (local databases only — DevSeedGuard keeps the seed out of every other one), with
      # her seeded authenticator key (S-150, scripts/dev/personas.json) so the demo run spends no backup codes
      eval "$(node -e '
        const s = require(process.argv[1]).personas.staff;
        const key = require(process.argv[2]).personas.find(p => p.email === s.identifier)?.totpSecret ?? "";
        console.log(`DEMO_STAFF_IDENTIFIER=${JSON.stringify(s.identifier)} DEMO_STAFF_BACKUP_CODES=${JSON.stringify((s.backupCodes ?? []).join(","))} DEMO_STAFF_TOTP_SECRET=${JSON.stringify(key)} DEMO_STAFF_PASSWORD=${JSON.stringify(s.password ?? "")}`);
      ' "$repo/web/e2e/data/local.json" "$repo/scripts/dev/personas.json")"
      export DEMO_STAFF_IDENTIFIER DEMO_STAFF_BACKUP_CODES DEMO_STAFF_TOTP_SECRET DEMO_STAFF_PASSWORD
    fi
    # S-159: the compose Mailpit (--profile mail) when it answers; else auth's dev outbox (local profile) has the codes
    if [ -z "${DEMO_MAILPIT_URL:-}" ] && curl -sf -o /dev/null --max-time 3 "http://localhost:${MAILPIT_UI_PORT:-8025}/api/v1/info" 2>/dev/null; then
      DEMO_MAILPIT_URL="http://localhost:${MAILPIT_UI_PORT:-8025}"
    fi
    ;;
  gcp-dryrun | dryrun | ovh-vps | ovh-mks)
    overlay=
    case "$env_name" in
      (gcp-dryrun) env_name=dryrun ;;                       # one state file for the GCP dry run, as before
      (ovh-vps | ovh-mks) overlay="values-$env_name.yaml" ;;
    esac
    : "${DEMO_STUDIO_URL:=$(values_url studio)}" "${DEMO_CONSUMER_URL:=$(values_url consumer)}"
    : "${DEMO_CONSOLE_URL:=$(values_url console)}" "${DEMO_AUTH_URL:=$(values_url auth)}"
    [ -n "${DEMO_STAFF_IDENTIFIER:-}" ] || { echo "DEMO_STAFF_IDENTIFIER and DEMO_STAFF_TOTP_SECRET are needed: the $env_name console admin (docs/runbooks/demo.md § Before the first run)" >&2; exit 2; }
    # S-159: the OVH demo inbox (OVH_DEMO_MAILPIT=true, S-155b) — every email lands there; its basic auth is MAILPIT_PASSWORD
    if [ -n "$overlay" ] && [ -z "${DEMO_MAILPIT_URL:-}" ]; then
      ovh_env="${OVH_SECRETS_FILE:-$repo/.ovh/$env_name.env}"
      from_env_file() { [ -f "$ovh_env" ] && awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$ovh_env"; }
      if [ "${OVH_DEMO_MAILPIT:-$(from_env_file OVH_DEMO_MAILPIT)}" = true ]; then
        DEMO_MAILPIT_URL="https://mail.${OVH_DOMAIN:-$(from_env_file OVH_DOMAIN)}"
        case "$DEMO_MAILPIT_URL" in (https://mail.) DEMO_MAILPIT_URL=https://mail.demo.northline.ca ;; esac
        : "${DEMO_MAILPIT_PASSWORD:=${MAILPIT_PASSWORD:-$(from_env_file MAILPIT_PASSWORD)}}"
      fi
    fi
    case "${DEMO_STRIPE_PUBLISHABLE_KEY:-}" in
      pk_live_*) echo "DEMO_STRIPE_PUBLISHABLE_KEY is a live key: the demo pays with test cards only (pk_test_…)" >&2; exit 2 ;;
    esac
    ;;
  *) echo "ENV=$env_name: only local, dryrun (gcp-dryrun), ovh-vps or ovh-mks (demo data never goes to dev, staging or prod)" >&2; exit 2 ;;
esac

export DEMO_ENV="$env_name" DEMO_STUDIO_URL DEMO_CONSUMER_URL DEMO_CONSOLE_URL DEMO_AUTH_URL
export DEMO_MAILPIT_URL="${DEMO_MAILPIT_URL:-}" DEMO_MAILPIT_PASSWORD="${DEMO_MAILPIT_PASSWORD:-}"
export DEMO_STATE_FILE="${DEMO_STATE:-$repo/.demo/$env_name.json}"
export DEMO_RESET="$reset" DEMO_RESET_ALL="$reset_all" DEMO_MERCHANTS="$only"
for url in "$DEMO_CONSUMER_URL" "$DEMO_AUTH_URL"; do
  curl -sf -o /dev/null --max-time 10 "$url/actuator/health" 2>/dev/null \
    || curl -s -o /dev/null --max-time 10 "$url" || { echo "$url does not answer — is the stack up?" >&2; exit 1; }
done
exec node "$repo/scripts/demo/demo-data.mjs"
