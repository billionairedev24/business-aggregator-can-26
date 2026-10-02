# Slow requests

**Alerts:** `NorthlineSlowRequests` · threshold — ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** A service's p95 latency is above 2 s for 10 minutes.

**Impact.** Slow pages and API calls.

## First checks

1. *Slowest routes*, then a trace: which span (SQL, Stripe, another service) takes the time; DB pool waits.

## Mitigate

Fix the slow query or dependency; scale.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
