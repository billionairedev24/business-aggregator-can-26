# Runbook — domain events: consumers, retries, DLQ and replay (S-25, S-26)

How an event travels from the api to the worker's consumers, what happens when a consumer fails, how you are
alerted, and how to replay a dead-lettered event. Topics and their provisioning: [infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials).

## 1. The path of an event

1. A module publishes a `DomainEvent` inside its transaction; the Modulith registry (`events.event_publication`) is
   the outbox. After commit, `@Externalized("<module>.<aggregate>::…")` events go to Kafka. northline-auth does the
   same for `user.registered` (outbox `auth.event_publication`, topic `identity.user`, S-28).
2. **Wire format** (ARCHITECTURE.md § Event management): key = aggregate id (per-aggregate ordering); value = the
   event record's JSON (ids only, no personal data), valid against
   `server/api/src/main/resources/events/<type>.v<version>.schema.json`; headers:

   | header | example | from |
   |---|---|---|
   | `nl-event-id` | `01J9ZD3V…` (ULID, = payload `eventId`) | platform `EventHeaders`, used by the api and northline-auth |
   | `nl-event-type` | `payments.payout_failed` (`<topic module>.<snake_case record>`, or `@EventType`) | same |
   | `nl-event-version` | `1` (`EnvelopedEvent.version()`; the api's `DomainEvent` extends it) | same |
   | `traceparent` | W3C trace context | the api's Kafka template observation, when tracing is on (not set by northline-auth) |

   Each producer declares `EventHeaders.externalization("<base package>")` as its `EventExternalizationConfiguration`
   bean, and its externalized events implement `EnvelopedEvent`. `:event-contracts`' `EnvelopeContractTest` parses
   every producer's `@Externalized` events through the worker's `EnvelopeParser`.

   A breaking payload change = a new version and a new schema file; consumers handle each version they know.
3. The worker's consumer groups (`deploy/kafka/topics.yaml` § consumers) read the topic. Each listener hands the
   record to `EventProcessing` (`ca.northline.worker.events`):
   - **parse** the headers and JSON and **validate** the payload against its schema (`EventSchemas`, the api's schema
     files packaged into the worker). Missing headers, not JSON, unknown type/version, a schema violation or an id
     that differs from the header = **poison** → straight to the `.dlq`, no retries;
   - **dedupe + handle** in one database transaction: insert `(consumer group, event id)` into
     `events.processed_events` (primary key) — a duplicate (redelivery, outbox republish, DLQ replay) finds the row and
     is skipped; a handler exception rolls the row back with the handler's own writes, so the retry runs it again;
   - **log** with `[consumer|type|eventId|traceId]` on every line and **count** the outcome.
4. **Retries** (non-blocking): the record goes to `<topic>.<group>.retry-0`, `-1`, … with the group's delays
   (search indexer: 10 s, 60 s, 5 min), then to **`<topic>.dlq`**. Each group has its own retry topics; the DLQ is
   shared, and the header `kafka_dlt-original-consumer-group` says which group gave up.

`events.processed_events` is also where the api's S-13 `Mailer` and the S-27 notifications claim
`<eventId>:<userId>` per channel (`email`, `sms`, `push`) and where the replay tool remembers what it replayed
(`dlq-replay`). The worker deletes claims older than `EVENTS_PROCESSED_RETENTION` (60 days, longer than any topic's
retention) every night at 03:17 Edmonton.

## 2. Alerts and metrics

The worker exposes Prometheus metrics at `:8084/actuator/prometheus` (same port as the probes).

| metric | meaning | alert |
|---|---|---|
| `northline_events_dead_lettered_total{consumer,topic}` | a group's event reached the `.dlq` (also an ERROR log line `DEAD-LETTERED consumer=… event=… type=… reason=…`) | **page**: `increase(...[15m]) > 0` |
| `northline_events_consumed_total{consumer,type,outcome}` | `processed`, `duplicate`, `failed` (will be retried), `poison` | ticket: `poison` > 0; `failed` rate high for 15 min |
| `kafka_consumer_fetch_manager_records_lag_max{client_id,…}` | consumer lag per listener container (Kafka client metrics) | ticket: lag > 1000 for 10 min (search: > 5000) |

Each group's `@DltHandler` reads the whole `<topic>.dlq` and ignores the records of other groups, so a DLQ record is
alerted once, by the group that failed.

## 3. DLQ: investigate and replay

**Symptoms.** The page `NorthlineEventDeadLettered` ([alerts/event-dead-lettered.md](alerts/event-dead-lettered.md));
the worker's ERROR line `DEAD-LETTERED consumer=<group> event=<id> type=<type> topic=<topic> dlq=<topic>.dlq-<p>@<offset>
reason=…`; `northline_events_dead_lettered_total{consumer,topic}` rising; a customer, a business or a partner says
something never arrived (an email, a push, a webhook, a listing in search).

**Impact.** Only that event's effect for that consumer group is missing — the other groups processed it. Nothing is
lost: the `.dlq` keeps the record 30 days (`deploy/kafka/topics.yaml`), the outbox and the source rows are intact.

| group | what didn't happen | how bad |
|---|---|---|
| `notifications` | a team email / SMS / push, or a customer's or courier's push | late or missing notice; money notices matter most (`payout.failed`, disputes) |
| `webhooks` | a partner webhook delivery row (nothing was queued) | the partner's integration misses an event until replayed |
| `search-indexer` | a listing or merchant document refresh | search shows stale data; the reconcile sweep fixes edits within a minute, events it can't see (hidden / deleted) wait |

**Decide** (read the failure first — the alert's `reason`, or `list` below, which prints it):

```
reason / kafka_exception-cause-fqcn
├─ PoisonEventException (not JSON, unknown type/version, schema violation, id mismatch)
│    → a producer or schema bug: replaying unchanged sends it straight back to the DLQ.
│      Fix the producer / add the schema version, deploy the worker, THEN replay (--type=…).
│      Obsolete event (the bug is fixed and the effect no longer matters)? Don't replay; note it in the incident.
├─ a provider outage (SMTP/SES/SendGrid, Twilio, APNs/FCM, Elasticsearch, the database) — many records, one window
│    → wait until the provider is healthy (its status page, the worker's logs), then replay that window
│      (--since/--until) at a gentle rate (--rate=5…20).
├─ a handler bug (NullPointerException, IllegalStateException from our code)
│    → fix, deploy, replay (--type=… to stay narrow).
└─ old notifications (> 24 h) — replaying a "your order is out for delivery" push days later confuses people
     → replay only what is still useful (--since=24h); for money notices (payout.failed, disputes) replay anyway.
search-indexer: a replay works, but a partial reindex of the merchants concerned does the same job without Kafka
(search.md § 9). webhooks: replaying queues the deliveries; partners dedupe on the event id.
```

**1. Inspect.** The tool lists without changing anything (no consumer group, nothing committed); each line has the
status, the DLQ position, event id, type, original topic and the failure:

```sh
# locally, or anywhere with the worker's KAFKA_* and DB_* variables (server/.env)
cd server && ./gradlew :worker:dlqReplay --args='list --topic=payments.payout.dlq --group=notifications'
# narrower: one or more events, a type, a time window (instant or age), a text in the failure
./gradlew :worker:dlqReplay --args='list --topic=payments.payout.dlq --group=notifications
    --type=payments.payout_failed --since=6h --until=2026-10-02T09:30:00Z --failure=timeout --limit=500'
```

Raw records with their headers (`kafka_exception-message`, `kafka_exception-cause-fqcn`, `kafka_original-topic`,
`kafka_dlt-original-consumer-group`): the Kafka UI locally (`docker compose --profile tools up -d`, then
<http://localhost:8190>), or

```sh
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
  --topic payments.payout.dlq --from-beginning --timeout-ms 10000 \
  --property print.headers=true --property print.timestamp=true --property print.key=true
```

**2. Replay.** Same filters, plus who you are and why — both go to the platform audit log, the run is refused
without them:

```sh
./gradlew :worker:dlqReplay --args='replay --topic=payments.payout.dlq --group=notifications --since=6h --rate=10
    --actor=you@northline.ca --reason=INC-2026-10-02-smtp'
```

| option | meaning |
|---|---|
| `--event=<id>[,<id>…]` | these events only |
| `--type=<type>` | `nl-event-type`, e.g. `payments.payout_failed` |
| `--since=`, `--until=` | the DLQ record's timestamp: an instant (`2026-10-02T08:00:00Z`) or an age (`30m`, `6h`, `2d`) |
| `--failure=<text>` | the failure message contains it (case-insensitive) |
| `--limit=N` | at most N records per run (default 100; already-replayed ones don't count) — run again for the next N |
| `--rate=N` | at most N records a second (default 20) |
| `--force` | replay records already replayed once (normally refused: `ALREADY_REPLAYED`) |
| `--actor=`, `--reason=` | required for `replay`: your staff email (or id) and the incident or ticket |

Each record is republished to its **original topic** with its key, value and `nl-*` headers (plus
`nl-replayed-from=<dlq>:<partition>:<offset>`). Every group of that topic receives it; the groups that had processed
the event find their dedupe claim (`events.processed_events`) and skip it, so only the failed group does the work.
Each replayed record is remembered (`dlq-replay` claim): a second run reports `ALREADY_REPLAYED`. The DLQ itself is
never modified. One audit entry per run: action `events.dlq_replayed`, role `operator`, target the DLQ topic, with
the actor, reason, filters, counts and up to 200 event ids (console › Audit log, area `events.`).

**3. Verify** (idempotency included):

```sql
-- the failed group processed it now; every other group still has exactly one claim (it did no work twice)
select consumer, processed_at from events.processed_events where event_id = '<event id>' order by consumer;
-- what was replayed, and by whom
select event_id, processed_at from events.processed_events where consumer = 'dlq-replay' and event_id like 'payments.payout.dlq:%' order by processed_at desc limit 20;
select at, after->>'actor' as actor, after->>'reason' as reason, after->'details'->>'replayed' as replayed
  from developer.audit_log where action = 'events.dlq_replayed' order by at desc limit 5;
```

Then the effect itself: `northline_notifications_sent_total` / Mailpit or the provider's log (notifications), the
endpoint's Deliveries drawer or `select state, attempt from developer.webhook_deliveries where event_id = '<id>'`
(webhooks), `curl -s "$ES_URIS/listings_en/_doc/<listing id>"` (search). A replayed record that fails again goes back
to the DLQ with a new offset and pages again: stop, re-read the failure.

**Rollback.** A replay can't be undone, but it can't double anything either: consumers dedupe on the event id, and
notifications claim per person and channel. To stop a running replay, interrupt it (Ctrl-C, or delete the Job);
what was sent stays claimed, the rest can be replayed later.

**Comms.** Tell support when customers or businesses missed notices (which type, the window, that they are being
sent now). Partners: nothing to announce for a short delay (at-least-once delivery is the contract); for a gap of
more than a day, the business owner is told by support. Write the incident note: cause, window, records replayed
(the audit entry), time to detect and fix.

**In a cluster** — a one-off Job with the worker image and the worker Deployment's environment:

```yaml
apiVersion: batch/v1
kind: Job
metadata: { name: dlq-replay-1, namespace: northline-<env> }
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 86400
  template:
    spec:
      restartPolicy: Never
      serviceAccountName: northline-worker
      securityContext: { runAsNonRoot: true, runAsUser: 65532, runAsGroup: 65532 }
      containers:
        - name: replay
          image: <the worker Deployment's image>          # kubectl -n northline-<env> get deploy northline-worker -o jsonpath='{..image}'
          command: ["java", "-XX:MaxRAMPercentage=75", "-cp", "@/app/jib-classpath-file",
                    "ca.northline.worker.events.DlqReplayCommand",
                    "list", "--topic=payments.payout.dlq", "--group=notifications"]
                    # then: "replay", …, "--rate=10", "--actor=you@northline.ca", "--reason=INC-…"
          envFrom:
            - configMapRef: { name: northline-infra }
            - configMapRef: { name: northline-worker }
          env:
            - { name: DB_PASSWORD, valueFrom: { secretKeyRef: { name: northline-worker-secrets, key: DB_PASSWORD } } }
            - { name: KAFKA_SASL_JAAS_CONFIG, valueFrom: { secretKeyRef: { name: northline-worker-secrets, key: KAFKA_SASL_JAAS_CONFIG, optional: true } } }
          resources: { requests: { cpu: 100m, memory: 384Mi }, limits: { memory: 384Mi } }
          securityContext: { readOnlyRootFilesystem: true, allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
          volumeMounts: [{ name: tmp, mountPath: /tmp }]
      volumes: [{ name: tmp, emptyDir: {} }]
```

`kubectl -n northline-<env> logs job/dlq-replay-1` prints one line per record (`WOULD_REPLAY` / `REPLAYED` /
`ALREADY_REPLAYED`, position, event id, type, original topic, reason). With the plain-Secret mode (no External
Secrets) the secret is `secrets.existingSecret`. The worker's Spring profile comes from its ConfigMap, so the
command checks the same required variables as the worker.

**Azure Event Hubs.** The DLQs are ordinary event hubs (Terraform creates one per catalogue topic, S-25): the tool
works through the Kafka endpoint with the worker's connection string, nothing differs. The 100-topic limit per
processing unit is about creating topics, not replaying.

### Deferred notifications (the table's own dead letters, S-115)

SMS, push (and a customer's email after an outage) that quiet hours or a provider outage held back live in
`messaging.deferred_notifications`, not in Kafka: the every-minute job sends them, retrying an outage every 5 minutes.
After 10 attempts the row is **dead** — kept (`dead_at`, `last_error` = the failure's class, V290), logged
`DEAD-LETTERED deferred <channel> <type> for user <id> after 10 attempts`, and no longer tried. Dead rows are deleted
after 30 days (`northline.notifications.deferred-dead-retention`, nightly 03:27 platform zone). Before S-115 they were
deleted at once.

```sh
# list (dry run): filters --channel=sms|push|email, --type=<event type>, --event=<id>, --since/--until (dead_at)
./gradlew :worker:dlqReplay --args='list --deferred --channel=sms --since=12h'
# requeue: fresh attempts, due now (or --at=<instant>, e.g. the next morning — requeued rows are not held for
# quiet hours again, so don't requeue SMS / push in the middle of the night)
./gradlew :worker:dlqReplay --args='replay --deferred --channel=sms --since=12h --at=2026-10-03T14:00:00Z
    --actor=you@northline.ca --reason=INC-2026-10-02-twilio'
```

```sql
select channel, event_type, count(*), min(dead_at), max(dead_at), max(last_error)
  from messaging.deferred_notifications where dead_at is not null group by 1, 2;
```

The job sends a requeued row like any other: it re-reads the person (still on the team, account active) and their
matrix (a channel turned off meanwhile cancels it); a delivery that did go out is claimed in `events.processed_events`
and isn't sent twice. One audit entry per run: `notifications.deferred_requeued`. Verify: the query above no longer
lists them, `northline_notifications_sent_total{channel="sms",outcome="sent"}` rises, the provider's log shows the
messages.

### Exercised

| date | what was run | outcome |
|---|---|---|
| 2026-10-02 | `ConsumerFrameworkTest.runbookDrill_filtersPaceAndAuditTheReplay_andOnlyTheFailedGroupRedoesTheWork` — Kafka 4 + PostGIS (Testcontainers) with the whole worker: two events dead-lettered by one group after its retries; `DlqReplayCommand list` with `--event`, `--type`, `--failure`, `--since`/`--until` (ages), `--limit`; `replay` refused without `--actor`; `replay --rate=2 --actor --reason` | **passed** (9.5 s): both replayed in ≥ 0.5 s (paced), processed once by the failed group, the bystander group's claim absorbed the replay (no second run of its handler), one `events.dlq_replayed` audit entry with the actor, reason, group and event ids, a second run `ALREADY_REPLAYED` |
| 2026-10-02 | `CustomerNotificationsTest.runbookDrill_aDeadDeferredNotification_isKept_listed_requeuedWithAnAudit_andSentOnce` — an SMS provider down for 11 attempts | **passed**: the row stays dead (`last_error = SmsDeliveryFailed`), the job ignores it, `list --deferred` shows it, `replay --deferred` without an actor is refused, with one it is requeued (audit `notifications.deferred_requeued`) and the job sends the SMS once; the push that went out earlier isn't repeated |
| — | **not exercised:** a cluster Job against MSK / Managed Kafka / Event Hubs (no cloud environment exists); the Kafka UI / console-consumer inspection steps (manual, read-only) | |

## 4. Writing a consumer

```java
@Component
@RequiredArgsConstructor
class PayoutNotifications {
    static final String GROUP = "notifications";
    private final EventProcessing events;

    @RetryableTopic(attempts = "4", backOff = @BackOff(delay = 10_000, multiplier = 6, maxDelay = 300_000),
            retryTopicSuffix = ".notifications.retry", dltTopicSuffix = ".dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, autoCreateTopics = "false",
            exclude = PoisonEventException.class, traversingCauses = "true")
    @KafkaListener(topics = {"payments.payout"}, groupId = GROUP)
    void on(ConsumerRecord<String, byte[]> record) {
        events.process(GROUP, record, event -> switch (event.type()) { … });
    }

    @DltHandler
    void deadLetter(ConsumerRecord<String, byte[]> record) {
        events.deadLettered(GROUP, record);
    }
}
```

Then add the group, its topics and `retryDelaysSeconds` to `deploy/kafka/topics.yaml` — `TopicCatalogueTest` fails
until the listener and the entry agree (and requires the `exclude`, the `@DltHandler` and an exponential back-off).
Handlers that call outside systems claim per recipient and channel on top of the group claim (S-27).

## 5. Shutdown and redelivery

- The listener containers commit after every record (`ack-mode: record`), so a crash redelivers at most the record in
  hand — which the dedupe absorbs.
- On SIGTERM the containers stop polling and let the record in hand finish, up to `EVENTS_SHUTDOWN_TIMEOUT` (20 s),
  within the pod's 45 s grace period (`server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 30s`).
- A new consumer group starts at the oldest retained event (`auto-offset-reset: earliest`).

## 6. Local

`docker compose --profile events --profile db up -d` (topics are created by the one-shot), then
`cd server && ./gradlew :worker:bootRun`. Publish a hand-made event with the Kafka UI (`--profile tools`) or
`kafka-console-producer.sh --property parse.headers=true` — the three `nl-event-*` headers are required. Tests:
`./gradlew :worker:test` (Kafka 4 and PostGIS in Testcontainers; `ConsumerFrameworkTest` covers duplicate delivery,
retry, DLQ + alert, poison, replay, lag metrics and shutdown settings).

## 7. Schema checks (S-34)

The schemas in `server/api/src/main/resources/events` are a contract between the producers (api, northline-auth)
and the worker, which rejects any payload that breaks its schema (poison → `.dlq`, § 3). Four checks keep it:

| # | check | fails when |
|---|---|---|
| 1 | schemas | a file isn't JSON; the name isn't `<module>.<event>.v<n>.schema.json` or `$id` isn't `northline:<type>:<n>`; `$schema` isn't draft 2020-12; a keyword or `format` the worker's `EventSchemas` doesn't implement (it would pass unchecked); a malformed value (`type`, `required` naming an undeclared field, schema-valued `additionalProperties`, bad `pattern`); `eventId`, `occurredAt`, `aggregateId` not required |
| 2 | events ↔ schemas | an `@Externalized` event (api modules, northline-auth) has no file for its type and `version()`; a file's type has no event, or its version is above the event's |
| 3 | sample payloads | an event record built with representative values — every `@Nullable` component set and null, every Java enum constant — serialised like the outbox (Jackson 3, nulls included) breaks its schema: an undeclared field in a closed schema, a missing required one, another type, null where the schema says non-null, an enum value outside the list |
| 4 | breaking changes | compared with the base branch **at the merge base**: a field removed or newly required, a type or `enum` narrowed (or added), `additionalProperties` closed, `pattern` / `format` added or changed, `minimum` / `minLength` raised, `maxLength` lowered or added, or a file deleted — in the **same** version file |

**Changing an event.** Additive (new optional field, wider type or enum, descriptions): edit the file. Breaking: keep
`v<n>` untouched, add `<type>.v<n+1>.schema.json`, return `n+1` from the event's `version()` (the api's
`DomainEvent`), and teach the consumers the new version (the worker validates both while old events drain). Old
version files are never deleted while a topic or DLQ can still hold them.

**Where it runs.** `./gradlew build` runs checks 1–3 (and 4 when `origin/main` — or `-PeventSchemas.base=<ref>` —
is available locally) through `:event-contracts:test`. By hand, with the report:

```sh
cd server && ./gradlew :event-contracts:eventSchemas                          # base = origin/main if present
./gradlew :event-contracts:eventSchemas -PeventSchemas.base=origin/main -PeventSchemas.requireBase=true   # as CI
```

CI: the manual `event-schemas` workflow / GitLab `events:schemas` job ([ci.md](ci.md)) fetches the base in full and
requires it (exit 2 without it). The sample builder knows the types events use today (strings, numbers, booleans,
`Instant`/dates, enums, lists, nested records); another type or a `pattern` none of its sample strings match fails
check 3 with "teach SamplePayloads" rather than being skipped.
