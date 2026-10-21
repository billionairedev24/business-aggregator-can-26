# CASL — consent and unsubscribe for commercial messages (S-108, S-157)

Canada's anti-spam legislation (CASL) governs **commercial electronic messages**: email, text messages and similar
messages that encourage someone to buy, or that promote Northline. This page says which of Northline's messages are
commercial, how consent is captured and proven, how people withdraw it, and what operators check. Québec's Law 25
also bears on consent wording (separate per purpose, clear and simple terms); the wordings below follow both. Code:
`ca.northline.email.MessageClasses` (the classification), `ca.northline.messaging` (consent records, the unsubscribe
page, the settings, the SMS keywords, the offers) and the worker's `notifications` package (senders); decisions in
`docs/DECISIONS.md` § S-108 and § S-157.

> **Never run against a real email or SMS provider.** Commercial email has only gone to the test fakes and the
> library's in-memory sender; the RFC 8058 one-click POST has been exercised with MockMvc, not by Gmail, Outlook or
> Yahoo; the SMS opt-out link has only been "sent" to the recording SMS transport. S-157's Twilio webhooks (STOP /
> START / HELP, delivery statuses) were tested with Twilio's documented signature example and MockMvc, never with a
> real Twilio number or handset. See [§ 8](#8-first-real-send-checklist).

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
| Sign-up: the unticked offers boxes of the consumer site's and the Studio's Create account forms (S-154's form) and of the app's sign-up (S-157) | northline-auth: `GET /api/auth/accounts/consent-wordings?app=consumer\|studio`, then `marketing: [{category, wordingVersion}]` on `POST /api/auth/accounts` or `POST /api/auth/register`; recorded when the account is created | `web_signup`, `app_signup` |
| Checkout: the unticked "Offers by email" box (shop, food, bookings on the web; shop and bookings in the app), shown only without the consent (S-157) | `PUT /api/v1/me/consents/marketing_email` after the order or booking went through | `checkout` |
| A text reply STOP / ARRET / UNSUBSCRIBE / DÉSABONNER (withdraws), START after a STOP (gives it back) — S-157, [§ 9](#9-text-replies-stop-start-help-and-delivery-statuses-s-157) | `POST /api/v1/webhooks/sms/inbound` (Twilio-signed) | `sms_keyword` |
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

**Sign-up (S-157):** the form shows the wordings northline-auth serves (customers: email and texts, the texts box only
with a mobile number; Studio: email; the console: none), each unticked, with who asks. A ticked box is checked against
the current wording (a stale version is 422 "The consent wording has changed…", before the existing-account branch so
the answer never tells two addresses apart), kept with the pending account (`auth.pending_accounts.marketing_consents`,
V452) or the phone registration's session, and written to `messaging.consent_records` in the transaction that
creates the account — wording version, language, the form's time, the truncated IP and hashed user agent. An abandoned
sign-up leaves nothing. **Checkout (S-157):** the box is shown only to someone without the email consent, and recorded
after the purchase; recording it never blocks or fails the order.

**SMS STOP / ARRET (S-157):** see [§ 9](#9-text-replies-stop-start-help-and-delivery-statuses-s-157). Every commercial
text still carries the opt-out link, and now ends "or reply STOP" / « ou répondez ARRET ».

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
  SMS — `Northline: <offer> — Northline Marketplace Inc. · Opt out: https://api…/api/v1/email/unsubscribe?t=… or reply STOP` (S-157: the keyword works too)
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
| records of a withdrawn consent | **3 years after the withdrawal** (`ConsentRetention.PROOF_PERIOD`) | deleted by the nightly retention job `messaging.consent_records` (S-107, `RETENTION_CRON`; [retention.md](retention.md)) |
| after an erasure (S-105) | granted consents withdrawn (`erasure`), network and browser evidence dropped; the rest kept as `consent_proof` | purged 3 years later like any withdrawal |

`messaging.api.ConsentRetention` exposes the period and `purgeExpiredProofs(now)`; the retention schedule's
`messaging.consent_records` category (S-107) calls it nightly with the other retention jobs, counts it in dry runs and
shows it in Console › Privacy › Retention. S-108's own daily purge (`CASL_PURGE_CRON`) is retired: one scheduler, one
report, one audit trail. Calling the purge again is harmless.

## 7. Configuration

| variable | app | default | notes |
|---|---|---|---|
| `EMAIL_LEGAL_NAME` | api, worker | `Northline Marketplace Inc.` | the legal sender (configuration, never code); required |
| `EMAIL_MAILING_ADDRESS`, `EMAIL_CONTACT` | api, worker | see [email.md](email.md#variables) | the mailing address and contact every message and the unsubscribe page show |
| `EMAIL_UNSUBSCRIBE_KEY`, `API_PUBLIC_URL` | api, worker | development key, `http://localhost:8080` | sign and host the unsubscribe links (required under staging/prod) |
| `RETENTION_CRON` | api | `0 47 2 * * *` | when expired proofs are purged, with the other retention jobs ([retention.md](retention.md)); `CASL_PURGE_CRON` (S-108) is retired |

No secret is added; the region and legal entity come from configuration, nothing province-specific is in code.

## 8. First real send checklist

1. Send a `marketing-offer` to a seed address at Gmail, Outlook and Yahoo with consent granted; check the
   "Unsubscribe" the mailbox shows and that pressing it withdraws the consent (`source = list_unsubscribe`).
2. Check the provider passes `List-Unsubscribe` and `List-Unsubscribe-Post` unchanged (SES, SendGrid, Resend and Azure ACS
   adapters set them as custom headers; SendGrid's own subscription tracking must stay **off**).
3. DKIM must sign the `List-Unsubscribe` headers (Gmail and Yahoo bulk-sender rules); check `h=` in the signature.
4. Send a commercial SMS to a test handset: the opt-out link opens the page in the handset's language; carriers may
   rewrite long links — check the full token arrives.
5. Reply STOP to that number: Northline records the withdrawal (`source = sms_keyword`, Account › Notifications shows
   it) and the reply confirms it; the provider then blocks the next send (21610). Reply START: texts work again and the
   offers consent comes back (it was withdrawn by STOP). Reply HELP. Check the Messaging Service's own opt-out texts
   don't contradict ours (§ 9).
6. Have counsel review the five wordings (en/fr) and the footer line before the first campaign.
7. Point the number's status callback at `SMS_STATUS_CALLBACK_URL` and check `messaging.sms_delivery_status` fills in
   (`delivered`, or `undelivered` with Twilio's error code).
8. Send a console offer to a market with two seed customers (one consenting): only one gets it, at the throttle rate,
   with the footer; the other never.

## 9. Text replies (STOP, START, HELP) and delivery statuses (S-157)

Texts to Northline's number reach the api through the SMS provider's webhook, authenticated by its signature:

| provider | inbound texts | delivery statuses | authentication |
|---|---|---|---|
| `twilio` | `POST /api/v1/webhooks/sms/inbound` — the number's (or Messaging Service's) "A message comes in" webhook, HTTP POST | `POST /api/v1/webhooks/sms/status` — every text the api and the worker send asks for it (`StatusCallback` = `SMS_STATUS_CALLBACK_URL`) | `X-Twilio-Signature` (HMAC-SHA1 with `SMS_AUTH_TOKEN`) over the URL Twilio was given: `API_PUBLIC_URL` + the path, never the request's own host; 403 `invalid_signature` otherwise |
| `local` | the same endpoints, **unsigned, under the local and test profiles only** (`curl -d MessageSid=SMlocal1 -d From=+14035550201 -d Body=STOP localhost:8080/api/v1/webhooks/sms/inbound`) | same | none |
| `aws`, `azure` | not built (their inbound texts arrive through SNS / Event Grid): 503 `sms_webhooks_unconfigured` | — | — |

- **STOP**, STOPALL, UNSUBSCRIBE, CANCEL, END, QUIT, **ARRET**, DÉSABONNER, DESABONNEMENT (case, accents and punctuation
  aside; a sentence is not a keyword) withdraws `marketing_sms` of **every active account with that number**, at once
  (CASL allows 10 business days), source `sms_keyword`; the TwiML reply confirms it in the account's language (both
  when the number isn't an account's): "you're unsubscribed — no more offers by text. Texts from this number stop until
  you reply START."
- **START** (UNSTOP, YES, OUI, ABONNER) turns texts back on at the provider. It gives the **offers consent** back only
  when that consent was withdrawn by a STOP — with the wording `keyword.sms.2026-10`, quoted in the reply; otherwise
  offers stay off ("turn them on in Account › Notifications"): a START is not express consent to offers never asked for.
- **HELP** (INFO, AIDE) answers with the legal name and `EMAIL_CONTACT`.
- Anything else gets no reply. The provider's message id dedupes a retried webhook (applied and answered once).
- **Stored:** `messaging.sms_inbound` (provider, message id, keyword, how many accounts, the sender as the SHA-256 of
  its number — the consent records' address hash) and `messaging.sms_delivery_status` (latest status per text; a final
  one — delivered, undelivered, failed — is never replaced by a late `sent`), V453; no number or text; kept 90 days
  (nightly, `SmsKeywordService`). Metrics `northline_sms_inbound_total{keyword}`, `northline_sms_delivery_total{status}`.

**Twilio set-up (once per number, test and live):** Messaging Service → Integration → "Send a webhook" to
`https://api.<zone>/api/v1/webhooks/sms/inbound`; set `SMS_STATUS_CALLBACK_URL=https://api.<zone>/api/v1/webhooks/sms/status`
on the api and the worker. Twilio's Advanced Opt-Out handles STOP itself on long codes and may answer before (or
instead of) Northline's TwiML: set its opt-out, opt-in and help confirmation texts to the same words as above (en / fr),
so the person reads one consistent answer; Northline records the keyword either way.

## 10. Offers from the console (S-157)

The console's **Offers** screen (admins and finance; sending and testing need the `offers` action) is Northline's only
producer of commercial messages: an offer in English and French (title ≤ 80, message ≤ 600 characters), one channel
(email or text) and a page of the consumer site.

1. **Preview** renders it as customers get it — the email's subject and text with the CASL footer (legal name, mailing
   address, the unsubscribe line), the text with `— legal name · Opt out: link or reply STOP` and its SMS segment
   count — and counts the **customers of the market** (a saved address in the province) whose newest consent for that
   channel is a customer-facing grant (Account wording or the START keyword; the Studio's business consent doesn't
   count).
2. **Send me a test** goes to the staff member's own email or mobile only, subject or text marked "[Test]"
   (audit `offer.test_sent`).
3. **Send** (after a confirmation naming the count) writes `messaging.offer_campaigns` (V454) and queues one
   `messaging.offer` row per customer in the worker's `messaging.deferred_notifications`, `OFFERS_PER_MINUTE` (30) a
   minute; audit `offer.sent`. The worker (deferred job, every minute, at most 50 rows) re-reads each person and their
   consent before sending (a withdrawal meanwhile cancels it), sends only that channel, holds a text inside the
   person's quiet hours until they end, and adds the unsubscribe link and `List-Unsubscribe` one-click. The list shows
   each offer's customers, how many are still to send and how many were given up (dead rows, S-115).

Not built: scheduling, targeting finer than a province, images, A/B tests, per-offer results (opens, clicks).

