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


## 2026-09-29 — Onboarding + page builder workstream (merchants, studio, web/features/onboarding + storefront)

### Schema additions (V030–V031, seed V102)
- `merchants.merchants`: `province` (AB/BC/ON/QC), `work_email`, `profile jsonb` (Business-step public answers, design `bfSets`; option fields hold codes), `onboarding_step` (furthest step reached: account … listings, done), `business_terms_accepted_at`, `submitted_at`, `approved_at`, `created_by`.
- `merchants.verifications`: `check_key` (which design row: `kyc`, `registry`, `gst`, `licence:<REGISTRY>`, `insurance`, `category_permits`, `product_safety`, `returns_policy`, `ahs_permit`, `food_cert`, `inspection`, `allergen_attestation`, `aglc`, `bank`, `mfa`, `site_visit`), `position`, `created_at`, `updated_at`; unique `(merchant_id, check_key)`, indexes `(merchant_id, status)` and `(expires_at)`.
- New `merchants.documents` (id, merchant_id, purpose legal|verification|logo, file name, type, size, storage key, uploader). Its ULID is the "media id" used by `legal_details.*_doc`, `verifications.document_media_id` and `storefronts.logo_media_id` (`catalogue.media` belongs to catalogue and holds listing images).
- `merchants.storefronts`: `custom_domain_status` (pending|verified|failed; NULL iff no domain), `custom_domain_verified_at`, `created_at`, `updated_at`; the V004 TODO indexes unique(slug) and unique(merchant_id); index on `storefront_sections(storefront_id)`.
- V031: trigger `trg_principal_role` — the V004 TODO "role in allowed set for structure" (schema `x-principals` roles). The API validates first (422); the trigger is the last line of defence, like V016.
- `docs/spec/` is packaged on the api classpath (`spec/…`, `api/build.gradle.kts`) so the server validates `legal_details` against the schema file itself.
- Dev seed V102: onboarding answers, principals, categories, verified checklists and storefronts (Prairie Wrench business page published, Parts store, Pho Dau Bo menu page) for the V100 personas.

