# Implementation plan — Studio (provider · seller · kitchen · both)

Scope: everything in `design/02 Provider Studio.dc.html` (signed-out auth, onboarding, every Studio screen for all four portals), full stack. Source of truth order is in `CLAUDE.md`. This file fixes the conventions every workstream follows so the pieces fit.

## Repository layout
This folder (`project/repo/`) is the project root; it is meant to be lifted into its own git repository unchanged.

```
server/api        Spring Modulith monolith — one package + one Postgres schema per module
server/auth       Spring Authorization Server (OIDC, passkeys, TOTP, OTP registration)
server/bff        Gateway MVC + OAuth2 client (consumer-bff, studio-bff) — browser holds only a session cookie
server/worker     Kafka consumers
db/migrations     Flyway. V001–V017 are the design baseline. New migrations use the version range assigned to the workstream (below).
web/packages/tokens   the only place colours/fonts/radii live
web/packages/ui       reusable components + Storybook stories (+ i18n runtime)
web/apps/studio       TanStack Router SPA for the Studio
```

## Frontend conventions (web/)
- Stack: React 19, TypeScript strict, TanStack Router (file routes via `@tanstack/router-plugin`), Query, Form, Table, Virtual, zod 4.
- Styling: plain CSS co-located with the component (`Foo.tsx` + `Foo.css`, class prefix `nl-`), values only from `var(--*)` tokens. Inline `style` only for genuinely dynamic values. No hex/rgb literals in components (third-party brand marks such as the Google logo SVG excepted).
- Icons: `@phosphor-icons/react`, `weight="duotone"` in navigation, matching the design's `ph-duotone` usage.
- i18n: `defineMessages({ en: {...}, fr: {...} })` from `@northline/ui` (ICU MessageFormat via `intl-messageformat`). Every user-visible string goes through it — en and fr together, fr-CA from `design/i18n-fr.js` wording where it exists. Money/dates: `formatMoney(cents)`, `formatDate()` from `@northline/ui` (America/Edmonton).
- Generic, reusable pieces (inputs, dialogs, tables, charts, shells) go in `packages/ui` with a story that covers every state and passes the a11y addon. Screen-specific composition stays in the app under `src/features/<feature>/`.
- App structure: `src/features/<feature>/{api.ts, messages.ts, *.tsx}`; routes in `src/routes/` (thin: loader + component from the feature). `api.ts` exposes zod schemas, `queryOptions` factories and mutation hooks; the HTTP client is `src/lib/http.ts`.
- Every screen: skeleton loading state, empty state (one line + primary action), error state (rosehip inline + Retry), no horizontal scroll at ≥ 320 px, focus ring, 44 px targets.
- Validation: zod schemas mirror `docs/spec/validation-rules.md` with the exact messages; server 422 `{ errors: [{ field, rule, message }] }` is mapped onto the same fields.

## Backend conventions (server/)
See `docs/BACKEND_CONVENTIONS.md` (written by the backend foundation) — layering, module API packages, error format, security (`MerchantAccess`), testing base classes.
- REST under `/api/v1`. Studio endpoints are merchant-scoped: `/api/v1/merchants/{merchantId}/…`. JSON camelCase, ids ULID strings, money `…Cents` longs, instants ISO-8601.
- Validation errors: HTTP 422 `{ "errors": [ { "field", "rule", "message" } ] }` with the messages from `validation-rules.md`.

## Migration version ranges (avoid collisions between parallel workstreams)
| range | workstream |
|---|---|
| V018–V019 | backend foundation |
| V020–V029 | auth / identity |
| V030–V039 | onboarding / merchants |
| V040–V049 | operations: booking, availability, orders |
| V050–V059 | catalogue |
| V060–V069 | finance: payments, payouts, reports |
| V070–V079 | messaging & help |
| V080–V089 | storefront, settings, compliance |
| V090–V099 | kitchen / food |
| V100–V109 | dev seed data (`db/seed-dev/`, only under the `local` profile) |

