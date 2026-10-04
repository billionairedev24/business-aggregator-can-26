# Legal review packet (S-106)

For outside counsel. The Terms of Service and the Privacy Policy shipped with the product are the design's drafts
(design 09 and 10, "strong draft, not legal advice"), published word for word. Nothing in the product has been
reviewed by a lawyer. This packet lists every legal text in the product, where each one appears, its version and its
languages; the questions are in [counsel-questions.md](counsel-questions.md) (53, each with its source and the decision
needed). The S-106 acceptance — "counsel sign-off recorded, pages updated verbatim" — works through the registry
described in [§ Sign-off mechanism](#sign-off-mechanism).

## How to read the texts

- The two pages: `web/packages/legal/pages/terms.html` and `privacy.html` in the repository, or `/legal/terms.html`
  and `/legal/privacy.html` on the consumer site and the Studio (`make up SERVICES="api studio"` serves them at
  `http://localhost:3100/legal/terms.html`).
- Everything else is UI or email copy in the repository files named below, English and French side by side.
- `cd web/packages/legal && node registry.mjs status` prints every registered text with its version and sign-off.

## Every legal text in the product

| # | text | where it appears (route / screen) | version id | languages | source |
|---|---|---|---|---|---|
| 1 | **Terms of Service**, Part A (everyone) | consumer web `/legal/terms.html` (footer; registration and sign-in checkbox); Studio `/legal/terms.html` (registration); consumer app (welcome, sign-up, account → opens the site's page); App Store / Play listings | registry `terms` **3.0**, effective 2026-10-01 (stored per user as `identity.users.terms_version`) | en only (the page claims a French version exists — Q D1) | `web/packages/legal/pages/terms.html` |
| 2 | **Business Terms** = Terms Part B (B1–B13): the merchant agreement and seller terms | Studio onboarding 07d checkbox ("I agree to the Business Terms (Part B …) and the Marketplace Facilitator tax arrangement") → `/legal/terms.html#business`; Compliance › Platform obligations › "read" | part of `terms` 3.0; acceptance stored as `merchants.merchants.business_terms_accepted_at` without a version (Q G4) | en only | same file, `#business` |
| 3 | **Platform obligations** — the merchant's versioned summary (consumer protection, privacy, anti-circumvention, accessibility and language, safety / food safety) | Studio › Compliance › "Platform obligations you've accepted" (owner accepts each new version) | registry `merchant-obligations` **2.3** (`ComplianceRules.OBLIGATIONS_VERSION`; `merchants.obligation_acceptances`) | en, fr | `web/apps/studio/src/features/compliance/messages.ts` (`ob1`–`ob5`, `ob5Kitchen`) |
| 4 | **Privacy Policy** | consumer web and Studio `/legal/privacy.html` (footer, registration); consumer app; store listings (privacy URL, en-CA and fr-CA) | registry `privacy` **3.0**, effective 2026-10-01 | en only | `web/packages/legal/pages/privacy.html` |
| 5 | **Registration consent line** ("I agree to the Terms and Privacy Policy. Data stays in Canada." / Studio "…Data is stored in Canada.") | consumer web `/register`, Studio `/register`, consumer app sign-up | none (UI copy; the accepted version is `terms_version`) | en, fr | `web/apps/consumer/src/features/auth/messages.ts`, `web/apps/studio/src/features/auth/messages.ts`, `web/packages/auth-kit`, `mobile/apps/consumer` sign-up |
| 6 | **CASL consent wordings** (S-108) | consumer web and app Account › Notifications › Marketing messages; Studio Settings › Notifications › Marketing from Northline | registry `casl-consent-wordings`: `account.email.2026-10`, `account.sms.2026-10`, `account.push.2026-10`, `studio.email.2026-10` (stored per consent in `messaging.consent_records.wording_version`) | en, fr-CA | `server/api/src/main/java/ca/northline/messaging/domain/ConsentWordings.java` |
| 7 | **Email sender identification and unsubscribe** (CASL footers: sender, why you got it, commercial footer, one-click unsubscribe page) | every email; `GET /api/v1/email/unsubscribe` page | none (template copy) | en, fr | `server/email/src/main/resources/email/messages*.properties`; casl.md |
| 8 | **Privacy request notices** (S-105): what each right does, the law that grants it (`{privacyLaw}` from the region model), deadlines, the grace period, what is kept and why, the export summary | consumer web Account › Profile › Your data; consumer app `/account/data`; Studio Settings › Security › Your personal data; console Privacy (staff); the access export's readable summary | none (UI copy); the law's values in `region.privacy_laws` (V271) | en, fr | `web/apps/consumer/src/features/account/privacyMessages.ts`, `mobile/apps/consumer/src/account/YourData.tsx`, `web/apps/studio/src/features/settings/PersonalData.tsx`; privacy-requests.md |
| 9 | **Store listing privacy answers** (S-103) | App Store Connect › App Privacy; Play Console › Data safety (entered by hand) | registry `store-privacy-consumer`, `store-privacy-courier` (2026-10-02) | en (answers are structured; listings en-CA and fr-CA) | `mobile/apps/{consumer,courier}/store/privacy.json` |
| 10 | **Cookie notice** | none in the product: the Privacy Policy § 9 says only strictly necessary cookies are used, so no banner was built (Q F2: Stripe.js on payment pages, Google Fonts, GPC) | — | — | privacy.html § 9 |
| 11 | **Merchant attestations** signed in onboarding (consumer-product safety and labelling; allergen and labelling) and the category permits list | Studio onboarding verification step (signature dialog) | none (UI copy; the signature is recorded as a verification) | en, fr | `web/apps/studio/src/features/onboarding/messages.ts` (`dlgSignText_*`, `ck_*`) |
| 12 | **Stripe's terms** (Connected Account Agreement, shown in Stripe's hosted onboarding) | Studio onboarding → Stripe Express | Stripe's | Stripe's | Stripe (not Northline's text; listed so counsel knows merchants accept it) |

Not found in the product: courier terms or a courier privacy notice (Q H3); a sub-processor page (`northline.ca/subprocessors`, Q F5); a French version of the Terms or Privacy Policy (Q D1).

## Sign-off mechanism

The **legal document registry** (`web/packages/legal/registry.json`, S-106) is the record of what counsel approved:

- every legal text has an id, where it appears, its languages, how acceptance is stored, and its **versions** (newest
  last), each with an **effective date** and a **sign-off** (`null` until counsel signs);
- a sign-off is `{ "counsel": "name", "firm": "…", "date": "YYYY-MM-DD", "sha256": "…", "reference": "memo id" }`, where
  `sha256` is the hash of **the exact text reviewed** (`node registry.mjs hash terms` prints it);
- pages and files are hashed: `legal.test.mjs` (part of `pnpm -r test` and `make legal-check`) **fails when a text
  changes without a new version**, when a page's "Version … · Effective …" line disagrees with the registry, when two
  versions have the same text, or when a sign-off covers a different text than its version. Texts that live in code
  (the CASL wordings, the platform obligations) are versioned where they are and pinned by their own tests; the
  registry test checks their version ids match.

When counsel returns approved text:

1. Put the text into the design source (`design/09 Terms of Service`, `design/10 Privacy Policy`) and regenerate the
   pages (`node scripts/legal-pages.mjs`) — the generator test keeps the pages verbatim. Update the "Version … ·
   Effective …" line.
2. Add a version to `registry.json` with the new effective date and `sha256` (the test prints the expected hash) and
   the sign-off record with the same hash.
3. For the Terms and the Privacy Policy, set northline-auth's `TERMS_VERSION` to the new version (the test checks the
   default in `AuthProperties`) so new sign-ups record it; existing users get the 30 days' notice the documents promise.
4. For CASL wordings: add a new wording version in `ConsentWordings` (published ones are never edited), then list it
   in the registry.

Counsel can also sign off the current drafts unchanged: add the sign-off record to the current version with its hash.

## What engineering cannot provide

- Legal advice on any of the questions; the French translations of the Terms and Privacy Policy (Q D1).
- Whether the drafts' factual claims are true at launch (pen tests, SOC 2, segregated accounts — Q F4): operations must
  confirm before publication.
