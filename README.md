# Northline — Canadian marketplace platform

Implementation scaffold generated from the design project. Read `CLAUDE.md` first if you are an agent.

## What's here
```
CLAUDE.md                     agent instructions — spec precedence, stack, non-negotiables
README.md
docker-compose.yml            local infra: Postgres 17 + PostGIS, Redis, Kafka, OpenSearch, Keycloak
db/migrations/V001..V018      Flyway migrations, one schema per module; V016 = spec constraints & triggers
db/seed/categories.json       grouped taxonomy (~120 services, shop departments, food types) with regulators
docs/ARCHITECTURE.md          components, connections, end-to-end flows
docs/DATA_MODEL.md            every table/column with notes, events, search projections
docs/spec/legal-details.schema.json   per-structure legal fields (sole … nonprofit) + principal rules
docs/spec/storefront-sections.json    page-builder section library with on/off semantics
docs/spec/validation-rules.md         every validation rule and its exact message
docs/DECISIONS.md             log anything the spec didn't decide
design/                       HTML design references (open in a browser) + design tokens
```

## Design references (`design/`)
| file | what it is |
|---|---|
| 00 Direction | product direction |
| 01 Consumer App + Consumer Screen | mobile consumer journeys (incl. Quote received) |
| 02 Provider Studio, 02b Seller Studio, 02c Kitchen Studio | merchant back-office; 02 also contains the full onboarding flow and the page builder |
| 03 Platform Console | ops / trust & safety |
| 04 Wireframes, 05 Architecture, 08 Data Model | system views (05 and 08 are the sources of `docs/`) |
| 06 Consumer Web | web storefront |
| 07a–d Onboarding | entry points into the onboarding flow (provider / seller / kitchen / not-signed-in) |
| 09 Terms of Service, 10 Privacy Policy | legal pages — ship as static content |

Design files load `theme/northline.css` and `support.js`; keep the folder structure intact to open them.

## Getting started (backend)
Needs JDK 25 (`export JAVA_HOME=/path/to/jdk-25`) and Docker. Details: `docs/BACKEND_CONVENTIONS.md`.
```
docker compose up -d postgres                     # PostGIS 17 on :5432 (northline/northline)
cd server
./gradlew build                                   # compile all 4 apps, Error Prone/NullAway, Checkstyle, Spotless, tests (Testcontainers)
./gradlew :api:flywayMigrate                      # db/migrations → localhost:5432/northline (-Pdb.url=… for another DB, -Pdb.devSeed=true for personas)
./gradlew :api:seedCategories                     # db/seed/categories.json → catalogue.categories (idempotent)
./gradlew :api:bootRun --args='--spring.profiles.active=local'
curl -H 'X-Dev-User: 01J9ZD3V00000000000000RAV1' localhost:8080/api/v1/me/businesses
```
The `local` profile needs only Postgres. It applies the dev personas from `db/seed-dev`, authenticates the
`X-Dev-User` header without the auth server, and keeps Kafka, Elasticsearch and Redis off. For the full stack, run
`docker compose up -d` and `scripts/topics.sh`, then start `:auth:bootRun` and `:api:bootRun` without `local`.

## Local sign-in (Studio → auth → BFF → api, Postgres only)
```
cd server
./gradlew :auth:bootRun --args='--spring.profiles.active=local'   # northline-auth :9000 (migrates + seeds the DB too; SMS codes are logged)
./gradlew :api:bootRun  --args='--spring.profiles.active=local'   # api :8080
./gradlew :bff:bootRun  --args='--spring.profiles.active=local'   # studio-bff :8082 (in-memory session, no Redis)
cd ../web && pnpm --filter @northline/studio dev                   # http://localhost:3100 (no NL_DEV_USER → goes through the BFF)
```
Open http://localhost:3100 → Sign in. Seeded credentials (`db/seed-dev/V101__auth.sql`, local only):

| persona | email / mobile | second factor |
|---|---|---|
| Ravi Sandhu (owner, all three businesses) | `ravi.sandhu@example.com` · `+1 403 555 0148` | authenticator key `NORTHLINERAVIDEVTOTPSECRET234567` (add it to any authenticator app, or `oathtool --totp -b <key>`), or backup codes `ravis-00001` … `ravis-00010` (single use) |
| Jas Gill (technician) | `jas.gill@example.com` · `+1 403 555 0172` | authenticator key `NORTHLINEJASDEVTOTPSECRET2345672` |
| Priya Sandhu (bookkeeper) | `priya.sandhu@example.com` · `+1 403 555 0191` | backup codes `priya-00001` … `priya-00010` |

"Create account" works end to end: the 6-digit code is printed in the auth server's log (`Verification code for …`),
passkeys work in any browser on `localhost` (WebAuthn RP id `localhost`). Google/Apple need real client ids
(`GOOGLE_CLIENT_ID`, `APPLE_CLIENT_ID`, `APPLE_CLIENT_SECRET`). The Studio reaches northline-auth at
`VITE_NL_AUTH_ORIGIN` (default `http://localhost:9000`). How the hand-off works: `docs/DECISIONS.md` § Auth workstream.

## v2 layout
- `server/` — Gradle multi-project (wrapper 9.8.0): api, auth, bff, worker (Java 25, Spring Boot 4.1.1, Spring Modulith 2.1)
- `web/` — pnpm monorepo: packages/tokens, packages/ui (Storybook), apps/consumer (TanStack Start)
- `db/migrations` — V001–V017 design baseline, V018 foundation; workstream ranges in `docs/IMPLEMENTATION_PLAN.md`
- `db/seed-dev` — dev-only seed (V100–V109), applied under the `local` profile
- `docs/BACKEND_CONVENTIONS.md` — how to add a module, endpoint, migration, event, test

Frontend: `cd web && pnpm i && pnpm storybook`.
