#!/usr/bin/env bash
# OVHcloud targets (docs/runbooks/ovh.md § V10 / M8): the smoke test after a deploy, in the cluster of kubectl's current
# context, and the address the DNS records point at.
#
#   scripts/ovh/smoke.sh ovh-vps|ovh-mks          (make ovh-smoke TARGET=…; the pipeline's deploy job runs it)
#
#   1. every Deployment of the release rolled out; the release's Jobs (migrations, topics, indices, OAuth clients) done
#   2. the api's readiness from inside its pod (/actuator/health/readiness)
#   3. the public address: ovh-vps the VPS's IPv4 (Terraform), ovh-mks the load balancer of Envoy's Service
#   3b. the location (S-160): the api's Object Storage endpoint is the one of Terraform's ovh_location
#   4. once the zone's records point at that address: over HTTPS — the issuer of auth's OpenID configuration, the
#      Studio's and the consumer site's sign-in pages, the consumer home, the console (retried while Let's Encrypt issues
#      the certificates), and with the demo inbox that https://mail.<domain> refuses a request without credentials.
#      Before the records exist this step is reported as pending, not failed (SMOKE_STRICT=1 fails).
#
# Environment: OVH_DOMAIN [demo.northline.ca], SMOKE_STRICT [0], ROLLOUT_TIMEOUT [10m], CERT_WAIT [600] seconds.
# Writes a Markdown summary to $GITHUB_STEP_SUMMARY when set (the URLs, the address, the records to create).
set -euo pipefail
. "$(dirname "$0")/common.sh"
ovh_target "${1:-}"
need kubectl curl jq
NS=$OVH_NAMESPACE
D=${OVH_DOMAIN:-demo.northline.ca}
fail=0
summary=${GITHUB_STEP_SUMMARY:-/dev/null}
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*" >&2; fail=1; }

echo "== 1. rollouts and jobs"
for d in $(kubectl -n "$NS" get deploy -l app.kubernetes.io/part-of=northline -o name); do
  if kubectl -n "$NS" rollout status "$d" --timeout="${ROLLOUT_TIMEOUT:-10m}" >/dev/null; then ok "$d"; else bad "$d not rolled out"; fi
done
while read -r name succeeded failed; do
  [ -n "$name" ] || continue
  if [ "$succeeded" -ge 1 ]; then ok "job/$name"; elif [ "$failed" -ge 1 ]; then bad "job/$name failed"; else echo "...  job/$name running"; fi
done < <(kubectl -n "$NS" get jobs -o json | jq -r '.items[] | "\(.metadata.name) \(.status.succeeded // 0) \(.status.failed // 0)"')

echo "== 2. health"
# The images are distroless (no shell, no wget): a pod is Ready only while its readiness probe —
# /actuator/health/readiness for the Spring apps — answers UP, so Ready is that endpoint's answer.
for app in api auth bff consumer-bff console-bff worker; do
  if kubectl -n "$NS" wait --for=condition=Ready pod -l "app.kubernetes.io/name=northline-$app" --timeout=120s >/dev/null 2>&1; then
    ok "northline-$app Ready (/actuator/health/readiness UP)"
  else bad "northline-$app not Ready"; fi
done

echo "== 3. address"
ip=
if [ "$OVH_TARGET" = ovh-vps ]; then
  ip=$(tf_out vps .ipv4 2>/dev/null || true)
