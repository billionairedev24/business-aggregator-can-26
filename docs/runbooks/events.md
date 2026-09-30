# Runbook — domain events: consumers, retries, DLQ and replay (S-25, S-26)

How an event travels from the api to the worker's consumers, what happens when a consumer fails, how you are
alerted, and how to replay a dead-lettered event. Topics and their provisioning: [infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials).

## 1. The path of an event

1. A module publishes a `DomainEvent` inside its transaction; the Modulith registry (`events.event_publication`) is
   the outbox. After commit, `@Externalized("<module>.<aggregate>::…")` events go to Kafka.
2. **Wire format** (ARCHITECTURE.md § Event management): key = aggregate id (per-aggregate ordering); value = the
   event record's JSON (ids only, no personal data), valid against
   `server/api/src/main/resources/events/<type>.v<version>.schema.json`; headers:

   | header | example | from |
   |---|---|---|
   | `nl-event-id` | `01J9ZD3V…` (ULID, = payload `eventId`) | api `config.EventHeaders` |
   | `nl-event-type` | `payments.payout_failed` (`<topic module>.<snake_case record>`, or `@EventType`) | same |
   | `nl-event-version` | `1` (`DomainEvent.version()`) | same |
   | `traceparent` | W3C trace context | the api's Kafka template observation, when tracing is on |

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

1. **Find the record.** The alert/log line gives the consumer group, event id, type, the DLQ position
   (`<topic>.dlq-<partition>@<offset>`) and the exception. Kafka UI (`--profile tools` locally) shows the headers
   (`kafka_exception-message`, `kafka_exception-cause-fqcn`, `kafka_original-topic`).
2. **Decide.** Poison (`PoisonEventException`): a producer bug or a schema without a version bump — fix the producer
   or the schema, then replay. Handler failure: fix the cause (a provider down, a bug) and deploy, then replay.
3. **List** what would be replayed (changes nothing):

   ```sh
   # locally or anywhere with the worker's KAFKA_* and DB_* variables
   cd server && ./gradlew :worker:dlqReplay --args='list --topic=payments.payout.dlq --group=notifications'
   ```
4. **Replay** (all of the group's records in that DLQ, or one event):

   ```sh
   ./gradlew :worker:dlqReplay --args='replay --topic=payments.payout.dlq --group=notifications --event=01J…'
   ```

   Each record is republished to its **original topic** with its key, value and `nl-*` headers (plus
   `nl-replayed-from`). Every group of that topic receives it; the groups that had processed the event find their
   dedupe claim and skip it, so only the failed group does the work. A replayed DLQ record is remembered
   (`dlq-replay` claim): running the command again doesn't replay it twice — `--force` does. `--limit=N` (default
   100) caps one run. The DLQ itself is never modified (retention 30 days).

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
                    "list", "--topic=payments.payout.dlq", "--group=notifications"]   # then: replay (+ --event=…)
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
