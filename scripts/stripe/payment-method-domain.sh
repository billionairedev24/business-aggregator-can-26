#!/usr/bin/env bash
# S-157: register a consumer web domain for Apple Pay / Google Pay with Stripe's payment method domains API
# (docs/runbooks/stripe.md § 14). Run once per domain and mode (test for dev/staging, live for prod), after the
# consumer web serves /.well-known/apple-developer-merchantid-domain-association (NL_APPLE_PAY_DOMAIN_ASSOCIATION).
#
#   STRIPE_SECRET_KEY=sk_test_… scripts/stripe/payment-method-domain.sh register staging.northline.ca
#   STRIPE_SECRET_KEY=sk_test_… scripts/stripe/payment-method-domain.sh status   staging.northline.ca
#   STRIPE_SECRET_KEY=sk_test_… scripts/stripe/payment-method-domain.sh validate staging.northline.ca
#
# register: checks the association file is served over https without a redirect, creates the domain (or finds it),
#           then asks Stripe to validate it (Stripe asks Apple to fetch the file) and prints each wallet's status.
# status:   prints the domain's Apple Pay / Google Pay / Link status.
# validate: asks Stripe to check again (after fixing the file).
# STRIPE_API_BASE overrides https://api.stripe.com (stripe-mock: http://localhost:12111).
# Needs curl and python3. Never prints the key.
set -euo pipefail

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[ $# -eq 2 ] || usage
action=$1
domain=$2
api=${STRIPE_API_BASE:-https://api.stripe.com}
version=${STRIPE_API_VERSION:-2026-08-26.dahlia}
: "${STRIPE_SECRET_KEY:?set STRIPE_SECRET_KEY (secret or restricted key of the platform account)}"
case "$domain" in
  *[!a-z0-9.-]* | "" ) echo "not a domain name: $domain" >&2; exit 2 ;;
esac

stripe() { # method path [form fields…]
  local method=$1 path=$2
  shift 2
  local args=()
  for field in "$@"; do args+=(--data-urlencode "$field"); done
  # a POST without fields still sends a form body (Stripe and stripe-mock expect the form content type)
  if [ "$method" = POST ] && [ $# -eq 0 ]; then args+=(-H 'Content-Type: application/x-www-form-urlencoded' --data ''); fi
  curl -sS --fail-with-body -X "$method" -u "$STRIPE_SECRET_KEY:" -H "Stripe-Version: $version" \
    ${args[@]+"${args[@]}"} "$api$path"
}

field() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {"d": d}))' "$1"; }

find_domain() {
  stripe GET "/v1/payment_method_domains?domain_name=$domain&limit=1" | field '(d.get("data") or [{}])[0].get("id", "")'
}

show() {
  stripe GET "/v1/payment_method_domains/$1" | python3 -c "$SHOW"
}

SHOW='
import json, sys
d = json.load(sys.stdin)
print("%s (%s) enabled=%s" % (d.get("domain_name"), d.get("id"), d.get("enabled")))
for wallet in ("apple_pay", "google_pay", "link"):
    s = d.get(wallet) or {}
    err = (s.get("status_details") or {}).get("error_message")
    print("  %s: %s%s" % (wallet, s.get("status"), " - " + err if err else ""))
'

case "$action" in
  register)
    file="https://$domain/.well-known/apple-developer-merchantid-domain-association"
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-redirs 0 "$file" || true)
    if [ "$code" != "200" ]; then
      echo "$file answers $code (Apple needs 200 without a redirect): set NL_APPLE_PAY_DOMAIN_ASSOCIATION first" >&2
      exit 1
    fi
    id=$(find_domain)
    if [ -z "$id" ]; then
      id=$(stripe POST /v1/payment_method_domains "domain_name=$domain" | field 'd["id"]')
      echo "created $id"
    else
      echo "already registered: $id"
    fi
    stripe POST "/v1/payment_method_domains/$id/validate" >/dev/null
    show "$id"
    ;;
  status | validate)
    id=$(find_domain)
    [ -n "$id" ] || { echo "$domain is not registered: run register first" >&2; exit 1; }
    [ "$action" = validate ] && stripe POST "/v1/payment_method_domains/$id/validate" >/dev/null
    show "$id"
    ;;
  *) usage ;;
esac
