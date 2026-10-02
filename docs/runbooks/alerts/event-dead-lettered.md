# Event dead-lettered

**Alerts:** `NorthlineEventDeadLettered` · threshold — page · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** A worker consumer moved an event to its `.dlq` topic (S-26).

**Impact.** That event's effect (an email, an index update, a webhook) did not happen.

## First checks

1. [events.md § 3](../events.md#3-dlq-investigate-and-replay): find it, read the failure, replay.

## Mitigate

Fix the cause, replay from the DLQ.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
