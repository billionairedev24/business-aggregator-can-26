---
sidebar_position: 1
title: Getting started
slug: /
---

# Northline for developers

Northline is a Canadian marketplace for services, shop products and food. These guides and the
[API reference](/api/) describe what an integration can use:

- **Partner API**: read (and, where allowed, change) the data of the businesses that bound your integration.
- **Webhooks**: signed events about those businesses, such as a booking completed, a payment released or a refund
  issued.
- **OAuth 2.1 / OpenID Connect**: how every client gets a token.

Each reference is available in two viewers: **Redoc** for reading and **Scalar** for trying requests. You can also
download the OpenAPI 3.1 file it is generated from.

## Conventions

| Topic | Rule |
| --- | --- |
| Base path | `/api/v1` |
| Format | JSON, camelCase |
| Ids | [ULIDs](https://github.com/ulid/spec): 26 characters, sortable by time |
| Money | integer cents of Canadian dollars (`priceCents: 12345` = $123.45) |
| Times | ISO-8601 instants in UTC |
| Lists | `{ "items": [ … ] }` |
| Validation errors | `422 { "errors": [ { "field", "rule", "message" } ] }`, one error per field |
| Other errors | `application/problem+json` (RFC 9457) with a stable `code`, e.g. `not_bound` or `partner_not_allowed` |
| Idempotency | money-moving POSTs take an `Idempotency-Key` header, remembered for 24 hours |

## Next

1. [Authenticate](authentication.md) with client credentials and a signed assertion.
2. [Receive webhooks](webhooks.md) and verify their signature.