## Studio app (`web/apps/studio`) — what exists
- `pnpm dev` (port 3100). Dev without auth/bff: `NL_DEV_USER=<seeded user id> pnpm dev` → `/api` is proxied to the api (`:8080`) with `X-Dev-User` (accepted only by the api `local` profile) and `/bff/session` is answered by the dev server. Without `NL_DEV_USER`, `/api`, `/bff`, `/oauth2`, `/login` go to the studio BFF (`:8082`).
- Routes (file-based, `src/routes/`): `/` (redirects: signed out → `/sign-in`, no business → `/onboarding`, else the first business's home), `/sign-in`, `/register`, `/onboarding/…`, and the studio under `/b/$merchantId/…`:
  `''` dashboard · `appointments` · `orders` · `messages` · `listings` (+ `listings/new`, `listings/$listingId`, `listings/bulk`) · `availability` · `page` (business page / store / menu page) · `earnings` · `reports` · `payouts` · `refunds` · `compliance` · `reviews` · `settings` (`?tab=business|team|security|notifications|api`) · `help` · `kitchen/live` · `kitchen/menu` · `kitchen/combos` · `kitchen/hours`.
  Each currently renders `ScreenPending`; a workstream replaces the route's component with its feature screen. The `/b/$merchantId` layout loads the merchant, renders the shell and redirects away from screens the portal doesn't have (`features/shell/nav.ts` → `screensFor`).
- In a screen: `useMerchantId()`, `useMerchant()` (type provider|seller|kitchen|both, tier, status, role), `useRole()` from `features/shell/api.ts`; `http()` from `lib/http.ts` (CSRF header, 422 → `ValidationError` with `byField()`); `visibleError`, `serverFieldErrors`, `attentionCount` from `lib/forms.ts`.
- UI kit (`@northline/ui`): `Field`/`TextInput`/`TextArea`/`Select`/`FormGrid`, `Checkbox`, `Switch`, `OptionCard`, `Chip`, `ChipTabs`, `Segmented`, `UnderlineTabs`, `StepBars`, `Dialog`, `Drawer`, `Menu`, `Panel`, `PageHeader`, `Kpi`/`KpiRow`, `Alert`, `LinkRow`, `Meter`, `Avatar`, `Skeleton`/`PageSkeleton`, `EmptyState`, `ErrorState`, `StackedBarChart`, `LineChart`, `BarList`, `Legend`, `AppShell`, `DataTable`, `defineMessages`, `useFormatters`, `formatMoney`, `formatDate`.
- The studio sets `--color-surface: var(--color-bg)` globally: panels are the page's off-white outlined by a 1px `--color-divider` border (design decision of 2026-09-29).

## Contracts between workstreams
- **Session (BFF):** `GET /bff/session` → `200 { user: { id, firstName, lastName, email, phone, initials, locale, memberSince }, acr }` or `401`; `POST /bff/logout` → `204`. CSRF: cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`.
  Auth workstream additions: `GET /bff/login?next=/path` (sign-in hand-off), `GET /api/v1/me` → `{ id, firstName, lastName, email, phone, initials, locale, memberSince, mfaPrimary, mfa }`. Sign out (incl. onboarding "Not you?") = `useSignOut()` from `src/lib/session.ts`. Legal pages: `/legal/terms.html` (Part B: `#business`), `/legal/privacy.html`. Settings → Security can issue backup codes with `POST {VITE_NL_AUTH_ORIGIN}/api/auth/backup-codes` (credentials included). The sign-in log is `identity.sessions` (one row per sign-in).
- **Businesses:** `GET /api/v1/me/businesses`, `GET /api/v1/merchants/{id}` (backend foundation).
- **Nav badges:** `GET /api/v1/merchants/{id}/nav-badges` → `{ "<screenKey>": "<badge text>" }` (screen keys as in `nav.ts`). Each module contributes through a `NavBadgeContributor` bean (interface in `ca.northline.shared`), the `studio` module aggregates. Badge text is computed server-side in the caller's locale (`Accept-Language`).
- **Dashboard:** `GET /api/v1/merchants/{id}/dashboard` — composed by the `studio` module from other modules' public APIs (no cross-module repository access).
- **Listings (catalogue ↔ onboarding step 6):** the catalogue workstream owns these; onboarding's "First listings" step calls them.
  `GET /api/v1/merchants/{id}/listings?kind=service|product&limit=` → `{ items: [{ id, kind, name, sku, meta, priceCents, stock, sales30d, vetting: draft|pending|approved|rejected, status: live|hidden }] }` ·
  `POST /api/v1/merchants/{id}/services` `{ name, categoryId?, pricingMode: fixed|quote|hourly, priceCents?, durationMin, bufferMin, included, instantBook }` ·
  `POST /api/v1/merchants/{id}/products` `{ gtin?, title, categoryId, priceCents, stock, variantTheme?: none|size|colour|size_colour }` (created as `vetting=draft`).
  Menu items for kitchens: `POST /api/v1/merchants/{id}/menu-items` `{ menuId, sectionId, name, description, priceCents, prepAddMin, allergens[], modifierGroupIds[] }` (kitchen workstream owns; created hidden until approval).
- **Schema additions:** V001–V017 lack some display columns (e.g. names/titles live in `*_i18n` or are missing). A workstream may add columns/tables in its own version range — additive only, never rename/drop baseline columns — and records each addition in `DECISIONS.md`.
- **Dev seed:** each workstream adds its persona data (Prairie Wrench provider, Prairie Wrench Parts seller, Pho Dau Bo kitchen — see backend foundation's V100 seed) as `db/seed-dev/V1xx__<workstream>.sql` using its own number: auth V101, onboarding V102, operations V103, catalogue V104, finance V105, messaging V106, settings V107, kitchen V108.
