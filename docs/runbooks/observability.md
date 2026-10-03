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
| `northline_jobs_runs_total`, `northline_jobs_last_success_seconds` | api | `task` (`payments.payouts`, `payments.release_escrow`, …), `outcome` | S-113: each payments job run, and when it last succeeded ([alerting.md](alerting.md)) |
| `northline_payouts_delay_seconds_*` | api | `kind` (scheduled) | S-113: a scheduled payout sent after the business's payout time |
| `northline_kds_ticket_delivery_seconds_*` | api | — | S-113: a food order placed → its signal on the kitchen's live bus |
| `northline_studio_live_probe_seconds_*` | api | `outcome` (delivered, lost) | S-113: each replica's live bus round trip every 30 s (KDS freshness) |
| `northline_events_outbox_pending`, `northline_events_outbox_oldest_age_seconds` | api, auth | — | S-113: the Modulith outbox's incomplete publications and the oldest one's age |

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

Queries are PromQL on a `datasource` variable — any Prometheus-compatible source: the local Prometheus, Grafana Cloud,
Amazon Managed Prometheus, Google Managed Prometheus, Azure Monitor managed Prometheus. Change a dashboard in the
generator, then `make obs-dashboards`; `make obs-check` (and CI) fails when the JSON is stale. Import into a hosted
Grafana with its API (`make obs-grafana-provision`, [§ Your own Grafana](#your-own-grafana)) or the UI ("Import
dashboard" → upload the JSON), or with file provisioning (`deploy/observability/grafana/provisioning.yaml`).
On CloudWatch-only AWS set-ups use CloudWatch dashboards over the `Northline` EMF namespace instead (same metric names).

## Alerts

S-113 moved alerting to its own page, [alerting.md](alerting.md): the SLOs (sign-in, checkout, payouts, KDS) and
their burn-rate alerts generated by Sloth, the threshold alerts of
`deploy/observability/prometheus/rules/northline-alerts.yml`, severity routing (page / ticket) to PagerDuty, Opsgenie
or a webhook, the Helm toggle (`alerting.enabled`), the on-call rota export, and a runbook per alert
([alerts/](alerts/README.md)). Rules are unit-tested with `promtool test rules` (`make obs-rules-check`).

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
make up-all                         # everything, with your own Postgres / Valkey / Grafana (BYO_SERVICES) — local.md § 6a
make up OBS=1                       # stand-ins + apps as usual, plus the observability stack; the apps export to it
make obs-up                         # only the observability stack (then start apps yourself with: eval "$(make -s obs-env)")
make obs-open                       # http://localhost:3300 — admin / admin, folder "Northline"
make obs-status · make obs-down
```

`docker compose --profile observability` runs the same Collector processors as the chart
(`deploy/observability/collector/collector-local.yaml`) and sends each signal to its own backend, each published on a
host port so that any Grafana can read it:

| service | host port (`.env`) | what |
|---|---|---|
| OpenTelemetry Collector | `OTEL_HTTP_PORT` 4318, `OTEL_GRPC_PORT` 4317 | what the apps send to (`OTEL_EXPORTER_OTLP_ENDPOINT`) |
| Prometheus 3.5 | `PROMETHEUS_PORT` 9090 | metrics, through its OTLP receiver; evaluates the S-113 rules (`/alerts`, `/rules`) |
| Alertmanager 0.28 | `ALERTMANAGER_PORT` 9093 | the S-113 routing (page / ticket), every receiver = Mailpit |
| Loki 3.5 | `LOKI_PORT` **3110** | logs over OTLP (the trace id is structured metadata `trace_id`) |
| Tempo 2.8 | `TEMPO_PORT` **3210** | traces; TraceQL search |
| Mailpit | `MAILPIT_UI_PORT` 8025 | the alert emails (the app's emails too) |
| Grafana 12 | `GRAFANA_PORT` **3300** | bundled, admin / admin; not started when `BYO_SERVICES` has `grafana` |

Loki and Tempo are not on their usual 3100 / 3200, and Grafana not on 3000: the Studio (3100), the console (3200) and
the consumer web app (3000) own those. Data lives in the containers' tmpfs — nothing is kept after `make obs-down`.
`grafana/otel-lgtm` (S-111's single container) could publish its Prometheus, Loki and Tempo too, but its Prometheus
loads neither our rule files nor an Alertmanager, and it always runs a Grafana; separate containers let the same
rules run locally and leave Grafana optional.

Explore → Northline Tempo → search by service name, or paste a `traceparent`'s trace id from the browser's network
panel; a span's "Logs for this span" opens Loki on the same trace id, a log line's "Trace" link opens Tempo. Without
`OBS=1` (or `make up-all`) nothing is exported (the apps still put trace ids in their logs).

### Your own Grafana

```sh
make obs-grafana-provision GRAFANA_URL=http://localhost:3001 GRAFANA_TOKEN=glsa_your_token
make obs-grafana-provision GRAFANA_URL=http://localhost:3001 GRAFANA_USER=admin GRAFANA_PASSWORD=admin   # or basic auth
```

(= `scripts/grafana-provision.py`, standard-library Python.) Through Grafana's HTTP API it creates or updates — run it
as often as you like, nothing is duplicated:

- the data sources **Northline Prometheus**, **Northline Loki**, **Northline Tempo** and **Northline Alertmanager**
  (uids `northline-prometheus`, `-loki`, `-tempo`, `-alertmanager`), linked: Tempo → Loki (logs of a span, by trace
  id), Loki → Tempo (a log line's `trace_id`), Prometheus exemplars → Tempo, Tempo's service graph from Prometheus;
- the folder **Northline**, and every dashboard of `deploy/observability/grafana/dashboards` in it, their `datasource`
  variable set to Northline Prometheus;
- the alert rules: Prometheus evaluates them, so Grafana lists them under Alerting → Alert rules (data source-managed,
  read-only) with their state, and the Alertmanager data source shows the alerts and silences. `GRAFANA_ALERT_RULES=1`
  also imports every rule group as **Grafana-managed** rules into the Northline folder (Grafana 12's Prometheus rule
  conversion API) — only if you want Grafana to evaluate them as well.

The token: Administration → Users and access → Service accounts → add one with the **Admin** role → add a token
(data sources need Admin). The backend URLs are the ones Grafana itself calls: `http://localhost:9090` etc. for a
Grafana on your machine; for a Grafana in Docker add `GRAFANA_BACKEND_HOST=host.docker.internal` (on Linux, start that
container with `--add-host=host.docker.internal:host-gateway`, or with `--network host` and the default). Put
`GRAFANA_URL` and `GRAFANA_TOKEN` in `.env` with `BYO_SERVICES=…,grafana` and `make up-all` / `make obs-up` provision
it on every start.

**Port:** Grafana's default is 3000, which the consumer web app needs (its OAuth redirects are registered for
`localhost:3000`) — `make up-all` refuses to start the consumer app next to a Grafana on 3000. Move Grafana:
`[server] http_port = 3001` in grafana.ini (Homebrew: `$(brew --prefix)/etc/grafana/grafana.ini`, then
`brew services restart grafana`), or `GF_SERVER_HTTP_PORT=3001` for a container.

### Local alerting

Prometheus loads the S-113 rule files unchanged (`deploy/observability/prometheus/rules`, the SLO burn rates and the
threshold alerts — the same files the Helm chart deploys) plus one local-only test rule
(`deploy/observability/prometheus/local/test-alert.yml`), and sends alerts to the local Alertmanager
(`deploy/observability/alertmanager/alertmanager-local.yml`): the S-113 routing by severity, but both receivers are
emails to Mailpit — **nothing pages anyone**.

```sh
make obs-fire-test-alert            # ~15 failing sign-ins → NorthlineLocalTestAlert pending → firing → Alertmanager → Mailpit (~2 min)
make obs-fire-test-alert SUSTAIN=12 # then 12 more minutes of failures: the S-113 alert NorthlineSignInFailures fires too
```

Each attempt signs in as an unknown address (`…@example.invalid`) with a wrong authenticator code against
northline-auth, so no account is locked; it needs auth running with export on (`make up-all`, or
`make up OBS=1 SERVICES="auth …"`). The script prints each state change; follow along at
http://localhost:9090/alerts (pending → firing), http://localhost:9093 (Alertmanager) and http://localhost:8025 (the
`[TICKET FIRING] NorthlineLocalTestAlert …` email, then `[TICKET RESOLVED] …` a few minutes after the failures stop).

