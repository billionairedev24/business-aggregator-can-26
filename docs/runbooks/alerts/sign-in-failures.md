# Sign-in failures

**Alerts:** `NorthlineSignInFailures` · threshold — ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** Over half the sign-in attempts fail (wrong factor) for 10 minutes.

**Impact.** Either one factor is broken for everyone or someone is trying credentials.

## First checks

1. Per-method failures on the **sign-in** dashboard: passkeys after an RP id change, the TOTP clock; or a credential-stuffing run (lockouts rise too — rate limits already hold).

## Mitigate

Fix the broken factor; for an attack, rate limits hold — tighten them if needed ([README § Rate limits](../README.md#rate-limits-s-9)).

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
