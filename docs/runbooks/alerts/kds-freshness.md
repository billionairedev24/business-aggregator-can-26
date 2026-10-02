# KDS freshness

**Alerts:** `NorthlineKdsFreshnessBurn` · SLO burn — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** KDS freshness: 99.5 % of live bus probes (each api replica signals itself every 30 s through the Studio live bus, S-68) come back within 2 s, over 30 days.

**Impact.** Kitchen displays, Studio orders and messages stop updating live while their streams look healthy (keep-alives continue), so they don't fall back to polling.

## First checks

1. Which replicas lose probes: `northline_studio_live_probe_seconds_count` by `outcome` and pod.
2. Valkey pub/sub: is Valkey up, failing over, or at its client limit? The api logs `Studio live signal … not published`.
3. A replica's listener container stalled: restart that pod.

## Mitigate

Restart the affected api pods (browsers reconnect within 3 s); fix Valkey.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
