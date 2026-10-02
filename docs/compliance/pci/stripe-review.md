# Stripe integration security review (S-110)

Reviewed 2026-10-02 against Stripe's integration-security guidance (TLS, Stripe.js/Elements and the mobile SDKs, API
keys, webhooks, fraud tools, Connect). **No Stripe account exists yet**: everything under "Northline's side" was
checked in the code and the tests (stripe-mock, the fake gateways); everything under "needs the account" is a
dashboard setting or a document that can only be checked or obtained once the platform account exists. Owners are
roles (see [saq-a.md § Who signs](saq-a.md#who-signs-and-what-needs-a-real-stripe-account)).

| # | item | Northline's side (evidence) | needs the account | status |
|---|---|---|---|---|
| 1 | **Card entry only in Stripe-hosted fields** | web: Payment Element (iframe from `js.stripe.com`) in `Payment.tsx`, `StripeCard.tsx`, `CardForm.tsx`; app: PaymentSheet only (`stripe.native.ts`, `payments.ts`, `cardSetup.ts`); no card input anywhere else (`CardDataScanTest.clientsTakeCardsOnlyInStripeFields`) | — | done |
| 2 | **TLS everywhere** | edge: HTTPS only, TLS 1.2+, HSTS ([edge.md](../../runbooks/edge.md)); api → Stripe: stripe-java over HTTPS (TLS 1.2+ is the JDK 25 default); DB `sslmode=require` (Terraform outputs); the publishable key and client secrets reach clients only over the TLS'd api | Stripe's TLS checks run on live-mode activation | done |
| 3 | **Webhook signatures verified** | `StripeSignatureVerifier` / `StripeWebhookController`: `Webhook.constructEvent` (HMAC-SHA256, every `v1`), 5-minute tolerance, one secret per endpoint (platform, Connect), 400 on a bad, missing or stale signature, 503 when unconfigured; dedupe by event id in `payments.stripe_events`; personal fields dropped before storage (S-12); `StripeWebhookApiTest` | create both endpoints with the pinned API version, copy each `whsec_` into the secrets manager ([stripe.md § 5](../../runbooks/stripe.md#5-webhooks-s-12)) | code done; endpoints **not yet** |
| 4 | **Restricted API key** for the api | the api works with `rk_` or `sk_` (`STRIPE_SECRET_KEY`); the permission list is in [stripe.md § 2 step 12](../../runbooks/stripe.md#2-platform-account-setup-once-per-mode) | create the restricted key with exactly those permissions; optionally restrict it to the clusters' egress IPs | **not yet** — owner: payments owner |
| 5 | **Key storage** | secrets manager → External Secrets → environment only (S-6, [secrets.md](../../runbooks/secrets.md)); never in the repo, images, Helm values or the apps (the publishable key comes from the api at checkout); keys masked in logs (`sk_`, `rk_`, `whsec_` rules of the Redactor); test keys in dev/staging, live keys in prod only | put the keys in each environment's secrets manager | process done; **not yet** filled |
| 6 | **Key rotation** | yearly and on leave; leak = incident ([key-rotation.md § 4](../../runbooks/key-rotation.md#4-stripe-api-keys-and-webhook-secrets), [stripe-incidents.md § 5](../../runbooks/stripe-incidents.md#5-leaked-keys)) | first rotation drill | runbook done; **not exercised** |
| 7 | **API version pinned** | `2026-08-26.dahlia`, the api refuses to start on another SDK version (`StripeClients`); webhooks read raw JSON | set the account's default version and the endpoints' version to it | code done |
| 8 | **Idempotency keys** on every mutating call | `StripeIdempotencyKeys` (`nl1:…`), client keys folded in; tests assert every POST carries one | — | done |
| 9 | **Radar** | Northline relies on Stripe's default rules; escrow holds and manual capture add a human window | add *Block if `:risk_level: = 'highest'`*; review the rules quarterly; Radar for Fraud Teams is optional | **not yet** — owner: payments owner |
| 10 | **3-D Secure** | Payment Element / PaymentSheet handle challenges; off-session renewals fall back to asking the customer (`payment.reauthorization_required`) | leave Stripe's default (request when required / SCA); consider "request 3DS when Radar's risk is elevated" | code done; setting **not yet** |
| 11 | **Connect responsibilities** | separate charges and transfers, Northline is merchant of record: Northline carries refunds, chargebacks and negative balances (S-11), Express accounts with Stripe-hosted onboarding and KYC (Identity, S-22), manual payouts; bank changes only through the Studio with step-up and a 24 h hold | platform profile answers (marketplace, responsible for losses), Express only, Canada only, Express dashboard bank edits **off** ([stripe.md § 2](../../runbooks/stripe.md#2-platform-account-setup-once-per-mode)) | **not yet** |
| 12 | **Dashboard access** | — | two-step authentication for every member; roles Administrator (2), Developer, Support specialist, View only; leavers removed the same day (with a key roll if they were Developers) | **not yet** — owner: payments owner |
| 13 | **Stripe's PCI documents** | — | download Stripe's PCI DSS **Attestation of Compliance** (service provider) and keep it with this folder (requirement 12.8.4, yearly); Stripe's dashboard also offers its own SAQ A questionnaire under Settings › Compliance, pre-filled for Elements users — answer it with [saq-a.md](saq-a.md) | **not yet** |
| 14 | **No card data on Northline** | `CardDataGuard` (422 `card_data` on any JSON body with a PAN, track data, CVC or card-named field), `CardDataScanTest` (schema, contents, seeds, contracts, logs, clients), Redactor and Collector rules, `CardDataRegressionTest` | — | done |
| 15 | **Payment-page script control** | Studio enforced CSP; consumer report-only CSP from the script inventory + `/csp-report` ([payment-page-scripts.md](payment-page-scripts.md)) | — | partly; enforcement and alerting **not yet** |

## Responsibility split (requirement 12.8.5)

| PCI DSS area | Stripe | Northline |
|---|---|---|
| Card capture, transmission, storage, tokenisation (req. 3, 4) | yes — Elements iframe, PaymentSheet, vault | never receives card data; keeps ids, brand, last4, expiry |
| Payment page integrity (eligibility criterion; 6.4.3 / 11.6.1) | the iframe's own content | the page that hosts the iframe: scripts, headers, CSP, build pipeline |
| Fraud screening, 3-D Secure | Radar, SCA engine | rule choices, refunds, dispute handling |
| Connected accounts' KYC | Stripe Identity / Connect onboarding | owners ≥ 25 % verified before payouts (S-22) |
| Keys and webhooks | issue, verify TLS, sign events | store, rotate, verify signatures, never log |
| Incident response | Stripe's own; informs brands and acquirer | [stripe-incidents.md § 6](../../runbooks/stripe-incidents.md#6-payment-page-tampering-or-card-data-found) |
