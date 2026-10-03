---
title: "UAT script — Customer"
---

# UAT script — Customer

| | |
|---|---|
| Persona | Customer (`customer`) |
| Version | 1.0 |
| Who | A pilot customer, on the consumer site and on the Northline app. |
| What you need | Your phone with the Northline pilot app (TestFlight or the Play internal track) and a computer with a browser; a test card the pilot team gives you (no real charge in the pilot environment). |
| Derived from | S-117 journeys 2 (quote → booking → sign-off) and 3 (order → delivery); SCREENS.md Consumer web and Consumer app. |

[French version](fr/customer.md)

## Before you start

1. Use the pilot environment the team gave you, never production.
2. Do each step in order. Mark **Pass** when what you see matches the expected result, **Fail** otherwise.
3. For every failure, and anything confusing, use **Send feedback** in the product (or the form, or tell your pilot contact) and write the reference (UAT-…) in the Notes column.
4. Don’t type passwords, card numbers or codes into feedback; the screen you are on, the app version, your language and your browser or phone system are sent with it — nothing else.
5. When you are done, fill in the sign-off form: [sign-off form](customer-signoff.md).

## Steps

| # | Step | Expected result | Pass / Fail | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Sign up (or sign in) on the app with your phone number and the code. | You are signed in; you’re asked for a second factor or a passkey. | ☐ Pass ☐ Fail | |
| 2 | Set your delivery location. | Home shows what is available where you are. | ☐ Pass ☐ Fail | |
| 3 | Search for a product and open it. | The product page shows price, stock and delivery options. | ☐ Pass ☐ Fail | |
| 4 | Add it to the cart and pay with the test card. | You see the confirmation with the order number; an email arrives. | ☐ Pass ☐ Fail | |
| 5 | Follow the delivery. | You see the courier on the way and your drop-off PIN. | ☐ Pass ☐ Fail | |
| 6 | Receive the order; give the PIN. | The order shows delivered. | ☐ Pass ☐ Fail | |
| 7 | Request quotes from a service provider for a job. | You can compare the quotes you receive line by line. | ☐ Pass ☐ Fail | |
| 8 | Accept a quote and pay the deposit. | The booking appears under Orders & bookings; the deposit is held, not paid to the provider yet. | ☐ Pass ☐ Fail | |
| 9 | When the provider finishes, sign off the job and leave a review. | The booking shows complete; your review appears on the provider’s page. | ☐ Pass ☐ Fail | |
| 10 | Report a problem with the order (“Something’s wrong”). | A case opens with a number; you can follow it under Help & cases. | ☐ Pass ☐ Fail | |
| 11 | Switch the app and the site to French; repeat one step. | Everything is in French, including emails and texts. | ☐ Pass ☐ Fail | |
| 12 | Send one note with the “Feedback” button in the app and one from the site. | Each gets a reference (UAT-…). | ☐ Pass ☐ Fail | |
