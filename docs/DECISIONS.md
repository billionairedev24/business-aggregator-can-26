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

## 2026-09-29 — Data Table (web/packages/ui/src/DataTable)
- Money columns (`type: 'money'`) hold CAD cents and render with `formatMoney`; range filters take dollars. `num` columns also parse legacy strings like "$1,912.40". · platform money convention · SCREENS.md § Data Table
- Excel export is a real .xlsx (Office Open XML in a stored ZIP written in-house, no dependency); PDF is a print-styled HTML tab that calls `print()`. · avoids a heavy dependency · Data Table.dc.html
- Mutations are caller-owned async callbacks (`onCreate/onUpdate/onDelete/onAction`). A CRUD control shows only when the role permits it AND a handler (or `onCreateClick`/`onEditClick` navigation) is supplied. The design's local `set`/`call` action fields are replaced by `onAction(action, rows)`. · no fake persistence in shared components
- Hit targets: controls keep the design's visual size (32–40px) with an invisible ≥ 44px hit area (pseudo-element), so the layout stays pixel-faithful. Table cells use `overflow-wrap: break-word` (design: `anywhere`) so words are not split mid-word; column auto-hide still prevents horizontal scroll.
- French create/edit titles use "Ajouter · {entity}" / "Modifier · {entity}" to avoid grammatical gender on caller-supplied nouns; callers can pass `createLabel`.


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


## 2026-09-29 — Operations (appointments, quotes, availability, orders, dashboard)

### Schema additions (V040–V042, additive only)
- **V040 booking**: `bookings.ref/title/address_line/area` (display snapshot taken at booking time; the service name and address can change later), `created_at/updated_at`, `version` (optimistic lock for the job flow), plus indexes `(merchant_id, starts_at)`, `(customer_id)` and `booking_events(booking_id, at)`. `quote_requests.created_at` and `number`: the sequence `booking.quote_ref_seq` gives the `QT-####` reference. The composer shows it before anything is sent, and every version of every merchant's quote on that request keeps it. New table `quote_request_declines`. `quotes.created_at/created_by/sent_at/deposit_cents/tax_bps`, with CHECKs (version ≥ 1, deposit bps only for `pct`, deposit ≤ total) and unique `(request_id, merchant_id, version)`. `approvals.requested_at/requested_by`, with CHECKs: state `pending|approved|declined`, amount > 0, description 1–160 characters. New table `booking.media` holds the metadata of quote attachments and completion photos; the bytes sit behind the `MediaStore` port.
- **V040 immutability triggers**: `trg_quote_immutable` rejects any change to a non-draft quote except its lifecycle columns (`state`, `viewed_at`). It also rejects any state change on a quote that is already final. `trg_quote_lines_immutable` rejects inserting, updating or deleting lines of a non-draft quote. Together with V016's deferred `trg_quote_subtotal`, a sent total cannot be tampered with. As a result, a quote is written as `draft` (with its lines) and then moved to `sent` in the same transaction. Quotes do not use Spring Data JDBC, because it rewrites child rows on every save.
- **V041 availability**: `availability_rules.updated_at`, a weekday CHECK 1–7 (ISO, Mon = 1) and unique `(merchant, member, weekday, effective_from)`. A day without hours is stored as `[]`, so a later schedule can close a day. `booking_rules` changes:
  - PK `merchant_id`.
  - `same_day_cutoff_min`: "Same day by 9 am" is stored as notice 0 + cutoff 540.
  - `late_cancel_fee_bps` for "50% of job".
  - `emergency_premium_cents/bps`.
  - `holiday_premium_cents`, default $50. The design shows "Open · +$50" while the same-day premium is Off, so the holiday premium is a separate value.
  - CHECKs that allow only the design's options.

  `time_off.created_at/created_by` plus CHECKs. New tables `holiday_openings` and `service_areas`; service areas are stored as zone names because `region.zones` has no Calgary rows yet. `calendar_links.merchant_id/account_label/mode/connected_at`, a provider CHECK `google|outlook|ical` and unique `(merchant, member, provider)`.
- **V042 orders**: `orders.ref/placed_at/delivered_at/delivery_area`, `order_lines.title/packed_at/packed_by/issue_note` (plus a qty > 0 CHECK), and `delivery_windows.run_label` ("R-611"), because `fulfilment.runs` has no human-readable reference.

### Rules the spec left open
- The spec shows "—" or no message for several rules. The server constants and the zod schemas use the same text:
  - lines ≥ 1: "Add at least one line."
  - description > 160 characters: "At most 160 characters."
  - qty: "Quantity must be more than 0."
  - valid_hours: "Choose how long the quote is valid."
  - subtotal < 0: "Discounts can't be more than the other lines." The V016 trigger requires subtotal = Σ lines, while the design clamped the subtotal at 0.
  - deposit %: "Deposit must be between 1 and 100 %."
  - approvals: "Describe the extra parts or work." / "Enter an amount."
  - hours: "End time must be after start time." / "These hours overlap another range on the same day." / "Pick today or a later date."
  - time off: "Pick the first day." / "The last day can't be before the first day." / "Add the hours you're open."
  - booking rules: "Choose one of the options."

  Validation messages are in English in both locales, because the spec defines only English.
