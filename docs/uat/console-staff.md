---
title: "UAT script — Console staff"
---

# UAT script — Console staff

| | |
|---|---|
| Persona | Console staff (`staff`) |
| Version | 1.0 |
| Who | A Northline staff member using the platform console during the pilot (support, trust & safety, dispatch, finance). |
| What you need | A computer with a current browser; your staff sign-in with a passkey or authenticator; the console roles you will hold at launch. |
| Derived from | S-117 journeys 1 (verification queue), 3 (dispatch) and 4 (payouts); SCREENS.md Platform console. |

[French version](fr/console-staff.md)

## Before you start

1. Use the pilot environment the team gave you, never production.
2. Do each step in order. Mark **Pass** when what you see matches the expected result, **Fail** otherwise.
3. For every failure, and anything confusing, use **Send feedback** in the product (or the form, or tell your pilot contact) and write the reference (UAT-…) in the Notes column.
4. Don’t type passwords, card numbers or codes into feedback; the screen you are on, the app version, your language and your browser or phone system are sent with it — nothing else.
5. When you are done, fill in the sign-off form: [sign-off form](console-staff-signoff.md).

## Steps

| # | Step | Expected result | Pass / Fail | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Sign in to the console with your second factor. | You see the overview for your role; the sidebar shows only your screens. | ☐ Pass ☐ Fail | |
| 2 | Switch role view (if you hold more than one). | The sidebar narrows; the switch is in the audit log. | ☐ Pass ☐ Fail | |
| 3 | Verification queue: review a pilot business and approve it. | The business becomes active and is told by email. | ☐ Pass ☐ Fail | |
| 4 | Listing vetting: approve one listing, reject one with a reason. | The merchant sees both decisions, the reason in their language. | ☐ Pass ☐ Fail | |
| 5 | Delivery ops: put a courier on shift and assign a run. | The courier gets the run; the map shows it. | ☐ Pass ☐ Fail | |
| 6 | Support desk: answer a pilot case with a macro in the requester’s language. | The requester sees the answer; the case state changes. | ☐ Pass ☐ Fail | |
| 7 | Finance: find the pilot payouts and the reconciliation. | Payouts and the Stripe vs ledger check match. | ☐ Pass ☐ Fail | |
| 8 | Pilot UAT: triage one feedback item: triaged, accepted (blocking or not), owner, tracker link. | Each step is saved and listed in the item’s history. | ☐ Pass ☐ Fail | |
| 9 | Pilot UAT: merge a duplicate; export the CSV. | The duplicate points at the item; the CSV opens in a spreadsheet. | ☐ Pass ☐ Fail | |
| 10 | Pilot UAT › Go / no-go: read the report. | Open blocking items, coverage per persona and the 14-day trend are shown. | ☐ Pass ☐ Fail | |
| 11 | Switch the console to French; repeat one screen. | Everything is in French. | ☐ Pass ☐ Fail | |
| 12 | Send one note with “Send feedback”. | You get a reference (UAT-…). | ☐ Pass ☐ Fail | |
