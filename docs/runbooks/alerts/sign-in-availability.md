# Sign-in availability

**Alerts:** `NorthlineSignInAvailabilityBurn` · SLO burn — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** Sign-in availability: 99.9 % of sign-in calls (`POST /api/auth/sign-in/*` on northline-auth) don't fail with a 5xx, over 30 days.

**Impact.** People can't sign in — consumers, businesses (the Studio, the KDS) and staff. Wrong passwords or codes are 4xx and never count.

## First checks

1. Dashboard **Northline · sign-in** (`northline-sign-in`) and **auth** (`northline-auth`): which route and which status (`uri`, `status`).
2. `502/503` on `/code` or `/code/verify`: the SMS provider ([README § SMS](../README.md#sms-and-voice-codes-s-8)); on `/passkey*`: WebAuthn settings (`WEBAUTHN_RP_ID`) after a host change.
3. `500` everywhere: auth's database or Valkey (sessions, rate limits) — the auth pods' logs by `trace.id`, Hikari pool waits.
4. A new release in the last hour? Roll back ([gitops.md](../gitops.md)).

## Mitigate

Roll back a bad release; switch the SMS provider (`SMS_PROVIDER`) if one is down; scale auth if CPU-bound.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
