# Consumer lag

**Alerts:** `NorthlineConsumerLag (ticket)`, `NorthlineConsumerLagCritical (page)` · threshold · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** A worker consumer is more than 1,000 (ticket, 10 min) / 10,000 (page, 15 min) records behind.

**Impact.** Notifications, search updates and partner webhooks are late.

## First checks

1. [events.md § 2](../events.md#2-alerts-and-metrics); dashboard **events**.
2. The worker's CPU and DB pool; Elasticsearch health for the indexer.

## Mitigate

Scale the worker (partitions allow it); fix the slow dependency.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
