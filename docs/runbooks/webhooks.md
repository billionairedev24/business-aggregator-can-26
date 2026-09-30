# Partner webhooks (S-33)

A business adds HTTPS endpoints in **Studio › Settings › API & integrations › Webhooks**, picks event types, and the
worker delivers those events to them, signed. Part 1 is what an integrator needs; part 2 is how it works and how to
run it. Code: `server/worker` `ca.northline.worker.webhooks`, api `ca.northline.developer`.

## Part 1 — for integrators

### Request

`POST <your URL>` with `Content-Type: application/json; charset=UTF-8` and:

| header | value |
|---|---|
| `Northline-Signature` | `t=<unix seconds>,v1=<hex HMAC-SHA256>[,v1=…]` (below) |
| `Northline-Event-Id` | the event id (ULID) — the same on every retry and resend: **deduplicate on it** |
| `Northline-Event-Type` | e.g. `booking.completed` |
| `Northline-Delivery-Id` | this delivery (a resend has a new one) |
| `Northline-Delivery-Attempt` | 1, 2, … |
| `User-Agent` | `Northline-Webhooks/1.0 (+https://northline.ca/developers/webhooks)` |

Body (version 1; the JSON Schemas are the contract: [`docs/spec/webhooks/<type>.v1.schema.json`](../spec/webhooks)):

```json
{ "id": "01JA2Q7K3M9V0Z6X4C8B1N5D2F", "type": "booking.completed", "version": 1,
  "createdAt": "2026-10-02T15:00:00Z", "merchantId": "01J9ZD3V00000000000000PWM1",
  "data": { "bookingId": "01JA2Q…", "completedBy": "01J9ZD3V000000000000000JAS", "photoCount": 3 } }
```

