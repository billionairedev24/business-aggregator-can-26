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


## 2026-09-29 — Auth workstream (server/auth, server/bff, identity, Studio sign-in / register)

### Architecture: how the Studio signs someone in
- **The Studio renders its own sign-in UI** (design 02) and drives a **JSON API on northline-auth** (`/api/auth/**`): register → phone code → second factor → account; sign in by email or mobile → passkey / authenticator / backup code. Success writes the auth server's **own HTTP session** (cookie `NL_AUTH`, 12 h idle).
- **Hand-off to the BFF without an extra page:** the SPA then navigates to `GET /bff/login?next=/path` (studio-bff). The BFF stores `next`, starts authorization code + PKCE (`/oauth2/authorization/studio`), northline-auth finds its session and redirects straight back with a code (clients don't require consent), the BFF exchanges it, keeps the tokens in its session (cookie `NL_STUDIO`, HttpOnly) and redirects to `next` (local paths only). An unauthenticated `/oauth2/authorize` goes to the Studio's `/sign-in` (`northline.auth.login-page`).
- The SPA calls northline-auth **cross-origin but same-site** (`business.northline.ca → auth.northline.ca`; locally `localhost:3100 → localhost:9000`) with `credentials: 'include'` (`VITE_NL_AUTH_ORIGIN`). CORS allows the Studio/consumer origins. **CSRF on the JSON API** = Origin allow-list on every state-changing request + JSON-only bodies (a cross-site form can't send `application/json` without a preflight). Spring's token CSRF is off there because its cookie would live on the auth host, unreadable from the Studio. The BFF keeps Spring's CSRF (`XSRF-TOKEN` cookie / `X-XSRF-TOKEN` header, `csrf.spa()`).
- **Sign-out / "Not you?"** = `POST /bff/logout` (invalidates the BFF session, revokes the refresh token at `/oauth2/revoke`) **and** `POST {auth}/api/auth/sign-out` (ends the auth session so the next `/bff/login` can't silently sign the same person back in). `useSignOut()` does both; either failing still signs out locally.
- **Federation (Google/Apple):** OAuth2/OIDC client registrations on northline-auth with **placeholder client ids** (`GOOGLE_CLIENT_ID`, `APPLE_CLIENT_ID`/`APPLE_CLIENT_SECRET`; explicit Apple endpoints so nothing is fetched at start-up). They only vouch for name + email, which is not a business second factor, so a federated login never becomes the session: a known email continues at the Studio's factor step (`/sign-in?step=factor&identifier=`), an unknown one opens Create account pre-filled (`/register?firstName=&lastName=&email=`). Non-functional until real apps are registered.

### Identity data: one database, split ownership
- Auth and api share the **same PostgreSQL database**. northline-auth **owns** the `auth` schema (SAS clients/authorizations/consents, WebAuthn `user_entities`/`user_credentials`, `totp_secrets`, `backup_codes`) and **writes** `identity.users` (registration), `identity.sessions` (sign-in log) and `developer.audit_log` (sign-in audit). The api `identity` module **reads** `identity.users` (`GET /api/v1/me`) and will own profile edits. Migrations stay in `db/migrations`, applied by the api in prod; northline-auth runs the same Flyway files only under `local`/`test` (history table `public.flyway_schema_history`, so it's a no-op when the api already migrated).
- northline-auth connects with `search_path = auth, public` (Hikari `connection-init-sql`) because the Spring Authorization Server and WebAuthn JDBC repositories use unqualified table names; `identity.*`, `merchants.*` and `developer.*` are always schema-qualified.
- Passkeys live in Spring Security's `auth.user_credentials` (V017), not `identity.passkeys` (V002, left unused for a later device list). The WebAuthn user **name** is the `identity.users` id; the browser is shown the email and full name instead.

### Schema additions (V020, auth range V020–V029)
- `identity.users`: `first_name`, `last_name`, `phone_verified_at`, `terms_version`, `terms_accepted_at` (validation-rules.md "store terms_version, accepted_at"; the design collects first and last name separately and V002 only had `display_name`, which is still filled with "First Last"). Partial unique indexes on `phone` and `email` (citext → case-insensitive) — the V002 TODO.
- `identity.platform_roles (user_id, role ∈ staff|admin)` — source of the token `roles` claim (the spec had no table for platform staff).
- `identity.sessions`: `created_at`, `last_seen_at`, `method` (passkey|totp|backup_code|registration|google|apple), `acr` — one row per successful sign-in ("every sign-in is logged"); index `(user_id, created_at desc)`. Failed sign-ins go to `developer.audit_log` only (`auth.sign_in_failed`, written in their own transaction), successes to both (`auth.sign_in`).
- `auth.backup_codes (id, user_id, code_hash, created_at, used_at)`; `auth.totp_secrets.last_used_step` (TOTP replay protection); indexes on `auth.user_entities(name)` (unique), `auth.user_credentials(user_entity_user_id)`, `identity.passkeys(user_id)`, `developer.audit_log(actor_id, at desc)`.
- Dev seed `db/seed-dev/V101__auth.sql`: first/last names + terms for the V100 personas, TOTP secrets for Ravi and Jas, ten backup codes each for Ravi and Priya (README § Local sign-in).

### Rules the spec didn't fix
- **Registration writes nothing until the second factor is confirmed**; the pending registration (with a pre-assigned ULID) lives in the auth session. An abandoned registration leaves no account (at most an orphan `auth.user_entities` row if the passkey step was started).
- **Phone code:** 6 digits, valid **10 min**, **5 wrong tries** lock it (a new code is needed), resend after **45 s**. "Call me instead" is allowed **once per SMS code without waiting**; after that the 45 s cool-down applies to both channels. Submitting the form again inside the cool-down re-uses the open code instead of sending another. Codes go through the `SmsSender` port: `LoggingSmsSender` under `local`/`test` logs them; **no production SMS/voice provider was chosen** — outside those profiles `UnconfiguredSmsSender` fails loudly.
- **Second factor at registration:** passkey (resident key required, so the "Passkey" button works without typing an email) or authenticator app (RFC 6238, SHA-1, 6 digits, 30 s, ±1 step, a used step can't be reused). The authenticator set-up screen (QR + key + code field) isn't drawn in the design; it reuses the step's layout with minimal copy. Secrets are AES-256-GCM encrypted at rest (`northline.auth.totp-key`: secrets manager in prod, a fixed dev key under `local`/`test`).
- **Backup codes:** ten single-use codes `abcde-fghij` (unambiguous alphabet), stored as SHA-256 of the normalised code; case, spaces and dashes are ignored. They are **not** shown at registration (the design's done step doesn't show them); `POST {auth}/api/auth/backup-codes` (needs a second-factor auth session) issues a fresh set and is the hook for Settings → Security. In the sign-in factor step the code field is labelled "Backup code" rather than the design's "6-digit code", because backup codes aren't 6 digits.
- **Sign-in never reveals whether an account exists:** the identifier step always succeeds and offers all three factors (as the design does); an unknown account fails at the factor with the same message. 5 failed factor attempts lock the attempt (`429 too_many_attempts`, per auth session — a Redis-backed per-account/IP limit is still to do).
- **acr:** `acr=mfa` only when the session holds a second factor (passkey, TOTP, backup code). The phone code alone and Google/Apple never give `mfa`; such tokens simply have no `acr`. `amr` carries RFC 8176 values (`hwk`, `otp`, `sms`). The session authentication is a `UsernamePasswordAuthenticationToken` (principal = user id) with Spring Security 7 `FactorGrantedAuthority`s `FACTOR_PASSKEY|TOTP|BACKUP_CODE|PHONE_OTP` (their time is the token's `auth_time`).
- **Tokens:** ES256 (ARCHITECTURE.md) for access and ID tokens; access tokens are addressed to `[client_id, northline-api]` (the api's configured audience) and carry `scope` as a space-delimited string (RFC 9068; the api's `NorthlineJwtConverter` reads it that way), plus `roles`, `merchants`, `acr`, `amr`. ID tokens add `given_name`, `family_name`, `name`, `email`, `phone_number`, `locale`, `member_since` (Edmonton date); the BFF builds `/bff/session` from them without a userinfo call. The api resource server now declares `jws-algorithms: ES256`. The signing key is generated at start-up (fine for one local instance); **production must load a persistent key pair from the secrets manager** (not done).
- **Clients** (created/updated at start-up outside the `prod` profile): `studio-bff`, `consumer-bff`, `console-bff` (confidential, client_secret_basic, PKCE required, no consent, access 10 min, rotating refresh 12 h = session idle), `mobile-consumer`, `courier-app` (public, PKCE, rotating refresh 30 days). DPoP and `partner:*` (client credentials, private_key_jwt) are not set up. The studio-bff registration id is `studio` (redirect `…/login/oauth2/code/studio`). `server/bff` is now the studio-bff (port 8082); a consumer-bff would be the same app with another profile.
- **Sessions:** 12 h idle on both the auth server and the BFF; Redis (Spring Session) in prod, in-memory under `local`/`test` (the Redis session auto-configuration is excluded there). Cookies: HttpOnly, SameSite=Lax, Secure except under `local`. The BFF relays `/api/**` with the access token (TokenRelay, refreshed when needed) and strips the browser's `Cookie` header.
- **Messages not in validation-rules.md** (en + fr in `features/auth/messages.ts`, en on the server in `AuthMessages`): "An account already uses this email. Sign in instead." / "…this mobile number…", "Enter the 6-digit code.", "The code is 6 digits.", "That code doesn't match. Check it and try again.", "That code has expired. Send a new one.", "Too many tries. Send a new code.", "Enter your email or mobile.", "That code didn't work. Check it and try again.", "Enter one of your backup codes.", "That backup code didn't work, or it was already used.", "That passkey couldn't be verified. Try again or use another method.", "Too many attempts. Start again in a few minutes." Rule ids beyond the foundation's: `unique`, `mismatch`, `expired`, `locked`, `passkey`. The Studio maps server 422s back to its own (translated) message by field + rule.
- **Studio copy the design left open:** the sign-in done state says "Welcome back, {first name}." (the prototype's "2 things need you today." needs dashboard data the auth flow doesn't have); "Resend code" once the countdown reaches 0; "N things need attention." on a failed register submit (validation-rules.md). French for the signed-out page is ours where design/i18n-fr.js had no entry.
- The signed-out top bar shows only the brand and "Signed out": the prototype's business name, tier, portal switcher and "← Direction" link are prototype controls that need a signed-in business.
- **Legal pages:** `web/apps/studio/public/legal/terms.html` and `privacy.html` are generated by `scripts/legal-pages.mjs` from `design/09`/`10` (markup and copy verbatim, prototype runtime removed, `northline.css` copied alongside, one media query so the two-column layout stacks under 720 px). Onboarding's "Business Terms" link should point to `/legal/terms.html#business`.
- **Not done / follow-ups:** production SMS/voice provider; persistent signing key; Redis rate limits per account/IP for codes and factors; the `user.registered` event (northline-auth has no outbox — either the api emits it when it first sees a user, or auth gets a Modulith registry); step-up re-prompt for payouts; device list / session revocation UI (data is in `identity.sessions`).

## 2026-09-29 — Catalogue workstream (listings, product/service editors, bulk upload)

### Schema additions (V050, V051 — additive only)
- `catalog_products`: `ref` (display code `NL-P-88120`, from sequence `catalog_product_ref_seq` starting 88200; the dev seed uses 88120–88123), `title` (default-language title; `title_i18n` keeps translations and is merged on write), `identifier_type` (gtin|ean|isbn|none), `mpn`, `description`, `bullets text[]`, `owner_merchant_id` (seller-owned record for local goods; null = shared GTIN record), `created_by_merchant_id` (first seller of a shared record, who may edit it until it is `locked`), timestamps. Unique partial indexes on `gtin` and `ref`.
- `offers`: `title` (the Studio listing name — the design's table shows "Wiper blades · 22"" while the matched record is "Bosch Icon 22" …"), `variant_theme` (none|size|colour|size_colour|length|length_position — the contract's four plus the design's Length and Length × Position), `image_source` (shared|own), `own_images text[]` (media ids, main first), `handling_time`, `returns_policy`, `country_of_origin`, `restricted_ok`, `bilingual_ok`, `warranty`, `search_keywords`, `vetting_flags text[]`, `submitted_at`, `sales_30d` (read model; 0 until the orders workstream feeds it), timestamps. CHECKs on `condition` and on non-negative price and stock. Unique `(merchant_id, sku)`.
- `variants.position`; unique `(offer_id, sku)`.
- `services`: `name` (plain text; `name_i18n` is merged), `sku`, `included` ("What's included"), `vetting_flags`, `submitted_at`, `sales_30d`, timestamps. CHECKs on `vetting` and `status`; unique `(merchant_id, sku)`.
- `media`: `merchant_id`, `content_type`, `width`, `height`, `byte_size`, `on_white`, `created_at`.
- New tables: `attribute_templates` (required attributes and variation themes per leaf category; it has no FK, so V051 can fill it before `seedCategories` runs), `imports` (bulk uploads: counts, error report, pending rows) and `integrations` (Shopify/Square/Lightspeed connection per merchant).

### Model and rules
- Amazon-ASIN model. A GTIN that matches a record attaches the offer to it, and the offer **inherits** brand, category, attributes, description, bullets and images. These fields are read-only in the editor unless the merchant created the record and it isn't locked. A new GTIN creates the shared record, and the first seller's content and photos become it. No GTIN means a seller-owned record. The offer title always stays the seller's.
- Lifecycle:
  - A draft is submitted for vetting. Submitting returns a 422 that lists what is missing when the completeness meter is below 100 %.
  - The listing then becomes pending, and an `@ApplicationModuleListener` on `ListingSubmitted` runs the automated checks.
  - No flags: the listing is **approved and live**, and `listing.published` is published. Flags: it stays pending with `vetting_flags`, and `listing.flagged` is published for manual review in the console. Rejection belongs to the console.
  - Editing a pending listing withdraws it to draft. Editing an approved listing keeps it approved; re-vetting on edit is left to the console/vetting workstream.
  - Publishing a draft returns 409 `listing_draft`. Publishing a pending listing records the intent, so it becomes visible once approved.
  - `listing.published`, `listing.flagged`, `listing.hidden` and `listing.deleted` are externalized to `catalogue.listing`.
- Automated checks:
  - Banned category or wrong root. Banned categories come from config `northline.catalogue.banned-categories`; the default is cannabis accessories plus tobacco & vape.
  - Price more than ±60 % away from the median of approved listings in the category. This needs at least 3 comparables.
  - Regulated category (`regulated_registry`) without a verified, unexpired licence or registry row in `merchants.verifications`. This is **read by SQL across the module boundary** (`MerchantLicenceQueries`) because merchants has no public licence API yet; replace it with that API when it exists.
  - Duplicate image: a 64-bit average hash within Hamming distance 4 of another merchant's upload.
  - Main image not on white, judged from the border pixels.
- Completeness: products have 6 sections, as in the design's meter: identity (including required attributes), variants, images, price/stock/fulfilment, compliance (attestations and origin), and preview, which is always complete. Services have 4: name & category, pricing, duration, what's included.
- Validation messages: validation-rules.md has no catalogue section, so the messages live in `ListingMessages` (server) and `validation.ts` (client). The two are identical, and the client adds fr-CA translations. The design's import-error strings are used verbatim ("GTIN check digit invalid", "Missing SKU", `Category "X" requires attribute Y`, "Price $4 is 92% below category median — confirm", "Category not allowed in Shop").
- Images: "main + up to 8", following the task and the Amazon rule in chat1 (the design label said 7). With shared catalogue images, a seller can add up to 3 of their own supplementary photos. The client checks type, size and ≥ 1000 px, and the server checks again. Per-variant images are not supported yet; the column shows "inherits".
- SKU is optional in the editor and the contract. A missing SKU is generated from initials (`SVC-BI` for "Brake inspection") and suffixed `-2`, `-3` … when taken.
- "Fees on this item" uses a take rate by tier: master 9 %, trusted 12 %, registered 15 %. Processing is 2.9 % + 30¢. This reproduces the design's figures: $19.00 sale, $16.44 net, 32 % margin.
- The Compliance "Documents" buttons are shown disabled, because documents are requested during manual review. The "Bundle" listing type is shown but can't be selected.
- Bulk upload:
  - The server parses CSV and the first sheet of an .xlsx without a library (zip + StAX, with a zip-bomb guard).
  - Rows for existing SKUs update price and stock only. New SKUs are created as drafts through the same editor use case.
  - Rows that share a `parent_sku` become one listing with variants.
  - Image-URL columns are not supported, so there is no "Image URL unreachable" check.
  - Templates are generated in the browser with the UI kit's `toXlsx`.
- Integrations sit behind the `CommerceSync` port. The local/test fake connects instantly and mirrors the merchant's products with stock + 1. Outside local/test, an "unconfigured" adapter fails loudly when used; the same applies to `MediaStorage` until the S3 adapter exists. Connect and disconnect need owner (MANAGE); sync needs EDIT.
- Collections are `{"items": […]}` as BACKEND_CONVENTIONS requires. This includes `GET …/listings`, even though its contract was written as a bare array.
- Nav badge `products` is the number of listings; a count of 0 shows no badge.
- Categories are listed alphabetically within each level, because the table has no position column.

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
