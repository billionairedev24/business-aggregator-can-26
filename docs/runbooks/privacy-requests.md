# Privacy requests: access, correction, erasure (S-105)

How people use their privacy rights on Northline — a copy of their personal information (access), a correction, the
deletion of their account (erasure) — under the private-sector privacy law of their province, and how staff handle the
requests. Code: `ca.northline.privacy` (api; schema `privacy`), the per-module contributors
(`ca.northline.<module>.persistence.<Module>PersonalData`, interface `ca.northline.shared.privacy.PersonalDataContributor`),
`ca.northline.region` (the laws), northline-auth (`ErasedAccounts`). Clients: consumer site Account › Profile ›
**Your data**, the consumer app You › Personal details › **Your data** (`/account/data`), Studio › Settings ›
Security › **Your personal data**, console › **Privacy requests** (`/privacy`).

> **Status (2026-10-02).** The laws' deadlines in `region.privacy_laws` were drafted from the statutes and need legal
> review before launch (table below). Nothing was run against a real KMS, S3 / GCS / Azure bucket or Stripe here: the
> export sealing, the bundle storage and the saved cards' detach are tested against the local key, RustFS / fake-gcs /
> Azurite and the fake card gateway. Google / Microsoft calendar grants are deleted locally, not revoked at the
> provider.

## 1. Which law, which deadline

The law is region data, never code: `region.regions.privacy_law` (V130) names a province's law, `region.privacy_laws`
(V271) what the law requires. A request records the province and law it was received under.

| law (`code`) | provinces (V130) | answer within | extension | regulator |
|---|---|---|---|---|
| PIPEDA (`pipeda`) | every province without its own act | 30 days | once, up to 30 days | Office of the Privacy Commissioner of Canada |
| Alberta PIPA (`ab_pipa`) | AB | 45 days | once, up to 30 days | OIPC Alberta |
| BC PIPA (`bc_pipa`) | BC | 30 **business** days (no Saturdays, Sundays or BC holidays of the region model) | once, up to 30 days | OIPC BC |
| Québec private sector act / Law 25 (`qc_law25`) | QC | 30 days | none | Commission d'accès à l'information |

- **Whose province:** the person's default address; else the province of the first business they are on the team
  of; else `REGION_DEFAULT_PROVINCE`. An unknown province falls back the same way, and PIPEDA when nothing is known.
- **Deadline:** the end of the N-th day after receipt in the province's time zone (`PrivacyRegimes.deadline`). It
  starts at receipt, verification or not.
- **Changing a law's rules** is a row update in `region.privacy_laws` (requests already received keep their due date;
  an extension is computed with the row at the time).

## 2. A request's life

```
awaiting_verification ──verify──▶ verified ──start──▶ in_progress ──▶ completed
        │                             │
        └────── withdraw / refuse ────┴──▶ withdrawn | rejected
```

| type | after verification |
|---|---|
| access | the pipeline builds the export at once → `completed` with a download that lasts `PRIVACY_EXPORT_TTL` |
| erasure | waits `PRIVACY_ERASURE_GRACE` (7 days, never later than a day before the deadline) — the person can still withdraw it; staff can **Start erasure now** |
| correction | waits for staff, who apply the corrections they accept (`completed`) or refuse |

One open request per person and type (409 `request_open`). Every transition, every erasure step, every download and
every correction is a `developer.audit_log` row (`action` = `privacy.request_*`, `target_type` = `privacy_request`,
ids and codes only); the console's request detail and the audit log viewer show them.

**Identity check.** A request from the account is verified by (a) a fresh step-up proof — the passkey or the
authenticator code at northline-auth (`/api/auth/step-up/*`), sent as `X-Step-Up` — or (b) a 6-digit code the api
texts to the account's verified mobile (15 minutes, 5 tries, a new code at most once a minute). (b) exists because a
phone-code account has no other factor and the app must be able to delete it. A request staff record (email, mail,
phone) waits for staff to verify the person by hand (**Identity verified**).

## 3. The erasure pipeline

When a verified erasure is due (`PrivacyScheduler`, every `PRIVACY_RUN_INTERVAL`):

1. **The account closes at once** (identity `PrivacyAccounts.close`): `identity.users.status = 'erased'`, every
   sign-in ended (`identity.sessions.revoke_reason = 'erased'` — northline-auth drops the auth session on its next
   request), passkeys and console roles removed. northline-auth refuses any new or refreshed token for the account
   (`invalid_grant`), and its `ErasedAccountsJob` (every 5 minutes) deletes the account's WebAuthn credentials,
   authenticator secret, backup codes, linked Google / Apple identities, consents and OAuth authorizations. An access
   token already issued lives out its 10 minutes.
