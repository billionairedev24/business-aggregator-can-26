# PCI DSS SAQ A v4.0.1 — answer sheet (S-110)

**Status: prepared, not attested.** This sheet holds Northline's answer to every requirement of the Self-Assessment
Questionnaire A for PCI DSS v4.0.1 (the revision of January 2025, "r1"), with the evidence and the owner of each. It is
what the signer transcribes into the official questionnaire and the Attestation of Compliance ([aoc.md](aoc.md)).

- **Check the list against the official PDF before signing.** It was transcribed by the engineering team, not copied
  from the PCI SSC document (the document library was not reachable from the build environment). If the PDF has a line
  this sheet lacks, add it; if this sheet has one the PDF lacks, drop it.
- **Answers** use the SAQ's terms: *In Place*, *In Place with CCW* (compensating control), *Not Applicable* (with the
  reason), *Not in Place*. **"Not yet"** marks lines that will be *In Place* only after a launch task — each names its
  owner. Every "not yet" must be closed, or the SAQ submitted with a remediation date, before live payments.
- **Owners** are roles; the people are named in [aoc.md](aoc.md) when the company assigns them.
- Card data flows: [cardholder-data-flow.md](cardholder-data-flow.md). Stripe-side checklist:
  [stripe-review.md](stripe-review.md). Scripts on payment pages: [payment-page-scripts.md](payment-page-scripts.md).

## Merchant and channel

