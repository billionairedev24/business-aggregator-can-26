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
