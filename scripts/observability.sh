#!/usr/bin/env bash
# Local observability (S-111, docs/runbooks/observability.md § Local): the OpenTelemetry Collector of the compose
# `observability` profile in front of Prometheus (OTLP metrics, the S-113 rules), Loki (logs), Tempo (traces),
# Alertmanager (local receiver: Mailpit) and Grafana; the dashboards and the alert rules. Every subcommand is
# non-interactive, so make targets (S-124) and CI can call it:
#
#   scripts/observability.sh up          # start it: Collector :4317/:4318, Prometheus :9090, Loki :3110, Tempo :3210,
#                                        # Alertmanager :9093, Mailpit :8025, Grafana :3300 (not with BYO_SERVICES=…grafana)
#   scripts/observability.sh env         # the variables that make an app export: eval "$(scripts/observability.sh env)"
#   scripts/observability.sh status      # what runs, what answers
#   scripts/observability.sh open        # print (and try to open) the Grafana URL
#   scripts/observability.sh down        # stop it (no data is kept)
#   scripts/observability.sh grafana-provision  # data sources, folder, dashboards (+ GRAFANA_ALERT_RULES=1) into the
#                                        # Grafana at GRAFANA_URL (GRAFANA_TOKEN, or GRAFANA_USER + GRAFANA_PASSWORD)
#   scripts/observability.sh fire-test-alert    # failing sign-ins against northline-auth until NorthlineLocalTestAlert
#                                        # fires and reaches Mailpit (SUSTAIN=12: long enough for NorthlineSignInFailures)
#   scripts/observability.sh dashboards  # regenerate deploy/observability/grafana/dashboards/*.json from dashboards.py
#   scripts/observability.sh check       # dashboards up to date + Collector config valid + rules-check
#   scripts/observability.sh slo         # S-113: regenerate the SLO burn-rate rules from deploy/observability/slo (Sloth)
#                                        #        and copy every rule file into the Helm chart (files/alerting)
#   scripts/observability.sh rules-check # S-113: offline — validate-alerts.py, Sloth drift, promtool check + test rules,
#                                        #        amtool check-config; each tool from PATH, else Docker, else skipped
#   scripts/observability.sh test-rules  # promtool check rules + test rules only
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
. "$ROOT/scripts/local-env.sh"
GRAFANA_PORT=$(cfg GRAFANA_PORT 3300)
OTEL_HTTP_PORT=$(cfg OTEL_HTTP_PORT 4318)
PROMETHEUS_PORT=$(cfg PROMETHEUS_PORT 9090)
LOKI_PORT=$(cfg LOKI_PORT 3110)
TEMPO_PORT=$(cfg TEMPO_PORT 3210)
ALERTMANAGER_PORT=$(cfg ALERTMANAGER_PORT 9093)
MAILPIT_UI_PORT=$(cfg MAILPIT_UI_PORT 8025)
export PROMETHEUS_PORT LOKI_PORT TEMPO_PORT ALERTMANAGER_PORT
PROMETHEUS_IMAGE=${PROMETHEUS_IMAGE:-prom/prometheus:v3.5.0}
COLLECTOR_IMAGE=${COLLECTOR_IMAGE:-otel/opentelemetry-collector-contrib:0.161.0}
ALERTMANAGER_IMAGE=${ALERTMANAGER_IMAGE:-prom/alertmanager:v0.28.1}
SLOTH_IMAGE=${SLOTH_IMAGE:-ghcr.io/slok/sloth:v0.12.0}
OBS=deploy/observability
compose() { docker compose --profile observability "$@"; }
# The compose services of the stack; Grafana only when you don't bring your own, Mailpit only when you don't run one.
obs_services() {
  local s="otel-collector prometheus loki tempo alertmanager"
  byo mail || s="$s mailpit"
  byo grafana || s="$s grafana"
  echo "$s"
}
grafana_url() { if byo grafana; then cfg GRAFANA_URL http://localhost:3000; else echo "http://localhost:${GRAFANA_PORT}"; fi; }
# wait_http <name> <url> [seconds]: until the URL answers 2xx/3xx
wait_http() {
  local i=0 max=${3:-90}
  until curl -fsS -o /dev/null --max-time 2 "$2" 2>/dev/null; do
    i=$((i + 1)); [ $i -ge "$max" ] && { echo "✗ $1 did not answer on $2 (docker compose logs $1)" >&2; return 1; }
    sleep 1
  done
}
provision_grafana() { # provision_grafana [extra args…]: GRAFANA_URL and credentials from the environment / .env
  local url token user password
  url=$(grafana_url); token=$(cfg GRAFANA_TOKEN); user=$(cfg GRAFANA_USER); password=$(cfg GRAFANA_PASSWORD)
  GRAFANA_URL=$url GRAFANA_TOKEN=$token GRAFANA_USER=$user GRAFANA_PASSWORD=$password \
    GRAFANA_BACKEND_HOST=$(cfg GRAFANA_BACKEND_HOST localhost) GRAFANA_ALERT_RULES=$(cfg GRAFANA_ALERT_RULES) \
    python3 "$ROOT/scripts/grafana-provision.py" "$@"
}
have_docker() { command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; }
# tool <name> <image> <args…>: the binary from PATH, else the pinned image (the repository mounted at the same path,
# and $SCRATCH when set), else return 3 (not available).
SCRATCH=""
tool() {
  local name=$1 image=$2; shift 2
  if command -v "$name" >/dev/null 2>&1; then "$name" "$@"
  elif have_docker; then
    docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT:$ROOT" ${SCRATCH:+-v "$SCRATCH:$SCRATCH"} -w "$PWD" \
      --entrypoint "$name" "$image" "$@"
  else return 3; fi
}
skip() { echo "skip $1 (no $1 binary and no Docker)"; }
slo_generate() { tool sloth "$SLOTH_IMAGE" generate --no-log -i "$OBS/slo" -o "$1"; }

cmd=${1:-help}
case "$cmd" in
  up)
    # shellcheck disable=SC2046 # a list of service names
    compose up -d $(obs_services)
    wait_http prometheus "http://localhost:${PROMETHEUS_PORT}/-/ready"
    wait_http loki "http://localhost:${LOKI_PORT}/ready" 120
    wait_http tempo "http://localhost:${TEMPO_PORT}/ready" 120
    if byo grafana; then
      if [ -n "$(cfg GRAFANA_TOKEN)$(cfg GRAFANA_USER)" ]; then provision_grafana --wait 30
      else echo "Your Grafana: make obs-grafana-provision GRAFANA_URL=$(grafana_url) GRAFANA_TOKEN=…  (once; idempotent)"; fi
    else
      wait_http grafana "http://localhost:${GRAFANA_PORT}/api/health" 120
      # The bundled Grafana reaches the backends by their compose names.
      GRAFANA_URL="http://localhost:${GRAFANA_PORT}" GRAFANA_USER=admin GRAFANA_PASSWORD=admin GRAFANA_TOKEN= \
        python3 "$ROOT/scripts/grafana-provision.py" --prometheus-url http://prometheus:9090 --loki-url http://loki:3100 \
        --tempo-url http://tempo:3200 --alertmanager-url http://alertmanager:9093 >/dev/null
      echo "Grafana http://localhost:${GRAFANA_PORT} (admin / admin) — dashboards in the Northline folder."
    fi
    echo "Prometheus http://localhost:${PROMETHEUS_PORT} · Alertmanager http://localhost:${ALERTMANAGER_PORT} · alerts by email in Mailpit http://localhost:${MAILPIT_UI_PORT}"
    echo "Loki http://localhost:${LOKI_PORT} · Tempo http://localhost:${TEMPO_PORT} (APIs for Grafana) · OTLP http://localhost:${OTEL_HTTP_PORT}"
    echo "Export from the apps:  eval \"\$(scripts/observability.sh env)\"  then start them as usual (make up OBS=1 does it)."
    ;;
  down)
    # shellcheck disable=SC2046
    compose stop $(obs_services) && compose rm -f $(obs_services) ;;
  env)
    cat <<ENV
