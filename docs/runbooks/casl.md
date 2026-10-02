# CASL — consent and unsubscribe for commercial messages (S-108)

Canada's anti-spam legislation (CASL) governs **commercial electronic messages**: email, text messages and similar
messages that encourage someone to buy, or that promote Northline. This page says which of Northline's messages are
commercial, how consent is captured and proven, how people withdraw it, and what operators check. Québec's Law 25
also bears on consent wording (separate per purpose, clear and simple terms); the wordings below follow both. Code:
`ca.northline.email.MessageClasses` (the classification), `ca.northline.messaging` (consent records, the unsubscribe
page, the settings) and the worker's `notifications` package (senders); decisions in `docs/DECISIONS.md` § S-108.

> **Never run against a real email or SMS provider.** Commercial email has only gone to the test fakes and the
> library's in-memory sender; the RFC 8058 one-click POST has been exercised with MockMvc, not by Gmail, Outlook or
> Yahoo; the SMS opt-out link has only been "sent" to the recording SMS transport. See [§ 8](#8-first-real-send-checklist).

## 1. Which messages are commercial

| message | class | consent needed | unsubscribe |
|---|---|---|---|
| Studio › Settings › Notifications rows (bookings, quotes, messages, payouts, disputes, stock, quality) | transactional / relationship | no | notification rows carry the S-13 link (turns the row's email off) |
| Account › Notifications rows: booking reminders, order updates, sign-off, quotes & messages, refunds & cases, security | transactional / relationship | no | the S-102 link (turns the row's email off); security alerts: none |
| Account › Notifications row **offers & rewards** (push, SMS, email) | **commercial** | **express, per channel** | email: link + RFC 8058 headers; SMS: opt-out link; push: the settings |
| Template `marketing-offer` (Northline's offer to a customer) | **commercial** | express (`marketing_email`) | link + RFC 8058 headers |
| Team invitations, sign-in and step-up codes, privacy-request codes, courier run pushes | transactional | no | none |
| A business's own marketing to its customers | **not sent by the platform** | — | — (the Privacy Policy forbids businesses to add customers to marketing lists) |

The classification is code: `MessageClasses.ROWS` (every notification row), `MessageClasses.OTHER` (messages outside
the matrices) and each email template's `EmailContent.Purpose`. Tests fail on anything unclassified:
`MessageClassesTest` (a template file without a record and sample), `ConsentApiTest` (an api matrix row),
`CommercialNoticesTest` (a row of the worker's defaults file). An unclassified row is treated as commercial at send
time (fail closed).

**Implied consent is not used.** CASL allows implied consent for 2 years after a purchase and 6 months after an
inquiry, but Northline's marketing relies on express consent only (the Privacy Policy: "Only with your express
opt-in"). The schema allows only `basis = 'express'`; adding implied consent means a migration, its expiry, and a
policy change.

## 2. Consent records

`messaging.consent_records` (V300) is append-only: one row per grant or withdrawal of one category by one person. The
current state of a category is its newest row.

| column | what |
|---|---|
| `category` | `marketing_email`, `marketing_sms`, `marketing_push` |
| `action`, `at` | `granted` / `withdrawn`, when |
| `source` | `web_signup`, `app_signup`, `web_settings`, `app_settings`, `checkout`, `studio`, `import` (where the person consented) · `unsubscribe_link`, `list_unsubscribe`, `sms_keyword`, `console`, `erasure` (withdrawals) |
| `wording_version`, `language` | the exact wording shown (a version of `ConsentWordings`, never edited once published) and `en` / `fr` |
| `address_hash` | SHA-256 of the email (lower-cased) or phone the consent covered; push has none |
| `ip_prefix`, `user_agent_hash` | the request's network (`203.0.113.0/24`, IPv6 /48) and the user agent's SHA-256: minimised evidence |
| `actor_id` | the staff member, for `console` |

**Wordings** (`ConsentWordings`, en and fr-CA): each names the legal sender (`EMAIL_LEGAL_NAME`), what is sent, on
which channel, and that consent can be withdrawn at any time; the requester's mailing address and contact are shown
next to it (CASL regulations s. 4). `account.email.2026-10`, `account.sms.2026-10`, `account.push.2026-10`,
`studio.email.2026-10`. To change words, add a **new version** (and pin its hash in `ConsentWordingsTest`); records
keep pointing at the version that was shown. A client that sends an older version gets 409 `consent_wording_changed`.

**Double opt-in is not used**: consent is given in a signed-in session for the account's own address; a confirmation
email would add a message without making the proof stronger for that case. Revisit if a signed-out sign-up form for
marketing is ever added.

## 3. Where people consent and withdraw

| where | api | source |
|---|---|---|
| Consumer web, Account › Notifications ("Offers & rewards" cells, "Marketing emails", the "Marketing messages" section with wording and history) | `PUT /api/v1/me/notifications` (`consentSource`, `consentWordings`) | `web_settings` |
| Consumer app, You › Notifications (same; turning the offers push off is the push promo opt-out) | same | `app_settings` |
| Studio › Settings › Notifications › "Marketing from Northline" (email only) | `PUT /api/v1/me/consents/marketing_email` | `studio` |
| Sign-up and checkout checkboxes | `PUT /api/v1/me/consents/{category}` accepts `web_signup`, `app_signup`, `checkout` | **not built yet** |
| An email's unsubscribe link (page with one button) | `POST /api/v1/email/unsubscribe` | `unsubscribe_link` |
| A mailbox's one-click unsubscribe (RFC 8058, `List-Unsubscribe=One-Click`) | same URL | `list_unsubscribe` |
| A commercial SMS's opt-out link | same page | `unsubscribe_link` |
| Staff, for a person who asked by phone, mail or email | `POST /api/v1/console/consents/withdrawals` | `console` |
| The account's erasure (S-105) | the messaging contributor | `erasure` |

Withdrawals take effect **in the same request** (CASL allows up to 10 business days). The unsubscribe page needs no
sign-in: the link carries an HMAC-signed token (`EMAIL_UNSUBSCRIBE_KEY`, rows `consent.<category>`), answers in the
token's language (en/fr), never changes anything on GET (mail scanners prefetch links), and names the legal sender
with its mailing address and contact. The ingress must route `/api/v1/email/unsubscribe` to the api without the BFF
session (as for S-13).

**SMS STOP / ARRET:** the SMS port (`SmsTransport`) only sends; it receives no inbound messages, so Northline doesn't
see STOP replies. Every commercial text carries an opt-out link instead (CASL allows a link). Carriers and providers
still handle STOP themselves for long codes (Twilio's default opt-out; AWS End User Messaging opt-out lists), and a
number that replied STOP fails with Twilio 21610, which the adapter maps to "undeliverable" (not retried). Recording
those as withdrawals needs an inbound webhook (the reserved source `sms_keyword`) — not built.

## 4. Senders check consent at send time

- **Email:** the shared `Mailer` refuses a `COMMERCIAL` email without the recipient's id and asks `CommercialConsent`
  right before it goes (`NO_CONSENT`: nothing sent, nothing claimed). The api's `CommercialConsent` is the messaging
  module; the worker's reads the same table (`JdbcConsents`).
- **Worker (push, SMS, email of the `offers` row):** `Deliveries` asks for the channel's consent before every send —
  also for what quiet hours or a provider outage held back in `messaging.deferred_notifications`, so a consent
  withdrawn overnight stops the morning's message. The matrix cells stored for the `offers` row are ignored.
- **Content check:** a commercial email must name the legal sender, the mailing address and carry the unsubscribe link
  in both bodies (`CommercialMessageCheck`, after rendering); a commercial SMS must name the legal sender and carry the
  opt-out link. A failing message is never sent (a bug, not a delivery problem).
- **What a commercial message looks like:** email — the footer's sender line, "Northline Marketplace Inc. sent you
  this marketing message because you agreed…", the unsubscribe link, `List-Unsubscribe` + `List-Unsubscribe-Post`;
  SMS — `Northline: <offer> — Northline Marketplace Inc. · Opt out: https://api…/api/v1/email/unsubscribe?t=…`
  (French: "Désabonnement :"); push — the title and words only (no personal data), turned off in Account ›
  Notifications.

**Nothing produces commercial messages yet.** The worker understands an offer (`PersonalNotices.OFFER`, payload
`PersonalNotices.offer(...)`) and the email library has `marketing-offer`; the first campaign or offer feature hands
them over.

## 5. Consent history and proof

- **The person:** Account › Notifications on the web and in the app ("Your consent history"), Studio's panel, and the
  S-105 access export (section `messaging.consents`: category, action, time, source, wording version, language,
  network; not the hashes).
- **Staff:** the console's privacy screen (admin, privacy officer, support lead) → "Consent to marketing (CASL)":
  search by account id, email or phone (matched by the address hash, so it works after an erasure); withdraw on the
  person's behalf (action `privacy`; audit-logged as `consent.withdrawn`). API: `GET /api/v1/console/consents`.

## 6. Retention

| data | kept | then |
|---|---|---|
| records of an active consent | while it is active (express consent doesn't expire) | — |
| records of a withdrawn consent | **3 years after the withdrawal** (`ConsentRetention.PROOF_PERIOD`) | deleted by the daily purge (`CASL_PURGE_CRON`, default 04:23 platform zone) |
| after an erasure (S-105) | granted consents withdrawn (`erasure`), network and browser evidence dropped; the rest kept as `consent_proof` | purged 3 years later like any withdrawal |

`messaging.api.ConsentRetention` exposes the period and `purgeExpiredProofs(now)` for the retention jobs and report
(S-107); calling it again is harmless.

## 7. Configuration

| variable | app | default | notes |
|---|---|---|---|
| `EMAIL_LEGAL_NAME` | api, worker | `Northline Marketplace Inc.` | the legal sender (configuration, never code); required |
| `EMAIL_MAILING_ADDRESS`, `EMAIL_CONTACT` | api, worker | see [email.md](email.md#variables) | the mailing address and contact every message and the unsubscribe page show |
| `EMAIL_UNSUBSCRIBE_KEY`, `API_PUBLIC_URL` | api, worker | development key, `http://localhost:8080` | sign and host the unsubscribe links (required under staging/prod) |
| `CASL_PURGE_CRON` | api | `0 23 4 * * *` | when expired proofs are purged |

No secret is added; the region and legal entity come from configuration, nothing province-specific is in code.

## 8. First real send checklist

1. Send a `marketing-offer` to a seed address at Gmail, Outlook and Yahoo with consent granted; check the
   "Unsubscribe" the mailbox shows and that pressing it withdraws the consent (`source = list_unsubscribe`).
2. Check the provider passes `List-Unsubscribe` and `List-Unsubscribe-Post` unchanged (SES, SendGrid and Azure ACS
   adapters set them as custom headers; SendGrid's own subscription tracking must stay **off**).
3. DKIM must sign the `List-Unsubscribe` headers (Gmail and Yahoo bulk-sender rules); check `h=` in the signature.
4. Send a commercial SMS to a test handset: the opt-out link opens the page in the handset's language; carriers may
   rewrite long links — check the full token arrives.
5. Reply STOP to that number and confirm the provider blocks the next send (21610) — it is not recorded as a
   withdrawal by Northline yet.
6. Have counsel review the four wordings (en/fr) and the footer line before the first campaign.
