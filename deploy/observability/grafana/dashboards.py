#!/usr/bin/env python3
"""Northline Grafana dashboards as code (S-111, docs/runbooks/observability.md § Dashboards).

    python3 deploy/observability/grafana/dashboards.py          # (re)write dashboards/*.json
    python3 deploy/observability/grafana/dashboards.py --check  # fail when the committed JSON differs (CI, make)

One JSON file per service (api, auth, bff, consumer-bff, console-bff, worker) and per key flow (sign-in, checkout and payouts,
kitchens, events), plus an overview. Every query is PromQL against a Prometheus-compatible data source picked by the
`datasource` variable: the local Prometheus (docker compose --profile observability), Grafana Cloud, Amazon Managed Prometheus, Google Managed Prometheus
or Azure Monitor managed Prometheus. Metric names are the Prometheus spelling of what the apps export over OTLP
(`http.server.requests` in seconds -> http_server_requests_seconds_*, counters -> *_total); every series carries the
`application` label (management.metrics.tags.application = spring.application.name).
"""
import json
import pathlib
import sys

OUT = pathlib.Path(__file__).parent / "dashboards"
DS = {"type": "prometheus", "uid": "${datasource}"}
SERVICES = {
    "api": "northline-api",
    "auth": "northline-auth",
    "bff": "northline-studio-bff",
    "consumer-bff": "northline-consumer-bff",
    "console-bff": "northline-console-bff",  # S-90
    "worker": "northline-worker",
}


