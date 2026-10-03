#!/usr/bin/env bash
# make obs-fire-test-alert (docs/runbooks/observability.md § Local alerting): trip an alert on purpose and follow it
# through the local pipeline — northline-auth counts failed sign-ins (northline.auth.sign_ins{outcome=failed}) → OTLP →
# Collector → Prometheus evaluates the rules → Alertmanager → an email in Mailpit. Nothing leaves your machine.
#
#   scripts/fire-test-alert.sh              # ~15 failing sign-ins: NorthlineLocalTestAlert pending, then firing (~2 min)
#   SUSTAIN=12 scripts/fire-test-alert.sh   # then keep failing for 12 minutes: the S-113 alert NorthlineSignInFailures
#                                           # (half the sign-ins fail for 10 minutes) goes pending, then fires too
#
# Needs northline-auth (AUTH_URL, default http://localhost:9000) running with OTEL export (make up OBS=1 / make up-all)
# and the observability stack. Each attempt is a new sign-in of an unknown address (…@example.invalid) with a wrong
# authenticator code — no account is touched, and it never locks one out. Every attempt comes from a different
# documentation address (203.0.113.0/24) in X-Forwarded-For, which northline-auth believes from loopback only
# (TRUSTED_PROXIES), so the per-IP rate limits of S-9 don't stop the burst.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/local-env.sh"
AUTH_URL=${AUTH_URL:-http://localhost:9000}
PROM="http://localhost:$(cfg PROMETHEUS_PORT 9090)"
AM="http://localhost:$(cfg ALERTMANAGER_PORT 9093)"
MAILPIT="http://localhost:$(cfg MAILPIT_UI_PORT 8025)"
COUNT=${COUNT:-15}
SUSTAIN=${SUSTAIN:-0}
ALERT=NorthlineLocalTestAlert
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '✗ %s\n' "$*" >&2; exit 1; }

curl -fsS -o /dev/null --max-time 3 "$AUTH_URL/actuator/health" || die "northline-auth does not answer on $AUTH_URL (make up-all, or make up OBS=1 SERVICES=\"auth …\")"
curl -fsS -o /dev/null --max-time 3 "$PROM/-/ready" || die "Prometheus does not answer on $PROM (make obs-up)"
curl -fsS --max-time 3 "$PROM/api/v1/rules" | grep -q "\"$ALERT\"" || die "Prometheus has no $ALERT rule (deploy/observability/prometheus/local/test-alert.yml mounted?)"

n=0
fail_once() { # one sign-in attempt that fails: an unknown address, then a wrong authenticator code
  n=$((n + 1))
  local jar="$TMP/jar$n" ip="203.0.113.$((n % 250 + 1))" id="fire-test-$n-$$@example.invalid" code
  post() { curl -sS -c "$jar" -b "$jar" -H 'Content-Type: application/json' -H 'Origin: http://localhost:3100' \
    -H "X-Forwarded-For: $ip" "$@"; }
  post -o /dev/null -d "{\"identifier\":\"$id\"}" "$AUTH_URL/api/auth/sign-in" || true
  code=$(post -o /dev/null -w '%{http_code}' -d '{"code":"000000"}' "$AUTH_URL/api/auth/sign-in/totp" || true)
  rm -f "$jar"
  echo "$code"
}
state() { # state <alertname>: inactive | pending | firing (from Prometheus's ALERTS series)
  curl -fsS --max-time 3 "$PROM/api/v1/alerts" 2>/dev/null | python3 -c '
import json, sys
alerts = [a for a in json.load(sys.stdin)["data"]["alerts"] if a["labels"].get("alertname") == sys.argv[1]]
print("firing" if any(a["state"] == "firing" for a in alerts) else "pending" if alerts else "inactive")' "$1"
}

say "Failing $COUNT sign-ins against $AUTH_URL (unknown addresses, wrong codes)…"
first=$(fail_once)
case "$first" in 4*) ;; *) die "a wrong code answered HTTP $first, expected 4xx — is $AUTH_URL northline-auth?" ;; esac
fail_once >/dev/null
# The apps export metrics every 30 s; a counter's first export is its starting point for increase(), so the rest of
# the burst comes after it.
say "Waiting 35 s for the first metrics export…"; sleep 35
i=2; while [ $i -lt "$COUNT" ]; do fail_once >/dev/null; i=$((i + 1)); sleep 1; done
say "Burst sent. Following $ALERT in Prometheus ($PROM/alerts)…"

last=inactive; deadline=$(( $(date +%s) + 300 ))
while :; do
  s=$(state "$ALERT")
  [ "$s" != "$last" ] && { say "$ALERT: $s"; last=$s; }
  [ "$s" = firing ] && break
  [ "$(date +%s)" -ge "$deadline" ] && die "$ALERT did not fire within 5 minutes — make obs-status; is auth exporting (OTEL_EXPORT_ENABLED=true)?"
  sleep 5
done

deadline=$(( $(date +%s) + 120 ))
until curl -fsS --max-time 3 "$AM/api/v2/alerts?filter=alertname%3D%22$ALERT%22" | grep -q "\"$ALERT\""; do
  [ "$(date +%s)" -ge "$deadline" ] && die "Alertmanager ($AM) never received $ALERT"
  sleep 3
done
say "Alertmanager has it: $AM/#/alerts"
deadline=$(( $(date +%s) + 120 ))
until curl -fsS --max-time 3 "$MAILPIT/api/v1/search?query=subject%3A$ALERT" | grep -q '"messages_count":[1-9]'; do
  [ "$(date +%s)" -ge "$deadline" ] && die "no email about $ALERT in Mailpit ($MAILPIT) — docker compose logs alertmanager"
  sleep 3
done
say "Delivered to the local receiver: $MAILPIT (subject \"[TICKET FIRING] $ALERT …\")"

if [ "$SUSTAIN" -gt 0 ]; then
  say "Sustaining failures for $SUSTAIN minutes (one every 4 s) for NorthlineSignInFailures (for: 10m)…"
  end=$(( $(date +%s) + SUSTAIN * 60 )); last=$(state NorthlineSignInFailures)
  say "NorthlineSignInFailures: $last"
  while [ "$(date +%s)" -lt "$end" ]; do
    fail_once >/dev/null; sleep 4
    s=$(state NorthlineSignInFailures)
    [ "$s" != "$last" ] && { say "NorthlineSignInFailures: $s"; last=$s; }
  done
  if [ "$last" = firing ]; then
    deadline=$(( $(date +%s) + 120 ))
    until curl -fsS --max-time 3 "$MAILPIT/api/v1/search?query=subject%3ANorthlineSignInFailures" | grep -q '"messages_count":[1-9]'; do
      [ "$(date +%s)" -ge "$deadline" ] && die "NorthlineSignInFailures fired but no email reached Mailpit ($MAILPIT)"
      sleep 3
    done
    say "NorthlineSignInFailures fired and reached Mailpit (\"[TICKET FIRING] NorthlineSignInFailures …\")"
  else
    say "NorthlineSignInFailures is $last after $SUSTAIN minutes (it needs 10 minutes of > 50 % failures)"
  fi
fi
say "Both resolve on their own a few minutes after the failures stop (Mailpit gets the RESOLVED email)."