**Metric names, locally and in the cloud.** The apps export with Micrometer over OTLP (`base-time-unit: seconds`), and
every Prometheus-compatible store — the local Prometheus, Grafana Cloud / Mimir, the managed Prometheus services —
translates OTLP names the same way: `http.server.requests` → `http_server_requests_seconds_count` / `_bucket` /
`_sum`, counters get `_total`, a gauge in seconds `_seconds`; dots become underscores; the bucket at 1 s is `le="1"`
(the SLO rules match `1` and `1.0`). The resource's `service.namespace/service.name` becomes the `job` label, which
**overwrites a metric attribute called `job`** — so the payments job counters use `task` (`northline_jobs_runs_total{task="payments.payouts"}`;
they used `job` until this was found running the rules locally, so the payout SLO and the payout-stalled alerts never
matched). Check a name with Prometheus's own view: http://localhost:9090/api/v1/label/__name__/values.

## Tests

- `platform`: `ConsistentSamplingTest` (callers can't force sampling, every service agrees, local children follow),
  `ObservabilityDefaultsTest`; the test fixture `OtlpReceiver` is a stand-in Collector that decodes OTLP/protobuf.
- api `TracingTest`: a request's trace continues the caller's `traceparent`, has SQL spans without parameter values,
  and is carried into Kafka (`traceparent` header, PRODUCER span); metrics are exported; probes aren't traced.
- bff `BffTracingTest`: browser `traceparent` → BFF SERVER span → CLIENT span → the api receives the same trace id.
- worker `WorkerTracingTest`: a record with the api's `traceparent` is consumed in the same trace (CONSUMER span + SQL).
- auth `AuthTelemetryTest`: sign-ins traced and counted, failures counted, no email in any span.
