# Northline — Canadian marketplace platform

Implementation scaffold generated from the design project. Read `CLAUDE.md` first if you are an agent.

## Test it locally

```sh
make setup        # once: toolchain check, the .env files (cp .env.example .env, …), pnpm install
                  # your own Postgres / Valkey / Grafana? BYO_SERVICES=db,cache,grafana in .env
make up-all       # every app + every stand-in you don't bring, migrated and seeded; prints every URL
make demo-data    # optional: a demo marketplace (shops, listings, orders) made through the product
make creds        # who to sign in as, and each persona's 6-digit code for right now
make down         # stops the apps and the stand-ins (never your own services)
```

| app | URL | sign in as (`make creds WHO=…`) |
|---|---|---|
| Studio | http://localhost:3100 | `ravi` (owner of all three businesses, kitchen included), `jas` (technician), `priya` (bookkeeper) |
| Consumer web | http://localhost:3000 | `amara` (bookings, quotes, orders); `sophie` / `luc` in French |
| Console | http://localhost:3200 | `priya-n` (every console role); `marc` (the second admin) |
| Phone apps | `make mobile-dev APP=consumer` / `APP=courier` | `amara` / `priya-nair` ([mobile-local.md](docs/runbooks/mobile-local.md)) |

Sign in with the email or mobile → **Authenticator app** → the code `make creds` shows (a new one every 30 s, as
often as you like; no `oathtool` needed). Backup codes are single use: `make backup-codes-reset` makes them work again.
"Create account" works too: `make creds` also shows the latest phone code from the auth log.