2. **One step per module** (`privacy.erasure_steps`), in `order()`: every module, then identity last (it blanks the
   name and contact others may look rows up by; the request keeps that contact sealed until the end).
3. **Each step runs in one transaction with its row:** the module's `erase()` and the step's status commit together.
   A crash, a deploy or an outage leaves the step `pending` / `failed`; the next run takes it up (back-off 2, 4, 8 …
   minutes, at most 6 h). Every module's erasure is idempotent. Replicas share the work (`FOR UPDATE SKIP LOCKED`).
4. **Holds.** A module that can't erase yet (an order on its way, money still held, an open refund or dispute, a
   delivery under way, the only owner of an open business) answers `held`; the step is retried every
   `PRIVACY_HOLD_RETRY`. The request **completes** (the law's answer) once no step is pending or failed, with
   `holds_open` counting what is still held; the sealed contact is wiped when the last hold clears (audit
   `privacy.request_holds_cleared`).
5. **Events carry ids only:** `privacy.personal_data_erased` (in-process: request id, account id, holds left) and
   `merchants.merchant_data_erased` (request id, business id) for every business whose public data changed — published
   on the business's own topic `merchants.merchant`, so the search indexer re-reads it (a review's author name and
   words gone). The Elasticsearch documents hold no personal data (listings and businesses only), so there is nothing
   else to remove from search.

### What each module keeps after erasure, and why

Kept data stays under the account's id — now a blank pseudonym (no name, email, phone, birthday) — and without the
person's free text.

| module | erased | kept (reason) |
|---|---|---|
| identity | name, email, phone, pronouns, birthday, reliability score; passkeys, console roles, future on-call shifts, household memberships; sign-ins' device, IP and city; addresses' street, unit, notes, map point, label | the account row as a pseudonym (`financial_records`: orders, payments and reviews point at it); addresses' city, province and postal code's first three characters (`tax_records`: place of supply) |
| account | favourites, preferences (diet, allergies, access notes) | — |
| orders | carts, unplaced checkouts; a food order's street, unit, notes and map point | orders, placed checkouts and lines (`tax_records`); **held** while an order is on its way |
| booking | finished bookings' street address and job details, their sealed access notes; quote requests' job descriptions | bookings' service, dates, prices and tax (`tax_records`); jobs a team member did (`business_records`); **held** while a booking is to come |
| payments | saved cards (detached from the Stripe customer, rows deleted); the name copied onto settled escrows, refunds and decided disputes | payments, escrows, refunds, disputes (`financial_records`); a dispute's statement and evidence (`chargeback_evidence`); **held** while money is held, a refund is open or a dispute undecided |
| messaging | push devices (S-102), notification settings, the inbox, held-back notifications, the customer's uploads (rows and objects under `messaging/customers/<id>/`), what a customer wrote in conversations | help cases without subject, label and account context (`business_records`); what a team member wrote, without their name (`business_records`) |
| trust | a review's author name and words; ratings businesses gave the person; points | the review's star rating in the business's rating (`business_records`) |
| fulfilment | a finished delivery's drop-off address and instructions; a courier's scheduled shifts (cancelled), the courier made inactive | deliveries and proof of delivery (`chargeback_evidence`); a courier's runs (`business_records`); **held** while a delivery is under way |
| merchants | team memberships, invitations sent to the person's email or mobile | a principal's legal name and identity checks, unlinked from the account and without the email (`kyc_records`); **held** while the person is the only owner of an active, paused, pending or suspended business |
| availability | connected calendars (sealed refresh tokens, busy blocks, mirrored events), own hours, time off | — (the grant at Google / Microsoft is not revoked; the person removes it there) |
| region | waitlist entries (by account or email) | — |
| ai | usage records | — |
| catalogue, food | pending commerce / POS OAuth states | who uploaded listing files or handled a kitchen ticket (ids only, `business_records`) |
| developer | — | the audit log (`audit_log`: append-only, ids and codes only, seven years) |
| privacy | the request's sealed contact and asked corrections (when it closes) | the request itself: type, dates, law, decision, steps (accountability, ids only) |

`PrivacyContributorsTests` fails when a module that owns a schema has no contributor.

## 4. The access export