- The line-amount rule is reported on `lines[i].unitCents` with rule id `positive_unless_discount` (class-level constraint `@PositiveUnlessDiscount`). The composer's "Amount" is the amount per unit. Line amount = qty × unit, negative for discounts.
- Tax comes from `region.api.TaxRates.bpsFor("AB")`. Every business is treated as Alberta, the only live region. While `region.tax_profiles` is unseeded, it falls back to statutory rates. Tax applies to taxable lines (all lines by default) and is rounded half-up. "Parts cost up front" deposit = part lines + their GST. "25 %" = 25 % of the total.
- The quote composer summary uses the design's wording "N things to fix before sending.", counting one per line with errors plus the scope. The other forms use the generic "N things need attention."
- Sending when a draft exists updates that draft and sends it; the dev seed holds the design's qSeed drafts. A second send returns 409 `quote_already_sent`. Revise creates a new row with version + 1 and the same reference, and marks the prior row `superseded`. Only `sent` and `viewed` quotes can be revised. Customer acceptance remains a domain method plus an `AcceptQuote` use case, with no Studio endpoint.
- Job flow:
  - Completion photos are optional. The design says missing photos delay the release by 48 h, and `booking.completed` carries `photoCount`.
  - GPS is sent when the browser grants it and is stored in `booking_events.geom`.
  - Technicians see and move only their own jobs; other jobs return 404.
  - "Message", "Ask a question" and "Reschedule" open Messages. The messaging workstream owns conversations, and rescheduling a paid booking needs the customer's agreement.
- The job card derives escrow from the booking: if `escrow_id` is set, it shows "held" until sign-off and "released" after. This avoids a booking → payments dependency, since payments already depends on booking.api.
- The week calendar shows Mon–Sat, and Sunday only when it has jobs. Previous/next arrows were added. Whole-team closures show as "Blocked · reason". The design's "Open slot" and "Held for quote" cells are not rendered.
- The availability preview is computed server-side (`POST …/availability/preview`). It uses the unsaved ranges of the selected day plus the saved rules, and the preview day is the next occurrence of that weekday. Services come from `catalogue.services`. If there are none, the preview offers 30/45/60/90 min.
- Alberta holidays: statutory holidays plus Heritage Day and Boxing Day (the design lists Boxing Day). Dates are computed, so Thanksgiving 2026 is Oct 12; the design's "Oct 13" is the 2025 date. The next five upcoming holidays are shown.
- Team bookability writes `merchants.merchant_members.bookable` through `merchants.api.TeamRoster`, owner only (MANAGE). The team sub-line shows "All services · <days>", because service assignment per member isn't modelled.
- Calendar sync goes through the `CalendarSync` port. `local`/`test` use a fake that connects immediately. Other profiles return 409 `calendar_sync_unavailable` until the Google/Microsoft OAuth adapters exist. The iCal feed URL is `webcal://northline.ca/cal/<token>.ics`. Media works the same way: an in-memory store under `local`/`test`, and 409 `storage_unavailable` elsewhere until S3 is wired.
- Orders:
  - The seller's status is derived from the seller's own lines plus the order state: To pack, Awaiting pickup, Out for delivery, Delivered, or Issue.
  - "Mark packed" packs all of the seller's pending lines. It sets the order to `ready` when no merchant has pending lines left, otherwise to `packing`.
  - The list shows open orders plus today's issues. The chips count today's deliveries.
  - Totals include only the seller's lines.
- Dashboard:
  - `GET /api/v1/merchants/{id}/dashboard` returns one read model for every portal. The client renders the provider, seller or both variant.
  - The studio module serializes its application read model directly, with no separate web DTO, because the model is purpose-built for this screen.
  - "Instant book pauses" = compliance expiry + 15 days (design: expired Aug 31, pauses in 7 days on Sep 8).
  - Coaching counts the completed jobs without any photo among the last 20. The next tier review is the first of next month.
  - The earnings subtitle "Services in cyan, parts in magenta" is stale copy from the old palette. A legend (Services / Parts) replaces it.
- Several owning workstreams haven't merged yet. For each, a small cross-module read interface was added in the owner's `api` package, with a package-private JdbcClient adapter in its `persistence` package, for the owner to take over: `identity.api.PersonDirectory`, `merchants.api.TeamRoster` + `ComplianceStatus`, `payments.api.EarningsSummary`, `trust.api.Reputation`, `catalogue.api.CatalogueFacts`, `region.api.TaxRates`. `QuoteAccepted` moved to `booking.api`, and the payments listener's import was updated.
- Nav badges: `booking` contributes `appointments` = the number of jobs today. `orders` contributes `orders` = "N to pack" / « N à emballer ». `ca.northline.shared.NavBadgeContributor` was created with the agreed shape; the aggregating endpoint belongs to the onboarding workstream.
- Dates in the dev seed (V103) are relative to the day it runs (America/Edmonton), so Today and Tonight's run always have data. The customer personas are invented (ids `01J9ZD3V0000000000000C000n`).