Your own services for one run: `make up-all BYO=db,cache,grafana` (`byo=`, `byo_arg=` and `BYO_SERVICES=` mean the
same); `make up-all-check BYO=db,cache,grafana` only checks them (your Grafana must not be on :3000, the consumer
app's port). Lighter: `make up` (auth, api, Studio BFF and Studio only).

Step by step, configuration and troubleshooting: [`docs/runbooks/local.md`](docs/runbooks/local.md).

## What's here
```
CLAUDE.md                     agent instructions — spec precedence, stack, non-negotiables
README.md
Makefile, make/*.mk           every developer and operator workflow (`make help`); scripts/stack.sh runs the apps (S-124)
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
docs/api/openapi/             OpenAPI 3.1 documents per service and audience, generated and drift-checked (S-125)
web/apps/docs                 the documentation site (Docusaurus; `make docs`, docs.<zone>; S-126)
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

## Branches
Two long-lived branches, nothing else:
- **`develop`** — integration. Every change is a short-lived branch from `develop` and a pull request into it.
- **`main`** — releases. It only moves by a pull request from `develop` into `main`.

Merged branches are deleted automatically (GitHub "Automatically delete head branches"). CI stays manual-only.

## Getting started
**Step-by-step guide: [`docs/runbooks/local.md`](docs/runbooks/local.md)** — prerequisites, using your own Postgres +
PostGIS and Valkey or Docker stand-ins, dev auth and real sign-in, troubleshooting. Cloud environments:
[`docs/runbooks/`](docs/runbooks/README.md) (dev, staging, prod on AWS, Google Cloud or Azure).

Configuration is environment variables with local defaults: copy `.env.example` → `.env` (docker compose),
`server/.env.example` → `server/.env` (the apps read it on start-up) and `web/apps/studio/.env.example` →
`web/apps/studio/.env`. Spring profiles: `local` (fakes, dev seed, dev auth), `dev` / `staging` / `prod` (cloud shape,
required variables checked at start-up), `test`.

Needs JDK 25, Node 22 + pnpm, and Docker for any stand-in you don't run yourself. **Everything goes through `make`**
(S-124; `make help` lists every target, [`docs/LOCAL_DEVELOPMENT.md`](docs/LOCAL_DEVELOPMENT.md) explains them):
```
make setup          # toolchain check (make doctor), .env files from the examples, pnpm install
make up             # Postgres 17 + PostGIS in Docker, migrations + dev personas + categories, api :8080 + Studio :3100
make run            # the same apps in the foreground with merged logs (Ctrl-C) — or: make status, make logs, make down
make all            # what CI checks: ./gradlew build (Error Prone/NullAway, Checkstyle, Spotless, tests) + web checks
curl -H 'X-Dev-User: 01J9ZD3V00000000000000RAV1' localhost:8080/api/v1/me/businesses
```
The `local` profile needs only Postgres. It applies the dev personas from `db/seed-dev`, authenticates the
`X-Dev-User` header without the auth server, and keeps Kafka, Elasticsearch and Redis off. Other stand-ins start per
compose profile (`make up PROFILES=db,cache,events,search,mail,storage,payments`, or `PROFILES=all`); Kafka topics are
created by the `events` profile. See `docs/runbooks/local.md` § 6. The plain commands (`./gradlew :api:bootRun
--args='--spring.profiles.active=local'`, `pnpm dev`, `docker compose --profile db up -d`) keep working; each make target
is a thin wrapper around them.

## Studio without auth (fastest way to click through)
```
make up             # = the default SERVICES="auth api bff studio" (real sign-in); PROFILES=none with your own Postgres
```
It runs `:api:flywayMigrate -Pdb.devSeed=true`, `:api:seedCategories`, `:api:bootRun --args='--spring.profiles.active=local'`
(api :8080, accepts X-Dev-User) and the Studio dev server with `NL_DEV_USER=01J9ZD3V00000000000000RAV1
VITE_NL_DEV_STEP_UP=1` (studio :3100 as Ravi Sandhu).
Ravi owns the three seeded businesses (Switch business in the account menu): **Prairie Wrench** (provider), **Prairie Wrench Parts** (seller) and **Pho Dau Bo** (kitchen). Settings › Security needs the auth server (see Local sign-in below).
End-to-end suite (S-117): `make e2e` starts a disposable Postgres and the whole stack under `local`, runs the Playwright journeys (sign-in, onboarding, quote → booking → escrow, order → pack → deliver, payout) and the Studio smoke sweep of every screen, then stops everything — `docs/runbooks/e2e.md`.

## The mobile apps on your phone
`make up-all`, then `make mobile-dev APP=consumer` (or `APP=courier`): Metro with the apps' API addresses on your Mac's
LAN IP, for a phone on the same Wi-Fi (`TARGET=ios` for the Simulator, `TARGET=android` for the emulator). Build the
development app once with `make mobile-dev-build`. Step by step, accounts, a test card and troubleshooting:
`docs/runbooks/mobile-local.md`.

## Local sign-in (Studio → auth → BFF → api, Postgres only)
```
make up SERVICES="auth api bff studio"
```
= northline-auth :9000 (`:auth:bootRun`, migrates + seeds the DB too; SMS codes are in `.run/logs/auth.log`), api :8080,
studio-bff :8082 (in-memory session, no Redis) and the Studio on http://localhost:3100 without `NL_DEV_USER` (it goes
through the BFF).
Open http://localhost:3100 → Sign in. **`make creds`** prints every seeded persona with the authenticator code for
right now (`make creds WHO=ravi` for one). The personas (`scripts/dev/personas.json`, seeded by `db/seed-dev` —
V101, V191, V381 — local only; `make creds-test` keeps this table, the JSON and the seed equal):

| WHO= | persona | app | email · mobile | authenticator key | backup codes |
|---|---|---|---|---|---|
| `ravi` | Ravi Sandhu: Business owner: Prairie Wrench (services), Prairie Wrench Parts (shop) and Pho Dau Bo (kitchen; Switch business for its KDS) | Studio | `ravi.sandhu@example.com` · `+1 403 555 0148` | `NORTHLINERAVIDEVTOTPSECRET234567` | `ravis-00001` … `-00010` |
| `jas` | Jas Gill: Technician at Prairie Wrench; also pays as a customer | Studio, Consumer web, Consumer app (phone) | `jas.gill@example.com` · `+1 403 555 0172` | `NORTHLINEJASDEVTOTPSECRET2345672` | `jas-00001` … `-00010` |
| `priya` | Priya Sandhu: Bookkeeper at Prairie Wrench and Prairie Wrench Parts | Studio | `priya.sandhu@example.com` · `+1 403 555 0191` | `NORTHLINEPRIYADEVTOTPSECRET23456` | `priya-00001` … `-00010` |
| `amara` | Amara Osei: Customer: bookings, quotes and orders (the default consumer persona) | Consumer web, Consumer app (phone) | `amara.osei@example.com` · `+1 403 555 0201` | `NORTHLINEAMARADEVTOTPSECRET23456` | `amara-00001` … `-00010` |
| `sophie` | Sophie Bouchard: Customer, French (fr-CA) | Consumer web, Consumer app (phone) | `sophie.bouchard@example.com` · `+1 403 555 0202` | `NORTHLINESOPHIEDEVTOTPSECRET2345` | `sophie-00001` … `-00010` |
| `minh` | Minh Tran: Customer | Consumer web, Consumer app (phone) | `minh.tran@example.com` · `+1 403 555 0203` | `NORTHLINEMINHDEVTOTPSECRET234567` | `minh-00001` … `-00010` |
| `jordan` | Jordan Whitford: Customer | Consumer web, Consumer app (phone) | `jordan.whitford@example.com` · `+1 403 555 0204` | `NORTHLINEJORDANDEVTOTPSECRET2345` | `jordan-00001` … `-00010` |
| `dana` | Dana Kowalski: Customer | Consumer web, Consumer app (phone) | `dana.kowalski@example.com` · `+1 403 555 0205` | `NORTHLINEDANADEVTOTPSECRET234567` | `dana-00001` … `-00010` |
| `kevin` | Kevin Ng: Customer | Consumer web, Consumer app (phone) | `kevin.ng@example.com` · `+1 403 555 0206` | `NORTHLINEKEVINDEVTOTPSECRET23456` | `kevin-00001` … `-00010` |
| `rosa` | Rosa Diaz: Customer | Consumer web, Consumer app (phone) | `rosa.diaz@example.com` · `+1 403 555 0207` | `NORTHLINEROSADEVTOTPSECRET234567` | `rosa-00001` … `-00010` |
| `luc` | Luc Cardinal: Customer, French (fr-CA) | Consumer web, Consumer app (phone) | `luc.cardinal@example.com` · `+1 403 555 0208` | `NORTHLINELUCDEVTOTPSECRET2345672` | `luc-00001` … `-00010` |
| `phuong` | Phuong Nguyen: Customer | Consumer web, Consumer app (phone) | `phuong.nguyen@example.com` · `+1 403 555 0209` | `NORTHLINEPHUONGDEVTOTPSECRET2345` | `phuong-00001` … `-00010` |
| `kofi-osei` | Kofi Osei: Customer with a Plus household (account settings) | Consumer web, Consumer app (phone) | `kofi.osei@example.com` · `+1 403 555 0211` | `NORTHLINEKOFIOSEIDEVTOTPSECRET23` | `kofi-o-00001` … `-00010` |
| `kofi-mensah` | Kofi Mensah: Customer: hosts tonight's group order at Pho Dau Bo | Consumer web, Consumer app (phone) | `kofi.mensah@example.com` · `+1 403 555 0301` | `NORTHLINEKOFIMENSAHDEVTOTPSECRET` | `kofi-m-00001` … `-00010` |
| `ama` | Ama Boateng: Customer: member of the group order | Consumer web, Consumer app (phone) | `ama.boateng@example.com` · `+1 403 555 0302` | `NORTHLINEAMADEVTOTPSECRET2345672` | `ama-00001` … `-00010` |
| `yaw` | Yaw Darko: Customer: member of the group order | Consumer web, Consumer app (phone) | `yaw.darko@example.com` · `+1 403 555 0303` | `NORTHLINEYAWDEVTOTPSECRET2345672` | `yaw-00001` … `-00010` |
| `priya-nair` | Priya Nair: Courier (e-bike, on a run) | Courier app (phone) | `priya.nair@example.com` · `+1 403 555 0311` | `NORTHLINEPRIYANAIRDEVTOTPSECRET2` | `priya-nair-00001` … `-00010` |
| `sam` | Sam Okafor: Courier (car and bike) | Courier app (phone) | `sam.okafor@example.com` · `+1 403 555 0312` | `NORTHLINESAMDEVTOTPSECRET2345672` | `sam-00001` … `-00010` |
| `priya-n` | Priya Natarajan: Northline staff with every console role | Console | `priya.natarajan@example.com` · `+1 403 555 0123` | `NORTHLINEPRIYANATDEVTOTPSECRET23` | `priya-n-00001` … `-00010` |
| `marc` | Marc Bélanger: Second console admin (the go-live two-person approval), French (fr-CA) | Console | `marc.belanger@example.com` · `+1 555 555 0188` | `NORTHLINEMARCDEVTOTPSECRET234567` | `marc-00001` … `-00010` |

Every persona has a reusable authenticator key (add it to any authenticator app as a time-based key, or just read the
code from `make creds`) and ten single-use backup codes; `make backup-codes-reset` makes used codes work again.

"Create account" works end to end: the 6-digit code is printed in the auth server's log (`Verification code for …`),
passkeys work in any browser on `localhost` (WebAuthn RP id `localhost`). Google/Apple need real client registrations
(`GOOGLE_CLIENT_ID`/`_SECRET`, `APPLE_*` — `docs/runbooks/federation.md`); without them the buttons say "not available". The Studio reaches northline-auth at
`VITE_NL_AUTH_ORIGIN` (default `http://localhost:9000`). How the hand-off works: `docs/DECISIONS.md` § Auth workstream.

## v2 layout
- `server/` — Gradle multi-project (wrapper 9.8.0): api, auth, bff, worker (Java 25, Spring Boot 4.1.1, Spring Modulith 2.1)
- `web/` — pnpm monorepo: packages/tokens, packages/ui (Storybook), apps/consumer (TanStack Start)
- `db/migrations` — V001–V017 design baseline, V018 foundation; workstream ranges in `docs/IMPLEMENTATION_PLAN.md`
- `db/seed-dev` — dev-only seed (V100–V109), applied under the `local` profile
- `docs/BACKEND_CONVENTIONS.md` — how to add a module, endpoint, migration, event, test

Frontend: `make web-storybook` (or `make up SERVICES=storybook`); `make web-check` runs what CI's web job runs.
