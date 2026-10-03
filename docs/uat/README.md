---
title: "UAT with the pilot group"
---

# UAT with the pilot group (S-121)

How Northline runs user acceptance testing with its pilot merchants, customers, couriers and staff: who takes part,
what they run, how their feedback reaches the team and is triaged, how they sign off, and how the go/no-go is decided.
French: [fr/README.md](fr/README.md). The dry run of this process: [dry-run.md](dry-run.md).

## The pieces

| piece | where |
|---|---|
| Who takes part (the pilot participant flag) | console › Pilot UAT › Participants; `uat.participants` |
| In-product “Send feedback” control | Studio, consumer site, console (corner button); consumer app (floating “Feedback” button) — pilot participants only |
| Triage queue | console › Pilot UAT › Feedback; CSV export |
| UAT scripts and sign-off forms, per persona, en/fr | this folder (below) |
| Sign-off per participant and script | console › Pilot UAT › Participants › Record sign-off |
| Go / no-go report | console › Pilot UAT › Go / no-go; CSV; `uat.api.UatReadiness` for the go-live checklist (S-118) |

Console access: the **Pilot UAT** screen opens for **support**, **support lead** and **admin**; all three can triage,
add participants and record sign-offs (console action `uat`). Every change is in the audit log with the role acted with.

## Scripts and forms

| persona | script (en) | form (en) | script (fr) | form (fr) |
|---|---|---|---|---|
| Merchant — service provider | [merchant-provider](merchant-provider.md) | [form](merchant-provider-signoff.md) | [prestataire](fr/merchant-provider.md) | [formulaire](fr/merchant-provider-signoff.md) |
| Merchant — seller | [merchant-seller](merchant-seller.md) | [form](merchant-seller-signoff.md) | [vendeur](fr/merchant-seller.md) | [formulaire](fr/merchant-seller-signoff.md) |
| Merchant — kitchen | [merchant-kitchen](merchant-kitchen.md) | [form](merchant-kitchen-signoff.md) | [cuisine](fr/merchant-kitchen.md) | [formulaire](fr/merchant-kitchen-signoff.md) |
| Customer | [customer](customer.md) | [form](customer-signoff.md) | [client](fr/customer.md) | [formulaire](fr/customer-signoff.md) |
| Courier | [courier](courier.md) | [form](courier-signoff.md) | [livreur](fr/courier.md) | [formulaire](fr/courier-signoff.md) |
| Console staff | [console-staff](console-staff.md) | [form](console-staff-signoff.md) | [personnel](fr/console-staff.md) | [formulaire](fr/console-staff-signoff.md) |

Each script lists steps, the expected result and a pass/fail column. The steps follow the critical journeys of the
end-to-end suite (S-117: onboarding, quote → booking → escrow, order → delivery, payout, the Studio smoke) and the screens
in [SCREENS.md](../SCREENS.md), from the participant's side. The script version is in `uat.scripts` (V325); a sign-off
records the version that was run. **Changing a script:** edit both languages, raise the version in a new migration
(`UPDATE uat.scripts SET version = …`), and ask for new sign-offs where the change matters.

## Running UAT

1. **Recruit the group.** Merchants come from the pilot cohort (S-120 onboarding). Add each one in Pilot UAT ›
   Participants: a business by its id (provider, seller or kitchen — the persona must match the business type; a
   “provider and seller” business can take part as either, once per persona), a person by the email or mobile number of
   their Northline account (customer, courier, staff). The label is a working name (“Pilot kitchen 3”), not the
   person's name. At least one participant per persona.
2. **Hand out the script and the form** in the participant's language (print, or share the page).
3. **Participants run the script** in the pilot environment and send feedback from the product as they go. They write
   the reference (UAT-…) next to a failed step.
4. **Triage daily** (support; the support lead owns the queue):
   - `new` → `triaged`: read it, reproduce if you can, set an **owner** (a staff member who opens Pilot UAT).
   - `triaged` → `accepted`, and decide **blocking or not**: blocking = a participant of that persona can't finish a
     script step, money or personal data is wrong, or it breaks the accessibility or French-language rules. Everything
     else is non-blocking.
   - Link the **tracker issue** (any web address — GitHub, Linear, Jira).
   - `duplicate` merges an item into the one it repeats (its own duplicates follow); `wont_fix` with a note when it is
     out of scope or works as designed. Both can be reopened (→ `triaged`).
   - `accepted` → `fixed` when the fix is deployed to the pilot environment; `fixed` → `verified` once someone (ideally
     the reporter) checked it there; a failed check goes back to `accepted`. `verified` → `closed`.
   - The participant's own severity is a hint, not the decision. A participant-reported **blocker** that nobody has
     triaged holds the go/no-go until it is triaged.
5. **Record sign-offs** from the forms the day they come back (Participants › Record sign-off): signed off, signed off
   with comments, or blocked (name the UAT-… items or describe them). The latest sign-off per participant and script
   counts; earlier ones stay as history.
6. **Go / no-go.** The report says **Go** when: no blocking item is open (accepted and not fixed) or waiting for
   verification (fixed and not verified); no reported blocker waits for triage; every persona has at least one active
   participant and every active participant has signed off (with or without comments) — none blocked, none pending. The
   reasons list says what is missing. Export the CSV for the go/no-go meeting; S-118's go-live checklist reads the same
   report through `ca.northline.uat.api.UatReadiness`.

## What feedback holds (and doesn't)

- What the person chose and typed (category, their severity, the text), the **screen** as a path (no host, query string
  or fragment; a token-looking path segment, or one after `/invite/`, `/unsubscribe/`, … becomes `:token`), the app
  **version** (web: `VITE_NL_APP_VERSION` at build time, else `dev`; app: the Expo version), the **language**, and
  **browser and OS** (“Firefox 131 · macOS”; app: “Northline app · ios 18”) — nothing else from the device.
- The text, the screen and the device line go through the log redaction (S-112) before they are stored: secrets and
  tokens, emails, phone numbers, card numbers, one-time codes and postal codes are masked even when the person typed them.
- **Screenshot** (web only, optional): the browser's own screen capture (the person picks what to share; the dialog hides
  while capturing) or an attached image. PNG or JPEG, at most 5 MB, checked as S-104 asks (declared type, magic bytes,
  a readable image within the pixel ceiling). Stored in object storage under `uat/customers/` followed by the person's id, served only to
  staff on the Pilot UAT screen. An attached screenshot that is never sent is removed after a day.
- Access, correction and erasure (S-105): the `uat` module exports the person's feedback and participation; erasure
  blanks their words, device line and screenshots and replaces their id, keeping the triage record for the launch
  decision. The staging refresh masks the same columns (`db/dr/mask/uat.sql`).

## Local and environments

Nothing to configure: no new environment variable. Screenshots use the storage port (`STORAGE_PROVIDER`; locally the
`northline.uat.screenshots-dir` folder in the temp directory). The dev seed (V326, local only) adds a fake pilot group —
the three persona businesses, two customers, a courier and Priya Natarajan as staff — with feedback at each triage
stage and a few sign-offs, so `make up` shows a filled Pilot UAT screen.
