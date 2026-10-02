# Sign-in latency

**Alerts:** `NorthlineSignInLatencyBurn` · SLO burn — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** Sign-in latency: 99 % of sign-in calls answered within 1 s, over 30 days.

**Impact.** Sign-in feels stuck; phone-code sign-ins time out in the apps (20 s client timeout).

## First checks

1. Dashboard **sign-in**: p95/p99 by route. A slow `/code` is usually the SMS provider; slow `/passkey` the database.
2. A trace of a slow request (Explore → Tempo, service `northline-auth`): which span takes the time (SQL, Valkey, the SMS call, KMS signing).
3. KMS token signing (`KMS_PROVIDER`): throttling at the cloud KMS shows as slow `kms` spans ([key-rotation.md](../key-rotation.md)).

## Mitigate

Scale auth; fix the slow dependency; raise the KMS quota.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