Northline Marketplace Inc. (the legal entity of the Terms), a marketplace that is **merchant of record** for card
payments (Stripe Connect, separate charges and transfers). Channels: e-commerce only — the consumer website and the
consumer apps for iOS and Android. No card-present, no mail or telephone orders, no card data on paper.
Payment service provider: **Stripe** (PCI DSS Level 1 service provider). Card entry: Stripe Payment Element (an
iframe served from `js.stripe.com`) on the website, Stripe PaymentSheet (Stripe's SDK UI) in the apps.

## Eligibility criteria

| # | criterion | answer | evidence | owner |
|---|---|---|---|---|
| E1 | Accepts only card-not-present (e-commerce or MOTO) transactions | Yes | [cardholder-data-flow.md](cardholder-data-flow.md); no terminal, no MOTO flow in the product | payments owner |
| E2 | All processing of account data is entirely outsourced to a PCI DSS compliant third-party service provider | Yes — Stripe | S-11 charge model ([DECISIONS](../../DECISIONS.md)); `payments/infra/StripeConnectGateway` | payments owner |
| E3 | Does not electronically store, process or transmit account data on its systems or premises | Yes | `CardDataGuard` (422 `card_data`), `CardDataScanTest`, `CardDataRegressionTest`, Redactor; `payments.customer_cards` keeps brand, last4, expiry only | engineering lead |
| E4 | Has reviewed the TPSP's PCI DSS attestation and confirmed it covers the services used | **Not yet** — download Stripe's AOC once the account exists | [stripe-review.md](stripe-review.md) #13 | payments owner |
| E5 | Any account data retained is on paper and not received electronically | Not Applicable — no paper records of card data | — | ops lead |
| E6 | Every element of the payment page(s) delivered to the consumer's browser originates only and directly from a PCI DSS compliant TPSP (embedded iframe) | Yes — the card fields are Stripe's iframe; the hosting page is Northline's | `Payment.tsx`, `StripeCard.tsx`, `CardForm.tsx`; app: PaymentSheet | engineering lead |
| E7 | The merchant has confirmed its site is not susceptible to attacks from scripts that could affect its e-commerce systems (added January 2025, in place of 6.4.3 and 11.6.1) | **Not yet** — supported by the script inventory, the Studio's enforced CSP and the consumer's report-only CSP; confirm after the consumer CSP is enforced and violations alert | [payment-page-scripts.md](payment-page-scripts.md) | security lead |

## Requirements

### Requirement 2 — secure configurations

| req. | requirement | answer | evidence | owner |
|---|---|---|---|---|
| 2.2.2 | Vendor default accounts are removed, disabled or their passwords changed | In Place (code and images) · **not yet** (cloud accounts) | images run non-root without shells or admin consoles (S-14, `web/Dockerfile`, Helm `securityContext`); managed Postgres/Kafka/Valkey created by Terraform with generated credentials (S-3); confirm in each real cloud account at launch | platform/SRE lead |

### Requirement 3 — protect stored account data

| req. | requirement | answer | evidence | owner |
|---|---|---|---|---|
| 3.1.1 | Security policies and procedures for requirement 3 are documented, in use and known | In Place | this sheet; [cardholder-data-flow.md](cardholder-data-flow.md); the request guard and scanner described in `CardDataGuard` / `CardDataScanTest`; Privacy Policy § 1 ("we never store card numbers") | security lead |
| 3.2.1 | Account data storage is kept to a minimum (retention, deletion, sensitive authentication data not kept after authorisation) | Not Applicable — Northline stores no account data. Brand, last4 and expiry without the PAN are not account data | `make pci-scan`: schema names, every column's contents, seeds, contracts, logs | engineering lead |

### Requirement 6 — secure systems and software

| req. | requirement | answer | evidence | owner |
|---|---|---|---|---|
| 6.3.1 | Security vulnerabilities are identified from industry sources and risk-ranked | **Not yet** — no dependency or image vulnerability scanning runs (CI is manual-only); S-104 (security) owns it | CI workflows `.github/workflows/*.yml` | security lead |
| 6.3.3 | Critical patches installed within one month of release | **Not yet** — follows 6.3.1; base images and dependencies are pinned (lock files, image digests), so patches are deliberate pull requests | `web/pnpm-lock.yaml`, `server/gradle/libs.versions.toml`, Dockerfiles | security lead |
| 6.4.3 | *(removed from SAQ A in January 2025; kept as the means for E7)* Payment-page scripts authorised, integrity-assured, inventoried | In Place for the inventory and authorisation; integrity by origin only (Stripe.js can't take SRI) | [payment-page-scripts.md](payment-page-scripts.md), `SCRIPT_INVENTORY` + `csp.test.ts` | web lead |

### Requirement 8 — identify users and authenticate access

Applies to the people who can change the payment pages or the payment set-up: the Stripe dashboard, the cloud
accounts and cluster, the source repository and CI, the container registry.

| req. | requirement | answer | evidence | owner |
|---|---|---|---|---|
| 8.2.1 | Every user has a unique ID | In Place (policy) · **not yet** (accounts don't exist) | Stripe: one login per person, roles ([stripe.md § 2 step 1](../../runbooks/stripe.md#2-platform-account-setup-once-per-mode)); cloud: SSO identities, workload identity for apps (no shared keys, S-6) | platform/SRE lead |
| 8.2.2 | Shared or generic accounts only on exception, attributable | In Place (policy) | no shared logins in any runbook; break-glass accounts to be listed in [aoc.md](aoc.md) | platform/SRE lead |
| 8.2.5 | Access of terminated users revoked immediately | **Not yet** — leaver checklist: Stripe dashboard, cloud SSO, GitHub, registry, on-call; a Developer leaving also rolls the Stripe keys ([key-rotation.md § 4](../../runbooks/key-rotation.md#4-stripe-api-keys-and-webhook-secrets)) | — | ops lead |
| 8.3.1 | All user access authenticated (something you know, have or are) | In Place (policy) | Stripe two-step authentication required; cloud SSO with MFA; GitHub 2FA required for the organisation | platform/SRE lead |
| 8.3.5 | First-use and reset passwords unique and changed on first use | Not Applicable — Northline issues no passwords to these systems; Stripe, the IdP and GitHub enforce their own flows | — | platform/SRE lead |
| 8.3.6 | Passwords at least 12 characters, letters and numbers | In Place by the providers' policies · **not yet** confirmed for the chosen IdP | IdP password policy | platform/SRE lead |
| 8.3.7 | New passwords differ from the last four | as 8.3.6 | IdP policy | platform/SRE lead |
| 8.3.9 | Passwords changed every 90 days when they are the only factor | Not Applicable — MFA everywhere (8.3.1) | — | platform/SRE lead |

### Requirement 9 — physical access to cardholder data

| req. | requirement | answer | evidence | owner |
|---|---|---|---|---|
| 9.4.1 | Media with cardholder data physically secured | Not Applicable — no media or paper holds cardholder data | E3, E5 | ops lead |
| 9.4.1.1 | Offline media backups with cardholder data stored securely | Not Applicable — backups hold no cardholder data (the database never does) | `CardDataScanTest`; backups-dr.md | ops lead |
| 9.4.1.2 | Their location reviewed yearly | Not Applicable | as above | ops lead |
| 9.4.2 | Media classified by sensitivity | Not Applicable | — | ops lead |
| 9.4.3 | Media sent outside secured, logged, tracked | Not Applicable | — | ops lead |
| 9.4.6 | Hard-copy materials with cardholder data destroyed when no longer needed | Not Applicable — support never takes card numbers on paper or by phone; a card number received by email or chat is deleted ([stripe-incidents.md § 6](../../runbooks/stripe-incidents.md#6-payment-page-tampering-or-card-data-found)) | support macros (S-83) | support lead |

### Requirement 11 — test security regularly

| req. | requirement | answer | evidence | owner |
|---|---|---|---|---|
| 11.3.2 | External vulnerability scans by a PCI SSC Approved Scanning Vendor (ASV) at least every three months, passing | **Not yet** — needs the public hosts (site, api, auth, Studio) and an ASV contract; Stripe's dashboard can connect an ASV | — | security lead |
| 11.3.2.1 | External scans after any significant change | **Not yet** — follows 11.3.2 | — | security lead |
| 11.6.1 | *(removed from SAQ A in January 2025; kept as the means for E7)* Unauthorised changes to payment pages detected | Partly — report-only CSP + `/csp-report` log lines; alerting and a weekly synthetic check not yet | [payment-page-scripts.md § Change detection](payment-page-scripts.md#change-detection-1161) | SRE lead |

### Requirement 12 — policies and third-party service providers

| req. | requirement | answer | evidence | owner |
|---|---|---|---|---|
| 12.8.1 | A list of TPSPs with which account data is shared or that could affect its security, with the services | In Place | **Stripe** (payments, card vault, Connect, Identity, Radar); **the cloud provider** hosting the payment pages and the edge (AWS, Google Cloud or Azure, Canadian region — chosen at launch); **Google Fonts** (CSS and fonts on the payment pages); **GitHub** (source and CI building the pages); the **container registry** | security lead |
| 12.8.2 | Written agreements with TPSPs acknowledging their responsibility for the account data they handle or the security of the CDE | **Not yet** — Stripe Services Agreement (accepted when the account is opened) includes Stripe's PCI DSS commitment; cloud provider's terms + its PCI DSS AOC | — | payments owner, legal |
| 12.8.3 | An established process for engaging TPSPs with due diligence | **Not yet** — adding a provider that could touch payment pages = security review + an entry in 12.8.1 + `SCRIPT_INVENTORY` if it serves a script | [payment-page-scripts.md](payment-page-scripts.md) | security lead |
| 12.8.4 | TPSPs' PCI DSS compliance monitored at least yearly | **Not yet** — yearly calendar task: download Stripe's and the cloud's current AOCs into this folder | — | payments owner |
| 12.8.5 | Which requirements each TPSP manages and which the entity manages | In Place | [stripe-review.md § Responsibility split](stripe-review.md#responsibility-split-requirement-1285) | security lead |
| 12.10.1 | An incident response plan exists and is ready to be activated if a breach is suspected | In Place (written) · not exercised | [stripe-incidents.md § 6](../../runbooks/stripe-incidents.md#6-payment-page-tampering-or-card-data-found) (skimming, card data found), § 5 (leaked keys) | security lead |

## "Not yet" items and owners

| item | owner | needs |
|---|---|---|
| E4 Stripe AOC reviewed; 12.8.4 yearly | payments owner | the Stripe account |
| E7 site not susceptible to script attacks (enforce consumer CSP with nonces, alert on violations, weekly synthetic check) | security lead (web lead, SRE lead) | engineering work; a deployed site for the check |
| 2.2.2 vendor defaults in the cloud accounts | platform/SRE lead | the cloud accounts |
| 6.3.1, 6.3.3 vulnerability identification and patching | security lead | S-104, a scanner in CI |
| 8.2.1, 8.2.5, 8.3.6, 8.3.7 accounts, leavers, IdP policy | platform/SRE lead, ops lead | the IdP and accounts |
| 11.3.2, 11.3.2.1 ASV scans | security lead | public hosts, an ASV |
| 12.8.2, 12.8.3 TPSP agreements and process | payments owner, legal, security lead | the Stripe account, cloud contract |
| Stripe dashboard settings: restricted key, webhook endpoints, Radar rule, 3-D Secure, Connect profile, team 2FA | payments owner | the Stripe account ([stripe-review.md](stripe-review.md)) |

## Who signs, and what needs a real Stripe account

- **Who signs.** The Attestation of Compliance (Part 3) is signed by an **executive officer of Northline Marketplace
  Inc.** (the "merchant executive officer"), on the recommendation of the security lead who completed the SAQ. No QSA
  or ISA is needed for SAQ A unless Stripe or its acquirer asks for one (Part 3b/3c stay blank). The completed SAQ and
  AOC go to **Stripe** (as the acquirer's agent; Stripe's dashboard has a PCI compliance page that takes the
  attestation) — not to the card brands directly.
- **Needs the Stripe account:** Stripe's own AOC (E4, 12.8.4); Stripe's PCI page in the dashboard (it may pre-fill
  SAQ A for Elements users and record the attestation); the restricted key, the webhook endpoints and secrets, Radar
  and 3-D Secure settings, the Connect platform profile, dashboard members with two-step authentication; the Services
  Agreement (12.8.2). An ASV scan (11.3.2) needs the public hosts.
- **Never run against real Stripe:** the Payment Element mount, PaymentSheet, 3-D Secure and webhooks from Stripe
  itself — the code was tested against stripe-mock and fakes (S-11, S-12, S-51, S-59, S-99, S-100).

## How to re-run the evidence

```sh
make pci-scan     # CardData unit tests, Redactor, the request guard, the regression test and the full scanner
make legal-check  # the legal registry (S-106) — not PCI, but run with it before a release
```
