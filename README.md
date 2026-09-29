# Northline — Canadian marketplace platform

Implementation scaffold generated from the design project. Read `CLAUDE.md` first if you are an agent.

## What's here
```
CLAUDE.md                     agent instructions — spec precedence, stack, non-negotiables
README.md
docker-compose.yml            local infra: Postgres 17 + PostGIS, Redis, Kafka, OpenSearch, Keycloak
db/migrations/V001..V016      Flyway migrations, one schema per module; V016 = spec constraints & triggers
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

## Getting started
```
docker compose up -d
# run Flyway against postgres://northline:northline@localhost:5432/northline with db/migrations
# seed: load db/seed/categories.json into catalogue.categories (group rows first, then leaves with parent_id)
```


## v2 layout
- `server/` — Gradle multi-project: api, auth, bff, worker (Java 25, Spring Boot 4.1.1)
- `web/` — pnpm monorepo: packages/tokens, packages/ui (Storybook), apps/consumer (TanStack Start)
- `db/migrations` — V001–V017 (V017: event outbox + authorization server)

Quick start: `docker compose up -d`, `cd server && ./gradlew :auth:bootRun :api:bootRun`, `cd web && pnpm i && pnpm storybook`.
