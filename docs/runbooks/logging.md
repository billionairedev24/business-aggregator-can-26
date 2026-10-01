# Logging: structured, centralised, redacted (S-112)

Every Spring app (api, auth, studio-bff, consumer-bff, worker) writes **one JSON object per line** on its console in
the deployed environments and sends the **same records over OTLP** to the environment's OpenTelemetry Collector
([observability.md](observability.md)), which forwards them to the log backend next to the traces. Before anything
leaves the process, **one redaction layer** (`ca.northline.platform.logging.Redactor`) masks personal data and
secrets; the Collector masks again.

```
log.warn(…)  ──▶ Logback ──┬─▶ console: ECS JSON (RedactingJsonMembersCustomizer)  ──▶ kubectl logs, the node's log agent
                           └─▶ OtlpLogAppender (redacted, with trace id) ──OTLP──▶ Collector (transform/redact) ──▶ backend
```

## Format

| | local, test | dev, staging, prod |
|---|---|---|
| console | Spring Boot's plain text (people read it), trace id in `[app,traceId,spanId]` | **ECS JSON** ([Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html)) |
| OTLP | only with `OTEL_EXPORT_ENABLED=true` (`make up OBS=1`) | on with the chart's Collector |

`LOG_FORMAT` overrides the console: `ecs` · `logstash` · `gelf` · `text`. A JSON line carries `@timestamp`,
`log.level`, `log.logger`, `process.thread.name`, `service.name` (the app), `service.environment`, `message`,
`trace.id`, `span.id`, the MDC (the worker's `consumer`, `eventType`, `eventId` — S-26) and, on errors,
`error.type`, `error.message`, `error.stack_trace`. Search by `trace.id` to see every app's lines of one request, or
jump from a trace to its logs (Grafana: Tempo → Loki; X-Ray → CloudWatch Logs; Cloud Trace → Cloud Logging;
Application Insights' transaction view).

## What is redacted

`Redactor` runs on **every string** of every record — message, MDC entries, exception message and stack trace — in
both outputs. A field whose **name** is sensitive is replaced whole (`password`, `secret`, `token`, `authorization`,
`cookie`, `code`, `otp`, `totp`, `api_key`, `client_secret`, `x-xsrf-token`, `x-dev-user`, `card_number`, `sin` …).
Everything else is scanned:

| what | example in | example out |
|---|---|---|
| email addresses | `amara.osei@example.ca` | `[EMAIL]` |
| phone numbers (North American, any punctuation; E.164) | `(587) 555-0101`, `+15875550101` | `[PHONE]` |
| card-like numbers (13–19 digits, Luhn-valid) | `4242 4242 4242 4242` | `[CARD …4242]` |
| Canadian postal codes | `T2P 1B5` | `T2P ***` (the forward sortation area stays) |
| one-time codes after "verification / sign-in / security / backup / OTP code" (en/fr) | `verification code 482913` | `verification code [CODE]` |
| bearer / basic / DPoP credentials, `Authorization` and `Cookie` echoes, JWTs | `Bearer eyJhbGci…` | `[REDACTED]` |
| known secret shapes | `sk_live_…`, `whsec_…`, `sk-or-…`, `AKIA…`, `ghp_…`, PEM private keys, `otpauth://…` | `…[REDACTED]` |
| `key=value` / `"key":"value"` with a secret-sounding key | `client_secret=abc`, `"password":"x"` | `client_secret=[REDACTED]` |

Ids stay readable (ULIDs, `po_…`, trace ids, amounts, timestamps): a number glued to letters or `_` is never touched.
The SMS adapters' own masked form (`+1 403 *** **48`) is kept. The rules and their cases:
`server/platform/src/test/java/ca/northline/platform/logging/RedactorTest.java` — add a case there before changing a
rule.

**Second line (Collector):** `transform/redact` in the chart's Collector (and the local one) rewrites log bodies and
log/span attributes with RE2 versions of the email, phone, card, postal-code, bearer and JWT rules
(`deploy/helm/northline/templates/_helpers.tpl` → `northline.redactPatterns`). It catches what an app without the
platform library (or a future one) might send. Coarser on purpose — no Luhn check, so any 13–19-digit run becomes
`[CARD]`.

**Not a licence to log PII.** Redaction is a safety net for mistakes, provider error bodies and stack traces. Log ids
(user, merchant, order), not people: CLAUDE.md's "no PII in event payloads" applies to logs too.

### The local SMS stand-in (S-20)

S-20 accepted that `SMS_PROVIDER=local` writes verification codes to the auth log on a developer's machine. S-112
keeps that **only under the `local` and `test` profiles**: anywhere else (dev may still run the fake) `LoggingSmsSender`
and the shared `LoggingSmsTransport` log that a code or text was **withheld** — never the code, the number or the
text — and start-up warns that phone verification can't be completed (set `SMS_PROVIDER=twilio` or `aws` in dev to
register). staging/prod refuse `local` altogether (S-8). Even a code that slipped into a JSON or OTLP line would be
masked (`[CODE]`), and `SmsConfigTest` / `SmsTransportsTest` prove the fake withholds them under `dev`.

## Shipping

- **OTLP (primary):** `OtlpLogAppender` (attached to the root logger when `management.logging.export.enabled`, i.e.
  `OTEL_EXPORT_ENABLED=true`) turns each event into an OTLP log record with the current trace context, the logger,
  thread, MDC and exception as attributes. Spring Boot's OTLP log exporter batches them to the Collector's
  `/v1/logs`; the Collector sends them on with the environment's exporters: Loki / Grafana Cloud (`otlphttp`),
  CloudWatch Logs (`/northline/<env>/apps`), Cloud Logging, Azure Monitor. Retention is the backend's (set it there:
  30 days dev/staging, 90 days prod is a sensible start; CloudWatch log group retention, Cloud Logging bucket
  retention, Log Analytics workspace retention).
