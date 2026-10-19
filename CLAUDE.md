# Northline — agent instructions

You are implementing the Northline marketplace from a finished design spec. **Do not deviate from the spec.** Where it is silent, choose the simplest option consistent with it and record it in `docs/DECISIONS.md`.

## Source of truth (priority order)
1. `db/migrations/*.sql` — schema (V001–V017). Column names and enums are final.
2. `docs/spec/` — machine-readable rules (legal-details schema, storefront sections, validation messages) + `db/seed/categories.json`.
3. `docs/ARCHITECTURE.md`, `docs/DATA_MODEL.md` — modules, events, flows.
4. `design/*.dc.html` — interactive references of every screen (open in a browser). Behavioural spec for copy, states, ordering. Recreate, don't copy. **Their look is retired** (2026-10-18): layouts, visual language and the screen-by-screen look come from `docs/design/redesign-spec.md`.
5. `design/09 Terms of Service`, `design/10 Privacy Policy` — ship verbatim; open from registration in a new tab.

## Stack (fixed)
- **Backend:** Java 25, Spring Boot 4.1.1, Spring Modulith 2 — `server/api` (modular monolith, one package + schema per module), `server/auth` (Spring Authorization Server, OAuth 2.1/OIDC, passkeys, TOTP, Google/Apple), `server/bff` (Spring Cloud Gateway MVC, token relay, Redis session), `server/worker` (Kafka consumers: search indexer, notifications, webhooks). Virtual threads on.
- **Data:** PostgreSQL 17 + PostGIS (Flyway), Elasticsearch 9 (search read model only), Redis 8 (sessions, cache, rate limits, idempotency, slot holds, live tracking pub/sub), Kafka 4 (KRaft).
- **Events:** publish with `ApplicationEventPublisher` inside the state-changing transaction; Modulith JDBC registry is the outbox (`events.event_publication`); cross-module listeners are `@ApplicationModuleListener`; `@Externalized` events go to Kafka topic `<module>.<aggregate>`, key = aggregate id. Consumers dedupe via `events.processed_events`, `@RetryableTopic` → `.dlq`. JSON Schemas in `server/api/src/main/resources/events`; breaking change = version bump.
- **Frontend:** pnpm monorepo `web/`. TanStack Start + Router + Query + Form + Table + Virtual, React 19, TypeScript, zod. `packages/ui` holds every component, each with a Storybook 9 story (a11y addon must pass). `packages/tokens/brand.json` (+ its palette in `packages/tokens/palettes/`) is the ONLY place colours/fonts/radii and the product's name are defined — components use semantic `var(--color-*)` tokens only; no hex literals in components (lint rule), no literal product name in UI strings (`{brand}` / `brand.name`, lint rule).
- **Payments:** Stripe Connect Express, manual capture (escrow), Stripe Tax. **Residency:** ca-central-1.
- Bilingual en/fr from day one.

