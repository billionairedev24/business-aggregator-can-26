# Lockout spike

**Alerts:** `NorthlineLockoutSpike` · threshold — ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** More than 20 rate-limit lockouts on one action in 15 minutes.

**Impact.** Probably an attack; real people may be locked out for a while.

## First checks

1. [README § Rate limits](../README.md#rate-limits-s-9): which action, from where (the auth logs, IPs masked).

## Mitigate

Rate limits hold; block at the edge (WAF, [edge.md](../edge.md)) if it continues.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