export OTEL_EXPORT_ENABLED=true
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:${OTEL_HTTP_PORT}
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment.name=local
ENV
    ;;
  status)
    # shellcheck disable=SC2046
    compose ps $(obs_services)
    for t in "Prometheus http://localhost:${PROMETHEUS_PORT}/-/ready" "Alertmanager http://localhost:${ALERTMANAGER_PORT}/-/ready" \
      "Loki http://localhost:${LOKI_PORT}/ready" "Tempo http://localhost:${TEMPO_PORT}/ready" "Grafana $(grafana_url)/api/health"; do
      set -- $t
      curl -fsS -o /dev/null -w "$1: HTTP %{http_code}\n" --max-time 3 "$2" 2>/dev/null || echo "$1: not answering ($2)"
    done
    ;;
  open)
    url="$(grafana_url)/d/northline-overview"
    echo "$url"
    (command -v xdg-open >/dev/null && xdg-open "$url" >/dev/null 2>&1) || (command -v open >/dev/null && open "$url") || true
    ;;
  grafana-provision) shift; provision_grafana "$@" ;;
  fire-test-alert) shift; exec "$ROOT/scripts/fire-test-alert.sh" "$@" ;;
  dashboards) python3 deploy/observability/grafana/dashboards.py ;;
  test-rules)
    rc=0
    tool promtool "$PROMETHEUS_IMAGE" check rules $OBS/prometheus/rules/*.yml $OBS/prometheus/rules/slo/*.yaml \
      $OBS/prometheus/local/*.yml || rc=$?
    [[ $rc -eq 0 ]] && { tool promtool "$PROMETHEUS_IMAGE" check config --syntax-only $OBS/prometheus/prometheus-local.yml || rc=$?; }
    [[ $rc -eq 0 ]] && { (cd $OBS/prometheus && tool promtool "$PROMETHEUS_IMAGE" test rules tests/*_test.yml) || rc=$?; }
    [[ $rc -eq 3 ]] && { skip promtool; exit 0; }
    exit $rc
    ;;
  slo)
    slo_generate "$OBS/prometheus/rules/slo" || { [[ $? -eq 3 ]] && echo "sloth: no binary and no Docker" >&2; exit 1; }
    rm -rf deploy/helm/northline/files/alerting && mkdir -p deploy/helm/northline/files/alerting/slo
    cp $OBS/prometheus/rules/*.yml deploy/helm/northline/files/alerting/
    cp $OBS/prometheus/rules/slo/*.yaml deploy/helm/northline/files/alerting/slo/
    echo "SLO rules generated; chart copies in deploy/helm/northline/files/alerting"
    ;;
  rules-check)
    python3 scripts/validate-alerts.py
    tmp=$(mktemp -d); SCRATCH=$tmp; trap 'rm -rf "$tmp"' EXIT
    rc=0; slo_generate "$tmp" || rc=$?
    if [[ $rc -eq 3 ]]; then skip sloth
    elif [[ $rc -ne 0 ]]; then exit $rc
    elif diff -r "$tmp" $OBS/prometheus/rules/slo >/dev/null; then echo "ok   SLO rules match their specs (Sloth)"
    else echo "FAIL $OBS/prometheus/rules/slo is stale: make obs-slo"; diff -r "$tmp" $OBS/prometheus/rules/slo | head -20; exit 1; fi
    "$0" test-rules
    rc=0; tool amtool "$ALERTMANAGER_IMAGE" check-config $OBS/alertmanager/alertmanager.yml >/dev/null || rc=$?
    if [[ $rc -eq 3 ]]; then skip amtool
    elif [[ $rc -ne 0 ]]; then exit $rc
    else
      echo "ok   $OBS/alertmanager/alertmanager.yml (amtool)"
      tool amtool "$ALERTMANAGER_IMAGE" check-config $OBS/alertmanager/alertmanager-local.yml >/dev/null
      echo "ok   $OBS/alertmanager/alertmanager-local.yml (amtool)"
      # The chart's own alertmanager.yml (routing.format=configMap) for each provider.
      if command -v helm >/dev/null 2>&1; then
        for p in pagerduty opsgenie webhook; do
          helm template northline deploy/helm/northline --set alerting.enabled=true --set alerting.routing.format=configMap \
            --set alerting.routing.page.provider=$p --set alerting.routing.ticket.provider=webhook \
            --show-only templates/alerting.yaml | python3 -c 'import sys,yaml
for d in yaml.safe_load_all(sys.stdin):
    if d and d.get("kind") == "ConfigMap" and "alertmanager.yml" in d["data"]: print(d["data"]["alertmanager.yml"])' \
            > "$tmp/am-$p.yml"
          tool amtool "$ALERTMANAGER_IMAGE" check-config "$tmp/am-$p.yml" >/dev/null
          echo "ok   chart alertmanager.yml with page → $p (amtool)"
        done
      else skip helm; fi
    fi
    ;;
  check)
    python3 deploy/observability/grafana/dashboards.py --check
    docker run --rm -v "$ROOT/deploy/observability/collector/collector-local.yaml:/c.yaml:ro" "$COLLECTOR_IMAGE" \
      validate --config=/c.yaml && echo "Collector config valid"
    "$0" rules-check
    ;;
  help|-h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) echo "observability.sh: unknown command '$cmd' (up | down | env | status | open | grafana-provision | fire-test-alert | dashboards | check | slo | rules-check | test-rules)" >&2; exit 2 ;;
esac
