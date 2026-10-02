# Alerting and on-call: SLOs, burn-rate alerts, routing, the rota (S-113)

The apps' metrics (S-111, [observability.md](observability.md)) reach a Prometheus-compatible store through the
OpenTelemetry Collector. On top of them Northline keeps, **as code and vendor-neutral**:

- **SLOs** for the four journeys that must not break — sign-in, checkout, payouts, the kitchen display (KDS) — as
  [Sloth](https://sloth.dev) specs (`deploy/observability/slo/*.yaml`), turned into **multi-window multi-burn-rate**
  Prometheus rules (`deploy/observability/prometheus/rules/slo/`);
- **threshold alerts** for what isn't an SLO (the outbox, consumer lag, the DLQ, a stalled payout run, …) in
  `deploy/observability/prometheus/rules/northline-alerts.yml`;
- **severity routing**: `severity="page"` wakes the person on call, `severity="ticket"` waits for the next working day
  — Alertmanager configuration with pluggable receivers (PagerDuty, Opsgenie, any webhook);
- a **runbook per alert** ([alerts/](alerts/README.md)), linked from every rule's `runbook_url`;
- the **on-call rota** of the platform console (S-96) exported for the paging tool.

```
apps ──OTLP──▶ Collector ──▶ metrics store (Prometheus · Mimir/Grafana Cloud · AMP · GMP · Azure managed Prometheus)
                                   │ rules: slo/*.yaml (Sloth) + northline-alerts.yml
                                   ▼
                             Alertmanager ── severity=page ──▶ PagerDuty │ Opsgenie │ webhook (Grafana OnCall …) ──▶ on call now
                                          └─ severity=ticket ─▶ the team's queue (webhook / PagerDuty low urgency / Opsgenie P3)
console on-call rota (S-96) ──GET /api/v1/ops/oncall(.ics)──▶ the paging tool's schedule
```

**Nothing has paged anyone yet.** No metrics store, Alertmanager or paging account exists in any environment; the
rules and routes are checked offline (promtool and amtool unit tests, kubeconform) and have never fired for real.

## SLOs

30-day rolling windows. The error budget is what may fail in 30 days; at 99.9 % that is 0.1 % of the requests — the
same as about 43 minutes of everything failing.

| journey | SLO (Sloth `service` / `slo`) | SLI: good events / all events | objective | budget (30 d) | alert |
|---|---|---|---|---|---|
| sign-in | `northline-sign-in` / `availability` | `POST /api/auth/sign-in/*` (northline-auth) not answered 5xx — a wrong factor is a 4xx and good | 99.9 % | 0.1 % ≈ 43 min | [`NorthlineSignInAvailabilityBurn`](alerts/sign-in-availability.md) |
| sign-in | `northline-sign-in` / `latency` | the same calls answered within 1 s | 99 % | 1 % ≈ 7.2 h | [`NorthlineSignInLatencyBurn`](alerts/sign-in-latency.md) |
| checkout | `northline-checkout` / `availability` | `POST /api/v1/me/checkouts`, `…/checkouts/{id}/place`, `/api/v1/me/bookings/checkout` (api) not 5xx — declined cards are 4xx | 99.9 % | ≈ 43 min | [`NorthlineCheckoutAvailabilityBurn`](alerts/checkout-availability.md) |
| checkout | `northline-checkout` / `latency` | the same calls within 2.5 s (they call Stripe and the tax provider) | 99 % | ≈ 7.2 h | [`NorthlineCheckoutLatencyBurn`](alerts/checkout-latency.md) |
| payouts | `northline-payouts` / `run-success` | payments job runs of `payments.payouts` (every minute: scheduled payouts, settlement) that finish without an error | 99 % | ≈ 7.2 h | [`NorthlinePayoutRunFailureBurn`](alerts/payout-run.md) |
| payouts | `northline-payouts` / `timeliness` | scheduled payouts sent within 1 h of the business's payout time (its own time zone, from the region model) | 99 % | 1 % of payouts | [`NorthlinePayoutTimelinessBurn`](alerts/payout-timeliness.md) |
| KDS | `northline-kds` / `ticket-delivery` | food orders signalled to the kitchen's live bus within 5 s of being placed | 99.5 % | 0.5 % ≈ 3.6 h | [`NorthlineKdsTicketDeliveryBurn`](alerts/kds-ticket-delivery.md) |
| KDS | `northline-kds` / `freshness` | live bus probes back within 2 s (each api replica signals itself every 30 s; one not back by the next is lost) | 99.5 % | ≈ 3.6 h | [`NorthlineKdsFreshnessBurn`](alerts/kds-freshness.md) |

Why these SLIs: sign-in and checkout are measured where users feel them (the HTTP calls); a payout run that fails or
doesn't happen, and payouts sent late, are what businesses feel; the KDS must show a new order quickly *and* keep
updating — the live stream can go silent while its keep-alives continue, so the bus is probed end to end.

### Burn-rate alerts

Each SLO has one alert name with two severities (Sloth's defaults, Google SRE workbook):

| severity | fires when | i.e. |
|---|---|---|
| `page` | error rate > 14.4 × budget over **1 h and 5 min**, or > 6 × over **6 h and 30 min** | 2 % of the month's budget gone in an hour, or 5 % in six |
| `ticket` | > 3 × over **1 d and 2 h**, or > 1 × over **3 d and 6 h** | on course to spend the whole budget |

No traffic is not an error (0/0 has no value, nothing fires). A page silences the same SLO's ticket (inhibit rule).

### The metrics behind them

| metric (Prometheus name) | app | where | used by |
|---|---|---|---|
| `http_server_requests_seconds_{count,bucket}` | auth, api | Spring MVC (S-111); S-113 adds exact buckets at **1 s and 2.5 s** (`management.metrics.distribution.slo` in the platform's observability defaults) | sign-in, checkout |
| `northline_jobs_runs_total{job,outcome}`, `northline_jobs_last_success_seconds{job}` | api | `JobRuns`, every step of `PaymentsScheduler` (`payments.payouts`, `payments.release_escrow`, …) | payout run SLO, `NorthlinePayoutRunStalled/Missing` |
| `northline_payouts_delay_seconds{kind="scheduled"}` | api | `PayoutService.runScheduled` → `PaymentMetrics` (bucket at 1 h) | payout timeliness |
| `northline_kds_ticket_delivery_seconds` | api | `StudioLiveEvents` on a food `OrderPlaced`: placed → the kitchen's signal published (bucket at 5 s) | KDS ticket delivery |
| `northline_studio_live_probe_seconds{outcome}` | api | `LiveBusProbe` (`LIVE_PROBE_INTERVAL`, 30 s; bucket at 2 s) | KDS freshness |
| `northline_events_outbox_pending`, `northline_events_outbox_oldest_age_seconds` | api, auth | platform `OutboxBacklog` over the Modulith registry (`events.` / `auth.event_publication`), one query per 15 s | `NorthlineOutboxBacklog/Stuck` |
| `kafka_consumer_fetch_manager_records_lag_max`, `northline_events_dead_lettered_total` | worker | Kafka client, S-26 (already there) | `NorthlineConsumerLag(Critical)`, `NorthlineEventDeadLettered` |

Labels never carry ids (cardinality, privacy). The `le="1"` buckets are matched as `le=~"1(\\.0)?"`: Prometheus 3
writes `1.0`, older stores `1`.

## Threshold alerts

| alert | severity | when | runbook |
|---|---|---|---|
| `NorthlineHighErrorRate` | page | > 5 % 5xx for 5 min (and some traffic) | [high-error-rate](alerts/high-error-rate.md) |
| `NorthlineSlowRequests` | ticket | p95 > 2 s for 10 min | [slow-requests](alerts/slow-requests.md) |
| `NorthlineEventDeadLettered` | page | a consumer dead-lettered an event | [event-dead-lettered](alerts/event-dead-lettered.md) |
| `NorthlineConsumerLag` / `…Critical` | ticket / page | lag > 1,000 for 10 min / > 10,000 for 15 min | [consumer-lag](alerts/consumer-lag.md) |
| `NorthlineOutboxBacklog` / `NorthlineOutboxStuck` | ticket / page | oldest pending publication > 5 min / > 30 min | [outbox-backlog](alerts/outbox-backlog.md) |
| `NorthlinePayoutRunStalled` | ticket / page | last successful payout run > 30 min / > 2 h ago | [payout-run](alerts/payout-run.md) |
| `NorthlinePayoutRunMissing` | ticket | no replica reports the payout run for 30 min | [payout-run](alerts/payout-run.md) |
| `NorthlineSignInFailures`, `NorthlineLockoutSpike` | ticket | half the sign-ins fail / > 20 lockouts in 15 min | [sign-in-failures](alerts/sign-in-failures.md), [lockout-spike](alerts/lockout-spike.md) |
| `NorthlinePayoutFailed` | ticket | a payout failed or was canceled | [payout-failed](alerts/payout-failed.md) |
| `NorthlineKitchensLate` | ticket | > 30 % of kitchen orders ready late | [kitchens-late](alerts/kitchens-late.md) |

## Routing (page vs ticket)

Alertmanager format, two receivers. The reference is `deploy/observability/alertmanager/alertmanager.yml`; the chart
renders the same routes from values:

```yaml
alerting:
  enabled: true
  routing:
    format: alertmanagerConfig      # | configMap | none
    page:   { provider: pagerduty } # | opsgenie | webhook | none
    ticket: { provider: webhook }
```

| provider | page | ticket | secret (secrets manager name) |
|---|---|---|---|
| `pagerduty` | Events API v2, `severity: critical`, runbook link | `severity: warning` (low urgency in the service's settings) | `ALERTING_PAGERDUTY_ROUTING_KEY` (`alerting-pagerduty-routing-key`): a service's Events API v2 integration key |
| `opsgenie` | priority `P1` | priority `P3` | `ALERTING_OPSGENIE_API_KEY` (`alerting-opsgenie-api-key`): an API integration's key; `alerting.routing.opsgenie.apiUrl` for an EU account |
| `webhook` | Alertmanager's webhook JSON to `ALERTING_PAGE_WEBHOOK_URL` | to `ALERTING_TICKET_WEBHOOK_URL` | the URLs (`alerting-page-webhook-url`, `alerting-ticket-webhook-url`) — secrets, since such URLs carry tokens (Grafana OnCall, a chat or ticketing bridge) |

Only the selected providers' keys are mapped (one ExternalSecret `northline-alerting` → Secret
`northline-alerting-secrets`; without External Secrets, keys in `secrets.existingSecret`). Terraform creates the five
secrets empty in all three clouds (with `oncall-export-token`). Group by `alertname, severity, sloth_service,
sloth_slo, application`; pages repeat hourly, tickets daily.

### Which format where

| metrics store | rules (`alerting.rules.format`) | routing (`alerting.routing.format`) |
|---|---|---|
| any cluster with the Prometheus Operator (kube-prometheus-stack on EKS / GKE / AKS / kind) | `prometheusRule` + `alerting.rules.labels` matching its `ruleSelector` | `alertmanagerConfig` + `alerting.routing.labels` for its `alertmanagerConfigSelector` |
| Google Managed Prometheus | `gmpRules` (namespaced `Rules`, `alerting.rules.interval`) | GMP's managed Alertmanager reads the Secret `alertmanager` in `gmp-public`: put the rendered `configMap` alertmanager.yml there, or run your own |
| Grafana Cloud / Mimir | `configMap`, then `mimirtool rules load` (or Grafana Alloy reading `prometheusRule`) | `configMap` → `mimirtool alertmanager load` |
| Amazon Managed Service for Prometheus | `configMap`, then `aws amp create-rule-groups-namespace` per file | `configMap` → `aws amp create-alert-manager-definition` (AMP routes to SNS; put PagerDuty/Opsgenie/webhook behind SNS) |
| Azure Monitor managed Prometheus | `configMap`, then `az-prom-rules-converter` into rule groups | Azure Monitor action groups (`none` here) |

Every alert gets `namespace` (the release's — the operator's AlertmanagerConfig only sees its own namespace) and
`environment` labels; `alerting.runbookBaseUrl` rewrites the runbook links (default: this repository on GitHub; the
internal docs site works too). `alerting.enabled=false` (the default) renders nothing: no environment has a rule
loader yet. The AppProject allows `PrometheusRule`, `AlertmanagerConfig` and GMP `Rules`.

## The on-call rota

The rota is planned in the platform console (`/on-call`, S-96, `identity.oncall_shifts`, migration V234): shifts, who
is on now, hand-overs. Its console API needs a staff session with a second factor, so a paging tool can't use it.
S-113 adds a read-only **export for machines**:

```
GET /api/v1/ops/oncall        Authorization: Bearer <ONCALL_EXPORT_TOKEN>
  → {"asOf": "...", "now": [Entry], "shifts": [Entry]}   Entry = shiftId, userId, name, email, startsAt, endsAt, duty
GET /api/v1/ops/oncall.ics    (or ?token=<ONCALL_EXPORT_TOKEN> for calendar clients that can't send a header)
  → text/calendar, one VEVENT per shift, SUMMARY = the person's email, DESCRIPTION = name · duty
```

From 12 h back to 8 days ahead. `401` without the right token; **`404` while `ONCALL_EXPORT_TOKEN` is unset** (the
default: off). The token is one shared secret (`oncall-export-token`), compared in constant time; it is not a user
session and opens nothing else (its own security chain, GET only). The JSON carries staff names and work emails —
treat the token like any other credential.

How the paging side follows the rota:

- **Grafana OnCall** (OSS or Cloud): a schedule of type *iCal* with the `.ics` URL (token in the query); OnCall maps
  each event's SUMMARY to the user with that email. Route `page` with the `webhook` provider to the OnCall
  integration's Alertmanager URL. This is the only set-up where the console rota drives paging with no extra code.
- **PagerDuty / Opsgenie**: their schedules don't import iCal. Either keep their own schedule in step by hand, or run a
  small job that reads `/api/v1/ops/oncall` and writes schedule **overrides** (PagerDuty `POST
  /schedules/{id}/overrides`, Opsgenie `POST /v2/schedules/{id}/overrides`). Not built: no account exists to test it
  against.
- A calendar app can subscribe to the `.ics` feed so people see their shifts.

The console's "Page current on-call" button (design 03) stays unbuilt: paging goes through Alertmanager, which must
work when the api itself is down — the api never sits in the paging path.

## Changing an SLO or an alert

```sh
$EDITOR deploy/observability/slo/checkout.yaml     # objective, query, annotations
make obs-slo           # Sloth → prometheus/rules/slo/*.yaml, and copies every rule file into the chart (files/alerting)
make obs-rules-check   # offline: validate-alerts.py; Sloth drift; promtool check + test rules; amtool check-config
make obs-dashboards    # if you added an SLO: the SLO dashboards (deploy/observability/grafana/dashboards.py)
deploy/helm/validate.sh
```

`make obs-rules-check` needs only Python 3 + PyYAML for `scripts/validate-alerts.py` (every alert has `severity`
page|ticket, a summary and a `runbook_url` to an existing `docs/runbooks/alerts/*.md`; no orphan runbook; SLO specs
valid and generated; chart copies identical; routing sends page/ticket to the right receivers; no province, city or
time zone). Sloth (`ghcr.io/slok/sloth:v0.12.0`), promtool (`prom/prometheus:v3.5.0`) and amtool
(`prom/alertmanager:v0.28.1`) come from `PATH`, else Docker, else are skipped with a notice. Unit tests:
`deploy/observability/prometheus/tests/northline-alerts_test.yml` (threshold alerts) and `northline-slo_test.yml`
(fast burn pages, slow burn tickets, healthy and idle stay quiet, `le="1.0"` and `le="3600.0"` match). A new alert
needs a runbook page under `alerts/`, a test, and — if it pages — a reason it can't wait for the morning.

## Dashboards

`Northline · SLO · <journey>` (uids `northline-slo-sign-in`, `-checkout`, `-payouts`, `-kds`): per SLO the objective,
the error budget left over 30 days, the burn rate now and over the period, and the SLI error ratio by window — all
from Sloth's recording rules (`slo:*`), the same numbers the alerts use. The flow dashboards gained the payments job
runs and payout delay (checkout and payouts), ticket delivery and live bus probes (kitchens), and the outbox backlog
(events). Generated by `deploy/observability/grafana/dashboards.py`.

## Local

The local Grafana LGTM stack (`make obs-up`) shows the new metrics and the dashboards; its Prometheus loads no rules.
To see the rules evaluate, use the unit tests (`make obs-rules-check`), or point a Prometheus at the OTLP metrics with
`--web.enable-otlp-receiver` and `rule_files: [deploy/observability/prometheus/rules/**/*.y*ml]`.

## Variables

| variable | app | required | default | what |
|---|---|---|---|---|
| `ONCALL_EXPORT_TOKEN` | api | no — secret | empty = the export answers 404 | the on-call export's shared token |
| `LIVE_PROBE_INTERVAL` | api | no | `30s` | how often each replica probes the live bus (KDS freshness SLO) |

Alertmanager's keys are not app variables: `ALERTING_PAGERDUTY_ROUTING_KEY`, `ALERTING_OPSGENIE_API_KEY`,
`ALERTING_PAGE_WEBHOOK_URL`, `ALERTING_TICKET_WEBHOOK_URL` (above, [secrets.md](secrets.md)).