class Board:
    def __init__(self, uid, title, description, tags, variables=()):
        self.uid, self.title, self.description, self.tags = uid, title, description, tags
        self.variables = list(variables)
        self.panels = []
        self.y = 0
        self.x = 0
        self.row_h = 0

    def row(self, title):
        self._newline()
        self.panels.append({"type": "row", "title": title, "collapsed": False, "panels": [],
                            "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}})
        self.y += 1
        return self

    def _newline(self):
        if self.x:
            self.y += self.row_h
            self.x, self.row_h = 0, 0

    def panel(self, kind, title, targets, unit="short", w=8, h=8, description="", stack=False, thresholds=None):
        if self.x + w > 24:
            self._newline()
        panel = {
            "type": kind, "title": title, "description": description, "datasource": DS,
            "gridPos": {"x": self.x, "y": self.y, "w": w, "h": h},
            "targets": [{"refId": chr(65 + i), "datasource": DS, "expr": expr, "legendFormat": legend}
                        for i, (expr, legend) in enumerate(targets)],
            "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
            "options": {},
        }
        if kind == "timeseries":
            panel["fieldConfig"]["defaults"]["custom"] = {"fillOpacity": 10, "lineWidth": 1,
                                                          "stacking": {"mode": "normal" if stack else "none"}}
            panel["options"] = {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}
        if kind == "stat":
            panel["options"] = {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "area"}
        if thresholds:
            panel["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": thresholds}
        self.panels.append(panel)
        self.x += w
        self.row_h = max(self.row_h, h)
        return self

    def ts(self, title, targets, **kw):
        return self.panel("timeseries", title, targets, **kw)

    def stat(self, title, expr, unit="short", w=6, h=4, **kw):
        return self.panel("stat", title, [(expr, "")], unit=unit, w=w, h=h, **kw)

    def json(self):
        for i, p in enumerate(self.panels, start=1):
            p["id"] = i
        templating = [{
            "name": "datasource", "label": "Data source", "type": "datasource", "query": "prometheus",
            "current": {"text": "Prometheus", "value": "prometheus"}, "hide": 0,
        }] + self.variables
        return {
            "uid": self.uid, "title": self.title, "description": self.description, "tags": ["northline"] + self.tags,
            "schemaVersion": 41, "version": 1, "editable": True, "graphTooltip": 1, "timezone": "browser",
            "time": {"from": "now-6h", "to": "now"}, "refresh": "1m",
            "templating": {"list": templating},
            "links": [{"title": "Northline", "type": "dashboards", "tags": ["northline"], "asDropdown": True}],
            "annotations": {"list": []},
            "panels": self.panels,
        }


def rate(metric, labels, by="", window="$__rate_interval"):
    grouped = f"sum by ({by}) " if by else "sum "
    return f"{grouped}(rate({metric}{{{labels}}}[{window}]))"


def quantile(q, metric, labels, by="le"):
    return f"histogram_quantile({q}, sum by ({by}) (rate({metric}_bucket{{{labels}}}[$__rate_interval])))"


GREEN_RED = [{"color": "green", "value": None}, {"color": "red", "value": 1}]


def service_board(key, app):
    sel = f'application="{app}"'
    http = "http_server_requests_seconds"
    b = Board(f"northline-{key}", f"Northline · {key}",
              f"{app}: requests (rate, errors, duration), outgoing calls, JVM, database pool. Traces: Explore → Tempo "
              f"/ X-Ray / Cloud Trace with service.name={app}.", ["service", key])
    b.row("Requests")
    b.stat("Requests / s", rate(http + "_count", sel), unit="reqps")
    errors = rate(http + "_count", sel + ',status=~"5.."')
    b.stat("5xx ratio", f"{errors} / {rate(http + '_count', sel)}",
           unit="percentunit", thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 0.01}])
    b.stat("p95 latency", quantile(0.95, http, sel), unit="s")
    b.stat("p99 latency", quantile(0.99, http, sel), unit="s")
    b.ts("Requests by status", [(rate(http + "_count", sel, "status"), "{{status}}")], unit="reqps", w=12, stack=True)
    b.ts("Latency p50 / p95 / p99", [(quantile(0.5, http, sel), "p50"), (quantile(0.95, http, sel), "p95"),
                                     (quantile(0.99, http, sel), "p99")], unit="s", w=12)
    b.ts("Slowest routes (p95)", [(f"topk(10, {quantile(0.95, http, sel, 'le, uri')})", "{{uri}}")], unit="s", w=12)
    b.ts("Errors by route", [(rate(http + "_count", sel + ',outcome=~"SERVER_ERROR|CLIENT_ERROR"', "uri, status"),
                              "{{status}} {{uri}}")], unit="reqps", w=12)
    b.row("Outgoing calls")
    b.ts("HTTP client p95 by host", [(quantile(0.95, "http_client_requests_seconds", sel, "le, client_name"),
                                      "{{client_name}}")], unit="s", w=12)
    b.ts("HTTP client errors", [(rate("http_client_requests_seconds_count", sel + ',outcome!="SUCCESS"',
                                      "client_name, status"), "{{client_name}} {{status}}")], unit="reqps", w=12)
    if key in ("api", "auth", "worker"):
        b.ts("Database pool", [(f'sum(hikaricp_connections_active{{{sel}}})', "active"),
                               (f'sum(hikaricp_connections_pending{{{sel}}})', "pending"),
                               (f'sum(hikaricp_connections_max{{{sel}}})', "max")], w=12)
        b.ts("Connection wait p95", [(quantile(0.95, "hikaricp_connections_acquire_seconds", sel), "acquire")],
             unit="s", w=12)
    b.row("JVM")
    b.ts("Heap used", [(f'sum by (id) (jvm_memory_used_bytes{{{sel},area="heap"}})', "{{id}}")], unit="bytes",
         stack=True)
    b.ts("GC pause", [(rate("jvm_gc_pause_seconds_sum", sel, "action"), "{{action}}")], unit="s")
    b.ts("CPU", [(f'avg(process_cpu_usage{{{sel}}})', "process"), (f'avg(system_cpu_usage{{{sel}}})', "system")],
         unit="percentunit")
    return b


def overview():
    b = Board("northline-overview", "Northline · overview",
              "Every service at a glance, and the business flows. Drill down from here.", ["overview"])
    b.row("Services")
    http = "http_server_requests_seconds"
    b.ts("Requests / s by service", [(rate(http + "_count", 'application=~"northline-.*"', "application"),
                                       "{{application}}")], unit="reqps", w=12, stack=True)
    b.ts("5xx / s by service", [(rate(http + "_count", 'application=~"northline-.*",status=~"5.."', "application"),
                                 "{{application}}")], unit="reqps", w=12)
    b.ts("p95 by service", [(quantile(0.95, http, 'application=~"northline-.*"', "le, application"),
                             "{{application}}")], unit="s", w=12)
    b.ts("Dead-lettered events", [(rate("northline_events_dead_lettered_total", "", "consumer"), "{{consumer}}")],
         w=12)
    b.row("Business")
    b.stat("Sign-ins / h", f'sum(increase(northline_auth_sign_ins_total{{outcome="succeeded"}}[1h]))')
    b.stat("Checkouts / h", "sum(increase(northline_checkouts_total[1h]))")
    b.stat("Payouts failed (24 h)", 'sum(increase(northline_payouts_total{outcome!="sent"}[24h]))',
           thresholds=GREEN_RED)
    b.stat("Kitchen prep p95", quantile(0.95, "northline_kds_prep_seconds", ""), unit="s")
    return b


