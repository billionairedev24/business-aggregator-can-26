# Northline — Canadian marketplace platform

Implementation scaffold generated from the design project. Read `CLAUDE.md` first if you are an agent.

## What's here
```
CLAUDE.md                     agent instructions — spec precedence, stack, non-negotiables
README.md
docker-compose.yml            local stand-ins behind compose profiles: Postgres 17 + PostGIS, Valkey, Kafka, Elasticsearch, Mailpit, S3 storage, stripe-mock
.env.example, server/.env.example, web/apps/studio/.env.example   every setting, with local defaults
db/migrations/V001..V018      Flyway migrations, one schema per module; V016 = spec constraints & triggers
db/seed/categories.json       grouped taxonomy (~120 services, shop departments, food types) with regulators
docs/ARCHITECTURE.md          components, connections, end-to-end flows
docs/DATA_MODEL.md            every table/column with notes, events, search projections
docs/spec/legal-details.schema.json   per-structure legal fields (sole … nonprofit) + principal rules
docs/spec/storefront-sections.json    page-builder section library with on/off semantics
docs/spec/validation-rules.md         every validation rule and its exact message
docs/DECISIONS.md             log anything the spec didn't decide
docs/runbooks/                how to run each environment: local, dev, staging, prod (services, variables, secrets per cloud)
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
| chats/ | the design conversations (intent and every decision behind the screens) |

Design files load `theme/northline.css` and `support.js`; keep the folder structure intact to open them.

## Getting started
**Step-by-step guide: [`docs/runbooks/local.md`](docs/runbooks/local.md)** — prerequisites, using your own Postgres +
PostGIS and Valkey or Docker stand-ins, dev auth and real sign-in, troubleshooting. Cloud environments:
[`docs/runbooks/`](docs/runbooks/README.md) (dev, staging, prod on AWS, Google Cloud or Azure).

Configuration is environment variables with local defaults: copy `.env.example` → `.env` (docker compose),
`server/.env.example` → `server/.env` (the apps read it on start-up) and `web/apps/studio/.env.example` →
`web/apps/studio/.env`. Spring profiles: `local` (fakes, dev seed, dev auth), `dev` / `staging` / `prod` (cloud shape,
required variables checked at start-up), `test`.

Needs JDK 25 (`export JAVA_HOME=/path/to/jdk-25`), and Docker for any stand-in you don't run yourself. Details:
`docs/BACKEND_CONVENTIONS.md`.
```
docker compose --profile db up -d                 # PostGIS 17 on :5432 (northline/northline) — or use your own
cd server
./gradlew build                                   # compile all 4 apps, Error Prone/NullAway, Checkstyle, Spotless, tests (Testcontainers)
./gradlew :api:flywayMigrate                      # db/migrations → localhost:5432/northline (-Pdb.url=… for another DB, -Pdb.devSeed=true for personas)
./gradlew :api:seedCategories                     # db/seed/categories.json → catalogue.categories (idempotent)
./gradlew :api:bootRun --args='--spring.profiles.active=local'
curl -H 'X-Dev-User: 01J9ZD3V00000000000000RAV1' localhost:8080/api/v1/me/businesses
```
The `local` profile needs only Postgres. It applies the dev personas from `db/seed-dev`, authenticates the
`X-Dev-User` header without the auth server, and keeps Kafka, Elasticsearch and Redis off. Other stand-ins start per
compose profile (`--profile cache|events|search|mail|storage|payments`, or `--profile all`); Kafka topics are created
by the `events` profile. See `docs/runbooks/local.md` § 6.

## Studio without auth (fastest way to click through)
```
# one Postgres (docker compose --profile db up -d, or your own), then from server/:
./gradlew :api:flywayMigrate -Pdb.url=jdbc:postgresql://localhost:5432/northline -Pdb.devSeed=true
./gradlew :api:seedCategories -Pdb.url=jdbc:postgresql://localhost:5432/northline
./gradlew :api:bootRun --args='--spring.profiles.active=local'          # api :8080, accepts X-Dev-User
cd ../web && NL_DEV_USER=01J9ZD3V00000000000000RAV1 VITE_NL_DEV_STEP_UP=1 pnpm dev   # studio :3100 as Ravi Sandhu
```
Ravi owns the three seeded businesses (Switch business in the account menu): **Prairie Wrench** (provider), **Prairie Wrench Parts** (seller) and **Pho Dau Bo** (kitchen). Settings › Security needs the auth server (see Local sign-in below).
Smoke sweep of every screen (migrates + seeds a disposable database, starts api, auth and the studio, then checks 135 screens): `ci/studio-smoke.sh` — ports, database and Chromium are set by environment variables, see `docs/runbooks/ci.md`.

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
