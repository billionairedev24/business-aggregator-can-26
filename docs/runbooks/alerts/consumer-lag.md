# Consumer lag

**Alerts:** `NorthlineConsumerLag (ticket)`, `NorthlineConsumerLagCritical (page)` · threshold · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** A worker consumer is more than 1,000 (ticket, 10 min) / 10,000 (page, 15 min) records behind.

**Impact.** Notifications, search updates and partner webhooks are late. Nothing is lost while the records are within
the topic's retention (7 days).

## First checks

1. [events.md § 2](../events.md#2-alerts-and-metrics); dashboard **events**: which group (`client_id`), rising or
   stuck?
2. Stuck at a constant lag with `failed` outcomes = one record retried over and over (it will reach the DLQ: see
   [event-dead-lettered](event-dead-lettered.md)). Rising with `processed` = too slow for the load.
3. The worker's CPU and DB pool; Elasticsearch health for `search-indexer`; the provider for `notifications`.
4. Kafka credentials rotated recently? Consumers that can't authenticate stop reading
   ([key-rotation.md § 5](../key-rotation.md#5-database-kafka-push-openrouter-on-call-export)).

## Mitigate

Scale the worker (partitions allow up to 6 consumers per group); fix the slow dependency. For `search-indexer` after
a long outage, a full reindex rebuilds the index without draining the backlog first
([search.md § 9](../search.md#runbook-reindex-partial-reindex-rollback-s-115)); the indexer then skips through what it
missed harmlessly (versions).

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Updated in S-115; never
exercised in a real incident — improve it the first time it is used.*
