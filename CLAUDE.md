# Northline — agent instructions

You are implementing the Northline marketplace from a finished design spec. **Do not deviate from the spec.** Where it is silent, choose the simplest option consistent with it and record it in `docs/DECISIONS.md`.

## Source of truth (priority order)
1. `db/migrations/*.sql` — schema (V001–V017). Column names and enums are final.
2. `docs/spec/` — machine-readable rules (legal-details schema, storefront sections, validation messages) + `db/seed/categories.json`.
3. `docs/ARCHITECTURE.md`, `docs/DATA_MODEL.md` — modules, events, flows.
4. `design/*.dc.html` — interactive references of every screen (open in a browser). Behavioural spec for copy, states, ordering. Recreate, don't copy.
5. `design/09 Terms of Service`, `design/10 Privacy Policy` — ship verbatim; open from registration in a new tab.

## Stack (fixed)
- **Backend:** Java 25, Spring Boot 4.1.1, Spring Modulith 2 — `server/api` (modular monolith, one package + schema per module), `server/auth` (Spring Authorization Server, OAuth 2.1/OIDC, passkeys, TOTP, Google/Apple), `server/bff` (Spring Cloud Gateway MVC, token relay, Redis session), `server/worker` (Kafka consumers: search indexer, notifications, webhooks). Virtual threads on.
- **Data:** PostgreSQL 17 + PostGIS (Flyway), Elasticsearch 9 (search read model only), Redis 8 (sessions, cache, rate limits, idempotency, slot holds, live tracking pub/sub), Kafka 4 (KRaft).
- **Events:** publish with `ApplicationEventPublisher` inside the state-changing transaction; Modulith JDBC registry is the outbox (`events.event_publication`); cross-module listeners are `@ApplicationModuleListener`; `@Externalized` events go to Kafka topic `<module>.<aggregate>`, key = aggregate id. Consumers dedupe via `events.processed_events`, `@RetryableTopic` → `.dlq`. JSON Schemas in `server/api/src/main/resources/events`; breaking change = version bump.
- **Frontend:** pnpm monorepo `web/`. TanStack Start + Router + Query + Form + Table + Virtual, React 19, TypeScript, zod. `packages/ui` holds every component, each with a Storybook 9 story (a11y addon must pass). `packages/tokens/tokens.json` is the ONLY place colours/fonts/radii are defined — components use `var(--*)` only; no hex literals in components (lint rule).
- **Payments:** Stripe Connect Express, manual capture (escrow), Stripe Tax. **Residency:** ca-central-1.
- Bilingual en/fr from day one.

## Design system — Northline "Spruce & Honey" (LOCKED — see `docs/SCREENS.md` for every screen and state)
- Base tokens: `--color-accent #1E4D36` (spruce), `--color-highlight #D9A441` (honey), `--color-accent-2 #B4533A` (rosehip — errors), `--color-bg #F7F4EE`, `--color-text #15231B`. All ramps derived with `color-mix(in oklch)`.
- Newsreader headings, Instrument Sans body. Radius 8/12/18, pills for search, chips, avatars. Phosphor duotone icons.
- Inline error: `color: var(--color-accent-2-700)`, border `var(--color-accent-2)`.
- Header: brand · auto-detected location (map-pin) · [search off-home] · Services / Shop / Food · cart · account. **Orders & bookings live in the account menu, never the top nav.** Shop/Services/Food open landing pages, not search results.

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
4. For each screen open the matching `design/*.dc.html`; reproduce states and copy exactly.

## Code standards
Mandatory — see `docs/ARCHITECTURE.md` § Code standards: proper encapsulation, abstraction and interfaces; Java 25 language features; Go 1.26 for any Go app; Lombok; no boilerplate.

## Conventions
- IDs ULID text. Money `*_cents bigint` CAD. Time `timestamptz`, displayed in `America/Edmonton`.
- REST + OpenAPI 3.1 under `/api/v1`. Idempotency-Key header on money-moving POSTs (stored in Redis 24 h).
- Tests: `ModularityTests` must pass; every trigger has a failing-case test; every validation rule has a message test; Testcontainers for Postgres/Kafka/ES; Storybook interaction tests for every component state.
