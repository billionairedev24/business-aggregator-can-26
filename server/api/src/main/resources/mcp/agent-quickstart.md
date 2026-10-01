# Northline for AI agents — quickstart

You are acting for a person (or a partner integration) on **Northline**, a Canadian marketplace for local services,
shops and kitchens. Every tool is a Northline API operation run **with that person's own sign-in**: you see and change
only what their role in each business allows.

## Start

1. `list_my_businesses` — the businesses you can act for, with the person's role (owner, technician, cook, bookkeeper).
2. Every other tool takes a `merchantId` from that list.

## Reading

Listings (`search_listings`, `get_listing`), orders (`list_orders`, `get_order`, `get_kitchen_board`), appointments
(`list_appointments`, `get_appointment`, `list_quote_requests`), availability (`get_availability_hours`,
`get_booking_rules`, `list_time_off`, `get_time_off_conflicts`), messages (`list_message_threads`,
`get_message_thread`), money (`get_earnings`, `get_payouts_overview`, `list_payouts` — read only) and reviews
(`get_review_summary`, `list_reviews`). Money is in cents (CAD); times are ISO-8601.

## Changing things (scope `mcp.write`)

`update_listing_price_stock`, `mark_order_packed`, `kitchen_accept_order`, `kitchen_mark_ready`, `kitchen_hand_off`,
`appointment_en_route`, `appointment_on_site`, `set_availability_hours`, `add_time_off`, `reply_to_thread`.

- **Every change is confirmed.** The first call changes nothing and answers `confirmation_required` with what would
  change. Show it to the person. If they agree, call the same tool with **exactly the same arguments** within 5 minutes.
- The same change repeated within 10 minutes is answered `already_done`, never applied twice.
- `kitchen_hand_off` releases the kitchen's payment for that order; refunds and payouts are never available to agents.

## Limits and errors

- 60 tool calls a minute, 10 of them changes. Over it: HTTP 429 with `Retry-After`.
- A tool error with `insufficient_scope` means the person didn't grant that permission to you; `mfa_required` means
  they must sign in with a passkey or authenticator app.
- `422` errors carry `{ field, rule, message }`: fix the argument and try again.

## Resources

- `northline://me` — the businesses and roles (same as `list_my_businesses`).
- `northline://merchants/{merchantId}` — one business.
- `northline://guides/agent-quickstart` — this page.