### Onboarding API — decisions the spec left open
- The applicant is created at the end of the Account step by `POST /api/v1/merchants` (not merchant-scoped; needs `scope=merchant`, and `acr=mfa` is checked in the handler). The caller becomes `owner` in `merchant_members`. `display_name` is NOT NULL (2–80), so it starts as the placeholder **"New business"** and `legal_name` as `''` until the Business step; `business` is `null` in the response until then. Tier starts `registered`, take rate 1500 bps ("15% to start").
- Business Terms: the design shows the checkbox only for brand-new accounts (07d). The api stores acceptance when sent and does not require it (open legal question). The web app requires it on the new-account path (`?new=1` or session `memberSince` = today) with "You need to accept the Business Terms." (not in spec). ON/QC (waitlist) provinces are accepted and stored.
- Account-step messages not in the spec: "Pick what your business does on Northline.", "Pick the province you operate in."; work email reuses "That doesn't look like an email address.".
- The Business step validates everything at once (design fields + `legal_details` against the schema branch + principals + categories); nothing is stored when anything fails. Legal-detail messages (the spec only says "per-field required"): "This is required.", "Upload the document.", "At least 2 characters.", "Enter the full address.", "Use the format YYYY-MM-DD.", "Pick one of the options.", and pattern messages "Business number is 9 digits (e.g. 123456789).", "Corporate access number is 10 digits.", "Corporation number is 7 digits.", "Format is 9 digits + RR0001 (e.g. 123456789 RR0001).". Spaces in number fields are typing aids and are stripped. Uploaded `*_doc` ids must belong to the merchant. Duplicate business number: "That business number is already registered on Northline." (unique index `ux_merchants_bn`).
- Principals (`x-principals`): "Enter the full legal name.", "Enter a percentage from 0 to 100.", "Pick one of the roles listed.", "Ownership can't add up to more than 100 %.", "Add at least 2 partners." / "Add at least one director or owner." / "Add at least 3 board members.", "One partner must be the signing partner." / "Add at least one director." / "Add the board chair." / "Add the president.". Sole proprietors have no table: the owner principal (100 %) comes from `owner_legal_name`. The design's co-op role "Secretary / treasurer" is split into the schema's `secretary` and `treasurer`. Co-op / non-profit rows have no share % (design "—"). Principals at or above the KYC threshold (0 = everyone) point at the single `kyc` verification row.
- The SIN field is shown disabled ("Collected by Stripe, never stored by Northline"); the client always sends `sin_collected_by_stripe: true` and the SIN never reaches Northline. `attorney_for_service` (a schema object) is two inputs (name, Alberta address) instead of the design's single input.
- Categories: sellers get the design's "Pick at least one department."; unknown or foreign-root ids: "Pick categories from the list, or suggest a new one.". Suggested categories are stored with `category_id = 'suggested:<slug>'` (the PK needs a value; logical ref only), status `requested`. Regulated leaves start `requested`, the rest `approved`. Changing the business type (only while applicant) clears categories and rebuilds the checklist and the page.
- The taxonomy is read from `catalogue.categories` (read-only cross-schema read of reference data, `TaxonomyQueries`). That table has no order or note column, so order and group notes come from `db/seed/categories.json` (the file the rows were seeded from). French group notes live in the client until the table carries French.
- Checklist per type = design `checkDefs`, except the generic "AMVIC licence" row becomes one `licence:<REGISTRY>` row per regulator of the chosen service categories (none when nothing is regulated). Sellers declare product-category permits in one row. Kitchens add licence rows only for food regulators not already covered by the AHS / AGLC checks (e.g. "Mobile permit"). Rows no longer required are deleted; kept rows keep their evidence.
- Completing checks (`POST …/verifications/{id}/complete {reference?, documentId?, expiresOn?, choice?}`): instant rows call ports (Stripe Identity, registries, bank linking — fakes under `local`/`test`); numbers go through the registry port; uploads become `submitted` (human review); attestations need `choice=signed`; returns policy `standard|perishables`; permits `none` or numbers; AGLC `not_applicable` or a licence number; the kitchen visit is an instant within 14 days. Messages: "Enter the licence number.", "Enter the permit number.", "Upload the document.", "This document has expired.", "Read and sign to continue.", "Pick a returns policy.", "Enter your permit numbers, or confirm none are required.", "Enter the licence number, or confirm you don't sell alcohol.", "Pick one of the offered visit slots.". A verified row cannot be redone (409 `already_verified`). `submitted` counts as complete for "N of M complete" and for submitting.
- Submit: 422 `business` "Complete the Business step first." / `verifications` "Complete every check before submitting."; 409 `already_submitted`. Steps review, page, listings and done need a submitted application (409 `not_submitted`).
- "Simulate approval →": `POST /api/v1/dev/merchants/{merchantId}/approve` exists only under the `local` profile (owner only; pending → active; submitted checks → verified; publishes `merchant.approved`). The button renders only in dev builds (`import.meta.env.DEV`). Real approval belongs to the Platform Console.
- Events: `merchant.submitted`, `merchant.approved` (topic `merchants.merchant`), `storefront.published` (topic `merchants.storefront`); schemas in `resources/events`.
- The city next to the business name is picked from the Business-step addresses (known AB/BC/ON/QC cities); languages are copied to `merchants.languages`.
- External systems sit behind `IdentityVerification`, `RegistryLookup`, `BankLinking`, `DomainVerifier` and `DocumentStorage` ports. Only the `local`/`test` fakes exist (`merchants.integration`), so a production profile needs real adapters before it can start. Fake rules: licence numbers containing "manual" stay `submitted`; domains containing "pending" / "fail" stay pending / fail.
- Documents: `POST …/onboarding/documents` multipart (`file`, `purpose`): PDF/PNG/JPEG ≤ 10 MB ("Upload a PDF, PNG or JPEG under 10 MB."); logos SVG/PNG ("Upload an SVG or PNG under 10 MB."). `spring.servlet.multipart.max-file-size=10MB`.
- Bean classes carry module-specific names (`OnboardingDocumentService`, `OnboardingTaxonomyQueries`, `MerchantJsonColumns` …) and every route sits under `/onboarding…`, `/verifications…` or `/storefront…` (documents: `/api/v1/merchants/{id}/onboarding/documents`) to avoid clashes after merging (coordinator note).
- `GET /api/v1/me/businesses` now also returns `status`. `/onboarding` without `m`/`type` resumes the user's first `applicant` business at its furthest step, otherwise it shows the type picker. The rail only reaches steps up to the furthest one.

