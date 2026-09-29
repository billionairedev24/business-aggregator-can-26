# Decisions log

Record anything the spec did not decide. Format: date · decision · why · spec reference.

- 2026-09-08 · Enums stored as `text + CHECK` in V0xx; promote to native enums after schema stabilises · keeps early migrations cheap · DATA_MODEL.md
- 2026-09-08 · Cross-module references are logical (no FK) to preserve module extractability · ARCHITECTURE.md (Spring Modulith)


## 2026-09-27 — v2 redesign + stack change
- Visual system replaced: Broadsheet → Northline "Spruce & Honey" (design/theme/northline.css, web/packages/tokens).
- Java 25 + Spring Boot 4.1.1 replaces Java 21 / Boot 3. Go services dropped — tracking runs in Java (SSE + Redis pub/sub) on virtual threads.
- Keycloak → Spring Authorization Server (we own the login UX, passkeys, "Not you?").
- OpenSearch → Elasticsearch 9. Debezium removed — Spring Modulith event publication registry is the outbox and externalizes to Kafka.
- Starter artifact names follow Boot 4's modular starters. Verify exact names/versions on start.spring.io at implementation time and record changes here.
- Orders moved from top nav to account menu; location auto-detected (browser geolocation → /api/v1/geo/reverse) with saved-address fallback; Shop/Services/Food are landing pages.


## 2026-09-29 — Backend foundation (server/, db/)

### Versions (checked against Maven Central / Gradle Plugin Portal on 2026-09-29)
- Gradle wrapper **9.8.0**, Java toolchain 25, Spring Boot **4.1.1**, Spring Framework 7.0.9, Spring Security 7.1.1, Spring Data 2026.0.1, Flyway 12.4.0, Jackson 3.1.5, Testcontainers 2.0.5, Kafka client 4.2.1, JUnit 6.0.3 (the last seven are managed by the Boot BOM).
- Corrections to the scaffold:
  - Spring Modulith `2.0.2` → **2.1.1**. 2.1.x is the line released alongside Boot 4.1.
  - Spring Cloud `2025.1.0` → **2025.1.3**. The Boot-4.1 train, 2026.0, only has milestones so far. The BFF compiles against 2025.1.3; re-check when 2026.0.0 GA ships.
  - springdoc `3.0.0` → **3.1.1**.
  - stripe-java `29.5.0` → **33.4.2** (latest non-beta).
  - ulid-creator `5.2.3` → **5.2.4**.
  - Testcontainers `1.21.3` (BOM-overridden) → Boot-managed **2.0.5**. The artifacts are now `testcontainers-postgresql` and `testcontainers-junit-jupiter`, and the class is `org.testcontainers.postgresql.PostgreSQLContainer`.
  - `com.webauthn4j:webauthn4j-core:0.29.5` → **`org.springframework.security:spring-security-webauthn`**. It is managed by Boot and pulls in webauthn4j 0.31.9 (passkeys moved into this module in Security 7).
  - `org.springframework.session:spring-session-data-redis` → **`spring-boot-starter-session-data-redis`** (Boot 4 modular starter), in auth and bff.
  - Added test starters `spring-boot-starter-webmvc-test` and `spring-boot-starter-security-test`. Boot 4 moved `@AutoConfigureMockMvc` to `org.springframework.boot.webmvc.test.autoconfigure`.
  - The other scaffold starter names were correct for Boot 4: `spring-boot-starter-webmvc`, `-flyway`, `-kafka`, `-opentelemetry`, `-security-oauth2-resource-server`, `-security-oauth2-authorization-server`, `spring-modulith-events-kafka` / `-events-jackson` / `-starter-jdbc`.
- Tooling: Lombok 1.18.48 (JDK 25 support; it prints a harmless `sun.misc.Unsafe` warning), MapStruct 1.6.3 + lombok-mapstruct-binding 0.2.0, ArchUnit 1.5.1, JSpecify 1.0.1, Checkstyle 14.3.0, Spotless 8.10.3 with palantir-java-format 2.100.0.
- **Error Prone 2.50.0 + NullAway 0.14.2 work on JDK 25**, via Gradle plugin `net.ltgt.errorprone` 5.1.1. NullAway runs in JSpecify mode with `AnnotatedPackages=ca.northline` and fails the build on main code; it is off for tests. Other Error Prone findings stay warnings. `StringSplitter`, `MissingSummary` and `JavaTimeDefaultTimeZone` are disabled as noise.
- Code changes the upgrade forced in the scaffold:
  - worker: `org.springframework.retry.annotation.Backoff` → `org.springframework.kafka.annotation.BackOff` (`@RetryableTopic(backOff = …)`), and `com.fasterxml.jackson.databind.JsonNode` → `tools.jackson.databind.JsonNode` (Jackson 3).
  - bff and worker had no main class, so `bootJar` failed. Added `BffApplication` and `WorkerApplication`.

