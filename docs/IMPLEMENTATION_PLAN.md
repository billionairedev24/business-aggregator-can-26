# Implementation plan — Studio (provider · seller · kitchen · both)

> The consumer web app (design 06) has its own plan: [CONSUMER_WEB_PLAN.md](CONSUMER_WEB_PLAN.md) (S-45); the platform
> console (design 03) too: [CONSOLE_PLAN.md](CONSOLE_PLAN.md) (S-90).

Scope: everything in `design/02 Provider Studio.dc.html` (signed-out auth, onboarding, every Studio screen for all four portals), full stack. Source of truth order is in `CLAUDE.md`. This file fixes the conventions every workstream follows so the pieces fit.

## Repository layout
This folder (`project/repo/`) is the project root; it is meant to be lifted into its own git repository unchanged.

```
server/api        Spring Modulith monolith — one package + one Postgres schema per module
server/auth       Spring Authorization Server (OIDC, passkeys, TOTP, OTP registration)
server/bff        Gateway MVC + OAuth2 client (studio-bff; consumer-bff = the `consumer` profile, S-45) — browser holds only a session cookie
server/worker     Kafka consumers
db/migrations     Flyway. V001–V017 are the design baseline. New migrations use the version range assigned to the workstream (below).
web/packages/tokens   the only place colours/fonts/radii live
web/packages/ui       reusable components + Storybook stories (+ i18n runtime)
web/apps/studio       TanStack Router SPA for the Studio
mobile/packages/mobile-kit  native apps' shared plumbing: DPoP OAuth (PKCE), api client, secure storage, theme from web/packages/tokens, i18n (S-87)
mobile/apps/courier   the courier app, Expo + React Native + expo-router (S-87; docs/runbooks/courier-app.md). The consumer app joins mobile/apps in phase 4
```

