# Checkout availability

**Alerts:** `NorthlineCheckoutAvailabilityBurn` · SLO burn — page / ticket · [all alerts](README.md) · [alerting](../alerting.md)

**What it measures.** Checkout availability: 99.9 % of checkout calls (`POST /api/v1/me/checkouts`, `…/{checkoutId}/place`, `/api/v1/me/bookings/checkout`, and since 2026-10-04 the food orders `POST /api/v1/me/food-orders` and `…/food-orders/{orderId}/confirm`) don't fail with a 5xx, over 30 days.

**Impact.** Customers can't pay: orders, food orders and booking deposits are lost revenue. Declined cards (4xx) don't count.

## First checks

1. Status codes on the **checkout and payouts** dashboard (`northline-checkout-payouts`): **503** with
   `code: payments_unavailable` = Stripe is down, slow or throttling us (since S-115); **500** = our bug or database.
2. <https://status.stripe.com>; the api's logs: `Provider unavailable (payments_unavailable)`, `StripeCallFailed`
   (`Invalid API Key` = a key problem, not an outage), by `trace.id`.
3. Tax provider (`TAX_PROVIDER=stripe`): Stripe Tax errors fail the quote and the checkout ([stripe.md § 6](../stripe.md#6-stripe-tax-s-21)).
4. Database: escrow writes, idempotency keys in Valkey.
5. A release in the last hour? Roll back ([gitops.md](../gitops.md)).

## Mitigate

Stripe outage: nothing to switch — checkout already answers 503 "try again in a few minutes" and nothing queues
wrongly; follow [stripe-incidents.md § 1](../stripe-incidents.md#1-stripe-is-down-degrade-checkout-what-queues)
(what queues, comms, the catch-up afterwards). A rolled or leaked key:
[stripe-incidents.md § 5](../stripe-incidents.md#5-leaked-keys). Our bug: roll back.

## Afterwards

Write down what happened in the incident notes (cause, impact, time to detect and fix); if the alert was noise, tune
it in `deploy/observability/` (make obs-rules-check) rather than silencing it for good. *Updated in S-115; never
exercised in a real incident — improve it the first time it is used.*