One JSON document (`format: northline.privacy-export/v1`) with every module's sections exactly as stored (Postgres
renders the rows), and a readable summary in the person's language (what we hold, what we would keep after deletion,
the regulator). Both are sealed together (envelope encryption, `KMS_PROVIDER`, bound to the request id) and stored at
`privacy/exports/<request id>.bin` (`STORAGE_PROVIDER`; a temp folder under `local`). The person asks for a
**download link** (`POST /api/v1/me/privacy-requests/{id}/download-link`): a 256-bit token, stored hashed, valid
`PRIVACY_LINK_TTL` (15 minutes), served by `GET /api/v1/public/privacy-exports/{token}[?part=summary]` (no session:
the app hands it to the share sheet). The bundle is deleted `PRIVACY_EXPORT_TTL` (7 days) after it was built (hourly
sweep); the person can ask again.

## 5. Corrections

For what people can't change themselves (`GET /api/v1/me/privacy-requests/correctable-fields`): `phone` (the
verified mobile; identity), `receiptName` (the name copied onto escrows, refunds and disputes; payments),
`reviewName` (the name on their reviews; trust — through V272's privacy door in the reviews' immutability trigger),
`legalName` (a business principal's legal name; merchants). Staff read what was asked in the request detail and
**Apply corrections**; each field is audit-logged by code, never by value.

## 6. Console

Screen **Privacy requests** (`ConsoleScreen.PRIVACY`): the privacy officer (role `privacy`, new), support leads and
admins. Every change needs the `privacy` action (same three roles). Grant the role in Team & audit. The queue lists
open requests soonest deadline first (overdue marked), or closed ones.

| action | when |
|---|---|
| Record a request | someone asked by email, mail or phone: enter the account's email or mobile |
| Identity verified | you checked who the person is (for a recorded request, or a stuck one) |
| Extend | once, with the reason the law accepts (many records, consultations, conversion); refused where the law has none |
| Refuse | identity not verified, not our data, a legal exception, duplicate, frivolous — the person sees the reason and the regulator |
| Start erasure now | skip the grace period |
| Apply corrections | the corrections you accept |
| Retry failed steps | after fixing what made a module fail |

## 7. Configuration

| variable | default | what |
|---|---|---|
| `PRIVACY_ERASURE_GRACE` | `P7D` | wait before a verified erasure starts |
| `PRIVACY_EXPORT_TTL` | `P7D` | how long an export can be downloaded |
| `PRIVACY_LINK_TTL` | `PT15M` | a download link's life (capped at `PT1H`) |
| `PRIVACY_HOLD_RETRY` | `P1D` | when a held step is retried |
| `PRIVACY_RUN_INTERVAL` | `PT1M` | how often the pipeline looks for work |

The exports use the existing `STORAGE_*` and `KMS_*` settings; codes go out through `SMS_PROVIDER`. No new secret.

## 8. Operations

```sql
-- overdue requests
SELECT number, type, state, law, coalesce(extended_to, due_at) AS due FROM privacy.requests
 WHERE state IN ('awaiting_verification', 'verified', 'in_progress') AND coalesce(extended_to, due_at) < now();
-- erasure steps that keep failing (the exception class is in last_error; the log has the request id and module)
SELECT r.number, s.module, s.attempts, s.last_error, s.next_attempt_at FROM privacy.erasure_steps s
  JOIN privacy.requests r ON r.id = s.request_id WHERE s.status = 'failed' ORDER BY s.attempts DESC;
-- what is still held, and why
SELECT r.number, s.module, s.holds FROM privacy.erasure_steps s JOIN privacy.requests r ON r.id = s.request_id
 WHERE s.status = 'held';
```

- **A failing module:** fix the cause, then **Retry failed steps** (or wait for the back-off). The logs say
  `Privacy request <id>: erasure in <module> failed (<exception>)` — never a name.
- **Held as a business's only owner:** the owner adds another owner in Studio › Settings › Team (or the business
  closes, which isn't modelled yet); the next retry erases the membership.
- **A regulator asks:** the request row and its audit entries show when it was received, verified, extended, answered
  and what each module kept.
- **Never run here:** see the status note above.

## 9. Retention (S-107)

What happens to data nobody asked about once the Privacy Policy's period ends: [retention.md](retention.md). The
retention jobs reuse this page's machinery — the same legal holds (`PersonalDataContributor.Hold`), the same rule for
a person's province and law (`region.privacy_laws` now also says what a law keeps after a decision), and for
"Account profile: deleted within 30 days of closure" the erasure pipeline itself: an erasure still unfinished 30 days
after the account closed has its pending and failed steps run at once. A person with a request still open keeps their
data as it is until the request ends. Console › Privacy › Retention shows the schedule, each job's last run, rows
changed and held, and exports it as CSV.