else
  for _ in $(seq 1 30); do
    ip=$(kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=northline \
      -o jsonpath='{.items[0].status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
    [ -z "$ip" ] || break
    sleep 10
  done
fi
[ -n "$ip" ] && [ "$ip" != null ] || { bad "no public address yet"; ip="(pending)"; }
echo "address: $ip"

echo "== 3b. location"
loc=$(tf_out location .name 2>/dev/null || true)
loc_label=$(tf_out location .label 2>/dev/null || true)
want=$(tf_out location .s3_endpoint 2>/dev/null || true)
have=$(kubectl -n "$NS" get configmap northline-infra -o jsonpath='{.data.STORAGE_ENDPOINT}' 2>/dev/null || true)
if [ -z "$loc" ]; then echo "...  no location output in the state (terraform apply with S-160 first)"
elif [ "$have" = "$want" ]; then ok "location $loc ($loc_label): the api stores uploads at $have"
else bad "location $loc: the api's STORAGE_ENDPOINT is '$have', expected $want — re-run the deploy after terraform apply"; fi
country=$(tf_out location .country 2>/dev/null || true)

resolve() {
  if command -v dig >/dev/null 2>&1; then dig +short A "$1" | tail -1
  elif command -v getent >/dev/null 2>&1; then getent ahostsv4 "$1" | awk 'NR == 1 { print $1 }'
  else host -t A "$1" 2>/dev/null | awk '/has address/ { print $4; exit }'
  fi
}

echo "== 4. HTTPS"
dns=pending; https=pending
if [ "$(resolve "$D")" = "$ip" ] && [ "$(resolve "studio.$D")" = "$ip" ]; then
  dns=ok
  deadline=$(( $(date +%s) + ${CERT_WAIT:-600} ))
  check() {   # check <label> <url> [grep pattern]
    local code body
    while :; do
      body=$(curl -sS --max-time 15 -w '\n%{http_code}' "$2" 2>/dev/null || true)
      code=${body##*$'\n'}
      if [ "$code" = 200 ] && { [ -z "${3:-}" ] || printf '%s' "$body" | grep -q "$3"; }; then ok "$1 $2"; return 0; fi
      if [ "$(date +%s)" -ge "$deadline" ]; then bad "$1 $2 (HTTP ${code:-none})"; return 0; fi
      sleep 15
    done
  }
  check "auth issuer" "https://auth.$D/.well-known/openid-configuration" "\"issuer\":\"https://auth.$D\""
  check "studio sign-in" "https://studio.$D/sign-in"
  check "consumer home" "https://$D/"
  check "consumer sign-in" "https://$D/sign-in"
  check "console" "https://console.$D/"
  if kubectl -n "$NS" get deploy northline-mailpit >/dev/null 2>&1; then
    # The demo inbox must answer only with credentials.
    code=$(curl -s -o /dev/null --max-time 15 -w '%{http_code}' "https://mail.$D/" || true)
    if [ "$code" = 401 ]; then ok "demo inbox https://mail.$D asks for a password"; else bad "demo inbox https://mail.$D answered $code without credentials (expected 401)"; fi
  fi
  [ "$fail" = 1 ] && https=failed || https=ok
else
  echo "pending: $D and studio.$D don't resolve to $ip yet — create the records below, then re-run the deploy (or make ovh-smoke)"
  [ "${SMOKE_STRICT:-0}" = 1 ] && bad "DNS not pointing at $ip"
fi

{
  echo "### Northline on $OVH_TARGET"
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| location | \`${loc:-?}\` ${loc_label:+— $loc_label}$( [ -n "$country" ] && [ "$country" != CA ] && printf ' (outside Canada: fake demo data only)') |"
  echo "| public address | \`$ip\` |"
  echo "| DNS | $dns |"
  echo "| HTTPS smoke test | $https |"
  echo
  echo "**DNS records** at whoever hosts \`$D\` (docs/runbooks/ovh.md § DNS):"
  echo
  echo '```text'
  echo "$D.    300 IN A   $ip"
  echo "*.$D.  300 IN A   $ip"
  echo "$D.        IN CAA 0 issue \"letsencrypt.org\""
  echo '```'
  echo
  echo "**URLs:** consumer https://$D · studio https://studio.$D · console https://console.$D · auth https://auth.$D · api https://api.$D"
  echo
  if kubectl -n "$NS" get deploy northline-mailpit >/dev/null 2>&1; then
    echo "**Emails** (sign-up codes): the demo inbox https://mail.$D — user \`demo\`, the password MAILPIT_PASSWORD (docs/runbooks/ovh.md § Email)."
  else
    echo "**Emails** (sign-up codes): EMAIL_PROVIDER=\`$(kubectl -n "$NS" get configmap northline-auth -o jsonpath='{.data.EMAIL_PROVIDER}' 2>/dev/null)\` — with \`local\` they are only in \`kubectl -n $NS logs deploy/northline-auth\` (docs/runbooks/ovh.md § Email)."
  fi
} | tee -a "$summary"

exit $fail