- **Console (always):** the same redacted lines on stdout, so `kubectl logs`, `stern` and a cloud's node agent
  (CloudWatch Container Insights, GKE's logging agent, Azure Container Insights) keep working. With OTLP on, turn the
  node agent's collection of the `northline-*` namespace off or you pay twice.

Searchable: every backend indexes the ECS / OTLP fields — query `service.name`, `log.level`, `trace.id`, `eventId`,
`log.logger`.

## Operations

| task | how |
|---|---|
| change a level at runtime | `LOGGING_LEVEL_CA_NORTHLINE_<PACKAGE>=debug` on the Deployment (`apps.<app>.env`), roll out |
| read logs of one request | the `traceparent` trace id from the browser / the response → search `trace.id` |
| plain text in a deployed pod (debugging) | `LOG_FORMAT=text` on that Deployment — temporarily; the OTLP copy stays JSON-free and redacted |
| something sensitive reached the logs | add a `RedactorTest` case and rule; ask the backend to delete the affected lines (Loki `delete` API, CloudWatch: delete the log stream, Cloud Logging: bucket retention / exclusion, Log Analytics purge API); note it as an incident (privacy) |

## Variables

| variable | app | default | |
|---|---|---|---|
| `LOG_FORMAT` | api, auth, bff, worker | `ecs` under dev/staging/prod, `text` under local/test | `ecs`, `logstash`, `gelf` or `text`; anything else stops start-up |
| `OTEL_EXPORT_ENABLED` | all | `false` (chart: `true` with its Collector) | also turns the OTLP log export on ([observability.md](observability.md)) |

## Tests

- `RedactorTest` — every rule, and what must stay readable; `StructuredLogsTest` — a `dev` start writes redacted ECS
  JSON (message, MDC, exception), `local` stays text, `LOG_FORMAT` overrides; `OtlpLogAppenderTest` — the OTLP record
  is redacted and carries the current trace.
- On every app (`TracingTest` api, `AuthTelemetryTest` auth, `BffTracingTest` consumer-bff, `WorkerTracingTest`
  worker): `RedactionCheck` logs a line full of PII inside a span; the console JSON line and the exported OTLP record
  are both redacted and the record carries the span's trace id.
- `SmsConfigTest` (auth) and `SmsTransportsTest` (sms library): the local fake shows codes / texts under `local` only.
- The Collector's `transform/redact` was run against a sample record (otelcol-contrib 0.161.0): body and attributes
  came out `[EMAIL]`, `[PHONE]`, `[CARD]`, `T2P ***`, `Bearer [REDACTED]`.