| type | when | `data` |
|---|---|---|
| `booking.completed` | a team member marked a job complete | `bookingId`, `completedBy` (team member's user id), `photoCount` |
| `payment.released` | escrow for a job / order line moved to your balance | `escrowId`, `reference {type: booking\|order_line, id}`, `grossCents`, `feeCents`, `netCents`, `currency` (`CAD`) |
| `refund.issued` | a refund was paid back to the customer | `refundId`, `caseNumber` (`RF-…` or null), `escrowId` (or null), `amountCents`, `currency`, `chargedTo` (`merchant`\|`platform`) |
| `webhook.test` | "Send test event" in the endpoint's Deliveries drawer | `endpointId` |
| `booking.confirmed`, `order.placed`, `order.delivered`, `review.created` | subscribable, **not sent yet**: their domain events aren't published on Kafka yet (see DECISIONS § S-33) | — |

Payloads carry ids and amounts your business already sees in the Studio — never customer names, contact details,
addresses or customer ids. Look details up through the API with a key (Settings › API keys).

**Versioning.** New optional fields may appear in `data` within a version (ignore what you don't know); removing or
renaming a field, making one required or narrowing a type ships as `version: 2` alongside the old one.

### Verifying the signature (Stripe's scheme)

1. Split `Northline-Signature` on `,`; take `t` and every `v1`.
2. Compute `HMAC-SHA256(key = your whole secret "whsec_…" as UTF-8, message = t + "." + raw request body)`, hex.
3. Accept if it equals **any** `v1` (constant-time compare) and `t` is within 5 minutes of your clock.

During a secret rotation there are two `v1` values (new and old secret) for the overlap the owner chose (24 h by
default, up to 7 days, or none). Verify against the raw bytes — don't re-serialise the JSON. Reference implementation:
`WebhookSigner.verify` (server/worker).

```python
import hmac, hashlib, time
def verify(header: str, body: bytes, secret: str, tolerance=300) -> bool:
    parts = [p.split("=", 1) for p in header.split(",") if "=" in p]
    t = next((v for k, v in parts if k == "t"), None)
    if t is None or abs(time.time() - int(t)) > tolerance:
        return False
    expected = hmac.new(secret.encode(), f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return any(hmac.compare_digest(expected, v) for k, v in parts if k == "v1")
```

### Answering, retries, turning off

- Answer **2xx within 10 seconds** (15 s total); do the work afterwards. Anything else — 3xx (redirects are not
  followed), 4xx, 5xx, timeout, TLS or connection error — is a failed attempt.
- Retries: 30 s, 90 s, 4.5 min, 13.5 min, 40 min, 2 h, 6 h, then every 12 h (± 10 %) — 13 attempts over about
  2.9 days, then the delivery is **failed**. Delivery is at-least-once and **not ordered**; dedupe on the event id.
- An endpoint with no successful delivery for **3 days** and at least 10 failed attempts in a row is **turned off**:
  queued deliveries fail, new events aren't queued, the owners get an email ("We turned off a webhook endpoint…").
  The owner turns it back on in the Studio and resends what they need.
- The Deliveries drawer lists the last 50 deliveries (30 days kept) with every attempt, its status or error and the
  first 1,000 characters of your answer; the owner can **Resend** a finished delivery (same event id and body, new
  delivery id and signature) or **Send test event**.

## Part 2 — design and operations

### Flow

```
api (outbox) ─► Kafka booking.booking / payments.escrow / payments.refund
                    │ consumer group `webhooks` (S-26 framework: schema check, dedupe, retry topics, .dlq)
                    ▼
      WebhookFanOut: event → public payload (WebhookPayloads, validated against docs/spec/webhooks)
                     → one `developer.webhook_deliveries` row per active subscribed endpoint (state pending)
                    │  (same DB transaction as the dedupe claim; no HTTP on the Kafka thread)
                    ▼
      WebhookDispatcher (every second, every replica):
        take ≤ 64 endpoints with something due and no lease → lease row on the endpoint (2 min, renewed)
        → one virtual thread per endpoint, one request in flight per endpoint, ≤ 20 deliveries per lease
        → per attempt, one transaction: webhook_attempts row, delivery state / next_attempt_at,
          endpoint health (failing_since, consecutive_failures), maybe turn it off (+ audit_log `webhook.disabled`)
      WebhookDisabledNotices (every minute): email the owners of endpoints turned off (S-27 Notifier, email only)
```

**Why per endpoint, not per partition:** a partner's slow or dead endpoint would otherwise block every event behind
it on the Kafka partition (other merchants' included). The consumer only writes rows; the dispatcher leases
endpoints, so a slow endpoint occupies one virtual thread and delays only its own queue. A replica that dies keeps
its leases for at most 2 minutes; the pending delivery is then sent again (at-least-once).

### Secrets

The api creates `whsec_…`, shows it once and stores it AES-256-GCM encrypted (`secret_enc`,
`platform.WebhookSecretBox`, key `WEBHOOK_SECRET_KEY`). The worker decrypts with the **same key** — both apps need it.
Rotation keeps the previous ciphertext in `secret_prev_enc` until `secret_prev_until`. Don't rotate
`WEBHOOK_SECRET_KEY` itself without re-encrypting both columns ([secrets.md](secrets.md)).

### SSRF rules (worker `EgressPolicy`, `HttpWebhookTransport`)

- https only; no credentials in the URL. `WEBHOOKS_ALLOW_LOCAL=true` (local only; the cloud profiles refuse to start
  with it) allows `http://` and loopback — nothing else.
- The host is resolved by the client's own resolver, **every** address is checked, and the connection uses exactly
  the checked addresses (no second lookup: DNS rebinding can't switch targets). IP literals are checked too. TLS
  still verifies the certificate against the host name.
- Refused: loopback, 0.0.0.0/8, RFC 1918, 100.64/10 (CGNAT, Alibaba metadata), 169.254/16 (link-local; AWS / GCP /
  Azure metadata 169.254.169.254, ECS 169.254.170.2), 192.0.0/24, documentation and benchmarking ranges, multicast,
  reserved, broadcast; IPv6 `::`, `::1`, fe80::/10, fec0::/10, fc00::/7 (incl. AWS `fd00:ec2::254`), ff00::/8,
  2001:db8::/32, and IPv4-mapped / NAT64 / 6to4 addresses embedding a refused IPv4.
- No redirects, no automatic retries, no cookies, no proxy from the environment (a proxy would resolve the name).
  Timeouts: connect 5 s, read 10 s, total 15 s. The answer is read up to 64 KiB, then the connection is dropped.
- A refusal is a failed attempt with the reason in the log ("refused: … resolves to 10.0.0.8 (private network)");
  it is retried like any failure (DNS may change) and counts toward turning the endpoint off.
- Egress: the chart's NetworkPolicy leaves egress open; if you restrict it, the worker needs 443 to the internet.

### Variables (worker)

| variable | default | |
|---|---|---|
| `WEBHOOK_SECRET_KEY` | dev key locally; **required** in dev/staging/prod | the api's key |
| `WEBHOOKS_ALLOW_LOCAL` | `false` | local only |
| `WEBHOOKS_MAX_IN_FLIGHT` | `64` | endpoints at once per replica |
| `WEBHOOKS_CONNECT_TIMEOUT`, `WEBHOOKS_RESPONSE_TIMEOUT`, `WEBHOOKS_TOTAL_TIMEOUT` | `5s`, `10s`, `15s` | |
| `WEBHOOKS_DISABLE_AFTER` | `3d` | with ≥ 10 failures in a row |
| `WEBHOOKS_LOG_RETENTION` | `30d` | nightly purge 03:37 Edmonton |

### Watching it

- Metrics: `northline_webhooks_deliveries_total{type,outcome=succeeded|retrying|failed}`,
  `northline_webhooks_disabled_total` (alert on any increase — a merchant's integration broke), plus the S-26 consumer
  metrics for group `webhooks` (lag, `northline_events_dead_lettered_total{consumer="webhooks"}`).
- Logs: `WEBHOOK-DISABLED endpoint … of merchant …` (WARN), per-attempt failures at INFO with the event id in the MDC.
- Backlog: `select count(*), min(next_attempt_at) from developer.webhook_deliveries where state = 'pending'`.
- A `.dlq` record for group `webhooks` means the event couldn't be queued (poison, or a payload failing its public
  schema — a mapping bug): fix, then `./gradlew :worker:dlqReplay --args='replay --topic=<t>.dlq --group=webhooks'`
  ([events.md](events.md)).

### Not run against real partner endpoints

Everything is tested against WireMock receivers (signature, retries and back-off with a moved clock, auto-disable and
the owners' email, per-endpoint isolation, resend and test events, rotation overlap) and against address fixtures for
the SSRF rules. No real partner has received a delivery yet.
