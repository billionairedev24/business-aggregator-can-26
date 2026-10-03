---
title: "UAT script — Merchant — service provider"
---

# UAT script — Merchant — service provider

| | |
|---|---|
| Persona | Merchant — service provider (`provider`) |
| Version | 1.0 |
| Who | The owner (or a technician) of a service business taking part in the pilot. |
| What you need | A computer or tablet with a current browser; your Northline sign-in with a passkey or authenticator app; a phone for the customer side (a pilot customer, or a second account the pilot team gives you). |
| Derived from | S-117 journeys 1 (onboarding), 2 (quote → booking → escrow) and 4 (payout); SCREENS.md Studios (provider) and Onboarding. |

[French version](fr/merchant-provider.md)

## Before you start

1. Use the pilot environment the team gave you, never production.
2. Do each step in order. Mark **Pass** when what you see matches the expected result, **Fail** otherwise.
3. For every failure, and anything confusing, use **Send feedback** in the product (or the form, or tell your pilot contact) and write the reference (UAT-…) in the Notes column.
4. Don’t type passwords, card numbers or codes into feedback; the screen you are on, the app version, your language and your browser or phone system are sent with it — nothing else.
5. When you are done, fill in the sign-off form: [sign-off form](merchant-provider-signoff.md).

## Steps

| # | Step | Expected result | Pass / Fail | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Sign in to the Studio with your second factor. | You land on your business’s dashboard. The “Send feedback” button is in the bottom corner. | ☐ Pass ☐ Fail | |
| 2 | Open Compliance and check every verification item. | Each check shows its state; anything due says what to send and by when. | ☐ Pass ☐ Fail | |
| 3 | Open your business page (Page) and preview it. | The preview matches what customers see: services, service area, reviews. | ☐ Pass ☐ Fail | |
| 4 | Add a service with a fixed price, then one priced “on quote”. | Both are saved; the fixed-price one shows its price, the other “Quote”. | ☐ Pass ☐ Fail | |
| 5 | Set this week’s availability and block one hour. | The calendar shows the open hours and the blocked hour. | ☐ Pass ☐ Fail | |
| 6 | From the customer phone, request a quote for your “on quote” service. | The request appears in the Studio (Appointments / Messages) with the customer’s details of the job. | ☐ Pass ☐ Fail | |
| 7 | Write an itemized quote: labour, a part, a deposit, validity; send it. | The customer sees every line, the deposit and the expiry date. | ☐ Pass ☐ Fail | |
| 8 | Customer accepts and pays the deposit. | The booking shows in Appointments; the money shows as held in escrow, not yet yours. | ☐ Pass ☐ Fail | |
| 9 | On the day, check in and mark the job complete. | The customer is asked to sign off the job. | ☐ Pass ☐ Fail | |
| 10 | Customer signs off. | The escrow is released (immediately on sign-off, or 48 hours after completion); Earnings shows it. | ☐ Pass ☐ Fail | |
| 11 | Open Payouts. | The next payout and its date are shown; a paid one shows “On its way to your bank”. | ☐ Pass ☐ Fail | |
| 12 | Switch the Studio to French and repeat one screen of your choice. | Everything on that screen is in French, including error messages. | ☐ Pass ☐ Fail | |
| 13 | Send one note with “Send feedback”, with a screenshot. | You get a reference (UAT-…). The pilot team can find it. | ☐ Pass ☐ Fail | |