### Page builder / storefront API — decisions
- The detail panel shows the spec's section texts (storefront-sections.json: "shown verbatim"), not the design's per-kind variants ("About the store" …). `both` uses `both_default_order`; its save button is the design's "Save page and add listings".
- Every change saves at once: swatch and CTA label immediately; tagline, domain and announcement on blur; sections as one optimistic `PATCH …/storefront/sections` with the full ordered list. "Reset to recommended order" sends the default list with every section on (design `resetBlocks`). Positions are rewritten in two passes so `ux_section_position` always holds.
- Brand colours: the swatches are merchant data, not theme tokens (`features/storefront/sections.ts`); "Ink" is `#15231b`. The contrast note shows the computed WCAG ratio (7.6:1 for Forest; the design's sample numbers differ slightly). The server rejects colours under 4.5:1: "White text needs 4.5:1 contrast — pick a darker colour." / format "Pick a colour like #2F5D3A.".
- Tagline ≤ 80 "At most 80 characters." (same wording as the display-name max); announcement ≤ 120 "At most 120 characters." (not in spec). Both are stored as `{"en": …}` in the `_i18n` columns (the builder edits one language for now).
- Slug: generated from the display name when the Business step first saves (`pho-dau-bo`, numbered on collision); editable through the API only: "Use 3–40 lowercase letters, numbers or hyphens." / "That address is taken.". Custom domain: "Enter a domain like book.yourbusiness.ca." / "That domain is already connected to another page."; a new domain is `pending` until `POST …/storefront/domain/verify`. Publishing with an unverified domain is refused (422 `customDomain` "Point the CNAME at pages.northline.ca and verify it before publishing.") — spec: "custom_domain requires CNAME verification before published_at".
- Publishing needs an approved (active) business: 409 `not_approved` "Your page goes live once Northline approves your business.". Onboarding step 5 never publishes.
- Studio `page` = the full builder (a superset of the design's lighter Studio mock, so logo, tagline, domain and CTA stay editable after onboarding) + announcement bar + Publish / Preview as customer (web preview dialog) / Embed code (script snippet). The lede shows the publish state instead of the design's sample "1,204 visits … 8.6% booked" (no analytics source yet). "Provider-funded reward" belongs to trust/loyalty (`trust.merchant_rewards`); it is shown disabled with a note until that module exists.
- The preview uses real data (catalogue listings when available, profile texts, verified facts; "$2M insured" because the insurance check requires ≥ $2M) and neutral placeholders otherwise.
- Public read `GET /api/v1/storefronts/{slug}` (+ `/logo`): published pages of active merchants only.

### Listings step (catalogue / kitchen contract)
- Calls only the contract endpoints; `GET …/listings` may answer a bare array or `{items}`. The kitchen form also reads `GET …/menus` (`[{id, name, sections: [{id, name}]}]`) and `GET …/modifier-groups` (`[{id, name}]`), which are not in the contract yet (404 = none). Photos are not part of the create contracts; the photo tiles say photos are added in the listing editor. Client messages: "Enter a name.", "Enter a price.", "Pick a category.", "Enter the stock on hand.", "Pick a menu and a section.", "Declare allergens, or choose None."; "Look up" checks the GS1 check digit ("Enter the 12 or 13 digits of the barcode.").

### Nav badges
- `ca.northline.shared.NavBadgeContributor` (`badges(Context{merchantId, userId, role, locale})` → screen key → text) and the `studio` module's `GET /api/v1/merchants/{merchantId}/nav-badges` (VIEW). The response is a plain map (not `{items}`), as the shell consumes it. A failing contributor is skipped; the first bean wins on a duplicate key. The design shows no badge for onboarding or page-builder screens, so this workstream registers no contributor.

### Frontend
- New generic UI: `GroupedMultiSelect` (combobox + grouped listbox, limit, suggest) and `FileButton`, with stories. The shell's `businessesQuery` now accepts the api's `{items}` envelope (it expected a bare array).
- Kitchen-visit slots are offered as the next business days at 16:00 and 20:00 UTC (10:00 / 14:00 MDT); the server only checks "within the next 14 days".