def sign_in():
    b = Board("northline-sign-in", "Northline · sign-in", "northline-auth: sign-ins by method and second factor, "
              "failures, rate-limit lockouts (S-9). Counts only — no user in any label.", ["flow", "auth"])
    b.ts("Sign-ins by method", [(rate("northline_auth_sign_ins_total", 'outcome="succeeded"', "method, mfa"),
                                 "{{method}} mfa={{mfa}}")], unit="ops", w=12, stack=True)
    b.ts("Failures by method", [(rate("northline_auth_sign_ins_total", 'outcome="failed"', "method"), "{{method}}")],
         unit="ops", w=12)
    failed = rate("northline_auth_sign_ins_total", 'outcome="failed"')
    b.ts("Failure ratio", [(f"{failed} / {rate('northline_auth_sign_ins_total', '')}", "failed / all")],
         unit="percentunit",
         w=12)
    b.ts("Lockouts by action", [(rate("northline_auth_lockouts_total", "", "action"), "{{action}}")], unit="ops",
         w=12)
    b.ts("Sign-in API p95", [(quantile(0.95, "http_server_requests_seconds",
                                       'application="northline-auth",uri=~"/api/auth/sign-in.*"', "le, uri"),
                              "{{uri}}")], unit="s", w=12)
    b.ts("Token endpoint p95", [(quantile(0.95, "http_server_requests_seconds",
                                          'application="northline-auth",uri="/oauth2/token"'), "/oauth2/token")],
         unit="s", w=12)
    return b


def checkout():
    b = Board("northline-checkout-payouts", "Northline · checkout and payouts",
              "Escrow holds opened at checkout (Stripe PaymentIntents, manual capture) and payouts to merchants.",
              ["flow", "payments"])
    b.ts("Checkouts by status", [(rate("northline_checkouts_total", "", "status"), "{{status}}")], unit="ops",
         w=12, stack=True)
    b.ts("Checkouts by kind", [(rate("northline_checkouts_total", "", "ref_type"), "{{ref_type}}")], unit="ops",
         w=12, stack=True)
    b.ts("Stripe calls p95", [(quantile(0.95, "http_client_requests_seconds",
                                        'application="northline-api",client_name=~".*stripe.*"', "le, uri"),
                               "{{uri}}")], unit="s", w=12)
    b.ts("Payouts", [(rate("northline_payouts_total", "", "outcome"), "{{outcome}}")], unit="ops", w=12)
    b.ts("Payout amounts (CAD / h)", [(f'sum by (outcome) (increase(northline_payouts_amount_dollars_sum[1h]))',
                                       "{{outcome}}")], unit="currencyUSD", w=12,
         description="Dollars (CAD) paid out per hour; the unit symbol is Grafana's.")
    # S-113
    b.ts("Payments job runs", [(rate("northline_jobs_runs_total", 'task=~"payments.*"', "task, outcome"),
                                "{{task}} {{outcome}}")], unit="ops", w=12,
         description="Alerts: NorthlinePayoutRunFailureBurn (SLO), NorthlinePayoutRunStalled")
    b.ts("Since the last successful run", [('time() - max by (task) (northline_jobs_last_success_seconds)', "{{task}}")],
         unit="s", w=12)
    b.ts("Scheduled payout delay after the payout time", [(quantile(0.5, "northline_payouts_delay_seconds", ""), "p50"),
                                                          (quantile(0.99, "northline_payouts_delay_seconds", ""),
                                                           "p99")], unit="s", w=24,
         description="SLO: 99 % within 1 h (NorthlinePayoutTimelinessBurn)")
    return b


