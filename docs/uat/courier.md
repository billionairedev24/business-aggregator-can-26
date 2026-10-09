---
title: "UAT script — Courier"
---

# UAT script — Courier

| | |
|---|---|
| Persona | Courier (`courier`) |
| Version | 1.0 |
| Who | A pilot courier using the Northline courier app. |
| What you need | Your phone with the courier app (pilot build), location on while in use; a test delivery the pilot team sets up. |
| Derived from | S-117 journey 3 (order → delivery, courier side); SCREENS.md Courier app. |

[French version](fr/courier.md)

## Before you start

1. Use the pilot environment the team gave you, never production.
2. Do each step in order. Mark **Pass** when what you see matches the expected result, **Fail** otherwise.
3. For every failure, and anything confusing, use **Send feedback** in the product (or the form, or tell your pilot contact) and write the reference (UAT-…) in the Notes column.
4. Don’t type passwords, card numbers or codes into feedback; the screen you are on, the app version, your language and your browser or phone system are sent with it — nothing else.
5. When you are done, fill in the sign-off form: [sign-off form](courier-signoff.md).

## Steps

| # | Step | Expected result | Pass / Fail | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Sign in to the courier app. | You see your shift screen with your status. | ☐ Pass ☐ Fail | |
| 2 | Start your shift. | You show as available; dispatch can assign you a run. | ☐ Pass ☐ Fail | |
| 3 | Open the run you are assigned. | The stops are in order, the next one marked. | ☐ Pass ☐ Fail | |
| 4 | At the shop, confirm the pickup (sealed bag). | The stop shows picked up; the customer is told it is on the way. | ☐ Pass ☐ Fail | |
| 5 | Turn on airplane mode, arrive at the drop-off, then turn it off. | The app keeps what you did and sends it when you are back online. | ☐ Pass ☐ Fail | |
| 6 | Hand over with the customer’s PIN (and a photo). | The stop is complete; the customer sees delivered. | ☐ Pass ☐ Fail | |
| 7 | End your shift. | You are warned if anything is still unsent; the shift ends. | ☐ Pass ☐ Fail | |
| 8 | Switch the app to French and open a stop. | Everything is in French. | ☐ Pass ☐ Fail | |
| 9 | Tell the pilot team one thing that slowed you down: **Feedback** at the top of any screen of the courier app (add a screenshot if it helps; crop out customer details). | You see the reference (UAT-…) on the phone; the item is in the UAT queue as a courier's. | ☐ Pass ☐ Fail | |