### Migrations: fixes to V001–V017 (never applied before; now apply cleanly on PostGIS 17-3.5)
- **V010**: `fulfilment.positions` had invalid DDL (a column named `lat/lng` and a type `ms`). DATA_MODEL.md says this data is "not in Postgres … Redis Streams", so the table is no longer created. Its shape stays in a comment.
- **V012 / V013 / V015**: the schema labels `trust · loyalty`, `messaging · support` and `developer · audit` are not valid identifiers. The schemas are now **`trust`**, **`messaging`** and **`developer`**: the first word, which matches the module name where one exists. All tables keep their names and columns. For example, `trust.points_ledger`, `messaging.tickets`, `developer.audit_log`.
- V017 matches the Modulith 2.x JDBC schema (v2 columns). V018 adds the indexes from Modulith's `schema-postgresql.sql` (hash on `serialized_event`, and `completion_date`).
- No column was renamed and no enum value changed.
- Still open for the owning workstreams: the "TODO indexes/constraints" comments that were never materialised, such as unique(phone/email) on `identity.users`, unique(slug) and unique(merchant_id) on `merchants.storefronts`, and the per-structure principal role check. `developer.outbox` is obsolete now that Modulith is the outbox (Debezium was removed), but it was left in place.

### V018 (foundation range V018–V019)
- `merchants.merchants.city text`: the Studio header and "Switch business" show "<name> · Calgary", and the spec had no city on merchants (the address only lives in `legal_details`). Onboarding should fill it from the business address.
- `merchants.merchant_members.role`: NOT NULL + CHECK `('owner','technician','bookkeeper','cook')`, from DATA_MODEL.md "Staff with roles (owner, technician, bookkeeper, cook)". No "manager" role, because the spec has none. Also added index `(user_id)`.

### Flyway location and seeding
- `:api:processResources` copies `db/migrations` → `classpath:db/migration`, `db/seed-dev` → `classpath:db/seed-dev` and `db/seed` → `classpath:db/seed`. bootRun, tests, the boot jar and the Gradle DB tasks all resolve the same files, with no dependence on the working directory. Override with `SPRING_FLYWAY_LOCATIONS`.
- `./gradlew :api:flywayMigrate | flywayInfo | seedCategories` run `ca.northline.tools.DbTool` (separate `tools` source set, not in the boot jar and not scanned by Modulith). They take `-Pdb.url/-Pdb.user/-Pdb.password/-Pdb.devSeed`. This replaces the Flyway Gradle plugin that CLAUDE.md's working order implied.
- Category ids are **stable slugs**, not ULIDs: `<root>.<group>` and `<root>.<group>.<leaf>`, e.g. `service.automotive.mobile-mechanic`. This keeps the seed idempotent and lets fixtures, frontends and other agents reference the same ids. `name_i18n` holds `en` only because the seed has no French. The group `note` text has no column and is not stored. `requires_vs_check` is copied from the group to its leaves, and `regulator` goes to `regulated_registry`.
- Dev seed `db/seed-dev/V100__dev_personas.sql` (profile `local` only): Ravi Sandhu (owner), Jas Gill (technician), Priya Sandhu (bookkeeper), plus businesses Prairie Wrench (provider/master), Prairie Wrench Parts (seller/trusted) and Pho Dau Bo (kitchen/trusted), all in Calgary. They come from `bizList` in design 02, and their ULIDs are fixed (see BACKEND_CONVENTIONS.md). Legal names, GST numbers and phone numbers are invented. Feature teams use V101–V109.

### API conventions fixed by the foundation
- 422 body is exactly `{"errors":[{field, rule, message}]}` with no ProblemDetail wrapper. It has one error per field, most basic rule first. `field` is the camelCase JSON path, not the snake_case column name used in validation-rules.md. Rule ids: `required | format | length | range`, and custom constraints use their snake_cased name.
- `displayName` longer than 80 characters returns "At most 80 characters." validation-rules.md gives only the ≥ 2 message, while the DB CHECK is 2–80.
- 403/404/409 are RFC 9457 ProblemDetail with an extra `code` (`mfa_required`, `not_a_member`, `insufficient_role`, `forbidden`, `not_found`, `stale`, `duplicate`, `constraint_violation`, or a domain `Conflict` code). `type` = `https://northline.ca/problems/<code>`.
- Collections are returned as `{"items":[…]}`. Enums are serialized as lower-case codes (`CodedEnum`).
- Merchant authorization (`@RequiresMerchant`) reads membership from `merchants.merchant_members` on each request instead of trusting the `merchants` token claim, so revocation is immediate. It also requires `acr=mfa`. Permission matrix: owner = all; technician/cook = VIEW, OPERATE, EDIT; bookkeeper = VIEW, FINANCE_READ. It comes from the design's Team table and the owner/technician/bookkeeper data-table rules.
- Security: `/api/v1/me/businesses` needs only authentication, not MFA, because it is just a list. Non-`/api` paths are closed except `/actuator/health`, `/actuator/info` and the OpenAPI docs.
- `local` and `test` profiles: Kafka externalization off, Redis/ES health indicators off, OTLP export off. Production defaults are unchanged (externalization on, archive completion mode). The Modulith registry lives in schema `events` (`spring.modulith.events.jdbc.schema=events`).
- Legacy scaffold code in `booking` and `payments` (`Quote`, `QuoteService`, `EscrowOnQuoteAccepted`) was left as-is for those workstreams. `Quote` maps columns that don't exist (`customer_id`, `status`, `deposit_cents`) and uses `Instant.now()`. ArchUnit exempts `booking` from the "@Table in persistence" rule until it is rewritten.