def kitchens():
    b = Board("northline-kitchens", "Northline · kitchens (KDS)",
              "Kitchen display latency: promised prep, accepted → ready, ready → handed off.", ["flow", "food"])
    b.ts("Prep time (accepted → ready)", [(quantile(0.5, "northline_kds_prep_seconds", ""), "p50"),
                                          (quantile(0.95, "northline_kds_prep_seconds", ""), "p95")], unit="s", w=12)
    late = rate("northline_kds_prep_seconds_count", 'late="true"')
    b.ts("Late share", [(f"{late} / {rate('northline_kds_prep_seconds_count', '')}", "late")], unit="percentunit",
         w=12)
    b.ts("Hand-off wait (ready → handed off)", [(quantile(0.95, "northline_kds_handoff_wait_seconds", "",
                                                           "le, mode"), "p95 {{mode}}")], unit="s", w=12)
    b.ts("Promised minutes (average)", [("sum(rate(northline_kds_promised_minutes_sum[$__rate_interval])) / "
                                         "sum(rate(northline_kds_promised_minutes_count[$__rate_interval]))",
                                         "promised")], unit="m", w=12)
    b.ts("Kitchen API p95", [(quantile(0.95, "http_server_requests_seconds",
                                       'application="northline-api",uri=~".*/kitchen/.*"', "le, uri"), "{{uri}}")],
         unit="s", w=24)
    # S-113
    b.ts("Ticket delivery (placed → kitchen's bus)", [(quantile(0.5, "northline_kds_ticket_delivery_seconds", ""), "p50"),
                                                      (quantile(0.99, "northline_kds_ticket_delivery_seconds", ""),
                                                       "p99")], unit="s", w=12,
         description="SLO: 99.5 % within 5 s (NorthlineKdsTicketDeliveryBurn)")
    b.ts("Live bus probes", [(rate("northline_studio_live_probe_seconds_count", "", "outcome"), "{{outcome}}"),
                             (quantile(0.99, "northline_studio_live_probe_seconds", 'outcome="delivered"'),
                              "p99 round trip")], w=12,
         description="Freshness SLO: 99.5 % back within 2 s (NorthlineKdsFreshnessBurn)")
    return b


def events():
    b = Board("northline-events", "Northline · events (Kafka)",
              "The worker's consumers (S-26): outcomes, lag, dead letters; the producers' sends.", ["flow", "kafka"])
    b.ts("Consumed by outcome", [(rate("northline_events_consumed_total", "", "outcome"), "{{outcome}}")],
         unit="ops", w=12, stack=True)
    b.ts("Consumed by consumer", [(rate("northline_events_consumed_total", "", "consumer"), "{{consumer}}")],
         unit="ops", w=12, stack=True)
    b.ts("Consumer lag (max records)", [("max by (client_id) (kafka_consumer_fetch_manager_records_lag_max)",
                                         "{{client_id}}")], w=12)
    b.ts("Dead-lettered", [(f'sum by (consumer, topic) (increase(northline_events_dead_lettered_total[1h]))',
                            "{{consumer}} {{topic}}")], w=12, description="Alert: NorthlineEventDeadLettered")
    b.ts("Listener p95", [(quantile(0.95, "spring_kafka_listener_seconds", "", "le, spring_kafka_listener_id"),
                           "{{spring_kafka_listener_id}}")], unit="s", w=12)
    b.ts("Producer sends", [(rate("spring_kafka_template_seconds_count", "", "application, spring_kafka_template_name"),
                             "{{application}}")], unit="ops", w=12)
    # S-113: the transactional outbox (Modulith event publication registry) of the api and auth
    b.ts("Outbox: pending publications", [("max by (application) (northline_events_outbox_pending)",
                                           "{{application}}")], w=12)
    b.ts("Outbox: oldest pending", [("max by (application) (northline_events_outbox_oldest_age_seconds)",
                                     "{{application}}")], unit="s", w=12,
         description="Alerts: NorthlineOutboxBacklog (5 min), NorthlineOutboxStuck (30 min)")
    return b


