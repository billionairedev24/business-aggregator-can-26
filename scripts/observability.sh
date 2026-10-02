#!/usr/bin/env bash
# Local observability (S-111, docs/runbooks/observability.md): the OpenTelemetry Collector + Grafana LGTM (Tempo,
# Loki, Prometheus, Grafana) of the compose `observability` profile, the dashboards and the alert rules. Every
# subcommand is non-interactive, so make targets (S-124) and CI can call it:
#
#   scripts/observability.sh up          # start the Collector (:4317 gRPC, :4318 HTTP) and Grafana (:3300)
#   scripts/observability.sh env         # the variables that make an app export: eval "$(scripts/observability.sh env)"
#   scripts/observability.sh status      # is it running, what does Grafana answer
#   scripts/observability.sh open        # print (and try to open) the Grafana URL
#   scripts/observability.sh down        # stop it (no data is kept)
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
GRAFANA_PORT=${GRAFANA_PORT:-3300}
OTEL_HTTP_PORT=${OTEL_HTTP_PORT:-4318}
PROMETHEUS_IMAGE=${PROMETHEUS_IMAGE:-prom/prometheus:v3.5.0}
COLLECTOR_IMAGE=${COLLECTOR_IMAGE:-otel/opentelemetry-collector-contrib:0.161.0}
ALERTMANAGER_IMAGE=${ALERTMANAGER_IMAGE:-prom/alertmanager:v0.28.1}
SLOTH_IMAGE=${SLOTH_IMAGE:-ghcr.io/slok/sloth:v0.12.0}
OBS=deploy/observability
compose() { docker compose --profile observability "$@"; }
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
    compose up -d otel-collector lgtm
    echo "Grafana http://localhost:${GRAFANA_PORT} (admin / admin) — dashboards in the Northline folder."
    echo "Export from the apps:  eval \"\$(scripts/observability.sh env)\"  then start them as usual."
    ;;
  down) compose stop otel-collector lgtm && compose rm -f otel-collector lgtm ;;
  env)
    cat <<ENV
export OTEL_EXPORT_ENABLED=true
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:${OTEL_HTTP_PORT}
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment.name=local
ENV
    ;;
  status)
    compose ps otel-collector lgtm
    curl -fsS -o /dev/null -w "Grafana: HTTP %{http_code}\n" "http://localhost:${GRAFANA_PORT}/api/health" || echo "Grafana: not answering"
    ;;
  open)
    url="http://localhost:${GRAFANA_PORT}/d/northline-overview"
    echo "$url"
    (command -v xdg-open >/dev/null && xdg-open "$url" >/dev/null 2>&1) || (command -v open >/dev/null && open "$url") || true
    ;;
  dashboards) python3 deploy/observability/grafana/dashboards.py ;;
  test-rules)
    rc=0
    tool promtool "$PROMETHEUS_IMAGE" check rules $OBS/prometheus/rules/*.yml $OBS/prometheus/rules/slo/*.yaml || rc=$?
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
  help|-h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) echo "observability.sh: unknown command '$cmd' (up | down | env | status | open | dashboards | check | slo | rules-check | test-rules)" >&2; exit 2 ;;
esac
