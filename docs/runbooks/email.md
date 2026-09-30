# Transactional email (S-13)

The api sends team invitations and money notices (payout receipts, bank-account changes, disputes, refund cases) by
email. The code is the shared library `server/email` (package `ca.northline.email`), which the notifications worker
(S-27) will reuse. This runbook covers local use with Mailpit, the provider set-up per cloud, DNS (SPF/DKIM/DMARC), the
variables, CASL and operations. Decisions and trade-offs: `docs/DECISIONS.md` § S-13.

- [How it works](#how-it-works)
- [Local: Mailpit](#local-mailpit)
- [Choosing a provider](#choosing-a-provider)
- [Amazon SES (AWS)](#amazon-ses-aws)
- [SendGrid (Google Cloud, or anywhere)](#sendgrid-google-cloud-or-anywhere)
- [Azure Communication Services Email (Azure)](#azure-communication-services-email-azure)
- [Any SMTP relay](#any-smtp-relay)
- [DNS: SPF, DKIM, DMARC](#dns-spf-dkim-dmarc)
- [Variables](#variables)
- [CASL: sender identification and unsubscribe](#casl-sender-identification-and-unsubscribe)
- [Operations](#operations)

## How it works

| piece | where |
|---|---|
| Port `EmailSender`, one adapter per provider (`smtp` package also serves `local`), retries of transient failures | `server/email` — `EmailAutoConfiguration` creates **only** the adapter `EMAIL_PROVIDER` selects |
| Templates: `email/templates/<template>.html` (inline styles) + `.txt`, copy in `email/messages[_fr].properties` | `server/email/src/main/resources` |
| `Mailer`: render in the recipient's language → CASL footer → `List-Unsubscribe` headers (notifications) → send **once** per `(event, recipient)` | `server/email` (de-duplication in `events.processed_events`, consumer `email`) |
| Team invitation: `TeamInvitationIssued` (internal event) → `TeamInvitationDelivery` listener | api `merchants` |
| Money notices: `PayoutSent`, `PayoutAccountChanged`, `DisputeUpdated`, `DisputeDecided`, `RefundCaseUpdated`, `RefundIssued` → `MerchantEmailNotices` | api `messaging` (owns Settings › Notifications) |
| Unsubscribe page / one-click POST: `GET|POST /api/v1/email/unsubscribe?t=…` | api `messaging` (public route) |
| Resubmission of listeners that failed because the provider was down (every 10 min, ~4 h) | api `config.FailedEmailResubmission` |

Every send happens **after the business transaction commits** (Modulith `@ApplicationModuleListener`): the user's
action never waits for, or fails because of, email. A provider outage leaves the event publication failed; it is
resubmitted and members already emailed are skipped.

Who gets what:

| email | template | recipients | Settings › Notifications row | purpose |
|---|---|---|---|---|
| Team invitation | `team-invitation` | the invited address (inviter's language) | — | transactional |
| Bank account changing / active | `bank-account-change` | owners | — (security notice, always sent) | transactional |
| Payout sent (receipt) | `payout-sent` | owners, bookkeepers | `payout` | notification |
| Dispute opened / offer declined / offer expired / decided | `dispute-update` | owners | `dispute` | notification |
| Refund requested / approved / with an agent / denied / paid | `refund-case-update` | owners | `dispute` | notification |

**SMS** (the bank-change text the Payouts screen promises) is **not** sent by the api: the SMS port lives in
northline-auth (S-8). `payout_account.changed` is already on Kafka (`payments.payout_account`); the S-27
notifications consumer sends the text. Mobile-number team invitations are likewise not delivered yet — the invite
dialog shows the link (`sent: false`).

## Local: Mailpit

```sh
docker compose --profile mail up -d          # SMTP :1025, inbox http://localhost:8025
cd server && ./gradlew :api:bootRun --args='--spring.profiles.active=local'
```

`EMAIL_PROVIDER` defaults to `local` = SMTP to `SMTP_HOST:SMTP_PORT` (`localhost:1025`). Without Mailpit the api
still works: each email's text (with its links) is logged at WARN — `No SMTP server took the email…`.

Try it — invite someone as Ravi (dev auth) and open the inbox:

```sh
curl -s -X POST localhost:8080/api/v1/merchants/01J9ZD3V00000000000000PWM1/settings/team/invitations \
  -H 'X-Dev-User: 01J9ZD3V00000000000000RAV1' -H 'Content-Type: application/json' \
  -d '{"email":"sam@example.com","role":"technician"}'
curl -s localhost:8025/api/v1/messages | jq '.messages[] | {To, Subject}'
```

Previews of every template and variant with sample data (profile `local` only):
`http://localhost:8080/api/v1/dev/emails` (list), `…/dev/emails/payout-sent?lang=fr-CA` (HTML),
`…&format=text` (plain text).

Ports taken? Set `MAILPIT_SMTP_PORT` / `MAILPIT_UI_PORT` in the root `.env` and `SMTP_PORT` in `server/.env`.

## Choosing a provider

| cloud | recommended | `EMAIL_PROVIDER` | residency |
|---|---|---|---|
| AWS | Amazon SES in `ca-central-1` | `ses` (API v2) — or `smtp` with SES SMTP credentials | stays in Canada |
| Google Cloud | SendGrid (Google Cloud has no first-party email service) | `sendgrid` — or `smtp` with any relay | leaves Canada (US) — disclosed in the Privacy Policy |
| Azure | Azure Communication Services Email | `azure` | ACS data location **Canada** for the resources; delivery is global |

All adapters take the same message (text + HTML + headers) and report the same two failure kinds:
**rejected** (bad or suppressed address, content refused — logged, never retried) and **unavailable** (5xx, 408/429,
time-outs, 401/403 credentials or unverified sender — retried `EMAIL_RETRY_ATTEMPTS` times with back-off, then the
event is resubmitted later).

## Amazon SES (AWS)

1. **Region** `ca-central-1` (SES → Configuration → Identities).
2. **Verify the domain** (Create identity → Domain → `mail.northline.ca` or `northline.ca`) with **Easy DKIM**
   (RSA 2048). Publish the three `CNAME` records SES shows (`<token>._domainkey…` → `<token>.dkim.amazonses.com`).
3. **Custom MAIL FROM** (identity → Custom MAIL FROM domain, e.g. `bounce.northline.ca`): an `MX` record
   `10 feedback-smtp.ca-central-1.amazonses.com` and an SPF `TXT` `v=spf1 include:amazonses.com ~all` on that
   subdomain. This aligns SPF for DMARC.
4. **Leave the sandbox** (Account dashboard → Request production access): use case "transactional notifications for
   a marketplace (invitations, payout receipts, dispute notices)", expected volume, and how bounces/complaints are
   handled (SES account-level suppression list on; complaints → the Settings unsubscribe). In the sandbox SES only
   delivers to verified addresses — fine for `dev`.
5. **Configuration set** (optional, `EMAIL_CONFIGURATION_SET`): event destinations for bounces/complaints
   (SNS/EventBridge) and a dedicated IP pool later. Turn click/open tracking **off** — invitation links carry tokens.
6. **Permissions** for the `northline-api` workload identity (EKS Pod Identity / IRSA), no static keys:
   ```json
   {"Effect": "Allow", "Action": "ses:SendEmail",
    "Resource": ["arn:aws:ses:ca-central-1:<account>:identity/northline.ca",
                 "arn:aws:ses:ca-central-1:<account>:configuration-set/<set>"]}
   ```
7. Variables: `EMAIL_PROVIDER=ses`, `EMAIL_REGION=ca-central-1`, `EMAIL_FROM=Northline <no-reply@northline.ca>`,
   optionally `EMAIL_CONFIGURATION_SET`. (`EMAIL_ENDPOINT` only for a VPC endpoint or LocalStack.)

SES SMTP instead (`EMAIL_PROVIDER=smtp`): SMTP credentials from SES → SMTP settings (an IAM user; keep them in the
secrets manager), `SMTP_HOST=email-smtp.ca-central-1.amazonaws.com`, `SMTP_PORT=587`, `SMTP_STARTTLS=true`.

## SendGrid (Google Cloud, or anywhere)

1. Account on a paid plan (dedicated sending reputation later; the free tier caps volume).
2. **Domain authentication** (Settings → Sender Authentication → Authenticate your domain, "automated security" on):
   publish the `CNAME`s SendGrid shows (`em1234.northline.ca`, `s1._domainkey`, `s2._domainkey`). Link branding is
   optional — the adapter turns click and open tracking **off** per message, so links are never rewritten.
3. **API key** (Settings → API Keys → Restricted access → **Mail Send: Full access** only). Store it in the secrets
   manager as `EMAIL_API_KEY` (External Secrets → Kubernetes Secret).
4. Suppressions: keep SendGrid's global bounce/spam suppressions on (a suppressed address answers 202 and is dropped by
   SendGrid; hard 4xx answers are logged as rejected).
5. Variables: `EMAIL_PROVIDER=sendgrid`, `EMAIL_API_KEY=SG.…`, `EMAIL_FROM=Northline <no-reply@northline.ca>` (the
   domain must be the authenticated one, or SendGrid answers 403 → unavailable).

## Azure Communication Services Email (Azure)

1. Create an **Email Communication Service** and a **Communication Service** resource, data location **Canada**.
2. Email Communication Service → Provision domains → **Custom domain** `northline.ca` (or a subdomain): verify with the
   `TXT` record, then add the **SPF** (`TXT v=spf1 include:spf.protection.outlook.com -all`) and the two **DKIM**
   `CNAME`s (`selector1-azurecomm-prod-net._domainkey`, `selector2-…`) Azure shows; wait for all to show *Verified*.
3. Domain → MailFrom addresses: add `no-reply` with display name "Northline". ACS ignores the display name in
   `EMAIL_FROM` and uses this one.
4. Communication Service → Email → **Connect domain**.
5. Authentication — pick one:
   - **Entra ID (recommended):** leave `EMAIL_API_KEY` empty; the api uses `DefaultAzureCredential` (AKS workload
     identity). Grant the `northline-api` identity the built-in role "Communication and Email Service Owner" scoped
     to the Communication Service resource (or a custom role with `Microsoft.Communication/CommunicationServices/Read`
     and `…/Write`).
   - **Access key:** `EMAIL_API_KEY` = Keys → Primary key (or the whole connection string); store it in Key Vault.
     Rotate by switching to the secondary key, restarting, regenerating the primary.
6. Sending limits: new domains start with low per-minute/hour quotas — request a quota increase before go-live.
7. Variables: `EMAIL_PROVIDER=azure`, `EMAIL_ENDPOINT=https://<resource>.canada.communication.azure.com` (the
   Communication Service endpoint), `EMAIL_FROM=no-reply@northline.ca`, `EMAIL_API_KEY` (or empty for Entra ID).

## Any SMTP relay

`EMAIL_PROVIDER=smtp` with `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_STARTTLS=true`
(required whenever credentials are set). Works with SES SMTP, SendGrid SMTP (`smtp.sendgrid.net`, user `apikey`),
Azure Communication Services SMTP, Mailgun, Postmark or Postfix. SMTP 5xx on a recipient = rejected; 4xx, TLS,
authentication and connection problems = unavailable.

## DNS: SPF, DKIM, DMARC

On the sending domain (records per provider above; one provider per domain — use `mail.` / `notify.` subdomains to
run two):

| record | value | notes |
|---|---|---|
| SPF (`TXT` on the envelope/MAIL FROM domain) | `v=spf1 include:amazonses.com ~all` · `include:sendgrid.net` · `include:spf.protection.outlook.com` | one SPF record per name; merge includes; ≤ 10 DNS lookups |
| DKIM | the provider's `CNAME`s (SES 3, SendGrid 2, ACS 2) | 2048-bit keys; providers rotate them |
| DMARC (`TXT _dmarc.northline.ca`) | start `v=DMARC1; p=none; rua=mailto:dmarc@northline.ca; adkim=r; aspf=r; pct=100` | move to `p=quarantine`, then `p=reject`, once reports show only aligned mail (2–4 weeks) |
| MX for bounces | SES custom MAIL FROM only | `10 feedback-smtp.ca-central-1.amazonses.com` |

Gmail and Yahoo require SPF **and** DKIM, DMARC, one-click unsubscribe (RFC 8058) on bulk mail and a spam rate
below 0.3 % — the notifications carry `List-Unsubscribe` + `List-Unsubscribe-Post`. Check a new set-up with
mail-tester.com or by viewing the headers of a received email (`spf=pass dkim=pass dmarc=pass`).

## Variables

| variable | app | required | example | notes |
|---|---|---|---|---|
| `EMAIL_PROVIDER` | api (worker later) | staging, prod (`local` refused there) | `ses` · `sendgrid` · `azure` · `smtp` | default `local` |
| `EMAIL_FROM` | api | staging, prod | `Northline <no-reply@northline.ca>` | domain verified at the provider |
| `EMAIL_REPLY_TO` | api | no | `support@northline.ca` | empty = no Reply-To |
| `EMAIL_MAILING_ADDRESS` | api | no | `Northline Marketplace Inc. · 1200 – 8th Avenue SW, Calgary, Alberta T2P 1B5, Canada` | CASL footer (default = the Terms' address) |
| `EMAIL_CONTACT` | api | no | `support@northline.ca` | CASL footer |
| `EMAIL_REGION` | api | with `ses` (else SDK default chain) | `ca-central-1` | |
| `EMAIL_ENDPOINT` | api | with `azure` | `https://nl-prod.canada.communication.azure.com` | also a VPC endpoint / mock |
| `EMAIL_API_KEY` | api | with `sendgrid`; `azure` with an access key | `SG.…` | **secret** |
| `EMAIL_CONFIGURATION_SET` | api | no | `northline-transactional` | SES only |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_STARTTLS` | api | with `smtp` | `email-smtp.ca-central-1.amazonaws.com`, `587`, …, `true` | `SMTP_PASSWORD` is a **secret** |
| `EMAIL_RETRY_ATTEMPTS`, `EMAIL_RETRY_BACKOFF` | api | no | `3`, `2s` | in-process tries per email; back-off doubles |
| `EMAIL_UNSUBSCRIBE_KEY` | api | staging, prod | `openssl rand -base64 32` | **secret**; HMAC key of unsubscribe links. Rotating it breaks the links in emails already sent (the page then points to Settings) |
| `API_PUBLIC_URL` | api | staging, prod | `https://api.northline.ca` | where unsubscribe links point; the ingress must route `/api/v1/email/unsubscribe` to the api **without** the BFF session |
| `STUDIO_ORIGIN` | api | yes (already) | `https://studio.northline.ca` | buttons in the emails |

## CASL: sender identification and unsubscribe

- Every email identifies the sender: name and mailing address (`EMAIL_MAILING_ADDRESS`) plus a contact
  (`EMAIL_CONTACT`), and says why the address received it.
- **Transactional** messages (invitation requested by an owner, security notices) have no unsubscribe link (CASL
  s. 6(6) exemptions) but still identify the sender.
- **Notifications** a member can turn off (payout receipts, disputes, refunds) carry an unsubscribe link and the
  RFC 8058 one-click headers. The link turns that row's email cell off in Settings › Notifications at once (CASL
  allows up to 10 business days) and keeps working indefinitely (CASL: at least 60 days).
- **Commercial** messages (none yet) must use `Purpose.COMMERCIAL`: the renderer refuses them without an unsubscribe
  link. They also need recorded consent — out of scope until marketing email exists.

## Operations

- Logs: `Email <template> sent to r***@example.com (<eventId>:<userId>)`; rejections at WARN with the provider's
  answer; the full address is never logged.
- A provider outage: `Email … not sent` warnings, then the listener fails and `events.event_publication` holds the
  failed publication; `FailedEmailResubmission` retries every 10 min up to 24 times (~4 h), and all incomplete
  publications are republished at start-up. After that, find them with
  `select listener_id, publication_date, completion_attempts from events.event_publication where completion_date is null and (listener_id like '%MerchantEmailNotices%' or listener_id like '%TeamInvitationDelivery%');`
  — restart the api (republish on restart) once the provider is back.
- De-duplication rows: `events.processed_events` with `consumer = 'email'` (`event_id` = `<eventId>:<userId>` or the
  invitation event id). Deleting a row makes that email sendable again.
- Bounces/complaints: handled by the provider's suppression list for now (SES account-level suppression,
  SendGrid suppressions, ACS). Feeding them back into Settings is S-27 work.
- Switching provider = change the variables and restart; templates and behaviour are identical.

## Worker emails (S-27)

The worker's notifications consumer sends one email the api doesn't: `payout-failed` (a returned or canceled payout,
row `payout`, with an unsubscribe link signed with the same `EMAIL_UNSUBSCRIBE_KEY`). It uses this library with the
same variables, so the worker needs the email settings too. Which app sends what: [notifications.md](notifications.md).