def ai():
    """S-129/S-130: the LlmClient port (ObservedLlmClient) — calls, outcome, latency, tokens and cost per call."""
    sel = 'application="northline-api"'
    calls = "northline_ai_completion_seconds"
    b = Board("northline-ai", "Northline · AI",
              "Model calls through the LlmClient port: outcome, latency, tokens and cost (OpenRouter's own figure), by "
              "feature and model. Per-business spend: the ai.usage table (docs/runbooks/ai.md).", ["ai", "flow"])
    b.row("Spend")
    b.stat("Spend today (USD)", f"sum(increase(northline_ai_cost_usd_total{{{sel}}}[24h]))", unit="currencyUSD")
    b.stat("Cost per call (USD)", f"{rate('northline_ai_cost_usd_total', sel)} / {rate(calls + '_count', sel)}",
           unit="currencyUSD")
    b.stat("Calls / min", f"{rate(calls + '_count', sel)} * 60")
    failed = rate(calls + "_count", sel + ',outcome!="ok"')
    b.stat("Error share", f"{failed} / {rate(calls + '_count', sel)}",
           unit="percentunit", thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 0.1}])
    b.ts("Cost per call by feature", [(f"{rate('northline_ai_cost_usd_total', sel, 'feature')} / "
                                       f"{rate(calls + '_count', sel, 'feature')}", "{{feature}}")],
         unit="currencyUSD", w=12)
    b.ts("Spend by feature and model (USD/h)", [(f"{rate('northline_ai_cost_usd_total', sel, 'feature, model')} * 3600",
                                                 "{{feature}} · {{model}}")], unit="currencyUSD", w=12, stack=True)
    b.row("Calls")
    b.ts("Calls by outcome", [(f"{rate(calls + '_count', sel, 'outcome')} * 60", "{{outcome}}")], w=12, stack=True)
    b.ts("Latency p95 by feature", [(quantile(0.95, calls, sel, "le, feature"), "{{feature}}")], unit="s", w=12)
    b.ts("Tokens / min", [(f"{rate('northline_ai_tokens_total', sel, 'kind')} * 60", "{{kind}}")], w=12, stack=True)
    b.ts("Tokens / min by feature", [(f"{rate('northline_ai_tokens_total', sel, 'feature')} * 60", "{{feature}}")],
         w=12, stack=True)
    return b


def hypercare():
    """S-118: the 14 days after a market launches (docs/runbooks/go-live.md § Hypercare). Market-level activity comes from
    northline_market_activity_total (label `market` = the region market id); checkout, sign-in and payout health is
    platform-wide (those metrics carry no place) — while one market is live, that is the market."""
    market = {"name": "market", "label": "Market", "type": "query", "datasource": DS, "refresh": 2, "sort": 1,
              "definition": "label_values(northline_market_activity_total, market)",
              "query": {"query": "label_values(northline_market_activity_total, market)", "refId": "market"},
              "current": {}, "hide": 0, "includeAll": False, "multi": False}
    sel = 'market="$market"'
    act = "northline_market_activity_total"
    b = Board("northline-hypercare", "Northline · hypercare (market launch)",
              "The first 14 days of a market: its orders, bookings, payouts and support tickets (market label), "
              "platform checkout errors, sign-in failures and paging alerts, and the pilot group's UAT feedback (S-121). "
              "Daily stand-up: docs/runbooks/go-live.md § Hypercare.", ["hypercare", "flow"], variables=[market])
    b.row("The market today")
    for title, kind in (("Orders (24 h)", "order"), ("Bookings (24 h)", "booking"),
                        ("Support tickets (24 h)", "support_ticket"), ("Payouts failed (24 h)", "payout_failed")):
        b.stat(title, f'sum(increase({act}{{{sel},kind="{kind}"}}[24h])) or vector(0)',
               thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}] if kind == "payout_failed" else None)
    b.ts("Market activity / h", [(f"sum by (kind) (increase({act}{{{sel}}}[1h]))", "{{kind}}")], w=24, stack=True)
    b.row("Health (platform)")
    failed = rate("northline_checkouts_total", 'status="failed"')
    b.ts("Checkout errors", [(failed, "failed / s"),
                             (f"{failed} / {rate('northline_checkouts_total', '')}", "failed share")], w=8)
    b.ts("Sign-in failures", [(rate("northline_auth_sign_ins_total", 'outcome="failed"', "method"), "{{method}}")],
         unit="ops", w=8)
    b.ts("Payouts", [(rate("northline_payouts_total", "", "outcome"), "{{outcome}}")], unit="ops", w=8)
    b.stat("Paging alerts firing", 'count(ALERTS{alertstate="firing",severity="page"}) or vector(0)',
           thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])
    b.stat("Checkout SLO budget left", 'min(slo:period_error_budget_remaining:ratio{sloth_service="northline-checkout"})',
           unit="percentunit", thresholds=BUDGET)
    b.stat("Sign-in SLO budget left", 'min(slo:period_error_budget_remaining:ratio{sloth_service="northline-sign-in"})',
           unit="percentunit", thresholds=BUDGET)
    b.stat("Payouts SLO budget left", 'min(slo:period_error_budget_remaining:ratio{sloth_service="northline-payouts"})',
           unit="percentunit", thresholds=BUDGET)
    b.row("Feedback")
    b.stat("UAT feedback today", "max(northline_uat_feedback_reported)")
    b.stat("UAT blocking open", "max(northline_uat_blocking_open)",
           thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])
    b.ts("UAT feedback and blocking items", [("max(northline_uat_feedback_reported)", "reported today"),
                                             ("max(northline_uat_blocking_open)", "blocking open")], w=12)
    b.ts("Support tickets / h (market)", [(f'sum(increase({act}{{{sel},kind="support_ticket"}}[1h]))', "tickets")], w=12)
    return b


