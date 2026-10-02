# High error rate

**Alerts:** `NorthlineHighErrorRate` · threshold — page · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** More than 5 % of a service's requests fail (5xx) for 5 minutes, with some traffic.

**Impact.** Whatever that service does is failing for users.

## First checks

1. The service dashboard → *Errors by route* → open a trace from that route; the app's logs by `trace.id`.
2. A release in the last hour? Roll back ([gitops.md](../gitops.md)).

## Mitigate

Roll back, or fix the failing dependency.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
