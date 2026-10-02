#!/usr/bin/env bash
# S-104 — scripted negative tests against a running api (make security-dast; docs/security/pentest-scope.md).
# What the external testers will try first, as curl calls with the expected refusal. Runs against the `local` profile
# (dev auth: X-Dev-User picks the seeded persona) — never against a shared environment.
#
#   API=http://localhost:8080 scripts/security/negative-tests.sh
#
# Exit status: the number of checks that failed (0 = all refused as expected).
set -uo pipefail

API=${API:-http://localhost:8080}
RAVI=01J9ZD3V00000000000000RAV1   # owner of the three seeded businesses
AMARA=01J9ZD3V0000000000000C0001  # consumer
PRAIRIE_WRENCH=01J9ZD3V00000000000000PWM1
failed=0

# check <name> <expected status regex> <curl args…>
check() {
  local name=$1 expected=$2
  shift 2
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$@")
  if [[ $status =~ ^($expected)$ ]]; then
    printf '  ok    %-70s %s\n' "$name" "$status"
  else
    printf '  FAIL  %-70s %s (expected %s)\n' "$name" "$status" "$expected"
    failed=$((failed + 1))
  fi
}

# header_absent <name> <header regex> <curl args…>
header_absent() {
  local name=$1 header=$2
  shift 2
  if curl -s -D - -o /dev/null --max-time 30 "$@" | grep -qiE "$header"; then
    printf '  FAIL  %-70s header present: %s\n' "$name" "$header"
    failed=$((failed + 1))
  else
    printf '  ok    %-70s\n' "$name"
  fi
}

# header_present <name> <header regex> <curl args…>
header_present() {
  local name=$1 header=$2
  shift 2
  if curl -s -D - -o /dev/null --max-time 30 "$@" | grep -qiE "$header"; then
    printf '  ok    %-70s\n' "$name"
  else
    printf '  FAIL  %-70s header missing: %s\n' "$name" "$header"
    failed=$((failed + 1))
  fi
}

curl -s -o /dev/null --max-time 10 "$API/actuator/health" || { echo "No api at $API (make up, or API=…)"; exit 1; }
echo "Negative tests against $API"

echo "Authentication"
check "no token on /me" "401" "$API/api/v1/me"
# refused either way: 401 where the issuer answers, 403 under `local` (no auth server to verify against)
check "garbage bearer token" "401|403" -H 'Authorization: Bearer not.a.jwt' "$API/api/v1/me"
check "unsigned JWT (alg none)" "401|403" -H 'Authorization: Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.' "$API/api/v1/me"
check "DPoP scheme without a proof" "401" -H 'Authorization: DPoP abc' "$API/api/v1/me"

echo "Authorization (object and function level)"
check "consumer on a business's orders" "403" -H "X-Dev-User: $AMARA" "$API/api/v1/merchants/$PRAIRIE_WRENCH/orders"
check "owner without a second factor" "403" -H "X-Dev-User: $RAVI" -H 'X-Dev-Acr: pwd' \
  "$API/api/v1/merchants/$PRAIRIE_WRENCH/orders"
check "owner on a business that isn't theirs" "403" -H "X-Dev-User: $RAVI" "$API/api/v1/merchants/01J9ZD3V00000000000000NONE/orders"
check "business owner on the console" "403" -H "X-Dev-User: $RAVI" "$API/api/v1/console/overview"
check "consumer on someone else's order" "403|404" -H "X-Dev-User: $AMARA" "$API/api/v1/me/orders/01J9ZD3V00000000000000NONE"
check "consumer on the courier API (bearer, no DPoP)" "401|403" -H "X-Dev-User: $AMARA" "$API/api/v1/courier/run"

echo "Input handling"
check "oversized webhook body (6 MB)" "413" -X POST -H 'Content-Type: application/json' \
  --data-binary @<(head -c 6291456 /dev/zero | tr '\0' 'a') "$API/api/v1/webhooks/stripe"
# 503 under `local` (no signing secret configured: nothing is accepted), 400 where one is
check "Stripe webhook without a signature" "400|401|403|503" -X POST -H 'Content-Type: application/json' \
  -d '{"id":"evt_x","type":"payment_intent.succeeded"}' "$API/api/v1/webhooks/stripe"
check "Stripe webhook with a forged signature" "400|401|403|503" -X POST -H 'Content-Type: application/json' \
  -H 'Stripe-Signature: t=1,v1=0000' -d '{"id":"evt_x"}' "$API/api/v1/webhooks/stripe"
check "path traversal in a media id" "400|404" "$API/api/v1/public/catalogue/media/..%2f..%2fetc%2fpasswd"
check "SQL metacharacters in search" "200|400|422" -G --data-urlencode "q=' OR 1=1 --" "$API/api/v1/search"
check "malformed JSON body" "400|415|422" -X POST -H "X-Dev-User: $AMARA" -H 'Content-Type: application/json' \
  -d '{"items":[' "$API/api/v1/cart/items"
check "TRACE is not allowed" "400|401|403|405" -X TRACE "$API/api/v1/search"

echo "Exposure"
check "actuator env not exposed" "401|403|404" "$API/actuator/env"
check "actuator heapdump not exposed" "401|403|404" "$API/actuator/heapdump"
check "unknown path outside /api is closed" "401|403|404" "$API/admin"
header_absent "no CORS for a foreign origin" '^access-control-allow-origin' -H 'Origin: https://evil.example' "$API/api/v1/search?q=x"
header_present "nosniff on api answers" '^x-content-type-options: *nosniff' "$API/api/v1/search?q=x"
header_absent "no stack trace or server version" '^(x-powered-by|server: *apache)' "$API/api/v1/me"

echo
if [ "$failed" -eq 0 ]; then echo "All negative tests refused as expected."; else echo "$failed check(s) failed."; fi
exit "$failed"