# S-113: one dashboard per SLO service (deploy/observability/slo, docs/runbooks/alerting.md) over the recording rules
# Sloth generates (slo:*), so it reads the same numbers the burn-rate alerts use. (service, title, [(slo, what)]).
SLOS = [
    ("sign-in", "sign-in", [("availability", "no 5xx · 99.9 %"), ("latency", "≤ 1 s · 99 %")]),
    ("checkout", "checkout", [("availability", "no 5xx · 99.9 %"), ("latency", "≤ 2.5 s · 99 %")]),
    ("payouts", "payouts", [("run-success", "runs succeed · 99 %"), ("timeliness", "≤ 1 h late · 99 %")]),
    ("kds", "kitchen display (KDS)", [("ticket-delivery", "on the bus ≤ 5 s · 99.5 %"),
                                      ("freshness", "live bus ≤ 2 s · 99.5 %")]),
]
BUDGET = [{"color": "red", "value": None}, {"color": "orange", "value": 0}, {"color": "green", "value": 0.25}]
BURN = [{"color": "green", "value": None}, {"color": "orange", "value": 1}, {"color": "red", "value": 6}]


def slo_board(service, title, slos):
    b = Board(f"northline-slo-{service}", f"Northline · SLO · {title}",
              f"S-113 SLOs of {title} over 30 days: error budget left, burn rates, the SLI by window. Alerts: page at "
              "14.4x (1 h) / 6x (6 h) burn, ticket at 3x (1 d) / 1x (3 d) — docs/runbooks/alerting.md.",
              ["slo", service])
    for slo, what in slos:
        sel = f'sloth_service="northline-{service}",sloth_slo="{slo}"'
        b.row(f"{slo} — {what}")
        b.stat("Objective", f"max(slo:objective:ratio{{{sel}}})", unit="percentunit")
        b.stat("Error budget left (30 d)", f"max(slo:period_error_budget_remaining:ratio{{{sel}}})",
               unit="percentunit", thresholds=BUDGET)
        b.stat("Burn rate now (1 h)", f"max(slo:current_burn_rate:ratio{{{sel}}})", unit="x", thresholds=BURN)
        b.stat("Burn rate over 30 d", f"max(slo:period_burn_rate:ratio{{{sel}}})", unit="x", thresholds=BURN)
        b.ts("SLI error ratio by window", [(f"max(slo:sli_error:ratio_rate{w}{{{sel}}})", w)
                                           for w in ("5m", "1h", "6h", "1d", "30d")]
             + [(f"max(slo:error_budget:ratio{{{sel}}})", "budget (1 - objective)")], unit="percentunit", w=12)
        b.ts("Burn rate (1 = budget gone in exactly 30 d)",
             [(f"max(slo:sli_error:ratio_rate{w}{{{sel}}}) / max(slo:error_budget:ratio{{{sel}}})", f"{w}")
              for w in ("1h", "6h", "1d", "3d")], unit="x", w=12)
    return b


def boards():
    yield overview()
    for key, app in SERVICES.items():
        yield service_board(key, app)
    yield sign_in()
    yield checkout()
    yield kitchens()
    yield events()
    yield ai()
    yield hypercare()
    for service, title, slos in SLOS:
        yield slo_board(service, title, slos)


def main(check):
    OUT.mkdir(exist_ok=True)
    stale = []
    expected = set()
    for board in boards():
        path = OUT / f"{board.uid}.json"
        expected.add(path.name)
        text = json.dumps(board.json(), indent=2, ensure_ascii=False) + "\n"
        if check:
            if not path.exists() or path.read_text() != text:
                stale.append(path.name)
        else:
            path.write_text(text)
    extra = {p.name for p in OUT.glob("*.json")} - expected
    if check and (stale or extra):
        print("Dashboards out of date: " + ", ".join(sorted(stale + list(extra)))
              + " — run python3 deploy/observability/grafana/dashboards.py", file=sys.stderr)
        return 1
    for name in extra if not check else ():
        (OUT / name).unlink()
    print(f"{len(expected)} dashboards {'up to date' if check else 'written'} in {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main("--check" in sys.argv))
