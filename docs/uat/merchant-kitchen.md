---
title: "UAT script — Merchant — kitchen"
---

# UAT script — Merchant — kitchen

| | |
|---|---|
| Persona | Merchant — kitchen (`kitchen`) |
| Version | 1.0 |
| Who | The owner or a cook of a restaurant or kitchen taking part in the pilot. |
| What you need | The tablet you will use in the kitchen, with sound on; your Northline sign-in; a phone for the customer side. |
| Derived from | S-117 journey 3 (order flow, food variant) and the Studio smoke; SCREENS.md Studios (kitchen). |

[French version](fr/merchant-kitchen.md)

## Before you start

1. Use the pilot environment the team gave you, never production.
2. Do each step in order. Mark **Pass** when what you see matches the expected result, **Fail** otherwise.
3. For every failure, and anything confusing, use **Send feedback** in the product (or the form, or tell your pilot contact) and write the reference (UAT-…) in the Notes column.
4. Don’t type passwords, card numbers or codes into feedback; the screen you are on, the app version, your language and your browser or phone system are sent with it — nothing else.
5. When you are done, fill in the sign-off form: [sign-off form](merchant-kitchen-signoff.md).

## Steps

| # | Step | Expected result | Pass / Fail | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Sign in to the Studio on the kitchen tablet. | You land on the live board (KDS); “Send feedback” is in the corner. | ☐ Pass ☐ Fail | |
| 2 | Open Menu: add a dish with allergens and a modifier group. | The dish is saved hidden until approval; allergens show on it. | ☐ Pass ☐ Fail | |
| 3 | Set this week’s kitchen hours, with one closed day. | The consumer site shows the kitchen closed that day. | ☐ Pass ☐ Fail | |
| 4 | From the customer phone, order two dishes for pickup. | The order rings on the board within seconds and is announced once. | ☐ Pass ☐ Fail | |
| 5 | Let the tablet sleep for 5 minutes, then place another order. | The new order still rings when it arrives. | ☐ Pass ☐ Fail | |
| 6 | Accept, mark ready, and hand off the first order. | Each step moves the ticket; the customer sees “Ready” then “Picked up”. | ☐ Pass ☐ Fail | |
| 7 | Pause new orders, then resume. | While paused the consumer site doesn’t take orders for your kitchen. | ☐ Pass ☐ Fail | |
| 8 | Mark one dish sold out. | The consumer site shows it sold out; it can’t be ordered. | ☐ Pass ☐ Fail | |
| 9 | Open Earnings after the hand-off. | The food order’s money is released on hand-off and shows in Earnings. | ☐ Pass ☐ Fail | |
| 10 | Switch to French and use the board for one order. | The board, buttons and announcements are in French. | ☐ Pass ☐ Fail | |
| 11 | Send one note with “Send feedback”. | You get a reference (UAT-…). | ☐ Pass ☐ Fail | |
