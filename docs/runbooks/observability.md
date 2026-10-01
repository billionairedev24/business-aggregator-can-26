# Observability: traces, metrics, dashboards, alerts (S-111)

Every Spring app — api, auth, the two BFFs, worker — records **traces** and **metrics** with Micrometer and exports
them, with their **logs** (S-112, [logging.md](logging.md)), over **OTLP** to one **OpenTelemetry Collector** per
environment. The Collector is the only place that knows the backend: any OTLP backend (Grafana Cloud, a self-hosted
LGTM, Honeycomb, Elastic…), AWS X-Ray + CloudWatch, Google Cloud Operations or Azure Monitor. Switching backends is a
values change; the apps never change.

```
browser ──traceparent──▶ BFF ──traceparent──▶ api ──▶ Postgres (a span per statement)
                                               └──traceparent header──▶ Kafka ──▶ worker ──▶ Postgres / Elasticsearch / email
  every app ──OTLP/HTTP :4318──▶ OpenTelemetry Collector ──▶ the environment's backend(s)
```

## What is recorded

| signal | where from | notes |
|---|---|---|
| HTTP server spans + `http.server.requests` | every app (Spring MVC observation) | `/actuator/**` (probes, scrapes) is neither traced nor counted |
| HTTP client spans + `http.client.requests` | BFF relay to the api and to northline-auth, Stripe, registries, providers (Boot's `RestClient`) | the BFF→api call carries `traceparent` |
| SQL spans | api, auth, worker (datasource-micrometer) | one span per statement with the **parameterised** SQL; parameter values are never recorded (`jdbc.datasource-proxy.include-parameter-values: false`), and the Collector deletes `jdbc.params*` anyway |
| Kafka producer / consumer spans | api, auth (outbox externalizer), worker (listeners, retries, DLQ) | `traceparent` travels as a Kafka header next to `nl-event-*` ([events.md](events.md)); the worker continues the api's trace |
| JVM, Hikari pool, Kafka client (lag), Spring Kafka listener timers | every app | Kafka lag: `kafka_consumer_fetch_manager_records_lag_max` |
| business metrics | see below | counters/timers only — never a user, merchant or order id in a label |

### Business metrics

| metric (Prometheus name) | app | labels | meaning |
|---|---|---|---|
| `northline_auth_sign_ins_total` | auth | `method` (totp, passkey, backup_code, phone_otp…), `mfa`, `outcome` (succeeded, failed) | sign-ins and failed factors |
| `northline_auth_lockouts_total` | auth | `action` | rate-limit lockouts (S-9) |
| `northline_checkouts_total` | api | `ref_type` (booking, order_line), `status` (authorized, requires_action, …) | escrow holds opened at checkout (Stripe PaymentIntents) |
| `northline_payouts_total`, `northline_payouts_amount_dollars_*` | api | `outcome` (sent, failed, canceled), `kind` | payouts to merchants and their amounts (CAD) |
| `northline_kds_prep_seconds_*` | api | `late` | kitchen display: accepted → ready (the promised time is the ticket's) |
| `northline_kds_handoff_wait_seconds_*` | api | `mode` (delivery, pickup) | ready → handed to the courier / customer |
| `northline_kds_promised_minutes_*` | api | — | minutes promised at "Accept" |
| `northline_events_consumed_total` | worker | `consumer`, `type`, `outcome` (processed, duplicate, failed, poison) | S-26 |
| `northline_events_dead_lettered_total` | worker | `consumer`, `topic` | S-26 DLQ — the page |
| `northline_tracking_streams` | api | — | open order-tracking streams (SSE) on the replica (S-91: the console's "Tracking" tile) |

Counted after the transaction commits (a rolled-back checkout never happened). Every series also carries
`application` (= `spring.application.name`: `northline-api`, `northline-auth`, `northline-studio-bff`,
`northline-consumer-bff`, `northline-worker`).

## Trace context

- **W3C Trace Context** (`traceparent`, `tracestate`) everywhere — HTTP and Kafka; B3 is not produced or accepted.
- **Browser:** `@northline/client`'s `http()` sends a fresh `traceparent` with every same-origin call (the BFF), so a
  request seen in the browser's network panel can be looked up by its trace id. The browser exports no spans of its
  own (no public telemetry endpoint to abuse — see DECISIONS); the BFF's server span is the first one stored. The
  consumer site's server-side rendering uses the same client, so SSR → consumer-bff calls carry a trace too.
- **@Async / Modulith listeners:** the platform's `ContextPropagatingTaskDecorator` carries the trace onto the task
  executor, so the outbox externalizer publishes to Kafka inside the request's trace.
- **Logs:** every log line carries `traceId` / `spanId` (the console pattern and, S-112, the JSON fields `trace.id`,
  `span.id`); the worker's lines also carry `[consumer|type|eventId|traceId]` (S-26). Jump from a log line to its trace.

## Sampling

One ratio for the whole system, `observability.tracesSampleRatio` in the chart (`OTEL_TRACES_SAMPLER_ARG`):
dev `1.0`, staging `0.5`, prod `0.1`; local `1.0`. `ConsistentSampling`
(`server/platform/…/observability/ConsistentSampling.java`) decides on the **trace id**:

- a span whose parent is in the same process follows its parent;
- a span whose parent came from outside — a browser, the BFF, a Kafka header — is decided again on its trace id, so a
  caller sending `-01` can't force sampling (the browser sends it, anyone can);
- because the rule is a pure function of the trace id, the BFF, the api and the worker agree: whole traces are kept or
  dropped. **Keep the ratio the same for every app** (the chart sets one value).

Metrics are not sampled. Want 100 % of the errors and slow requests on top? Add a `tail_sampling` processor through
`observability.collector.extraProcessors` and raise the apps' ratio (they then send everything to the Collector, which
keeps what the policy says).

## The Collector (Helm)

`deploy/helm/northline/templates/otel-collector.yaml` — Deployment `northline-otel-collector` (image
`otel/opentelemetry-collector-contrib`, pinned by digest), Service on 4317 (gRPC) / 4318 (HTTP), a NetworkPolicy that
admits only the release's own pods, optional HPA and ExternalSecret. Its configuration is rendered from values:

- **fixed processors:** `memory_limiter` (`observability.collector.memoryLimitMiB`), `resource`
  (`deployment.environment.name`), `attributes/scrub` (deletes `jdbc.params*`, `authorization` / `cookie` /
  `x-xsrf-token` / `x-dev-user` / `set-cookie` header attributes, `enduser.id`, `user.email`), S-112's log redaction,
  `batch`;
- **your exporters:** `observability.collector.exporters` (the Collector's own syntax) and
  `observability.collector.pipelines.{traces,metrics,logs}`; the chart refuses a pipeline naming an undefined exporter;
- `observability.collector.extraProcessors` / `extraProcessorsIn.<signal>` for anything else (tail sampling,
  filtering);
- `observability.collector.env` / `secretEnv` for the exporters' settings and credentials (`${env:VAR}` in the config).

With the Collector on (dev, staging, prod), every Spring app gets `OTEL_EXPORT_ENABLED=true`,
`OTEL_EXPORTER_OTLP_ENDPOINT=http://northline-otel-collector:4318`, `OTEL_TRACES_SAMPLER_ARG` and
`OTEL_RESOURCE_ATTRIBUTES=deployment.environment.name=<env>,service.version=<image tag>`. Spring Boot maps the
standard `OTEL_*` variables (also `OTEL_EXPORTER_OTLP_HEADERS`, `OTEL_SDK_DISABLED`, …) onto its properties, so an app
can also send straight to a backend — not recommended (no scrub, credentials in every app).

`deploy/helm/validate.sh` renders every environment × cloud with the Collector, and the Collector configurations were
checked with `otelcol-contrib validate` (`scripts/observability.sh check` does it for the local one).

### AWS

`values-aws.yaml`: traces → **X-Ray** (`awsxray`), metrics → **CloudWatch** in embedded metric format (`awsemf`,
namespace `Northline`, log group `/northline/<env>/metrics`), logs → **CloudWatch Logs** (`/northline/<env>/apps`),
region `ca-central-1`. Identity: IRSA role `northline-<env>-otel-collector` (Terraform `workload_identities`,
policies `AWSXrayWriteOnlyAccess` + `CloudWatchAgentServerPolicy`); the chart picks the role annotation from
`workloadIdentities.otel-collector`. The ADOT image (`public.ecr.aws/aws-observability/aws-otel-collector`) is a
drop-in replacement (`observability.collector.image`). Amazon Managed Grafana reads X-Ray and CloudWatch; or send
metrics to Amazon Managed Service for Prometheus with `prometheusremotewrite` + `sigv4auth`.

### Google Cloud

`values-gcp.yaml`: traces → **Cloud Trace**, logs → **Cloud Logging** (`googlecloud`), metrics → **Managed Service
for Prometheus** (`googlemanagedprometheus`), project from the metadata server. Identity: Workload Identity
`otel-collector` with `roles/cloudtrace.agent`, `roles/monitoring.metricWriter`, `roles/logging.logWriter`
(Terraform; it also enables the three APIs). Dashboards: Cloud Monitoring reads the PromQL of the Grafana dashboards,
or point a Grafana at Managed Prometheus.

### Azure

`values-azure.yaml`: traces, metrics and logs → **Azure Monitor / Application Insights** (`azuremonitor`) with
`APPLICATIONINSIGHTS_CONNECTION_STRING` from Key Vault (`applicationinsights-connection-string`, created empty by
Terraform — create a workspace-based Application Insights resource in **Canada Central** and paste its connection
string). Prometheus-style dashboards: Azure Managed Grafana on Azure Monitor managed Prometheus (`prometheusremotewrite`
with the managed identity sidecar), or the Application Insights views.

### Any OTLP backend

`deploy/helm/test-values/observability-otlp.yaml` shows Grafana Cloud: one `otlphttp` exporter for all three signals,
`Authorization` from `OTEL_BACKEND_AUTH` (`otel-backend-auth` in the secrets manager, created empty by Terraform in
every cloud: `Basic <base64 of instance-id:token>`). The same shape works for Honeycomb (`x-honeycomb-team`),
Elastic (`Authorization: ApiKey …`), New Relic, Datadog's OTLP intake or your own LGTM — add the file to the
environment's values (`deploy/argocd/envs/<env>/values.yaml`).

### Canadian data residency

Telemetry is operational data, but log bodies, span names and SQL can describe customers' activity. Keep the backend
in Canada: X-Ray / CloudWatch in `ca-central-1`, Google Cloud's `northamerica-northeast1/2`, Application Insights in
Canada Central, Grafana Cloud's Canadian region (`prod-ca-east-0`) or a self-hosted stack in the cluster's region. The
apps redact before export (S-112) and the Collector scrubs again.

## Dashboards (as code)

`deploy/observability/grafana/dashboards.py` generates `deploy/observability/grafana/dashboards/*.json`:

| dashboard | uid |
|---|---|
| overview — every service, the business stats | `northline-overview` |
| one per service: requests (rate, 5xx, p50/p95/p99, slowest routes), outgoing calls, DB pool, JVM | `northline-api`, `-auth`, `-bff`, `-consumer-bff`, `-worker` |
| sign-in | `northline-sign-in` |
| checkout and payouts | `northline-checkout-payouts` |
| kitchens (KDS) | `northline-kitchens` |
| events (Kafka: outcomes, lag, DLQ, listener and producer timings) | `northline-events` |
| AI (S-129: model calls, outcome, latency, tokens, cost per call by feature and model — [ai.md](ai.md)) | `northline-ai` |

Queries are PromQL on a `datasource` variable — any Prometheus-compatible source: the local LGTM, Grafana Cloud,
Amazon Managed Prometheus, Google Managed Prometheus, Azure Monitor managed Prometheus. Change a dashboard in the
generator, then `make obs-dashboards`; `make obs-check` (and CI) fails when the JSON is stale. Import into a hosted
Grafana with its API or the UI ("Import dashboard" → upload the JSON), or provision the folder like the local stack.
On CloudWatch-only AWS set-ups use CloudWatch dashboards over the `Northline` EMF namespace instead (same metric names).

## Alerts

`deploy/observability/prometheus/rules/northline-alerts.yml` (Prometheus rule syntax — Grafana Cloud/Mimir, Amazon
Managed Prometheus, Google Managed Prometheus, Azure Monitor managed Prometheus, or your own Prometheus +
Alertmanager), unit-tested with `promtool test rules` (`make obs-check`; tests in `prometheus/tests`).

| alert | severity | when | first steps |
|---|---|---|---|
| <a id="northlinehigherrorrate"></a>`NorthlineHighErrorRate` | page | > 5 % 5xx for 5 min (and some traffic) | the service dashboard → "Errors by route" → open a trace from that route; the app's logs by `trace.id` |
| <a id="northlineslowrequests"></a>`NorthlineSlowRequests` | ticket | p95 > 2 s for 10 min | "Slowest routes", then a trace: which span (SQL, Stripe, another service) takes the time; DB pool waits |
| `NorthlineEventDeadLettered` | page | a consumer dead-lettered an event | [events.md § 3](events.md#3-dlq-investigate-and-replay) |
| `NorthlineConsumerLag` | ticket | lag > 1000 for 10 min | [events.md § 2](events.md#2-alerts-and-metrics); the worker's CPU / pool; Elasticsearch health for the indexer |
| <a id="northlinesigninfailures"></a>`NorthlineSignInFailures` | ticket | over half the sign-ins fail for 10 min | per-method failures: one factor broken (passkeys after an RP id change, the TOTP clock) or a credential-stuffing run (lockouts rise too — rate limits already hold) |
| `NorthlineLockoutSpike` | ticket | > 20 lockouts on one action in 15 min | [README.md § Rate limits](README.md#rate-limits-s-9) |
| `NorthlinePayoutFailed` | ticket | a payout failed or was canceled | [stripe.md](stripe.md); the merchant got the payout.failed email (S-27) |
| <a id="northlinekitchenslate"></a>`NorthlineKitchensLate` | ticket | > 30 % of kitchen orders ready late for 15 min | the kitchens dashboard; a kitchen keeps its default prep too low (Settings › Kitchen hours), or the KDS isn't used live |

Routing (pager / ticket) belongs to the backend's alerting (Alertmanager, Grafana alerting, CloudWatch alarms via
EMF, Cloud Monitoring, Azure Monitor action groups).

## Console health (S-91)

The platform console's overview shows six "System health" tiles (design 03). Five come from these metrics through the
api's `HealthSignals` port, chosen by `CONSOLE_HEALTH_PROVIDER`:

| `CONSOLE_HEALTH_PROVIDER` | what the tiles show |
|---|---|
| `none` (default; local, test, or before a metrics store exists) | "—" with a grey dot: not measured |
| `prometheus` | one instant query per tile (`GET <CONSOLE_HEALTH_PROMETHEUS_URL>/api/v1/query`) against any Prometheus-compatible store — Grafana Cloud / Mimir, Amazon Managed Prometheus (through a SigV4 proxy sidecar, the token empty), Google Managed Prometheus (its frontend), Azure Monitor managed Prometheus, or your own Prometheus. `CONSOLE_HEALTH_PROMETHEUS_TOKEN` is sent as a bearer token when set |

| tile | query (`northline.console.health.prometheus.queries.*`, application.yml) | rosehip dot above (`degraded-above`) |
|---|---|---|
| API p95 | p95 of `http_server_requests_seconds` of the api, ms | 500 ms |
| Search | the same for `/api/v1/search…`, ms | 300 ms |
| Kafka lag | `max(kafka_consumer_fetch_manager_records_lag_max)` of the worker, records (the design's seconds aren't measured) | 1,000 (the `NorthlineConsumerLag` alert) |
| Stripe | share of failed calls to `api.stripe.com` over 5 min | 5 % |
| Tracking WS | `sum(northline_tracking_streams)` — open order-tracking streams (a gauge the api adds in S-91) | — |
| Courier app | not a metric: couriers whose app went offline during a run (`fulfilment`, from the database) | any |

A query that fails, times out (`CONSOLE_HEALTH_PROMETHEUS_TIMEOUT`, 3 s) or returns no sample shows that tile as not
measured; the overview itself never fails for it (a WARN line `Console health: <tile> unreadable` is logged). The label
names follow the OTLP → Prometheus conversion of S-111 (`application`, `uri`, `client_name`, `outcome`); a backend
that names them differently needs its own queries, set as `NORTHLINE_CONSOLE_HEALTH_PROMETHEUS_QUERIES_API_P95=…` and
so on. **Never run against a real store yet**: the adapter is tested against a WireMock stand-in answering as the
Prometheus HTTP API documents.

## Local

```sh
make up OBS=1                       # stand-ins + apps as usual, plus the Collector and Grafana; the apps export to it
make obs-up                         # only the observability stack (then start apps yourself with: eval "$(make -s obs-env)")
make obs-open                       # http://localhost:3300 — admin / admin, folder "Northline"
make obs-down
```

`docker compose --profile observability` runs the same Collector processors as the chart
(`deploy/observability/collector/collector-local.yaml`) in front of `grafana/otel-lgtm` (Grafana, Tempo, Loki,
Prometheus). Grafana is on **3300** (the consumer web app owns 3000); ports: `GRAFANA_PORT`, `OTEL_GRPC_PORT`,
`OTEL_HTTP_PORT` in `.env`. Explore → Tempo → search by service name, or paste a `traceparent`'s trace id from the
browser's network panel. Without `OBS=1` nothing is exported (the apps still put trace ids in their logs).

## Tests

- `platform`: `ConsistentSamplingTest` (callers can't force sampling, every service agrees, local children follow),
  `ObservabilityDefaultsTest`; the test fixture `OtlpReceiver` is a stand-in Collector that decodes OTLP/protobuf.
- api `TracingTest`: a request's trace continues the caller's `traceparent`, has SQL spans without parameter values,
  and is carried into Kafka (`traceparent` header, PRODUCER span); metrics are exported; probes aren't traced.
- bff `BffTracingTest`: browser `traceparent` → BFF SERVER span → CLIENT span → the api receives the same trace id.
- worker `WorkerTracingTest`: a record with the api's `traceparent` is consumed in the same trace (CONSUMER span + SQL).
- auth `AuthTelemetryTest`: sign-ins traced and counted, failures counted, no email in any span.