## Frontend conventions (web/)
- Stack: React 19, TypeScript strict, TanStack Router (file routes via `@tanstack/router-plugin`), Query, Form, Table, Virtual, zod 4.
- Styling: plain CSS co-located with the component (`Foo.tsx` + `Foo.css`, class prefix `nl-`), values only from `var(--*)` tokens. Inline `style` only for genuinely dynamic values. No hex/rgb literals in components (third-party brand marks such as the Google logo SVG excepted).
- Icons: `@phosphor-icons/react`, `weight="duotone"` in navigation, matching the design's `ph-duotone` usage.
- i18n: `defineMessages({ en: {...}, fr: {...} })` from `@northline/ui` (ICU MessageFormat via `intl-messageformat`). Every user-visible string goes through it — en and fr together, fr-CA from `design/i18n-fr.js` wording where it exists. Money/dates: `formatMoney(cents)`, `formatDate()` from `@northline/ui` (the merchant's or market's time zone, else the configured platform zone — S-134; never a zone in code).
- Generic, reusable pieces (inputs, dialogs, tables, charts, shells) go in `packages/ui` with a story that covers every state and passes the a11y addon. Screen-specific composition stays in the app under `src/features/<feature>/`.
- App structure: `src/features/<feature>/{api.ts, messages.ts, *.tsx}`; routes in `src/routes/` (thin: loader + component from the feature). `api.ts` exposes zod schemas, `queryOptions` factories and mutation hooks; the HTTP client is `src/lib/http.ts`.
- Every screen: skeleton loading state, empty state (one line + primary action), error state (rosehip inline + Retry), no horizontal scroll at ≥ 320 px, focus ring, 44 px targets.
- Validation: zod schemas mirror `docs/spec/validation-rules.md` with the exact messages; server 422 `{ errors: [{ field, rule, message }] }` is mapped onto the same fields.
  French (S-40): `http()` sends the Studio's language as `Accept-Language` (`setRequestLocale`), so 422 messages arrive in it; client-side messages are translated with a feature dictionary keyed by the English (`lib/validation.ts`), worded as in `docs/spec/validation-messages.fr-CA.tsv`.

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
| V100–V109 | dev seed data (`db/seed-dev/`, only under the `local` profile; V109 = consumer persona) |
| V110–V119 | consumer web (CONSUMER_WEB_PLAN.md) |
| V120–V129 | search |
| V130–V139 | region platform (S-134: region profiles, launch markets) |
| V150–V159 | AI (S-129–S-133) |
| V170–V179 | Studio follow-ups (S-40, S-41, S-64, S-66, S-67, S-73) — the next free range above V164 (ordering rule below) |
| V160–V169 | consumer account (S-58–S-60: `account` schema, favourites, points read model, preferences, customer cases; dev seed V161…) — moved from V140–V149 by the ordering rule below |
| V180–V189 | Studio follow-ups, batch B (S-65, S-70, S-72, S-75, S-76, S-77) — above batch A's V170–V179 by the ordering rule below |
| V190–V199 | platform console (E-8: S-90 foundation, S-91 overview, then S-79–S-85, S-92–S-96; [CONSOLE_PLAN.md](CONSOLE_PLAN.md)) — the next free range above V183 |
| V200–V209 | fulfilment (E-9: S-89, S-78, S-86, S-88) — above the console's V190–V199 by the ordering rule below |
| V210–V219 | console queues (E-8: S-79 verification, S-92 vetting, S-80 disputes, S-93 trust & safety, S-83 support desk) — above fulfilment's V200–V209 by the ordering rule below |
| V230–V239 | platform console, batch 2 (S-81, S-82, S-84, S-85, S-94, S-95, S-96; [CONSOLE_PLAN.md](CONSOLE_PLAN.md)) — above the console queues' V210–V219 by the ordering rule below |
| V270–V279 | privacy rights (S-105: `privacy` schema, `region.privacy_laws`, erasure support) — above V245 by the ordering rule below |
| V245–V249 | push notifications and deep links (S-102: `messaging.push_devices`, customers' deferred notifications) — above V240–V244, which the mobile foundation (S-97) may use, by the ordering rule below |
| V301–V304 | data retention (S-107: `privacy.retention_runs`, `region.privacy_laws.decision_retention_days`, the jobs' indexes) — above S-108's V300 by the ordering rule below (first assigned V295–V299) |
| V305–V309 | security review before the penetration test (S-104: `privacy.verification_texts`) — above S-107's V301 by the ordering rule below |
| V320–V324 | pilot merchant onboarding (S-120: `merchants.pilot_*`, `merchants.kitchen_visits`, the kitchen-visit rule on `region.regions` and `catalogue.categories`, the merchant success role; dev seed V323) — above S-116's V319 |
| V325–V329 | UAT with the pilot group (S-121: schema `uat`, dev seed V326) — above S-120's V320–V324 (main's highest: V323) by the ordering rule below |
| V330–V334 | go-live (S-118: schema `golive` V330, dev seed V334 — a second console admin) — above S-121's V325–V329 by the ordering rule below |
| V340–V349 | age-restricted purchases and the 2026-10-04 owner decisions (`region.age_rules` V340, category age classes V341, `merchants.restricted_licences` V342, schema `restricted` V343, handoff ID checks V344, production's first market at `pilot` V345; dev seed V349 — the local markets live again) — above main's V334 by the ordering rule below |

**Ordering rule (2026-10-01):** Flyway applies versions in order and, outside the `local` profile, refuses a version lower than one already applied. A new migration must therefore be numbered **above the highest version on main** when it merges, not just inside its workstream's range. If a range is behind, take the next free range above the maximum and record it here (S-129/S-133's V125/V126 became V150/V151 for this reason).

## Studio app (`web/apps/studio`) — what exists
- `pnpm dev` (port 3100). Dev without auth/bff: `NL_DEV_USER=<seeded user id> pnpm dev` → `/api` is proxied to the api (`:8080`) with `X-Dev-User` (accepted only by the api `local` profile) and `/bff/session` is answered by the dev server. Without `NL_DEV_USER`, `/api`, `/bff`, `/oauth2`, `/login` go to the studio BFF (`:8082`).
- Routes (file-based, `src/routes/`): `/` (redirects: signed out → `/sign-in`, no business → `/onboarding`, else the first business's home), `/sign-in`, `/register`, `/onboarding/…`, and the studio under `/b/$merchantId/…`:
  `''` dashboard · `appointments` · `orders` · `messages` · `listings` (+ `listings/new`, `listings/$listingId`, `listings/bulk`) · `availability` · `page` (business page / store / menu page) · `earnings` · `reports` · `payouts` · `refunds` · `compliance` · `reviews` · `settings` (`?tab=business|team|security|notifications|api`) · `help` · `kitchen/live` · `kitchen/menu` · `kitchen/combos` · `kitchen/hours`.
  Each currently renders `ScreenPending`; a workstream replaces the route's component with its feature screen. The `/b/$merchantId` layout loads the merchant, renders the shell and redirects away from screens the portal doesn't have (`features/shell/nav.ts` → `screensFor`).
- In a screen: `useMerchantId()`, `useMerchant()` (type provider|seller|kitchen|both, tier, status, role), `useRole()` from `features/shell/api.ts`; `http()` from `lib/http.ts` (CSRF header, 422 → `ValidationError` with `byField()`); `visibleError`, `serverFieldErrors`, `attentionCount` from `lib/forms.ts`.
- UI kit (`@northline/ui`): `Field`/`TextInput`/`TextArea`/`Select`/`FormGrid`, `Checkbox`, `Switch`, `OptionCard`, `Chip`, `ChipTabs`, `Segmented`, `UnderlineTabs`, `StepBars`, `Dialog`, `Drawer`, `Menu`, `Panel`, `PageHeader`, `Kpi`/`KpiRow`, `Alert`, `LinkRow`, `Meter`, `Avatar`, `Skeleton`/`PageSkeleton`, `EmptyState`, `ErrorState`, `StackedBarChart`, `LineChart`, `BarList`, `Legend`, `AppShell`, `DataTable`, `defineMessages`, `useFormatters`, `formatMoney`, `formatDate`.
- The studio sets `--color-surface: var(--color-bg)` globally: panels are the page's off-white outlined by a 1px `--color-divider` border (design decision of 2026-09-29).

## Contracts between workstreams
- **Session (BFF):** `GET /bff/session` → `200 { user: { id, firstName, lastName, email, phone, initials, locale, memberSince }, acr }` or `401`; `POST /bff/logout` → `204`. CSRF: cookie `XSRF-TOKEN` (`__Host-XSRF-TOKEN` in the cloud, S-20), header `X-XSRF-TOKEN` (the only place the BFF accepts the token) — use `http()` / `xsrfToken()` from `src/lib/http.ts`.
  Auth workstream additions: `GET /bff/login?next=/path` (sign-in hand-off), `GET /api/v1/me` → `{ id, firstName, lastName, email, phone, initials, locale, memberSince, mfaPrimary, mfa }`. Sign out (incl. onboarding "Not you?") = `useSignOut()` from `src/lib/session.ts`. Legal pages: `/legal/terms.html` (Part B: `#business`), `/legal/privacy.html` (`web/packages/legal`, shared with the consumer app since S-63). Settings → Security can issue backup codes with `POST {VITE_NL_AUTH_ORIGIN}/api/auth/backup-codes` (credentials included). The sign-in log is `identity.sessions` (one row per sign-in).
- **Businesses:** `GET /api/v1/me/businesses`, `GET /api/v1/merchants/{id}` (backend foundation).
- **Nav badges:** `GET /api/v1/merchants/{id}/nav-badges` → `{ "<screenKey>": "<badge text>" }` (screen keys as in `nav.ts`). Each module contributes through a `NavBadgeContributor` bean (interface in `ca.northline.shared`), the `studio` module aggregates. Badge text is computed server-side in the caller's locale (`Accept-Language`).
- **Dashboard:** `GET /api/v1/merchants/{id}/dashboard` — composed by the `studio` module from other modules' public APIs (no cross-module repository access).
- **Listings (catalogue ↔ onboarding step 6):** the catalogue workstream owns these; onboarding's "First listings" step calls them.
  `GET /api/v1/merchants/{id}/listings?kind=service|product&limit=` → `{ items: [{ id, kind, name, sku, meta, priceCents, stock, sales30d, vetting: draft|pending|approved|rejected, status: live|hidden }] }` ·
  `POST /api/v1/merchants/{id}/services` `{ name, categoryId?, pricingMode: fixed|quote|hourly, priceCents?, durationMin, bufferMin, included, instantBook }` ·
  `POST /api/v1/merchants/{id}/products` `{ gtin?, title, categoryId, priceCents, stock, variantTheme?: none|size|colour|size_colour }` (created as `vetting=draft`).
  Menu items for kitchens: `POST /api/v1/merchants/{id}/menu-items` `{ menuId, sectionId, name, description, priceCents, prepAddMin, allergens[], modifierGroupIds[] }` (kitchen workstream owns; created hidden until approval). `GET /api/v1/merchants/{id}/menus` → `{ items: [{ id, name, status, schedule, sections: [{ id, name, sort, itemCount }] }] }`, `GET …/modifier-groups` → `{ items: [{ id, name, … }] }`.
- **Food orders (kitchen ↔ orders ↔ payments):** the kitchen display publishes `order.accepted` (`KitchenOrderAccepted`), `order.ready` (`KitchenOrderReady`) and `order.handed_off` (`FoodOrderHandedOff`, `fulfilmentMode`) from `food.api` to topic `orders.order`; the orders module moves `orders.orders.state` on them; **payments releases the kitchen's escrow on `order.handed_off`**. Checkout sets `orders.orders.fulfilment_mode` (`delivery|pickup`) and, for pickup, `customer_eta`.
  Menu items for kitchens: `POST /api/v1/merchants/{id}/menu-items` `{ menuId, sectionId, name, description, priceCents, prepAddMin, allergens[], modifierGroupIds[] }` (kitchen workstream owns; created hidden until approval).
- **Settings & compliance** (settings workstream): `merchants.api.ComplianceStatus.dueItems` (expired/todo verifications incl. `pausesAt` = expiry + 15 days) is the source for "Needs you"; `developer.api.AuditTrail.record(Entry)` for privileged Studio actions (merchant-scoped audit log); `identity.api.TeamAccounts` for team member names/contact/2FA; `payments.api.TaxSummary` reads `payments.tax_jurisdiction_totals`, which the finance sync must fill. `GET /api/v1/merchants/{id}` also returns the caller's `role` and `teamCount`. Team invitations: `/invite/$token` (Studio) + `/api/v1/team-invitations/{token}[/accept]`. Security: `GET {auth}/api/auth/security`, `POST {auth}/api/auth/security/passkeys/options`, `POST {auth}/api/auth/security/passkeys`.
- **Schema additions:** V001–V017 lack some display columns (e.g. names/titles live in `*_i18n` or are missing). A workstream may add columns/tables in its own version range — additive only, never rename/drop baseline columns — and records each addition in `DECISIONS.md`.
- **Messaging, help & trust (messaging workstream):** other modules open customer threads through `ca.northline.messaging.api.Conversations.open(...)` (booking/order/dispute ref, counterpart name, subject, assignee — idempotent per ref); finance/compliance offer payouts, disputes and documents in the help form's "Related to" list by implementing `ca.northline.messaging.api.CaseReferences`. The dashboard composes `ca.northline.trust.api.QualityQuery.latest(merchantId)` (score + components with 0–100 bars) and `RatingQuery.summary(merchantId)` (average, count). Onboarding links "Need help with a document?" to `/b/$merchantId/help?topic=verification`; `/b/$merchantId/messages?thread=<id>` opens a conversation. Endpoints: `/threads…`, `/message-attachments…`, `/help/{topics,articles,status,related,cases}…`, `/reviews…`, `/quality`.
- **Dev seed:** each workstream adds its persona data (Prairie Wrench provider, Prairie Wrench Parts seller, Pho Dau Bo kitchen — see backend foundation's V100 seed) as `db/seed-dev/V1xx__<workstream>.sql` using its own number: auth V101, onboarding V102, operations V103, catalogue V104, finance V105, messaging V106, settings V107, kitchen V108.
