---
sidebar_position: 3
title: Webhooks
---

# Webhooks

A business subscribes an HTTPS endpoint to events in Studio › Settings › API. Northline then `POST`s each event to that
endpoint as JSON. Every event type and its payload schema are in the [webhooks reference](/api/api-webhooks/).
Payloads carry ids and amounts only, never customer details.

## Verify the signature

Each delivery has a `Northline-Signature` header in this form:

```
Northline-Signature: t=1790790312,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
```

To verify it:

1. Take `t` from the header, then compute `HMAC-SHA256(secret, "<t>.<raw request body>")`. The secret is the
   endpoint's whole `whsec_…` value.
2. Compare the result in constant time with each `v1` value. While a rotated secret is still valid there are two
   `v1` values.
3. Reject the delivery when `t` is more than 5 minutes old.

Other headers: `Northline-Event-Id` (stable across retries; deduplicate on it), `Northline-Event-Type`,
`Northline-Delivery-Id` and `Northline-Delivery-Attempt`.

## Delivery

- **Acknowledging.** Any `2xx` acknowledges a delivery. Anything else is retried, including redirects, timeouts and
  TLS errors.
- **Retries.** The wait starts at 30 seconds and triples each time, capped at 12 hours. After 13 attempts (about 3
  days) the delivery is marked failed.
- **Turning off.** An endpoint that has not succeeded for 3 days, with at least 10 failures in a row, is turned off,
  and the business's owners are emailed.
