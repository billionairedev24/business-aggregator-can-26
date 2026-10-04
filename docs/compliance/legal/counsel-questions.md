# Questions for counsel (S-106)

Every point the project has left for legal review, gathered from [DECISIONS.md](../../DECISIONS.md), S-107's
retention runbook ([retention.md § 7](../../runbooks/retention.md#7-mismatches-between-the-policy-and-the-code), merged
in [#147](https://github.com/billionairedev24/business-aggregator-can-26/pull/147)) and a read of the two legal pages
against what the product does. Each item names its source and the decision needed. The texts themselves
are listed in [review-packet.md](review-packet.md).

**53 questions** in ten groups. "Product" means the answer may change code; "Text" means it changes a legal text (a
new registered version, [review-packet.md § Sign-off](review-packet.md#sign-off-mechanism)).

## A. Privacy requests (S-105)

| # | question | source | decision needed |
|---|---|---|---|
| A1 | Response deadlines drafted from the statutes: PIPEDA 30 days + 30 extension; Alberta PIPA 45 + 30; BC PIPA 30 business days + 30; Québec 30, no extension. The clock starts at receipt (not at identity verification). Are these right, and is "receipt" the right start? | DECISIONS § S-105 "Region-neutral law"; `region.privacy_laws` (V271) | confirm or correct each law's days, extension and counting; the start event (Product: data only) |
| A2 | The Privacy Policy § 8 promises "we respond within 30 days" for everyone, but Alberta PIPA allows 45. Promise 30 everywhere (then the Alberta value becomes 30) or say "within the time the law of your province allows"? | privacy.html § 8; A1 | Text or data |
| A3 | Erasure starts after a **7-day grace period** (`PRIVACY_ERASURE_GRACE`, never later than a day before the deadline) during which the person can withdraw. Acceptable under each act? | DECISIONS § S-105 "States" | keep / shorten / remove the grace period |
| A4 | An erasure **completes while holds remain** (open orders, upcoming bookings, held escrow, open disputes, the only owner of a business): the law's answer is sent; the held module's data goes when the hold ends. Kept data is pseudonymised; proof-of-delivery photos and dispute evidence are kept for chargebacks; review ratings stay (words and name blanked). Is that answer adequate, and must the person be told what is kept and why? | DECISIONS § S-105 "Erasure pipeline", "Reviews"; privacy-requests.md | approve the hold and keep list; wording of the answer |
| A5 | Identity verification: a fresh second-factor step-up, else a 6-digit code to the verified mobile; staff verify requests made by email or mail. Sufficient for access (the riskiest — it discloses everything)? | DECISIONS § S-105 "Identity check" | approve, or require more for access |
| A6 | Policy § 8 says "From Settings → Privacy … Export … in JSON and CSV"; the product has Account › Profile › Your data and exports JSON plus a readable summary (no CSV). Change the text or the product? | privacy.html § 8; DECISIONS § S-105 "Access export" | Text or Product |
| A7 | Not built: telling third parties a corrected record was shared with; deleting the Stripe Customer object (cards are detached); revoking Google / Microsoft calendar grants at the provider; verifying by email an account with no mobile and no second factor. Which are legally required before launch? | DECISIONS § S-105 "Not done" | must-have list |

## B. Retention (S-107, #147)

| # | question | source | decision needed |
|---|---|---|---|
| B1 | **CASL proof of consent** is kept 3 years after withdrawal; the policy's § 6 doesn't list it. Add it? Is 3 years right? | retention.md § 7 item 1; DECISIONS § S-108 "Retention" | Text; period |
| B2 | The **audit log** (ids and codes of privileged actions, some of them sign-ins and security changes) is kept 7 years; the policy doesn't name it and says "Login and security logs: 12 months". Which applies to which entries? | retention.md § 7 item 2 | period per kind of entry; Text |
| B3 | **Québec**: civil prescription is 3 years for most claims (C.c.Q. art. 2925); "messages and dispute evidence: 2 years after the transaction" may be short for Québec customers. Lengthen for Québec (the region model can) or for everyone? | retention.md § 7 item 3 | period |
| B4 | **KYC "5 years after the relationship ends"**: no business can be closed or refused yet, so nothing starts the clock; the documents are at Stripe, Northline keeps owner names and outcomes. What ends the relationship? | retention.md § 7 item 4 | trigger definition (Product) |
| B5 | Personal data **kept with no end** because the policy gives no period: notification inbox, abandoned carts, saved addresses of open accounts, trust flags, AI usage counters, storefront visit counts, merchants' job photos. A period for each? | retention.md § 7 item 7 | periods; Text |
| B6 | What a privacy law keeps **after a decision about a person**: BC PIPA 365 days (s. 35(1)); PIPEDA, Alberta PIPA, Québec 0. Right? | DECISIONS § S-107 "Province-dependent periods" | confirm values |
| B7 | Seven-year transaction records are **pseudonymised, not deleted** (customer id and words cleared; amounts, tax, dates and the business kept) because deleting breaks merchants' books; "after the transaction" is a conversation's last message, a case's resolution, an escrow's date. Acceptable reading of § 6? | DECISIONS § S-107 "Actions" and "Clocks" | approve |
| B8 | No **litigation hold** exists (keep everything about a case past the policy); stopping the jobs is the only lever. Required before launch? | DECISIONS § S-107 "Not done" | yes / no |
| B9 | **Backup replicas** on Google Cloud and Azure don't receive deletions (S-114), so a deleted file survives there; AWS replicates deletes. The policy says backups roll off within 35 days. Fix before launch on those clouds? | retention.md § 7 item 5 | risk acceptance or fix (Product) |
| B10 | An erasure step held by an open order keeps that module's data **past the 30 days** after account closure the policy promises. Acceptable as the law's exception, and does the text need to say so? | retention.md § 7 item 6 | Text |

## C. CASL consent (S-108)

| # | question | source | decision needed |
|---|---|---|---|
| C1 | Approve the four **consent wordings** (en/fr): `account.email.2026-10`, `account.sms.2026-10`, `account.push.2026-10`, `studio.email.2026-10` — purpose, requester (`{legalName}`, mailing address and contact are shown with them), withdrawal statement. | `ConsentWordings.java`; DECISIONS § S-108 | approve or redraft (a new wording version) |
| C2 | **Push** promotions are treated as needing express consent (CASL's reach over app push is unsettled; the stricter reading). Keep? | DECISIONS § S-108 "Categories per channel" | keep / relax |
| C3 | Commercial **SMS opt-out is a link** to the unsubscribe page; texting STOP / ARRET isn't handled (no inbound SMS). Sufficient? | DECISIONS § S-108 "SMS STOP/ARRET" | approve, or require keyword handling before any commercial SMS |
| C4 | Only express consent, no implied consent (the policy says "only with your express opt-in"); no double opt-in (consent given in a signed-in session); **no grandfathering** of earlier settings. Agree? | DECISIONS § S-108 | approve |
| C5 | Proof of consent keeps an **unsalted SHA-256 of the address** after an erasure (so staff can match a complaint). Is a hashed email/phone still personal information that the erasure should remove? | DECISIONS § S-108 "Schema" | keep / drop on erasure |
| C6 | The design's defaults had offers push **on** and marketing **weekly**; they are off (pre-ticked consent is not consent). Confirm the deviation from the design. | DECISIONS § S-108 "Deviation" | confirm |

## D. Québec: Law 25 and the Charter of the French Language (Loi 96)

| # | question | source | decision needed |
|---|---|---|---|
| D1 | The Terms and Privacy Policy exist **only in English**; the Terms' closing language clause says "Ces conditions sont également disponibles en français" — untrue today. Contracts of adhesion with Québec consumers must be presented in French (Charter art. 55 as amended by Bill 96). French versions before Québec opens? Who translates; is the French version authoritative? | terms.html; DECISIONS § S-63 "Not done" | French texts; Text |
| D2 | App store listings exist in fr-CA but link the English Privacy Policy (S-103). Acceptable until D1? | DECISIONS § S-103 "Open" | approve or wait for D1 |
| D3 | Platform obligation 4 asks merchants for "French required when serving Québec" and WCAG AA. Is that the right obligation to put on merchants (Charter art. 51–52 for product labels and catalogues; storefront copy)? | Studio compliance `ob4`; registry `merchant-obligations` | wording |
| D4 | Law 25 requires a **privacy impact assessment** before personal information leaves Québec, and the policy promises one "before any transfer outside Canada". Transfers today: OpenRouter (US, AI prompts), Stripe, email and SMS providers, Google (Places, Fonts), Apple/Google push. Which need a written PIA before launch? | privacy.html § 5; DECISIONS "AI provider and data residency" | PIA list |
| D5 | Law 25 asks for the **person in charge of personal information** to be named (title and contact published on the website). The policy gives only privacy@northline.ca. | privacy.html § 8, footer | name/title to publish; Text |
| D6 | Law 25 technology that can identify, locate or profile a person must be **off by default** (s. 8.1, private-sector act). Location in the consumer app is asked for (optional); Stripe.js collects device signals for fraud on payment pages. Is a notice or a default change needed? | S-47, S-103 privacy answers; F2 | Product / Text |
| D7 | Québec Consumer Protection Act: the Terms' **governing law and forum** (Alberta courts, mediation first) and the liability cap — enforceable against Québec consumers (CPA art. 11.1, 10)? The Terms add "Consumers may always bring claims in their home province". | terms.html § 19, § 21 | Text |

## E. AI processing

| # | question | source | decision needed |
|---|---|---|---|
| E1 | **OpenRouter as a US processor** was accepted by the product owner (prompts only; data at rest in Canada). The Privacy Policy's processor list (§ 3) doesn't name an AI provider, and § 5 says data "is stored and processed in Canada". Disclosure wording, and is consent needed for Québec residents (D4)? | DECISIONS "AI provider and data residency", § S-129 | Text; PIA |
| E2 | The policy says "We do not use your messages, photos or documents to train machine-learning models". AI features send message text, listing copy and help questions to models for **processing** (writing help, reply drafts, triage, screening). Not training — but should the policy say so, and must OpenRouter's no-retention routing be contractual? | privacy.html § 2; DECISIONS § S-130–S-133 | Text; contract terms |
| E3 | **Automated decisions** (§ 10) lists reliability scores, fraud holds and ranking. AI trust & safety screening (S-133) flags listings and messages for staff; AI help triage (S-132) routes requests. Are these automated decisions that need the § 10 notice and review right? | DECISIONS § S-132, § S-133 | Text |

## F. Privacy Policy statements that don't match the product

| # | question | source | decision needed |
|---|---|---|---|
| F1 | § 1 "We do not collect **precise location** from customers" — the consumer app asks for precise location (optional, while in use; S-47, S-103 store answers), and couriers share location during runs. | privacy.html § 1; `store/privacy.json` | Text |
| F2 | § 9 "strictly necessary cookies … no third-party … fingerprinting; we honour **Global Privacy Control**": Stripe.js sets its own cookies and collects device signals on payment pages; the fonts load from Google; nothing reads GPC (`Sec-GPC`) yet. Is a cookie notice needed (there is none), and what does honouring GPC mean here? | privacy.html § 9; [payment-page-scripts.md](../pci/payment-page-scripts.md) | Text; Product (GPC) |
| F3 | § 5 names the cloud regions "in **Montréal and Toronto**": the platform runs on whichever cloud is chosen (AWS, Google Cloud, Azure), in one or two Canadian regions. Name the cities, or say "Canadian regions"? | privacy.html § 5; region-neutral rule (DECISIONS 2026-09-30) | Text |
| F4 | § 7 claims **annual penetration testing, a SOC 2 Type II programme**, review of all staff access, and **segregated escrow accounts**: none exists yet. Remove until true (misleading representation risk)? | privacy.html § 7 | Text |
| F5 | § 3 promises a current processor list at **northline.ca/subprocessors** and 30 days' notice before adding one: the page doesn't exist. | privacy.html § 3 | Text or a new page |

## G. Terms of Service and the money

| # | question | source | decision needed |
|---|---|---|---|
| G1 | Terms § 10 says Northline "holds your payment in a **segregated trust account**". In fact Northline is merchant of record and the money sits in Northline's Stripe balance (separate charges and transfers, manual capture) until it is transferred to the business. Is "escrow" / "trust account" accurate, and what must the text say? | terms.html § 10; DECISIONS § S-11 | Text |
| G2 | Does holding customers' funds until release make Northline a **payment service provider** under the Retail Payment Activities Act (registration with the Bank of Canada), or does an exclusion apply (Stripe as the PSP, Northline as merchant of record)? | DECISIONS § S-11 | legal opinion |
| G3 | Onboarding asks merchants to agree to the Business Terms "and the **Marketplace Facilitator tax arrangement**", as if it were a separate document; the Terms cover it in § 12 and B5. Is a separate agreement needed (GST/HST collected and remitted by Northline as marketplace facilitator), or should the checkbox point to § 12 / B5? | Studio onboarding `termsAfter`; terms.html § 12, B5; DECISIONS § S-21 | Text |
| G4 | The api records **Business Terms acceptance** when sent but doesn't require it (an open question since onboarding), and stores no version (`business_terms_accepted_at` only). Must acceptance be mandatory and versioned? | DECISIONS "Onboarding API — decisions the spec left open" | Product |
| G5 | The Terms name **Alberta and British Columbia** as served provinces, an "Alberta corporation with its head office in Calgary" and Alberta courts; the product is built for every province (region model). Keep place names in the legal text and update per launch, or generalise? | terms.html opening paragraph, § 1, § 21 | Text |
| G6 | Platform obligations (v2.3) state per-province facts with a placeholder: "{province} Consumer Protection Act — quotes honoured, 10-day cancellation on contracts > $200", and a 12-month anti-circumvention clause. True in each province? Enforceable? | Studio compliance `ob1`, `ob3`; registry `merchant-obligations` | Text |

## H. Age and restricted goods

| # | question | source | decision needed |
|---|---|---|---|
| H1 | The consumer app is rated **18+** (stores) because the catalogue has alcohol, tobacco and vape categories and the Terms require the age of majority — but the age of majority is 19 in several provinces, the Privacy Policy § 11 says "under 18", and nothing checks age at sign-up or checkout. What age, what check, where? **Since 2026-10-04 (owner decision):** the check is driven by the cart — a customer buying an age-restricted item verifies once with photo ID and a selfie (Stripe Identity) and must be the delivery province's minimum age for the class (H4); the courier or the counter checks photo ID again at handoff. Sign-up still asks no age. Remaining: the Terms' "age of majority" and Privacy Policy § 11's "under 18" against this. | DECISIONS § S-103 "Age ratings", § 2026-10-04 Age-restricted purchases; terms.html § 1; privacy.html § 11; [age-restricted.md](../../runbooks/age-restricted.md) | Text |
| H2 | **Alcohol, tobacco and vape**: the seed categories include "Alcohol", "Alcohol with food", "Tobacco & vape" (with a provincial tobacco act as regulator); the catalogue's banned list can switch leaves off by configuration. Which may be sold, in which provinces, under whose licence (the business's or Northline's as facilitator), and with what delivery rules (proof of age at handoff by the courier, no delivery to intoxicated persons, hours)? Federal Tobacco and Vaping Products Act limits on promotion apply to listings and search. **Built 2026-10-04 (owner decision), to confirm:** alcohol only for now (tobacco/vape and cannabis accessories stay banned — the app stores also refuse them); the business's own licence per class and province, vetted by staff, listings hidden without it or after expiry; delivery and pickup allowed per province and class with optional hours (region data, H4); the courier and the counter confirm photo ID, that it is the account holder (no gifting) and the age, and refuse to anyone who seems intoxicated; a refused goods order goes back to the shop (items refunded, delivery fee kept), a refused food order refunds the alcohol only. Is a marketplace courier delivering a licensed business's alcohol lawful in each province, or does a province require the retailer's own staff or a delivery licence? | `db/seed/categories.json`; DECISIONS § S-94, § 2026-10-04; catalogue `banned-categories`; [age-restricted.md](../../runbooks/age-restricted.md) | per-province allowed list; courier procedure; refund rules |
| H3 | Courier app: background location during runs, proof-of-delivery photos and the customer's signature. No **courier privacy notice** or courier terms exist. | DECISIONS § S-87, § S-103 | new texts |
| H4 | **The age table** (`region.age_rules`, V340): alcohol 18 in AB, MB, QC and 19 elsewhere; tobacco/vape AB 18, MB 18, QC 18, PE 21, all others 19 (SK since 2024); cannabis 18 AB, 21 QC, 19 elsewhere — does a province's cannabis age apply to accessories? Hours seeded only for alcohol in ON (delivery 09–23, pickup 07–23) and AB (10:00–02:00); none elsewhere. Every value came from public summaries (the statute sites were unreachable from the build machine) and is marked unconfirmed. Confirm each row, supply the other provinces' hours, and say where delivery or pickup is not allowed. | V340 header and `source` column; [age-restricted.md § The age table](../../runbooks/age-restricted.md#the-age-table-region-data) | each row (`confirmed = true` with the citation) |
| H5 | **The customer's ID check**: Stripe Identity (document + selfie; the face match is Stripe's biometric processing as Northline's processor). Northline keeps only "verified over N (capped at 21), on date, by method", while the account is open; the session is redacted at Stripe once the result is known; handoff checks (three yes/no answers or a reason, who, where) are kept 2 years. Does Law 25 (biometrics: declaration to the Commission, express consent) or PIPEDA require more than Stripe's own consent screen? Is 2 years right for handoff records, and is "verified over N" in the store privacy answers the right disclosure? | DECISIONS § 2026-10-04 Age-restricted purchases; `restricted` schema (V343); retention schedule; `mobile/apps/consumer/store/privacy.json` | consent and declarations; retention period; Text |

## I. Consent at sign-up and store listings

| # | question | source | decision needed |
|---|---|---|---|
| I1 | Registration has one checkbox: "I agree to the Terms and Privacy Policy. Data stays in Canada." Is bundling acceptance of the Terms with the privacy notice meaningful consent (PIPEDA guidelines; Law 25's separate, specific consent)? Is "Data stays in Canada" accurate given E1 and D4? | consumer/Studio/app sign-up copy | Text; Product |
| I2 | Approve the **store privacy answers** (App Privacy, Data safety) for both apps: data types, "no tracking", nothing shared (Stripe, APNs, FCM as service providers), deletion in-app plus a web URL. | `mobile/apps/*/store/privacy.json`; DECISIONS § S-103 | approve (registered texts `store-privacy-*`) |
| I3 | Merchant **attestations** signed during onboarding (consumer-product safety and labelling; allergen and labelling) and the permit list name one province's bodies (AHS, AGLC, ACP). Approve the attestation wording and the per-province regulator names (the region model can carry them). | Studio onboarding `dlgSignText_*`, `ck_category_permits` | Text; data |

## J. Payments compliance (S-110)

| # | question | source | decision needed |
|---|---|---|---|
| J1 | Who is the **merchant executive officer** who signs the PCI DSS SAQ A attestation, and which Stripe entity contracts with Northline (Stripe Payments Canada, Ltd.)? | [saq-a.md](../pci/saq-a.md#who-signs-and-what-needs-a-real-stripe-account), [aoc.md](../pci/aoc.md) | names |
