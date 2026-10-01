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
#   scripts/observability.sh check       # dashboards up to date + Collector config valid + alert rule unit tests
#   scripts/observability.sh test-rules  # promtool test rules (Docker image prom/prometheus)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
GRAFANA_PORT=${GRAFANA_PORT:-3300}
OTEL_HTTP_PORT=${OTEL_HTTP_PORT:-4318}
PROMETHEUS_IMAGE=${PROMETHEUS_IMAGE:-prom/prometheus:v3.5.0}
COLLECTOR_IMAGE=${COLLECTOR_IMAGE:-otel/opentelemetry-collector-contrib:0.161.0}
compose() { docker compose --profile observability "$@"; }

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
    docker run --rm -v "$ROOT/deploy/observability/prometheus:/w:ro" -w /w --entrypoint promtool "$PROMETHEUS_IMAGE" \
      check rules rules/northline-alerts.yml
    docker run --rm -v "$ROOT/deploy/observability/prometheus:/w:ro" -w /w --entrypoint promtool "$PROMETHEUS_IMAGE" \
      test rules tests/northline-alerts_test.yml
    ;;
  check)
    python3 deploy/observability/grafana/dashboards.py --check
    docker run --rm -v "$ROOT/deploy/observability/collector/collector-local.yaml:/c.yaml:ro" "$COLLECTOR_IMAGE" \
      validate --config=/c.yaml && echo "Collector config valid"
    "$0" test-rules
    ;;
  help|-h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) echo "observability.sh: unknown command '$cmd' (up | down | env | status | open | dashboards | check | test-rules)" >&2; exit 2 ;;
esac
