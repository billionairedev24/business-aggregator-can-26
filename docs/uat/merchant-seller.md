---
title: "UAT script — Merchant — seller"
---

# UAT script — Merchant — seller

| | |
|---|---|
| Persona | Merchant — seller (`seller`) |
| Version | 1.0 |
| Who | The owner (or staff) of a shop that sells goods through Northline in the pilot. |
| What you need | A computer with a current browser; your Northline sign-in with a second factor; a product photo; a phone for the customer side. |
| Derived from | S-117 journeys 1 (onboarding) and 3 (order → delivery); SCREENS.md Studios (seller). |

[French version](fr/merchant-seller.md)

## Before you start

1. Use the pilot environment the team gave you, never production.
2. Do each step in order. Mark **Pass** when what you see matches the expected result, **Fail** otherwise.
3. For every failure, and anything confusing, use **Send feedback** in the product (or the form, or tell your pilot contact) and write the reference (UAT-…) in the Notes column.
4. Don’t type passwords, card numbers or codes into feedback; the screen you are on, the app version, your language and your browser or phone system are sent with it — nothing else.
5. When you are done, fill in the sign-off form: [sign-off form](merchant-seller-signoff.md).

## Steps

| # | Step | Expected result | Pass / Fail | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Sign in to the Studio with your second factor. | You land on the dashboard; “Send feedback” is in the corner. | ☐ Pass ☐ Fail | |
| 2 | Add a product with a photo, a price and stock. | It is saved as a draft and goes to vetting; the list shows its state. | ☐ Pass ☐ Fail | |
| 3 | Add a product with sizes or colours (variants). | Each variant has its own stock and price. | ☐ Pass ☐ Fail | |
| 4 | Once approved, find the product on the consumer site. | The product page is public with the right price and stock. | ☐ Pass ☐ Fail | |
| 5 | From the customer phone, buy it for delivery. | The order appears in Orders as new, with the delivery details. | ☐ Pass ☐ Fail | |
| 6 | Mark the order packed. | The order shows “Packed”; dispatch can assign a courier. | ☐ Pass ☐ Fail | |
| 7 | Hand the order to the courier. | The order shows picked up; the customer sees the courier on the way. | ☐ Pass ☐ Fail | |
| 8 | After delivery, open the order again. | It shows delivered; the money is held until the release date (7 days after delivery). | ☐ Pass ☐ Fail | |
| 9 | Open Refunds and look at a refund request (the pilot team can create one). | You can accept or answer it; the customer sees your answer. | ☐ Pass ☐ Fail | |
| 10 | Open Reports and export a CSV. | The file opens in a spreadsheet with your sales. | ☐ Pass ☐ Fail | |
| 11 | Switch to French and repeat one screen. | Everything on it is in French. | ☐ Pass ☐ Fail | |
| 12 | Send one note with “Send feedback”. | You get a reference (UAT-…). | ☐ Pass ☐ Fail | |
