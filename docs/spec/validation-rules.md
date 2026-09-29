# Validation rules (client AND server)

Every rule here is displayed inline in the UI (magenta text under the field, `role="alert"`, field border `--color-accent-2`), shown after the field is touched or on submit, plus a summary banner "N things need attention." on a failed submit. The server returns the same rule ids as `422 { errors: [{ field, rule, message }] }`.

## Registration (identity)
| field | rule | message |
|---|---|---|
| first_name | required, trimmed | First name is required. |
| last_name | required, trimmed | Last name is required. |
| phone | required; Canadian mobile `^\+?1?[\s.-]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$`; stored E.164 | Mobile number is required for verification. / Enter a valid Canadian mobile, e.g. +1 403 555 0148. |
| email | required; `^[^\s@]+@[^\s@]+\.[^\s@]{2,}$`; unique (citext) | Email is required. / That doesn't look like an email address. |
| terms | must be true; store `terms_version`, `accepted_at` | You need to accept the Terms and Privacy Policy. |
Flow: form → OTP (6 digits, resend 45 s, voice fallback) → second factor (passkey or TOTP; SMS backup only; **mandatory for business accounts**) → account created → business onboarding. Social login: Google, Apple (name/email only). Sign-in accepts email or mobile.

## Business step (merchants)
| field | rule | message |
|---|---|---|
| display_name | required, 2–80 | Enter the name customers will see. / At least 2 characters. |
| legal_name | required | Enter the registered legal name. |
| structure | one of sole, partnership, corp_ab, corp_fed, corp_ex, coop, nonprofit | — |
| legal_details | must validate against `legal-details.schema.json` branch for `structure` | per-field "required" |
| gst_number | optional for sole/partnership; required otherwise; `^\d{9}\s?RT\s?\d{4}$` | Format is 9 digits + RT0001 (e.g. 123456789 RT0001). / Required for corporations, co-ops and non-profits. |
| principals | per `x-principals` in schema: min count, roles, KYC for ≥ 25 %, Σ pct ≤ 100 | — |
| categories | ≥ 1; ≤ limit(type): provider 10, seller 5, both 10, kitchen 3; regulated leaves create `verifications` rows | Pick at least one service. / Maximum N. |
| suggested category | free text allowed when no match; stored `merchant_categories.suggested_name`, status `requested` | — |

## Quote (booking)
| field | rule | message |
|---|---|---|
| lines | ≥ 1 | — |
| line.description | required, 1–160 | Describe this line — customers must see what they're paying for. |
| line.amount | > 0 unless kind=discount | Enter an amount. |
| scope | required | Describe the scope of work. |
| valid_hours | 24 / 72 / 168 / 336 | — |
| totals | subtotal = Σ lines (discounts negative); tax from region profile; total = subtotal + tax | — |
Quote is immutable once sent; edits create version+1 and mark prior `superseded`. Accept → booking created, escrow hold for `total_cents`, `quote.accepted` event. Mid-job changes → `booking.approvals`.

## Storefront (page builder)
- `hero` and `cta` always enabled.
- Section kinds restricted by `page_kind` (see storefront-sections.json and the DB trigger).
- `position` unique per storefront; reorder is a single PATCH with the full ordered list.
- brand_color must pass 4.5:1 with white (the swatch set is curated; custom colours rejected if they fail).
- tagline ≤ 80 chars. slug `^[a-z0-9-]{3,40}$`. custom_domain requires CNAME verification before `published_at`.