## Design system — the redesign (2026-10-18; spec: `docs/design/redesign-spec.md`, screens: `docs/SCREENS.md`)
- The "Spruce & Honey" look (2026-09-28 lock) is **retired**. The UI is rebuilt on AfroTransact's layouts and flows (grid, spacing, type scale, radii, shadows, header, search bar, cards, buttons, inputs, tables, sheets, empty states) with **our own brand** — never AfroTransact's colours, fonts or logo.
- **The brand is one file:** `web/packages/tokens/brand.json` (name and identity, radii, `palette`) + `palettes/<palette>.json` (colours, fonts, mark). The owner has not chosen the name or the palette: A `lakeshore` (default placeholder), B `evergreen`, C `dusk` — switching is the `palette` setting. `pnpm --filter @northline/tokens build` regenerates `generated/theme.json` (committed; TypeScript and the native apps read it) and `dist/tokens.css`, and **fails when any text pair of any palette drops below WCAG AA**. The rhythm (spacing, type, sizes, shadows, motion) is `scale.json` — not brand.
- Components use the **semantic roles** only (`--color-canvas/surface/text/text-muted/border/border-control`, `--color-header-*`, `--color-action-{primary,accent,neutral,dark,danger}*`, `--color-link`, `--color-focus`, `--color-danger/success/warning/info(-bg)`, `--color-badge-*`…). The old names (`--color-accent*`, `--color-highlight*`, `--color-neutral-*`, `--color-bg`, `--font-body`) are deprecated aliases for screens not yet rebuilt; `pnpm lint:tokens` keeps them out of `@northline/ui` and the shells.
- The product's name: messages say `{brand}` (filled by `useT`), code says `brand.name`, the mark + wordmark is `<Logo/>`. `pnpm lint:brand` fails on the literal. Don't rename `@northline/*` packages, Java packages, bundle ids or infra domains until the name is chosen.
- **The light direction (owner, 2026-10-19; palette B Evergreen, `brand.json` `chrome: "light"`):** white header and pages, linen for sunken areas and the footer, big photos, soft cards with a visible edge (`--color-border-card`, 3:1), the brand colour only for buttons and highlights. Fonts are self-hosted from `@fontsource` (no Google Fonts; the CSPs allow `'self'` only). Look at every UI change with `make ui-shots` (docs/LOCAL_DEVELOPMENT.md).
- The palette's sans for UI text, its display face (Evergreen: Fraunces) for headings, Phosphor **regular** icons with **fill** for the active state. Buttons are pills (32/40/48 drawn); every target stays ≥ 44px. Inline error: `color: var(--color-danger)`, border `var(--color-danger-border)`.
- Header (one white bar): logo · the location control (the place's name at every width; opens the location dialog — never a geolocation prompt on load) · search (every page) · avatar menu (guest or signed in) · cart; Services / Shop / Food as quiet tabs under it (a menu sheet on phones). **Orders & bookings live in the account menu, never the top nav.** Shop/Services/Food open landing pages, not search results. FR/EN lives in the footer and the account menu. No site nav on auth pages.
- Pilot and testing tools (the "Send feedback" control, console Pilot / UAT / Go-live / On-call / API) are hidden from the UI unless `VITE_NL_TESTING_TOOLS=1`; their routes and endpoints stay.

## Non-negotiables
- Every rule in `docs/spec/validation-rules.md` exists on client (zod) AND server (Bean Validation) with the exact messages.
- DB triggers in V016 (category limits, principal ownership, quote totals, sections) are never bypassed.
- Quotes are itemized and versioned; customers see every line, scope, exclusions, warranty, deposit, validity.
- No off-platform payments. Escrow release: services 48 h after completion, goods 7 days after delivery, food on handoff.
- Browsers never see OAuth tokens (BFF). Business + staff tokens require `acr=mfa`.
- No PII in event payloads. Never store SIN or card numbers.

## Working order
1. `docker compose --profile all up -d` (or only the stand-ins you lack — `docs/runbooks/local.md`) → `./gradlew :api:flywayMigrate` → seed categories (topics are created by the compose `events` profile).
2. auth (register/sign-in/passkey/MFA, "Not you?", sign-out) → bff → identity → merchants onboarding → catalogue/food → search indexer → booking (quotes) → payments (escrow) → orders/fulfilment → the rest.
3. `pnpm i && pnpm storybook` — build components in Storybook first, then routes in `apps/consumer`, then studio and console apps.
4. For each screen: layout and look from `docs/design/redesign-spec.md` § 2 (its AfroTransact reference); states, copy and behaviour from the matching `design/*.dc.html`.

## Code standards
Mandatory — see `docs/ARCHITECTURE.md` § Code standards: proper encapsulation, abstraction and interfaces; Java 25 language features; Go 1.26 for any Go app; Lombok; no boilerplate.

## Conventions
- IDs ULID text. Money `*_cents bigint` CAD. Time `timestamptz`, displayed in the merchant's or market's time zone from the region model (S-134, docs/runbooks/regions.md) — never a province, city or zone in code (lint).
- REST + OpenAPI 3.1 under `/api/v1`. Idempotency-Key header on money-moving POSTs (stored in Redis 24 h).
- Tests: `ModularityTests` must pass; every trigger has a failing-case test; every validation rule has a message test; Testcontainers for Postgres/Kafka/ES; Storybook interaction tests for every component state.
