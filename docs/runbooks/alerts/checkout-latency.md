# Checkout latency

**Alerts:** `NorthlineCheckoutLatencyBurn` · SLO burn — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** Checkout latency: 99 % of checkout calls answered within 2.5 s, over 30 days.

**Impact.** Slow payment steps; customers retry (idempotency keys keep one charge).

## First checks

1. Dashboard **checkout and payouts**: p95 of the checkout routes and of Stripe calls.
2. A slow trace: Stripe PaymentIntent create, the tax calculation, or the database.

## Mitigate

Fix the slow dependency; scale the api. Stripe slow for everyone (its status page): wait — calls time out after
30 s and answer 503 `payments_unavailable`, customers retry ([stripe-incidents.md § 1](../stripe-incidents.md#1-stripe-is-down-degrade-checkout-what-queues)).

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Stub (S-113): never exercised
in a real incident — improve it the first time it is used.*
