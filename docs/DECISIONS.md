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
- **Federation (Google/Apple):** OAuth2/OIDC client registrations on northline-auth with **placeholder client ids** (`GOOGLE_CLIENT_ID`, `APPLE_CLIENT_ID`/`APPLE_CLIENT_SECRET`; explicit Apple endpoints so nothing is fetched at start-up). They only vouch for name + email, which is not a business second factor, so a federated login never becomes the session: a known email continues at the Studio's factor step (`/sign-in?step=factor&identifier=`), an unknown one opens Create account pre-filled (`/register?firstName=&lastName=&email=`). ~~Non-functional until real apps are registered.~~ Real registrations, linking and the new-user path: S-18 below.

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
- **Phone code:** 6 digits, valid **10 min**, **5 wrong tries** lock it (a new code is needed), resend after **45 s**. "Call me instead" is allowed **once per SMS code without waiting**; after that the 45 s cool-down applies to both channels. Submitting the form again inside the cool-down re-uses the open code instead of sending another. Codes go through the `SmsSender` port: `LoggingSmsSender` under `local`/`test` logs them; ~~no production SMS/voice provider was chosen~~ — S-8 added Twilio and AWS adapters chosen by `northline.sms.provider` (section S-8 below).
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
- **Not done / follow-ups:** production SMS/voice provider; persistent signing key; Redis rate limits per account/IP for codes and factors; ~~the `user.registered` event (northline-auth has no outbox — either the api emits it when it first sees a user, or auth gets a Modulith registry)~~ (S-28: auth got the registry); step-up re-prompt for payouts; ~~device list / session revocation UI (data is in `identity.sessions`)~~ (S-19).

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

## 2026-09-29 — Messaging, help & reviews workstream (Messages, Help & support, Reviews, quality score)

### Schema additions (V070–V073, additive only; dev seed V106)
- **V070 `messaging.threads`**: `merchant_id`, `kind` (`customer` | `support` | `case`), `counterpart_id`, `counterpart_name` (display snapshot), `subject` (context line: "brake inspection Tue 9:00"), `ref_code` (BK-7712, NL-48213, DS-1188), `assignee_id` (team member on the job), `merchant_read_at` (one read mark per business — shared inbox), `created_at`; indexes on `(merchant_id, kind, last_message_at)` and `(ref_type, ref_id)`. **`messaging.messages`**: `sender_role` (`merchant` | `customer` | `agent` | `system`), `sender_name` (agents' display name, "Dev K."), in-module FK to threads, `flagged` defaults to false, index `(thread_id, at)`. New **`messaging.attachments`** (metadata; `messages.attachments` holds its ids). **`messaging.macros`**: `portals text[]`, `position`, unique `key`; quick replies are macros with `topic = 'quick_reply'`, seeded in the migration (platform content).
- **V071 `messaging.tickets`**: `number` (sequence starting at 4480, shown as `HD-<number>`, so the design's next case is HD-4480), `merchant_id`, `opened_by`, `subject`, `channel` (`chat` | `call` | `email`), `urgent`, `ref_label`, `context jsonb`, `agent_name`, `resolution_note`, `resolved_at`, `created_at`, `updated_at`; CHECK on `priority` (`normal` | `priority` | `urgent`); the TODO indexes. New tables **`help_topics`** (per portal, mapped to a case topic), **`help_articles`** (`title_i18n` / `body_i18n` / `section_i18n` en + fr, `topic_keys[]`, `portals[]`, `read_min`, `featured` position, GIN full-text indexes per language), **`status_components`** (platform status rows).
- **V072**: help-centre content (12 topics, 26 articles, en + fr). Platform content, so a migration rather than dev seed.
- **V073 `trust.reviews`**: `author_name`, `job_label` (display snapshots), `created_at`, `reply_at`, `reply_by`, `reported_at`, `report_reason`, `report_note`, `reported_by`; CHECKs rating 1–5 and "verified" (`ref_type IN ('booking','order')`, `ref_id` and `author_id` not null); unique `(ref_id, author_id, target_type)`; index `(target_type, target_id, created_at)`. **Trigger `trust.reviews_immutable`**: a review's content (rating, text, tags, author, transaction, dates) never changes and the reply is written once. **`trust.flags`**: `merchant_id`, `created_at`, indexes.

### Rules the spec didn't fix
- **Inbox scope by role**: owners see customer and Northline-support threads; technicians only customer threads assigned to them (`assignee_id`); cooks every customer thread of the kitchen; bookkeepers none (the empty state explains why). Sending needs `OPERATE`. Help-case threads never appear in Messages.
- **Unread** is per business: a thread is unread while it has a customer/agent message newer than the team's last open (`POST …/threads/{id}/read`, sent when a thread is opened) or the team's own last message. Badge `messages` = unread threads **the viewer can see** (uses the `NavBadgeContributor.Context` user and role).
- **Real time**: the Studio polls (thread list 15 s, open thread 5 s, cases 30 s, open case 10 s). Server-sent events (`GET …/threads/stream`) are the planned replacement; `message.sent` is already externalized for the notifications worker.
- **Masking and off-platform nudges** (design: "Phone numbers are masked"): phone numbers and email addresses in business messages are replaced (`•••-•••-••••`, `•••@•••`). Masked contact details or payment words (e-transfer, Interac, cash only, PayPal, "pay me directly", virement…) set `messages.flagged`, show a one-line nudge under the sent bubble, and the trust module opens an `off_platform_payment` flag (listener on `message.sent`, idempotent per message). Case conversations with Northline are not masked.
- **Quick replies**: the design's four provider replies. The design has none for sellers and kitchens, so seller (packed / out of stock / ready for pickup) and kitchen (preparing / running late / ready / item sold out) sets were added. A quick reply fills the composer; it is sent with its `template_key` only if the text is unchanged.
- **Attachments** (messages and cases): JPG, PNG, HEIC or PDF, ≤ 10 MB (the declared type must match the file signature), up to 5 per message; bytes go through the `AttachmentStorage` port (a local folder under `local`/`test`, a loud placeholder elsewhere until the S3 adapter exists). Routes live under `/message-attachments`.
- **Help centre**: topics are the design's per portal (kitchen vs everyone else); cards show the real article count (the design's "12 articles" were placeholders). Clicking a topic lists its articles with "Still stuck? Contact support about this →" (the prototype jumped straight to the form). With no search the heading reads "Suggested for you" (the prototype showed an empty heading); with a search it is the query, as in the design. Search is Postgres full text in the reader's language, any word matching, ranked title-first. Articles open in a drawer.
- **"Start a chat" / "Request a callback"** open Contact support with that channel chosen (the prototype only set the channel). **"Français"** in the form switches the UI language; the case's `lang` follows the UI language.
- **Case SLA** (design `helpSlaNote`) counts support hours, 7 am–11 pm Mountain, every day: urgent 15 min, Master tier 1 h (`priority`), everyone else 4 h. `sla_due_at` is when Northline owes the next reply; the table's "Next" column shows "Reply by …". A reply to a case waiting on the business sends it back to `in_progress` with a fresh target; resolved cases can't be replied to (409 `case_resolved`). Every team member may open and follow cases (the design's case table lets every role create). The subject is the first line of the description (≤ 80 chars) — the form has no subject field. "Open a case" in the table opens the Contact support tab.
- **Account context** attached to a case: portal, tier, role and the last 5 domain events about the business, read from the Modulith event registry (`events.event_publication` + archive — infrastructure, not a module).
- **"Related to"** options come from the `CaseReferences` SPI in `messaging.api`; messaging contributes the bookings, orders and disputes its threads link to ("Booking BK-7712 · A. Osei"). Payouts and documents appear once finance / compliance implement the SPI.
- **Cross-module read**: messaging reads `merchants.merchants.type/tier` by SQL (`MessagingMerchantProfiles`) because the merchants module has no public query yet — replace it with a merchants API when one exists.
- **Reviews**: the business replies once, publicly (no edit window — the design shows none), and may report once (fake, offensive, personal info, wrong business, other — "other" needs a note); a report opens a `review_report` flag. Owners, technicians and cooks respond; bookkeepers read. A "two-way" note explains that customers are rated back from the job. Reviews list newest first, 10 per page with "Show more reviews". The average is rounded half-up to one decimal; the `reviews` badge is that average in the viewer's locale ("4.9" / "4,9").
- **Seed rating**: the design's distribution (271/32/6/2/1) averages 4.83, which rounds to 4.8, not the "4.9" shown everywhere. The dev seed keeps 312 reviews and the 4.9 headline by moving eight 4★ to 5★ (279/24/6/2/1 → 4.85); the bars read 89/8/2/1/0 instead of 87/10/2/1/0.
- **Quality score**: `trust.quality_scores.components` is `{"on_time":{"value","floor"}, "photos", "response", "rebook", "disputes"}`. `QualityQuery` returns them in the dashboard's order with a 0–100 `bar` (disputes inverted: 100 − 10 × rate, so 0.3 % → 97 against a floor bar of 90, as in the design). `GET /quality` 404s before the first nightly score.
- **Seed specifics**: cases HD-4471/4402/4298 (Prairie Wrench), HD-4466 (Parts), HD-4472 (Pho Dau Bo — the design reuses 4471 for kitchens; numbers must be unique). Parts and Pho Dau Bo threads, reviews and cases are written in the design's voice (the design only details Prairie Wrench).
- **Shared kernel**: `NavBadgeContributor.badges(Context)` per the coordinator's merged signature; catalogue's contributor was adapted in this branch only so it compiles.


## 2026-09-29 — Finance workstream (payments module, Earnings / Reports / Payouts / Refunds & disputes)

### Architecture
- **Step-up for money moves** ("Payouts always require a fresh authentication", design 02). northline-auth issues a **step-up proof**: `POST /api/auth/step-up/passkey/options`, `/api/auth/step-up/passkey {credential}`, `/api/auth/step-up/totp {code}` → `{proof, expiresAt}`. The proof is an ES256 JWT signed with the access-token key: `aud=northline-api/step-up` (so the resource server never accepts it as an access token), `token_use=step_up`, `sub`, `acr=mfa`, `amr`, `auth_time`, one-time `jti`, 5 min. It needs an auth session with a second factor; the factor must be the session user's; 5 failures lock step-up for that session. The Studio sends it as **`X-Step-Up`** on `POST …/payouts/instant` and `POST …/payouts/bank-accounts/{id}/confirm`; the api (`JwtStepUpVerifier`) checks signature/issuer via the issuer's JWKs, audience, `token_use`, `sub` = caller, `auth_time` ≤ 5 min, and consumes the `jti`. Missing/invalid → **403 `step_up_required`**. No api-side challenge endpoint was needed. The Studio offers the passkey first and "Use authenticator code instead" (the seeded personas have TOTP, not passkeys). This closes the auth workstream's "step-up re-prompt for payouts" follow-up.
- **Dev step-up:** under the api's `local` and `test` profiles the literal proof `dev` is also accepted; the Studio sends it when built with `VITE_NL_DEV_STEP_UP=1` (use with `NL_DEV_USER` when northline-auth isn't running).
- **Idempotency-Key** is required on money-moving POSTs (instant payout, bank confirm, goodwill offer, full refund, refund accept): missing → 422 `{field:"Idempotency-Key", rule:"required", message:"Idempotency-Key header is required."}`; same key + same body → the stored response (`Idempotent-Replayed: true`); same key + other body → 409 `idempotency_key_reused`; concurrent → 409 `idempotency_in_progress`. Keys are scoped `merchant:user:operation` and kept 24 h: **Redis** (`nl:idem:*` hashes) outside `local`/`test`, table `payments.idempotency_keys` under `local`/`test` (Redis isn't running there). A failed request releases its key so the client can retry with it; the Studio keeps one key per user action across retries. Step-up proof ids are consumed through the same store.
- **Stripe:** `PaymentGateway` / `PayoutGateway` ports. `StripeConnectGateway` (stripe-java `StripeClient`) is active only when `northline.payments.stripe-secret-key` is set (`NORTHLINE_PAYMENTS_STRIPE_SECRET_KEY`, plus `…STRIPE_PUBLISHABLE_KEY` for Stripe.js); otherwise `FakeStripeGateway` (instant payouts arrive in 30 min, scheduled ones the next business morning, "instant linking" returns RBC ··8820). Instant bank linking uses a Financial Connections session + Stripe.js `collectBankAccountToken`; typed details are tokenized at Stripe and **never stored** (only last 4 and Stripe's reference).
- **Escrow = manual capture:** `hold` records the authorized PaymentIntent (no ledger entry); capture at fulfilment; release per kind — services 48 h after completion, goods 7 days after delivery, food on handoff; sign-off / delivery confirmation releases at once (CLAUDE.md). Design 02's Orders footnote ("24 h after delivery") conflicts with CLAUDE.md; CLAUDE.md wins. Other modules drive it through `payments.api.EscrowLifecycle` (idempotent on `(refType, refId)`).
- **Ledger** accounts: V011's `escrow`, `revenue`, `stripe_fees`, `tax_payable`, `merchant:<id>` **plus `stripe_balance`** (cash at Stripe) so every posting balances. "Released" / "Available now" = credit balance of `merchant:<id>` minus open refund cases on already-released money. GST/HST (`tax_cents`) is collected on top of the merchant's amount and owed to the CRA (Northline remits as marketplace facilitator); refunds don't reverse tax yet.
- **Refunds are never instant** (chat 1): request → seller review (under $25: approved automatically unless contested within 48 h; otherwise 24 h, then a Northline agent) → approved → the **refund queue** job pays it (`refund.issued`). While open, the escrow goes on hold (`disputed`) or, if already released, the amount is held back from payouts. A full refund of unreleased escrow comes out of escrow; partial ones release the rest and debit the merchant.
- **Disputes:** the merchant drafts a response and uploads evidence (owner / technician / cook), then (owner only) sends a goodwill offer (design: 50 %; customer has 72 h; declined or expired → agent), refunds in full, or contests (needs the written response → agent, 2 business days). `payments.api.DisputeDecisions` (accept/decline offer, agent decisions incl. refund cases) and `CustomerCases` (open a refund or dispute) are for the consumer app and console. Studio goodwill offers / refunds take an Idempotency-Key but no step-up (money only returns to the customer's original payment method).
- **Jobs** (`PaymentsScheduler`, every minute, not under `test`): release due escrow, lapse refund cases and offers, pay the refund queue, activate bank accounts after the 24 h hold (`payout_account.changed` phase `effective`), scheduled payouts at 09:00 Edmonton on payout days, settle in-transit payouts (Stripe webhooks would in production — not implemented).
- The legacy scaffold `payments.EscrowService` / `EscrowOnQuoteAccepted` (a no-op listener on the old `booking.QuoteAccepted`) was removed. **Operations must call `EscrowLifecycle.hold / fulfilled / confirmed`** — nothing holds escrow on quote acceptance yet.

### Rules the spec didn't fix
- Take rate: `merchants.merchants.take_rate_bps` when set, else the tier's (Master 9 %, Trusted 12 %, Registered 15 % — design 02), fixed on the escrow when held. Payments reads `tier` / `take_rate_bps` from `merchants.merchants` with read-only SQL because the merchants module exposes no query — replace with a `merchants.api` query when one exists.
- Headline "{available + escrow releasing before the next payout} releasing {weekday}" (a date when > 7 days away); manual schedule → "{available} available to pay out". "In escrow" is **net** (what the merchant will get); "on hold" is **gross** of escrow on hold + held refund amounts.
- Payouts run 09:00 America/Edmonton: weekly Mon–Fri (default Friday), daily = business days, monthly 1st / 15th / last; payout days inside a bank-change hold are skipped. Reserve none / keep $500 / keep 10 % (`reserve_cents` 50000 or `reserve_percent` 10). Instant: 1 % (min $0.50), from $1.00, up to available − reserve, eligible debit-linked account only, not during a bank-change hold (409 `payouts_paused`).
- Reports: 30 d = daily points, 90 d = 13 weekly points, 12 mo = 12 calendar months (current partial); previous period = same length before. Gross = escrow amounts by job/order date (incl. later refunded); refund rate = refunds paid ÷ gross; repeat customers = customers of the period with ≥ 2 jobs/orders up to its end; By listing = top 5 + "Everything else"; sources = share of jobs/orders. "Category avg" comes from `payments.benchmarks` (per kind, maintained by platform finance; seeded 3.1 % / 3.1 % / 2.8 %), so no merchant sees an average of a handful of competitors.
- Dispute rate = disputes that count (open, lost, partial — not won, not accepted goodwill) ÷ jobs & orders, trailing 12 months. Floors: Master 1 % (design), Trusted 1.5 %, Registered 2 % (ours).
- "Refunds this month" = refund cases opened in the last 30 days (rolling).
- Money-in-motion copy per portal (the design shows the provider's): seller "2 · Delivered / Customer confirms · 3 · Confirmed / 7 days", kitchen "2 · You hand off / Courier or pickup · 3 · On handoff", both "Sign-off 48 h / delivery 7 d"; step 4 follows the schedule ("4 · Daily (or instant)").
- Tax documents and exports are CSV (`reports/export.csv`, `reports/gst-summary.csv`, `reports/annual-statement.csv`); no PDF library was added.
- Evidence uploads are the raw file body (`Content-Type` + URL-encoded `X-File-Name`), not multipart, so the app-wide multipart limits stay untouched: JPG/PNG/HEIC/PDF, ≤ 10 MB, ≤ 20 files. Stored under `local`/`test` in `$TMPDIR/northline-evidence` (`northline.payments.evidence-dir`); **production object storage (ca-central-1) is not chosen** — uploads fail loudly there.
- Bank holder: the design's "Prairie Wrench Automotive Ltd." differs from V100's legal name; the seed uses "Prairie Wrench Mobile Mechanics Ltd." (the holder must match the legal entity).
- Nav badges: `payouts` = short weekday of the next weekly payout ("Fri" / "ven."), a short date for daily/monthly, none for manual or during a bank-change hold; `refunds` = disputes awaiting the merchant + refund cases in seller review.
- Messages not in validation-rules.md (server `PayoutMessages` / `CaseMessages`; Studio `features/finance/messages.ts`, en + fr): "Enter an amount.", "Instant payouts start at $1.00.", "You can pay out up to {amount}.", "Choose a payout schedule.", "Choose a day of the week.", "Choose a day of the month.", "Choose a reserve option.", "Choose how to add the account.", "Connect your bank first.", "Enter the 3-digit institution number.", "Enter the 5-digit transit number.", "Account numbers are 7 to 12 digits.", "Enter the account holder's legal name.", "Keep your response under 2,000 characters.", "Write your response before sending this to an agent.", "Offer less than the full amount — or choose Full refund.", "Tell the agent why you're contesting this refund.", "Choose a file to upload.", "Upload a photo (JPG, PNG, HEIC) or a PDF.", "Files can be up to 10 MB.", "You can attach up to 20 files.", "Idempotency-Key header is required.". French finance copy is ours where design/i18n-fr.js had none (fiducie, versements, litiges follow the glossary).

### Schema additions (V060–V061)
- `payments.escrows`: `kind`, `label`, `order_number`, `customer_id`, `customer_name` (display form copied at hold), `listing_id`, `listing_name`, `source`, `take_rate_bps`, `fee_cents`, `tax_cents`, `occurred_at`, `fulfilled_at`, `created_at`, `version`; unique `(ref_type, ref_id)`; indexes for the release job and merchant queries. Unique `payment_intents.stripe_pi` (V011 TODO).
- `payments.payouts`: `created_at`, `item_count`, `payout_account_id`, `destination`, `requested_by`, `version`, CHECK on `state` (Stripe states). `payments.payout_settings`: the missing PRIMARY KEY, `monthly_anchor`, `reserve_percent`, `updated_at/by`, CHECK weekday 1–7.
- New: `payments.connected_accounts`, `payments.payout_accounts` (never the account number; one active + one pending per merchant), `payments.benchmarks`, `payments.idempotency_keys`, sequence `payments.case_numbers`.
- `payments.refunds`: `merchant_id`, `escrow_id`, `dispute_id`, `case_number`, `what`, `customer_name`, `kind` (refund | credit), `auto`, `contest_by`, `contest_reason`, `created_at`, `decided_at`, `paid_at`, `stripe_refund`, `version`. `payments.disputes`: `merchant_id`, `case_number`, `subject`, `amount_cents`, `customer_name`, `customer_statement`, `response`, `response_updated_at`, `offer_cents/state/expires_at`, `refund_cents`, `respond_by`, `opened_at`, `decided_at`, `version`; `evidence` jsonb = the file list.
- `payments.ledger_entries`: NOT NULLs, one-sided CHECK, indexes, and an **append-only trigger** (UPDATE / DELETE refused).
- Dev seed `db/seed-dev/V105__finance.sql` (generated; times relative to when it runs) reproduces design 02's Prairie Wrench numbers and checks the $822.60 balance on apply; Prairie Wrench Parts has the open RF-2214 (P. Nguyen), Pho Dau Bo a year of food orders.

## Integration (coordinator)
- 2026-09-29 · `NavBadgeContributor` standardised on the onboarding shape `badges(Context{merchantId, userId, role, locale})` — personal badges (a technician's own jobs) need the caller; all contributors adapted.
- 2026-09-29 · Booking's job-photo upload moved to `POST /api/v1/merchants/{id}/jobs/media` (catalogue owns `/media`); `booking.application.MediaService` renamed `JobMediaService` (bean-name clash).
- 2026-09-29 · Dashboard now composes the owners' queries: `payments.api.EarningsQuery` (12-week net, month net, new `releasing()` = the Earnings headline "… releasing Friday") and `trust.api.RatingQuery` / `QualityQuery`. The operations stand-in `trust.api.Reputation` is removed; `payments.api.EarningsSummary` remains only for open cases and refund rate (dispute subject now read from `payments.disputes.subject`).

## 2026-09-29 — Kitchen workstream (food module, Studio kitchen portal)

### Schema additions (V090–V091, seed V108; additive only)
- **V090 menus**: `food.menus.name/sort/published_at/created_at/updated_at` (`name_i18n` is still written as `{"en": name}` for the search projection); `menu_sections.name/created_at`; `menu_items.name/description/sort/status (draft|published)/availability (always|lunch|after_5|weekends)/combo_eligible/sold_out_on/photo_key/photo_content_type/published_at/created_at/updated_at` plus CHECKs (price > 0, prep 0/5/10, daily limit 1–999, allergen codes ⊂ Health Canada list, vetting draft|pending|approved|rejected, **a published item always has an allergen declaration**); `modifier_groups.name/pick_rule (exactly|at_least|up_to)/pick_count/show_for_option_ids/sort/created_at` (min/max_select stay the numeric form of the rule); `modifier_options.name/sort` (+ delta 0–$100); `combos.name/pricing (fixed|percent_off)/swaps_allowed/created_at/updated_at` + status/price CHECKs; new `food.kitchen_promos`.
- **V091 operations**: unique `kitchen_settings(merchant_id)` (V006 had no key) + `prep_bump_min, large_order_cents/add_min, auto_pause_late, group_max, scheduled_days, delivery_areas, paused_by, updated_at` with CHECKs limited to the design's select options; new `food.opening_hours` (per ISO weekday, ranges jsonb), `food.holiday_hours`, `food.kitchen_tickets` (the KDS stage per food order). **Cross-schema addition:** `orders.orders.fulfilment_mode (delivery|pickup)` and `customer_eta` — V009 has no way to tell a pickup order from a delivery; checkout / the consumer app must fill them (null = delivery). Index on `fulfilment.stops(order_id)`.
- `allergens` NULL = not declared; `'{}'` = declared none (the onboarding form's "None" sends `[]`).

### Decisions the spec left open
- **Orders stay in `orders`.** The kitchen keeps its own ticket (`food.kitchen_tickets`, no row = New) and reads orders/lines/group orders/courier stops read-only (like catalogue's licence query). Each step publishes an event in `food.api`, externalized to `orders.order` (key = order id): `KitchenOrderAccepted` (`order.accepted`, prepMin + readyBy), `KitchenOrderReady` (`order.ready`, late flag), `FoodOrderHandedOff` (`order.handed_off`, fulfilmentMode). New files in the orders module (`FoodOrderProgress` listener, `FoodOrderStates` port + JDBC adapter) move `orders.state`: placed → accepted → ready → `picked_up` (courier) or `delivered` + `delivered_at` (pickup at the counter). **Payments must release the kitchen's escrow on `order.handed_off`** ("food on handoff") — not implemented here.
- KDS stages: New = placed, Cooking = accepted, Ready = ready. Board order = design (New, Cooking, Ready; oldest first). Scheduled orders appear 60 min before `scheduled_for`. Actions are `OPERATE` (owner, cook); a second tap on a moved ticket is 409 `kitchen_stage`; cancelled/refunded orders 409 `order_closed`. The board polls every 15 s.
- Promised prep at Accept = default prep + busy bump + slowest line's `prep_add_min` + large-order extra (order share ≥ threshold). "Busy · +5 min" steps 5 up to +30 (409 `prep_bump_max`). The header shows "Prep time shown to customers: **30 min (+5 busy)**" — the design's literal `{{prepBump}}+25 min` ("0+25 min") is a prototype artefact.
- Pickup CTA reads "Handed to customer" (the design only has "Handed to courier", which is wrong for pickup). "Where" line: courier legs from `fulfilment.stops/runs/couriers` (first name via `PersonDirectory`): finding a courier / courier assigned / {name} arriving {time} / {name} waiting; pickup: customer {n} min away (from `customer_eta`). Customer name = `PersonDirectory.shortName()` ("A. Osei"); group orders show the host's first name + others ("Group · Kofi +2").
- Pause = 30 min (`paused_until`, auto-resume), events `kitchen.paused` / `kitchen.resumed` on new topic `food.kitchen` (added to `scripts/topics.sh`). The auto-pause-on-late-orders setting is stored, not enforced (needs the on-time score).
- Item visibility (`vetting`): draft → `draft`; published without photo → `pending` ("Needs photo"); published with photo but kitchen not active → `pending` ("Hidden until approved"); else `approved` (live). Customers see it only when the menu is live and it is not sold out. `merchant.approved` (kitchen) re-audits published items. The "prices within ±40 % of cuisine median" check is not automated. Onboarding's `POST /menu-items` defaults to `publish: true` (goes live on approval + photo).
- `merchant.submitted` (kitchen) creates a draft "Dinner menu" with Starters / Mains / Drinks / Dessert (the editor's section options) so onboarding's "First listings" step has a menu to add to.
- Publishing a menu needs an approved kitchen (409 `not_approved`, same rule as the storefront). Hiding a live menu sets `hidden`.
- "Sold out today" stores the Edmonton date (`sold_out_on`) and clears itself the next day. A reached daily limit also shows sold out. Cooks may toggle it (EDIT).
- Photos: JPEG/PNG/WebP ≤ 10 MB, ≥ 1000 px on the short side (WebP isn't measured, no JDK reader), behind the `KitchenPhotoStore` port (local-disk fake under local/test, unconfigured elsewhere). Seed items reference keys with no bytes; the Studio falls back to the halftone tile.
- "Import from POS / CSV": CSV only (header `section,name,price,allergens[,description,dietary,prep_add_min]`, ≤ 1 MB / 500 rows, all or nothing, errors `rows[<line>].<field>`, items arrive as drafts, unknown sections are created). POS sync is pointed at Settings → API & integrations.
- Modifier rules: exactly N (min = N when required, else 0), at least N (no max), up to N ("Pick any · up to N"). Nesting = `show_for_option_ids` (options of other groups); deleting a group removes it from items and from other groups' nesting. The design's "Pick 1" (Sweetness) shows as "Pick exactly 1".
- Combos: slots are `{label, qty, sectionId | itemIds}`; the rule text is the labels joined with " + ". Saving = items bought separately at the **cheapest** eligible item per slot − combo price, so it is never overstated; the seed therefore shows Save $7 / $3 / $2 instead of the design's $6 / $3 / $14. A combo must save something (422). Statuses Draft / Live / Scheduled / Paused are set by the owner.
- Northline-funded promos: the two design promos, opt-in stored in `food.kitchen_promos`; owner only (MANAGE) because they change what the kitchen pays.
- Hours screen: prep & throttling selects save on change; fulfilment, opening hours, holiday hours and menu schedules edit in dialogs. "Customer pickup · On · 15–20 min" = default prep − 10 … − 5. Food safety reads `merchants.verifications` (`ahs_permit`, `food_cert`) read-only; renewals link to Stripe & compliance. The seed sets the AHS permit expiry to Mar 2027 (design).
- "Where this menu shows" says "your storefront on northline.ca" instead of the design's hard-coded `northline.ca/pho-dau-bo`.
- Validation messages (the spec has no kitchen section; English in both locales on the server) are the constants in `food.domain.KitchenMessages`, mirrored in `features/kitchen/messages.ts` (en + fr).
- Nav badges (`KitchenNavBadges`): `kds` = New + Cooking food orders → "N cooking" / « N en cuisine »; `menu` = sections of the first menu (live first) → "N sections". Note: the orders module's "N to pack" badge also counts pending food-order lines; the kitchen portal has no Orders screen, so it is not shown.
- Application read models are serialized directly (no separate web DTOs), as the studio dashboard does. Kitchen endpoints are not restricted to `type = kitchen` merchants (others get empty data).
- Routes: `/menus…`, `/menu-items…`, `/modifier-groups…`, `/combos…`, `/kitchen/{live,prep-bump,pause,setup,prep,fulfilment,hours,holiday-hours,promos}`. Onboarding contract: `GET /menus` and `GET /modifier-groups` answer `{items:[…]}` (supersets of `{id, name, sections:[{id, name}]}` / `{id, name}`); `POST /menu-items` answers the created item (`id, name, priceCents, status`, …).

### Frontend
- `src/features/kitchen`: `LiveOrdersScreen`, `MenuBuilderScreen` (+ `MenuItemEditor`, section editor, new-menu and import dialogs), `CombosScreen` (+ `GroupDialog`, `ComboDialog`, add-option dialog; combos use `DataTable`), `HoursScreen` (+ hours, holiday, schedule and fulfilment dialogs). Query keys for menus / modifier groups differ from onboarding's (`…'menus','_list'`, `…'modifier-groups','_full'`) because the two parse different shapes; invalidation shares the prefix.
- Section reorder: drag and drop, or the ⋮⋮ handle with ↑/↓ keys. Deleting items / groups / combos is owner-only and confirmed.
- No new generic UI components were needed.
## 2026-09-29 — Settings & compliance workstream (Settings, Stripe & compliance, team invitations)

### Where the code lives
- **merchants** module: Settings › Business (`BusinessSettingsService`), Team & roles + invitations (`TeamManagementService`), Stripe & compliance (`ComplianceLedgerService`, which now implements `merchants.api.ComplianceStatus` — the operations stub in `OperationsQueries` was removed), the sidebar badge (`ComplianceNavBadges`) and the `ConnectAccountGateway` port (Stripe Connect). Routes: `/api/v1/merchants/{id}/settings/business|team…`, `/api/v1/merchants/{id}/compliance…`, `/api/v1/team-invitations/{token}`.
- New **developer** module (schema `developer`): API keys, webhook endpoints, audit log (`developer.api.AuditTrail`, called in-transaction by merchants and developer for every privileged change). Routes `/api/v1/merchants/{id}/settings/api-keys|webhooks|developer-options|audit-log`.
- **messaging** module: the notification matrix (`/api/v1/merchants/{id}/settings/notifications`). Its package-info files are byte-identical to the messaging workstream's so the merge adds nothing twice.
- **payments.api.TaxSummary** (+ `TaxSummaryQueries`) reads the tax read model. **identity.api.TeamAccounts** (names, contact, `mfa_primary` of team members). **northline-auth** `/api/auth/security` (+ `/passkeys/options`, `/passkeys`) for Settings › Security.
- Application read models (`TeamView`, `ComplianceView`, `InvitationCreated`…) are serialised directly, as the studio dashboard does; only the Business tab has a web DTO (value objects inside).

### Schema additions (V080–V084, additive)
- **V080** `merchants.merchants.cancellation_policy` (`flexible|12h|24h`) and `auto_accept_quote_cents` (≥ 0). The service area stays in onboarding's `profile.serviceArea`, languages in `merchants.languages`. `merchant_members.joined_at`, `invited_by`. New `merchants.member_invitations` (role CHECK as V018, email citext or phone E.164, SHA-256 `token_hash`, 7-day `expires_at`, accepted/revoked; one open invitation per contact per business via partial unique indexes).
- **V081** `merchants.verifications.label` (display name "Liability insurance $2M · Intact"), `verified_at` ("Signed Mar 2026"), `submitted_at`, `submitted_by`. Renewal uploads reuse onboarding's `merchants.documents` (purpose `verification`); `document_media_id` points at the latest. New `merchants.obligation_acceptances (merchant_id, version, accepted_by, accepted_at)`.
- **V082** `payments.tax_jurisdiction_totals (merchant_id, period 'YYYY-Qn', jurisdiction, collected_cents, handling)` — read model written by the Stripe Tax sync (finance/worker; not built here). Jurisdiction codes `ab_gst`, `bc_gst_pst`, `platform_fee_gst`; handling `remitted_by_northline|not_selling|charged_on_invoice`.
- **V083** `developer.api_keys.prefix|created_by|created_at` (+ unique `key_hash`), `developer.webhook_endpoints.secret_enc|created_by|created_at`, `developer.audit_log.merchant_id` (+ index).
- **V084** `messaging.notification_prefs` gets the PRIMARY KEY `(user_id)` V013 documented but never declared (guarded) and `updated_at`.
- Dev seed **V107**: the design's Settings/compliance data for the three personas (Connect account ids, tax for the current quarter, AMVIC / insurance / Red Seal / WCB expired 8 days ago / GST / privacy rows, AHS permit renewing in 20 days for Pho Dau Bo, obligations v2.3 on Mar 14 2026, the two API keys and the webhook, three audit entries). It labels onboarding's V102 rows. Seeded keys' secrets are unknown and the seeded webhook has no signing secret ("Rotate secret" makes one).

### Rules the spec left open
- **Compliance ledger**: rows are `merchants.verifications` except KYC, bank and second factor (shown under Stripe Connect), the kitchen visit and the business-registry row. A row without a checklist key that repeats a keyed row (same type, registry, reference — e.g. catalogue's V104 AMVIC row) is hidden. A verified row past `expires_at` is treated as **expired** on read (no job flips it yet). Due = expired, rejected or todo; "expiring soon" = verified and expiring within 30 days (design "shows here 30 days ahead"), shown with an Upload button but not counted as due. Instant book pauses at expiry + 15 days (the grace the dashboard used; `DueItem.pausesAt` now carries it and the dashboard reads it).
- **Renewals**: the owner uploads a PDF/PNG/JPEG ≤ 10 MB (onboarding's rules and messages); the row becomes `submitted` ("Uploaded · in review"), `verification.renewal_submitted` is published for the console's review queue, and a second upload while one waits → 409 `renewal_pending`. An agent sets the new expiry when verifying (console workstream). Empty file: "Choose a file to upload.".
- **Badge** `compliance`: "{n} due" / « {n} à faire »; kitchens show "AHS" when a food-safety check (AHS permit, handler certificates, inspection) is due or expires within 30 days (design navKitchen), else the count.
- **Headline**: "One item due: {doc}", "{n} items due, starting with {doc}", "Everything is up to date". Row notes (e.g. "Alberta · regulated automotive") come from type/registry; the state texts follow the design ("Verified · renews Jan 2027", "Expired Aug 31 · upload new letter", "Active · Northline remits on your behalf", "Signed Mar 2026").
- "Required for **X · Alberta**": X = the first approved category, regulated ones first, then taxonomy order (logical read of `catalogue.categories`, as onboarding does).
- **Stripe Connect** behind `ConnectAccountGateway`: the `local`/`test` fake = the design's account (Express, charges + payouts on, all verified, annual re-verification due in 12 days, TD ··3391, weekly Friday + instant); other profiles use stripe-java with `northline.stripe.secret-key` (409 `stripe_unavailable` without one). Requirement strings map to the five lines (identity: `individual.*`/`representative.*`/`person_*`; business: `company.*`/`business_profile.*`/`tos_acceptance.*`; bank: `external_account`; owners: `owners.*`/`*owners_provided`/`directors.*`; annual re-verification: `future_requirements`). A Stripe error shows "We couldn't reach Stripe…" instead of failing the page. The account id is masked "acct_1Kx9…Q2". "Open Stripe dashboard" = Express login link (new tab); "Update identity document" = hosted onboarding link (creates the Express account first when none exists; the button reads "Set up payouts with Stripe" then). Both owner-only. The statement descriptor defaults to "NORTHLINE* {NAME}" when Stripe has none.
- Payment settings' escrow model, Radar and CRA lines are platform facts (static copy); the payout schedule links to Payouts.
- **Obligations**: current version `2.3` (constant); the five lines are static copy (kitchens get a food-safety line instead of the on-site safety line). When the accepted version is older, an Accept button (owner) records `obligation_acceptances`; accepting a stale version → 409 `obligations_outdated`. "read" opens `/legal/terms.html#business`.
- **Business tab**: owner-only edits (others see it disabled with "View only · {role}"). The display name goes through `RenameMerchant` (publishes `merchant.renamed`). Changing the legal name or GST number sends onboarding's `registry` / `gst` check back to `submitted` for a re-check. GST spaces are typing aids (onboarding's `GstNumber`). Auto-accept quotes is shown only for provider/both. Languages are ISO codes (en, fr, pa, hi, zh, tl, es, ar, vi, uk; "+ Français" as in the design, the rest in a select). Messages not in validation-rules.md: "At most 200 characters.", "Choose one of the options.", "Enter an amount of $0 or more.", "Pick at least one language.", "Pick languages from the list.".
- **Team**: roles offered per type — kitchens owner/cook/bookkeeper, others owner/technician/bookkeeper. "Can" texts from the design; cook = "Kitchen orders, menu & 86s". 2FA from `identity.users.mfa_primary` (passkey → Passkey, totp → App, sms → SMS only in rosehip, none → None). A business always keeps one owner (409 `last_owner`). Removing a member deletes the membership row; access ends at once because `MerchantAccess` reads membership per request. Messages: "Enter an email or a mobile number.", "Choose a role.", "This role isn't available for this business.", "An invitation is already pending for this contact.", "This person is already on your team." (email/phone formats reuse the registration messages).
- **Invitations**: 32-byte random token, SHA-256 stored, link `northline.studio.base-url` + `/invite/<token>`, valid 7 days. The link is returned to the owner once (Copy link) because **no production email/SMS provider exists**: the `TeamInviteSender` port logs under `local`/`test` and the unconfigured adapter reports "not sent" elsewhere. The invitee signs in (or registers — `/sign-in?next=/invite/<token>`), sees business + role, and accepts; accepting needs `acr=mfa` (403 `mfa_required`) and an account whose email/mobile matches the invitation (409 `invitation_not_for_you`); expired/used/withdrawn → 409 `invitation_expired|invitation_used|invitation_revoked`. New members are bookable only as technicians. The accept page (`/invite/$token`) is not drawn in the design; its copy is ours.
- **Security tab**: talks to northline-auth with the browser's auth-server session. When that session has no second factor (401), the tab asks the person to confirm with their passkey or authenticator code (the JSON sign-in API) and reloads. Rows: passkeys (the first is "Primary" when `mfa_primary = passkey`), authenticator, "Security key (FIDO2)" → Add (WebAuthn registration via `/api/auth/security/passkeys`), backup codes left + "New codes" (`POST /api/auth/backup-codes`, shown once), payout re-auth + 24 h hold and login alerts (platform policies, always On), active sessions (the last 10 `identity.sessions` rows, device from the user agent) with a Review drawer, audit log (owner only, last 90 days, DataTable). ~~Session revocation, removing passkeys~~ (S-19, below) and partner client credentials (`partner:*`, private_key_jwt) are **not built**. The banner keeps the design's "Seller accounts…" copy.
- **Notifications**: per user (V013's table has no merchant column), edited by every member for themselves. Defaults = the design's ✓/— matrix; cells toggle and save at once (optimistic). Rows per portal: quote requests for provider/both, low stock for seller/both. Quiet hours 21:00–07:00 are stored and shown, not editable (the design shows only the sentence).
- **API keys**: `nl_live_` + 24 random bytes (base64url), SHA-256 stored, the first 12 characters kept as `prefix`, 600 req/min default, scopes from a fixed list (`storefront:read, listings:read, listings:write, booking:read, booking:write, orders:read, payouts:read, reviews:read`). Revoked keys leave the list and publish `developer.api_key_revoked`. Messages: "Name the key so you can tell it apart.", "At most 60 characters.", "Pick at least one scope.", "Pick scopes from the list.".
- **Webhooks**: https only (http://localhost allowed for development), events from a fixed list (`booking.confirmed, booking.completed, order.placed, order.delivered, payment.released, refund.issued, review.created`), HMAC-SHA256 signing secret `whsec_…` shown once, stored AES-256-GCM encrypted with `northline.developer.webhook-key` (fixed dev key under `local`/`test`; other profiles without a key → 409 `webhooks_unavailable`). Create / rotate / delete publish `developer.webhook_endpoint_changed` for the worker. Messages: "Enter the URL that receives events.", "Enter an https:// URL.", "Pick at least one event.", "Pick events from the list.". Delivery itself (worker) is not built.
- **Integrations** rows link to the owning screens: Google Calendar (availability sync status, provider/both), QuickBooks = an API key named "QuickBooks…" (Connect opens Issue key pre-filled with `payouts:read orders:read`), Shopify / Square inventory (catalogue integrations, not for kitchens). The embed snippet uses the storefront slug; publishable keys are not modelled, so it stays `pk_live_…` as in the design.
- The Studio header summary (`GET /api/v1/merchants/{id}`) now also returns the caller's `role` and `teamCount` (the shell's `useRole()` and "Team · N members" read them; before, every user looked like an owner in the UI).
- Kafka topics added to `scripts/topics.sh`: `merchants.member`, `merchants.verification`, `developer.api_key`, `developer.webhook_endpoint`.
- 2026-09-29 · Escrow release is now fed by the Studio's fulfilment events: `payments` listens to `BookingCompleted` (48 h clock); `food` listens to its own `FoodOrderHandedOff` and calls `EscrowLifecycle.fulfilledIfHeld` for each of the kitchen's order lines (the reverse direction created a food → merchants → payments → food module cycle). Work without a held escrow is skipped. Goods delivery still has no event (fulfilment/courier app is out of Studio scope) — `EscrowLifecycle.fulfilled/confirmed` must be called from there.
- 2026-09-29 · Integration smoke (`scripts/studio-smoke.mjs`): 135 screen renders (3 businesses × desktop/phone × en/fr) against a DB with every migration + dev seed. Fixed: French inline table actions overflowing (DataTable reserves 170 px and wraps the label), duplicate keys in the availability preview service picker (keyed by service id, not duration), missing favicon, dispute customer name on the dashboard (falls back to `payments.disputes.customer_name`). Known and expected under dev auth: Settings › Security needs the auth server running (shows its error state + Retry); seeded kitchen items reference photos with no stored bytes (placeholder tile).

## 2026-09-29 — S-1 profiles & runbooks (environments, configuration, local stand-ins)

- **Configuration = environment variables with local defaults.** Every connection, credential and public URL in api, auth, bff and worker is `${VAR:local-default}` in `application.yml`; the full list with comments is `server/.env.example`, the matrix is `docs/runbooks/README.md`. No committed yml holds a real secret (defaults are local-only values such as `northline/northline`, `{noop}dev-studio-bff`). New variables: `DB_POOL_SIZE`, `REDIS_PORT/USERNAME/PASSWORD/SSL`, `KAFKA_SECURITY_PROTOCOL/SASL_MECHANISM/SASL_JAAS_CONFIG`, `ES_USERNAME/PASSWORD`, `COOKIE_DOMAIN`, `STRIPE_SECRET_KEY/PUBLISHABLE_KEY/API_BASE`, `WEBHOOK_SECRET_KEY`, `SERVER_PORT`, `OTEL_EXPORT_ENABLED`, and the provider variables below. The worker got its first `application.yml`.
- **Renamed:** northline-auth's BFF client secrets are now `STUDIO_BFF_SECRET_HASH`, `CONSUMER_BFF_SECRET_HASH`, `CONSOLE_BFF_SECRET_HASH` (the *encoded* value, `{bcrypt}…`), while the bff keeps `STUDIO_BFF_SECRET` (plain). One name for two formats would have stored the plain secret as an unprefixed hash. The three secrets must differ: Spring Authorization Server rejects duplicate client secrets at start-up.
- **Auth defaults** for origins/RP id changed from the production hosts to localhost (`STUDIO_ORIGIN` default `http://localhost:3100`, `WEBAUTHN_RP_ID` default `localhost`, …): production hosts now come only from the environment, and the cloud profiles require them. The `local` and `test` files still set their own values, so their behaviour is unchanged.
- **Profiles.** `local` and `test` are unchanged. New `dev`, `staging`, `prod` (per app) each activate the group profile `cloud` (`spring.profiles.group` in `application.yml`), whose `application-cloud.yml` carries the shared shape: required variables, Kubernetes health probes, `ca.northline` at info, OTLP export behind `OTEL_EXPORT_ENABLED` (default off until S-111). `dev` logs at debug; `staging` and `prod` also require `STRIPE_SECRET_KEY` + `STRIPE_PUBLISHABLE_KEY` (so a deployed environment never silently runs the fake Stripe gateway); `prod` turns springdoc's OpenAPI and Swagger UI off. Fakes stay keyed on `local`/`test` as before, so the cloud profiles get the unconfigured adapters.
- **Fail fast on missing variables:** new shared module `server/platform` (plain library, package `ca.northline.platform`, used by all four apps). `RequiredEnvironmentCheck` is an `ApplicationContextInitializer` (registered in `META-INF/spring.factories`) that reads `northline.required-env.<purpose>: VAR, VAR…` — a map, so profile files can add purposes — and fails before any bean is created, listing **every** missing variable by purpose; `MissingEnvironmentFailureAnalyzer` prints it as Spring Boot's "APPLICATION FAILED TO START" report pointing at `docs/runbooks/<profile>.md`. Chosen over `${VAR}` placeholders without defaults, which fail one variable at a time deep inside bean creation.
- **Provider dimension:** `northline.storage.provider` (`local|s3|gcs|azure`, S-10), `northline.kms.provider` (`local|aws|gcp|azure`, S-7), `northline.email.provider` (`local|smtp|ses|sendgrid|azure`, S-13), `northline.sms.provider` (`local|twilio|sns|azure`, S-8) are `@ConfigurationProperties` records in `ca.northline.platform` (with bucket/region/endpoint/credentials, key id, SMTP settings, SMS account), default `local`, bound by `PlatformAutoConfiguration`, which logs `Providers: storage=… kms=… email=… sms=…` at start-up. Nothing reads them yet: the adapter stories replace today's `@Profile("local","test")` switches with these properties, so changing cloud stays a configuration change. No `secrets.provider`: secrets arrive as environment variables on every cloud (External Secrets, S-6).
- **Unconfigured merchants adapters:** `IdentityVerification`, `RegistryLookup`, `BankLinking`, `DomainVerifier` and `DocumentStorage` had only local/test fakes, so the api could not start under any other profile ("a production profile needs real adapters before it can start"). `merchants.integration.UnconfiguredMerchantIntegrations` now provides fail-loudly placeholders outside `local`/`test`, like the other modules.
- **Stripe API base:** `northline.payments.stripe-api-base` / `northline.stripe.api-base` (`STRIPE_API_BASE`) point stripe-java at stripe-mock locally; blank = Stripe.
- **Spring Session namespace fix:** `spring.session.redis.namespace` is ignored by Boot 4 (renamed `spring.session.data.redis.namespace`), so auth and bff sessions shared the default `spring:session` prefix. Both now use the Boot 4 name (`nl:auth`, `nl:studio-bff`).
- **`valkey` add-on profile** (auth, bff): `--spring.profiles.active=local,valkey` keeps sessions in your Valkey/Redis instead of memory (it re-enables the Redis session auto-configuration that `local` excludes).
- **`.env` for local runs:** each app imports `optional:file:.env[.properties]` and `../.env` (so `server/.env` works for bootRun and for jars started from `server/`), in a document activated on `!test` so tests never see a developer's file; real environment variables win. The Gradle DB tasks fall back to `DB_URL`/`DB_USER`/`DB_PASSWORD` from the environment, then `server/.env`. The api's test profile forces blank Stripe keys so an exported `STRIPE_SECRET_KEY` never reaches Stripe from tests. Root `.gitignore` ignores `.env` and `.env.*` except `.env.example`.
- **Docker Compose profiles:** every service is behind a profile (`db`, `cache`, `events`, `search`, `mail`, `storage`, `payments`, `all`; `tools` for Kafka UI and Kibana), so the owner's own Postgres/Valkey can be used and only missing stand-ins are started; `COMPOSE_PROFILES` in the root `.env` sets the default set. Redis → `valkey/valkey:8-alpine`. Kafka gets a second listener (`INTERNAL://kafka:29092`) so containers and the host both work with a configurable host port, and a one-shot `kafka-topics` service runs `scripts/topics.sh` (now parameterised by `KAFKA_TOPICS_CMD`, `KAFKA_TOPICS_BOOTSTRAP`, `KAFKA_REPLICATION_FACTOR`). Elasticsearch and Kibana come from Docker Hub's official images (same 9.1.3 builds). **Object storage stand-in is RustFS** (`rustfs/rustfs:1.0.0`, S3 API on :9100, console :9101; a one-shot `aws-cli` container creates the bucket): MinIO no longer publishes images on Docker Hub (`minio/minio` is gone), and :9000 belongs to northline-auth. Kafka UI moved to :8190 (the smoke script uses :8090 for the api).
- **Runbooks** `docs/runbooks/{README,local,dev,staging,prod}.md`: the cloud runbooks map every need to AWS / Google Cloud / Azure services in Canadian regions, list every variable (app, required, example, source), third-party accounts, residency, the blockers (adapters with only local fakes and their stories), deploy/migrate/rollback at today's level and a readiness checklist. The three cloud runbooks share one structure and one blockers table; change them together.
- **Found while validating, not fixed here:** under `prod` the `ClientSeeder` is off and nothing else registers the OAuth clients (no backlog story yet); northline-auth generates its signing key at start-up, so it must run as exactly one replica until S-7; registration can't work outside `local` until an SMS provider exists (S-8).

## 2026-09-29 — S-7 persistent token signing keys (server/auth `ca.northline.auth.signing`)

- **Port:** `SigningKeys` — `active()` (a `SigningKey`: `kid` + `sign(signingInput)` returning the JWS `R || S`) and `published()` (public JWKs). `KeyStoreJwtEncoder` is the app's `JwtEncoder` bean: Spring Authorization Server picks it up for access and ID tokens, and `JwtStepUpProofs` now signs step-up proofs with it (before, it built its own `NimbusJwtEncoder` over the in-memory key, so proofs died with a restart too). The `JWKSource` bean publishes public keys only and is read on every call, so `/oauth2/jwks` and the auth server's own decoder follow rotations without a restart. Only ES256.
- **kid = RFC 7638 thumbprint** of the public key for every provider: replicas, the rotation command and the cloud adapters agree on it without sharing state.
- **Provider = `northline.kms.provider` (S-1 property), nothing else.** `local` (default) = one JWK set file (`signing-keys.jwks.json`, mode 600, atomic writes under a lock file) in `SIGNING_KEYS_DIR` (default `~/.northline/auth-signing-keys`, tests: a temp dir), created with a first key on first start. A file rather than the database: no migration, the same mechanism works for a dev volume, and the DB would put private keys in every backup. `aws` | `gcp` | `azure` = `RemoteSigningKeys` over a small `KeyService` (public key + sign a SHA-256 digest) implemented with the official SDKs — AWS KMS (`ECC_NIST_P256`, `ECDSA_SHA_256`, DER → `R||S`), Google Cloud KMS (`EC_SIGN_P256_SHA256`, key *version* name), Azure Key Vault (`ES256`, versioned key URL, answers `R||S` already). The private key never leaves the KMS; public keys are fetched once at start-up (wrong key type or permission = start-up failure). Only the selected provider's client bean is created (the SDKs are always on the classpath; Azure uses the JDK HTTP client instead of Netty, AWS the default sync client).
- **Rotation, local:** time-based, so replicas need no coordination: `rotate` adds a key whose `nbf` is `publishAhead` (10 min, > the 5-min JWK set caches of api/bff) away and gives the current key `exp = nbf + retireAfter` (1 h, > the 30-min ID token). Active = the usable key with the latest `nbf`; published = every key not past `exp`. Servers re-read the file when it changes (and at least every 30 s). Triggers: `./gradlew :auth:signingKeys --args='status|rotate [--immediately]|retire <kid>'` (`SigningKeysCommand`, also runnable from the boot jar with `PropertiesLauncher`) and an optional hourly job (`SIGNING_KEYS_ROTATE_EVERY`, e.g. `90d`, checked under the file lock). No HTTP endpoint: the auth server's security chain would need a management port + auth for it, a file edit needs neither.
- **Rotation, cloud:** new key/version, then configuration: `KMS_PUBLISHED_KEY_IDS=<new>` (publish, wait ≥ 5 min) → `KMS_KEY_ID=<new>`, `KMS_PUBLISHED_KEY_IDS=<old>` → after ≥ 1 h `KMS_PUBLISHED_KEY_IDS=` — each a plain rolling deploy that only adds before it switches. Aliases/"latest version" ids are discouraged (they switch replicas at random moments). Runbook: `docs/runbooks/key-rotation.md` (+ a short section in dev/staging/prod.md).
- **Fail fast:** `application-cloud.yml` requires `KMS_PROVIDER` (purpose `signing-provider`); `application-staging.yml` / `application-prod.yml` also require `KMS_KEY_ID` (`signing-key`; a separate purpose because the `cloud` group profile is applied after `prod` and would override a shared key) and `KMS_PROVIDER=local` is refused there. `dev` may use `local` with a volume shared by its replicas (the local rehearsal in local.md § 7 does).
- New variables (auth only): `KMS_PUBLISHED_KEY_IDS`, `KMS_REGION`, `KMS_ENDPOINT` (AWS; LocalStack), `SIGNING_KEYS_DIR`, `SIGNING_KEYS_ROTATE_EVERY`. `KMS_*` moved from "api, auth" to "auth" in the runbooks (the api never read them).
- **Consequences:** northline-auth can now run several replicas and restart without signing anyone out (the S-1 "exactly one replica" blocker is gone from the runbooks). Refresh tokens stay opaque (DB), so even losing the key only costs access tokens in flight.
- Tests: `LocalFileSigningKeysTest` (persistence, 600 permissions, publish-ahead/overlap/retire with a moving clock, change by another instance, compromise path, job), `CloudKeyServicesTest` (SDK mocks that sign with a real key: DER/raw handling, wrong key types), `AwsKmsLocalStackTest` (the real KMS API in LocalStack `4.4` via Testcontainers, pinned: it runs without a LocalStack account), `SigningKeysConfigTest` (provider selection, job switch, `local` refused under prod/staging, missing key id), `SigningKeysStartupTest` (prod/staging stop with the missing KMS variables), `SigningKeysRestartTest` (three full app contexts sharing a key directory: cross-instance verification, restart, rotation published by all, a foreign key store rejected), `TokenClaimsTest` (kid published in `/oauth2/jwks`, no private parts).
- Not done: GCP and Azure adapters are tested against mocks only (no emulator implements asymmetric signing); CRC32C integrity checks of Cloud KMS responses are not verified; signing latency is one KMS round trip per token (no caching possible — each token is different).

## 2026-09-29 — S-9 rate limits per account, IP and session (server/auth, Studio sign-in / register)

- **Where:** application service `AttemptLimits` (inbound from `RegistrationService`, `SignInService`, `StepUpService`) over the port `RateLimiter`; adapters in `ca.northline.auth.ratelimit`. Limited actions (`LimitedAction`): `otp-send` (register form, resend, voice — every call), `otp-verify`, `sign-in-lookup` (every call), `totp-verify`, `backup-code-verify`, `passkey-assertion`, `step-up` (failures only). Scopes (`LimitScope`): account, IP, auth session. The existing per-flow rules stay (5 tries per code, 45 s cool-down, 5 failed factors per sign-in attempt / step-up session) — the new limits close the "start a new attempt / new session / other IP" gaps.
- **Algorithm:** sliding-window log per subject (a Valkey sorted set) and a lockout key; the attempt that reaches the threshold locks for `lockout × 2^(n-1)` (capped by `max-lockout`, strikes remembered 24 h). One Lua script checks and records all scopes of a call atomically, using the server's `TIME` (instances with skewed clocks agree); keys share an `{action}` hash tag (one slot even on a cluster) and all have TTLs. Chosen over bucket4j: the lockout + exponential backoff semantics are the requirement, and a 40-line script is simpler than a token bucket library plus a lockout layer. A success resets the account and session counters of that action, never the IP's (one good account must not unlock an IP). A request that is refused is not counted.
- **Account scope, without leaking existence:** a known account counts by its user id (email and mobile share the budget), an unknown identifier by its normalised value (lower-case email / E.164 mobile), with the same numbers and the same 429 — nothing distinguishes them. The sign-in lookup has **no** account scope (typing someone's email must never lock them out); registration sends count per mobile, and every "Send code" counts, including the ones answered "already in use" (the answer is itself a hint).
- **Defaults** (`application.yml`, all overridable by property / env var): otp-send 5/h account · 20/h IP · 5/h session, lockout 1 h; otp-verify 10/h · 50/h · 10/h, 15 min; sign-in-lookup — · 30/10 min · 20/10 min, 10 min; totp/backup/passkey 10/15 min · 30/15 min · 10/15 min, 15 min; step-up the same. The account/session numbers (10) sit above the per-attempt 5 so one attempt still ends with `too_many_attempts` as before; two restarted attempts reach the new limit.
- **Answer:** `FlowRejected.Reason.RATE_LIMITED` → `429`, `Retry-After`, ProblemDetail `code: rate_limited`, `retryAfterSeconds`, detail "Too many attempts. Wait a moment and try again." (server copy, en). Studio (`features/auth/rateLimit.tsx`): "Too many attempts. Try again in {m:ss}." / « Trop de tentatives. Réessayez dans {m:ss}. », counting down, buttons disabled until 0, on the sign-in identifier and factor steps, the register form, the phone-code step (verify, resend, call me). A federated sign-in refused by the limits lands on `/sign-in?error=rate_limited` ("Too many attempts. Wait a moment and try again." / « Trop de tentatives. Patientez un moment, puis réessayez. »). Finance's step-up dialog already treats any 429 as locked.
- **Audit:** every new lockout writes `developer.audit_log` `auth.rate_limited` (`{action, scopes, seconds, ip}`, actor = the user when the account is known), in its own transaction, plus a WARN log line.
- **Store:** `northline.auth.rate-limits.store` (`RATE_LIMIT_STORE`): `redis` (default, the cloud profiles, `local,valkey`) or `memory` (`local`, `test`; same algorithm per JVM, a WARN at start-up says so). `memory` is refused under staging/prod. Valkey errors fail **open** (logged): the per-flow rules still hold and the sessions, also in Valkey, are down anyway.
- **Client IP:** Tomcat's `RemoteIpValve` trusted every private address and had no CIDR setting, so `server.forward-headers-strategy` is now `none` and `TrustedProxyFilter` (first servlet filter) believes `X-Forwarded-For/-Proto/-Host` only from `northline.auth.trusted-proxies` (`TRUSTED_PROXIES`, CIDRs; default loopback + private ranges, to be narrowed to the ingress subnet in the cloud). The client is the right-most address that isn't a trusted proxy; only IP literals are accepted (no DNS from a header); from anyone else the headers are stripped. The sign-in log (`identity.sessions.ip`) uses the same address.
- **Tests:** `RateLimiterContractTest` (the same cases against Valkey `valkey/valkey:8` in Testcontainers and the in-memory limiter: window, lockout, backoff and cap, reset, several scopes), `RateLimitApiTest` (Valkey; lowered limits: per mobile across sessions and IPs, per IP, resend + voice, wrong codes across sessions with a new code, lookups per IP and per session, TOTP across sessions via email and mobile with doubled second lockout and audit rows, reset on success, unknown accounts indistinguishable, backup codes, passkeys per session, step-up across two signed-in sessions, X-Forwarded-For from trusted vs untrusted peers and multi-hop), `TrustedProxyFilterTest`, `RateLimitConfigTest` (store selection, WARN, refused under prod, env-var overrides); Studio `auth.test.tsx` (countdown, disabled buttons, en + fr, federation error). The suite's `test` profile keeps memory limits with the IP scope effectively off (all MockMvc calls come from 127.0.0.1).

## 2026-09-29 — S-2 Terraform modules (one interface, AWS / Google Cloud / Azure)

- **Layout:** `infra/terraform/modules/<capability>/{aws,gcp,azure}` (network, kubernetes, kms, registry, dns, storage, secrets), `stacks/<cloud>` composing them once, `envs/<cloud>/{dev,staging,prod}` roots that only pick sizes, `bootstrap/<cloud>` for the state bucket. The stack layer keeps nine env roots from repeating the same wiring.
- **Contract = identical `variables.tf` and output names** across the three implementations of a capability, checked by `scripts/check-contract.sh` (only the region list in the shared `context` validation differs). Cloud-specific extras go out through a `cloud` output object. Principals are `{ label => principal }` maps with static labels so `for_each` keys are known at plan time; customer-managed keys are passed as `kms_key = { id = … }` for the same reason (a found bug: `count = var.kms_key_id == null` fails to plan when the key is created in the same run).
- **Residency:** every module and env root validates the region against that cloud's Canadian regions (AWS `ca-central-1`/`ca-west-1`, Google Cloud `northamerica-northeast1`/`2`, Azure `canadacentral`/`canadaeast`); Secret Manager uses user-managed replication in the region only (automatic replication is global). Tags/labels on everything: `app`, `env`, `owner`, `data-residency=ca`, `managed-by=terraform`.
- **Keys:** two per environment: `data` (symmetric / RSA wrap, envelope encryption of Kubernetes Secrets, buckets, registry, secrets) and `signing` (EC P-256, `KMS_KEY_ID` for S-7; HSM-backed in prod on Google Cloud and Azure). Workload identities `api`, `auth` get sign/verify on `signing`.
- **Workload identity everywhere, no static cloud keys:** IRSA (EKS), GKE Workload Identity, Azure Workload Identity for `northline-api/-auth/-bff/-worker` in `northline-<env>` and `external-secrets/external-secrets`; outputs carry the ServiceAccount annotations and pod labels for the Helm charts (S-14).
- **App secrets are created empty** (Secrets Manager, Secret Manager) so Terraform never holds their values; Key Vault cannot hold an empty secret, so on Azure only the vault and its RBAC are created and the operator sets the named secrets.
- **Remote state:** backend blocks are committed commented out (so CI and first-time users can `init -backend=false`), with `backend.hcl.example`; AWS uses S3-native locking (`use_lockfile`) with the DynamoDB table still created for the classic setup.
- **Offline verification:** `terraform test` in each env root plans against mocked providers (plus a non-Canadian-region rejection test). It caught two real bugs before any cloud existed: the unknown-at-plan `count` above, and AKS picking `apps` as the system pool (sorted keys) instead of `system`.
- **Lock files** are not committed yet: they were generated from an offline provider mirror (one platform's hashes); the first online `init` runs `terraform providers lock` for linux/darwin/windows and commits them.

## 2026-09-29 — S-10 object storage adapters (S3-compatible, Google Cloud Storage, Azure Blob)

- **One abstraction, module ports unchanged.** `ca.northline.shared.storage.ObjectStore` (named interface `storage` of the shared kernel): `put` (returns key, content type, size) · `get` · `info` · `exists` · `delete` (idempotent) · `presignGet(key, ttl ≤ 1 h)` · `within(prefix)`. A missing object is an empty `Optional`. Adapters: `S3ObjectStore` (AWS SDK v2, sync Apache client; AWS S3 and any S3-compatible server via `STORAGE_ENDPOINT` + path style; with an endpoint the SDK sends checksums only when required, which not every S3-compatible server supports), `GcsObjectStore` (google-cloud-storage, JSON API; an endpoint means emulator + no credentials), `AzureBlobObjectStore` (azure-storage-blob on the JDK HTTP client, Netty excluded like S-7; shared key only for Azurite, else `DefaultAzureCredential`), `FileSystemObjectStore` (the `local` reference implementation, content type in a sidecar). The modules' ports (`DocumentStorage`, `AttachmentStorage`, `KitchenPhotoStore`, `MediaStorage`, `MediaStore`, `DisputeEvidenceStorage`) keep their shapes; each module got a thin `ObjectStore…` adapter over `store.within("<module>")`. No generic port replaced them: the modules' contracts differ (key chosen by the service vs by the adapter, content type returned or not) and they stay swappable one by one.
- **Selection = `northline.storage.provider` only** (S-1 property; plus a new `encryption-key` / `STORAGE_ENCRYPTION_KEY`). `@UsesLocalStorage` / `@UsesObjectStorage` (a condition binding the property like `StorageProperties` does, so `S3`/`s3` both work) replace the `@Profile("local","test")` vs `@Profile("!local & !test")` switch for storage beans: `local` + `local`/`test` profiles = the existing disk/in-memory fakes, **unchanged** (including `LocalKitchenPhotoStore`'s bundled seed photos); `local` + `dev` = the S-1 fail-loudly placeholders (booking: 409 `storage_unavailable`); `s3|gcs|azure` under any profile = the object-store adapters (so a laptop can run the real adapter against RustFS). Only the selected provider's SDK client is created. Staging/prod: `northline.required-env.storage: STORAGE_PROVIDER, STORAGE_BUCKET` (own purpose, like S-7's `signing-key`) and `STORAGE_PROVIDER=local` stops start-up ("… is not allowed under staging/prod"). Missing bucket, or Azure without the account URL, also fail start-up.
- **Keys:** `<module>/<merchantId>/<ulid>[.<ext>]` (extension from the content type: jpg, png, webp, heic, heif, svg, pdf). The database stores the key relative to the module prefix, so the stored keys survive a provider switch. Services now build `ObjectKeys.merchantObject(merchantId, id, contentType)` instead of `listings/<m>/<id>.<ext>`, `menu-items/<m>/<item>/<id>`, `messages/<m>/<id>`, `disputes/<disputeId>/<id>` (dispute evidence had no merchant in its key); merchants and booking build the same key in their adapters. Old keys keep working under `local` (they only exist on developer disks). Keys are validated (`[A-Za-z0-9._-]` segments, no `..`, ≤ 512 chars) before any provider call.
- **Encryption at rest:** provider default when `STORAGE_ENCRYPTION_KEY` is empty — S3 has encrypted every object with SSE-S3 since 2023, so no header is sent; GCS and Azure encrypt with provider-managed keys. With a key: SSE-KMS + key id (S3), per-object `kmsKeyName` (GCS CMEK), encryption scope (Azure — the Blob service's mechanism for a customer-managed key per request). One variable, interpreted per provider, rather than three provider-specific ones.
- **Downloads stay streamed through the api.** Every download endpoint keeps its `@RequiresMerchant` check (or, for storefront logos, the published-storefront check) and returns the bytes with the same headers as before. A redirect to a presigned URL would work for `<img>` but not for the Studio's `fetch` calls without bucket CORS, and it would move `Content-Disposition`/`nosniff` handling into the provider. `presignGet` exists and is contract-tested (≤ 1 h TTL, GCS V4 via IAM `signBlob`, Azure service SAS with a shared key or user-delegation SAS with Entra ID) for the consumer app / CDN later. **No presigned uploads:** a direct-to-bucket upload would skip the MIME, signature, size, dimension and virus checks, so it isn't offered; hence no CORS rules in the runbooks.
- **Virus-scan hook:** `VirusScanner` (`scan(key, contentType, bytes)` → sealed `Verdict` `Clean` | `Infected(threat)`), default `VirusScanner.NONE`. Every `ObjectStore` bean is a `GuardedObjectStore` that validates keys and TTLs and calls the scanner before `put`; a declared `VirusScanner` bean replaces the default (logged at start-up). Infected → 422 `{"field":"file","rule":"virus","message":"This file can't be accepted. Try a different file."}` (our copy; validation-rules.md has none), WARN log with the threat name, nothing stored. A scanner exception fails the upload (fail closed). The `local` disk fakes do not call it.
- **Content type and size** are stored as object metadata (and, as before, in each module's table). The whole file is held in memory, as the ports already did (all uploads are ≤ 15 MB).
- **Woodstox:** google-cloud-storage brings Woodstox, which then becomes the default StAX provider and rejects `XMLConstants.ACCESS_EXTERNAL_DTD`; `SpreadsheetFiles` (catalogue bulk upload) now uses `XMLInputFactory.newDefaultFactory()` (the JDK parser) with the same hardening.
- **Least privilege:** S3 needs `s3:GetObject/PutObject/DeleteObject` on `bucket/*` plus `s3:ListBucket` on the bucket (without it S3 answers 403 instead of 404 for a missing key, which would turn "file gone" into a 500); GCS `roles/storage.objectUser` on the bucket (overwrites need `storage.objects.delete`, so `objectCreator` + `objectViewer` is not enough); Azure "Storage Blob Data Contributor" on the container. Presigned URLs need `iam.serviceAccounts.signBlob` (GCS) / "Storage Blob Delegator" (Azure), not granted until something uses them. Details per cloud, lifecycle (versioning/soft delete for recovery, no expiry of live objects until S-107) and local use of RustFS: `docs/runbooks/object-storage.md`.
- **Tests:** `ObjectStoreContract` runs the same cases against all four implementations — `FileSystemObjectStoreTest`, `S3ObjectStoreTest` (RustFS `rustfs/rustfs:1.0.0`, the compose image, endpoint + path style), `GcsObjectStoreTest` (`fsouza/fake-gcs-server:1.52.2`, `-public-host` so signed URLs route; signed with a throw-away service-account key), `AzureBlobObjectStoreTest` (Azurite `3.35.0`, `--skipApiVersionCheck`; the endpoint uses `127.0.0.1` because the SDK only reads the account name from the path for IP hosts): round trip with type and size, missing objects, overwrite, delete idempotence, empty objects, prefixes, presigned GET fetched over HTTP, TTL bounds, invalid keys, infected uploads. `ObjectStorageConfigurationTest` (provider × profile selection, `local` refused under staging/prod, missing settings, scanner bean) and `ObjectKeysTest`. `DocumentsInObjectStorageTest` runs the whole api with `STORAGE_PROVIDER=s3` against RustFS: an onboarding document and a message attachment uploaded through their endpoints land at `merchants/<m>/<id>.pdf` / `messaging/<m>/<id>.png` with their content type, read back through the endpoints (403 for a non-member), and an EICAR upload is rejected with 422 and not stored.
- **Not done:** no real scanner (ClamAV / GuardDuty Malware Protection / Defender for Storage) is wired — only the hook; no endpoint hands out presigned URLs; buckets are created by hand until Terraform (S-2); GCS CMEK and Azure encryption scopes are passed through but not exercised by the emulators; `GET …/media/{mediaId}` (catalogue) looks the media up by id without the merchant (unchanged — shared catalogue images are read across merchants; worth a look in the catalogue workstream).

## 2026-09-29 — S-3 Managed data stores (PostgreSQL 17 + PostGIS, Valkey/Redis, Kafka, Elasticsearch)

- **Four more capabilities under the S-2 contract:** `modules/{postgres,cache,kafka,search}/{aws,gcp,azure}`, identical variables and output names per capability (`scripts/check-contract.sh`), wired once per cloud in `stacks/<cloud>` and sized per environment by a `data_stores` block in `envs/<cloud>/<env>/main.tf`. Outputs go straight into `config_env` / `secret_env` under the apps' variable names (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_*`, `KAFKA_*`, `ES_*`); `data_stores` carries the operator details (admin secret, topic policy, CA certificates).
- **Generated credentials live in the environment's secrets store** (`secret_store = module.secrets.store`, same prefix as the app secrets), so External Secrets reads them with the existing prefix-scoped grant. They do pass through Terraform state — accepted, because state is only in the bootstrapped encrypted bucket. The app role (`northline_app`) is created by a one-off bootstrap SQL from a pod (runbook § 5.1): no provider can reach the private databases from the operator's machine.
- **PostgreSQL:** RDS (isolated subnets, `rds.force_ssl`, RDS-managed master secret), Cloud SQL Enterprise (private IP via Private Service Access, `ENCRYPTED_ONLY`, backups pinned to the region), Flexible Server (delegated subnet + private DNS, `azure.extensions` allow-list, geo-redundant backup only in prod since the paired region is Canadian). Customer-managed keys on AWS and Google Cloud; Azure uses service-managed keys for now (CMK needs a user-assigned identity and a key in the paired region).
- **Valkey/Redis:** cluster mode off everywhere (the apps have no cluster client). Azure uses **Azure Managed Redis** (`EnterpriseCluster` single endpoint, port 10000) rather than Azure Cache for Redis, which is being retired. Memorystore for **Valkey** has no static password (IAM or none): the module uses none + PSC, and trusting its per-instance CA is left to the Helm charts (S-14) — an open item for Google Cloud.
- **Kafka:** MSK 3.9 (KRaft) with SASL/SCRAM-SHA-512 and `auto.create.topics.enable=false`; Google Managed Kafka with SASL/PLAIN and a service-account key; **Event Hubs Premium** (Standard caps at 10 event hubs; Northline has ~50 with `.dlq`). The apps get the complete JAAS line as one secret (`KAFKA_SASL_JAAS_CONFIG`). IAM/OAUTHBEARER variants would remove static credentials but need client libraries the apps don't have. Topics are always created explicitly (S-25); on Event Hubs only a separate Manage-right credential may create them.
- **Elasticsearch: Elastic Cloud via `elastic/ec` on all three clouds** (`modules/search/elastic-cloud` shared, each cloud maps its Canadian region and writes the password to its secrets store). Amazon OpenSearch Service is rejected: the apps use the Elasticsearch 9 Java client, which refuses OpenSearch (product check) and the APIs have diverged. Traffic filter = the cluster's NAT egress IPs; private links are later work. The `elastic` superuser is used until a least-privilege user exists.
- **Offline verification extended:** each env root's `terraform test` mocks the `ec` provider too and asserts the new `config_env` / `secret_env` keys.

## 2026-09-29 — S-122 OAuth client registration outside local (server/auth `ca.northline.auth.clients`)

- **Clients are configuration**, `northline.oauth.clients.<client-id>` (`ClientSpec` record: `type` confidential|public, `optional`, `name`, `secret-hash`, `redirect-uris`, `post-logout-redirect-uris`, `scopes`, `grant-types`, `require-pkce`, `require-consent`, `access-token-ttl`, `refresh-token-ttl`, `dpop-required`), replacing the hard-coded `ClientSeeder` (which ran everywhere except `prod`) and `northline.clients.{studio,consumer,console}`. Defaults keep the old registrations exactly: authorization code + refresh, PKCE, no consent, access 10 min, rotating refresh 12 h, ES256 ID tokens, `client_secret_basic` for confidential and `none` for public clients.
- **Reconcile, never delete:** `OAuthClientSync` creates missing clients and updates differing ones in place (same internal id and issue date); the comparison is field by field (secret, name, auth methods, grant types, URIs, scopes, client/token settings), so an update names the fields it changed but never their values. A client stored but no longer configured is logged (`stored but not in configuration (left as is)`) — deleting a client revokes its tokens, so it stays a deliberate manual step. One transaction under `pg_advisory_xact_lock`, so replicas starting together and the Job don't race on the insert.
- **Two triggers, one mechanism:** at every start of northline-auth in every profile (`northline.oauth.sync-on-startup`, `OAUTH_CLIENTS_SYNC_ON_STARTUP`, default true — the simplest thing that makes a fresh environment work) and `OAuthClientsCommand` (`./gradlew :auth:oauthClients --args='list|sync'`, or `PropertiesLauncher` from the boot jar as a Kubernetes Job). The command starts a data-source-only context (no web server, Valkey or KMS) but reads the server's full configuration, required-variable check included, so the Job gets the auth Deployment's environment. Chosen over Flyway-managed registration: secrets can't live in migrations, and a Flyway callback would run in the api, which doesn't own these tables' configuration.
- **Secrets only as hashes** (`*_BFF_SECRET_HASH`, S-1 convention): `secret-hash` must be an encoded value (`{bcrypt}…`); a plain secret is refused everywhere, `{noop}` is refused under staging/prod and warned about under dev (the local cloud rehearsal uses it). Rotation = new hash in the secrets manager + Job/restart; no code change. Spring Authorization Server stores one secret per client, so there is no overlap: the BFF is restarted with the new plain secret right after (documented).
- **Re-encoded secrets aren't changes:** Spring Authorization Server re-encodes a client secret on its first successful use when the encoder asks for an upgrade (`{noop}` → `{bcrypt}`, or a bcrypt cost below 10). The sync treats a configured `{noop}x` whose stored hash matches `x` as unchanged, so local restarts don't flip it back and forth; configured `{bcrypt}` hashes must use cost ≥ 10 (the runbooks' `htpasswd -bnBC 12`), or every start would rewrite them.
- **Redirect URI policy from the profile** (like the other staging/prod refusals): `local`/`test` any absolute URI; `dev` `https` or `http` on a loopback host (RFC 8252 § 7.3; the local rehearsal runs `dev` on `http://localhost`); `staging`/`prod` `https` only. Everywhere: no wildcards, no fragments; public clients may use a reverse-domain private-use scheme (RFC 8252 § 7.1). Other fail-fast checks: type, known grant types, scopes, a redirect URI and PKCE for `authorization_code`, no secret on public clients, no `client_credentials` on public clients, refresh TTL > access TTL, no shared secrets (Spring Authorization Server would reject them at insert anyway), at least one client under staging/prod. All problems are reported at once, before anything is written.
- **Which clients where:** `studio-bff` always (`STUDIO_BFF_SECRET_HASH` stays required). `consumer-bff` and `console-bff` are `optional: true`: their apps don't exist yet, so `CONSUMER_BFF_SECRET_HASH` / `CONSOLE_BFF_SECRET_HASH` are **no longer required** in the cloud and each client is registered once its hash is set. `mobile-consumer` and `courier-app` are declared in `application-local.yml` only (they have no apps yet; S-28/S-87 add them to the environments — README § OAuth clients shows the block). Existing dev/staging databases that the old seeder filled will report them as "not in configuration" until then. Partners (S-29) need `private_key_jwt` (`jwk-set-url`), which `ClientSpec` doesn't model yet; `client_credentials` with a secret hash works today.
- **DPoP** (ARCHITECTURE.md § Identity, mobile): `dpop-required` is stored as client setting `settings.client.northline.dpop-required` and logged as not enforced; enforcement comes with the mobile stories.
- Tests: `OAuthClientCatalogTest` (policy per profile, https/loopback/private-use rules, `{noop}`/plain secrets, every missing setting listed, optional clients, shared secrets, empty under strict), `OAuthClientSyncTest` (start-up registration, create then unchanged — i.e. the stored settings round-trip equal —, in-place update naming the field, secret rotation checked at `/oauth2/token` with bcrypt: old secret 401, new one accepted, orphans reported and kept, `list` writes nothing, the command's `list`/`sync` against the database without the server), `OAuthClientsStartupTest` (prod without `STUDIO_BFF_SECRET_HASH` stops before start-up; the consumer/console hashes aren't required).

## 2026-09-29 — S-8 SMS and voice OTP provider (server/auth `ca.northline.auth.sms`, Studio register flow)

- **Port unchanged in spirit, one argument more:** `SmsSender.sendCode(phone, code, channel, locale)` returns once the provider accepted the message, or throws `SmsDeliveryFailed` with a `Kind`: `UNDELIVERABLE_NUMBER` (invalid, not a mobile, opted out/STOP, unreachable) or `PROVIDER_UNAVAILABLE` (credentials, sender, geo permissions, throttling, 5xx, time-out). Codes stay generated, stored and checked by northline-auth (the existing 10 min / 5 tries / 45 s rules, S-9 limits, audit), so the providers' own verification products (Twilio Verify, …) are not used: they would own the code and its rules, and every provider's would differ.
- **Provider = `northline.sms.provider` (S-1 property), nothing else.** `SmsConfig` creates exactly one adapter: `local` (default; `LoggingSmsSender` logs the code — unchanged behaviour for `local`/`test`, the profile switch `@Profile("local","test")` is gone and `UnconfiguredSmsSender` is deleted), `twilio`, `aws`. `azure` stays a reserved value that stops start-up with "not implemented yet" (Azure Communication Services SMS + Call Automation is the documented next adapter; Google Cloud has no first-party SMS service, so GKE uses Twilio). The S-1 value `sns` was renamed `aws`: the adapter uses AWS End User Messaging SMS and voice (API `pinpoint-sms-voice-v2`), which is what SNS sends SMS through and the only AWS API that also places voice calls.
- **Twilio** (recommended everywhere): plain REST through an `@HttpExchange` interface (`TwilioApi`) on a `RestClient` over the JDK `HttpClient` (connect 5 s, read 10 s), HTTP Basic with account SID + auth token — no Twilio SDK. SMS = `Messages.json` (`From` = an E.164 number, or `MessagingServiceSid` when `SMS_FROM` starts with `MG`); voice = `Calls.json` with inline `Twiml` (`<Say>` with the Amazon Polly voice of the language, the digits read one by one, twice; Twilio's legacy `alice` voice is retired). `SMS_VOICE_FROM` = the caller id when `SMS_FROM` is a Messaging Service (which can't call). Error codes 21211, 21214, 21217, 21401, 21610, 21612, 21614, 13223, 13224 = undeliverable number; everything else (including 21408 geo permission — our configuration) = unavailable.
- **AWS** (for an all-AWS deployment): the official SDK client (`pinpointsmsvoicev2`, sync, the SDK's default Apache 5 HTTP client as for KMS; credentials from the default chain = workload identity), `SendTextMessage` (`TRANSACTIONAL`) and `SendVoiceMessage` (Polly Joanna / Chantal). A `ValidationException` about `DestinationPhoneNumber` or an opted-out / protect-blocked `ConflictException` = undeliverable, the rest unavailable.
- **No retries** in either adapter (AWS SDK retry strategy `doNotRetry`): a retried send can deliver twice and the person can press Resend; the S-9 limits count every attempt anyway.
- **Canadian numbers:** `PhoneNumber` already accepts only NANP numbers and keeps them as E.164 `+1XXXXXXXXXX`; that is what both providers receive. Logs show `PhoneNumber.masked()` (`+1 403 *** **48`), never the whole number or the code (except the `local` adapter, whose purpose is to log the code).
- **Language:** the Studio sends `Accept-Language: fr-CA` / `en-CA` (its UI language) on register and resend; French for any `fr` locale, English otherwise (`CodeMessages`). SMS texts fit one GSM-7 segment (checked by a test: ≤ 160 characters, only GSM-7 characters — `é` is one).
- **Failures shown to the person** (FlowRejected `CODE_NOT_SENT` → **503** `code_not_sent`): an undeliverable number on the form = the existing field error on `phone` ("Enter a valid Canadian mobile…", rule `format`); anything else on the form = "We couldn't send a code to this number right now. Try again in a moment."; a failed text resend = "…or choose Call me instead."; a failed call = "…or resend the code by text." (new en/fr copy in `AuthMessages` and the Studio's `messages.ts`, since no existing message said the code wasn't sent). Nothing is stored for a code that wasn't sent: the registration isn't started, and a failed resend/call keeps the previous code valid. The voice call is the fallback the design already has ("Call me instead"); there is no silent automatic fallback, because a call the person didn't ask for is worse than an error that offers it.
- **Fail fast:** `staging`/`prod` add `sms-provider: SMS_PROVIDER, SMS_FROM` to the S-1 required variables (so `SMS_PROVIDER` can't silently default to `local`), and `SmsConfig` refuses `local` there; `dev` may keep `local` (warning logged — the local cloud rehearsal registers with logged codes). Each provider lists every missing setting at once (`SMS_PROVIDER=twilio needs: SMS_ACCOUNT_ID …; SMS_AUTH_TOKEN …`).
- New variables (auth): `SMS_VOICE_FROM`, `SMS_REGION`, `SMS_ENDPOINT` (API base override for tests / LocalStack / VPC endpoints). The runbooks now list the SMS variables for auth only (api and worker only log the provider).
- Tests: `TwilioSmsSenderContractTest` and `AwsSmsSenderContractTest` (WireMock stand-ins of the real APIs, with the clients exactly as `SmsConfig` builds them: request shape, Basic auth / SigV4 region, French and English bodies, voice TwiML / Polly voice, invalid number, landline, opted out, 401, geo permission, throttling, 5xx, unreachable), `CodeMessagesTest` (language, one GSM-7 segment, digits read one by one), `SmsConfigTest` (selection, missing settings, `local` refused under staging/prod, Messaging Service needs a voice number, `azure` reserved), `SmsStartupTest` (prod without the SMS variables stops before start-up), `RegistrationApiTest.Delivery` (field error, 503 then retry, failed call keeps the SMS code, locale passed on); Studio `auth.test.tsx` (Accept-Language, both 503 messages).
- Not done: Twilio API keys (`SK…`) instead of the auth token; delivery status callbacks (a message accepted but then undelivered is only visible in the provider's console); the Azure adapter; Twilio's SMS Pumping Protection and spend alarms are console settings (runbook), not code.

## 2026-09-30 — S-2/S-3 Terraform reconciled with S-7, S-8, S-10, S-122 (merge of main into infra/s-3-data-stores)

- **Signing key (S-7):** `KMS_KEY_ID` in `config_env` is now what the adapters sign with: the AWS key ARN (unchanged), the Google Cloud key **version** name (`…/cryptoKeys/signing/cryptoKeyVersions/1`, the version created with the key — asymmetric keys have no primary version) and the Azure **versioned** key URL (was the crypto key name / the versionless URL, which S-7 rejects). New `KMS_PUBLISHED_KEY_IDS`. Both are driven during a rotation by the env root variable `signing_key_ids = { active, published }` (defaults: the Terraform key, empty), so the ConfigMap stays the source of truth across the publish → switch → retire deploys; on Azure `active` must be pinned before `az keyvault key rotate` because the resource's `id` follows the newest version. AWS rotation (a new key) is still a manual key + grant: the stack manages one signing key.
- **Least privilege on the signing key:** only `northline-auth` is a key user (the api never reads `KMS_*`; it verifies via the JWK set — supersedes "`api`, `auth` get sign/verify" in S-2). Actions narrowed to what the adapters call: AWS `kms:Sign` + `kms:GetPublicKey` (no `Verify`/`DescribeKey`), Google Cloud `roles/cloudkms.signer` + `roles/cloudkms.publicKeyViewer` (was `signerVerifier`), Azure "Key Vault Crypto User" on the key (versionless scope, so new versions are covered).
- **Object storage (S-10):** new `storage_encryption_key` module output (contract updated in all three implementations) → `STORAGE_ENCRYPTION_KEY`: the `data` key ARN (AWS, SSE-KMS) / Cloud KMS key name (Google Cloud, CMEK); empty on Azure, where the account-level customer-managed key already encrypts every blob, so an encryption scope would add nothing. `STORAGE_REGION` is empty on Google Cloud and Azure (S3-only variable); `STORAGE_ENDPOINT` on Azure is the blob endpoint without the trailing slash and `STORAGE_BUCKET` the container name. Grants match object-storage.md: S3 object get/put/delete on `bucket/*` + `ListBucket` on the bucket (dropped `GetBucketLocation`), `roles/storage.objectUser` on the GCS bucket, "Storage Blob Data Contributor" on the **container** (was the account). Azure blob soft delete 30 days (runbook value). This supersedes S-10's "buckets are created by hand until Terraform (S-2)" for Terraform-managed environments; the manual tables stay for others.
- **SMS (S-8):** infrastructure can't obtain a sender number (Canadian long code / toll-free registration, SMS sandbox exit are console/support processes), so Twilio stays operator configuration (`SMS_AUTH_TOKEN` is already a `secret_env` secret). On AWS, `sms_origination_identity` (the End User Messaging phone number / pool ARN, validated to a Canadian region) grants `northline-auth` `sms-voice:SendTextMessage` + `SendVoiceMessage` on that identity only and sets `SMS_PROVIDER=aws`, `SMS_FROM`, `SMS_REGION`.
- **OAuth clients (S-122):** nothing to provision; `CONSUMER_BFF_SECRET_HASH` / `CONSOLE_BFF_SECRET_HASH` keep their (empty) secrets but are optional now. `OAUTH_CLIENTS_SYNC_ON_STARTUP` and the registration Job are deployment concerns (S-14).
- **Runbooks:** dev/staging/prod keep S-3's Terraform column and "from Terraform `config_env`" sources with main's S-7/S-8/S-9/S-10/S-122 variable rows; infrastructure.md § 4 lists the new outputs, the grants and the variables Terraform does not set; key-rotation.md and object-storage.md point to Terraform above their manual set-up sections.
- **Offline tests** assert the new keys in every env root plus per-cloud values (GCP key version suffix, Azure container/endpoint, empty `STORAGE_ENDPOINT` on S3/GCS), the `signing_key_ids` pass-through and, on AWS, the End User Messaging wiring.

## 2026-09-30 — S-11 Stripe Connect Express live adapter (payments, merchants Connect)

- **Charge model = separate charges and transfers** on the platform account: one manual-capture PaymentIntent per job
  or order line (a single PaymentIntent can be captured only once without IC+ multicapture, and each line captures and
  releases on its own clock), CAD, `payment_method_types=[card]`, `setup_future_usage=off_session` on a Stripe Customer
  that holds only `northline_user_id`; no `on_behalf_of` (Northline is merchant of record and remits GST/HST). Capture
  at fulfilment (amount + tax); on release a Transfer of `amount − fee` to the connected account with the
  PaymentIntent's `transfer_group` (`order:<id>` / `booking:<id>` given at checkout, fallback `<refType>:<refId>`) and
  `source_transaction` = its charge. The **application fee is implicit**: the take rate is what Northline doesn't
  transfer (`Fees.transferCents`). New `payments.api.PaymentAuthorizations.start` opens the PaymentIntent (checkout —
  the consumer app / orders / booking call it; nothing does yet) and returns the client secret for Stripe.js.
- **A hold must be real:** `EscrowLifecycle.hold` reads the PaymentIntent at Stripe and refuses 409
  `payment_not_authorized` unless it is `requires_capture`, and `payment_amount_mismatch` when less than amount + tax is
  capturable. It records customer, card, charge, `capture_before`, transfer group and the reference on
  `payments.payment_intents`. The fake treats unknown `pi_…` as authorized (ids containing `requires_action` are not).
- **Authorization window policy** (`AuthorizationWindow`): Stripe keeps an online card authorization 7 days
  (`capture_before` on the charge). 36 h before it lapses the job `renewAuthorizations` places a new manual-capture
  PaymentIntent off-session with the saved card (key `nl1:reauthorize:<escrow>:<n>`), and only once it is authorized
  moves the escrow to it and cancels the old one (never unheld, never two captures). Declined / needs 3-D Secure /
  no saved card → the old hold stays, `payment.reauthorization_required` (new event, topic `payments.payment`, key =
  escrow id; published once per hold) asks the customer to confirm again, retry every 12 h until it lapses. We don't
  request extended (30-day) or incremental authorization: IC+ pricing only, some brands only; a larger quote is a new
  quote version and a new hold.
- **Refunds at Stripe:** hold never captured (escrow not fulfilled) → cancel the PaymentIntent, no Stripe refund and no
  ledger posting (nothing was charged); captured → Refund to the card; released and merchant-funded → also a Transfer
  reversal of `min(refund, transferred − already reversed)` (`Fees.transferReversalCents`). **Northline's fee is not
  refunded** (it matches the Finance ledger, which debits the merchant with the whole refund); tax still isn't
  reversed. Goodwill credits (`kind = credit`) move no card money. A shortfall beyond the transfer is a negative merchant
  balance recovered from later releases; accounts have `debit_negative_balances=true`.
- **Payouts:** Stripe's schedule on every connected account is `manual` (set at account creation and when payments
  links the account; `PayoutGateway.updateSchedule` was removed — it set an automatic Stripe schedule that would have
  paid out beside Northline's run and ignored the reserve, case holds and the 24 h bank-change hold). Scheduled payouts
  stay Northline's 09:00 run; instant payouts send `amount − 1 %` and then recover the fee from the connected account
  with an **account debit** (Transfer from the connected account to the platform, `payouts.stripe_fee_transfer`),
  because Stripe bills Express instant-payout fees to the platform. Step-up and the bank-change hold are unchanged.
  The in-transit settle job now asks Stripe for the payout's state (reconciler; webhooks in S-12).
- **Idempotency keys** (`shared.stripe.StripeIdempotencyKeys`) on every mutating call: `nl1:<operation>:<ids>` from
  domain ids (capture includes the PaymentIntent row so a renewed hold gets its own key; scheduled payouts are keyed
  per merchant and Edmonton date); for calls a person starts, `nl1:<operation>:<sha256(scope, client Idempotency-Key)>`
  so the client's retry reaches the same Stripe call and its raw key never leaves Northline (instant payout; checkout
  when given a key). Keys over 255 characters keep their start plus a digest. Bank tokens use a digest of the typed
  details, single-use links/sessions a fresh ULID (only stripe-java's own retries share them). stripe-java retries
  network errors twice (safe with the keys).
- **API version pinned** to `2026-08-26.dahlia` (stripe-java 33.4.2): `shared.stripe.StripeClients` builds every
  client, refuses to start if the SDK speaks another version, and sets timeouts (10 s / 30 s) and retries. The merchants
  gateway uses the same factory.
- **Adapter selection:** payments unchanged (key set → stripe-java, else fake). Merchants' `ConnectAccountGateway` now
  also follows the key (`ConnectGatewayConfig`): key set → stripe-java under any profile (so `local` +
  `STRIPE_API_BASE` exercises the real adapter against stripe-mock); no key → the fake under `local`/`test`, the 409
  `stripe_unavailable` adapter elsewhere. Tests still force blank keys.
- **Connect accounts** are created Express, CA, CAD, capabilities `card_payments` + `transfers`, manual payouts,
  metadata `northline_merchant_id`, key `nl1:connect-account:<merchantId>`; onboarding links collect `eventually_due`.
  The compliance screen's instant-payout flag comes from the default external account's `available_payout_methods`
  (was hard-coded true), and `directors_provided` / `executives_provided` count as owners. When the owner opens the
  onboarding link, merchants calls new `payments.api.ConnectedAccounts.linked` so `payments.connected_accounts` gets the
  account (before, nothing wrote it outside the seed). Payout schedule / instant eligibility on the compliance screen now
  come from `payments.api.PayoutPlan` (Northline's schedule; Stripe's is always manual), falling back to Stripe's.
- **Schema V062:** `payment_intents` + `stripe_customer, stripe_charge, transfer_group, ref_type, ref_id, merchant_id,
  authorized_at, capture_before, reauthorizations, reauth_failed_at, replaced_by, created_at`, state `canceled`;
  new `payments.stripe_customers`; `transfers.transfer_group, reversed_cents` + unique `stripe_transfer`;
  `refunds.stripe_transfer_reversal, reversed_cents`; `payouts.stripe_fee_transfer` + unique `stripe_payout`.
- **Tests:** stripe-mock (`stripe/stripe-mock:v0.205.0`) in Testcontainers for every adapter call (payments and
  merchants), recording the headers stripe-java sends — every POST has an `nl1:` Idempotency-Key and every request the
  pinned Stripe-Version; a Spring test runs checkout → hold → capture → transfer → instant payout + fee → refund with
  reversal through the real adapter against stripe-mock (only "is it `requires_capture`" is stubbed: stripe-mock is
  stateless). Unit tests for fee/transfer/reversal math, the authorization window and key derivation; fake-gateway
  tests for renewal, renewal failure, refund-before-capture and linking. Runbook: `docs/runbooks/stripe.md`.
- **Not done:** the consumer checkout itself (nothing calls `PaymentAuthorizations` yet), Stripe webhooks (S-12),
  chargebacks, Stripe Tax (S-21), Identity (S-22), Financial Connections beyond the existing bank-link session (S-24),
  refunding tax, reconciling Stripe's processing fees into the `stripe_fees` ledger account.
## 2026-09-30 — S-13 Transactional email (library `server/email`, api `messaging` + `merchants`)

- **Where the code lives:** a new plain library `server/email` (package `ca.northline.email`, like `server/platform`), used by the api now and by the worker's notifications consumer (S-27) later: the `EmailSender` port, one adapter per provider, the templates, and `Mailer` (render → CASL footer → unsubscribe headers → send once). Its top-level package is the public surface (Modulith sees it as the module `email`); adapters sit in sub-packages. `EmailProperties` (S-1) gained reply-to, mailing address, contact, region, endpoint, api key, configuration set and retry settings.
- **Provider = `northline.email.provider` only**, one adapter created: `local` = SMTP to `SMTP_HOST:SMTP_PORT` (Mailpit), which only **logs** the text body when nothing listens (a laptop without Mailpit still sees invitation links, as before); `smtp` = any relay (SES SMTP, SendGrid SMTP, ACS SMTP…; STARTTLS required with credentials); `ses` = SES API v2 SDK (`SendEmail`, simple content with custom headers, workload identity); `sendgrid` = v3 Mail Send over an `@HttpExchange` client (click/open tracking off per message, so invitation tokens never pass a redirector); `azure` = Azure Communication Services Email REST (`emails:send`, api-version 2023-03-31) with access-key HMAC signing or Entra ID (`DefaultAzureCredential`) — implemented fully rather than stubbed, it is one REST call. Staging/prod: `northline.required-env.email: EMAIL_PROVIDER, EMAIL_FROM, EMAIL_UNSUBSCRIBE_KEY, API_PUBLIC_URL` and `EMAIL_PROVIDER=local` stops start-up; `dev` may keep `local` (warning). Missing provider settings are all listed at start-up.
- **Failures:** two kinds, as for SMS (S-8): *rejected* (SMTP 5xx on the recipient, HTTP 4xx except 401/403/408/429, SES `MessageRejected`/`BadRequest`) is logged and never retried; *unavailable* (5xx, throttling, time-outs, credentials, unverified sender, sending paused) is retried in-process (`EMAIL_RETRY_ATTEMPTS` 3, back-off 2 s doubling; SDK retries off so there is one policy), then the listener fails, Modulith marks the publication failed, and `config.FailedEmailResubmission` resubmits the **email listeners'** failed publications every 10 min up to 24 attempts (other modules' publications are untouched); all incomplete publications are also republished at start-up.
- **Once per (event, recipient):** `Mailer` claims `<eventId>:<userId>` (the invitation: its event id) in `events.processed_events` with consumer `email` — the table the worker's consumers already use for dedupe, so the api and the S-27 worker can't both send the same email — in its own transaction, before sending; an unavailable provider releases the claim. Trade-off: a crash between the claim and the provider's answer loses that one email (chosen over duplicates, which the concurrent-delivery test showed happen with check-then-send).
- **After commit, never in the business transaction:** every email is sent by an `@ApplicationModuleListener` (async, own transaction, registry = outbox). The user's action never waits for or fails on email.
- **Team invitations:** `TeamManagementService.invite` publishes the internal (not externalized) `merchants.application.TeamInvitationIssued`; `TeamInvitationDelivery` sends it if the invitation is still pending. `sent` in the response now means "will be emailed" (email contacts) — mobile invitations are `sent: false` and the dialog's copy-link fallback is unchanged (no Studio change needed). The event carries the link token, because only its hash is stored: the token therefore sits in `events.event_publication[_archive]`. Accepted: a token alone can't join a team (a signed-in account whose email/mobile matches the invitation plus MFA is required) and it expires after 7 days. Language = the inviter's profile locale (the invitee may have no account). The old `LoggingTeamInviteSender` / `UnconfiguredTeamInviteSender` are gone.
- **Money notices** (`messaging.application.MerchantEmailNotices`; messaging owns Settings › Notifications): `payout.sent` → receipt to owners + bookkeepers (row `payout`); `payout_account.changed` (requested, effective) → owners, **always** (security notice, no preference); `dispute.updated` / `dispute.decided` and `refund.case_updated` / `refund.issued` → owners (row `dispute` — the design has no separate refunds row). Technicians and cooks get none (they can't act on money). Each member's own matrix email cell decides; quiet hours don't apply to email (S-27 applies them to push/SMS).
- **New / extended payments events (additive):** `dispute.updated` (`opened` with the reply deadline, `offer_declined`, `offer_expired`) and `refund.case_updated` (`requested` with the review deadline, `approved`, `agent_review`, `denied`) — published by `RefundCaseService` for changes the merchant didn't make themselves; `caseNumber` on `dispute.decided` (plus the disputed `amountCents`) and on `refund.issued`. Payloads stay ids + amounts (no PII) so the S-27 worker can render the same emails from Kafka. Schemas updated/added; same topics (`payments.dispute`, `payments.refund`).
- **SMS for the bank change** is **not** sent by the api: the SMS port lives in northline-auth and calling it across apps isn't clean. `payout_account.changed` is already on Kafka (`payments.payout_account`); the S-27 consumer sends the text. The S-13 acceptance "bank change sends email + SMS" is therefore met for email only until S-27.
- **Recipients:** new `identity.api.NotificationContacts` (name, email, phone, profile locale; active accounts only) and `merchants.api.BusinessNames` (display name). Invalid stored addresses are skipped with a warning; logs mask addresses (`r***@example.com`).
- **Templates:** Thymeleaf (core, no Spring integration) — `email/templates/<name>.html` (table layout, inline styles only) + `.txt`, a shared `layout.html` / `footer.txt`, copy in `email/messages.properties` + `_fr` (same keys — a test checks it; typographic apostrophes only). Values are formatted per locale before rendering: CAD money (`$814.37` / `814,37 $`), dates and times in `America/Edmonton`. Five templates: `team-invitation`, `bank-account-change` (2 variants), `payout-sent`, `dispute-update` (4 changes, 4 decisions), `refund-case-update` (5 changes). French copy follows the Studio glossary (versements, litiges, remboursements).
- **Colour exception:** email clients ignore stylesheets, CSS variables and `color-mix()`, so `EmailBrand` (template layer only) holds the five base colours copied from `tokens.json` (a test keeps them equal) and five sRGB approximations of derived ones (surface, divider, muted, highlight-100, on-accent). The no-hex rule still applies to every web component.
- **CASL:** every email identifies the sender (`EMAIL_MAILING_ADDRESS`, default the Terms' "Northline Marketplace Inc. · 1200 – 8th Avenue SW, Calgary, Alberta T2P 1B5", plus `EMAIL_CONTACT`) and says why the address got it. `Purpose.TRANSACTIONAL` (invitation, bank change) has no unsubscribe; `NOTIFICATION` (payout, dispute, refund) and `COMMERCIAL` (none yet) must carry one — the renderer refuses them without. The link is `API_PUBLIC_URL/api/v1/email/unsubscribe?t=<token>` (the api, not the BFF: it must work without a session), plus `List-Unsubscribe` / `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (RFC 8058). Token = HMAC-SHA256 (`EMAIL_UNSUBSCRIBE_KEY`) over `v1|userId|row|lang`; no expiry (CASL: ≥ 60 days), no address inside. `GET` shows a confirmation page (never changes anything — scanners prefetch links), `POST` (button or one-click) turns that row's email cell off at once. Unknown/forged token → 400 page pointing to Settings.
- **Local preview:** `GET /api/v1/dev/emails[/{key}?lang=fr-CA&format=text]` (profile `local` only; public route) renders every sample; the rendering tests use the same samples.
- **Tests:** library — every template × variant × en/fr (subject, CASL footer, links, inline styles only, escaping, unsubscribe required), `EmailBrandTest` (tokens.json), GreenMail SMTP (multipart/alternative, headers, UTF-8, no server = unavailable, `local` logs), WireMock contracts for SES (SigV4, body shape, `MessageRejected` not retried, `SendingPaused` unavailable, 500 → retried, 429 × 3), SendGrid (body, 400 rejected once, 403 unavailable, 503 → retried, 500 × 3) and Azure (HMAC signature and content hash recomputed, 400, 429 → retried, 503 × 3), `DefaultMailerTest`, `EmailAutoConfigurationTest` (selection, missing settings, `local` refused under staging/prod). api — `TeamInvitationEmailTest` (one email via the real endpoint, French, same link; mobile = not sent; a withdrawn invitation never emailed), `MerchantEmailNoticesTest` (exactly one email per member even when the event is delivered twice concurrently, language, roles, matrix honoured, bank notice always, unsubscribe GET/POST one-click, forged token), `CaseNotificationEventsTest` (payments publishes the new events with case numbers and deadlines).
- **Not done:** SMS notices and mobile invitations (S-27); bounce/complaint feedback into Settings (providers' suppression lists handle it); DNS records are manual (S-17); consent records for commercial email (none sent); the worker doesn't use the library yet (S-27 adds `implementation(project(":email"))`).

## 2026-09-30 — S-12 Stripe webhooks (payments; merchants link on account.updated)

- **Endpoints:** `POST /api/v1/webhooks/stripe` (platform events) and `POST /api/v1/webhooks/stripe/connect`
  (connected accounts' events), each with its own signing secret — `STRIPE_WEBHOOK_SECRET` /
  `STRIPE_CONNECT_WEBHOOK_SECRET` (`northline.payments.stripe-webhook-secret` / `…-connect-webhook-secret`), required
  in staging/prod (`northline.required-env.payments`); without one the endpoint answers 503 `webhooks_unconfigured`.
  Open in `SecurityConfig` (POST only): no token, session or CSRF — the `Stripe-Signature` is the authentication.
  Rate limit 600 requests/min per client address and api instance (`STRIPE_WEBHOOK_RATE_LIMIT`), then 429 +
  `Retry-After` (in-process fixed window; there is no shared rate-limit store yet).
- **Verification:** stripe-java `Webhook.constructEvent` (HMAC-SHA256, all `v1` values so secret rolls work) with a
  5-minute timestamp tolerance (`STRIPE_WEBHOOK_TOLERANCE`) — a captured delivery can't be replayed later; a delivery
  signed for the other endpoint fails. Bad / missing / stale → 400 `invalid_signature`. Handlers read the raw
  `data.object` JSON, so they don't depend on stripe-java's typed deserialisation for the event's API version (the
  endpoints must still be created with the pinned `2026-08-26.dahlia`).
- **Dedupe + async:** V063 `payments.stripe_events` (PK = Stripe event id; type, endpoint, account, livemode, object id,
  Stripe's `created`, payload, state `received|processed|ignored|failed`, attempts, error). Receiving inserts
  `on conflict do nothing` and publishes the internal `StripeEventReceived` in the same transaction (Modulith outbox),
  answers 200 (`{"received":true,"duplicate":…}`) — duplicates too. `StripeEventListener`
  (`@ApplicationModuleListener`) and the payments job (`processStripeEvents`, every minute, ≤ 10 attempts) apply events
  through `StripeEventProcessor`, each in its own transaction under `SELECT … FOR UPDATE`, so the two never apply one
  twice; a failure is recorded on the row and retried. Processed/ignored rows are purged after 30 days. The stored
  payload drops personal fields (billing details, e-mail, phone, names, addresses, dispute evidence, individual /
  representative) — Northline never needs them.
- **Out of order:** payouts and closed cases never leave a final state (`payout.paid` after `payout.failed` changes
  nothing; Stripe can send `failed` after `paid`, which is honoured); disputes and connected accounts store the
  `created` time of the last event applied and ignore older ones; a dispute event for a dispute not seen yet opens it
  first (closed-before-created ends in the same state); PaymentIntent statuses only move forward. Events of the other
  mode (live vs test, from the key prefix) are ignored.
- **Handlers:**
  - `payout.paid|failed|canceled` → `Payout.paid()` / `returned()`: a returned payout goes back to the merchant's
    balance (`LedgerEntry.payoutReturned`, the reverse of `paidOut`), the recovered instant fee is transferred back to
    the connected account, `failure_code` kept, new event **`payout.failed`** (`payments.payout`, schema v1). The
    settle job stays as the **fallback reconciler**: it asks Stripe for in-transit payouts 24 h after their arrival
    date (at once with the fake, which sends no webhooks).
  - `charge.dispute.*` → the existing disputes flow: a new card-dispute case (`Dispute.chargeback`, subject "Card
    dispute · <reason>", reply-by = Stripe's evidence deadline − 2 days, escrow on hold, `dispute.updated` "opened" so
    S-13 emails the team), or the customer's open case on that escrow becomes the card dispute. The merchant answers
    with response + evidence; goodwill offers and "full refund" are refused (409 `card_dispute`) because a disputed
    charge can't be refunded — the issuer decides. `won` / `warning_closed` → decision `release` (escrow resumes its
    normal release); `lost` → `full_refund`: unreleased escrow is refunded from escrow, released money is taken from
    the merchant's balance and reversed from the transfer; the merchant carries up to the escrow amount, Northline the
    tax part (`LedgerEntry.chargedBack`). `dispute.decided` with `decidedBy = "stripe"`. Submitting evidence to Stripe
    stays an agent task in the Stripe dashboard.
  - `account.updated` → `payments.connected_accounts` gets charges/payouts enabled, requirements due / past due,
    disabled reason and instant eligibility (default bank's `available_payout_methods`); an account whose metadata names
    a merchant is recorded if unknown. Instant payouts answer 409 `payouts_disabled` and the scheduled run skips the
    merchant while Stripe has paused payouts. New in-process event `payments.api.ConnectAccountUpdated`; merchants
    links the business to the account (`StripeAccountUpdates`); the compliance screen already reads requirements live.
  - `payment_intent.*` → mirror state (authorized / captured + charge / failed / canceled); a hold canceled at Stripe
    while its escrow still waits for capture publishes `payment.reauthorization_required`. `charge.refunded` → intent
    `refunded`. `refund.*` → `refunds.stripe_status` (failed/canceled logged as errors; refunds made outside Northline
    logged and ignored). `transfer.reversed|updated` → `transfers.reversed_cents` = Stripe's cumulative
    `amount_reversed`.
  - Anything else → stored `ignored`, logged at info.
- **Schema V063:** `payments.stripe_events`; `payouts.failure_code, returned_fee_transfer`; `disputes.stripe_dispute`
  (unique), `stripe_status, stripe_reason, stripe_updated_at`; `refunds.stripe_status`; `connected_accounts.charges_enabled,
  payouts_enabled, requirements_due, requirements_past_due, disabled_reason, stripe_updated_at`.
- **Tests:** `StripeWebhookApiTest` signs fixtures like Stripe (`t=…,v1=HMAC`) with the test profile's obviously fake
  `whsec_test_…` secrets and goes through `Webhook.constructEvent`: missing / wrong / other-endpoint / tampered
  signatures, a stale timestamp (replay), duplicate delivery applied once, payout paid → failed → late paid, dispute
  created → updated → lost (+ late older update), closed-before-created (won), a card dispute joining the customer's
  case, account.updated pausing payouts / linking the business / newest wins, PaymentIntent mirror only moving forward
  + lapsed hold, refund and transfer events, unknown events and redaction, live-mode events ignored;
  `WebhookRateLimiterTest`.
- **Not done:** submitting dispute evidence to Stripe from Northline; the Stripe dispute fee in the ledger; emailing
  `payout.failed` (the event exists; the S-13 notifier doesn't subscribe to it yet); a shared (Redis) rate limit.

## 2026-09-30 — S-14 Container images and Helm chart (portable)

- **Java images with Jib 3.5.4** (Gradle plugin, applied from `server/build.gradle.kts` to api, auth, bff, worker), not Dockerfiles: no Docker daemon to push, layered exploded classpath, reproducible (epoch timestamps, same commit → same digest), multi-platform (amd64 + arm64) in one step. Base `gcr.io/distroless/java25-debian13:nonroot` pinned by digest (no shell, uid 65532). Jib handles Java 25 with an explicit `mainClass` (no class scanning). `-XX:MaxRAMPercentage=75` so the pod limit sizes the heap. Image = `<image.registry>/<app>:<image.tag>` (`REGISTRY` / `IMAGE_TAG`), matching Terraform's repository names; OCI labels carry the commit and tag.
- **Worker health:** the worker had no HTTP endpoint; it now runs Spring MVC + actuator on 8084 exposing only `health` (probes). Cheaper than an exec probe (distroless has no shell) and consistent with the other apps.
- **Web images from one `web/Dockerfile`** (targets `studio`, `consumer`; build stages on `$BUILDPLATFORM`, so arm64 needs no emulation). Studio: `nginxinc/nginx-unprivileged` (uid 101), SPA fallback, immutable `/assets`, `gzip_static`, CSP and the usual headers in one included snippet (nginx drops server-level `add_header` in locations that set their own). **Runtime configuration instead of per-environment builds:** `/config.js` is answered by nginx from `NL_AUTH_ORIGIN` (envsubst at start, filtered to `NL_*`), `src/lib/auth-server.ts` reads `window.__NL_CONFIG__.authOrigin` first, then `VITE_NL_AUTH_ORIGIN`. Brotli is left to the edge (stock nginx has no brotli module; a custom nginx build isn't worth it for one static site). Consumer: TanStack Start 1.168 emits a fetch handler, not a Node server (the old `.output/server/index.mjs` start script was stale); a ~100-line `server/node-server.mjs` on Node's http module serves assets and SSR, no new dependency; runs on distroless Node 22 with `pnpm deploy --prod` node_modules. The console app doesn't exist, so the chart has a disabled `apps.console` placeholder and the Dockerfile a note.
- **One chart, per-app values** (`deploy/helm/northline`), not subcharts: the apps share nearly all structure (env from ConfigMaps + per-key Secret refs, probes, security context), so one set of templates ranging over `apps` keeps them identical; `type: spring|static|node` picks probes/env. Resources are named `northline-<app>` regardless of the release name, because Terraform binds cloud identities to those ServiceAccount names.
- **Cloud neutrality:** templates contain no provider logic. Workload identity is Terraform's own output shape (`workloadIdentities.<app>.service_account_annotations` / `pod_labels`), so IRSA, GKE and Azure differ only in values. Cloud overlays hold region hints, the recommended email provider (S-13), and on Google Cloud the Memorystore CA via a Spring Boot SSL bundle (`SPRING_DATA_REDIS_SSL_BUNDLE` + PEM ConfigMap) — resolves the S-3 open item without a JKS truststore (untested against a real Memorystore).
- **Configuration and secrets:** Terraform `config_env` → ConfigMap `northline-infra` (values `configEnv`, i.e. `terraform output -json config_env | jq '{configEnv: .}'`); derived URLs + per-app env → ConfigMap `northline-<app>`; secrets by key from one Secret, each app listing only the keys it reads with required/optional (`secretEnv`), so the bff never sees the database password. `secrets.create` (Secret from values) exists only for kind and is refused unless `global.environment=local`. S-6 swaps the Secret for External Secrets without touching the Deployments.
- **Public api paths:** the api host exposes only `/api/v1/webhooks/stripe` (+ `/connect`, S-12) and `/api/v1/email/unsubscribe` (S-13); the rest of the api stays behind the BFF. `API_PUBLIC_URL` comes from `urls.api`.
- **Hardening defaults:** non-root, read-only root file system (`emptyDir` for `/tmp` and nginx's rendered config), all capabilities dropped, `RuntimeDefault` seccomp, no ServiceAccount token (the identity webhooks project their own tokens), `maxUnavailable: 0`, native `preStop.sleep` (Kubernetes ≥ 1.30, hence `kubeVersion: >=1.30`), PDBs only when there is more than one replica (a PDB on one replica blocks node drains), ingress-only NetworkPolicies (egress to managed services stays open: their addresses are provider-specific).
- **Routes:** Ingress or Gateway API HTTPRoutes, no controller- or cloud-specific annotations by default and no certificates (S-17 owns TLS/DNS/controller).
- **OAuth clients (S-122) as a post-install/post-upgrade hook Job** running `OAuthClientsCommand` from the auth image (`java -cp @/app/jib-classpath-file …`), with the auth Deployment's environment; `helm --wait` runs it after the pods are Ready. Argo CD maps it to PostSync.
- **CI (manual only):** GitHub `deploy.yml` (inputs: registry, tag, which images, push, login `password`|`ghcr`, platforms, chart checks) and GitLab `PIPELINE_PART=images` (never part of `all`) / `chart` (part of `all`). Registry credentials are plain username/password so every registry works; OIDC federation per cloud is later work. `deploy/helm/validate.sh` = `helm lint --strict` + `helm template | kubeconform -strict` for dev/staging/prod × aws/gcp/azure, kind, Gateway API, plus the render-time refusals.
- **Terraform:** `consumer` added to the registry modules' default repositories (all three clouds, contract unchanged).
- **kind rehearsal** (`deploy/kind/up.sh`): Postgres as a container beside the cluster behind a selector-less Service (managed-database shape; avoids copying the 900 MB PostGIS image into the node), Valkey in the cluster, Kafka/Elasticsearch as endpoint-less Services (clients retry instead of failing on an unknown host). Images are streamed into the node one platform at a time because `kind load docker-image` fails on multi-platform images with Docker's containerd image store.
- Not done: signing, SBOMs and provenance attestations for the images; CI OIDC to the clouds; brotli; KEDA/lag-based worker scaling (CPU HPA only); NetworkPolicy enforcement untested (the rehearsal machine's kernel lacks the nftables queue kindnet needs).

## 2026-09-30 — S-6 Secrets via External Secrets (AWS / GCP / Azure)

- **External Secrets Operator, API `external-secrets.io/v1`,** one namespaced `SecretStore` per environment (option: `ClusterSecretStore`) selected by `externalSecrets.provider` = `aws` (Secrets Manager) | `gcp` (`gcpsm`) | `azure` (`azurekv`, `authType: WorkloadIdentity`) | `fake` (kind only, refused elsewhere). The store has **no auth block by default: it authenticates as the ESO controller**, whose ServiceAccount `external-secrets/external-secrets` is the workload identity Terraform already creates and grants read on this environment's secrets (S-2). No static keys, no per-namespace cloud identity. One cluster per environment makes that sufficient; the `auth` / `serviceAccountRef` pass-throughs cover a shared cluster.
- **One ExternalSecret and one Secret per app** (`northline-<app>-secrets`), holding only the variables that app lists in `secretEnv` — the same least-privilege split S-14 made with `secretKeyRef`s, now also at the Secret level. The Deployments didn't change: only the Secret name the helper returns.
- **Optional variables are opt-in (`externalSecrets.optionalKeys`)** because an ExternalSecret fails as a whole when one remote secret is missing or has no value, and Terraform creates the operator secrets empty. Required variables are always mapped (a render-time error names any variable without a remote key). Defaults: the Terraform-generated `REDIS_PASSWORD`, `KAFKA_SASL_JAAS_CONFIG`, `ES_PASSWORD` (not `REDIS_PASSWORD` on Google Cloud: Memorystore has none).
- **Remote names:** `externalSecrets.remoteKeys` (Terraform's `secret_env`, exact) or `remoteKeyPrefix` (`northline/{env}/` AWS, `northline-{env}-` Google Cloud, empty on Azure's per-environment vault) + `secretNames` (the Terraform names). Cloud overlays turn External Secrets on; the plain-Secret mode stays for clusters without ESO (`externalSecrets.enabled=false`).
- **Terraform:** new secrets `stripe-webhook-secret`, `stripe-connect-webhook-secret` (S-12), `email-unsubscribe-key`, `email-api-key`, `smtp-password` (S-13) in all three stacks (so `secret_env` names every secret the apps read); new stack output `external_secrets` (store settings: AWS region, Google Cloud project, Azure vault URL) and env-root output **`helm_values`** (`configEnv`, the four app `workloadIdentities`, `externalSecrets` with `remoteKeys` = `secret_env` minus empty entries) — one `terraform output -json helm_values` file feeds the chart. Offline tests assert it in every env root.
- **Rotation is documented per secret** (docs/runbooks/secrets.md): ESO refreshes every hour (`force-sync` annotation to skip the wait); pods read env at start, so every rotation ends with a rollout restart (no Reloader installed — the annotation hook is documented). Dual-credential rotations where the provider allows it (ElastiCache `ROTATE`, Azure secondary keys, Event Hubs secondary connection strings, Stripe rolling keys and webhook secrets); `TOTP_KEY` and `WEBHOOK_SECRET_KEY` must not be rotated without re-encrypting stored data. The BFF client secret keeps its no-overlap caveat (S-122).
- **Local:** `.env` for Gradle/Vite; kind with a plain Secret (`secrets.create`) or with ESO's fake provider (`values-local-kind-eso.yaml`). Rehearsed on kind with ESO 0.20.4 (controller, webhook and cert-controller running): store `Valid`, the four ExternalSecrets `SecretSynced`, pods Ready, OAuth client Job completed; a `STUDIO_BFF_SECRET` rotation propagated (ESO refresh → `studio-bff: update secret` → bff restart → new secret accepted at `/oauth2/token`, old one 401). The ESO image used there was built from the v0.20.4 source with only the fake provider (the rehearsal machine can't pull from ghcr.io); the chart and CRDs are the released ones.
- Not done: nothing has talked to a real AWS / Google Cloud / Azure secrets manager yet (no accounts); ESO's own install is a documented Helm command, not part of this chart (cluster-scoped, shared); `PushSecret`/generators unused.

## 2026-09-30 — S-16 Flyway migrations as a deploy step; seed-dev never outside local

- **A Helm pre-install/pre-upgrade hook Job (`northline-migrate`), also annotated for Argo CD `PreSync` with sync waves (S-15),** runs the migrations before any Deployment changes. Helm stops the release when a pre-* hook fails, so a broken migration leaves the running ReplicaSets, ConfigMaps and Secrets untouched and the old pods keep serving. `backoffLimit: 1` (one retry for a transient connection problem; a broken migration simply fails twice), `activeDeadlineSeconds: 900`, Job kept a week for its logs.
- **Runner = the existing `DbTool` from the api image,** not the Spring application with `web-application-type=none`: DbTool needs only the database (no Kafka, Valkey, Elasticsearch, KMS or the S-1 required-variable check), starts in a second and logs the schema version and each pending migration. The `tools` source set is copied into the image under `/app/tools` (Jib extra directory) — outside the api's classpath, so Spring and Modulith never scan it — and the Job runs `java -cp /app/tools:/app/resources:/app/classes:/app/libs/* ca.northline.tools.DbTool migrate`. DbTool now also reads `DB_URL`/`DB_USER`/`DB_PASSWORD` from the environment, fails on a missing location (`failOnMissingLocations`), and quiets Flyway's URL dump.
- **Two steps, one Job:** `migrate` as the init container, then `seed-categories` (the idempotent upsert of `db/seed/categories.json`) as the container, so they run in order with separate logs (`-c migrate`, `-c seed-categories`); `migrations.seedCategories: false` drops the second.
- **Single migrator:** while `migrations.enabled` the api runs with `SPRING_FLYWAY_ENABLED=false` (auth never migrates outside local). This also removes the S-14 first-start race where auth came up before the api had created its tables.
- **Hook-scoped inputs:** before a first install the chart's ConfigMaps/Secrets don't exist, so the Job has its own ConfigMap `northline-migrate` (profile + `configEnv`) and Secret `northline-migrate-secrets` holding only `DB_PASSWORD` (hook ExternalSecret with S-6, hook Secret on kind, or `secrets.existingSecret`), all at hook weight -20 with `before-hook-creation` (never `hook-succeeded`: Helm would delete them before the Job reads them). The Job uses the namespace's `default` ServiceAccount without a token (the chart's ServiceAccounts don't exist yet either, and no cloud identity is needed). A separate migration database role with DDL rights (and a runtime role without) is possible through the same Secret later; not done.
- **seed-dev impossible outside local, three layers:** (1) `db/seed-dev` is no longer a main resource of api or auth (`processResources`) — it lives in `build/dev-seed`, added only to `bootRun`, the test runtime and the Gradle DB tasks — so neither boot jar nor image contains it (checked: the api image has `db/migration` and `db/seed`, no `db/seed-dev`); (2) DbTool refuses `db.devSeed` when a deployed profile is active (`dev`, `staging`, `prod`, `cloud` in `spring.profiles.active` / `SPRING_PROFILES_ACTIVE` / `NORTHLINE_ENVIRONMENT`) or the database host isn't local (localhost, loopback, or a dot-less container name); (3) the api's `DevSeedGuard` (`FlywayConfigurationCustomizer`) refuses any Flyway location naming `seed-dev` outside the `local`/`test` profiles (e.g. a stray `SPRING_FLYWAY_LOCATIONS`). Tests: `DbToolTest` (profiles, hosts, the refusal before connecting, and that `build/resources/main` has no `db/seed-dev` while the test classpath still does), `DevSeedGuardTest`; `deploy/helm/validate.sh` checks no deployed render mentions the dev seed.
- **Local jar runs:** `bootRun` keeps working unchanged; a boot jar started with `local` must point Flyway at the repository's seed (`SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:<repo>/db/seed-dev`) — `ci/studio-smoke.sh` does so.
- Rehearsed on kind (with External Secrets): empty database → `helm upgrade` → Job migrated 41 migrations (V001–V091) and upserted 182 categories, then the api ReplicaSet was created (after the Job's completion time); no V1xx rows, no `identity.users` rows. An api image with a deliberately broken `V999` → `pre-upgrade hooks failed`, Job `BackoffLimitExceeded` with the Flyway error in its log, the api pod and its image unchanged and still `UP`, schema still at 091; the next good upgrade succeeded. In the image, `-Ddb.devSeed=true` under `dev` was refused by DbTool, and without a profile Flyway could not resolve `classpath:db/seed-dev` (not in the image).

## 2026-09-30 — S-15 Argo CD GitOps delivery (dev auto, staging/prod manual)

- **One Argo CD per environment cluster**, each managing only `https://kubernetes.default.svc`: dev, staging and prod are separate clusters already (S-2/S-6), so prod credentials never leave prod and a dev Argo CD can't reach prod. A central Argo CD would work with the same chart (`destination.server`), not chosen.
- **App of apps as a small Helm chart (`deploy/argocd/app-of-apps`) rendered per environment from `deploy/argocd/envs/<env>/env.yaml`**, not an ApplicationSet: with one Argo CD per cluster there is one environment to generate, and explicit Applications are easier to read and diff. The cloud is a value (`cloud: aws|gcp|azure`) choosing the chart's `values-<cloud>.yaml` and add-on overlays, so environment × cloud is one definition; `env.yaml` defaults to `cloud: aws` until an environment is actually created (change by PR). The root Application `northline-<env>-root` manages itself, the AppProjects and the child Applications; bootstrap is one `helm template … | kubectl apply`.
- **Promotion file per environment: `envs/<env>/images.yaml`** (registry, tag, one digest per image), layered last on top of `values-<env>.yaml`, `values-<cloud>.yaml`, `envs/<env>/infra.yaml` (Terraform `helm_values`) and `envs/<env>/values.yaml`. New chart value `global.image.requireDigest` makes the render fail when an enabled app has no digest; every cloud environment sets it, so a movable tag can't reach staging/prod and an environment with nothing promoted shows a ComparisonError instead of deploying `:latest`-style guesses. `deploy/argocd/promote.sh` writes the file (digest lookup with crane/skopeo/`docker buildx imagetools`, the GitLab `image-digests.txt`, or `--from <env>` to copy exactly what staging runs to prod); manual CI jobs run it and open the PR (GitHub `gitops.yml` with the workflow token; GitLab push options with `GITOPS_PUSH_TOKEN`).
- **dev syncs by itself** (automated, prune, self-heal, cascade-delete finalizer); **staging and prod never do**: the app of apps fails to render if `sync.automated` is set for them. Approval = (1) the promotion PR needs a Code Owners review (`CODEOWNERS` on `envs/staging`, `envs/prod`, `app-of-apps`, `install`; branch protection / GitLab code owner approval must be switched on in the repository settings), (2) only the `deployer` role of the environment's AppProject (SSO groups in `env.yaml`) may sync, everyone else is read-only (`policy.default: role:readonly`), (3) prod syncs only inside a sync window (Mon–Thu from 08:00 America/Edmonton, 9 h). The root Application itself is automated everywhere (it only writes Application/AppProject objects) but prunes only in dev, and staging/prod Applications carry no cascade finalizer, so removing one from Git never deletes prod workloads.
- **Projects restrict destinations and kinds:** `northline-<env>-gitops` (argocd namespace, Application/AppProject only), `northline-<env>-platform` (the enabled add-ons' chart repositories and namespaces, plus the cluster-scoped kinds each add-on declares), `northline-<env>` (this repository, namespace `northline-<env>` only, a whitelist of the kinds the chart renders, no cluster-scoped kind but its Namespace; orphaned-resource warnings).
- **Sync order:** add-ons (wave −10) before the app (wave 0) — the root waits for a child's health through an `argoproj.io/Application` health check in `argocd-cm`, which reports manual-sync children as healthy so the root never waits for a human. Inside the app: PreSync migration inputs (−20) and Job (−10, S-16), SecretStore (−6) and ExternalSecrets (−5; Argo CD's built-in health check waits for `SecretSynced`) before the Deployments (0), the OAuth client Job as PostSync (Helm hook mapping). Server-side apply and server-side diff (webhook/API-server defaults on ExternalSecrets and HPAs are not drift), `PruneLast`, retries with back-off.
- **One source for the app:** the chart directory at `targetRevision`, the environment's files as value files relative to it (`../../argocd/envs/<env>/…`). A first version used two sources (chart at a pinnable `chartRevision`, environment files through `ref: values`) so chart changes could be promoted separately; the rehearsal showed Argo CD 3.1 refuses two revisions of the same repository in one Application ("cannot reference a different revision of the same repository", also hit on a sync retry), so it was dropped. Everything is read from `main`: a chart change shows as OutOfSync in staging/prod and waits for a deployer like an image change.
- **No `ApplyOutOfSyncOnly`:** in the rehearsal a manual sync started right after the root Application changed the child's spec applied from a stale comparison and skipped a changed Deployment; applying every resource (server-side, so unchanged objects are no-ops) is cheap for a chart this size.
- **Hook objects named after a hash of their inputs** (`northline-migrate-<hash>` for the migration's ConfigMap, ExternalSecret/Secret and Job — hash of the api image, profile, environment, Terraform `configEnv`, secret source, seed flag; `northline-oauth-clients-<hash>` — auth image, auth configuration checksum, command). Found in the rehearsal: with server-side apply, Argo CD 3.1 deletes a `BeforeHookCreation` hook and immediately re-applies the same-named object, which lands on the old object still being deleted. Two failures followed: the finished migration Job of the previous sync counted as this sync's migration (`Reached expected number of succeeded pods`), so a sync with a broken database URL went on to roll the Deployments — the S-16 guarantee silently lost; and the hook ConfigMap/Secret vanished right after being "created", leaving the new Job in `CreateContainerConfigError`. With content-hashed names a changed deploy has nothing to delete and always runs a fresh Job; an unchanged re-sync doesn't re-run it (idempotent anyway). Helm behaves as before; old Jobs expire after `ttlSecondsAfterFinished`. Logs by label (`app.kubernetes.io/component=migrate`).
- **External Secrets Operator becomes an Argo CD add-on** (chart 0.20.4 as in S-6; `addons/external-secrets/values*.yaml`, identity per environment in `envs/<env>/addons/external-secrets.yaml`), replacing the manual `helm upgrade` of secrets.md step 3 in GitOps environments.
- **Git host neutrality:** `repoURL` in `env.yaml` is the only place GitHub appears; GitLab (HTTPS or SSH) differs only in that URL and the repository Secret. Argo CD pinned by version in `deploy/argocd/install/kustomization.yaml` (upstream v3.1.1 manifests + `argocd-cm`/`argocd-rbac-cm` patches). Notifications are optional annotations from `env.yaml` (the upstream catalog and a Slack token are documented, not configured).
- **Checks:** `deploy/argocd/validate.sh` (manual CI: GitHub `gitops.yml` `action=validate`, GitLab `PIPELINE_PART=gitops`, also in `all`): app of apps for dev/staging/prod × aws/gcp/azure and local × kind `| kubeconform -strict` against the Argo CD CRD schemas, the policy, the chart rendered with exactly each Application's value files (must refuse without digests, must pass and pin every image with `test-values/promoted-images.yaml`), and the install kustomization.
- **Rehearsed on kind** (`deploy/argocd/kind/rehearse.sh`: kind 0.30 / Kubernetes 1.34, Argo CD 3.1.1 from the Bitnami build re-laid out as upstream's because quay.io is unreachable here, a bare Git repository mounted into the repo server as `file:///gitops/northline.git` standing in for GitHub/GitLab): bootstrap with one `helm template | kubectl apply`, then the root and the app Synced/Healthy by themselves; migration Job done (41 migrations) before the ReplicaSets were created, OAuth client Job last; switching `local` to manual sync through Git left a later change OutOfSync until a manual sync; a broken database URL failed the sync at PreSync with no Deployment/ReplicaSet/ConfigMap changed; a revert brought it back. Not done: Argo CD has not run in any cloud, SSO/Dex groups are placeholders (`northline:platform`, `northline:developers`, `northline:platform-admins`), no image has been promoted (every `images.yaml` is empty on purpose), the ESO add-on was not part of the kind rehearsal (its image is not pullable here), and branch protection / code-owner approval must be switched on in the GitHub or GitLab settings.

- **Merged with S-25 (main):** S-25's Kafka topics hook (`northline-kafka-topics`, PreSync −20/−10 like the migrations) arrived with fixed names, which has the same Argo CD 3.1 hook re-apply problem; its ConfigMap, ExternalSecret/Secret and Job are now `northline-kafka-topics-<hash>` (worker image — which carries `topics.yaml` —, command, the `KAFKA_*` configEnv, replication factor, min ISR, secret source). Logs by label `app.kubernetes.io/component=kafka-topics`.

## 2026-09-30 — S-19 Session management (list and revoke sessions, remove passkeys)

- **Session = one successful sign-in** (`identity.sessions` row; registration counts). `SignInLog.succeeded` now returns its id; the auth server's session authentication carries it as a plain `SESSION_<id>` authority (like the `FACTOR_*` ones it round-trips through Spring Authorization Server's JSON storage of the principal, so a token refresh sees the same id). ID tokens carry it as OIDC `sid`; `GET /bff/session` returns `sid`.
- **Authorizations ↔ sessions:** the `OAuth2AuthorizationService` bean is the JDBC one wrapped by `SessionLinkedAuthorizations`, which on every save links the authorization to its session in **`auth.authorization_sessions`** (V021: `authorization_id` FK → `auth.oauth2_authorization` on delete cascade, `session_id`, `refreshable`) and bumps "last seen". `refreshable = false` once the refresh token is revoked (the BFF's sign-out), so it no longer keeps the session listed. A link table rather than parsing the `attributes` JSON: indexable, and one join for the list.
- **Active** = not ended (`revoked_at` null) and seen within the 12 h idle timeout or holding a live refresh token (mobile apps: 30 days). "Last seen" = auth-server requests (throttled to once a minute by `RevokedSessionFilter`) and token refreshes (≤ every 10 min while the Studio is in use). Listed per session: user agent (the Studio names the device), city, approximate IP (`203.0.113.x`, IPv6 `/48` — never the full address), method, signed in, last active, the apps (client names) holding tokens, current.
- **Revoking** (one, or all others) sets `revoked_at` + `revoke_reason` (`revoked` | `revoked_others` | `signed_out`, V021) and deletes the session's authorizations in the same transaction — refresh tokens and introspection fail from the commit. "All others" also deletes the person's authorizations that aren't linked to a kept session (pre-S-19 ones). Sign-out (`POST /api/auth/sign-out`) now ends the session the same way (`signed_out`).
- **Killing the other copies without a session-store lookup:** the auth server's HTTP session is invalidated on its next request by `RevokedSessionFilter` (servlet filter after Spring Session, before Spring Security; one indexed lookup per signed-in request), so `/oauth2/authorize` can't silently issue a code to it. This works identically with Valkey sessions and the in-memory ones of `local`/`test`, which a "find sessions by principal and delete" approach (`FindByIndexNameSessionRepository`) would not.
- **BFF back-channel = introspection, not OIDC back-channel logout:** the BFF introspects its refresh token (`/oauth2/introspect`, client credentials) at most every `SESSION_CHECK_INTERVAL` (60 s) per session — the time of the last check is in the session, so replicas share it — and ends the session on `active:false`; a refresh answered `invalid_grant` during the relay also ends it (401 `session_ended` instead of a 500). Chosen over OIDC Back-Channel Logout because Spring Authorization Server doesn't send logout tokens, and the client side would need a shared (Redis) OIDC session registry; introspection is one call a minute per active session and needs nothing new on either side. Unreachable auth server = fail open (logged): the refresh still ends the session within the 10-minute access-token life. Access tokens already issued stay valid until they expire (≤ 10 min, stateless JWTs at the api) but only ever existed inside the BFF. **Result: a revoked session loses access within ~1 min, at most 10 min.**
- **BFF tokens now live in the HTTP session** (`HttpSessionOAuth2AuthorizedClientRepository`): Boot's default kept them in an in-memory `OAuth2AuthorizedClientService` — visible to one replica only, and outliving an invalidated session — contrary to what this log says ("keeps the tokens in its session").
- **"Current"** = the auth session's own id plus the BFF's `sid` passed as `?current=` / `{"current"}` (only honoured if it is the caller's open session). After a "Confirm it's you" sign-in the browser has two sessions; both show *This device*, neither is signed out by "all others", and revoking one of them answers `409 current_session` ("sign out" from the account menu instead).
- **Step-up = a second factor used in this auth session within 10 min** (`SESSION_STEP_UP_MAX_AGE`), read from the `FACTOR_*` authorities' `issuedAt`. Otherwise `403 step_up_required` ("Confirm it's you to make this change."). `/api/auth/step-up/passkey|totp` (the payout step-up) now also renews the session's factor time, so the Studio confirms there and retries — no new sign-in row. Applies to revoke, revoke-others and remove-passkey; listing only needs the second-factor session as before.
- **Removing a passkey** (`DELETE /api/auth/security/passkeys/{id}`, CORS now allows DELETE): never the last second factor — another passkey or the authenticator app must remain; backup codes don't count (recovery, not a daily factor) → `409 last_factor`. Serialised per person with `SELECT … FOR UPDATE` on `identity.users`. When the last passkey goes, `mfa_primary` becomes `totp`. Removing the authenticator app isn't offered (the Security tab has no such action yet).
- **Audit + limits:** `developer.audit_log` `auth.session_revoked` (one row per ended session, `reason`) and `auth.passkey_removed` (label), in the change's transaction. New S-9 action `security-change` (REQUESTS: 20/h account, 60/h IP, 20/h session, 15 min lockout).
- **City:** `CLIENT_CITY_HEADER` (e.g. CloudFront-Viewer-City) — copied by `TrustedProxyFilter` only from trusted proxies (URL-decoded, ≤ 100 chars) into `identity.sessions.city` at sign-in. No GeoIP database is bundled.
- **Errors:** new `FlowRejected` reasons `step_up_required` (403), `last_factor` (409), `current_session` (409), `not_found` (404). Messages not in the spec (en server copy in `AuthMessages`, en + fr in the Studio): "Confirm it's you to make this change.", "Add another passkey or an authenticator app before you remove this one.", "That session has already ended.", "That passkey was already removed.", "This is the session you're using now. Sign out instead."
- **Studio:** passkey rows get *Remove* (disabled with the reason when it is the last factor) with a confirmation; the sessions drawer is now "Active sessions" (device · city · IP, signed in · method · last active, apps, *This device* or *Sign out*), a "Sign out all other sessions" button with confirmation, and the previous "Recent sign-ins" history below. A `step_up_required` answer opens a "Confirm it's you" dialog (passkey or authenticator code) and the change is retried; rate-limit, last-factor, already-done and expired-confirmation answers are explained. The dev-auth notice is unchanged.
- **Found and fixed:** token refreshes always failed — the `amr` claim was a JDK immutable list, which Spring Authorization Server's JSON allow-list refuses when it reads the stored authorization back (`roles`/`merchants` are now copied into `ArrayList`s too). Nothing had refreshed a token before this story's tests.
- Tests: `SessionManagementApiTest` (list with current/device/IP/apps, `sid` in the ID token and `?current=`, sign-out ends the session and its refresh token, a revoked session's refresh token → `invalid_grant` and introspection inactive, its auth session can't silently re-authorize and gets 401, current session 409, someone else's 404, revoke-others keeps the current + BFF sessions, step-up after 11 minutes, passkey removal with the authenticator left, last factor 409 then allowed with a second key, someone else's passkey 404, audit rows), `RateLimitApiTest` (`security-change` per user; city header from trusted proxies only), bff `BffSessionRevocationTest` (WireMock `/oauth2/introspect`: active keeps, inactive ends, 503 fails open, client credentials + refresh token sent) and `SessionRevocationCheckTest` (`invalid_grant` during the relay → 401 + invalidated, other errors pass, once per interval); Studio `settings.test.tsx` (current `sid` passed, remove with confirmation and last-factor guard, revoke with step-up then retry, revoke others, 429 message, French).
- Not done: removing the authenticator app; a GeoIP fallback when no ingress header exists; eager deletion of the revoked auth session from Valkey (it dies on its next request or its idle timeout — it can do nothing before); push notification to the signed-out device.
## 2026-09-30 — S-25 Kafka topic catalogue and provisioning (every environment)

- **One catalogue: `deploy/kafka/topics.yaml`.** Topics (`<module>.<aggregate>`, owner module), defaults (6 partitions, 7 days, `delete`), the DLQ policy and the worker's consumer groups with their retry delays. Everything else is derived, identically, by three readers: the Java provisioner (`ca.northline.worker.topics`, packaged into the worker image as `classpath:kafka/topics.yaml`), `scripts/topics.sh` (POSIX awk — the compose one-shot runs in the `apache/kafka` image, which has busybox and no JDK compiler) and Terraform (`modules/kafka/catalogue`, `yamldecode`). The format is flat on purpose so awk can read it. It replaces the hard-coded list in `topics.sh` (same 25 topics).
- **Derived topics:** `<topic>.dlq` (1 partition, 30 days: time to investigate and replay; Event Hubs Premium allows 90), and **per consumer group** `<topic>.<group>.retry-<n>`, one per retry delay (the source topic's partitions so a key stays on its partition number; 1 day). Retry topics are per group because Spring Kafka's retry topics are otherwise shared by every group listening to the topic, and each group would re-consume the others' retries. The DLQ stays shared (`<topic>.dlq`, CLAUDE.md): Spring's DLT headers name the failing group, and replay is safe for the other groups because consumers dedupe on the event id (S-26).
- **Runtime and catalogue can't diverge:** `@RetryableTopic` on each listener spells the same policy (`attempts` = delays + 1, `retryTopicSuffix = ".<group>.retry"`, `SUFFIX_WITH_INDEX_VALUE`, `dltTopicSuffix = ".dlq"`, `autoCreateTopics = "false"`), and `TopicCatalogueTest` fails when the delays Spring derives from `@BackOff`, the topics, the group or the suffixes differ from the catalogue entry, or a listener has no entry. The search indexer moved from 5 attempts at 1 s × 3 (shared `-retry-*` topics, auto-created) to 10 s / 60 s / 5 min on its own retry topics.
- **Provisioning = Kafka admin API, no Strimzi/operators:** works on MSK (SASL/SCRAM), Google Managed Kafka (SASL/PLAIN) and local Kafka with the app's own credentials. `TopicsCommand plan|verify|apply` (a Kafka-auto-configuration-only Spring context reading the worker's `spring.kafka.*`, so the connection settings are the worker's; no database/Valkey/Elasticsearch). `apply` creates what is missing and sets drifted `retention.ms` / `cleanup.policy` / `min.insync.replicas` back; partition drift (raising partitions remaps keys and breaks per-aggregate ordering), replication factor and unmanaged topics are **reported, never changed**; nothing is ever deleted. Exit 3 = drift left in `verify`.
- **Every environment:** a Helm pre-install/pre-upgrade hook Job `northline-kafka-topics` (worker image, Argo CD PreSync, hook-scoped ConfigMap + Secret like the S-16 migrations Job) runs `apply` before any pod starts. Replication = the broker default unless `kafkaTopics.replicationFactor` (MSK 3, 2 on the two-broker dev; Managed Kafka 3); prod sets `min.insync.replicas=2` (the api produces with `acks=all`). Locally: the compose one-shot (`scripts/topics.sh`, now parallel `--create` calls: ~1 min the first time, one `--describe` after) or `./gradlew :worker:kafkaTopics`.
- **Azure Event Hubs = Terraform:** an event hub is an ARM resource and creating it through Kafka's CreateTopics needs a *Manage* connection string in a pod. `modules/kafka/azure` now creates one `azurerm_eventhub` per catalogue topic (partitions, `retention_time_in_hours`, cleanup policy) with `prevent_destroy` (removing an entry must never drop events; an environment teardown first `state rm`s them — runbook). The contract stays one interface: all three kafka implementations gained the same `topics` input (AWS and Google Cloud ignore it: their topics come from the Job), and only the Azure `cloud` output adds `event_hubs`. The Job runs `plan` on Azure (report only; `values-azure.yaml`). A Terraform path for Google Cloud (`google_managed_kafka_topic`) was not added: the admin API works there, and two creators for one cluster would fight over settings.
- **Limits checked:** the catalogue derives 59 topics; `TopicCatalogueTest` fails above 100 (Event Hubs Premium per processing unit — dev/staging run 1 PU), on names Event Hubs refuses, or on retention above 90 days. Adding a consumer costs `topics × delays` event hubs, which is why delays are few (3).
- **Guards:** api `ExternalizedTopicsCatalogueTest` scans every `@Externalized` event (records nested in sealed interfaces included) and fails when its topic is missing from the catalogue. Worker `TopicCatalogueTest` compares `topics.sh --list` with the Java derivation; `TopicProvisionerTest` (Kafka `apache/kafka:4.1.0` in Testcontainers) runs `topics.sh` inside the Kafka image then the provisioner: create, drift lines and strict exit, plan changes nothing, apply creates the missing and corrects config, idempotent re-run, partition drift and unmanaged topics left alone, the command's exit codes and `min.insync.replicas`. Terraform: `modules/kafka/catalogue/tests` and the Azure dev env test (event hubs from the catalogue). `deploy/helm/validate.sh` checks the Job's command per cloud.
- **Not done:** Kafka ACLs per app (MSK still `allow.everyone.if.no.acl.found=true`); the api's own `acks=all` producer does not set `min.insync.replicas` semantics for the Modulith outbox beyond the topic config; the Event Hubs Kafka endpoint's `DescribeConfigs` behaviour with a Send/Listen key is untested (reported as `UNREAD` when refused); no real cloud cluster has run the Job yet.

## 2026-09-30 — S-26 Worker consumer framework: dedupe, retries, DLQ, replay (server/worker `ca.northline.worker.events`)

- **Envelope on the wire = Kafka headers + the event's own JSON.** ARCHITECTURE.md's envelope (id, type, version, occurredAt, aggregate, actor, traceId, data) is realised without wrapping the payload: `occurredAt`, `aggregateId`, `actorId` are already in every event record, and the rest travels as headers set by the api (`config.EventHeaders` via an `EventExternalizationConfiguration` = Modulith's defaults + headers): `nl-event-id`, `nl-event-type`, `nl-event-version`, plus W3C `traceparent` from the Kafka template observation (now on). Chosen over a wrapper object because the schemas (and S-34) describe the flat payload and the api's externalization stays Modulith's own. **Type** = `<topic module>.<snake_case record name>`, matching the schema file names; four food events whose schemas are named otherwise carry the new `shared.EventType` annotation (`food.item_availability`, `orders.order_handed_off|accepted|ready`). `EventHeadersTest` fails when an externalized event has no `events/<type>.v<version>.schema.json`; `EventsOnKafkaTest` checks the real path (outbox → externalizer → Kafka 4 in Testcontainers): key, headers, JSON.
- **Schema validation in the worker:** the api's schema files are packaged into the worker (`classpath:events/`), and `EventSchemas` validates with a small in-house validator for exactly the keywords the files use (type incl. arrays of types, required, properties, additionalProperties, enum, pattern, format date-time, minimum, min/maxLength, items) — the approach `LegalDetailsSchema` already takes in the api, instead of a library that would bring Jackson 2. A schema using any other keyword stops the worker at start-up (and the tests), so nothing is silently unvalidated.
- **Poison = not retryable:** missing/invalid headers, not JSON, unknown type or version, schema violation, header/payload id mismatch → `PoisonEventException`, listed in every listener's `@RetryableTopic(exclude, traversingCauses)`, so it goes straight to `.dlq` (counted `outcome=poison`).
- **Dedupe semantics of `events.processed_events` (shared with the api):** `(consumer group, event id)` is inserted in the **same transaction** as the handler's own writes — a handler failure rolls the claim back, so retries work, and a duplicate (Kafka redelivery, outbox republish, DLQ replay) is skipped. Channel claims `(email|sms|push, <eventId>:<userId>)` keep S-13's meaning (claimed before sending, in their own transaction) and are what S-27 uses on top, so the api and the worker can never both notify; `(dlq-replay, <dlq>:<partition>:<offset>)` records replays. The worker purges claims older than 60 days nightly (V019: index on `processed_at`, foundation range) — longer than every topic's retention and the api's outbox resubmission window.
- **DLQ ownership:** the `.dlq` is shared per topic (S-25), so each group's `@DltHandler` reads it all and acts only on records whose `kafka_dlt-original-consumer-group` is itself (or its `-retry-n` endpoints): ERROR log `DEAD-LETTERED …` + counter `northline.events.dead_lettered{consumer,topic}` = the alert. Spring Kafka 4 writes `kafka_original-topic` / `kafka_exception-*` (not the `kafka_dlt-*` names older docs show) — found by the tests.
- **Replay tool** `DlqReplayCommand list|replay --topic=<t>.dlq --group=<g> [--event=] [--limit=] [--force]` (Gradle `:worker:dlqReplay`, or a one-off Job with the worker image — runbook): reads the DLQ without a consumer group (commits nothing, never modifies it), republishes the group's records to the **original topic** with key, value and `nl-*` headers (+ `nl-replayed-from`). Every group sees the replay; those that already processed the event skip it through their dedupe claim, which is what makes replaying to the shared topic safe and keeps the tool simple (no per-group retry-topic surgery). A replayed record is claimed, so a second run doesn't replay it again.
- **Operational settings (worker):** String/byte[] (de)serializers (the parser owns JSON), `ack-mode: record` (a crash redelivers at most the record in hand), `auto-offset-reset: earliest`, idempotent `acks=all` producer for retry/DLQ records, graceful shutdown (container `shutdownTimeout` 20 s via a `ContainerCustomizer`, `server.shutdown: graceful`, lifecycle phase 30 s, pod grace 45 s), MDC `[consumer|eventType|eventId|traceId]` in every log line, Prometheus endpoint on the health port with consumer lag (`kafka.consumer.fetch.manager.records.lag.max`) and outcome counters.
- **Guard extended:** `TopicCatalogueTest` also requires each listener's `exclude = PoisonEventException`, `traversingCauses`, an exponential back-off (a fixed one makes Spring name a single retry topic without `-0`, which the catalogue wouldn't match) and a `@DltHandler`.
- **Tests:** `ConsumerFrameworkTest` (the whole worker on Kafka 4 + a migrated PostGIS, two scripted test groups): duplicate delivery processed once per group; four concurrent deliveries → one handler run; transient failure retried on the group's own retry topic only; persistent failure → retry-0, retry-1, `.dlq`, claim rolled back, alert counted once (the other group ignores it), then `list` + `replay` through the command → processed by the failed group only, second replay `ALREADY_REPLAYED`; poison straight to the DLQ with its cause header; lag meter and shutdown timeout. `EnvelopeParserTest`, `EventSchemasTest` (every schema loads; each poison reason). api `EventHeadersTest`, `EventsOnKafkaTest`.
- **Not done:** the search projection itself (S-42/S-43 — the indexer now runs through the framework with a no-op handler); Prometheus scraping/alert rules as code (the runbook lists the queries; S-111–S-113 own observability); tracing export from the worker (the trace id is logged, not exported); S-34 (CI check that payloads match schemas without a version bump) — the worker's runtime validation is the safety net until then.

## 2026-09-30 — S-27 Notifications consumer (email, SMS, push stub) — worker, shared `server/sms`, api invitations

- **Who owns what (nothing sends twice):** the S-13 emails stay in the api (after-commit module listeners, already tested, and moving them would only add a Kafka hop); the worker's new consumer group `notifications` (topics `payments.payout`, `payments.payout_account`, `payments.dispute`, `payments.refund`; retries 10 s / 60 s / 5 min, then `.dlq`) sends what S-13 left open: **SMS and push** for payout, dispute and refund events, the **bank-change SMS** (security: always, whatever the matrix, even in quiet hours — the design's "we email and text you") and the **`payout.failed` email** (new `payout-failed` template, en/fr, row `payout`, `NOTIFICATION` with one-click unsubscribe). Every delivery claims `(channel, <eventId>:<userId>)` in `events.processed_events` before the provider is called — the key the api's `Mailer` uses for email — so even a future move of an email from one app to the other can't double-send. Table of events × roles × channels: `docs/runbooks/notifications.md`.
- **SMS team invitations are sent by the api, not the worker:** the invitation link's token exists only in the api (its hash is stored; S-13 accepted it in the outbox) and putting it on Kafka would copy a bearer token into every consumer group's retention for 7 days. `TeamInviteSenders` (merchants) now texts mobile invitations after commit through the shared SMS library, claimed once per delivery (`sms`, event id); `sent` is now `true` for mobile invitations (the dialog still shows the link). Copy (ours): "Northline: {inviter} invited you to join {business} as {role}. Accept by {date}: {link}" / « Northline : {inviter} vous invite à rejoindre {business} comme {rôle}. Acceptez d’ici le {date} : {lien} ».
- **Shared SMS library `server/sms`** (package `ca.northline.sms`), extracted from S-8 the way S-13 extracted `server/email`: the `SmsTransport` port (`sendText`, `call` in the language's Polly voice), the Twilio REST and AWS End User Messaging adapters, the logging fake, `SmsTransports` (selection by `northline.sms.provider` with S-8's exact rules and messages) and `SmsTransportConfiguration` (imported explicitly by the api and the worker — not an auto-configuration, so northline-auth's own wiring is untouched). **northline-auth keeps its port** (`SmsSender.sendCode`, its `SmsDeliveryFailed`, `CodeMessages`, `SmsConfig` with the same factory methods, `TwilioSmsSender`/`AwsSmsSender`/`LoggingSmsSender` class names): the two provider senders are now thin `TransportSmsSender` adapters over the library, so its S-8 tests run unchanged (`git diff` shows no auth test change) and still verify the exact Twilio/AWS requests.
- **Contact details: worker-side read of the api's tables** (`JdbcRecipients`: `identity.users` with the same "active only" rule and name/locale columns as `identity.api.NotificationContacts`, `merchants.merchant_members`, `merchants.merchants`, `messaging.notification_prefs`), read-only, at send time. Chosen over an api read model over HTTP: no service-to-service credentials exist, it avoids a network hop per event and a new endpoint exposing contact details, and it matches how northline-auth already shares the database. Events keep carrying ids only. The Settings › Notifications defaults moved into a spec file (`docs/spec/notification-matrix-defaults.json`) that the worker reads and an api test compares with `NotificationMatrix` — one table, two readers.
- **Quiet hours** (stored per member, America/Edmonton) hold back **push and SMS**; email is never held (S-13). Held notifications go to the new `messaging.deferred_notifications` (V074, messaging range; written only by the worker; payload = the event itself, no personal data) inside the consumer's transaction, and a job (every minute, `for update skip locked`, replicas share it) sends them when the quiet hours end, after re-reading the member and their matrix (a cell turned off meanwhile cancels it); provider outages are retried every 5 minutes and given up after 10 attempts with an ERROR alert. Chosen over dropping them (a dispute text at 22:00 would be lost for members who turned email off).
- **Failures:** undeliverable number / rejected address → logged, claim kept, not retried; provider unavailable → that claim released, the other members still served, then the event is retried through the consumer's retry topics (only missing deliveries go out), then `.dlq` (replay with `DlqReplayCommand --group=notifications`). The worker's in-process email retries are shortened (2 × 1 s) because Kafka retries on top.
- **Push = documented stub:** no provider or device registration exists; `PushSender.LOGGING` records the push after the matrix, quiet hours and claims have applied, so a real adapter only replaces one bean.
- **Unsubscribe tokens** moved into the email library (`ca.northline.email.UnsubscribeTokens`, same format and dev-key rules) so the worker signs links the api's unsubscribe endpoint verifies; the api's `UnsubscribeTokens` delegates to it.
- **Configuration:** the worker now needs the email and SMS variables (staging/prod: `EMAIL_PROVIDER, EMAIL_FROM, EMAIL_UNSUBSCRIBE_KEY, API_PUBLIC_URL, STUDIO_ORIGIN`, `SMS_PROVIDER, SMS_FROM`), the api the SMS ones (`SMS_PROVIDER, SMS_FROM` in staging/prod). Chart: worker `secretEnv` + cloud overlays' `EMAIL_PROVIDER`, api `SMS_AUTH_TOKEN`; runbook matrices updated. Catalogue: consumer `notifications` (+12 retry topics → 71 topics, within one Event Hubs Premium PU).
- **Tests:** worker `NotificationsConsumerTest` (Kafka 4 + migrated PostGIS; recording email/SMS/push, a movable clock): exactly one email and push per finance member in their language, none for technicians, redelivery sends nothing; each member's matrix; quiet hours defer SMS/push (not email) and the job sends them once in the morning; the bank-change SMS at night whatever the matrix; an outage retried with only the missing text sent; a provider that stays down → DLQ with claims released; an undeliverable number not retried. `TopicCatalogueTest` now reads property-based back-offs (`${…:default}`). api `TeamInvitationEmailTest` (mobile invitation texted once, French), `NotificationMatrixDefaultsSpecTest`; email library renders `payout-failed` in both languages; `server/sms` `SmsTransportsTest`, `TwilioSmsTransportTest`; auth's S-8 tests unchanged.
- **Not done:** rows whose events don't exist on Kafka yet (`new_booking`, `quote_request`, `customer_message`, `low_stock`, `quality`); a real push provider and device registration; per-member time zones (quiet hours use Edmonton); SMS delivery-status callbacks; Azure Communication Services SMS (still reserved).

## 2026-09-30 — S-18 Google and Apple sign-in

- **Registrations from `northline.auth.federation.*`** (`GOOGLE_CLIENT_ID/_SECRET`, `APPLE_CLIENT_ID`, `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY`), built by `FederationRegistrations` — only the providers that have a client id. They are no longer Boot `spring.security.oauth2.client.*` registrations: Boot refuses an empty client id, and the placeholders registered providers that could never work. Staging/prod require all six (`northline.required-env.federation`); dev/local may leave either off. An unconfigured provider's button lands on `/sign-in?error=federation_unavailable` ("…isn't available right now…") instead of a 500 (`KnownProvidersOnly` resolver + `UnavailableProviderController`). Redirect URI `{baseUrl}/login/oauth2/code/{registrationId}` (the public auth URL, via TrustedProxyFilter's forwarded headers). Helm/Terraform: the secret `apple-client-secret` became `apple-private-key`; staging/prod values map `GOOGLE_CLIENT_SECRET` and `APPLE_PRIVATE_KEY` as required.
- **Apple client secret generated, never pasted:** `AppleClientSecret` signs the ES256 JWT (`iss` team id, `sub` Services ID, `aud` `https://appleid.apple.com`, `kid` key id) with the `.p8` key (JDK `SHA256withECDSAinP1363Format`, no extra library), lives `APPLE_CLIENT_SECRET_TTL` (30 days, capped at Apple's 180) and is re-made once a quarter of its life is left; Apple's registration is rebuilt with the current secret on every lookup, so nobody renews anything and there is no 6-month cliff. A missing or malformed key stops start-up listing every problem.
- **Apple's form POST:** with `name`/`email` scopes Apple answers with `response_mode=form_post`, a cross-site POST that doesn't carry the SameSite=Lax `NL_AUTH` cookie. Authorization requests are therefore kept by `state` in **V022 `auth.federation_requests`** (JDK-serialised, class allow-list on read, 10 min, deleted on use, shared by replicas) instead of the HTTP session; the callback then starts a fresh auth session, which is fine at sign-in. The name comes from Apple's `user` form field (sent only on the first authorization).
- **ID tokens** are verified by Spring's OIDC provider (JWK set, `aud`, nonce, expiry) plus an issuer check per provider — Google issues both `https://accounts.google.com` and `accounts.google.com`, so its registration has no single issuer URI and the allowed list is configuration.
- **Outcomes** (`FederatedSignInService`): an already linked provider account (`auth.federated_identities`, V022, key = provider + `sub`) → the factor step of that account; a **verified** email of an existing account → the factor step with "Confirm it's you … to link your Google account", and the link is written only after that second factor succeeds in the same browser (`FederatedLinking`, called from the sign-in and registration transactions; another account signing in drops it; audit `auth.federated_linked`); anything else → "Create account" pre-filled (names, verified email only), phone code + second factor per validation-rules.md, and the new account is linked. A federated login never becomes the session and never gives `acr=mfa`, so business accounts still need their passkey/authenticator. Unverified emails never match an account.
- **Apple private relay** (`is_private_email`): the `…@privaterelay.appleid.com` address is pre-filled and the Studio explains it ("Your email is Apple's Hide My Email address…"); mail reaches it only once the sending domain is registered with Apple (runbook). Linking is by `sub`, so the relay address never needs to match anything.
- **Errors → Studio:** `access_denied` / `user_cancelled_authorize` → `federation_cancelled`; token/userinfo/client errors → `federation_unavailable`; everything else (expired or forged `state`, bad signature) → `federation`; S-9 lookup limits → `rate_limited`. New en/fr copy: "Signing in with Google or Apple was cancelled…", "…isn't available right now. Use your email or mobile instead.", "Confirm it's you with your passkey, authenticator app or a backup code to link your {provider} account.", "You're signed in with {provider}. Add your mobile number and a second factor to finish creating your account.", "Your email is Apple's Hide My Email address: messages from Northline reach you through it." (`/sign-in` and `/register` read `provider`, `link`, `relay`).
- `identity.sessions.method` keeps the factor that signed the person in (passkey/totp/backup_code/registration); `google`/`apple` stay unused — the provider only picked the account.
- Tests: `FederatedSignInTest` against WireMock stand-ins of both providers (RS256 ID tokens from their own JWK sets, Google userinfo): new person → pre-filled register → linked to the new account; verified email → factor step → linked only after TOTP → next time straight to that account even with a changed Google email; another person signing in drops the pending link; unverified email doesn't match; cancel → `federation_cancelled`; a token signed by another key → `federation`; Apple form POST without the session cookie, `user` name, private relay, and the client secret JWT (claims + signature checked against the key) on the token request; forged `state` → `federation`. `AppleClientSecretTest` (renewal at ¾ life, 180-day cap, every problem listed), `FederationUnavailableTest`, `FederationStartupTest` (staging requires the six variables, dev doesn't); Studio `auth.test.tsx` (errors en/fr, linking notice + factor step, Apple relay pre-fill, button targets).
- Not done: unlinking in the Studio (SQL in the runbook); Google/Apple for the consumer app (no consumer BFF yet); Apple server-to-server notifications (account deleted / email relay changed); nothing has run against the real Google/Apple yet — the "works in staging" acceptance needs the console set-up in `docs/runbooks/federation.md`.

## 2026-09-30 — S-28 user.registered from registration (northline-auth outbox)

- **Decision: northline-auth gets its own transactional outbox**, not "the api emits it when it first sees a user". The account is created by auth (`RegistrationService`), so only auth can publish exactly once *with* the registration: the api only sees a user when their first token reaches it, which may be never (abandoned onboarding) or twice (two replicas racing), and would need its own dedupe table to fake exactly-once. Auth now does what the api does (ARCHITECTURE.md § Event management): `ApplicationEventPublisher.publishEvent` inside the registration transaction → Spring Modulith's JDBC event publication registry (the outbox) → after commit, `@Externalized` to Kafka.
- **Registry in the `auth` schema** (`auth.event_publication` + `_archive`, **V023**, same shape and indexes as the api's `events.*` from V017/V018; `spring.modulith.events.jdbc.schema: auth`, schema initialisation off, `completion-mode: archive`, incomplete publications republished at start-up). Not the api's `events.event_publication`: both apps republish outstanding publications at start-up and the externalizer's listener id is the same class in both, so each would pick up the other's rows and fail to deserialise event types it doesn't have.
- **Event** `ca.northline.auth.application.UserRegistered(eventId ULID, occurredAt, aggregateId = identity.users.id)` — ids only, no name/email/phone (a test checks the Kafka value contains none of them). `@Externalized("identity.user::#{aggregateId()}")`: topic **`identity.user`**, already in the S-25 catalogue (`deploy/kafka/topics.yaml`, owner `identity`, now commented as published by northline-auth), key = user id. Schema `server/api/src/main/resources/events/identity.user_registered.v1.schema.json` next to the others (the event type is `user.registered`). Published right after the `identity.users` insert, before the sign-in log, second factor rows and the federated link (S-18) — any later failure rolls all of it back, event included.
- **Exactly once:** one outbox row per committed registration; Kafka delivery is at-least-once (idempotent producer, `acks=all`; a crash between send and completion, or a start-up republish, can resend), so consumers dedupe on `eventId` as everywhere (`events.processed_events`).
- **Configuration:** auth now reads `KAFKA_BOOTSTRAP` (required in the cloud profiles, `northline.required-env.events`), `KAFKA_SECURITY_PROTOCOL`, `KAFKA_SASL_MECHANISM`, `KAFKA_SASL_JAAS_CONFIG` (Helm: optional `secretEnv` for auth), same producer settings as the api. `local` and `test` keep externalization off, like the api (no listener: nothing is recorded or sent; with Kafka from the compose `events` profile, `--spring.modulith.events.externalization.enabled=true` sends it). A registration never waits for Kafka: externalization runs after commit.
- Tests: `UserRegisteredEventsTest` (real Kafka `apache/kafka:4.1.0` via Testcontainers with auto-creation off and the topic provisioned first): one registration → exactly one publication (archived, completed) and exactly one record on `identity.user` keyed by the user id whose value holds only `eventId`, `occurredAt`, `aggregateId`; a registration whose transaction fails after the event (the sign-in log throws) leaves no account, no publication and no record. `ExternalizedTopicsCatalogueTest` (auth's `@Externalized` topics are in the catalogue and have a schema).
- Not done: no consumer yet (the S-27 notifications worker can subscribe to `identity.user` for a welcome message); the api's `ExternalizedTopicsCatalogueTest` only scans the api, so auth has its own copy.

## 2026-09-30 — S-17 DNS, TLS and edge (Gateway API, cert-manager, external-dns, WAF options)

- **Gateway API with Envoy Gateway is the default edge on every cloud**, not ingress-nginx (retired by Kubernetes SIG Network in March 2026: no more releases or security fixes) and not the clouds' own L7 load balancers (each has a different TLS/header/redirect model, and AWS/Azure need an extra controller). The cloud only provides the layer-4 load balancer of the Envoy Service (EnvoyProxy per cloud in `deploy/argocd/addons/envoy-gateway/manifests/<cloud>`: EKS in-tree NLB, GKE passthrough NLB, AKS Standard LB; `externalTrafficPolicy: Local` to keep client addresses). TLS, redirects, HSTS and TLS policy are then identical on EKS, GKE, AKS and kind. The chart keeps the `Ingress` path (same certificates, HTTP-01 through the Ingress class) and HTTPRoutes on an existing Gateway (`gateway.parentRefs`) for other set-ups.
- **The chart owns the environment's Gateway** (`edge.gateway.create`, one environment per cluster): an HTTP listener that only redirects (301, port 443) and carries cert-manager's HTTP-01 challenge routes, and one HTTPS listener per host, each with its own Secret. App routes attach to their host's HTTPS listener by `sectionName`, so nothing is served over plain HTTP and a request for an unknown host fails the TLS handshake (no default certificate).
- **Hosts:** consumer on the zone apex, `studio.` (the backlog's name — replaces the draft `business.`, which was never deployed; S-20 renamed the last sample in `OAuthClientCatalogTest`), `auth.`, `api.` (Stripe webhooks and unsubscribe only, as S-14 decided), `pages.` (storefronts + merchant domains, served by the consumer app), `console.` (no route until E-8). New `urls.pages`. Zones stay one per environment (`dev.northline.ca`, `staging.northline.ca` delegated from `northline.ca`), with the passkey RP id = the zone.
- **Certificates: cert-manager + Let's Encrypt, one Certificate per host over HTTP-01 by default** (no cloud credentials, works behind any DNS), ECDSA P-256, new key on renewal, renewed 30 days early. **DNS-01 is an option** (`edge.certManager.issuer.solver: dns01`, required for `wildcard: true`): Terraform outputs the cloud's solver block (`helm_values.edge.certManager.issuer.dns01`) and cert-manager uses its workload identity. The Issuer is namespaced (the app project allows no cluster-scoped kinds) and cert-manager runs with `--issuer-ambient-credentials` for that — acceptable with one environment per cluster. Staging/prod refuse non-ACME issuers; `ca`/`selfSigned` exist for kind.
- **HSTS and headers as Gateway API `ResponseHeaderModifier` filters** on every route rule (`max-age=63072000; includeSubDomains`, no `preload` until every subdomain is HTTPS for good; `nosniff`, `Referrer-Policy`), portable across implementations. CSP and frame options stay with each app (the Studio's nginx sets them). TLS 1.2 minimum and trusted proxy hops (for a CDN/WAF in front) through Envoy Gateway's `ClientTrafficPolicy`, rendered only for the `envoy` class.
- **external-dns publishes the HTTPRoute hostnames** (source `gateway-httproute`, `policy: sync`, TXT ownership `_extdns.` prefix, owner `northline-<env>`, zone filter from Terraform); it never touches records it didn't create (NS/SOA/CAA/MX/SPF/DKIM stay manual). Merchant domains are outside its zone filter and their routes are annotated `controller: none`.
- **Terraform (all three clouds, contract unchanged in shape):** `modules/dns/*` take `record_writers` (principals allowed to change records in that zone only: Route 53 inline role policy on the zone ARN + zone listing; Cloud DNS `roles/dns.admin` on the managed zone + `roles/dns.reader` on the project; Azure "DNS Zone Contributor" on the zone) and output `cert_manager_dns01` and `external_dns` (the cloud-specific parts: zone id / project / `azure.json` with ids only, workload identity for auth). Stacks add workload identities `external-dns` and `cert-manager` and an `edge` output; env roots add `helm_values.edge` and **`gitops_addon_values`** (identities of external-secrets, external-dns, cert-manager + external-dns zone settings), asserted in every env root's `terraform test`.
- **Argo CD add-ons:** `envoy-gateway` (OCI chart from Docker Hub — the app of apps renders the `enableOCI` repository entry, so the gitops project may now hold Secrets in `argocd`; plus GatewayClass/EnvoyProxy manifests from the repo as extra sources, `{cloud}` substituted), `cert-manager`, `external-dns` (wave −5, after the Gateway API CRDs exist). The app project whitelists Gateway, ClientTrafficPolicy, Issuer, Certificate.
- **WAF is an option, not a dependency** (documented per cloud in edge.md): Cloudflare in front (any cloud, Full (strict) with our Let's Encrypt origin certificates), CloudFront + AWS WAF (AWS WAF can't attach to an NLB), Cloud Armor via GKE Gateway (`gke-l7-global-external-managed`, since Cloud Armor needs an Application LB), Azure Front Door Premium WAF. Each needs `edge.trustedProxyHops: 1`. Not in Terraform yet.
- **Custom domains:** `edge.customDomains` (by PR, after S-31 verified the CNAME to `pages.<zone>`) → listener + HTTP-01 Certificate + route to the consumer app. Documented limits: apex domains need ALIAS or a reserved IP; a Gateway holds at most 64 listeners, so beyond the pilot use several Gateways, `ListenerSet`, or CDN on-demand TLS (Cloudflare for SaaS, CloudFront SaaS Manager, Front Door) — to decide with S-31.
- **Rehearsed on kind** (the S-15 cluster; cert-manager 1.18.2 from the Bitnami builds because quay.io is unreachable here, Envoy Gateway 1.5.1 / Envoy 1.35.3 from Docker Hub, a local CA issuer): Argo CD synced the edge; Issuer and Certificates Ready; `https://auth.kind.northline.test` served the OIDC document over HTTP/2 with the host's certificate, HSTS, `nosniff`, `Referrer-Policy`; TLS 1.2/1.3 accepted, TLS 1.1 refused by the server; HTTP → 301 `https://…`; `/actuator/health` on the api host 404 at the Gateway; unknown SNI → no certificate. Not done: Let's Encrypt, external-dns against a real zone, cloud load balancers and WAFs (no accounts yet); the add-on charts themselves were not rendered offline (their chart repositories are unreachable here — the kind rehearsal used the same versions' release manifests).

## 2026-09-30 — S-21 Stripe Tax sync into payments.tax_jurisdiction_totals (payments)

- **Port:** `payments.application.TaxGateway` (calculate · record a sale from a calculation · partial reversal ·
  read a transaction's tax), chosen by `northline.tax.provider` (`TAX_PROVIDER`): `local` (default; fixed 2026
  Canadian rates from `domain.CanadianTax`, stateless — its ids carry the amounts) or `stripe` (`StripeTaxGateway`,
  stripe-java on the platform account with `STRIPE_SECRET_KEY` / `STRIPE_API_BASE`). `local` is refused at start-up
  under staging/prod, where `TAX_PROVIDER` joins the required variables (`tax:`) and the Helm values set `stripe`.
  **Never run against a real Stripe account:** the adapter is written from Stripe's API reference and tested with
  stripe-mock (`StripeTaxGatewayStripeMockTest`: every call validated against the pinned spec, `nl1:` Idempotency-Key
  on every POST, pinned `Stripe-Version`).
- **Where tax is calculated:** at checkout. New `payments.api.TaxCalculations.calculate(merchant, kind, province,
  postal code?, amount)` returns a quote (tax, lines per tax type, jurisdiction, expiry); `PaymentAuthorizations.Request`
  gains an optional `taxCalculationId` (amounts must equal the quote's → 422 "The tax doesn't match its calculation.
  Calculate it again."; a quote prices one job / order line → 409 `tax_calculation_used`; other merchant's quote →
  422 "Calculate the tax again."). The old 8-argument constructor stays (no calculation). Place of supply = the
  province the caller passes (job address, delivery address, or the kitchen for pickup); Stripe gets country `CA`,
  province and (at checkout only) the postal code with `address_source=shipping`. Only the province is stored.
  Messages ours: "Choose a Canadian province or territory.", "Enter a Canadian postal code, like T2P 1B5.",
  "Enter an amount.". Nothing calls `TaxCalculations` yet (the consumer checkout doesn't exist — same as S-11's
  `PaymentAuthorizations`).
- **At capture (charge):** `EscrowService.capture` stores a `pending` sale in `payments.tax_transactions`
  (`reference = sale_<escrowId>`, unique) in the capture's transaction and publishes the internal
  `TaxSyncRequested` (outbox). A sale without a checkout quote (the caller computed `taxCents`) or whose quote expired
  (Stripe keeps them 90 days; we recalculate 1 h before) is priced again at capture for the same province — the
  quote's, else `merchants.merchants.province`, else AB — and recorded from that calculation. Escrows with 0 tax and
  no quote report nothing.
- **Sync:** `TaxSyncListener` (`@ApplicationModuleListener`) reports right after commit; the payments job `syncTax`
  (every minute, ≤ 10 attempts) retries `pending`/`failed` rows; each report runs in its own transaction under
  `SELECT … FOR UPDATE`, so listener, job and reconciliation never report one twice, and Stripe calls use keys derived
  from the reference (`nl1:tax-transaction:<ref>`, `…tax-reversal…`, `…tax-calculation…`), so a retry after a crash
  repeats the same call. A reversal first reports its sale (same transaction) and fails (retried) until it can.
  The Studio's number is **what Northline collected** (`tax_cents` = the escrow's / refund's tax, which the ledger's
  `tax_payable` follows); Stripe Tax's own figure is stored as `stripe_tax_cents` and a difference is logged.
- **Read model:** after each report `payments.tax_jurisdiction_totals` is recomputed for (merchant, quarter,
  jurisdiction) from the recorded transactions — idempotent, under an advisory lock. Quarter = Edmonton calendar
  quarter of the capture / refund (a refund in a later quarter reduces that later quarter, as a GST credit note
  does). Jurisdiction codes extend V082's: `<province>_<taxes>` — `ab_gst`, `bc_gst_pst`, `mb_gst_pst`, `nb_hst`,
  `nl_hst`, `ns_hst`, `nt_gst`, `nu_gst`, `on_hst`, `pe_hst`, `qc_gst_qst`, `sk_gst_pst`, `yt_gst`; handling
  `remitted_by_northline`. V082's CHECK keeps `collected_cents ≥ 0`, so collected = max(0, base + sales − reversals)
  and the parts live in the companion table `payments.tax_totals_sync` (V082 sorts after V064, so its table can't be
  altered from our range). A row that existed before the sync touched it (dev seed V107) keeps its amount as
  `base_cents`; `platform_fee_gst` and `not_selling` rows are never written by the sync.
- **Refunds reverse tax** (closes S-41's "refunds don't reverse GST on the original sale"; S-41's PDF statements are
  still open): `refunds.tax_cents` = the refunded share of the sale's tax (half-up; refunding the whole amount gives
  back exactly the tax collected), fixed when the case opens. The refund queue refunds `amount + tax` to the card
  (the transfer reversal from the merchant is unchanged: the merchant only ever funds the pre-tax amount), the ledger
  posts `tax_payable` debit + merchant/escrow debit = `stripe_balance` credit, and a `refund_<id>` reversal
  (`mode=partial`, `flat_amount = −(amount + tax)`) goes to Stripe Tax. A **lost chargeback** now debits
  `tax_payable` for its tax part (up to the sale's tax; `revenue` only for anything beyond) and reports
  `chargeback_<disputeId>`. Goodwill credits and holds canceled before capture report nothing; refunds of sales
  captured before S-21 move the ledger but report no reversal (no sale at Stripe Tax), logged.
- **GST summary CSV** gains "GST/HST refunded"; "Remitted by Northline" is now collected − refunded.
- **Reconciliation:** `ReconcileTax` — reports everything still pending (whatever its attempts), compares each
  recorded transaction of the quarter with Stripe Tax's line items (`reconciled_at`, `stripe_tax_cents`, mismatch
  logged), rebuilds the quarter's rows; returns `{period, reported, checked, mismatched, rows, stillPending}`. Nightly
  at 03:17 Edmonton for the current and the previous quarter (`TAX_RECONCILE_CRON`), and on demand for staff:
  `POST /api/v1/console/payments/tax-reconciliations {period?}` (role `staff` via `/api/v1/console/**`, plus
  `acr=mfa` → else 403 `mfa_required`; bad period → 422 "Use a quarter like 2026-Q3."). It is the first
  `/console` endpoint; `TestJwt.staff` was added for it.
- **Studio:** Stripe & compliance names every new jurisdiction (en + fr-CA: TPS/TVH/TVP/TVQ/TVD).
- **Schema V064:** `payments.tax_calculations`, `payments.tax_transactions`, `payments.tax_totals_sync`,
  `refunds.tax_cents`. Like V062/V063, V064 sorts before V070–V091: a database migrated before it needs Flyway's
  `outOfOrder` once (fresh databases and the tests are unaffected).
- **Config:** `TAX_PROVIDER`, `TAX_CODE_SERVICE` (`txcd_20030000`), `TAX_CODE_GOODS` (`txcd_99999999`),
  `TAX_CODE_FOOD` (`txcd_40060003`), `TAX_RECONCILE_CRON`; runbooks (README, dev/staging/prod, local, stripe.md § 6),
  `.env.example`, Helm staging/prod values. No new secret.
- **Not done:** checkout callers of `TaxCalculations` (consumer app); the product tax codes and registrations need
  the accountant's confirmation (defaults are Stripe's general codes); Stripe Tax filing/exports; GST on Northline's
  own fee (`platform_fee_gst`, "charged on invoice" — no invoices exist); refunds made in the Stripe dashboard (still
  ignored by S-12) report no reversal; no Stripe Tax webhooks (there is nothing to drive from them); nothing has run
  against a real Stripe account.

## 2026-09-30 — S-24 Bank linking via Stripe Financial Connections (payments; merchants onboarding check; Studio Payouts)

- **Port:** `payments.application.BankLinking` (session · link a Financial Connections pick · typed details), split out
  of `PayoutGateway` (which keeps payouts and `makeDefault`). Selected like the other Stripe adapters: stripe-java
  (`StripeBankLinking`) when `STRIPE_SECRET_KEY` is set, `FakeBankLinking` otherwise — so `local` runs with no
  credentials, and staging/prod (which require the key) always use Stripe. No new variable. **Never run against a real
  Stripe account:** written from Stripe's API reference and tested with stripe-mock (`StripeBankLinkingStripeMockTest`).
- **Flow (Connect external account):** the api opens a Financial Connections session whose account holder is the
  merchant's connected account (`permissions=[payment_method]`) and returns its client secret + the publishable key;
  the Studio loads Stripe.js (only in `stripe` mode, only then) and calls `collectBankAccountToken`; it sends the
  bank-account token **and** the Financial Connections account id; the api checks that account is held by the same
  connected account and `active` (else 422 `linkedAccount` "We couldn't use that bank link. Connect your bank again."
  — also for a token Stripe refuses), attaches the token as an external account, and keeps a draft. Confirm (step-up,
  Idempotency-Key, 24 h hold) and the takeover (`default_for_currency`) are unchanged.
- **What is kept:** for a linked account only the institution's name and last 4 (plus `ba_…` and, new,
  `financial_connections_account` = `fca_…`); `institution_number` / `transit_number` stay null (before, they were
  parsed from the routing number). Typed details keep them (they are what the owner typed; never the account number).
- **Audit trail** (`developer.api.AuditTrail`, in the same transaction; payments now depends on `developer.api`):
  `payout_account.linked` (owner, `after` = method, institution, last 4, state), `payout_account.change_confirmed`
  (`before` = the active account, `after` + `stepUp: true`, `effectiveAt` — written only after the step-up proof is
  verified), `payout_account.change_effective` (actor `system`), `payout_account.bank_connection_ended` (actor
  `stripe`). Never an account number. `Prepare` / `confirm` carry the caller's team role for the log.
- **Disconnected:** `financial_connections.account.disconnected` and `…deactivated` (S-12 pipeline: signature, dedupe,
  async) set `payout_accounts.disconnected_at` once per account and write the audit entry; unknown `fca_…` → `ignored`.
  Payouts keep going to the bank account (it stays the connected account's external account — the Financial
  Connections link only gives access to account data); the overview returns `disconnectedAt` and the Studio shows
  "Bank connection ended {date}. Payouts still go to this account; reconnect to keep it verified." + Reconnect (opens
  the bank panel). The copy is ours (the design has no such state); fr-CA ours too.
- **Local fake simulates the flow without Stripe.js:** session mode `fake` → the Studio shows a "Test bank connection"
  picker (RBC ··8820 — the design's example — TD ··3391, BMO, Scotiabank, CIBC, ATB, Desjardins) and sends
  `btok_local_<institution>_<last4>` + `fca_local_…`; the fake refuses anything else (so the 422 path is testable).
- **Manual entry stays** (the design's "Enter details manually" chip) as the fallback, unchanged.
- **Onboarding:** merchants' `VerificationGateways.BankLinking` outside `local`/`test` is no longer the unconfigured
  adapter: `PaymentsBankLinking` reads new `payments.api.PayoutBankAccounts.current` — verified with "RBC ··8820" when a
  bank is linked, otherwise `submitted` (`awaiting_bank_link`); `OnboardingBankListener` verifies it when
  `payout_account.changed` arrives. The onboarding screen itself does not open Financial Connections (the owner links
  the bank in Payouts, or Stripe's Express onboarding collects it).
- **Schema V065:** `payout_accounts.financial_connections_account` (+ partial index), `payout_accounts.disconnected_at`.
- **Not done:** Stripe's Canadian coverage of Financial Connections must be confirmed with Stripe (manual entry covers
  the rest); `account.external_account.deleted` (a bank removed in Stripe) is not handled; a Financial Connections
  refresh / ownership check against the business's legal name is not requested; nothing has run against a real Stripe
  account or real Stripe.js.

## 2026-09-30 — S-20 Security review of the JSON sign-in flow and BFF handoff

Full review with every check, its result and the threat model: `docs/security/s-20-auth-review.md`. Findings fixed or
accepted with rationale (the story's acceptance criterion):

- **Fixed (medium):** Studio `safeNext` let `/<TAB>/host` through (the URL parser turns it into `//host`) → control
  characters refused, as in the BFF (which now also refuses DEL). The BFF accepted the CSRF token as an XOR-masked
  `_csrf` form field (`csrf.spa()`), usable from a sibling subdomain that plants the cookie → header-only token handler.
  No `__Host-` cookies → `__Host-NL_AUTH`, `__Host-NL_STUDIO`, `__Host-XSRF-TOKEN` under the cloud profiles, CSRF cookie
  `SameSite=Strict`. Session id not renewed at step-up → renewed. Passkey sign-in options listed the typed account's
  credentials (enumeration) → the same options for everyone. Passkey user verification `preferred` → `required`
  (a passkey alone gives `acr=mfa`). Signature counter compared with the registration's (Spring builds the webauthn4j
  record from the stored attestation object) → `PasskeyService` checks it against the stored counter first. Rate limits
  failed open with Valkey down → policy (below). The Studio's sign-out left the auth session alive when the browser's
  `POST /api/auth/sign-out` failed → a revoked refresh token ends its sign-in. `/api/v1/console/**` didn't require
  `acr=mfa` at path level → role + MFA (S-21's tax reconciliation endpoint, merged meanwhile, already checks it in its
  handler; the path rule now covers every future console endpoint, and single-factor staff still get
  `403 mfa_required`). Any pod could reach auth/bff with forged `X-Forwarded-*` → staging/prod NetworkPolicy admits
  only `envoy-gateway-system`.
- **Fixed (low):** decoy work for unknown accounts (TOTP / backup code timing); a request without `Origin` but with
  `Sec-Fetch-Site: cross-site` is refused; identifier ≤ 320 and backup code ≤ 64 characters (422 rule `length`, the
  auth API's error mapper now maps `Size` → `length` as the api does); CSP `default-src 'none'; frame-ancestors 'none';
  base-uri 'none'; form-action 'none'` and `Referrer-Policy: no-referrer` on every auth and bff answer.
- **Rate limits when Valkey is unreachable = `northline.auth.rate-limits.when-unavailable` (`RATE_LIMIT_WHEN_UNAVAILABLE`):**
  `open` | `closed`. Default `open` in `application.yml` (local, dev, test); `application-staging.yml` and
  `application-prod.yml` default it to `closed`. It applies to the actions that check a guessable secret — new
  `LimitedAction.guardsSecret()`: `otp-send`, `otp-verify`, `totp-verify`, `backup-code-verify`, `passkey-assertion`,
  `step-up`; `sign-in-lookup` (no secret; the next step fails closed anyway) and `security-change` (signed in, recent
  second factor already required) stay open. Closed = `503` ProblemDetail `code: sign_in_unavailable`,
  `retryAfterSeconds: 30`, `Retry-After: 30`, detail "Signing in is paused for a few minutes while we fix a problem on
  our side. Try again shortly." (en server copy; the Studio shows it in en/fr: « La connexion est suspendue quelques
  minutes, le temps de régler un problème de notre côté. Réessayez sous peu. »). A wrong answer recorded while the
  store is down is also answered 503 (not 422), so no "wrong code" oracle exists without counting. The port now
  signals an unreachable store (`RateLimiter.Unavailable`) and `AttemptLimits` decides; a failed *reset* is only logged
  (it can only make limits stricter). Overridable in staging/prod as a break-glass (a WARN at start-up) rather than
  refused: during a long Valkey outage the operator may prefer sign-ins over the limit, and the per-flow limits (5 per
  code / attempt) still hold. Chosen over failing closed for everything: typing an email reveals nothing and the
  Security tab already demands a fresh second factor.
- **Sign-out ends the sign-in when the refresh token is revoked:** `SessionLinkedAuthorizations.save` sees an
  authorization whose refresh token is *invalidated* (a client's `/oauth2/revoke`, or the server after a replayed
  authorization code) and calls `SessionService.signedOut` (`revoke_reason = signed_out`, authorizations deleted,
  auth HTTP session dropped on its next request by `RevokedSessionFilter`). This changes S-19's "refreshable = false"
  for that case: signing out of one app (the Studio, later a mobile app) now ends that sign-in for every client that
  shares it — single sign-out, which is what "Sign out" and "Not you?" mean. The Studio still calls
  `/api/auth/sign-out` too (idempotent).
- **Cookie names are profile configuration** (`server.servlet.session.cookie.name`, new `northline.bff.csrf-cookie-name`
  in the cloud profiles), not code, because `__Host-` needs Secure and local runs are http. **`COOKIE_DOMAIN` is
  removed** (auth, bff, runbooks, `.env.example`): nothing needs a cookie shared across subdomains and it would break
  `__Host-`. Renaming the cookies signs everyone out once at deploy. `SameSite=Lax` is kept for the session cookies
  (Strict breaks the federation callback and email links); CSRF defences don't rely on it. The Studio's
  `xsrfToken()` (`lib/http.ts`) reads either name; the finance evidence upload uses it instead of its own copy.
- **Passkey sign-in options:** new `PasskeyService.signInOptions()` (always anonymous); step-up keeps
  `requestOptions(userId)` (the user is signed in). Counter rule: refuse when `presented ≤ stored` unless both are 0
  (WebAuthn § 6.1.1; synced passkeys report 0). Existing passkeys registered without UV keep working only if the
  authenticator performs UV at sign-in (all platform passkeys do).
- **Network policy:** `networkPolicy.ingressFrom` in `values-staging.yaml` / `values-prod.yaml` =
  `namespaceSelector kubernetes.io/metadata.name: envoy-gateway-system` (the S-17 edge on every cloud). Another edge
  (GKE Gateway for Cloud Armor, Envoy in Gateway-namespace mode) must replace it — edge.md § Trusted proxies.
  `TRUSTED_PROXIES` itself stays the private ranges: Envoy's pods have pod addresses, so narrowing the CIDR can't
  single them out; the NetworkPolicy can.
- **Accepted risks (reasons in the review):** registration's "already uses this email/mobile" (design copy; rate
  limited, checked before any SMS); `SameSite=Lax`; BFF `redirect_uri` host from `X-Forwarded-Host` of private peers
  (sender-only effect, exact-match URIs refuse it; local development needs it); city header spoofable without a CDN
  (display only; documented); unsalted SHA-256 backup-code hashes (~50 bits; follow-up: HMAC with a server key and a
  compatible check of old hashes); no refresh-token reuse detection in Spring Authorization Server (confidential BFFs
  only in the cloud; revisit with the mobile apps and DPoP); `none` attestation; codes in the log of the local SMS fake
  (refused in staging/prod); OAuth `code`/`state` in edge access logs (single use, PKCE); access tokens valid ≤ 10 min
  after sign-out and fail-open introspection (S-19).
- **Sample data:** `OAuthClientCatalogTest` and federation.md use `studio.` instead of the draft `business.` host (S-17).
- **No schema change** (no migration in V024–V029). New variable `RATE_LIMIT_WHEN_UNAVAILABLE` (auth; runbooks README,
  dev, staging, prod, local, `.env.example`); `COOKIE_DOMAIN` removed. No new secret.
- **Tests:** auth `SecurityReviewApiTest` (session id renewed at sign-in, registration and step-up; Origin, Fetch
  Metadata and CORS; headers on the JSON API and `/oauth2/authorize`; malformed and oversized input; unknown vs known
  account answers; exact redirect URIs; PKCE required and S256 only; 10-min access tokens and refresh rotation; the
  BFF's revocation ends the auth session), `PasskeyApiTest` (identical options, UV required at sign-in and
  registration, cloned counter refused, non-counting authenticator allowed, foreign origin refused),
  `RateLimitStoreDownApiTest` (Valkey unreachable + closed: 503 on phone code, TOTP, backup code, passkey; lookup
  works), `AttemptLimitsTest`, `RateLimitConfigTest` (policy per profile, break-glass warning), `SignInServiceTest`
  (decoy work), `SessionCookieSettingsTest`; bff `BffHardeningTest` (full code + PKCE callback against a WireMock auth
  server with an ES256 ID token: session id renewed, lands on `next`, `code_verifier` sent; forged state; `__Host-`
  CSRF cookie attributes; `_csrf` form field and a planted cookie refused; logout clears `__Host-NL_STUDIO`; headers),
  `SessionCookieSettingsTest`, `BffSessionTest` (control characters in `next`); api `ConsoleAccessTest`; Studio
  `routeSupport.test.ts`, `session.test.tsx` (`__Host-XSRF-TOKEN`), `auth.test.tsx` (503 message en/fr). The new
  auth tests (step-up fixation, passkeys, headers, Fetch Metadata, revocation) and the BFF `_csrf` test were also run
  against the pre-fix code and failed there.
- **Not done:** backup-code HMAC; refresh-token family revocation; a GeoIP city fallback; applying the NetworkPolicy
  change to a real cluster (rendered with `helm template` for staging/prod only); anything against real Google/Apple, real authenticators or
  a deployed environment.

## 2026-09-30 — S-22 Identity verification (Stripe Identity) for owners ≥ 25 %

- **Who verifies:** the principals the structure's `x-principals.kyc_threshold_pct` points at the `kyc` row
  (`merchant_principals.kyc_verification_id`, onboarding's rule): ≥ 25 % for partnerships and corporations, every
  principal for sole proprietors (the owner), co-ops and non-profits (threshold 0 — board members hold no shares). The
  story title's "≥ 25 %" is the ownership case of that rule.
- **Port:** `merchants.application.IdentityVerification` (`start`, `cancel`, `read`) replaces onboarding's
  `VerificationGateways.IdentityVerification.verifyBusinessOwners` (a single instant "all owners passed"). Selected by
  `northline.identity.provider` (`IDENTITY_PROVIDER`): `local` (default; refused under staging/prod, a warning under
  dev) or `stripe` (stripe-java through `StripeClients`, `STRIPE_SECRET_KEY`, `STRIPE_API_BASE` for stripe-mock). The
  backlog calls the port `IdentityVerification`; kept.
- **Session:** `type=document`, `require_matching_selfie`, `require_live_capture`, driving licence / passport / ID card,
  `client_reference_id` = our check id, metadata `northline_merchant_id` / `northline_principal_id`,
  `provided_details.email` only for emailed links. Idempotency key `nl1:identity-session:<check>:<attempt>`; a new
  session cancels the previous one (`nl1:identity-cancel:<session>`). The hosted flow (Stripe's `url`) is used, not the
  Stripe.js modal: the Studio doesn't load Stripe.js anywhere yet, the design only shows "Start with Stripe", and the
  same URL works for emailed owners on their phones. Return URLs: `STUDIO_ORIGIN/onboarding/verification?m=…&identity=returned`
  (signed-in owner; the step polls every 5 s until the row moves) and the new public page `/identity/done`.
- **"This is me":** the signed-in owner picks their principal; `merchant_principals.user_id` (baseline column) records it.
  One principal per user per business (unique partial index); another member's principal → 409 `identity_not_you`,
  a second principal → 409 `identity_already_you`. Everyone else gets the link by email (`identity-verification`
  template, en/fr, transactional, the requesting owner's language). No SMS (not asked; the S-27 SMS path is for team
  invitations). The Stripe URL is never stored; it travels in the internal `IdentityLinkRequested` event (outbox), the
  same trade-off as the invitation token (S-13).
- **Webhooks:** Stripe sends Identity events to the **platform** endpoint, so S-12's `POST /api/v1/webhooks/stripe`
  (signature, 5-min tolerance, dedupe on event id, retry job) receives them; `StripeEventProcessor` publishes the new
  in-process `payments.api.IdentitySessionUpdated` (session id, status, `last_error.code`, merchant id — no personal
  data) and merchants applies it (`StripeIdentityUpdates` → `OwnerIdentityService`). No second endpoint/secret. For
  `verified` the adapter re-reads the session with `verified_outputs` expanded to compare. Updates apply in Stripe's
  `created` order; `verified` and `review` are final for webhooks; events for a replaced session are ignored.
  `StripeObject.PERSONAL` also drops `verified_outputs`, `provided_details`, `first_name`, `last_name` from stored payloads.
- **Kept data:** status, session id, `last_error` code, `name_match`, `dob_match`, delivery, the owner's email (for
  "Send a new link"), attempts, times. Never images, ID numbers, the verified name or date of birth.
- **Matching:** name = every given/family name Stripe read appears in the legal name, or vice versa (accents, case,
  apostrophes, periods, hyphens ignored). Date of birth: onboarding doesn't collect one, so it is compared with the
  business's Stripe Connect person of the same name (Connect collects it for payouts) — `unavailable` when there is no
  Connect account or person yet (typical during onboarding). A mismatch of either → `review` (manual review by an
  agent); `unavailable` doesn't block.
- **The `kyc` row** is derived: all owners verified → verified (reference `passed`); all handed in (verified,
  processing, review) → submitted (counts as complete, the owner can submit); otherwise todo. Recomputed on every
  webhook, every new session and every Business-step save (an added owner reopens it; a verified row with no owner
  checks at all — dev seed, approvals before S-22 — is left alone). The row's action is now `identity` (new
  `CheckKind.Action.IDENTITY`); `POST …/verifications/{kyc}/complete` answers 409 `identity_per_owner`.
- **ComplianceStatus:** `dueItems` now also returns the `kyc` row while it is todo/rejected (the compliance ledger's list
  still shows identity under Stripe Connect, as before). The dashboard already had the "Identity verification" label.
- **Principals keep their ids** across Business-step saves when the legal name is unchanged (case/spacing ignored), so
  their `user_id` and identity check survive; before, every save deleted and re-inserted all principals.
- **Schema V032:** `merchants.owner_identity_checks` (one row per principal, unique session id); index on
  `merchant_principals(merchant_id)`; unique partial index `merchant_principals(merchant_id, user_id)`.
- **API:** `GET /api/v1/merchants/{id}/identity-checks` and `POST …/identity-checks/{principalId}/session`
  `{delivery: self|email, email?}` (owner-only, MANAGE). Messages (not in validation-rules.md): "Choose how this owner
  verifies.", "Enter the owner's email address.", "That doesn't look like an email address." (existing). 409s:
  `identity_already_verified`, `identity_processing`, `identity_in_review`, `identity_unavailable` ("We couldn't reach
  Stripe…").
- **Local:** the fake's "hosted flow" is `GET /api/v1/dev/identity-sessions/{id}` (profile `local`, public like
  Stripe's page) with one button per outcome; the choice is applied like the webhook, then the browser goes to the
  return URL.
- **Studio:** "Start with Stripe" opens an owners dialog (status per owner, "This is me · verify now", "Email a link" /
  "Send a new link", Stripe's error codes in words, privacy note), en + fr. The design has no drawing of this dialog;
  copy is ours. `/identity/done` is public.
- **Never run against the real Stripe Identity** (no account): requests are validated by stripe-mock
  (`StripeIdentityVerificationStripeMockTest` — create, retrieve with expand, cancel, idempotency keys, pinned
  version; stripe-mock's fixture has no `url`, so `start` answers 409 there). The persons lookup for the date of
  birth and the real `verified_outputs` shape are untested against Stripe.
- **Not done:** the console's review queue for `review` owners (console workstream); annual re-verification (Stripe
  Connect's own `future_requirements` still shows on the compliance screen); SMS links; a Stripe.js modal.

## 2026-09-30 — S-23 Business registry lookups (Alberta corporate registry, Corporations Canada, municipal licences)

- **Built on S-22** (branch `merchants/s-22-stripe-identity`, PR #32): both stories change the same onboarding ports,
  fakes and the `ComplianceStatus` feed (`platformChecks`), and share the V030–V039 range (V032 → V033).
- **Port:** `merchants.application.BusinessRegistry` (`source()`, `lookup(RegistryQuery)` → `Found | NotFound |
  Manual | Unavailable`, never throws), one adapter per source, chosen by `northline.registries.<source>.provider`
  (`REGISTRY_CORPORATIONS_CANADA_PROVIDER` `fixtures|api|manual`, `REGISTRY_ALBERTA_PROVIDER`
  `fixtures|opencorporates|manual`, `REGISTRY_CALGARY_PROVIDER` `fixtures|socrata|manual`). It replaces onboarding's
  `VerificationGateways.RegistryLookup` (and its fake "everything matches" rules). The backlog's name `RegistryLookup`
  became `BusinessRegistry`, as the lead asked. `fixtures` (the local/test default, `registries/fixtures.json`) is refused
  under staging/prod; Helm sets `manual`/`manual`/`socrata` there until accounts exist.
- **Research** (docs/runbooks/registries.md; the government doc hosts were unreachable from the build environment, so
  details come from search results quoting them): Corporations Canada has a keyed Federal Corporation API in the GC
  API Store (lookup by corporation number or BN; exact path and key header *to confirm* — URL and header are
  configurable); Alberta has **no public API** (registry agents; Registries Online only for accredited subscribers), so
  the provider adapter is OpenCorporates' `ca_ab` company API with a manual registry-agent fallback; Calgary's
  business licences are the Socrata dataset `vdjc-pybd` (free; optional app token). AMVIC, AHS, AGLC and the other
  regulators have no API → every such licence is a manual review.
- **What is looked up** (`RegistryPlan`, per legal-details.schema.json structure): Alberta access / registration /
  trade-name / co-op / society numbers; federal corporations at Corporations Canada **and** their Alberta
  extra-provincial registration; for kitchens with a city licence number in Calgary, the City licence; licence rows
  (`licence:<registry>`, `ahs_permit`, `aglc`) → Calgary for "Mobile permit", manual otherwise. A sole proprietor
  without a trade name has nothing to register: the row is verified with reference `not_required`. Businesses outside
  Calgary aren't sent to the Calgary dataset (no city yet counts as Calgary, the launch city).
- **Matching:** a name entered (legal / corporate, operating or trade, display) equals the record's name ignoring case,
  accents, punctuation, "&"/"and", a leading "The" and legal-form suffixes; active status; licence not expired. Reasons
  `name`, `status`, `expired`. All matched → row `verified` (reference = the primary number, `expires_at` = the
  earliest registry expiry as that day in Calgary — the checklist's existing expiry convention); otherwise `submitted`
  (counts as complete, "{number} · checking" in the Studio) with an open review per unmatched lookup. An unreachable
  source during onboarding also goes to review (the owner isn't blocked).
- **Evidence:** every lookup is a `merchants.registry_checks` row (V033): source, subject, regulator, number, expected
  name, trigger (`initial|recheck`), outcome, reasons, the record's name/number/status/expiry, the provider reference
  (record URL, search id or dataset query) and `checked_at`, plus the review (state, agent, time, note). Public business
  data only.
- **Console verification queue** (no console UI exists; API only): `GET /api/v1/console/registry-reviews`,
  `POST …/{id}/decision {decision, reference?, expiresOn?, note?}` (role STAFF + MFA). Approve → verified with the
  agent's reference/expiry once no other review of that row is open; reject → `rejected` (a `ComplianceStatus` due item).
  Messages not in validation-rules.md: "Choose approve or reject.", "At most 120 characters.", "At most 500 characters.";
  409 `review_closed`.
- **Re-checks:** daily job (`REGISTRY_RECHECK_CRON`, 03:41 Calgary; not under `test`) re-runs verified rows backed by an
  API source whose `verifications.rechecked_at` (baseline column, first use) is older than `REGISTRY_RECHECK_AFTER`
  (30 days — the design's "re-checked monthly"), 50 per batch, `FOR UPDATE SKIP LOCKED`. Still matching → expiry and
  check time refreshed; no longer matching → `expires_at = now` (ComplianceStatus: expired, instant book pauses after
  the 15-day grace) + a review; source down → retried the next day, no review. Rows only an agent can check (AMVIC,
  AHS, …) aren't re-checked automatically (their expiry comes from the agent or the owner's renewal upload).
- **ComplianceStatus:** `dueItems` now also includes the `registry` row (with S-22's `kyc`) while due; licence rows
  were already in the ledger.
- **Tests changed:** onboarding's AHS permit number now waits for an agent (`submitted`, was the fake's `verified`).
- **Catalogue:** `MerchantLicenceQueries` (verified, unexpired `licence`/`registry` rows by registry) keeps working
  unchanged — registry results land in the same rows; a lapsed re-check makes it return false for that registry.
- **Config:** new secrets `REGISTRY_CORPORATIONS_CANADA_KEY`, `REGISTRY_ALBERTA_KEY`, `REGISTRY_CALGARY_APP_TOKEN`
  (Terraform creates them empty on all three clouds; Helm `secretEnv` entries off by default); runbooks, secrets.md,
  `.env.example`.
- **Never run against the live services:** the three adapters are tested with WireMock stand-ins built from the
  research; the Corporations Canada record shape, the API Store path and key header, and Calgary's `getbusid` format
  are unverified.
- **Not done:** a console UI for the queue; re-checking rows an owner's legal-name change sent back to `submitted`
  (Settings › Business) automatically; a name search when the owner doesn't know the number; Kyckr-style KYB providers.

## 2026-09-30 — S-29 Mobile and courier clients: public PKCE + rotating refresh tokens + DPoP

Runbook for app developers: `docs/runbooks/mobile-auth.md`.

- **Clients in every environment** (`application.yml`, no longer `local` only): `mobile-consumer` ("Northline":
  `openid profile orders bookings offline_access`, refresh 30 d) and `courier-app` ("Northline Courier":
  `openid courier deliveries`, refresh **12 h** — design 05's "device-bound, short refresh", read as one shift). Scopes
  follow design 05 (the old local block had `openid profile deliveries offline_access` for the courier). Public, no
  secret, PKCE S256, `dpop-required: true`. Redirects: `${CONSUMER_ORIGIN}/app/oauth2redirect` and
  `…/courier/oauth2redirect` (claimed App Link / Universal Link on the consumer host — the auth host itself can't be
  used: a Universal Link doesn't open the app for a redirect within the same domain) plus the reverse-domain schemes
  `ca.northline.app:/oauth2redirect`, `ca.northline.courier:/oauth2redirect` (RFC 8252). Catalogue rule: a public client
  with `refresh_token` must be `dpop-required` (its refresh tokens are then sender-constrained, RFC 9449 § 5); the
  "recorded but not enforced" warning is gone.
- **Spring Authorization Server's DPoP support is used where it exists** (7.1.1): proof verification in the grant
  providers, `cnf.jkt` on access tokens, `token_type: DPoP`, and the public-client refresh check that the proof's key
  equals the authorization's access-token `cnf.jkt` (that is what binds the refresh token). What it leaves open, and is
  added in `ca.northline.auth.dpop`:
  - **Proof required** for `dpop-required` clients (`DpopTokenEndpointFilter`, a servlet filter on `/oauth2/token`
    before Spring Security): without it Spring silently issues bearer tokens. 400 `invalid_dpop_proof`.
  - **Server nonces** (`DPoP-Nonce`, RFC 9449 § 8) — not implemented by Spring. Time windows of `nonce-lifetime` (5 min)
    with one random nonce each, created with SET NX in Valkey so every instance agrees; the current and previous
    window's nonce are accepted. Every token-endpoint answer to a DPoP request carries the current one; a missing or
    stale nonce → 400 `use_dpop_nonce`. Chosen over an HMAC-of-time nonce: no new secret to provision and rotate.
  - **Single-use `jti` in Valkey:** Spring's `DPoPProofReplayValidator` with a `Cache` adapter over Valkey
    (`nl:auth-replay:dpop-jti:<sha256>`, SET NX, ≤ 70 s) instead of its per-JVM in-memory cache. Spring then verifies the same
    proof again with its static in-memory cache (not configurable in `DPoPProofVerifier`) — harmless. `iat` stays at
    Spring's fixed ±30 s (a looser window here would be undone by Spring's second check); apps correct `iat` with the
    server's `Date`.
  - **Refresh tokens for public clients:** Spring never issues them (`OAuth2RefreshTokenGenerator`). The app's
    `OAuth2TokenGenerator` bean now issues one to a `dpop-required` public client when the request carried a DPoP proof
    (`DpopTokens.refreshTokens`). Declaring the generator turns off Spring's default claims customizer, so `cnf.jkt` is
    added by `DpopTokens.confirmation` (same computation) before our claims.
  - **Public-client client authentication beyond the code exchange** (`AppClientAuthentication`, first in Spring's
    client-authentication converters/providers): a refresh with `client_id`, no credentials and a `DPoP` header, and
    `POST /oauth2/revoke` with `client_id` (RFC 7009 § 2.1 — the token is the proof; revoking only ends the holder's
    own sign-in). Introspection stays closed to public clients.
- **Store:** a generic `ReplayStore` (package `ca.northline.auth.replay`: one-time ids + values shared per key, for
  DPoP now and partner assertions next), `northline.auth.replay.store` (`REPLAY_STORE`): `redis` (default; required
  under staging/prod) or `memory` (`local`, `test`; the `valkey` add-on profile switches it back to Valkey). **Valkey unreachable = fail closed**:
  a token request carrying DPoP answers 503 `temporarily_unavailable` (`Retry-After: 30`), whatever
  `RATE_LIMIT_WHEN_UNAVAILABLE` says — a proof that can't be checked for replay is never accepted. BFF sign-ins are
  unaffected.
- **Refresh-token reuse detection (closes S-20's accepted risk for public clients):** refresh tokens already rotated
  on every use (`reuse-refresh-tokens: false`); Spring keeps only the current one. **V024
  `auth.issued_refresh_tokens`** (SHA-256 of every refresh token issued to a public client, FK to the authorization with
  `ON DELETE CASCADE`) remembers the family; `RefreshTokenReuseDetection` wraps the JDBC authorization store: a refresh
  token the store no longer knows but whose family still exists is reuse → the family is deleted and its sign-in ends
  (`SignInSessions.end`, new **`revoke_reason = refresh_token_reused`** — V024 widens the V021 CHECK), audit
  `auth.refresh_token_reused` + `auth.session_revoked`, WARN log; the request fails `invalid_grant`. Strict: no grace
  period for a retried refresh (documented for app developers). The BFFs are left out: their tokens never leave the
  server, and two replicas refreshing one browser session at once would look like reuse.
- **Sessions (S-19):** nothing new was needed — an app's authorization is linked to the sign-in of the phone's browser
  like the BFF's, listed with the client name while its refresh token lives, revocable from Settings › Security.
  Signing out of the app (`/oauth2/revoke`) ends the sign-in (S-20 single sign-out).
- **Sign-in page → back to the app:** an unauthenticated `/oauth2/authorize` still goes to `northline.auth.login-page`
  (the Studio's page until the consumer web exists), and Spring's request cache keeps the request in the auth session.
  The JSON sign-in and registration answers now carry `continueTo` = that saved request **only when it is a public
  client's `/oauth2/authorize`** (`AppAuthorizationResume`, taken once); the Studio navigates there instead of its BFF
  hand-off, and only to an `/oauth2/authorize` on the auth origin. The Studio's own flow is unchanged.
- **acr:** consumer tokens don't need `acr=mfa`. Courier tokens: ARCHITECTURE.md requires `acr=mfa` for business and
  staff tokens only, and couriers are neither (DATA_MODEL: customers, merchant staff, couriers and admins are separate
  contexts) — so no requirement either; design 05 asks only for device binding and a short refresh. Tokens still carry
  `acr=mfa` whenever the sign-in used a second factor (today: every sign-in). App tokens can't reach merchant or console
  endpoints (no `merchant`/`console` scope), whatever their `acr`.
- **api (resource server):** Spring Security's `.dPoP()` (`Authorization: DPoP` + proof: thumbprint = `cnf.jkt`,
  `ath`, `htm`, `htu`, `iat`), with the replay cache in Valkey outside `local`/`test` (`nl:api-dpop:jti:*`,
  `DpopResourceConfig`) and Spring's in-memory cache there. Spring's bearer filter already refuses a DPoP-bound token
  sent as `Bearer` (a custom filter written for it was removed). **No nonce at the api:** the nonce would need state
  shared with auth; `iat` ± 30 s + single-use `jti` + `ath` bound a captured proof to one request. Valkey down at the
  api = the proof fails → 401.
- **Edge:** the api host now routes all of `/api/v1` (`apps.api.tokenClients: true`, default) — the apps call the api
  directly; before, only the Stripe webhooks and the unsubscribe link were routed (S-17). `false` restores that.
- **Configuration:** new variables `REPLAY_STORE`, `DPOP_NONCE_LIFETIME` (auth; optional; runbooks README, local, dev,
  staging, prod, `.env.example`). No secret, so no Helm/External Secrets change beyond the route.
- **Schema:** V024 (`auth.issued_refresh_tokens`, `identity.sessions.revoke_reason` + `refresh_token_reused`).
- **Tests:** auth `MobileDpopApiTest` (full flow with a generated P-256 key: `use_dpop_nonce` then tokens with
  `cnf.jkt`, `token_type DPoP`, rotation under the same key; no proof → 400 and the code isn't spent; refresh without a
  proof, replayed proof, another key, wrong `htu`/`htm`, made-up and stale nonces refused; reuse → family revoked,
  sign-in ended, audit, browser session 401, other sign-in intact; only hashes stored; app sign-out via
  `/oauth2/revoke` ends the sign-in, no introspection; the phone's sign-in listed with "Northline" and revoked from
  Settings › Security; sign-in page `continueTo` for the app and not for the Studio; courier scopes and 12 h refresh;
  consumer can't ask for courier scopes; the Studio BFF still gets bearer tokens without a nonce header),
  `ReplayStoreTest` (the same contract against Valkey 8 and memory, two instances agree on nonce and ids, TTLs, Valkey
  unreachable → 503 from the filter, `memory` refused under prod), `OAuthClientCatalogTest` (public refresh client
  without DPoP refused); api `DpopResourceServerTest` (proof accepted; no proof, bearer downgrade, replay, other key,
  other token/URL/method, missing `ath` → 401; plain bearer tokens still work; an app token with `acr=mfa` still gets
  403 on a merchant endpoint); Studio `auth.test.tsx` (`continueTo` followed; a foreign one ignored).
- **Not done / never run:** no real app, device, Secure Enclave or Android Keystore has used this; the App Link /
  Universal Link association files (`apple-app-site-association`, `assetlinks.json`) must be served by the consumer
  web (E-7) — until then only the custom schemes work; no consumer-facing sign-in page (the Studio's is used); no
  refresh grace window; no DPoP for the BFFs (tokens never leave the server); `dpop_jkt` at the authorization endpoint
  (RFC 9449 § 10, optional) is not checked.

## 2026-09-30 — S-30 Partner API clients: client credentials with private_key_jwt

Runbook: `docs/runbooks/partners.md`. Built on S-29 (branch based on `auth/s-29-mobile-dpop`): it reuses S-29's
`ReplayStore` for assertion ids and extends the same client catalogue, token generator and claims customizer.

- **Partners are configuration of their own**, `northline.oauth.partners.<name>` (`PartnerSpec`: `name`,
  `jwk-set-url` | `public-keys`, `scopes`, `merchants`, `access-token-ttl`, `revoked`), registered as client
  **`partner:<name>`** (design 05's `partner:*`) by the same catalogue and `OAuthClientSync`, so the `oauthClients`
  Job/command and start-up sync register, update and report them like any client. A separate map rather than
  `northline.oauth.clients` entries: the key fields differ entirely (no secret, redirect or PKCE; keys, businesses),
  and a `partner:` map key would need Spring's bracket syntax in YAML. The chart renders the `partners` value into the
  ConfigMap `northline-auth-partners` (`SPRING_CONFIG_ADDITIONAL_LOCATION`, mounted in auth and the Job; its content is
  in the Job's name hash and the auth pods' config checksum). Nothing secret: public keys, URLs, business ids.
- **Keys:** a JWK Set URL (preferred: the partner rotates alone; Nimbus refetches on an unknown `kid`) or registered
  public JWKs (EC P-256 / RSA ≥ 2048, each with its own `kid`; several at once = rotation overlap). Validated before
  anything is written (private keys refused, https under staging/prod). ES256, RS256 and PS256 accepted from those keys.
- **Assertion check (`PartnerAssertions`)** replaces Spring Authorization Server's `JwtClientAssertionDecoderFactory`
  for partners (Spring's supports only a JWK Set URL, one algorithm, and no replay or lifetime rule): `iss` = `sub` =
  client id, `aud` = the issuer or the token endpoint, `exp` + `iat` required with 60 s skew, at most 5 minutes of
  life, `jti` required and **single-use in Valkey** (`nl:auth-replay:assertion-jti:<sha256(client:jti)>`, until expiry
  + 60 s; checked last so a failing assertion doesn't spend its id). Decoders are cached per client and rebuilt when the
  registered keys or URL change (no restart for a rotation). Replay store down → the assertion is refused (400
  `temporarily_unavailable`). Spring requires `client_id` in the request although RFC 7523 makes it optional
  (documented for partners).
- **Scopes** follow design 05: `api.read`, `api.write` (the catalogue refuses anything else for a partner, e.g.
  `merchant`, which would open the Studio's endpoints); a request without `scope` gets the partner's registered scopes
  (Spring's client-credentials grant would otherwise issue a token with none). Settings › API's per-merchant API keys
  (`developer.api_keys`, their own scope list) are unrelated and unchanged.
- **Merchant binding in claims, enforced in the api:** the token (15 min default, design 05) carries `sub =
  partner:<name>`, `aud [client, northline-api]`, `scope`, `roles: [partner]`, `merchants` = the configured
  businesses, no `acr`. In the api: `/api/v1/merchants/**` accepts `SCOPE_merchant` **or** `ROLE_PARTNER`; everything
  else refuses partner tokens (authenticated and not partner). `MerchantAccessInterceptor`: a partner token needs a
  handler marked **`@PartnerAccess(scope)`** (new, `shared.security`) → else `403 partner_not_allowed`; the scope →
  else `partner_not_allowed`; the business in its `merchants` claim → else `403 not_bound` (new
  `MerchantAccessDenied` reasons). Partners never get a `CurrentMember`. Opened: `GET …/listings` and
  `GET …/listings/{listingId}` with `api.read` (reused, no sample endpoint). The binding is read from the token, not
  re-checked against configuration per request: a removed business or a revocation takes effect within the token's
  15 minutes.
- **Revocation:** `revoked: true` → client authentication fails (`invalid_client`) before any key is looked at. A
  partner removed from configuration is only reported (S-122's never-delete rule), so revoking = the flag.
- **Rate limit and audit (`PartnerTokenProvider` around Spring's client-credentials provider):** new S-9 action
  `partner-token` (REQUESTS; account = `partner:<client id>` 60/h, IP 600/h, 15 min lockout; not a guessed secret, so
  it fails open with Valkey down — the assertion's replay check fails closed anyway). Counted only after the client
  authenticated, so nobody can use up a partner's budget with forged assertions. Over it: `429 rate_limited` with
  `Retry-After` (`PartnerTokenErrors`, the token endpoint's error handler). Every token issued: `developer.audit_log`
  `auth.partner_token_issued` (actor/target = client id; scopes, merchants, expiry).
- **No schema change, no new variable.** Chart: `partners` value, ConfigMap template, mounts, `validate.sh` case.
- **Tests:** auth `PartnerClientsApiTest` (registered EC key → scoped token with the claims above and one audit row;
  JWK Set URL on WireMock with an RSA key; scopes limited, `openid merchant` refused; two registered keys at once; a key
  added to the JWK Set picked up without restart; a registered key removed by a sync stops working; replayed assertion;
  wrong `aud`, expired, too long-lived, future `iat`, missing `iat`/`jti`, foreign `iss`/`sub` all `invalid_client`;
  an unregistered key; revoked by a sync; rate limit per partner → 429 with `Retry-After`, another partner unaffected;
  the `oauthClients` command registers a partner from properties), `OAuthClientCatalogTest` (every partner rule);
  api `PartnerListingAccessTest` (bound + `api.read` → 200; other business `not_bound`; missing scope and unmarked
  endpoints `partner_not_allowed`; writes refused; nothing outside a business; members unchanged).
- **Not done / never run:** no real partner, JWK Set or partner signing stack has been used; mTLS-bound partner
  tokens; per-partner rate limits (one rule for all); a console UI for partners; merchant consent (businesses are bound
  by operators in configuration, not by the owners in the Studio); partner webhooks (design 05 mentions them — the
  worker's partner webhooks are a separate story).

## 2026-09-30 — S-33 Partner webhook delivery worker (worker `ca.northline.worker.webhooks`, api `developer`, Studio Settings › API)

- **Two stages, per-endpoint scheduling (not per Kafka partition).** A new consumer group `webhooks` (topics `booking.booking`, `payments.escrow`, `payments.refund`; retries 10 s / 60 s / 5 min, then `.dlq`; +9 retry topics → 80 in the catalogue) only maps the event to its public payload and inserts one `developer.webhook_deliveries` row per active subscribed endpoint, in the S-26 dedupe transaction — no HTTP on a partition. A dispatcher (every second, every replica) leases **endpoints** (`lease_owner`/`lease_until` on `webhook_endpoints`, 2 min, renewed per delivery) and drains each on its own virtual thread: one request in flight per endpoint, ≤ 20 deliveries per lease, ≤ 64 endpoints per replica. A slow or dead endpoint delays only its own queue. Chosen over per-endpoint Kafka topics (topic count, Event Hubs limits) and over retry topics per delivery (a 3-day back-off doesn't fit Kafka retry topics). Delivery is **at-least-once and unordered** (a crash between the POST and the record resends; receivers dedupe on the event id).
- **Public payloads are their own versioned contract**: envelope `{id, type, version, createdAt, merchantId, data}`, JSON Schemas in `docs/spec/webhooks/<type>.v1.schema.json` (packaged into the worker, every outgoing payload validated against them with the S-26 `EventSchemas` validator; a mapping bug is retried then dead-lettered). Internal event versions map explicitly (`WebhookPayloads`); a new internal version isn't delivered until mapped (logged). Breaking change = public `version: 2`.
- **PII (decided):** `data` carries only what the business already has in its Studio — its own ids (booking, escrow, refund, `RF-` case number, its team members' user ids) and amounts. **No customer ids**, names, contact details or addresses: a customer id is a cross-business identifier the partner has no use for.
- **Mapped today:** `booking.completed` ← `booking.booking_completed` v1, `payment.released` ← `payments.escrow_released` v1, `refund.issued` ← `payments.refund_issued` v1, plus `webhook.test`. **Not sent yet** (subscribable in Settings, no domain event on Kafka): `booking.confirmed` (quote acceptance creates the booking but no booking event exists; mapping it from `quote.accepted` would expose the quote id, not a booking id, and a customer id), `order.placed`, `order.delivered`, `review.created`. `WebhookPayloadsTest` keeps the subscribable list = mapped ∪ not-yet-published.
- **Signature = Stripe's scheme**, header `Northline-Signature: t=<unix>,v1=<hex HMAC-SHA256(whole "whsec_…" secret, "<t>.<raw body>")>`, one `v1` per valid secret; 5-minute tolerance recommended to receivers. Also `Northline-Event-Id|Event-Type|Delivery-Id|Delivery-Attempt`. The body is stored as **text** (`webhook_deliveries.payload`), so every attempt and resend sends byte-identical JSON.
- **Secrets:** the api keeps creating/encrypting (`whsec_…`, AES-256-GCM); the AES code moved to `platform.WebhookSecretBox` so the worker decrypts with the same implementation and `WEBHOOK_SECRET_KEY` (now also a worker variable, required in the cloud; dev key only outside `cloud`). **Rotation overlap:** `POST …/webhooks/{id}/secret {overlapHours}` (default 24, 0–168; "Keep the old secret for 0 to 168 hours.") moves the current ciphertext to `secret_prev_enc` until `secret_prev_until`; the worker signs with both meanwhile. Studio: a dialog with 24 h (recommended) / 7 days / "stop it now (it leaked)".
- **Retries:** 30 s × 3ⁿ⁻¹ capped at 12 h, ± 10 % jitter, 13 attempts ≈ 2.9 days (`RetrySchedule`), then `failed`. Every non-2xx (3xx included — no redirects), timeout, TLS/connection error or SSRF refusal is a failed attempt.
- **Auto-disable:** no success for **3 days** (`failing_since`) **and** ≥ 10 failed attempts in a row → `active = false`, `disabled_reason = 'failing'`, pending deliveries → failed, audit row `webhook.disabled` (actor null, role `system`), metric + WARN log. The owners' email goes through the S-27 `Notifier` (made public: `notify(Notice)`; `Notice.Texts.studioPage()` added) as a service notice (email only, owners, whatever the matrix; new `webhook-disabled` template en/fr, TRANSACTIONAL), claimed per owner under `disable_notice_id`; a job retries every minute for 2 days while the provider is down (`disabled_notified_at`). Owners turn it back on (`POST …/enable`, audit `webhook.enabled`, event change `enabled` — an additive enum value in `developer.webhook_endpoint_changed` v1).
- **SSRF (hard requirement):** https only, no userinfo; the Apache HttpClient 5 `DnsResolver` resolves, refuses the host when **any** address is loopback / 0/8 / RFC 1918 / 100.64/10 / 169.254/16 (metadata) / 192.0.0/24 / documentation / benchmarking / multicast / reserved / broadcast / `::` / `::1` / fe80::/10 / fec0::/10 / fc00::/7 (incl. `fd00:ec2::254`) / ff00::/8 / 2001:db8::/32 / IPv4-mapped, NAT64 or 6to4 embedding a refused IPv4, and returns exactly the checked addresses to the connection (pinning; TLS still verifies the host name). IP literals are checked before the request as well. No redirects, retries, cookies or environment proxies; connect 5 s, read 10 s, total 15 s (cancel); answer read to 64 KiB then the connection is dropped; 1,000-character snippet with control characters removed. `WEBHOOKS_ALLOW_LOCAL=true` (local/tests only; the cloud profiles refuse to start with it) allows http + loopback, never private/metadata. A refusal is retried (DNS may change) and counts toward auto-disable. No port restriction (partners use custom ports).
- **Delivery log:** `GET …/webhooks/{id}/deliveries` (VIEW; newest 50 with every attempt), `POST …/deliveries/{d}/resend` (MANAGE; same event id and body, new row `resend_of`; 409 `delivery_pending` while still retrying, 409 `webhook_disabled` when off), `POST …/webhooks/{id}/test` (MANAGE; `webhook.test` row the worker fills in). Kept 30 days (nightly purge). Studio: a Deliveries drawer per endpoint (state tag Delivered / Retrying / Queued / Failed, outcome, attempts, next attempt, per-attempt details with response snippet, Resend, Send test event), a "Turned off … after 3 days of failed deliveries" tag with **Turn back on**, "Failing since …" and "Old secret also signs until …" lines. Copy is ours (the design shows only the endpoint line); fr-CA written alongside.
- **Merchant-scoped** now (endpoints belong to a business; fan-out by `merchant_id`). Partners (S-30) will need an owner column and their own subscription scope.
- **No provider switch** for the transport: the only adapter is plain HTTPS (`WebhookTransport` port, `HttpWebhookTransport`); `local` differs only by `WEBHOOKS_ALLOW_LOCAL`. `HostResolver` is a port so tests pin made-up names.

### Schema additions (V087, settings range, developer schema)
- `developer.webhook_endpoints`: `secret_prev_enc`, `secret_prev_until`, `failing_since`, `consecutive_failures`, `disabled_at`, `disabled_reason` (`failing`), `disable_notice_id`, `disabled_notified_at`, `lease_owner`, `lease_until`; partial index on active endpoints by merchant.
- `developer.webhook_deliveries`: `merchant_id`, `event_type` (public type), `payload` (text), `state` (`pending|succeeded|failed`; null = a pre-S-33 row, read from its status code), `next_attempt_at`, `duration_ms`, `error`, `response_snippet`, `resend_of`, `test`, `created_at`; V015's `attempt` / `status_code` / `at` = attempts so far / latest status / latest attempt. Unique `(endpoint_id, event_id)` for originals (fan-out idempotency); due and log indexes.
- New `developer.webhook_attempts` (one row per HTTP try; cascades with its delivery).

### Configuration
Worker: `WEBHOOK_SECRET_KEY` (required in dev/staging/prod; chart `apps.worker.secretEnv`), optional `WEBHOOKS_ALLOW_LOCAL`, `WEBHOOKS_MAX_IN_FLIGHT`, `WEBHOOKS_CONNECT_TIMEOUT`, `WEBHOOKS_RESPONSE_TIMEOUT`, `WEBHOOKS_TOTAL_TIMEOUT`, `WEBHOOKS_DISABLE_AFTER`, `WEBHOOKS_LOG_RETENTION`. Runbooks README / dev / staging / prod / local / secrets / notifications, `server/.env.example`, new [webhooks.md](runbooks/webhooks.md) (payload reference, verification, retries, design, SSRF rules, operations). Terraform unchanged (External Secrets already reads `webhook-secret-key`).

### Tests
Worker: `WebhookDeliveryTest` (Kafka 4 + PostGIS + WireMock receiver: signed `booking.completed` delivered once to the subscribed endpoint only, verified with the endpoint's secret; 500 → 503 → 200 with the clock moved 31 s / 91 s and every attempt logged; auto-disable after 3 days + the owner's email once, not the technician, nothing queued afterwards; a 2-second endpoint doesn't delay another; metadata / private / credential URLs refused with nothing sent; test event + resend with the same event id and body; both secrets sign during the overlap, only the new one after), `HttpWebhookTransportTest` (WireMock: headers, no redirects, 4 MiB answer cut, timeout, http refused outside local, loopback / metadata / ULA / private-resolving / rebinding names refused before any request, pinned address with the original Host), `EgressPolicyTest`, `WebhookSignerTest`, `RetryScheduleTest`, `WebhookPayloadsTest`. Api: `DeveloperSettingsApiTest.Delivery` (overlap 24 h / 0 / 422 message, delivery log with attempts, resend + 409s, test event, turn back on, 403 `insufficient_role` / `mfa_required` / `not_a_member`, 404 across businesses). Studio: delivery drawer, technician read-only, turned-off endpoint, rotate with overlap.

### Not done / never run for real
- No real partner endpoint has received a delivery; all HTTP is against WireMock. The system DNS resolver path is exercised only for literals in tests (names use the test resolver).
- `booking.confirmed`, `order.placed`, `order.delivered`, `review.created` wait for their domain events.
- Partner-scoped endpoints (S-30), per-endpoint rate limits, `Retry-After` honouring, a manual "retry now" for a pending delivery, and a Studio chart of delivery health.
- The delivery log keeps the payload for 30 days in the database (ids and amounts only).

## 2026-09-30 — S-34 Event JSON Schemas validated in CI (`server/event-contracts`)

- **Where:** a new Gradle project `server/event-contracts` (no boot jar) that depends on `:api`, `:auth` and `:worker`. It is the only place that can see the api's and northline-auth's event records **and** the worker's `EventSchemas`, which is the only schema validator in the system. It exposes one task, `:event-contracts:eventSchemas` (a JavaExec that prints a report; exit 1 on problems, 2 when `-PeventSchemas.requireBase=true` and the base can't be read), and a test, `EventContractsTest`, which runs the same checks inside `./gradlew build` (about 2 s: no Spring context, no containers).
- **Check 1: "valid JSON Schema" means valid for the subset the consumers implement.** The worker's `EventSchemas` gains `KEYWORDS` / `FORMATS` (now public), `unsupportedKeywords(schema)` and `of(Map)`, so both sides use the same list. Beyond keywords, the check covers:
  - `$schema` is draft 2020-12;
  - `$id` = `northline:<type>:<n>` and matches the file name;
  - `type` values are valid;
  - every name in `required` is declared;
  - `additionalProperties` is boolean only, because schema-valued ones are not implemented;
  - every `pattern` compiles;
  - min/max lengths are non-negative integers;
  - `format` is one the worker checks;
  - the envelope fields `eventId`, `occurredAt` and `aggregateId` are required.

  I didn't add a full meta-schema validator library: it would bring Jackson 2, and it would accept keywords the worker then silently ignores, which is the real risk.
- **`format: date` is now implemented by the worker.** `food.item_availability.soldOutOn` used it, and the worker ignored it (the check found this).
- **Check 2:** every `@Externalized` event, whether nested in a sealed interface or from auth, must have `<EventHeaders.type>.v<version()>.schema.json`. Every schema file must belong to an event type at a version ≤ the event's; older versions stay valid for draining. A schema may also belong to a **module-internal `DomainEvent`**: the check found `catalogue.listing_submitted`, a documented internal event. Such schemas are held to checks 1 and 3 too.
- **Check 3 (sample payloads):** each record is built reflectively. Values follow the schema where it constrains them: enum, pattern (from a small list of candidate strings: ULID, `RF-…`, `DS-…`, `2026-Q3`, …), format, minimum and lengths. Samples are:
  - one with every field set;
  - one with every `@Nullable` component null (JSpecify TYPE_USE, read at run time);
  - one per Java enum constant.

  Each sample is serialized with a default Jackson 3 `JsonMapper` (the api's `default-property-inclusion: always` is Jackson's default) and validated. A type or pattern the builder can't handle fails with "teach SamplePayloads"; it is never skipped. String fields with a schema `enum` take their value from the schema, so drift in what code *assigns* to such strings is not detectable this way. Only Java enums are fully enumerated.
- **Check 4 (breaking changes)** compares with the **merge base** of HEAD and the base ref (default `origin/main`, read with the `git` CLI), so schemas added on main since the branch forked don't look deleted. The following are breaking in the same version file:
  - a removed field, a newly required field, a narrowed or added type or `enum`;
  - `additionalProperties` → false;
  - an added or changed `pattern` or `format`;
  - a raised `minimum` or `minLength`, a lowered or added `maxLength`;
  - a deleted file.

  The fix is a new `v<n+1>` file plus `version()`. Additive changes pass. In `./gradlew build` check 4 runs when the ref exists locally and is skipped otherwise (a shallow clone). The CI jobs require it.
- **CI, manual only as always:**
  - GitHub: `.github/workflows/event-schemas.yml` (`workflow_dispatch`, input `base`, full fetch).
  - GitLab: `ci/gitlab/events.yml`, job `events:schemas` (`PIPELINE_PART=events`, also part of `all`; `EVENT_SCHEMAS_BASE`, `GIT_DEPTH=0`; git installed in the Temurin image).
- **Tests:**
  - `BreakingChangesTest`: 11 breaking and 8 non-breaking cases, plus file deletion and version bumps.
  - `SchemaRulesTest`.
  - `SamplePayloadsTest`: made-up records, catching null-vs-non-null, enum, type, closed-schema and required divergences.
  - `EventContractsTest`: the real repository, plus a scratch git repository where a branch breaks v1 (fails) and then moves the change to v2 (passes).
- ~~**Found, not fixed here:** northline-auth's `user.registered` goes to Kafka without the `nl-event-*` headers (auth has no `EventHeaders` configuration). The S-26 worker would dead-letter it as poison if a consumer subscribed to `identity.user`. Nothing subscribes yet.~~ (fixed 2026-09-30, section "user.registered envelope headers" below)
- **Not done:** checking that consumers handle every schema version (the worker's mappings are per type and version); checking public webhook payload schemas (S-33 validates them at run time and in its own tests).

## 2026-09-30 — S-32 Google and Microsoft calendar two-way sync

- **Port:** `availability.application.CalendarGateway` (one per provider: OAuth authorization URL / code exchange /
  refresh / revoke, calendar list, incremental busy reads, notification channels, event create/update/delete) replaces
  the connect-only `CalendarSync` port. Adapters in `availability.integration`: `GoogleCalendarGateway` (Calendar API
  v3), `MicrosoftCalendarGateway` (Graph v1.0) and `FakeCalendarGateway`, chosen by `northline.calendar.provider`
  (`CALENDAR_PROVIDER`): `local` (default; refused under staging/prod) or `oauth`. With `oauth` a provider is offered
  once its client id and secret are set; otherwise the Studio shows "Not available yet" and connect answers 409
  `calendar_provider_unavailable`. The old 409 `calendar_sync_unavailable` placeholder is gone. HTTP clients are
  `@HttpExchange` interfaces over the JDK client that return Jackson trees (like S-23).
- **Per member, as the design shows:** each member connects their own Google and/or Outlook calendar (link = merchant
  + member + provider, V041's unique key). Only that member's jobs are written and only their busy times block their
  slots. iCal stays the read-only feed token it was (no feed endpoint yet).
- **OAuth:** authorization code + PKCE (S256) for both providers, `state` and verifier random 256-bit values; the
  request is stored server-side (`calendar_oauth_requests`, state as SHA-256, 10 min, single use — deleted on first
  use, even a refused one). The **redirect URI is on the Studio host** (`<STUDIO_ORIGIN>/api/v1/calendar/oauth/
  <google|outlook>/callback`), so the browser comes back through the studio-bff with the member's session: the
  callback is an authenticated endpoint, the state must belong to the signed-in member (else `result=failed`) and
  `MerchantAccess.require(merchant, EDIT)` is re-checked; it answers 303 to `/b/<merchant>/availability?calendar=…&
  result=connected|denied|failed|scopes|expired[&choose=1]` (relative Location, `Cache-Control: no-store`,
  `Referrer-Policy: no-referrer`). The ID token from the token endpoint is read without checking its signature (OIDC
  Core 3.1.3.7 rule 6: direct TLS response) — only `sub`/`oid` and the email/username label are used.
- **Minimal scopes and incremental consent:** Google: `openid email calendar.events.owned` on Connect (read and write
  events on calendars the member owns — busy reads, watch and write-back in one scope), `calendar.calendarlist.
  readonly` only when the member opens "Choose calendars" (`include_granted_scopes=true`; the list endpoint answers
  `{items: [], authorizationUrl}` until then). Every Google consent uses `prompt=consent` so a refresh token always
  comes back. Microsoft: `openid profile offline_access Calendars.ReadWrite` (nothing narrower can write events;
  `Calendars.ReadWrite` also lists calendars, so no second step). A grant missing the requested scopes (Google lets
  people untick) → `result=scopes`, and a grant without events is revoked at once. Reconnecting with another account
  replaces the link (old channels stopped, old grant revoked); the same account keeps the link id and adds scopes.
- **Refresh tokens at rest — new shared port:** the api had no symmetric KMS port (S-7's is signing-only in auth;
  S-10 passes a key to the storage provider). `ca.northline.shared.crypto.SecretSealer` (named interface `crypto`):
  envelope encryption, a fresh AES-256-GCM data key per value with the row id as additional data, the data key
  wrapped by the key service chosen with **the existing `KMS_PROVIDER` switch** — `local` (`KMS_LOCAL_KEY`, fixed dev
  key under local/test, 409 `encryption_unavailable` under dev without it, refused under staging/prod), `aws` (KMS
  Encrypt/Decrypt with encryption context), `gcp` (Cloud KMS encrypt/decrypt with AAD), `azure` (Key Vault
  wrapKey/unwrapKey RSA-OAEP-256; the versioned key id is stored). New variable `KMS_ENCRYPTION_KEY_ID` (required in
  staging/prod) = a **new Terraform key `tokens`** per environment that only the api's workload identity may use
  (AWS policy, `roles/cloudkms.cryptoKeyEncrypterDecrypter`, "Key Vault Crypto User"), output in `config_env`. A
  separate key rather than the `data` key: the api would otherwise gain decrypt on disks, buckets and secrets.
  `token_ref` holds the wrapping key reference (the data model's "KMS" column); `refresh_token_key` / `_enc` the
  wrapped key and ciphertext. Access tokens are only kept in memory per api instance.
- **Revoked grant → "reconnect":** `invalid_grant` on refresh (or a 401 again right after a refresh) sets
  `calendar_links.state = 'reconnect'` (+ `last_error`, `state_changed_at`); reads, writes and channel renewals stop;
  Availability shows "Access expired or was removed · reconnect to keep syncing" with **Reconnect** (starts OAuth
  again, same link), Settings › Integrations shows Reconnect for Google Calendar. Busy blocks already read **keep
  blocking** slots meanwhile (conservative: a double booking is worse than a missed slot); disconnect removes them.
  Microsoft rotates refresh tokens: a new one is re-sealed on every refresh.
- **Disconnect revokes the grant:** channels/subscriptions stopped, the member's upcoming Northline events deleted from
  their calendar, the grant revoked (Google `oauth2.googleapis.com/revoke`), the link and everything under it deleted
  (FK cascades). **Microsoft has no endpoint an app can call to revoke one user's consent** (deleting an
  `oauth2PermissionGrant` needs admin permissions; `revokeSignInSessions` signs the user out of every app), so for
  Outlook the token is destroyed and the runbook tells the member where to remove the app. Past events stay.
- **Inbound — busy times only:** `calendar_busy_blocks` holds the provider event id, start and end; never title,
  attendees, place or description (a test asserts the table's columns). Not busy: cancelled, free/transparent,
  declined by the member, and Northline's own events (by the stored mirror ids, and Google's private extended
  property / Graph `transactionId` prefix). All-day events count as busy for the Calgary day unless marked free.
  Google: `events.list` with `singleEvents=true` and a sync token (a first read has no time bounds because Google
  gives no sync token for a bounded list; only yesterday…+180 days is kept); 410 → full re-read. Graph:
  `calendarView/delta` over yesterday…+180 days (Graph expands recurrences in the window), UTC through `Prefer:
  outlook.timezone`, the delta link as cursor; the window is re-read from scratch weekly so it slides; `syncStateNotFound`
  /410 → full re-read. Sources = the calendars the member chose (primary / default on connect); a full read replaces
  the calendar's blocks, an incremental one upserts/removes. The preview subtracts busy blocks like jobs (travel buffer
  on both sides) and reports `busyBlocks`; `availability.changed` (`what = calendar`, schema enum widened, additive)
  is published when a read changed something so the search projection can recompute `next_slot`.
- **Change notifications:** one channel per chosen calendar (`calendar_channels`), opened after connect and by the hourly
  job for any calendar without one, only when `API_PUBLIC_URL` is HTTPS (providers call HTTPS only; a laptop polls).
  Google: `events.watch` with a per-channel random token (stored as SHA-256), up to 6 days, replaced (new channel +
  `channels.stop`) within a day of expiry. Graph: subscription on `me/calendars/{id}/events` with `clientState`
  (hashed), up to 6 days (< the 7-day maximum), `PATCH`ed within a day of expiry; lifecycle notifications:
  `reauthorizationRequired` → renew, `subscriptionRemoved` → recreate, `missed` → read. Endpoints `POST
  /api/v1/webhooks/calendar/google`, `/microsoft`, `/microsoft/lifecycle`: public in `SecurityConfig` (POST only) and
  routed on the api host by the Gateway (S-17 `ingress.yaml`, edge.md); rate-limited per client address
  (`WebhookRateLimiter`, moved from payments to the shared kernel for both). **Verification:** Google — the channel
  must exist, the token must match its hash (constant time), the resource id must be the one Google gave; `sync`
  messages are acknowledged. Graph — each entry's subscription must be ours and its `clientState` match; a batch where
  nothing verifies is 403, unknown entries in a mixed batch are skipped. Graph's `validationToken` handshake is echoed
  (text/plain, `nosniff`, ≤ 1024 chars) **only while one of our subscriptions is being created** (a pending channel row
  committed before the create call, 2-minute window); otherwise 403. **Dedupe:** `calendar_notifications` (Google:
  channel + `X-Goog-Message-Number`; Graph: SHA-256 of channel, lifecycle event, change type, resource id and etag),
  purged after 7 days. Verified notifications publish an internal `CalendarChanged` in the receiving transaction; the
  read runs after commit through the Modulith outbox (answers at once, retried after a crash).
- **Safety net:** `CalendarScheduler` (not under `test`) reads every chosen calendar not read for
  `CALENDAR_SYNC_INTERVAL` (5 min, the design's "two-way, every 5 min") and writes bookings back, and hourly renews
  channels and purges. Replicas share it: a calendar is locked `FOR NO KEY UPDATE SKIP LOCKED` (not `FOR UPDATE`, which
  blocks the foreign-key check of a channel inserted in its own transaction — found as a self-deadlock in the tests),
  skipped when another replica read it within half an interval; a member's write-back locks their link the same way.
- **Outbound — write-back:** the member's confirmed-and-later jobs from yesterday to +180 days (new
  `booking.api.BookingCalendar.jobs(merchant, member, from, to)`; requested and cancelled excluded) are written to
  their main calendar (Google `primary`, Graph default calendar); a content hash of text + times (`calendar_event_mirrors`)
  decides whether to `PATCH`; a mirror whose booking is gone (cancelled, reassigned, back to requested) is deleted
  unless it is already past. An event the member deleted comes back on the next change (Northline's bookings win, as
  the design says for paid bookings). Creates are idempotent: Google event id derived from the booking (a retried insert
  is 409 → update), Graph `transactionId`. No invitations or notifications are sent (`sendUpdates=none`, no attendees).
  **Why a reconciliation and not events:** the booking module publishes no `booking.confirmed`/`rescheduled`/
  `cancelled` events yet (bookings are created by the seed only), so the write-back compares the member's jobs with
  what was written, after connect and every 5 minutes. When those events exist, a listener should call the same
  write-back for the member at once.
- **Event text (PII):** the design says bookings are written "with the customer's first name and address", which is
  the ceiling: summary "<service title> · <first name>", location = the job's address line snapshot, description
  "Northline booking <ref> · réservation Northline" + a link to Studio › Appointments. Never the last name, phone,
  email, customer notes, access codes, vehicle or price. The text is fixed bilingual (no per-member locale is known).
- **Studio:** Connect → the browser goes to the consent page (`authorizationUrl` on the calendar response); the
  callback's outcome opens the Calendar sync tab with a notice (then leaves the address); connected calendars show
  "Two-way · last sync … · Blocks slots from: <calendars>" and **Choose calendars** (dialog, at least one — "Choose at
  least one calendar." in both locales like the other server rules); the preview note adds "N busy times from your
  calendar". The design's "Conflicts are resolved in Northline's favour…; you're alerted" alert is not built.
- **Schema (V043, additive):** `calendar_links.state/external_account_id/scopes/refresh_token_enc/refresh_token_key/
  write_calendar_id/last_error/state_changed_at` (+ CHECKs); new tables `calendar_sources`, `calendar_busy_blocks`,
  `calendar_channels`, `calendar_notifications`, `calendar_event_mirrors`, `calendar_oauth_requests`.
- **Configuration:** `CALENDAR_PROVIDER` (required `oauth` in staging/prod), `GOOGLE_CALENDAR_CLIENT_ID`/`_SECRET`,
  `MICROSOFT_CALENDAR_CLIENT_ID`/`_SECRET`/`_TENANT` (`common`), `CALENDAR_SYNC_INTERVAL`, `CALENDAR_WEBHOOK_RATE_LIMIT`,
  `KMS_ENCRYPTION_KEY_ID` (required in staging/prod), `KMS_LOCAL_KEY`; `KMS_REGION`/`KMS_ENDPOINT` now also read by
  the api; `API_PUBLIC_URL` required in staging/prod already. Secrets `google-calendar-client-secret`,
  `microsoft-calendar-client-secret` in Terraform's `app_secrets` (three clouds), the chart's `secretNames` and the
  api's optional `secretEnv`. A separate Google OAuth client from S-18's sign-in client (other redirect URI, keeps
  calendar scopes off the sign-in consent screen). Runbook: docs/runbooks/calendar-sync.md.
- **Tests:** `CalendarProvidersWireMockTest` (Google and Microsoft through the api: consent URL parameters, PKCE
  verifier ↔ challenge, token exchange, busy filtering, channel and subscription creation, verified/forged/duplicate
  notifications, Graph validation handshake, lifecycle renewal, 410 re-read, 401 → refresh with Microsoft's rotation
  re-sealed, `invalid_grant` → reconnect, write-back create / reschedule / cancel / recreate after a deleted event,
  incremental consent for the calendar list, disconnect with Google revocation), `CalendarSyncApiTest` (the fake:
  callback round trip, sealed token, busy block in the preview, state bound to the member and single use, denied,
  401/404, choose calendars with 422 messages, 403 for outsiders / without MFA / bookkeepers, public webhooks refusing
  forgeries), `EnvelopeSealerTest` + `AwsKmsSealerLocalStackTest` (real KMS API in LocalStack) +
  `CryptoConfigurationTest`, `CalendarConfigTest`, `BookingEventTextTest`; Studio `sync.test.tsx` (en + fr-CA) and a
  Settings reconnect test. The suite had reached Postgres' default 100 connections (each cached Spring context keeps a
  pool of 10); the WireMock test's extra context pushed it over ("too many clients"), so the shared test container now
  runs with `max_connections=300` (and keeps `fsync=off`) and that context uses a pool of 4.
- **Never run against the real services:** no Google Cloud project or Entra registration exists. Unverified against
  the live APIs: Google's acceptance of `calendar.events.owned` for `events.watch`, the untimed first `events.list`
  size on long histories, all-day and floating-time events; Graph's all-day times under `outlook.timezone="UTC"`,
  `transactionId` dedupe window, lifecycle payloads; both providers' error bodies. GCP and Azure key wrapping are
  tested with SDK mocks only (no emulator implements them); AWS against LocalStack.
- **Not done:** booking events → immediate write-back (see above); the "you're alerted" conflict notice; choosing the
  calendar bookings are written to (always the main one); a bulk re-seal command after changing
  `KMS_ENCRYPTION_KEY_ID`; the iCal feed endpoint; showing busy blocks in the Appointments week; Google app
  verification and Microsoft publisher verification (operational, before launch).

## 2026-09-30 — Fix: user.registered envelope headers; Studio tests under load

- **The envelope moved to `server/platform`** (`ca.northline.platform`): `EventHeaders` (the three header names, the wire-type rule, `of(event)` and `externalization(basePackage)` = Modulith's defaults + the headers), `EnvelopedEvent` (`eventId()`, `default version()`), and `EventType` (was `ca.northline.shared.EventType`; the four food events import it from there). The api's `config.EventHeaders` is gone; its `EventExternalizationConfig` and northline-auth's new one both return `EventHeaders.externalization(...)`, so the two producers can't drift. The api's `DomainEvent` now extends `EnvelopedEvent` (only `occurredAt()`/`aggregateId()` stay declared there); auth's `UserRegistered` implements it. Platform takes `spring-modulith-events-api` as `compileOnly` (bff and the worker don't need Modulith at run time); the worker's `EventHeaders` constants point at platform's.
- Chosen over copying the class into auth, and over making `UserRegistered` implement the api's `DomainEvent` (auth doesn't depend on the api and shouldn't). Top-level `ca.northline.platform` package, like the existing platform types the api's modules use, so Modulith sees no access to an internal package.
- **Wire type stays `identity.user_registered`, v1** (the schema file's name); no schema change, so S-34 check 4 has nothing to compare.
- **Tests:** auth `UserRegisteredEventsTest` now also asserts `nl-event-id` (= payload `eventId`), `nl-event-type` `identity.user_registered`, `nl-event-version` `1` on the real Kafka record. `:event-contracts` `EnvelopeContractTest`: auth's externalization routes `UserRegistered` to `identity.user` with those headers and the record parses through the worker's `EnvelopeParser` and validates against its schema; and **every** `@Externalized` event (api and auth, sample payload from S-34's builder) parses the same way, so a future producer event without headers fails the build. `EventContractsTest` (S-34 checks 1–4), api `EventHeadersTest`, `EventsOnKafkaTest`, `ModularityTests` pass unchanged apart from imports.
- **No consumer subscribed to `identity.user`.** Neither the backlog nor the design asks for a welcome message (S-28's "Not done" only noted the possibility; the notification matrix has no such row), so S-27's `notifications` group is unchanged and the topic catalogue gains no consumer.
- **Studio tests under load:** the flaky tests are whole flows (render → type → submit → mocked server answer). Under a parallel `./gradlew build` they took up to 4.1 s (`BusinessStep` "sends the business…", 2.7–3.8 s for `PageBuilder` "validates the tagline"), against vitest's 5 s `testTimeout`; `findBy*`/`waitFor` gave up after Testing Library's 1 s. Now: `testTimeout`/`hookTimeout` 20 s (`vite.config.ts`), `asyncUtilTimeout` 5 s (`src/test/setup.ts`), and `userEvent.setup({ delay: null })` in every test (files that used the direct API get a local `user()` helper with the same option). No sleeps; the auth countdown test's own 3 s `waitFor` limit now uses the shared 5 s. Measured side by side under the same load, `delay: null` did not make these two tests measurably faster (the time is rendering, not the keystroke timer) — the timeouts are what removes the flake; `delay: null` stays as a cheap guard for the longer typing tests. Other apps/packages keep their defaults (not reported flaky).
- **Also found:** `DashboardView.test.tsx` failed in the first minute of every hour (the seller run cut-off is now + 2 h; on the hour `clockWithPeriod` prints "4", the regex required minutes). The test accepts both now.

## 2026-09-30 — S-31 Custom domain verification and certificates for storefronts

- **Serving design: an in-cluster reconciler in the api, with shard Gateways** (option (a) of the brief, scaled like
  (b)). The api writes, in its own namespace only, one HTTPS listener per merchant domain on shard Gateways
  `northline-custom-N` (≤ 64 listeners, Gateway API's limit), one cert-manager Certificate (`nl-cd-<sha256(host)[:20]>`,
  HTTP-01) and one HTTPRoute to the consumer app. Envoy Gateway's `EnvoyProxy.spec.mergeGateways: true` serves every
  Gateway of the class from one proxy fleet behind one load balancer, so the CNAME target `pages.<zone>` never
  changes however many shards exist. Nothing cloud-specific: the same objects on EKS, GKE, AKS and kind. Rejected: a
  GitOps PR per domain (option (c): human review + manual sync in staging/prod, repository write access for the api —
  `edge.customDomains` stays for hand-pinned exceptions); on-demand TLS at a CDN (vendor-specific); a separate
  controller Deployment/Go app (another image and pipeline for ~500 lines; noted as the way to take RBAC away from the
  internet-facing api). `ListenerSet` replaces the shards once stable.
- **The backlog's "ACM certificate issuance" is not used:** ACM is AWS-only and can't give certificates to an
  in-cluster Envoy; cert-manager + Let's Encrypt (S-17) works on every cloud.
- **RBAC:** Role `northline-domain-reconciler` (gateways, httproutes, certificates; get/list/watch/create/patch/update/
  delete) bound to ServiceAccount `northline-api`, whose token is mounted only when `edge.domainReconciler.enabled`
  (projected token re-read on every call). No Secret access: cert-manager's `enableCertificateOwnerRef` makes deleting a
  Certificate delete its key. Accepted risk: RBAC can't restrict create/list by name, so the api could also modify the
  environment's own Gateway; Argo CD shows that as drift. Argo CD ignores the reconciler's objects as orphans
  (`northline.runtimeResources`); the project whitelists Role/RoleBinding (and `deploy/argocd/validate.sh` now checks
  every rendered kind is whitelisted).
- **Kubernetes client:** no library — an `@HttpExchange` client over the JDK HTTP client (list by label, server-side
  apply with field manager `northline-domains` and `force=true`, delete; 404 ignored), trusting the mounted cluster CA.
  Objects carry a spec hash annotation and are applied only when it changes; one replica reconciles at a time
  (`pg_try_advisory_xact_lock`), claim rows locked `FOR UPDATE`, DNS checks shared with `FOR UPDATE SKIP LOCKED`.
- **Verification:** TXT `_northline-verify.<domain>` = the claim's token (`nl-` + 32 base32 chars, 160 bits, new for
  every new domain) **and** the name points at us: its CNAME chain (≤ 8 hops) reaches `pages.<zone>`, or every A/AAAA
  address it resolves to is one of `pages.<zone>`'s or `DOMAINS_EDGE_ADDRESSES` (ALIAS/ANAME/flattening, apex A
  records). A stray extra address or a proxy in front = `not_pointing`. Resolver failures are "inconclusive" and never
  demote a domain. The TXT record must stay (re-checked every 6 h and on conflicts). Apex detection by name (2 labels,
  or 3 under a list of second-level suffixes incl. the Canadian provincial ones) — no Public Suffix List.
- **DNS resolver port** `merchants.application.DnsResolver` (replaces the onboarding workstream's `DomainVerifier`
  and its "pending"/"fail" fake rules): `local` in-memory zone (local/test; `pages.<zone>` → 192.0.2.10), `doh` (RFC 8484
  wire format POSTed as `application/dns-message` — mandatory for every DoH server; default endpoint CIRA Canadian
  Shield "Private", Canadian and unfiltered) and `jndi` (the JDK's `com.sun.jndi.dns`, one record type per query because
  several make it ask ANY, which resolvers answer minimally per RFC 8482; follows CNAMEs itself; `BanJNDI` suppressed:
  DNS provider only, validated names, string attributes). Our own wire codec (`DnsWire`, ~150 lines) instead of a DNS
  library.
- **States** (V085 widens V030's CHECK): `pending → verified → issuing → live`, `failed`, `expired`. `verified` = DNS
  proven but not on the edge (page unpublished, business not active, or waiting: `rate_limited`/`capacity`). Timing:
  pending checks every 5 min (first hour), 30 min (first day), 2 h, expired after 7 days (checks stop; "Check now"
  starts a new window); proven re-checked every 6 h; `failed` removes the Certificate and is retried every 6 h up to 3
  failures in a row, then only on "Check now". **Grace period** 72 h: a proven domain whose records stop pointing at us
  keeps serving, is checked every 30 min, owners are told the deadline; afterwards back to `pending` ("unverified")
  and off the edge. All values configurable (`DOMAINS_*`).
- **Publishing** still requires a proven domain (spec: "custom_domain requires CNAME verification before
  published_at"; `verified`, `issuing` and `live` count). A page whose domain fell back to `pending` can't be
  re-published until it is re-verified or removed — the literal reading of the rule, not relaxed.
- **Certificates only for pages that may serve** (active business, published page, proven domain). Let's Encrypt
  limits: `DOMAINS_ISSUE_PER_HOUR` (20) new certificates per hour across merchants, one request per page per hour
  (`custom_domain_requested_at`), certificates requested only after DNS proved the routing, a failed one removed so
  cert-manager's own retries stop. **Let's Encrypt staging outside prod** for merchants' certificates (Issuer
  `northline-acme-custom`, separate ACME account); the chart refuses the production endpoint in dev/staging.
- **Claim conflicts** (one page per domain, V016's unique index kept): when a page asks for a domain another page holds,
  the holder is re-verified in its own transaction (`REQUIRES_NEW`, committed even when the claimant's request fails):
  an unproven holder (pending/expired/failed) without its TXT record is released (owners emailed `released`); a
  proven holder keeps it, but its records are checked at once, so a DNS owner who removed its TXT starts its grace
  period; the claimant gets "That domain is already connected to another page." meanwhile. Resolver errors never
  release anything.
- **Blocklist:** `northline.ca` and below stay a format error (existing message); the environment's zone and
  `DOMAINS_BLOCKED_SUFFIXES` → new message "That domain can't be connected to a Northline page." (English only, like
  the other server messages; not in validation-rules.md). International names are stored as punycode.
- **HSTS for merchants' domains without `includeSubDomains`/`preload`** (their other subdomains aren't ours); also
  applied to the S-17 hand-pinned `edge.customDomains` routes, which previously got the full header.
- **Notifications:** new event `custom_domain.changed` (merchants.api `CustomDomainChanged`, topic
  `merchants.storefront`, key storefront id, schema `merchants.custom_domain_changed.v1`; the domain name is public
  business data, not PII) on every connect/disconnect/state/problem/grace change; a `notice`
  (`live | dns_lost | unverified | certificate_failed | expired | released`) makes the api email the owners (new
  template `custom-domain`, en/fr, TRANSACTIONAL service notice — sent whatever the Settings matrix says, like
  `webhook-disabled`), through `MerchantEmailNotices` (messaging). No SMS/push.
- **Routing contract:** `GET /api/v1/public/storefronts/by-host?host=` (GET open in SecurityConfig under
  `/api/v1/public/**`): the `GET /api/v1/storefronts/{slug}` body for a live custom domain of a published page of an
  active business, else 404; host case/port/trailing dot/Unicode normalised; `Cache-Control: max-age=60, public`;
  consumers evict on `custom_domain.changed`. The public storefront body now shows `customDomain` only when `live`
  (was: verified).
- **Studio:** the design's field and hint (the hint's target comes from the api, so it reads `pages.northline.ca` in
  prod as designed and `pages.<zone>` elsewhere), plus what the design leaves open: status line per state with the
  current problem, the records to add (CNAME / ALIAS / A + TXT, each with Copy), root-domain guidance, the grace
  deadline, last check, "Check now" (at most every 15 s server-side) and, in dev builds only, "Simulate DNS records →"
  (`POST /api/v1/dev/merchants/{id}/storefront/domain/dns`, `local` profile; publishes into the in-memory zone, checks,
  reconciles the local edge). Copy is ours, en + fr-CA. The domain field moved into `CustomDomainField`.
- **Schema additions (V085):** `merchants.storefronts.custom_domain_status` CHECK widened (drop + re-add, as V062 did);
  new `custom_domain_token`, `custom_domain_status_at`, `custom_domain_checked_at`, `custom_domain_next_check_at`,
  `custom_domain_problem` (CHECK), `custom_domain_dns_lost_at`, `custom_domain_live_at`, `custom_domain_requested_at`,
  `custom_domain_failures`; CHECKs that token and status_at exist iff a domain does; partial indexes for due checks
  and proven domains. Existing domains (dev databases) get a token and are re-checked (a previously "verified" one
  becomes pending: the old fake never checked anything). V086 unused.
- **Configuration:** `DOMAINS_DNS_PROVIDER`, `DOMAINS_EDGE_PROVIDER`, `DOMAINS_TARGET_HOST` (required in staging/prod;
  `local` refused there) and the optional `DOMAINS_*` of custom-domains.md § 5; chart `edge.domainReconciler.*` (on in
  dev/staging/prod) sets them. No new secret. Runbook: docs/runbooks/custom-domains.md; edge.md, README, local/dev/
  staging/prod.md, notifications.md, `server/.env.example` updated.
- **Operational change:** `mergeGateways` moves Envoy's proxies to a Service named after the GatewayClass, so syncing
  the add-on replaces the environment's load balancer once (new address; external-dns follows). Harmless before
  launch; do it before offering apex A records.
- **Tests:** `DomainClaimTest`, `DnsInspectorTest`, `DnsResolversTest` (DoH against WireMock speaking the wire format;
  JNDI against a UDP server), `GatewayDomainEdgeTest` (in-memory Kubernetes: shards past 64, removal and reuse,
  rationing, capacity, Ready/Failed mapping, idempotent reconcile, stable names), `HttpKubeApiWireMockTest`,
  `CustomDomainApiTest` (instructions, verification, scheduler, live + email + by-host, paused business, grace period
  with both emails, three claim-conflict cases, 403s/409/422), `DevOnboardingTest` (simulation), updated
  `StorefrontApiTest`/`StorefrontRulesTest`; email templates render in both languages; Studio
  `CustomDomainField.test.tsx`; `deploy/helm/validate.sh` (reconciler RBAC and token per environment, issuer per
  environment, five refusals, HSTS without includeSubDomains) and `deploy/argocd/validate.sh` (kinds whitelisted);
  the kind rehearsal below.
- **Rehearsed on kind** (`deploy/kind/custom-domains.sh` + `CustomDomainsKindRehearsal`, skipped unless
  `NL_KIND_API` is set; kind 1.34 with a runc wrapper node image, cert-manager 1.18.2 Bitnami builds, Envoy Gateway
  1.5.1, a local CA): the reconciler, authenticated with a token of the chart's `northline-api` ServiceAccount, wrote
  5 domains on 3 shards and 70 domains on 2 shards of 64 (the Gateway CRD accepted 64 listeners); certificates Ready
  and listeners Programmed within 6 s / 61 s; every domain served 200 over HTTP/2 through the one merged Envoy Service
  with HSTS without includeSubDomains; `targetSelectors` ClientTrafficPolicy Accepted on every shard; unknown SNI and
  TLS 1.1 refused; RBAC allowed exactly Gateways/HTTPRoutes/Certificates in the namespace (no Secrets, no Roles, no other
  namespace); removal deleted every object and cert-manager deleted the TLS Secrets. `rehearse.sh edge` now passes
  cert-manager the add-on's `--enable-certificate-owner-ref` and `--enable-gateway-api`.
- **Never run against the real services:** no public DoH resolver or name server (DoH is a WireMock stand-in built
  from RFC 8484, JNDI a local UDP server), no Let's Encrypt (a local CA on kind), no cloud load balancer, and the api
  never ran as a pod (the kind rehearsal used the ServiceAccount's token from the host). Unverified: CIRA's DoH
  endpoint behaviour; cert-manager's status on a real failed ACME order (`lastFailureTime` + `Issuing=False`, mapped to
  `failed`); HTTP-01 through the merged Gateways from the internet.
- **Not done:** the consumer app's host routing (it doesn't exist; the endpoint and contract do); SMS/push notices;
  reserved load-balancer IPs (apex A records are offered only once `edgeAddresses` is set); a Public Suffix List;
  serving behind a merchant's own CDN; a separate reconciler Deployment; Console tools for staff (take-down is SQL).

## 2026-09-30 — S-35 Shopify, Square and Lightspeed catalogue sync

- **Port:** `catalogue.application.CommerceCatalogSource` (the story's name; the backlog said `CommerceSync`) replaces
  the connect-only `CommerceSync` port and its fake. One adapter per platform in `catalogue.adapters.commerce`:
  `ShopifyCatalogSource` (Admin GraphQL, version `SHOPIFY_API_VERSION`, default `2026-07`), `SquareCatalogSource`
  (Catalog + Inventory, `Square-Version: 2025-10-16`), `LightspeedCatalogSource` (X-Series 2.0; 1.0 for token and
  webhooks), and `FakeCatalogSource` with fixture catalogues (`commerce-fixtures/*.json`). Chosen by
  `northline.commerce.provider` (`COMMERCE_PROVIDER`): `local` (default; refused under staging/prod) or `oauth`, where a
  platform is offered once its app id and secret are set (otherwise "Not available yet" and 409
  `commerce_provider_unavailable`). HTTP clients are `@HttpExchange` interfaces taking the full `URI` per call
  (shop-specific hosts), over the JDK client pinned to HTTP/1.1 (WireMock resets h2c-upgraded POSTs).
- **Connect = OAuth, redirect on the api host.** Unlike S-32 (Studio host through the BFF), the redirect URIs are
  `<API_PUBLIC_URL>/api/v1/commerce/oauth/<platform>/callback` as the story and edge.md ask: the platforms' app settings
  take one fixed URL. The callback is therefore public (no session): the single-use 256-bit state (stored as SHA-256,
  10 min, deleted on first use) names the member and business, and the member must **still** be able to manage the
  business (`MerchantMemberships`, MANAGE) when it comes back; Shopify's callback `hmac` (hex HMAC-SHA256 of the other
  parameters, sorted) and `shop` must match the store the owner typed; Lightspeed's `domain_prefix` must be letters,
  digits and dashes before it becomes a host name (SSRF). It answers 303 to `<STUDIO_ORIGIN>/b/<m>/listings/bulk?
  commerce=…&result=connected|denied|failed|expired` (`no-store`, `no-referrer`). Shopify connect needs the store
  (`your-store` or `your-store.myshopify.com`; 422 "Enter your Shopify store address (your-store.myshopify.com).").
  Connect/disconnect are owner-only (MANAGE), sync is EDIT, the list is VIEW — as before. No PKCE: the three are
  confidential-client code flows and only Square documents PKCE (for public clients).
- **Tokens at rest:** access + refresh token (+ expiry, account) sealed together as one JSON value with S-32's
  `SecretSealer`, bound to the new `integrations.id`; opened only for a call. Shopify's offline token doesn't expire;
  Square's 30-day token is refreshed when < 7 days remain; Lightspeed's short token is refreshed when < 5 min remain and
  its rotating refresh token is re-sealed. `invalid_grant` / a 401 → `connection_state = 'reconnect'` ("Access expired or
  was removed · reconnect to keep syncing" + Reconnect).
- **Import = drafts, as the design says** ("Existing SKUs are updated, new ones are created as drafts", "Draft stays
  private until you submit"). Each platform product becomes one draft through the editor's own `EditProduct` use case
  (so SKU generation, GTIN records and every save rule apply) — never submitted automatically: category, fulfilment
  and the compliance attestations can't come from a platform. A single-variant product whose SKU the merchant already
  sells is **linked** to that listing and only its price and stock change. Variants: option names pick the theme
  (Size/Taille → size, Color/Colour/Couleur → colour, both → size_colour, Length → length, else size); values are the
  option values; missing SKUs are derived from the platform's variant id (`SHO-123`), deduplicated. A barcode with a
  valid GTIN check digit makes a single-variant offer GTIN-identified (shared catalogue record, like the editor).
  Offer price = lowest variant price, stock = total. HTML descriptions are reduced to text; titles cut at a word ≤ 80.
  Images: up to 9, downloaded only over HTTPS from the platforms' image hosts (`northline.commerce.images.hosts`), no
  redirects, ≤ 15 MB; those failing the image standards (< 1000 px) are skipped. A product the rules refuse is skipped
  and listed with the editor's message ("N products couldn't be imported"), capped at 50. Each product is applied in
  its own transaction (`REQUIRES_NEW`), so one refusal doesn't roll the rest back.
- **Mapping kept by external id** (`commerce_products`: platform product → offer + content hash; `commerce_variants`:
  platform variant → Northline SKU + the inventory reference webhooks name). Links survive a disconnect so reconnecting
  re-links instead of duplicating; Shopify `shop/redact` deletes them. A listing the merchant deleted in Northline is
  not imported again.
- **Stock is one-way; the platform is the source of truth** (the design shows no two-way stock; the backlog says
  "stock changes sync hourly"). Price and stock always follow the platform. Northline never writes stock back —
  merchants record Northline sales in their POS; the next read overwrites Northline's number. A variant the platform no
  longer has goes to 0 in stock.
- **Conflicts:** title, description, images and variants follow the platform only while the listing is still a draft
  (content hash changed); once submitted, Northline's vetted content wins (re-vetting live listings on every platform
  edit would take them down; re-vetting on edit is the console's, as the catalogue workstream decided).
- **Removals hide, never delete:** a product deleted, archived or no longer active on the platform (webhook, or
  missing from a full read) hides its listing (`listing.hidden` when customers could see it) and marks the link
  `removed_at`. If it comes back, the link is restored but the listing stays hidden until the merchant publishes it.
- **Incremental sync:** webhooks where the platform has them — Shopify (registered per shop: products create/update/
  delete, inventory levels, app uninstalled; compliance topics answered), Square (app-level subscription in the
  Developer Console: `catalog.version.updated`, `inventory.count.updated`, `oauth.authorization.revoked`), Lightspeed
  (registered per store: `product.update`, `inventory.update`) — only when `API_PUBLIC_URL` is HTTPS. Verified by HMAC
  (Shopify body + app secret, base64; Square notification URL + body + subscription signature key, base64; Lightspeed
  `X-Signature` over the raw body with the client secret, hex or base64 accepted because the docs' example is neither),
  deduplicated in `commerce_webhook_receipts` (Shopify `X-Shopify-Event-Id`, Square `event_id`, Lightspeed SHA-256 of
  the body — no delivery id is sent), purged after 7 days, rate-limited per address (`WebhookRateLimiter`). A verified
  delivery publishes internal events (outbox) and answers at once; the named product is read again. Square's catalog
  notice names nothing → a full read. Uninstall / revocation disconnects at once.
- **Polling:** a full read (all active products) rather than "changed since" queries, because deletions and stock
  changes don't show in the platforms' updated-since filters: without webhooks every `COMMERCE_POLL_INTERVAL` (1 h),
  with webhooks every `COMMERCE_RECONCILE_INTERVAL` (1 day). `CommerceScheduler` checks every 5 minutes; replicas claim
  a read by moving `last_polled_at` in one conditional UPDATE. Lightspeed's families can span API pages, so its adapter
  reads the whole catalogue as one page; its single-product read falls back to a full read for families (the 2.0 API
  has no "variants of" call).
- **Rate limits:** Shopify's cost-based throttle (`THROTTLED` → wait ⌈(requested − available) / restoreRate⌉, and pace
  the next call when the bucket can't pay for it); 429 / 502 / 503 / 504 on all three → `Retry-After` (seconds, HTTP
  date or Lightspeed's ISO instant) else 0.5 s, 1 s, 2 s …, at most 5 retries, each wait ≤ 30 s; after that the read
  fails and is retried at its next due time.
- **Disconnect** revokes where possible (Shopify `DELETE /admin/api_permissions/current.json`, Square
  `/oauth2/revoke`); Lightspeed has no endpoint, so the tokens are destroyed and the runbook tells the merchant to
  remove the add-on. Imported listings stay.
- **Studio:** "Or connect" rows show "Not available yet", connected account, "Importing your catalogue…" (polls every
  3 s), the last sync ("N drafts created · N updated · N hidden (gone from …)"), how updates arrive (as they change /
  every hour), the products that couldn't be imported, Reconnect, and the callback's outcome once. Shopify opens a
  "Connect your Shopify store" dialog. Settings › Integrations counts any connected platform.
- **Schema (V052, additive):** `integrations.id/connection_state/external_account_id/scopes/token_ref/credentials_key/
  credentials_enc/webhooks/sync_status/last_error/created_count/hidden_count/sync_errors/last_polled_at/
  state_changed_at` (+ CHECKs); new `commerce_oauth_requests`, `commerce_products`, `commerce_variants`,
  `commerce_webhook_receipts`.
- **Configuration:** `COMMERCE_PROVIDER` (required `oauth` in staging/prod), `SHOPIFY_CLIENT_ID`/`_SECRET`/
  `SHOPIFY_API_VERSION`, `SQUARE_CLIENT_ID`/`_SECRET`/`SQUARE_WEBHOOK_SIGNATURE_KEY`/`SQUARE_BASE_URL`,
  `LIGHTSPEED_CLIENT_ID`/`_SECRET`, `COMMERCE_POLL_INTERVAL`, `COMMERCE_RECONCILE_INTERVAL`,
  `COMMERCE_WEBHOOK_RATE_LIMIT`; `API_PUBLIC_URL`/`STUDIO_ORIGIN`/`KMS_ENCRYPTION_KEY_ID` reused. Secrets
  `shopify-client-secret`, `square-client-secret`, `square-webhook-signature-key`, `lightspeed-client-secret` in
  Terraform `app_secrets` (AWS, Google Cloud, Azure), the chart's `secretNames` and the api's optional `secretEnv`.
  The Gateway routes `/api/v1/webhooks/commerce` and `/api/v1/commerce/oauth` on the api host also with
  `tokenClients: false`. Runbook: docs/runbooks/commerce-sync.md.
- **Tests:** `CommerceSourcesWireMockTest` (the three adapters through the api: consent URLs, Shopify callback hmac
  forged/valid, Lightspeed domain prefix, token exchange, Shopify throttling and paging, Square and Lightspeed 429s,
  webhook registration, import with images filtered by size, incremental updates by webhook, inventory webhooks,
  deleted product → hidden, full read → hidden, signature checks and duplicates, Square refresh, Lightspeed rotation
  re-sealed, 401 → reconnect, revocation on disconnect), `CommerceSyncApiTest` (the fakes: callback round trip, drafts
  with variants and images, existing SKU linked, sealed tokens, errors listed, single-use state, lost MANAGE → failed,
  webhook forged/verified/duplicate, hourly read restoring a link, disconnect, 403s for technicians / bookkeepers /
  outsiders / without MFA, 422 messages), `CommerceAdaptersTest`, `CommerceImporterTest`, `CommerceConfigTest`; Studio
  `commerce.test.tsx` (en + fr-CA).
- **Never run against the real services:** no Shopify Partner, Square Developer or Lightspeed developer account exists.
  Unverified live: Shopify's `2026-07` schema (`webhookSubscription.uri`, `media` on products), the uninstall REST
  endpoint's continued support; Square's `description_html`, sandbox behaviour, the exact signed URL; every Lightspeed
  X-Series field name (the docs site was unreachable from the build environment: `variant_parent_id`, `has_variants`,
  `variant_options`, `price_excluding_tax`, `product_codes`, `images[].sizes.original`, inventory paging by `version`,
  the webhook form fields and `X-Signature` encoding) and whether X-Series now requires OAuth scopes
  (`northline.commerce.lightspeed.scopes`, empty by default).
- **Not done:** two-way stock (Northline orders → platform); Shopify multi-location choice (stock is the total); a
  per-connection default category (the merchant picks one per draft); importing Square item options as variation
  themes (variation names become the values); compare-at prices and costs; Shopify's App Store listing, Square's
  production review and Lightspeed's add-on approval (operational, before launch).

## 2026-09-30 — S-36 POS menu import for kitchens

- **Based on S-35** (branch `catalogue/s-35-commerce-sync`, PR #41): the Square app and its OAuth plumbing are shared,
  so this branch needs S-35's code. Merge S-35 first.
- **Shared plumbing moved to `ca.northline.shared.integration`** (named interface `integration`): `ProviderHttp`
  (`@HttpExchange` clients over the JDK client on HTTP/1.1, query/form encoding, HMAC, token-call helpers), `Backoff`
  (429 / 502–504 with `Retry-After`) and the `OAuthCallback` SPI. S-35's catalogue adapters now use them
  (`CommerceHttp` delegates; `Backoff` is no longer a bean). **One OAuth callback endpoint**
  (`shared.web.OAuthCallbackController`, `/api/v1/commerce/oauth/<platform>/callback`, the S-35 path) asks each
  module's `OAuthCallback` in turn; the one whose single-use state it is completes it. Needed because Square takes
  **one redirect URL per application** and the same Square app serves the catalogue sync and the kitchens' import.
  An unknown state still ends at `/?commerce=<platform>&result=expired`.
- **Port:** `food.application.PosMenuSource`, adapters in `food.adapters.pos`: `SquarePosSource` (Catalog API,
  `SQUARE_*` of S-35, scopes `ITEMS_READ MERCHANT_PROFILE_READ`), `CloverPosSource` (REST v3, OAuth v2 with expiring
  tokens), `ToastPosSource` (menus API v2 + machine-client authentication — **partner-gated**, see below) and
  `FakePosSource` with `pos-fixtures/menu.json`. Chosen by `northline.pos.provider` (`POS_PROVIDER`): `local`
  (default; refused under staging/prod) or `oauth`, each POS offered once its credentials are set.
- **Toast is partner-gated:** there is no merchant OAuth. Northline signs in with partner credentials
  (`TOAST_CLIENT_ID`/`_SECRET`, `userAccessType: TOAST_MACHINE_CLIENT`); the restaurant enables the Northline
  integration in Toast and the owner enters its restaurant GUID (422 "Enter your Toast restaurant GUID (Toast Web ›
  Integrations)." when malformed, "Northline can't read this restaurant yet. Turn on the Northline integration in
  Toast, then try again." when Toast refuses). The adapter follows Toast's published docs as last known
  (doc.toasttab.com was unreachable from the build environment); **it has never run against Toast** and Northline is
  not a Toast partner yet.
- **Mapping:** categories → sections (matched to a section of the menu with the same name, else created at the end);
  items → dishes with price; modifier lists → modifier groups (kitchen-wide). POS min/max → the builder's rule
  (min = max → exactly N required; min > 0 → at least N required; else up to max). Square variations (Small / Large)
  become a required "Size" group priced as the difference to the cheapest; Toast menus are flattened into sections
  prefixed with the menu's name when there are several. Names/descriptions are cut to the builder's limits. Can't be
  imported (listed as problems): dishes without a fixed price, groups without options or with an option over $100 (the
  CHECK's range). Hidden/archived/inactive POS items are skipped.
- **Allergens are never taken from a POS** (the story: POS data can't be trusted for them). Imported dishes have
  `allergens` NULL (= not declared) and status draft; the item editor already requires an explicit allergen choice
  before any save, and the V090 CHECK keeps an undeclared dish from being published. The builder now tags such dishes
  **"Confirm allergens"**. Dietary tags, photos, prep time and availability are not read either.
- **Items are created hidden until approval, as today:** drafts (`vetting = draft`); publishing, the photo and the
  kitchen's approval work as in the kitchen workstream.
- **Preview and diff:** "Review import" reads the POS menu into `food.pos_imports` (the normalised menu + the diff);
  nothing is written. "Apply" re-plans from the stored menu against the kitchen's data at that moment and writes it in
  one transaction; a preview can be applied once, within an hour (409 `import_closed` / `preview_expired`), or
  discarded. **Re-import rules:** a dish imported before is *changed* only if the POS changed it since the last import
  (content hash in `food.pos_links`) **and** it differs from Northline — then only name, description, price, modifiers
  and section are written; the kitchen's allergens, photo, status and tags are kept. A dish deleted in Northline isn't
  imported again. A dish gone from the POS goes back to draft (hidden, never deleted; `food.item_availability` when it
  was visible — new `MenuBuilderService.unpublish`).
- **Connections:** Square / Clover over OAuth with a 256-bit single-use state stored hashed (10 min) and bound to the
  owner, who must still hold MANAGE when the callback comes back; tokens sealed with `SecretSealer` (S-32) and
  re-sealed on rotation; a refused refresh → `reconnect` (409 `reconnect_required` until the owner reconnects).
  Clover's callback `merchant_id` is checked (letters and digits) before it becomes part of a URL. Connect/disconnect
  are owner-only (MANAGE); preview/apply/discard EDIT (owner, cook); the list VIEW.
- **CSV:** the existing CSV import stays as it was (all rows or none, no preview) under a "CSV file" tab; the design
  has no XLSX template, so a CSV template download was added. The old note pointing POS sync to Settings › API is gone.
- **No webhooks / scheduled sync:** menus change rarely and every import needs a human review (allergens), so imports
  are on demand; re-import shows the diff.
- **Schema (V092, additive):** `food.pos_connections`, `pos_oauth_requests`, `pos_links` (section / item / group /
  option → local id + content hash + removed_at, scoped per menu for sections and items), `pos_imports`.
- **Configuration:** `POS_PROVIDER` (required `oauth` in staging/prod), `CLOVER_CLIENT_ID`/`_SECRET`/`CLOVER_AUTH_URL`/
  `CLOVER_API_URL`, `TOAST_CLIENT_ID`/`_SECRET`/`TOAST_API_URL`; Square reuses `SQUARE_*`. Secrets
  `clover-client-secret`, `toast-client-secret` in Terraform `app_secrets` (three clouds), the chart's `secretNames`
  and optional `secretEnv`; `POS_PROVIDER: oauth` in values-staging/prod. The callback path was already routed on the
  api host (S-35). Runbook: docs/runbooks/pos-menu-import.md.
- **Tests:** `PosSourcesWireMockTest` (Square: consent URL, code exchange, refresh, catalog paging, variations → Size
  group, variable price and archived items; Clover: forged merchant id, exchange, token refresh with rotation, a 429
  retried, categories/items/modifier groups; Toast: partner login once, restaurant refused/accepted, menus v2 with
  nested groups and several menus, reference maps), `PosImportApiTest` (the fakes: callback through the shared
  endpoint, preview writes nothing, apply, re-import diff with changed/unchanged/removed and the kitchen's own edits
  kept, Toast GUID messages, Clover connect/disconnect, discard, expiry, single-use state, lost MANAGE, 403s for cooks
  connecting / bookkeepers / outsiders / without MFA, 422 and 404), `PosImportPlannerTest`, `PosConfigTest`; Studio
  `pos.test.tsx` (en + fr-CA).
- **Never run against the real services:** no Square, Clover or Toast account exists. Unverified live: Square's
  `modifier_list_info` minimums and `categories[]` vs `category_id` on current API versions; Clover's OAuth v2 field
  names (`access_token_expiration`), the redirect configuration and `merchant_id` on the callback; everything about
  Toast (partner access, response shapes, `general.name`).
- **Not done:** scheduled or webhook-driven menu sync; importing POS item photos (the kitchen photo rules need own
  photos); Clover item descriptions (Clover has none); Square item options (only variations); a per-dish "don't sync"
  switch; Lightspeed Restaurant (the story names Square, Clover and Toast).

## 2026-09-30 — S-39 Re-vet approved listings when material fields change

- **"Material" (`catalogue.domain.MaterialField`).** These are the inputs of the automated checks and what customers
  decide on:
  - **price**: the offer price or any variant's price (keyed by SKU); for a service, the price or the pricing mode.
    Fixed → quote is a price change.
  - **category**: the leaf category.
  - **images**: the images customers see, in order, since the first is the main image. That is the image source
    (shared / own) plus the record's images when shared, plus the seller's own. Adding, removing, replacing or
    reordering all count.
  - **Not material:** title, description, attributes, bullets, stock, SKU, compare-at / cost, fulfilment, handling,
    returns and compliance fields. The story names price, category and images; the rest don't feed the checks.
    Content moderation of text is the console's job.
- **Same rule whoever changes it.** The editor save (`revise`), the bulk price & stock update (`restock` / `reprice`)
  and the platform sync (`syncStock`) all compare a before/after snapshot.
- **S-35 sync:** after a draft, Northline's vetted content still wins, and a sync never touches title, description,
  images or variants of a submitted listing. Price and stock still follow the platform. A **price** change from a sync
  is material, so an approved listing goes back to pending (actor `system:commerce`). A stock change is not material.
- **Transition** (`ListingState.revet`):
  - approved → **pending**, flags cleared, `submitted_at` = now, `revet_reasons` = what changed.
  - `listing.hidden` is published if customers could see the listing (search drops it); a listing the merchant keeps
    hidden publishes nothing.
  - `listing.submitted` is published, so the same `VettingOnSubmit` runs the automated checks.
  - No new event type; `listing.submitted` is not externalized.
- **While it is being re-vetted:**
  - Customers don't see it: only approved + live is visible.
  - The merchant's **live / hidden choice is kept**. Once the checks pass it returns to that state: `listing.published`
    again only if it is live. A first-time approval still makes a listing live, as before.
  - An edit doesn't withdraw it to draft (first submissions still are). A further material change adds its reasons
    and re-runs the checks.
  - Flagged → it stays pending with flags for the console, like any submission.
- **Retried events:** `vet()` now leaves a listing alone unless it is pending, and saves the outcome even when there
  is no event to publish (a hidden listing approved again).
- **Studio** (the design shows pending as the "Pending · N min" tag and "Submitted · vetting" / "In review · flagged"
  in the editor):
  - A re-vetted listing shows exactly those, since it is pending with a fresh `submittedAt`, and appears under
    "Pending vetting".
  - The editor adds a notice "Back in vetting — Changed: price and category. Customers don't see this listing until the
    automated checks pass, usually within minutes." A flagged re-vet says a reviewer looks at it instead.
  - An approved listing shows a one-line hint that changing the price, category or images sends it back to vetting.
  - This copy is ours; the design has none. en + fr-CA.
- **API (additive):** `revetReasons` (`price|category|images`) on `GET /listings` rows and on the product/service
  editor responses.
- **Schema (V054, additive):** `catalogue.offers.revet_reasons` and `catalogue.services.revet_reasons`
  (`text[] NOT NULL DEFAULT '{}'`). V053 is used by S-123 on its own branch.
- **Tests:**
  - `RevettingTest` (domain):
    - editor price / category / image order changes are material
    - title and stock are not
    - a sync price change is material, sync stock alone is not
    - bulk restock / reprice
    - a service's pricing mode counts as price
    - hidden listings re-vet quietly and stay hidden
    - re-published once approved
    - edits during a re-vet keep it pending, and reasons add up
    - drafts and first submissions behave as before
  - `RevettingApiTest`:
    - a price change → pending, `listing.hidden` + `listing.submitted`, then approved and live again with
      `listing.published`
    - a title change stays approved, while a new image re-vets
    - a hidden listing re-vets and stays hidden
    - a service moved to a regulated category is flagged `missing_licence` and shows `revetReasons` in the table
  - `CommerceSyncApiTest.aSyncedPriceChangeOnAnApprovedListingIsReVetted`
  - Studio `revet.test.tsx`: the hint, the notice (plain and flagged), no notice on a first submission, fr-CA.
- **Not done:**
  - A change to a **shared catalogue record's** images or category by its owner doesn't re-vet the other sellers'
    offers that inherit it. Only the offer being saved is compared.
  - No threshold: a 1¢ price change re-vets. The checks run in seconds, so a clean listing is back almost at once.

## 2026-09-30 — S-38 Feed sales_30d on offers and services from orders/bookings

- **What counts, over the last 30 days (a rolling window, not calendar days):**
  - **Offers:** units on goods orders placed in the window. Orders in state `cancelled` or `refunded`, and lines in
    state `refunded`, don't count.
  - **Services:** bookings made in the window (`booking.bookings.created_at`), cancelled ones excluded.
  - "Sold" means ordered or booked, not delivered or completed. That matches the dashboard's order volume, which is
    counted by placed date.
- **Where the counts come from:** two new public queries owned by the modules that own the data:
  - `orders.api.OfferSales.unitsByOffer(merchant, from, to)`, implemented by `OrdersJdbc`
  - `booking.api.ServiceSales.bookingsByService(merchant, from, to)`, implemented by `BookingInsightsQueries`

  Catalogue doesn't read `orders.*` / `booking.*` itself. That keeps it consistent with S-37's schema-ownership rule.
- **How it is kept current:**
  - `catalogue.application.SalesListener` (`@ApplicationModuleListener`) recounts the merchant's listings on
    `order.packed`, every `booking.*` progress event, `quote.accepted` and `refund.issued`.
  - A recount rewrites `sales_30d` for **all** of the merchant's offers and services in one statement per table, so
    listings with no sales go to 0.
  - It is derived, so a retried event or a duplicate is harmless.
  - A nightly job (`SalesScheduler`, 03:10 America/Edmonton, not under `test`) recounts every merchant that still
    shows a non-zero figure, so old sales age out even when nothing new happens.
  - Every replica runs the nightly job. A second run costs a few queries and needs no lock.
- **No `order.placed` or `booking.confirmed` event exists yet:** there is no checkout or booking creation in the api
  (the consumer workstream). The listener uses the events that do exist.
  - A newly placed order shows up once the seller packs it, or at the next nightly run.
  - When checkout starts publishing `order.placed` / `order.cancelled` and booking creation publishes its event, add
    them to `SalesListener`. That is a one-line handler each.
- **Local data:** the dev seed's fixed `sales_30d` numbers (31, 28, …) are not linked to any seeded order line or
  booking (those rows have no `offer_id` / `service_id`). The first recount for a seeded business therefore shows 0.
  These are the real numbers; the old ones were decoration.
- **Studio:** no change. The Listings table already shows `sales30d` in its "30-day sales" column.
- **Schema:** none. `sales_30d` already existed (V050); the existing `merchant_id` indexes serve the per-merchant
  update. No migration was needed.
- **Tests:** `SalesThirtyDaysApiTest`:
  - `order.packed` recounts units per offer: in-window counts; cancelled, refunded orders, refunded lines, older than
    30 days and other merchants' lines don't
  - `booking.completed` recounts bookings per service: cancelled and older than 30 days don't count
  - the nightly run lets a stale figure age out to 0

## 2026-09-30 — S-37 Merchants public query API to replace direct SQL reads in other modules

- **New `merchants.api` queries.** Both are implemented by `merchants.persistence.MerchantDirectoryQueries`:
  - `MerchantDirectory.profile(id)` returns type, tier, status (`active()`), own take rate and province, as the
    lower-case column codes.
  - `MerchantVerifications` has two methods:
    - `hasVerifiedLicence(id, registry, at)`: a verified licence or registry row, registry matched case-insensitively,
      not expired at `at`.
    - `latest(id, checkType)`: a verified row first, then the most recently updated one.
  - Both queries are the ones the other modules used to run themselves, moved and unchanged.
- **Replaced cross-module SQL** (each module keeps its own small port; only the adapter changed):
  - catalogue's licence check: `MerchantLicenceQueries` became `catalogue.adapters.MerchantLicences`, which uses `MerchantVerifications`
  - messaging's type and tier: `MessagingMerchantProfiles` became `messaging.adapters.DirectoryMerchantProfiles`, which uses `MerchantDirectory`
  - food's approval and food-safety evidence: `KitchenMerchantFactsJdbc` became `food.adapters.DirectoryKitchenMerchantFacts`
  - payments' tier and take rate (`MerchantTierQueries`) and the merchant's province (`TaxRepository.merchantProvince`, from S-21): now
    `payments.infra.MerchantTierLookup`. `MerchantTiers` gained `provinceOf`.
- **Two reads go the other way, to avoid module cycles** (Modulith `verify()` rejects cycles):
  - **payments → merchants:** merchants already depends on payments (Connect, payouts, tax summary, bank linking), so
    payments can't call `merchants.api`. Payments declares what it needs in **`payments.api.MerchantBillingFacts`**
    (tier, take rate, province). The merchants module implements it in `merchants.integration.PaymentsBillingFacts`
    over `MerchantDirectory`. The SQL still lives only in merchants, which is the story's aim; only the interface sits
    on the payments side.
  - **merchants → catalogue.categories:** merchants' onboarding taxonomy, selected categories and compliance "required
    for" read `catalogue.categories` by SQL. Catalogue now calls `merchants.api`, so the category query is declared in
    **`merchants.api.CategorySource`** and implemented by `catalogue.persistence.CategorySourceAdapter`. The former SQL
    joins became two steps: merchants' own rows, then the categories by id.
- **The rule:** `SchemaOwnershipTests` is an ArchUnit rule over every class in `ca.northline` except `ca.northline.tools`,
  the Gradle seeding tasks.
  - ArchUnit doesn't expose string literals, so the condition reads each class file's constant pool with the JDK
    class-file API (`java.lang.classfile`). Text blocks and the literal parts of concatenated SQL both land there.
  - A string counts as SQL when it contains select / insert into / update / delete from / from / join. A class fails
    when such a string names `<module>.<table>` for a module other than its own.
  - Event names such as `orders.order_ready` are not SQL and are not flagged.
  - `events` is the platform outbox and is not a module schema.
  - `SchemaOwnershipDetectorTest` checks the detector against a fixture: a text block, concatenation, the class's own
    schema, and a non-SQL string.
- **Allowed exceptions (listed in the test):** the kitchen live board in `food.persistence` (`KitchenTicketJdbc`,
  `KitchenOrderLinesJdbc`, `KitchenNavBadges`) joins `orders.*` and `fulfilment.*`.
  - orders already depends on food, so an `orders.api` call would be a cycle.
  - Moving it needs its own design: an SPI implemented by orders, or a food-side read model fed by order events.
  - Left as a follow-up rather than rewriting the live board inside a merchants story.
- **Outside the api monolith (not covered by the rule):** these are separate deployables that share the database and
  can't call an in-process Java API. A read model or an HTTP endpoint would be their own stories.
  - `server/worker` `JdbcRecipients` reads `merchants.merchant_members` / `merchants.merchants` for notification recipients.
  - `server/auth` `JdbcUserAccounts` reads `merchant_members` for the token's `merchants` claim.
- **Docs:** BACKEND_CONVENTIONS § 2 describes the rule and the two ways to read another module.
- **Tests:**
  - `MerchantQueryApiTest`:
    - profile fields
    - the billing facts
    - paused is not active
    - a licence must be verified, unexpired and for the right registry (case-insensitive)
    - a verified evidence row is preferred
    - `CategorySource` by ids and by roots
  - `SchemaOwnershipTests`, `SchemaOwnershipDetectorTest`, `ModularityTests`
  - the existing payments, messaging, food, catalogue and merchants API tests, unchanged.
- **Schema:** none.

## 2026-09-30 — S-123 Catalogue media: check merchant ownership before serving draft images

- **The bug:** `GET /api/v1/merchants/{merchantId}/media/{mediaId}` checked that the caller was a member of
  `{merchantId}`, then loaded the image by id alone. A member of business B could read business A's unvetted upload
  through B's own path if they learned the id.
- **Rule:** a listing image is served to members of the business that uploaded it (`catalogue.media.merchant_id`) and
  to anyone else only once it is **approved content**:
  - (a) an own image (`offers.own_images`) of an **approved** offer of the uploading business, or
  - (b) an image of a **locked** catalogue record (`catalog_products.image_set` with `locked = true`), meaning brand-owner or
    platform-curated content, such as the seeded Bosch record.
  - Approval is read from the listings on every request, not stored on the image. An image that leaves an approved
    listing, or whose listing goes back to pending (S-39), becomes private again. Customers don't see a pending
    listing anyway.
  - It must be the **uploader's own** approved offer. When seller B's listing that inherits A's shared GTIN record is
    approved, that does not publish A's images. Otherwise anyone who knows a product's GTIN could publish another
    seller's unvetted photos.
- **Responses:**
  - Another business's unapproved image: **403** ProblemDetail `code: forbidden`, detail "This image belongs to another
    business and hasn't been approved yet." (our copy).
  - Unknown id: 404, as before.
  - The owner's path used by an outsider: still 403 `not_a_member`.
- **Storefront:** there was no public route for listing images, so this adds `GET /api/v1/public/catalogue/media/{mediaId}`,
  open under the existing `/api/v1/public/**` rule.
  - It serves approved images only, and anything else is **404** (not 403), so ids can't be probed.
  - `Cache-Control: public, max-age=3600`, kept short so that an image going private (S-39) drops out of caches within an hour.
  - Plus `nosniff`, which the Studio route now also sends.
- **Editor and GTIN lookup:** `GET …/catalogue/products/lookup` and the product editor's shared-record images leave
  out images the caller may not load, instead of returning URLs that would 403. So a second seller of a new GTIN sees
  the record's text right away and its photos once the first seller's listing is approved.
  - `LookupCatalogue.byGtin` now takes the merchant id.
  - `MediaVisibility` (catalogue application) holds the rule for the endpoint, the lookup and the editor.
- **Schema (V053, additive):** two partial GIN indexes for the lookup: `offers(own_images) WHERE vetting = 'approved'`
  and `catalog_products(image_set) WHERE locked`. No new columns.
- **Other file endpoints checked for the same bug:** none had it. Each already looks the file up by merchant *and* id:
  - kitchen menu-item photos (`MenuStore.item(merchantId, itemId)`)
  - message and help-case attachments (`messaging.attachments where merchant_id = :m`)
  - onboarding / verification documents and storefront logos (`merchants.documents where merchant_id = :m`; a logo must be the merchant's own `logo` document)
  - dispute evidence (loaded through the merchant's dispute)
  - booking job photos, which have no download route, and attaching them checks the merchant.

  Regression checks were added for kitchen photos and dispute evidence: another business's own path returns 404. The
  tests for documents and attachments already covered this.
- **Tests:** `VettingAndMediaApiTest.Ownership`:
  - a draft image is 403 for another business (their path) and for an outsider on the owner's path, and 404 publicly
  - once approved, it is public (with public caching) and visible to other businesses, while the same business's
    other drafts stay private
  - locked-record images are approved
  - the GTIN lookup hides the first seller's unvetted photos until approval
- **Not done:** the consumer app doesn't render listing images yet, so nothing calls the public route today. There is
  no signed or CDN URL (S-10's `presignGet` is still unused).

## 2026-09-30 — S-42 Elasticsearch indices listings_en / listings_fr with analyzers and synonyms

- **Layout as versioned files, not code:** `deploy/search/listings.json` (a `schema` version, settings, mappings), `analysis-<lang>.json`, `synonyms-<lang>.txt` — the same place and style as `deploy/kafka/topics.yaml`. A new plain library **`server/search-index`** (package `ca.northline.searchindex`, outside the api's `search` module so Modulith doesn't mix them) packages them as `classpath:search/` and holds `IndexLayout` (renders one definition per language), `ListingIndices` (alias/index/mapping/synonym operations on the official Elasticsearch 9 Java client from the Spring Boot BOM) and `IndexBootstrap`. The worker (bootstrap Job, S-43 indexer, S-71 reindex) and the api (S-44) share it, so the writer, the reader and the tests use one copy.
- **One index per language, same fields and analyzer names** (`nl_text`, `nl_text_search`, `nl_prefix`, `nl_keyword`): only the analysis differs, so every query works on either index. English: possessive, lowercase, ASCII folding, English stop words, Porter stemmer (`light_english`/KStem left `mechanics` unstemmed, so "mechanic" missed "Mechanics"). French: elision (`l'`, `d'`, `qu'`, `jusqu'` …, case-insensitive), lowercase, **ASCII folding before stop words and the light French stemmer** — accents never decide a match, at the cost of a few accented stop words (`à`, `été`) being indexed; synonyms sit after folding so rules may be written with or without accents.
- **Aliases + versioned indices:** readers and writers use only `listings_en` / `listings_fr`; each points at `listings_<lang>_v<schema>_<yyyyMMddHHmmss>`. Each index records `_meta.northline` (schema, SHA-256 of the canonical analysis and of the mappings); the bootstrap compares hashes rather than reading settings back (Elasticsearch normalises them). New fields are added in place (`PUT _mapping`); a changed analysis, schema bump or incompatible mapping is **"reindex required"**, reported and never done by the bootstrap (S-71 does it). `dynamic: strict` so a writer can't silently invent fields. `auto_expand_replicas: 0-1` (one node locally, a replica on Elastic Cloud's two zones), one shard (the catalogue is small; revisit past a few million documents).
- **Synonyms through the Synonyms API, not files on the nodes:** Elastic Cloud has no access to the nodes' config directory, so the repository files are loaded into the synonym sets `listings-synonyms-en|fr` (`PUT _synonyms/<set>`), which `synonym_graph` filters reference with `updateable: true` in the search analyzers only; replacing a set reloads them at once — "a file you can reload" without a reindex or restart. French ↔ English pairs live in both files. `i18n.synonyms` (DATA_MODEL) isn't read yet: no screen writes it; the files are the source until a console editor exists.
- **Document fields** fixed now for S-43/S-44 (runbook § 1): kinds `service | product | food | merchant` (ARCHITECTURE's "dish" is `food`, as the backlog's kind filter says), `categoryPath` (root → leaf ids), money in cents, `trustTier` + numeric `trustRank` (for boosts), `openHours` as `integer_range`s of minutes in the Edmonton week and `deliveryCutoffMinute` / `soldOutOn` / `pausedUntil`, so "open now", "on tonight's run" and "sold out today" are evaluated at query time (no nightly re-index for the clock); `location` + `serviceRadiusKm` for distance and service area; `vetting` / `status` / `merchantStatus` kept as fields although only approved, live, active listings are ever indexed (filters stay explicit). Two completion fields: `suggest` (listing and merchant names, contexts `market` + `kind`) and `suggestCategory` (category names, context `market`); `name.prefix` is `search_as_you_type`.
- **Bootstrap Job** `northline-search-indices-<hash>` (hash-named hook objects as S-15, pre-install/pre-upgrade, weight/wave −9 right after the Kafka topics Job), worker image, `SearchIndicesCommand apply|plan|verify` (exit 3 = drift under `verify`); hook ConfigMap with the `ES_*` keys of `configEnv`, hook ExternalSecret/Secret with `ES_PASSWORD`. `apply` exits 0 when a reindex is required (logged `REINDEX REQUIRED`): additive changes must not block releases, and a reindex is an operator's decision. Disabled on the kind rehearsal (no Elasticsearch). `./gradlew :worker:searchIndices` for local runs. No new variables.
- **validate.sh:** the two Job checks render first and grep the result; `helm template | grep -q` under `pipefail` failed at random when grep closed the pipe early (the existing Kafka topics check flaked the same way and is fixed too).
- **Least-privilege role** documented (search.md § 5: `monitor`, `manage_search_synonyms`, `listings_*` create/manage/read/write): the S-3 `elastic` superuser stays until the Elastic Cloud deployment exists.
- **Tests:** `IndexLayoutTest` (both languages, names, hashes, synonym parsing, create body); `IndexBootstrapTest` on Testcontainers Elasticsearch 9.1 (the compose image): empty cluster → plan reports all missing and changes nothing; apply creates the sets, then versioned indices behind the aliases; second run in sync; French elision/folding/stemming and English possessives through `_analyze`; cross-language synonyms (`pain au levain` ↔ `sourdough`, `mobile mechanic` → `Mécanicien mobile`) in both indices; a changed synonym file is live at once without a reindex; a new field is added in place; a changed analyzer or an incompatible field type → reindex required, nothing touched. Worker `SearchIndicesCommandTest` (exit codes against its own empty cluster).
- **Not done / never run for real:** no Elastic Cloud deployment or credentials exist (known open item) — nothing has run against Elastic Cloud, only against local/Testcontainers Elasticsearch 9.1 with security off; the `northline_app` role is documented, not created; the reindex itself (S-71); `i18n.synonyms` and a console synonym editor.

## 2026-09-30 — S-43 Search indexer consumer (catalogue, food, merchants, trust events)

- **Documents are built from the read side by direct read-only queries in the worker** (`DocumentSource`), not from an api endpoint: the worker already shares the database with the api and reads its tables for notifications (S-27 `JdbcRecipients`), there are no service-to-service credentials, it saves a hop per event, and the reindex (S-71) can stream whole merchants through the same queries. Module boundaries: the worker writes nothing in the api's schemas (only `search.sync_state`); the queries are the only place that knows those columns, reviewed against V004/V030/V050/V090/V091/V041/V073.
- **Against S-37's rule?** S-37 now forbids SQL on another module's schema *inside the api* (`SchemaOwnershipTests`). The worker is a separate deployable with its own precedent (S-27 `JdbcRecipients`), its queries are read-only, and a read endpoint would put a synchronous api call (and service credentials) in every event's path. If the api's schemas are to become private to it, the follow-up is a projection feed from the api (an internal `GET /internal/search/documents?merchant=` built from the modules' `api` packages) that `DocumentSource` would call instead — the rest of the indexer is unchanged.
- **Events name a scope; the rows decide.** `listing.*` → that listing, `food.item_availability` → that dish, everything else (menu published, kitchen paused/resumed, `merchant.*`, storefront, custom domain, review, availability) → the whole merchant. The payload's content is never trusted for the document, so duplicates, DLQ replays and events out of order converge on Postgres. A merchant-wide refresh also asks the index for the merchant's ids, so rows deleted from Postgres (a dish) disappear.
- **Idempotent by version:** each refresh takes `pg_advisory_xact_lock('search:<merchant>')`, then a version = Postgres `clock_timestamp()` in µs, and writes with `version_type=external_gte` — deletes included. Refreshes of one merchant are serialised across replicas, the sweep and the reindex, so a later version never carries older data; Elasticsearch refuses an older snapshot (409 counted as `stale`, not an error). Chosen over per-document compare-and-set (`if_seq_no`), which needs tombstone documents for "hidden" to be safe.
- **Visibility = what customers see:** active merchant with a province (the market), `approved` + `live` listings, `published` + `approved` dishes on a `live` menu, the merchant's own document once its page is published. **Merchant paused/suspended, vetting rejection, hidden or deleted listings and hidden menus delete documents; a kitchen pause does not** — it is minutes to hours, customers still browse the menu ("Not accepting orders right now"), so it is `pausedUntil` on the documents and the search API's "open now" excludes it. Sold out today stays indexed with `soldOutOn` (it comes back tomorrow without an event).
- **Topics:** consumer `search-indexer` now also reads `food.kitchen`, `merchants.merchant`, `trust.review`, `availability.availability` (+12 retry topics → 92 topics, within one Event Hubs Premium processing unit's 100). The listener moved to `ca.northline.worker.search`.
- **The reconcile sweep** (new): the catalogue has no `listing.updated` event on the wire, reviews have no "created" event, and hours, locations and merchant status changes raise none either, so every minute the worker refreshes the merchants whose rows changed (`updated_at` of services, offers, catalogue records, dishes, menus, kitchen settings, opening hours, merchants, storefronts, locations, availability rules; reviews created/replied/reported). Watermark and a 5-minute lease in **`search.sync_state`** (V120, new `search` schema), 2 minutes of overlap for late commits, 500 merchants per sweep. Chosen over adding `listing.updated` to the seven catalogue save paths and new food/trust/merchant events owned by other workstreams: one mechanism covers them all within a minute; publishes, hides and deletions stay event-driven (< 5 s). `SEARCH_RECONCILE_ENABLED`, `SEARCH_RECONCILE_EVERY` (worker, optional).
- **One producer change (food):** deleting a visible menu item now publishes `food.item_availability` (`visible=false`) — a deleted row is the one thing the sweep can't see. Nothing else in the api changed.
- **Schema additions (V120, search range):** `merchants.locations` (merchant_id PK/FK, `geom geography(Point,4326)`, `service_radius_km`, `source manual|geocoder|seed`, `updated_at`) — no geocoder exists (addresses are text in the onboarding profile), so rows come from the console/SQL until one does; a kitchen without a radius uses `food.kitchen_settings.radius_km`; `search.sync_state`; `updated_at` indexes the sweep uses. Dev seed **`db/seed-dev/V121__search_locations.sql`** (points for the three V100 businesses) — V121 rather than V109 so the file stays in the search range.
- **Flyway and the dev seed:** V120 is the first migration above the dev seed's numbers (V100–V108), so on a database that has one without the other Flyway now sees the seed as "missing" (migrated first) or "out of order" (seeded first). The `local` profile and `DbTool -Pdb.devSeed=true` apply the seed **out of order**; `DbTool` without the seed on a *local* database and the `test` profile (one database for the `test` and `local` contexts) ignore `*:missing` besides Flyway's default `*:future`. Deployed databases (a deployed profile or a non-local host) keep strict validation. The consumer range (V110–V119) needs the same, so it is fixed once here.
- **Document contract** `ca.northline.searchindex.ListingDocument` (record with a Lombok builder) in the shared library: written by the worker, read back by the api (S-44). Text per language: `*_i18n->>'<lang>'`, falling back to the default-language column; category names French → English fallback. Food documents carry the kitchen's approved categories (cuisines). `openHours`: minute-of-week `integer_range`s in Edmonton time, members' ranges merged, past midnight carried into the next day. `deliveryCutoffMinute` from the seller's onboarding answer `sameDayCutoff` for `pooled` offers (the design's "On tonight's run"; `orders.delivery_windows` is empty until fulfilment exists). `imageKey` is opaque (`media:<id>` / `object:<key>`): no public media URL exists yet. Completion inputs start at every word ("sour" finds "Country sourdough"); weight = trust rank × 100 + rating × 10 + recent sales (≤ 50).
- **Tests:** `SearchIndexerTest` on Kafka 4 + migrated PostGIS + Elasticsearch 9 (Testcontainers), the whole worker: a published service is in both indices within 5 s with every field (French name and categories, tier, rating, location, hours, completion) and gone after `listing.hidden`; a stale event for a live listing still indexes it (rows decide); an older snapshot never overwrites a newer document and a repeated refresh is idempotent; a paused merchant loses every document and gets them back; a vetting rejection removes the listing; a kitchen's dish in both languages with prep time, fulfilment, dietary/allergens and the kitchen radius, sold out kept with its date, the pause recorded, a deleted row removed by a merchant-wide refresh; a price edit and a new review with no event arrive through the sweep. `OpenHoursTest` (week wrap, overnight, merge, completion inputs). api `MenuApiTest`: deleting a live dish publishes `food.item_availability visible=false`.
- **Not done / never run for real:** nothing has run against Elastic Cloud (no deployment or credentials); no geocoder (locations by hand); `i18n.content_translations` isn't read (no screen writes it); `next_slot` / "available today" for services (needs the booking calendar); pooled-run windows (`orders.delivery_windows`); per-member hours for services (merged per merchant); merchants with more than 10 000 documents (the id lookup of a merchant-wide refresh reads 10 000).

## 2026-09-30 — S-45 Consumer shell: header, location pill, EN/FR, cart, account menu (consumer-bff, consumer app foundation)

Contracts for the stories that follow: [CONSUMER_WEB_PLAN.md](CONSUMER_WEB_PLAN.md).

- **consumer-bff = a profile of server/bff, not new code.** `SPRING_PROFILES_ACTIVE=<env>,consumer` (profile added
  last; the chart's new per-app `profiles` list does it). The studio client registration and the Studio's cloud cookie /
  required secret moved into `!consumer` documents so the two never mix; `application-consumer.yml` sets client
  `consumer-bff` (registration id `northline`, matching the redirect URI S-122 already registered), scopes openid profile
  orders bookings, cookie `NL_CONSUMER` / `__Host-NL_CONSUMER`, Redis namespace `nl:consumer-bff`, port 8081. Deployed as
  `northline-consumer-bff` from the bff image (no image of its own; `promote.sh` writes the bff's digest for it).
- **Guests** (`northline.bff.guests`): `/api/**` is permitted without a session and relayed without a token (the api
  decides: public paths answer, the rest 401); CSRF still applies to their POSTs. `GET /bff/session` answers 200 with
  `user: null` instead of 401 — a 401 on every page view for most visitors would be noise, and the page needs the guest id.
- **Guest id** (the "anonymous session"): 128 random bits (`g_` + base64url) in the BFF session, created by
  `GET /bff/session` only (an `/api` call never creates a session: bots and the SSR server stay sessionless), kept
  through sign-in (Spring's session-id change keeps attributes), relayed as `X-Northline-Guest`. It keys the guest's
  cart (S-51 contract); it is not authentication. The relay now drops the browser's own `Authorization`,
  `X-Northline-Guest` and `X-Dev-User` for both BFFs (the Studio never sent them).
- **IP city:** the consumer-bff reuses S-19's `CLIENT_CITY_HEADER`; `/bff/session` returns it (URL-decoded, control
  characters removed, ≤ 60 chars) as `location.city`. Display-only, same accepted risk as S-19.
- **No MFA for consumer tokens:** nothing in the BFF checks `acr`; the api already requires `acr=mfa` only on merchant
  and console endpoints.
- **`pages.` host:** `/api` and `/bff` go to the consumer-bff (guest browsing), `/oauth2` and `/login` don't — the
  consumer-bff client has one redirect URI (the apex), so signing in happens on the apex. Merchants' own domains
  (S-31 reconciler) still route only to the consumer app; they get the relay when S-54/S-63 need it.
- **auth:** `CONSUMER_BFF_SECRET_HASH` is required in the cloud now (was optional until the BFF existed, S-122); the
  client is no longer `optional`. Local: the consumer dev server (:3000) is a redirect URI, a CORS origin and a WebAuthn
  origin. New secret `CONSUMER_BFF_SECRET` (`consumer-bff-secret`): Terraform `secret_env` (three clouds), chart
  `secretNames`, `apps.consumer-bff.secretEnv`, kind values.
- **Consumer app: TanStack Start SSR** (CLAUDE.md, SEO for S-63). Server-rendered HTML is identical for every visitor:
  loaders fetch public data as a guest through the consumer-bff (`NL_BFF_URL`); session, cart, account values and
  location load in the browser. `@tanstack/react-router-ssr-query` added for dehydration/hydration.
- **Language:** cookie `nl.locale` (else Accept-Language) read on the server via `createIsomorphicFn`, so French pages
  are rendered in French; the toggle switches in place (no reload) and writes the cookie.
- **Location pill logic** (the design's `_locate`): saved address → browser geolocation (asked on load, 3.5 s timeout,
  a 4 s safety fallback as in the design) → CDN IP city → **Calgary**. The design's fallback label "Beltline, Calgary"
  is a neighbourhood nobody chose; the fallback says "Calgary" (the story says "fallback to Calgary"). There is no
  reverse-geocoding endpoint yet: coordinates are named by `GET /api/v1/geo/reverse` when it exists (S-47), else by
  the nearest live market within 40 km (Calgary, Edmonton, Airdrie — the Location screen's list). A fallback shows the
  kicker "Deliver to" and the title "Delivery location" (the design reused "Detected from your device…" for its
  fallback, which would be untrue). The detected place is remembered for the visit (sessionStorage); a chosen address
  (S-47) in localStorage.
- **Header details the design leaves open:** the location pill, nav links, cart and menu items are links (crawlable,
  open in a new tab); the current section's link gets `aria-current="page"` (text colour only); the cart's accessible
  name carries the count ("Cart, 3 items"); the language button is labelled "Switch language — Français" and shows the
  current language (EN/FR) as the design does; on the sign-in pages the header keeps brand/location/search/nav/cart but
  not Sign in / Create account (design: `signedOut` hides them on `auth`). While the session loads, the account slot
  is a skeleton (no flash of "Sign in"). Phones: the search field takes its own row.
- **Account menu:** the design's items, sections and order; values ("3 active", "Visa ··4471", …) and the points card
  come from `GET /api/v1/me/account-summary` (a contract for S-58/S-59; missing → no values). The header line shows
  "email · reliability 4.9" only when the summary has a reliability. "Add photo" links to Profile (S-59). "Not you?"
  isn't in design 06's consumer menu, so it isn't there. Sign out = `POST /bff/logout` + northline-auth
  `POST /api/auth/sign-out`, then a full reload as a guest.
- **Footer:** design 06 has one, so a placeholder ships: company line, Privacy and Terms (the verbatim design 09/10
  pages, now also generated into `web/apps/consumer/public/legal` by `scripts/legal-pages.mjs` until S-63), the language
  switch, "Sell on Northline" / "Offer a service" / "Run a kitchen" → `/sell?type=…` (S-61). The prototype's
  "← Direction" link is not part of the product.
- **Routes** for every design-06 state (table in CONSUMER_WEB_PLAN.md), each a `ScreenPending`; home too (S-46 owns its
  content). Orders & bookings is `/account/orders`; account tabs are `/account?tab=`.
- **Shared code:** `@northline/client` (new package) holds the Studio's `http.ts`/`forms.ts` (+ `setHttpBase` for SSR,
  `isNotFound`); the Studio's `lib/` files re-export it. The UI kit's prototype consumer components (SiteHeader,
  LocationPill, AccountMenu, SearchBar) were rewritten to the design with tokens-only CSS (`styles/site.css`) and
  en/fr copy; `SiteLink`/`SiteLinkProvider` let the app route kit links; `useGeolocation` moved into the app.
- **Migration ranges:** V110–V119 consumer, V120–V129 search, consumer dev seed `V109`. S-45 adds no migration.
- **Not done / not verified:** Storybook browser tests (interaction + a11y) of the new stories were not run here (no
  Chromium in the sandbox) — `pnpm test-storybook` in CI; the consumer bundle is one ~590 kB chunk (the UI kit barrel
  pulls DataTable/Chat in) — split when screens land; merchant custom domains get no BFF routes yet; the api has none of
  the consumer endpoints listed in CONSUMER_WEB_PLAN.md (cart, account summary, geo reverse…), so the header shows no
  count/values until they land.

## 2026-09-30 — S-62 Consumer auth pages (sign in, register, OTP, MFA) on the auth JSON API

Built on S-45 (branch `web/s-62-consumer-auth` from `web/s-45-consumer-shell`).

- **Screens:** `/sign-in` and `/register` (design 06 `auth`): the pitch (kicker, hero, three numbered points, support
  line) and the card (title, "Back to browsing" → `next` or home, three progress bars, sub-line, the step, the legal
  line). Copy is the design's; French from its `T(…)` pairs where they exist, the rest written in fr-CA (points,
  field labels, second-factor options). Both pages are `noindex`.
- **Sign in = mobile → 6-digit code → signed in** (the design's `authNext` skips `mfa` for sign-in), or "Sign in with a
  passkey" (discoverable, `acr=mfa`). New auth endpoints `POST /api/auth/sign-in/code` (send / resend / call) and
  `/code/verify` after the existing `POST /api/auth/sign-in`; the code goes to the **account's** mobile (so a Google
  sign-in known by email continues at the code: "Enter the 6-digit code we sent to the mobile number on your
  account."). Unknown accounts: an unsent code (`PhoneCodes.unsent`) and the same answers. The code sender moved out of
  `RegistrationService` into `PhoneCodes` (shared). Sessions log `method = phone_otp`, `acr` null.
- **Register = full name, mobile, email (receipts), terms → code → second factor → done.** "Full name" is one field (the
  design's): the last word is the last name, the rest the first name; a one-word name answers the rule's own message
  "Last name is required." "Send code" stays disabled until mobile and terms are filled (design `authIncomplete`); the
  other rules show after touch/submit with "N things need attention.". Second factor: Passkey (recommended), Authenticator
  app, or **SMS code · Backup only = no second factor** → new `POST /api/auth/register/complete` (account `mfa_primary =
  sms`, session without `acr`). Registration's session now records `acr=mfa` only when a second factor was used (it was
  hard-coded). Done → "Set my address" → the BFF hand-off to `/location`.
- **Business apps keep requiring a second factor:** `northline.auth.mfa-required-clients` (`studio-bff`, `console-bff`)
  get no authorization code for a session without one (`MfaRequiredClients` filter → that app's sign-in page, where
  signing in again with a factor replaces the session). `TokenClaimsTest`'s single-factor token case now uses the
  consumer-bff (the Studio's client no longer issues one).
- **Which sign-in page:** `northline.auth.consumer-login-page` (`${CONSUMER_ORIGIN}/sign-in`) for the clients in
  `consumer-clients` (`consumer-bff`) — unauthenticated authorization requests land there instead of the Studio's. The
  mobile apps (S-29) still use the Studio's page (their `continueTo` flow); moving them is a follow-up with the apps.
- **Google / Apple (S-18) on the consumer site:** "Apple" and "Google" buttons (the design's order) open
  `/oauth2/authorization/<provider>?app=consumer`; the auth server prefixes the provider `state` with `consumer.` (it
  survives Apple's cross-site form_post, unlike a session attribute) and returns to the consumer's `/sign-in` /
  `/register` (and `?error=` there). A phone-code sign-in does not complete a pending Google/Apple link (only a second
  factor does, as S-18 decided); a consumer can always continue at the code step.
- **Schema (V110, consumer range):** `identity.sessions.method` CHECK widened with `phone_otp` (drop + re-add of
  `sessions_method_check`; no row changes).
- **`spring.flyway.out-of-order: true` under `local`** (api and auth): V110 is the first migration above the dev-seed
  range (V100–V109); a database migrated without `local` (the shared test database, a developer's) has V110 before the
  seed files, which Flyway would otherwise refuse ("resolved migration not applied"). Only the `local` profile. The api
  tests (one shared database for `test` and `test,local` contexts) also ignore the seed rows as `missing` migrations.
- **Shared code (`@northline/auth-kit`, new package):** the Studio's `features/auth/{api,errors,webauthn,useCountdown,
  rateLimit}` moved there (plus the Google/Apple/passkey marks, `safeNext`, `bffLoginUrl`, `appAuthorizationUrl`,
  `authUrl`/`endAuthSession` with `configureAuthOrigin`, and the new consumer calls). The shared copy (validation rules,
  flow errors, federation failures, rate limits) is `KIT_MESSAGES`; each app's `useAuthT` answers its page copy plus
  those. The Studio sets the origin in `lib/auth-server.ts` (imported by `main.tsx`), the consumer app in its root from
  `NL_AUTH_ORIGIN`. The Studio's screens are unchanged (253 tests pass); its settings list shows `phone_otp` sessions as
  "code to phone".
- **Legal links** (Terms, Privacy Policy) open the verbatim design 09/10 pages in a new tab (`target="_blank"
  rel="noopener"`, "(opens in a new tab)" for screen readers), in the terms checkbox and the legal line.
- **Hand-off:** `appAuthorizationUrl(continueTo) ?? /bff/login?next=` — sign-in back to `next` (or home), a new account
  to `/location`. A signed-in visitor opening `/sign-in` is sent to `next`.
- **Not done / not verified:** no real SMS, passkey or Google/Apple round trip was exercised in a browser (unit tests
  mock the auth API; server tests use the recording SMS sender, WireMock providers); step-up for payments on
  single-factor consumer sessions is S-51's; the security addendum is in docs/security/s-20-auth-review.md.

## 2026-09-30 — S-49 Shop landing, department/category pages

- **Public browse endpoints** (catalogue, open under `/api/v1/public/**`, guests allowed):
  `GET /api/v1/public/shop?market=&lang=` (landing) and `GET /api/v1/public/shop/departments/{slug}?market=&lang=`
  (404 for an unknown slug, a group or a banned leaf). The pages are server-rendered and identical for everyone, so the
  market and the language are **query parameters** (`lang` wins over Accept-Language), not the session;
  `Cache-Control: public, max-age=60`. The read models (`catalogue.application.ShopViews`) are purpose-built for these
  pages and serialized as they are (as the studio dashboard does). Bad market (blank / > 60 characters) → 422
  "Choose a city." (our copy).
- **What is shown:** offers with `vetting = approved` and `status = live`, of merchants that are `active` sellers
  (`seller` | `both`) whose `city` is the market, in `shop.*` categories other than the banned leaves
  (`northline.catalogue.banned-categories`). Merchants are read through the new **`merchants.api.ShopDirectory`**
  (`shopsIn(market)`, `shops(ids)`); catalogue never joins the merchants schema (S-37 rule). One product card per
  catalogue product: its cheapest offer in the market, with the number of sellers ("from $6.50", "+ 1 more shop").
  Popular = 30-day sales of all its offers, then newest. Images: the offer's main image when it is **approved** (S-123
  rule via `MediaRepository.approved`), served from `/api/v1/public/catalogue/media/{id}`; otherwise the design's
  halftone placeholder.
- **Market = city.** `region.zones` has no rows and addresses aren't geocoded to zones yet, so a market is one of
  `northline.orders.delivery.markets` (Calgary, Edmonton, Airdrie — design 06's live markets) matched against
  `merchants.merchants.city` ignoring case. Another city answers `served: false` with empty lists and the page says
  "Northline Shop doesn't deliver to {city} yet." with **Change location**. The consumer app renders the market in
  the URL (default Calgary) and follows the visitor's location after hydration (CONSUMER_WEB_PLAN.md § Market).
- **Pooled runs** (design 06 "Tonight's pooled run leaves 6:00 pm · order by 5:19"): new **`orders.api.DeliveryRuns`**,
  implemented by the orders module from `northline.orders.delivery` (application.yml): every market gets an evening
  run 6–9 pm (shops pack by 5:45, $2.99) and a morning run 8–11 am (pack by 7:30, $1.99) each day, plus the direct
  courier (45 min, $9.99) — the design's three windows and prices. A run is an `orders.delivery_windows` row created
  the first time someone asks (today and the next two days; `insert … on conflict do nothing`, in its own
  transaction so read-only callers can ask); windows without a market (the V103 dev seed's R-611/R-612) count as every
  market's. **Cut-off:** `cutoff_at` stays what the Studio shows sellers (pack by); customers must order
  `order-lead` (25 min) before it — "order by 5:20 pm" for a 5:45 pack-by (the design's 5:19 is a mock-up value).
  "N neighbours in" = distinct customers with a non-cancelled order on the run. Plus prices don't exist yet (no
  membership), so everyone pays the standard fee.
- **On the run:** a shop / product is on the first run it can make — it has an in-stock offer (a variant in stock
  when it has variants) whose fulfilment includes `pooled` (or is empty), and its handling time allows it: same day →
  the next run, next day → a run from tomorrow, two days → from the day after. Shop tags: "Order by {time}" for the
  next run, "Tomorrow" / a weekday for a later one, "Not on a run" otherwise (ours; the design has no such shop).
- **Departments are the taxonomy's leaves** (`/shop/bakery`), as the design's tiles are (Groceries, Butcher, Bakery,
  …). The design's sub-aisle chips ("All · Bread · Pastry · Cakes · Gluten-free") have no data behind them — the
  taxonomy stops at leaves — so the chips are the **other departments of the same group** that have shops in the
  market, the current one selected (`aria-current`), each a link. "Sorted by popular ▾" shows without the ▾: there is
  no other order yet.
- **Copy the design doesn't give:** run words for other days ("Today's / Tomorrow's / Tuesday's pooled run leaves …",
  "All shops on tomorrow's run", "On today's run"), the empty and not-served states, plural forms, "from $6.50",
  "+ N more shops", page descriptions — en + fr-CA (glossary: tournée groupée, Maître / Fiable / Inscrit, Sur la
  tournée de ce soir, Populaire dans …). The landing's "3× points" shop tag and the product page's "points" need
  merchant rewards (`trust.merchant_rewards`), which nothing fills: not shown. Distances ("0.8 km") need merchant
  locations, which don't exist: not shown.
- **Links:** department tiles and landing shop cards → `/shop/<department>`; department-page shop cards → search
  (`/search?scope=shop&q=<shop>`, the design's `go.search`); product cards → `/products/<id>` (S-50). An explicit
  `?market=` is kept on links between Shop pages.
- **French category names:** `db/seed/categories.json` is English only and `seedCategories` rewrites `name_i18n` on
  every run, so the translations live in the new `catalogue.category_labels` (V111) and the browse queries prefer
  them. Only the shop taxonomy is translated (services and food belong to S-53 / S-57).
- **UI kit:** `ProductTile`, `ShopTile` (department and landing looks), `DepartmentTile`, `TileGrid` (+ stories) in
  `@northline/ui`; shops without a logo get one of six token swatches picked from their id (no hex). `messagesFor()`
  gives a feature's catalogue outside React (route `head()`). The consumer test harness now uses the app's
  `RouteError` / `NotFound` as the router's defaults.
- **Schema (V111, consumer range):** `orders.delivery_windows.market`, `.slot` + unique `(market, starts_at)` where a
  market is set; `orders.run_label_seq` (R-700…); `ix_orders_window`; partial indexes `ix_offers_live` /
  `ix_offers_live_product`; `catalogue.category_labels` with the French shop taxonomy. **Dev seed V113**
  (`db/seed-dev/V113__consumer_shop.sql`, local only): design 06's Calgary shops and products (Country sourdough with
  Whole / Sliced, free-run eggs from two shops). V111–V113 sort after the dev seed's V100–V110, as S-62's V110 does.
- **Tests:** `PublicShopApiTest` (market filter: drafts, pending, hidden, paused merchants, providers, other cities
  and banned categories left out; cheapest offer + seller count; popularity order; handling time / stock / pickup-only
  vs the run; households on the run; French names by `lang` and by Accept-Language; unserved city; 404s; 422 message),
  in its own test market (application-test.yml lists `Shopville` & co.). vitest: `features/shop/shop.test.tsx` (design
  copy en + fr-CA, market follows the location and stays on links, explicit market kept, not-served and empty
  states, skeleton, error + Retry, department page).
- **Not done:** SEO structured data, canonical/hreflang and the sitemap (S-63); department pages for other markets'
  dedicated URLs (`/shop/bakery?market=Edmonton` is the URL); a shop's own page (sellers have no public storefront
  route — S-54 builds providers'); sorting other than popular; the Storybook a11y run of the new stories (no Chromium
  in the sandbox — `pnpm test-storybook` in CI).

## 2026-09-30 — S-50 Product detail with offers, variants, stock and delivery cut-off

Built on S-49 (branch `web/s-50-product-detail` from `web/s-49-shop-landing`).

- **Endpoint:** `GET /api/v1/public/shop/products/{productId}?market=&lang=` (public, server-rendered page, cached 60 s).
  404 when the id isn't a shop product, is in a banned category, or **no shop anywhere** has an approved, live offer
  for it — an unvetted catalogue record never becomes public. A product sold only in other markets answers 200 with
  no offers, and the page says "No shop in {city} sells this right now." with Back to Shop.
- **Several sellers per catalogue product** (Amazon-ASIN model, DECISIONS "Catalogue"): every active seller of the
  market with an approved, live offer on the record. Order ("best first"): in stock, then the earliest run it can
  make, then price, then tier (Master, Trusted, Registered). The first is shown in the design's layout; the others
  are listed under **"Also sold by"** (ours — design 06 shows one shop) with tier, run and price, and **Choose** opens
  the same page with `?offer=<id>`. "Also from {shop}" = up to 3 of that shop's other live products, most popular
  first (the design's "Also from Glenmore").
- **Price and stock:** an offer with variants shows the cheapest variant in stock (a variant's own price); its stock is
  the variants' total. Variants are the design's "Options" chips (one value per variant, as the Studio editor stores
  them); out-of-stock options are disabled. "Only N left" (rosehip tag) at or under the shop's low-stock mark or ≤ 3;
  "Out of stock" disables Add. The quantity stepper stops at the stock. Compare-at price shows as "Was $…".
- **Delivery cut-off, server-side in America/Edmonton:** each offer carries the next two pooled runs it can make
  (same `DeliveryRuns` and handling-time rule as S-49), each with `orderBy` (customer cut-off) and `packBy` (the shop
  packs). The panel reads, e.g., "Order by 5:20 p.m. for tonight 6–9 p.m. pooled ($2.99), tomorrow 8–11 a.m., or direct
  courier in 45 min. Glenmore Bakery packs at 5:45 p.m.; the shop is paid only after you confirm delivery." — the
  design's sentence with the order-by time in front (the story asks for the cut-off). Pickup-only or out of stock have
  their own sentences. The tag reads "On tonight's / today's / tomorrow's / <weekday>'s run".
- **Rating:** `trust.api.RatingQuery` ("★ 4.8 (211 verified)"), hidden when a shop has no reviews. The design's
  "3× points this week" and "Baked today" tags have no data (no merchant rewards, no bake dates): not shown.
- **Add to cart** posts `POST /api/v1/cart/items {offerId, variantId?, qty}` (the S-51 contract) and goes to `/cart`,
  as the design's `addToCart` does; any failure shows "We couldn't add it to your cart. Try again." **Until S-51 is
  merged the endpoint doesn't exist**, so Add answers with that error.
- **Images:** the offer's own approved images (or the record's, for shared-image listings), main + up to 3
  thumbnails that switch the main photo; without images, the design's halftone placeholders.
- **Copy the design doesn't give** (en + fr-CA): the order-by sentence variants, "Also sold by", "Choose", "Only N
  left", "Was …", returns tags ("Returns within 14 days" / "Final sale"), empty states. Glossary: Niveau Maître,
  TPS, tournée groupée, livreur direct.
- **Schema:** none (S-49's indexes serve the product query).
- **Tests:** `ProductPageApiTest` (best-first order with pending / other-market / sold-out offers, cheapest variant in
  stock, variants and stock, "Also from", runs by handling time with order-by = pack-by − 25 min and the configured
  Edmonton times, French title and department, market without sellers, 404 for drafts / banned / unknown);
  vitest `features/product/product.test.tsx` (design copy, cut-off sentence, option + quantity to the cart request,
  stock limit, other sellers + Choose, out of stock, cart failure, French, empty state, skeleton).
- **Not done:** Product JSON-LD and canonical URLs (S-63); per-variant images (the editor has none).

## 2026-09-30 — AI provider and data residency (user decision)

- **In-product AI uses OpenRouter**, behind an `LlmClient` port on the Spring AI stack, following billionairedev24/samop-inv-ship-26 (S-129–S-133). MCP (S-127) uses springdoc's OpenAPI-to-MCP tools on Spring AI's MCP server.
- **OpenRouter as a US processor is accepted by the product owner (2026-09-30).** The reason: Northline's data at rest stays in Canada (Postgres, object storage, search, backups in Canadian regions), and only per-request prompts go to OpenRouter.
- **Conditions that still apply to every AI feature:**
  - Send the model the minimum the feature needs, redacted on the port. Never SINs, card or bank numbers, or another merchant's data.
  - Prefer models and providers that don't retain or train on prompts, using OpenRouter's data-policy and provider-routing settings.
  - Disclose the processor in the Privacy Policy and in the PIPEDA / Law 25 assessment (SEC stories), together with the other processors.
- **Pending:** an OpenRouter API key per environment. Until it exists, `northline.ai.provider=fake` locally, and AI features answer `503 ai_unavailable` in the cloud.

## 2026-09-30 — S-46 Home: search-first hero and Services/Shop/Food entry points

Built on S-45's shell (docs/CONSUMER_WEB_PLAN.md). No migration.

- **Server-rendered layout, numbers in the browser.** The SSR HTML (the same for everyone) carries the hero, the tile
  names and links, the four promises and the section titles; the counts, greeting, trusted providers and "Your week"
  load after hydration, once the location (S-45 pill) and session are known. Until the place is known the heading reads
  "What do you need today?" / « De quoi avez-vous besoin aujourd'hui ? » (ours) instead of guessing a city; counts show
  skeletons.
- **Search** submits to `/search?q=` (S-48 renders results). No typeahead on the hero yet: the design's suggestions
  come from the search API (E-6), which S-48 wires to the same input.
- **New public read `GET /api/v1/public/home?city=`** in a new module **`discovery`** (the consumer site's
  cross-module landing reads; it only uses other modules' `api` packages): `providers` (active provider + both),
  `shops` (seller + both), `kitchensOpen`, `categories` (active businesses per approved category id), `cuisines` (open
  kitchens per cuisine code, plus `meal_kits` = open kitchens offering meal kits), `trusted` (three providers: best
  average rating, then most reviews, then higher tier, then name). Unknown cities answer zeros (the page then says
  "Northline isn't in {city} yet." and links to the Location screen); blank city → 422 "Choose a city.", > 60
  characters → "At most 60 characters.". `Cache-Control: public, max-age=60` (the same for everyone in a city).
- **Shared-contract additions (additive):** `merchants.api.PublicDirectory` (active businesses with their published
  storefront slug and brand colour, approved categories, public profile cuisines/dietary and kitchen address — never the
  legal name, contacts or documents); `food.api.KitchenAvailability` (open now, next opening, pause, fulfilment, prep).
- **"Open" for a kitchen** (`food.domain.KitchenCalendar`): inside today's opening range (in the time zone of the kitchen's market —
  new `region.api.Markets`, read from the existing `SEARCH_MARKETS` / `SEARCH_DEFAULT_MARKET`, no new list; a
  province that isn't served uses the default market's zone; a holiday entry replaces the weekday), not paused, **auto-pause enforced at read time** — the Studio's "Auto-pause if late orders ≥ N"
  (stored but not enforced since the kitchen workstream) now closes the kitchen to customers while N or more accepted
  orders are past their ready-by time, and reopens it as soon as the kitchen catches up (no write, no event) — and a
  live menu with at least one approved, published dish. Ranges end exclusive. "Opens …" looks a week ahead.
- **Tiles** are the design's lists (9 departments, 8 cuisines, 9 service categories) mapped to category ids of
  `db/seed/categories.json` / onboarding's cuisine codes (`features/home/catalog.ts`): Pharmacy = "Pharmacy (OTC)",
  Gifts = "Gifts & crafts", Home cleaning = "House cleaning", Tutor = K–12 + post-secondary (summed), Meal kits = the
  `meal_kits` fulfilment. The design's hard-coded "All 62 categories" reads "All categories" (the taxonomy has more,
  and the number would drift). Counts use ICU plurals ("1 shop"). Tile names are the design's en/fr pairs; category
  names from the database (English only today) are marked `lang="en"`.
- **Trusted near you:** providers come to the customer, so "near" = the same city and no distance is shown (the design's
  "1.2 km" has no source); the meta line is the first approved category (the design's "from $79" needs pricing reads
  that don't exist). Providers without a published page aren't links. No reviews → "New".
- **Your week** = contract `GET /api/v1/me/upcoming` for S-58 (CONSUMER_WEB_PLAN.md § Your week); until it exists the
  signed-in section says "Nothing booked or on its way this week." with "Book a service", guests get "Sign in to see
  your orders, bookings and quotes here." (ours). The points line uses the account summary's `points`; the design's
  "Glenmore Bakery is funding 3× this week" has no source and isn't shown.
- **Greeting** by the hour on the visitor's own clock, computed after mount (no time zone in code): morning 5–12, afternoon 12–17, evening otherwise ("Good
  evening, Amara · {area}"; guests "Good evening · {city}"; French « Bonsoir Amara · … »).
- **Not done:** hero typeahead (S-48), prices on trusted providers, distances.

## 2026-09-30 — S-124 Makefiles for every developer and operator workflow

- **Root `Makefile` + one include per area** (`make/server.mk`, `web.mk`, `db.mk`, `kafka.mk`, `search.mk`, `docs.mk`,
  `deploy.mk`, `infra.mk`), self-documenting: `target: ## text` and `##@ Section` lines are what `make help` prints
  (plus `##> VAR  text` for the common variables), so a target without a description is deliberately hidden
  (internal helpers such as `server-clean`). Layout, target names, `SERVICES`, `up`/`run`/`down`/`status`/`logs`/
  `restart` and the help format follow the user's other repository (samop), as asked. Guide: `docs/LOCAL_DEVELOPMENT.md`
  (same role as samop's); `docs/runbooks/local.md` keeps the configuration detail and now shows the make target next to
  each plain command.
- **`make up` = stand-ins + database + apps in the background.** Compose profiles come from `PROFILES` (comma list) or,
  when empty, `COMPOSE_PROFILES` in `.env` (`db`); `docker compose up -d --wait` (Compose ≥ 2.20, already the runbook's
  minimum), then `db-migrate` (with the dev personas) and `db-seed` (`SKIP_DB=1` skips), then `scripts/stack.sh up
  $(SERVICES)`. `make run` (alias `make dev`) is the foreground variant with merged, prefixed logs; Ctrl-C stops only what
  that run started. So `make setup up run` (the acceptance criterion) starts Postgres, migrates, seeds, starts the api and
  the Studio, and follows their logs. `make down` stops every app the runner started and every stand-in (data kept,
  `VOLUMES=1` deletes it); `make down SERVICES=…` stops only those apps.
- **App runner `scripts/stack.sh`** (samop's pattern): each app in its own session/process group (setsid, or Perl's
  `POSIX::setsid` on macOS where util-linux is missing), pid + log in `.run/` (git-ignored), stopped by group id only;
  refuses a port someone else holds and names the holder; restarts an app whose command changed; waits for
  `/actuator/health` (Java, up to 6 min for a cold first start) or the dev server's `/`. It compiles the selected server
  projects **once before** starting several `bootRun`s, because parallel Gradle builds compiling the same classes race.
- **Default `SERVICES="api studio"` with dev auth:** the fastest path needs only Postgres. The web apps choose dev auth
  (`NL_DEV_USER` = Ravi Sandhu / Amara Osei) automatically unless their BFF is being started or already runs;
  `DEV_AUTH=1|0` forces it. Real sign-in is `make up SERVICES="auth api bff studio"`.
- **Portable make:** GNU make 3.81 (macOS) — no `.ONESHELL`, `.SHELLFLAGS`, `::=`/`!=`, `undefine`, `$(file)`; bash 3.2 in
  recipes and in `stack.sh` (no `mapfile`, no associative arrays); no GNU-only `sed -i`/`find -printf`/`readlink -f`.
  Checked statically (grep) and run with GNU make 4.3; make 3.81 itself could not be downloaded here (the GNU mirrors are
  blocked by the sandbox proxy) and nothing ran on macOS. `infra/terraform/scripts/validate.sh` itself uses `mapfile` and
  `find -printf` (S-2): `make tf-validate` on macOS needs Homebrew bash + findutils, which `make doctor` and the guide say.
- **Toolchain check** `make/toolchain.sh` (POSIX sh): JDK 25, Node 22+, pnpm are required (setup fails without them);
  Docker/Compose ≥ 2.20, psql, helm ≥ 3.14, kubeconform, terraform ≥ 1.9, tflint, kubectl, kind and bash ≥ 4 only warn,
  each with what it is needed for. The Makefile finds a JDK 25 even when `JAVA_HOME` points at another version.
- **Gradle** always runs with `--max-workers=2` and CI's `ci/gradle/maven-mirror.init.gradle.kts` (a no-op without
  `MAVEN_MIRROR_URL`), so laptop and CI builds are the same command. `server-lint` = `spotlessCheck checkstyleMain
  checkstyleTest compileTestJava` (Error Prone/NullAway run in the compiler).
- **`web-format` formats only the web files changed against `BASE` (default `origin/main`)** plus untracked ones: Prettier
  was never applied to `web/` (139 Studio files differ), so a whole-tree format would bury real changes in every PR.
  `WEB_FORMAT_ALL=1` does the whole tree when someone decides to. `web-lint` = the hex-colour lint + typecheck (the root
  `pnpm lint` has no ESLint config and would fail). Web targets depend on `web/node_modules/.modules.yaml`, so they
  install only when the lockfile is newer.
- **`db-reset`** asks first (`YES=1` skips), refuses a non-local `PGHOST`, uses the compose `postgres` container when it
  runs, else psql as a superuser (creating `postgis`, `citext`, `pgcrypto`), then migrates and seeds.
- **Kafka/search targets wrap the existing provisioners** (`:worker:kafkaTopics`, `:worker:searchIndices`,
  `:worker:dlqReplay`); `search-reindex` calls S-71's `:worker:searchReindex`, which exists once S-71 is merged (before
  that Gradle says the task is unknown). `openapi` and `docs` are placeholders in this PR, filled by S-125 and S-126.
- **CI calls the targets** (still `workflow_dispatch` / web-or-api pipelines only): GitHub server (`make server-build
  TASKS=…`), web (`web-lint`, `web-test`, `web-build-studio`, `web-storybook-build`/`-test`, `e2e`), infra
  (`tf-validate`, `tf-lint`), deploy (`images-java JIB_TASK=…`, `helm-validate`), gitops (`argocd-validate`), event-schemas
  (`server-events`); GitLab likewise, installing `make` in images that lack it (Temurin, Playwright via apt; Alpine
  helm/terraform/tflint via apk). Kept as they were: the web image job (docker/build-push-action with the GHA cache, a
  buildx matrix on GitLab) and the promotion jobs (PR/MR creation is CI-specific).
- **Verified here:** `make env`, `make up PROFILES=db` (an isolated compose project: stand-ins healthy, 182 categories
  seeded, api + Studio up, `/api/v1/me/businesses` through the Studio's proxy as Ravi), `make status`, `make logs`,
  `make down SERVICES=…`, `make run SERVICES=api` stopped by a signal (app stopped, pid files removed), a failed start
  reported with the log's last lines, `make help`, and `-n` dry runs of the Gradle/compose/image targets.
- **Not done:** `OBS=1` (samop's observability flag) waits for S-111/S-112, which bring the telemetry stack; the pipelines
  were not run (manual only, no credits); nothing was run on macOS.

## 2026-09-30 — S-51 Cart and checkout (server-side cart, step-up, tax, manual-capture payments, order.placed)

- **Cart (orders, `/api/v1/cart`, open to guests):** `GET`, `POST /items` `{offerId, variantId?, qty}`,
  `PATCH /items/{id}` `{qty}`, `DELETE /items/{id}`. A signed-in person's cart is keyed by their user id. A guest's
  cart is keyed by the SHA-256 of the consumer-bff's `X-Northline-Guest` (the raw id is never stored). It is never
  keyed by an identity, and it expires 30 days after its last change. On the first signed-in call that still carries
  the guest id, the guest's lines are merged into the person's cart (quantities add, capped at 99) and the guest cart
  is deleted. Every read checks each line against the catalogue (new `orders.api.SellableOffers`, implemented by
  catalogue: only approved and live offers, variants and stock) and against the shops (`ShopDirectory`). A hidden
  listing, a paused shop or sold-out stock therefore shows up at once as "This item isn't available any more." /
  "This item is sold out." / "Only N left.". Lines are grouped by shop in the order the shops were first added (the
  design's multi-shop cart). `orders.carts` (V009) is kept. The lines move from the `lines` jsonb to
  `orders.cart_items`.
- **Checkout needs a person** (`/api/v1/me/…`):
  - `GET /checkout?market=` returns the saved addresses, the delivery options and `stepUp`.
  - `POST /checkout/quote` returns totals with GST/HST.
  - `POST /checkouts` (Idempotency-Key, X-Step-Up) takes the stock and opens the PaymentIntents.
  - `POST /checkouts/{id}/place` (Idempotency-Key) checks the authorizations, holds escrow and creates the order.
  - A guest who presses Pay is sent to `/sign-in?next=/cart`, and the cart follows them through the merge.
- **Step-up rule (decided here):**
  - A sign-in with a second factor (`acr=mfa`) pays directly.
  - A phone-code sign-in (single factor) must send `X-Step-Up`: a fresh proof (≤ 5 minutes, single use) from
    northline-auth's step-up with the account's passkey or authenticator (`identity.api.SecondFactors`: `mfa_primary`
    is `passkey` | `totp`).
  - An account with neither gets 403 `second_factor_required` ("Add a passkey to pay: payments sit behind a second
    factor."). It then enrols a passkey at checkout. The new `POST /auth/step-up/enrol/passkey/options` +
    `/enrol/passkey` are allowed only within 15 minutes of sign-in, and they issue the proof along with the new
    passkey.
  - The step-up endpoints now accept any signed-in session, not just MFA ones, because that is what they are for.
  - Browsing and the cart stay single-factor.
- **Delivery options:**
  - Pooled runs come from `DeliveryRuns` (S-49): the next two the whole cart can make. The slowest handling time
    decides, and a line that can't go pooled rules pooled runs out.
  - The direct courier is offered when everything can go the same day.
  - The address's city must be a served market, and every shop must be in it ("{shop} doesn't deliver to {city}.").
  - Addresses are `identity.addresses`, read and written through the new `identity.api.DeliveryAddresses`. A new one
    is saved when the checkout starts.
- **Tax:** `TaxCalculations.calculate` (S-21) runs once per order line (the shop sells) and once for the delivery fee
  (Northline sells). The province and postal code come from the delivery address. The quote shows GST/HST per rate
  as the design's "GST (5%)".
- **Payments (S-11 escrow model, unchanged):**
  - One manual-capture PaymentIntent per order line (`order_line`) and one for the delivery fee (`order_delivery`,
    merchant `PaymentAuthorizations.PLATFORM = "northline"`). They share `transfer_group=order:<id>`.
  - The consumer app mounts the Stripe Payment Element for the first PaymentIntent and confirms the others with the
    same PaymentMethod. It does this only when the api says `provider: stripe` (a secret key is set:
    `payments.api.PaymentSettings`, publishable key `STRIPE_PUBLISHABLE_KEY`, an existing variable). Otherwise it
    shows the local fake: a simulated card form ("Test payments · nothing is charged") whose intents are
    already `requires_capture`.
  - `place` checks each PaymentIntent (`PaymentAuthorizations.authorized`: `requires_capture` for at least the amount
    plus tax), then `EscrowLifecycle.hold` for each line. Card data never reaches Northline.
- **Stock:** `POST /checkouts` takes stock with conditional decrements (`stock >= qty`) in one transaction. If any line
  can't be taken, nothing is taken: 409 "Something in your cart just sold out…". The checkout holds stock and
  PaymentIntents for 30 minutes. `CheckoutJobs` (every minute, not under `test`) abandons expired checkouts, gives the
  stock back and cancels the PaymentIntents. A new checkout by the same person abandons their previous open one.
- **Idempotency:** both POSTs require `Idempotency-Key` (422 "Idempotency-Key header is required." when it is
  missing). A replay returns the stored answer with `Idempotent-Replayed: true`. A different body with the same key
  gets 409 `idempotency_key_reused`. The store is `payments.api.IdempotentRequests`
  (the S-11 request guard, now shared; `PaymentsIdempotency` delegates to it). The key also goes to Stripe
  (`nl1:…` keys, folded with the client key).
- **`order.placed`:** placing publishes `orders.api.OrderPlaced` **once per shop** with that shop's lines
  (`@Externalized` to `orders.order`; schema `events/orders.order_placed.v1.schema.json`, checked by S-34). It carries
  ids and amounts only. The customer id is in the internal event, like other orders events, and is never in the
  partner payload. The S-33 webhooks consumer now subscribes to `orders.order` (`deploy/kafka/topics.yaml`) and maps
  it to public `order.placed` (`docs/spec/webhooks/order.placed.v1.schema.json`), so `order.placed` leaves
  `NOT_YET_PUBLISHED`. S-38's `SalesListener` counts sales from it at once.
- **Order rows:** `orders.orders` gets `checkout_id` (unique) and `delivery_kind`, and one `order_lines` row per line
  (state `pending`). The reference comes from `orders.order_ref_seq` (NL-50000…, clear of the seed's NL-481xx).
  `orders.checkouts` holds the snapshot (ids, amounts, PaymentIntent ids; no personal data).
- **Not done / gaps:**
  - The delivery-fee PaymentIntent is authorized but **nothing captures it yet**. Capture happens on delivery, and
    the delivery flow (courier, "delivered") is a later story. Until then it lapses after 7 days like any
    uncaptured authorization.
  - The "points" line and "Plus" prices are not shown (no loyalty ledger writer, no membership).
  - Substitution preference is stored on the checkout, but nothing uses it yet.
  - No receipt email is claimed on screen.
  - Apple Pay / Google Pay are not enabled (cards only, stripe.md § 11).
- **Never exercised against real Stripe (only the local fake gateway and stripe-mock-shaped unit tests):**
  - the Payment Element mount;
  - `confirmPayment` / `confirmCardPayment` of several PaymentIntents with one PaymentMethod, including
    `setup_future_usage` on the first;
  - 3-D Secure on the second and later PaymentIntents;
  - Stripe Tax calculations for the delivery fee under the platform;
  - canceling PaymentIntents when a checkout expires.
- **Schema (V112, consumer range):** `orders.carts` timestamps + unique keys, `orders.cart_items`, `orders.checkouts`,
  `orders.orders.checkout_id` / `delivery_kind`, `orders.order_ref_seq`; the `ref_type` checks of
  `payments.payment_intents` and `payments.tax_calculations` widened to allow `order_delivery` (the delivery fee's
  PaymentIntent and tax calculation, referenced by the order id).
- **Tests:**
  - `CartCheckoutApiTest` covers:
    - the cart: guest keyed by header, validation messages, quantity changes, merge at sign-in, unavailable lines;
    - checkout: setup (runs, direct, step-up need), GST on items and delivery, address/choice validation, phone-code
      sign-in needs step-up;
    - idempotency: key required, replay, conflicting body;
    - placing: escrow held per line, `order.placed` per shop, cart emptied; an unauthorized payment doesn't place;
      an abandoned checkout returns stock and can't be placed; empty cart;
    - a stock race: two checkouts for the last unit, exactly one wins.
  - `StepUpApiTest` (auth, 3 new): a phone-code session steps up with TOTP, and passkey enrolment issues the proof
    and needs a recent sign-in.
  - `WebhookPayloadsTest.orderPlaced_theShopsLinesWithoutTheCustomer`.
  - vitest `features/cart/cart.test.tsx`: design copy, guest banner and sign-in, multi-shop groups, quantity and
    remove, delivery windows, tax lines, step-up dialog, fake card, errors, French.

## 2026-09-30 — S-52 Order confirmed and tracking (design 06 confirmed)

- **Endpoints (orders, `/api/v1/me/orders`, single-factor sessions allowed):**
  - `GET /{orderId}` returns the order (ref, state, totals), its delivery (the pooled run's label, window and
    households, or the direct courier's estimated time), one entry per shop (name, items, packed) and the timeline.
    It is `Cache-Control: no-store`. Anyone other than the order's customer gets 404, not 403, so order ids can't be
    probed.
  - `GET /{orderId}/events` is `text/event-stream`: an `order` event with the same JSON at once and again on every
    change. It sends a keep-alive comment every 25 s and ends after 30 minutes (the browser's EventSource
    reconnects). The ownership check runs before the stream opens.
- **The timeline follows the order's state**, since there are no courier or fulfilment events yet:
  - Paid → Shops packing (`placed` / `accepted` / `packing`) → Courier picks up (`ready`) → Delivered (`picked_up`
    is current, `delivered` / `confirmed` is done).
  - A cancelled or refunded order shows only Paid plus the state sentence.
  - "N of M packed" counts the shops whose lines have all left `pending`, which is the Studio's "Mark packed"
    (`POST /api/v1/merchants/{m}/orders/{o}/pack`).
  - The design's copy ("Shops packing · 1 of 3 packed", "Courier picks up · scan at each shop", "Delivered · photo
    proof · you confirm, shops paid") is used as it stands, even though courier scans and photo proof don't exist
    yet. That is the flow the design describes, and the steps advance when the order's state does.
- **Live updates:** `OrderTrackingEvents` turns every event that changes what the customer sees (`OrderPlaced`,
  `OrderPacked`, kitchen accepted/ready, food handed off) into a "changed" on the new `TrackingBus`.
  - Under `local` / `test` the bus is in memory.
  - Everywhere else it is Redis pub/sub (channel `nl:order:<id>`, message = the order id, nothing stored; CLAUDE.md
    names Redis for order tracking). Every replica wakes its own open streams, and each stream re-reads the order,
    so a message carries no data. No new configuration is needed (the existing `REDIS_*` variables).
  - The page also refetches every 30 s, in case a stream is dropped by a proxy.
- **Screen (`/orders/$orderId`):** checkout lands here after placing.
  - The title comes from the delivery: "Order placed. Arriving tonight 6–9 pm." / "… by about 7:10 pm". It then
    becomes "On the way…" and "Delivered.".
  - The subtitle is "{ref} · {total} · N shops packing now…".
  - The run card ("Pooled run R-701 · leaves 6:00 pm", households) and View orders / Back to home.
  - Signed out: "Sign in to see your order." with Sign in (next = this page). An unknown order: "We couldn't find
    this order.". en + fr-CA.
- **Not done / gaps:**
  - The design's map is a placeholder panel (no courier positions exist).
  - "Receipt sent to …" and points earned are not shown (no receipt email, no loyalty ledger).
  - "View orders" links to `/account/orders` (S-58).
  - SSE through the consumer-bff (Spring Cloud Gateway MVC relay) and the TanStack Start server hasn't been run end
    to end here. The api's stream is tested with MockMvc. If a proxy buffers it, the 30-second refetch still keeps
    the page current.
- **Schema:** none (reads `orders.orders` / `order_lines` and `orders.delivery_windows` through `DeliveryRuns`).
- **Tests:**
  - `OrderTrackingApiTest` (own market "Trackville"):
    - the view and its timeline;
    - only the customer sees it (401 / 404 for others, stream included);
    - the timeline follows the state through delivered;
    - the stream sends the order at once and again when a shop packs.
  - vitest `features/orders/orders.test.tsx`: design copy for pooled and direct, packed count and the run, live update
    from the stream, sign-in prompt, not found, skeleton, error + Retry, French.

## 2026-09-30 — Region-neutral by design (user direction)

- Northline **starts** in Alberta (Calgary first) but is built for every province.
- Code must not hardcode a province, city or time zone. That covers messages, defaults, holiday calendars, time zones, service zones and legal copy. All of it comes from the region configuration: the provinces (time zones, statutory holidays, tax, privacy law, registries, launch status) and the markets (city, province, time zone, zones, live flag).
- A message that names a place takes it as a parameter ({province}, {city}), in English and French.
- Province-specific integrations, such as the Alberta corporate registry or the City of Calgary licences, stay as adapters. They are selected by the business's province and city, never by default.
- S-134 moves the existing literals into that configuration and adds a lint rule. Until it lands, new code must not add region literals.

## 2026-09-30 — S-44 Search API: query, filters, geo sort, trust/distance boosts, completion suggester

- **Module `search` (api), hexagonal like the others:** `domain` (`SearchQuery`, `SuggestQuery` with every rule checked at once, `Coordinates`, `Highlight`, codes `SearchKind`/`SearchSort`/`TrustTier`), `application` (use cases `SearchListings`, `SuggestListings`; ports `SearchIndex`, `SearchCache`, `SearchRateLimit`; `SearchService` with the hot-query cache), `integration` (`ElasticsearchSearchIndex`, `LocalSearchIndex`, Redis/memory caches, `SearchConfig`), `web` (`SearchController`, `SearchParams`, DTOs, MapStruct `SearchWebMapper`). It reads the read model through the shared `ListingDocument` contract and never writes to Elasticsearch (ARCHITECTURE). No SQL at all (S-37's rule holds trivially).
- **Provider port as elsewhere:** `SEARCH_PROVIDER=elasticsearch` (default) | `local` = no index, empty results, the `local`/`test` profiles' default so the api still starts without Elasticsearch; `local` is refused under staging/prod at start-up. `SearchApiTest` switches to Elasticsearch in its own context.
- **Query DSL as JSON** (Jackson `ObjectNode`, sent with `withJson`), read back through the typed client: easier to read next to `deploy/search/listings.json` than the builder API. **Match:** `best_fields` on name ×4, merchant and category names ×2, keywords ×1.5, description with every word required; plus `cross_fields` (words spread over name, merchant, categories, keywords; ×0.5) and a `bool_prefix` on `name.prefix` for words still being typed. **Relevance:** `function_score` = text × (tier 1.5 master / 1.2 trusted / 1.0 registered + log10(2 + rating) + up to 2 for nearness: Gauss on `location`, full within 1 km, half at 6 km, only for documents that have a location). Summed rather than multiplied so a good match farther away still ranks (multiplying by the decay zeroed everything beyond ~10 km). Ties: trust rank, then id.
- **Filters** map the design's chips (design 06: shop "On tonight's run", "Under $10", "Master sellers", "Halal"/"Gluten-free"; providers "Master tier", "Instant book", "Available today", "Under 3 km", "Under $80"; food "Open now", "Halal", "Vegan", "Nut-free"): `kind` (the web's `scope`), `category` (any level, on `categoryPath`), `minPrice`/`maxPrice`, `minRating`, `tier`, `instantBook`, `openNow`, `delivery=tonight`, `dietary`, `allergenFree`, `lat`/`lng` + `radiusKm`. Time-dependent ones are evaluated at query time against Edmonton time: `openNow` = the minute of the week inside `openHours`, no pause in force, not sold out today; `delivery=tonight` = pooled, before `deliveryCutoffMinute`, in stock. Market, `vetting=approved`, `status=live`, `merchantStatus=active` are always filtered. "Available today" for services = `openNow` for now (next free slot needs the booking calendar). "Organic", "Family packs", "EV certified", "Free delivery (Plus)" have no data yet.
- **Location:** `lat`/`lng` (the consumer shell's names; both or neither); `market` = province/territory code (default `SEARCH_DEFAULT_MARKET`) — the location pill knows the province; no polygon lookup exists (`region.zones` is empty). `distanceKm` on every result that has a location (haversine, one decimal); `sort=distance` leaves out results without a location (their sort value would be infinite and can't round-trip in `search_after`).
- **Pages:** `search_after` on the sort values + `id` (no point-in-time: pages may shift when the index changes between them, acceptable for a marketplace list); the `next` token is `<sort>.<base64url JSON>` and is refused (422 on `after`) for another sort or when damaged. Facets (kinds, categories with the leaf name, merchants with names via `top_hits`, tiers, price buckets under_10/10_25/25_50/50_100/100_plus, dietary) on the first page only; they count the whole filtered result (not multi-select). `total` exact up to 10 000.
- **Suggestions** from the S-42 completion fields (`suggest` with contexts market + kind, `suggestCategory` with market), `skip_duplicates`, categories keep at most two places; **highlight** computed in the api (`Highlight`: case- and accent-insensitive, one char per char so offsets line up, only at a word start) as UTF-16 offsets. Types: `service` | `product` | `food` | `merchant` | `category`. Recent searches stay client-side; synonym rows ("fr → sourdough") aren't suggestions.
- **Hot-query cache 30 s** (backlog): Redis/Valkey `nl:search:<q|s>:<sha-256 of the canonical parameters>` outside `local`/`test` (memory there); best effort — a cache failure is logged and the index answers. `SEARCH_CACHE_TTL`.
- **Rate limit:** anonymous callers are welcome, so every client address gets `SEARCH_RATE_LIMIT` (120) requests a minute per api instance (the fixed-window `WebhookRateLimiter` already used for webhooks), then 429 `rate_limited` + `Retry-After: 60`. The address is the right-most public `X-Forwarded-For` hop when the peer is internal (ingress, BFFs, the consumer SSR server), else the peer — the api otherwise sees only the BFF's address and one limit would throttle every consumer. Per instance, not shared through Redis: good enough to stop scraping; a shared limit is a follow-up if needed.
- **Validation messages** are ours (search has no form in `validation-rules.md`), English like the other API messages until S-40: "Search for 100 characters or fewer.", "Choose a province: AB, BC, ON or QC.", "Choose service, product, food or merchant.", "Choose registered, trusted or master.", "Sort by relevance, distance, price_asc, price_desc or rating.", "Latitude must be between -90 and 90.", "Longitude must be between -180 and 180.", "Send both lat and lng, or neither.", "Choose a distance between 1 and 100 km.", "A distance filter needs your location (lat and lng).", "Sorting by distance needs your location (lat and lng).", "Prices can't be negative.", "The lowest price is above the highest.", "Choose a rating between 1 and 5.", "Ask for 1 to 50 results.", "Ask for 1 to 10 suggestions.", "This page link no longer works. Start the search again.", "Type at least one letter.", "Use delivery=tonight or leave it out.".
- **Contract** for the consumer web in `docs/CONSUMER_WEB_PLAN.md` § Contracts › Search (S-45's list of contracts; the search runbook links it), with the mapping of the web's `scope` and the design's chips to parameters, and the "exists" row in its public API table. OpenAPI: springdoc from `@Tag`/`@Operation`/`@Parameter` on the controller and `@ParameterObject SearchParams` (`/v3/api-docs`, tag *Search*).
- **Region-neutral** (DECISIONS "Region-neutral by design"): the markets search serves and each one's time zone are configuration, `SEARCH_MARKETS` (`CODE=Zone/Id,…`; default `AB=America/Edmonton,BC=America/Vancouver,ON=America/Toronto,QC=America/Toronto`), not code. "Now" for open-now, the same-day cut-off and "sold out today" is taken in the requested market's zone (the index keeps local times). A malformed code → 422 `format`; a well-formed one not configured → 422 `unsupported` ("Search isn't available in {market} yet."); `SEARCH_DEFAULT_MARKET` blank = requests must name a market, and it must be one of `SEARCH_MARKETS` (checked at start). S-134 moves both into the region configuration.
- **Variables (api):** `SEARCH_PROVIDER`, `SEARCH_MARKETS`, `SEARCH_DEFAULT_MARKET`, `SEARCH_CACHE_TTL`, `SEARCH_RATE_LIMIT` (all optional) — README, dev/staging/prod tables, `.env.example`.
- **Tests:** `SearchApiTest` (Elasticsearch 9.1 in Testcontainers with the deploy/search layout, documents built with the shared `ListingDocument`, a fixed clock — Wednesday 12:00 in Edmonton, anonymous requests): text ranked by tier and rating, market isolation; `lang` and `Accept-Language` pick the index, French synonyms and accents; the card fields; open now vs sold out vs paused, weekly hours; tonight's run, dietary, allergens, price, rating, tier, instant book, category at any level; distances, distance sort, radius; nearness and tier boosts; `search_after` pages cover everything once in order (unpriced last); facets on the first page only; suggestions with highlights (products, category, merchant, French, kind context); every validation message; the rate limit per address including `X-Forwarded-For` behind a proxy and a spoofed left entry; the 30 s cache; the OpenAPI description; p95 < 150 ms over 60 uncached queries. `SearchLocalProviderTest`: empty results and the rules with `local`, highlight folding.
- **Not done / never run for real:** nothing has run against Elastic Cloud; "Your recent" searches (client side); next free slot for services; image URLs (`imageKey` is opaque until a public media URL exists); multi-select facets; a shared (Redis) rate limit.

## 2026-09-30 — S-71 Full reindex job and backfill from Postgres

- **Blue/green by alias, fed from Postgres, caught up from Kafka** (`SearchReindex`, worker): note the end offsets of the `search-indexer` topics → create `listings_<lang>_v<schema>_<now>` beside the live indices (refresh off, no replica, `auto_expand_replicas` off while loading) → backfill every merchant through the live indexer's own `SearchProjection` → catch up the events published since the start, pass after pass until a pass applies nothing → restore the layout's refresh/replica settings, refresh, wait for yellow → **swap both aliases in one `_aliases` request** → catch up once more and re-read merchants changed since the start without an event (what the reconcile sweep wrote to the old index meanwhile) → delete the old indices (`--keep-old` keeps them). Readers never see a missing or partial index; the live indexer, the sweep and the API keep running.
- **Why the extra passes are safe:** every write goes through the per-merchant advisory lock and the Postgres-clock versions of S-43, so the catch-up and the live indexer can apply the same event in any order (an older snapshot is refused, a 409 is `stale`, not an error). Events are only a list of scopes to re-read; the catch-up reads the main topics (retries and DLQs repeat those records) with a group-less consumer that commits nothing.
- **One run at a time:** a Postgres *session* advisory lock (`search-reindex`) held on a dedicated connection for the whole run; a second run fails at once (exit 2). **Failure before the swap** deletes the new indices and leaves the aliases untouched (exit 1); after the swap the new indices are live and the indexer keeps them current.
- **Index names** bump a second when the timestamped name is taken. Old indices of earlier failed runs aren't touched by name guessing: only the indices the aliases pointed at are deleted.
- **Where it runs:** `SearchReindexCommand` (worker image, the worker's `DB_*`/`KAFKA_*`/`ES_*`), Gradle `:worker:searchReindex`, and a Helm one-off Job `northline-search-reindex-<runId>` rendered only while `searchReindex.runId` is set (a DNS label; the chart refuses others), `backoffLimit: 0`, 2 h deadline, Argo CD `Prune=false,Replace=false` so a later sync never kills a running rebuild. Not a deploy hook: a rebuild is an operator's decision (the search-indices Job only says `REINDEX REQUIRED`). `validate.sh` checks it renders only with a run id.
- **Library additions** (`ListingIndices`): `putSettings`, `refreshAndWait`, atomic `swap`, `delete` (listings indices only), `count`.
- **No migration.**
- **Tests:** `SearchReindexTest` on Kafka 4 + PostGIS + Elasticsearch 9 with the whole worker running: a listing indexed live, a ghost document Postgres doesn't have, a live listing whose event was lost; during the backfill a new listing is published (the live indexer writes the old index) — after the run the alias points at a new versioned index with the layout's `_meta`, the old index is gone, the ghost is gone, the lost and the late listings are there, searches answered the kept listing at every phase, and the live indexer keeps writing through the alias. A second concurrent run is refused; a failure after the backfill deletes the half-built indices and leaves the live alias as it was; `--keep-old` keeps the previous indices.
- **Not done / never run for real:** nothing has run against Elastic Cloud; no throttling of the backfill (Elasticsearch bulk per merchant; add one if a large catalogue loads a small cluster); a scheduled periodic rebuild (on demand only).

## 2026-09-30 — S-47 Location screen with Google Places autocomplete (Canada) and market/zone resolution

Built on S-45's shell and location pill (docs/CONSUMER_WEB_PLAN.md § Location).

- **Port `region.application.PlacesAutocomplete`** (autocomplete, place details, reverse geocoding), chosen by
  `northline.places.provider` (`PLACES_PROVIDER`): `local` (default; `FakePlaces`, fixture addresses read from
  `places-fixtures/addresses.json` — design 06's "1204 17 …" suggestions, one per dev-seed market and a few outside them;
  refused under staging/prod) or `google` (`GooglePlaces`: **Places API (New)** autocomplete + details and the **Geocoding API** for
  reverse, key `GOOGLE_MAPS_API_KEY`, required with `google`). The browser never sees the key: it calls
  `/api/v1/geo/*` through the consumer-bff. **Never run against Google** (no account) — WireMock tests only.
- **Google usage:** Canada only (`includedRegionCodes: ["ca"]`), address types only (`street_address`, `premise`,
  `subpremise`, `route`), 50 km location bias around the visitor when known, the browser's **session token** on every
  suggestion and on the details call (one billed session per search), nothing below 3 characters, a 250 ms debounce,
  3 s time-outs and no retries (a late suggestion is useless) → 503 `places_unavailable`. The Geocoding API takes the
  key only as a query parameter, so its errors are rethrown without the request (the URI would put the key in logs).
  Nothing Google returns is stored.
- **Abuse limit:** address lookups cost money, so each browsing session (the consumer-bff's `X-Northline-Guest`), else
  the caller's address, gets `PLACES_RATE_LIMIT` (60) lookups a minute per api instance → 429 `rate_limited`
  (the shared `WebhookRateLimiter`, in memory). Markets and resolve aren't limited (no Google call).
- **Region-neutral markets (DECISIONS "Region-neutral by design"; no city, province or zone in code).** Which
  provinces are served is **configuration we already have**: `SEARCH_MARKETS` / `SEARCH_DEFAULT_MARKET` (S-44), read
  through a new shared `region.api.Markets` (`region.config.MarketSettings`; the same parsing and start-up checks as
  search; S-46 uses it for kitchen hours). A province listed there is `live` whatever its row says; the others keep
  their row's stage (`off`, `waitlist`, `pilot`). City markets and their delivery zones are **region data**, not code
  or migrations: operations add them (SQL until the console's Regions screen; S-134 brings the region configuration).
- **Schema (V117, additive):** `region.regions` holds provinces **and** their city markets (`kind`, `parent_id`,
  `city`, `center geography(Point)`, `radius_km`, `sort`; unique province rows); a market covers addresses within
  `radius_km` of its centre and the nearest covering centre wins (a satellite town next to a large city). The migration
  only lists Canada's 13 provinces and territories (names en/fr, all `off`). `region.zones` got `sort`, a name CHECK and
  a GiST index. New `region.waitlist` (region, user id or a guest's own email, language; one entry per person and
  region).
- **Local dev seed `db/seed-dev/V119__dev_markets.sql`** (profile `local` only, like the personas): design 06's
  Location examples — markets live / pilot / waitlist with approximate neighbourhood zones priced as the design ("3
  pooled runs / day", $4.99, free with Plus, $35 minimum), one pilot and two waitlist provinces. `GeoApiTest` loads the
  same file.
- **`GET /markets`** answers `{items, fallback}`: provinces shown = served ones first (configuration order), then any
  other with a stage or a market; each with `taxBps` (sales tax on goods from `region.api.TaxRates`, shown as "Sales
  tax {rate}%") and its markets (with centre `lat`/`lng`). `fallback` = the first live market of the default market's
  province (else of any served province) — the pill's "nowhere known" place, replacing S-45's hardcoded list
  (`features/location/markets.ts` is deleted). With none configured the pill says "Set location".
- **Resolution** (`resolution: {market, zone, waitlist}`): the covering market (any stage); its zone when the market is
  live or pilot; the waitlist ("An address outside a live market joins the waitlist for its nearest one") = the
  covering market when it isn't live, else the province's nearest market, else the province (Toronto → Ontario). Pilot
  = invite only, so a pilot address also gets the waitlist (with the pilot wording) and can't be saved.
- **Endpoints** (`/api/v1/geo/**`, already public; guests allowed): `GET /markets` (cached 5 min), `GET /autocomplete`,
  `GET /places/{placeId}`, `GET /reverse` (S-45's missing endpoint), `GET /resolve`, `POST /waitlist` (201 joined, 200
  already listed; signed in → user id, guest → email required; a live market → 409 `region_live`; a served province can still be joined for an address outside its markets).
- **Pill contract (additive):** `/reverse` answers `{label, city, province?, market?, zone?}`; the pill treats a `market`
  that is null or not live/pilot as "outside every market" (the api's fallback market), and an unreachable `/reverse`
  the same (S-45's client-side nearest-market guess is gone with its hardcoded list), and remembers province,
  market and zone with the detected place. `SavedLocation` gained `street`, `unit`, `province`, `postalCode`,
  `marketId`, `zoneId`, `zone` (all optional; older saved values still parse). Label: "{neighbourhood}, {city}" = the
  provider's neighbourhood, else the zone, then the market's city; a saved address shows "{street}, {city}".
- **Screen details the design leaves open:** the province buttons come from `/markets`, in its order — "Live · {first two
  live markets}" reproduces the design's note. The design's hardcoded paragraph ("In Alberta: … are live; … pilot; …
  waitlist") is built from the chosen province's markets ("Live in {province}: {cities}. Pilot: … Waitlist: …"). Choosing a province only sets what's highlighted; the picked address's province
  wins. The address field is an ARIA combobox (↑/↓/Enter/Esc). The prototype's technical footer ("Google Places
  Autocomplete · restricted to CA · session token") isn't shown; "powered by Google" is (Google's terms). The parts
  row (Street, City, Province, Postal, Place ID) and the tags (Market · city · stage, Zone, pooled runs, tax) appear once
  an address is chosen; the tax tag is the province's total rate ("Sales tax 5%"; the design's per-tax names like "GST 5% + PST 7%"
  would need tax components per province, which `TaxRates` doesn't expose). "Save and continue" without a chosen address → "Choose your address from the list."
  (ours). `?next=` returns there after saving (checkout's "Change"). The address stays in this browser only
  (`localStorage`); account addresses are S-59's.
- **Messages** (server `region.domain.GeoMessages`, English; web en + fr-CA): "At most 200 characters.", "Start the
  address search again." (bad session token), "Choose an address in Canada.", "Choose where you'd like Northline.",
  and validation-rules.md's "Email is required." / "That doesn't look like an email address.".
- **Configuration:** `PLACES_PROVIDER`, `GOOGLE_MAPS_API_KEY` (secret `google-maps-api-key`: Terraform `secret_env` on
  AWS, Google Cloud and Azure; chart `secretNames`, `apps.api.secretEnv`, required in values-staging/prod with
  `PLACES_PROVIDER: google`), `PLACES_RATE_LIMIT`; required-env lists of staging/prod; runbooks README, local, dev,
  staging, prod, secrets, infrastructure; new runbook `docs/runbooks/google-maps.md`; `server/.env.example`.
- **Not done / never exercised:** no call to Google has ever been made (the adapter follows Google's documentation;
  field names of Places API (New) — `placePrediction.structuredFormat`, `addressComponents[].longText/shortText` — are
  unverified live); the zones are approximate boxes; no console screen for markets/zones/waitlist; waitlist emails
  aren't sent when a market opens (no job yet); the rate limit is per api instance.

## 2026-09-30 — S-57 Food: landing, restaurant menu, food checkout (delivery or pickup) and tracking

Stacked on S-46 (#66, kitchen availability, `PublicDirectory`), S-47 (#67, location, `region.api.Markets`) and S-51
(merged: step-up rule, `IdempotentRequests`, `order.placed`).

- **Food cart is separate from the shop cart** (the story asks to record it): one kitchen at a time, kept in the
  browser (`localStorage['nl.foodCart']`, `features/food/foodCart.ts`), priced again by the server at checkout. A food
  order goes to one kitchen by direct courier or pickup and must never join a pooled goods run, so it can't share S-51's
  multi-shop, window-based cart. Adding from another kitchen asks first ("Start a new order?").
- **Region-neutral:** a kitchen's hours, item windows, combo windows, "sold out today" and scheduled windows are read in
  the time zone of **its market** (`region.api.Markets.zone(province)`, i.e. `SEARCH_MARKETS`); the restaurant's tax
  estimate uses its province's rate (`TaxRates`, `taxBps` in the api); the pickup place of supply is the kitchen's
  province, else the default market's — no province, city or zone in code or messages.
- **Landing** `GET /api/v1/public/kitchens?city=&lat=&lng=` (public, 30 s cache): the city's active kitchens with open
  state (S-46's `KitchenAvailability`: hours, holidays, pause, **auto-pause**), next opening, fulfilment, prep, ETA
  (prep + ride: 3 min/km + 5, 10 when the distance is unknown; range +10), pickup ready time, distance (haversine from
  `merchants.locations`), `delivers` (courier and within the kitchen's radius, else the location's service radius, else
  8 km), delivery fee, price level ($ < $12 average dish, $$ < $25, $$$), rating. Open first, then nearest. Filters
  (open now, under 30 min, halal, vegan, $, nut-free) and cuisines are applied in the browser; "Family packs" and
  "Free delivery (Plus)" have no data and aren't shown.
- **Restaurant** `GET /api/v1/public/kitchens/{slug}` (SSR, SEO): live menus' sections with published, approved dishes,
  their modifier groups with the Studio's pick rules (exactly / at least / up to N, required, nested groups shown for an
  option, sold-out options), dish windows (always, lunch 11–2, after 5, weekends) and menu schedules (open hours, a
  window, by quote = never orderable here), live/scheduled combos with their slots, the AHS permit state, $15 minimum,
  8 % service fee, `taxBps`, and 30-minute scheduled windows (today and tomorrow within the kitchen's "scheduled days",
  ≥ 45 min + prep ahead, whole window inside the hours).
- **Fees (ours, the spec has no numbers beyond the design):** delivery $1.99 ≤ 1 km, $2.99 ≤ 2 km, $3.99 ≤ 4 km, then
  +$1 per 2 km ($2.99 when the distance is unknown); service fee 8 % of the dishes; tips No tip · $2 · $4 · 15 % · $6
  (up to $100 or 30 %); 100 % of the tip goes to the courier. Pickup: no delivery fee, no tip.
- **Checkout** (`/api/v1/me/food-orders`, signed in only): `POST /quote` (tax estimated from the province's rates),
  `POST /` (Idempotency-Key, X-Step-Up — **S-51's rule unchanged**: a phone-code-only session confirms its passkey or
  authenticator first → 403 `step_up_required`, or `second_factor_required` without one; the web reuses S-51's
  `StepUpDialog` and Payment Element), `POST /{id}/confirm` (Idempotency-Key), `GET /{id}` (tracking). Validation and
  conflict messages: "Pick 1 for Size." (and at least / up to), "Choose each option once.", "That choice isn't on this
  dish any more. Open it again.", "{dish} is sold out.", "Add $x.xx to reach the $15 minimum.", "Add your delivery
  address first.", "Choose delivery or pickup.", "Choose one of the windows offered.", "Choose a tip between $0 and
  $100, or up to 30 %.", "Choose how to hand it over.", "Choose from the extras offered.", 409 `kitchen_closed` /
  `out_of_range` / `no_delivery` / `no_pickup` / `checkout_closed`.
- **Payment = S-11 escrow, one PaymentIntent per food order** (`ref_type = 'food_order'`, manual capture). Unlike S-51
  (one intent per line + an uncaptured delivery-fee intent), a food order has one kitchen and one courier, so the intent
  carries the dishes + their tax (the kitchen's escrow) **plus Northline's own charges**: delivery + service fee, their
  GST/HST and the tip. Additive in payments: `PaymentAuthorizations.Request.platformCents`, `EscrowLifecycle.Hold.platform`
  (`PlatformCharges(feeCents, feeTaxCents, tipCents)`, old constructors kept), `payments.escrows.platform_fee_cents /
  platform_tax_cents / tip_cents` (V118); at capture the ledger credits the fee to revenue, the fees' tax to tax
  payable and the tip to a new `courier_tips` account. The hold is accepted only once the card is authorized; it is
  **released on hand-off** (`order.handed_off` → `KitchenEscrowRelease` now also releases `food_order` escrows).
  Finance should review this split.
- **`order.placed` is S-51's event**, not a second one: published once per food order with `orderType = "food"`,
  `delivery = "direct"` (hot courier) or `"pickup"`, no window. Additive: `Line.itemKind` (`offer` | `menu_item` |
  `combo`; S-51's 5-argument constructor defaults to `offer`) and the optional `itemKind` in
  `orders.order_placed.v1.schema.json`. No PII in the payload.
- **Schema V118 (orders, payments):** sequence `orders.food_order_numbers` (refs `FD-10000…`, like the kitchen display's food refs), `orders.food_checkouts`
  (the pending checkout: kitchen, mode, schedule, `customer_eta` for pickup, ETA range, priced lines jsonb, every
  amount with a sum check, province, tax calculation, PaymentIntent, delivery jsonb — address, drop-off, note, extras),
  the three escrow columns, `food_order` added to the `ref_type` checks of `payments.escrows`, `payments.payment_intents` and
  `payments.tax_calculations` (each check dropped and re-created wider — a relaxation, nothing existing changes). Placing writes `orders.orders` (`type = 'food'`) and `orders.order_lines` (menu item or
  combo, title, the choices + note as the kitchen display's modifiers), so the order reaches S-38's kitchen display as
  before. V118 sorts before S-43's V120 (Flyway's out-of-order setting already covers the ranges).
- **Tracking** follows the kitchen display: paid (not accepted) → cooking (`order.accepted`) → ready (`order.ready`)
  → on the way / picked up (`order.handed_off`) → delivered. ETA = ready-by + the ride (delivery) or ready-by (pickup),
  else placed + the quoted range. The web polls every 15 s; S-52's order SSE stream can replace the polling later.
- **Not done:** group orders; live courier positions (S-52's map); "Family packs" / "Free delivery (Plus)" filters;
  Plus pricing ($0 service fee) — no membership read yet; loyalty points on food. The checkout's pay note reads "The
  kitchen is paid once your order is handed off; until then the money is held in escrow." instead of the design's
  wording (escrow on hand-off is the story's rule).
- **Never run against the real service:** Stripe (PaymentIntent with platform charges, capture, the Payment Element)
  ran only against the local fake gateway; nothing here calls Google.

## 2026-10-01 — Error Prone warnings to zero

- **Count:** a clean `compileJava compileTestJava --rerun-tasks` of every module went from **102 Error Prone warnings** (plus 2 javac varargs warnings) to **0**. 91 were in `:api`; the rest were in the worker tests (9), `:sms` (1, `DoNotCallSuggester`) and `:search-index` (1, `InvalidParam`). auth, bff, platform, email and event-contracts had none. Merging the latest main (S-47, S-57 and others) brought 9 more (food, discovery, region), fixed the same way. Each one was fixed in the code; no new suppression was added.
- **`byte[]` in records → `ca.northline.shared.Bytes`** (11 records: uploads, stored objects, photos, evidence). It's an immutable wrapper that copies on the way in and out, compares by content, and whose `toString` shows only the size (Lombok-generated). We chose it over hand-written `equals`/`hashCode`/`toString` in every record because the code standards forbid that boilerplate. The ports (`ObjectStore.put`, the per-module storage ports) still take and return `byte[]`; the copies are acceptable at upload sizes of a few MB. The four records that already had a narrow `@SuppressWarnings("ArrayRecordComponent")` (`SecretSealer.Sealed`, `KeyWrapper.Wrapped`, the worker's `WebhookStore.Target`, `CommerceCatalogSource.WebhookRequest`) are unchanged: they hold key material and raw signed bodies that are never compared or logged, and the worker cannot see the api's `shared` package.
- **Request records:** a component that Bean Validation requires (`@NotNull`) is now non-null for NullAway and has no `@Nullable` next to it (`PayoutRequests`, `CaseRequests.GoodwillOffer`). The 422 messages and their tests are unchanged, because a missing field still binds to `null` and `@Valid` rejects it before the handler runs. The convention is in BACKEND_CONVENTIONS § 5.
- **Nested types with the same name** (`Command`, `Outcome`) are qualified where they are used, not renamed. Durations are now `ofDays(2)` / `ofDays(3)`; a `Duration` day is exactly 24 h, so behaviour is unchanged. Weekly earnings buckets use an `EnumMap` instead of `ordinal()`. The SMS `azure` provider returns its "not implemented" exception from the switch instead of a public method that always throws.
- **Proposal, not enabled:** once the in-flight feature branches have merged, make Error Prone warnings errors: add `-Werror` to the `JavaCompile` compiler args in `server/build.gradle.kts`, keeping `disableWarningsInGeneratedCode` and the excluded generated paths. javac then fails on any warning, Error Prone's included; the javac varargs warnings are fixed here too. Then a new warning fails `./gradlew build` instead of piling up. It is not switched on in this change because open branches would stop compiling. Until then: keep the count at 0 (BACKEND_CONVENTIONS § 10).

## 2026-10-01 — S-125 OpenAPI 3.1 for every HTTP service, with Swagger UI, Scalar and Redoc

Runbook: [docs/runbooks/api-docs.md](runbooks/api-docs.md). Stacked on S-124 (Makefiles, merged as #59).

- **A shared library `server/openapi`** (like `platform`, `email`) used by the api, northline-auth and the BFF, not by
  the worker:
  - springdoc 3.1.1 with its UI starter and, as the user asked, **springdoc's own Scalar starter**
    (`springdoc-openapi-starter-webmvc-scalar`, which brings `com.scalar.maven:scalar-webmvc` 0.5.55);
  - the Redoc page; the shared conventions and security schemes; the docs-path CSP;
  - the `OpenApiSnapshot` test fixture.

  It is auto-configured and off with `northline.docs.enabled=false`. Versions checked on 2026-09-30: springdoc 3.1.1
  is the latest release, and Redoc 2.5.4 is the latest on npm.
- **Redoc is self-hosted from the npm tarball** with a pinned version and the registry's sha512 integrity, as in samop
  (Maven there, Gradle here). The webjar stops at 2.5.0, and a CDN would need the CSP to allow another origin. The
  page has no inline script: `redoc-init.js` reads the group from `?group=`. The pages are WebMvc.fn routes rather than
  a `@Controller`, because the api component-scans `ca.northline.**` and would register an annotated controller twice.
- **Scalar shows every group on one page.** springdoc's Scalar controller turns the groups into Scalar *sources*, with
  a switcher. There is no per-group Scalar URL, so the landing page links Scalar once per service.
- **Groups per audience.** The api has `public` (public reads + the signed-in customer, incl. `/me/**`, `/cart/**`),
  `studio`, `partner`, `console`, `webhooks` and `internal`:
  - `partner` is a method filter on `@PartnerAccess`, with the annotation's scope as the operation's security, so the
    partner document can never list an endpoint partners can't call.
  - `webhooks` is S-33's JSON Schemas (`docs/spec/webhooks`, already on the api classpath) as OpenAPI 3.1 `webhooks`,
    used verbatim (`$schema` / `$id` dropped). The reference is the same contract the worker validates deliveries
    against; it includes `order.placed` from main.
  - A test fails when an `/api/**` path is in no group.

  northline-auth has `public` (the OAuth 2.1 / OIDC endpoints, described by hand because Spring Authorization Server
  serves them from filters) and `internal` (the `/api/auth/**` JSON API). The BFF has one `internal` document per
  profile (studio-bff, consumer-bff), and `/bff/logout` is described by hand because it is a filter. The worker has no
  HTTP API besides actuator, so it has no document.
- **Global `springdoc.paths-to-match` removed from the api.** springdoc ORs it with each group's `pathsToMatch`, so
  every group got every path. Groups don't share an `OpenAPI` bean either: springdoc reuses and mutates one for every
  group, so each group starts with its own `ApiDocs.base(props)` customizer and ends with `ApiDocs.conventions()`.
- **Conventions instead of annotations on ~200 handlers:**
  - `*Id` path parameters → `Ulid`;
  - writes with a body → `422 ValidationErrors` (with the validation-rules example);
  - secured operations → `401`/`403 Problem` (RFC 9457 with `code`);
  - parameterised paths → `404`;
  - `*Cents` → int64 cents of CAD.

  `override-with-generic-response=false` keeps springdoc from attaching every `@ExceptionHandler` response to every
  operation. Unused shared schemas and security schemes are pruned per document. Operation ids are
  `<controller><Method>`, so adding a `list` method elsewhere doesn't renumber `list_5` in every spec.
- **Security schemes:**
  - `bffSession` (cookie) + `csrf` (`X-XSRF-TOKEN`);
  - `oauth2`: authorization code + PKCE, with the auth issuer's URLs;
  - `dpop`: `http` scheme `DPoP`, plus `dpopProof` for the `DPoP` header (RFC 9449, S-29);
  - `partnerClientCredentials`: OAuth 2 client credentials with `x-token-endpoint-auth-methods: [private_key_jwt]`
    and the S-30 assertion rules in the description;
  - `authSession`: northline-auth's cookie;
  - `webhookSignature`: S-33.

  Public reads carry `security: []`.
- **Generated at build time and committed, with a drift check.** Each app's `OpenApiSpecsTest` boots the application
  context and fetches `<api-docs>.yaml/<group>`:
  - the api uses its own test class; auth uses its own; the BFF tests are added to `BffSessionTest` /
    `ConsumerBffTest`, so no extra Spring context;
  - `-Popenapi.write=true` (`make openapi`) writes `docs/api/openapi/<service>-<group>.yaml`;
  - otherwise the test compares, so `./gradlew build` fails on a difference.

  Output is stable thanks to `writer-with-order-by-keys` and fixed `servers`/issuer pinned in each
  `application-test.yml`. YAML only: it is easier to review in diffs than JSON, and JSON is one URL away. The
  springdoc Gradle plugin was not used: it needs a running app with all its stand-ins and doesn't fit
  `./gradlew build`.
- **Lint: Redocly CLI 2.57.0 with a root `redocly.yaml`** (samop's tool and layout), run through `pnpm dlx`, pinned in
  `make/docs.mk`, with no lockfile change. `recommended`, with `security-defined` as an error. Currently 0 errors and
  17 warnings: public GETs without a 4xx, and redirects without a 2xx. Manual CI: GitHub `openapi.yml`
  (lint + check) and GitLab `openapi:lint` (new `PIPELINE_PART=openapi`, also in `all`).
- **Where the viewers run (S-17 routes, S-20 CSP).** auth and the BFFs answer with `default-src 'none'`.
  - **CSP:** instead of relaxing it, or serving everything from the api only (the api host isn't routed for browsers
    beyond `/api/v1`), the viewer paths get their own filter chain (order 0). Its narrow CSP allows `'self'` scripts
    and styles (+ `'unsafe-inline'` for Swagger UI's and Scalar's initialisers), `connect-src 'self' <issuer>`,
    `frame-ancestors 'none'`. Every other path keeps `default-src 'none'`, and the tests check both.
  - **Routes:** auth's host already routes everything. The BFFs serve their documentation under `/bff` (`/bff/docs`,
    `/bff/swagger-ui.html`, `/bff/docs/scalar`, `/bff/v3/api-docs`), the prefix the edge already sends them. The api
    host gets `/docs`, `/swagger-ui(.html)` and `/v3/api-docs` through the new chart value `apps.api.docsRoutes`
    (true in `values-dev.yaml` / `values-staging.yaml`, false by default). The chart refuses it in prod, and
    `validate.sh` checks the refusal.
- **Prod exposes nothing:** all three `application-prod.yml` turn off springdoc, Swagger UI, Scalar and
  `northline.docs` (and with it the pages and the CSP chain), and each app's test asserts it. The public, partner and
  webhook references reach production through the docs site (S-126), from the committed files.
- **No new environment variable or secret, and no schema change.** `northline.docs.*` reads the existing
  `AUTH_ISSUER`, `API_PUBLIC_URL` and `AUTH_PUBLIC_URL`.
- **Not done:**
  - per-operation `@Operation` summaries and response examples beyond the conventions (descriptions come from the
    group texts);
  - Scalar's and Swagger UI's "Authorize" against a real northline-auth: no docs OAuth client is registered, so
    "Try it" uses pasted tokens. A public `docs` client with the viewers' redirect URIs is follow-up work.
  - the viewers were checked by status and content in MockMvc, not clicked through in a browser;
- **Found, not fixed: a JDK HttpClient race in the gateway relay (bff).**
  - **Symptom:** `ConsumerBffTest`'s relay tests fail intermittently with an NPE in `Http1Exchange.requestMoreBody`
    (the body subscriber is not set yet when Spring's empty `OutputStreamPublisher` completes).
  - **When:** whenever an OpenAPI document had been generated in the same Spring context first, and more often when
    Swagger UI's webjar files had been served in the JVM. Main is stable: 4 of 4 runs passed. This branch failed 4 of
    6 runs before the change below.
  - **Mitigation:** the consumer-bff spec is checked in its own context (`ConsumerBffOpenApiTest`), and the BFF test
    checks Swagger UI through its redirect and `swagger-config` instead of the webjar files. Since then the suite
    passes (3 of 3).
  - **Production:** never affected, because springdoc is off in prod.
  - **Follow-up for the lead:** dev/staging could hit the race after someone opens `/bff/docs`. Candidate fixes are a
    gateway MVC `ClientHttpRequestFactory` without a body for GET, or the JDK fix.

## 2026-10-01 — S-126 Docusaurus documentation site (docs., en/fr)

Runbook: [docs/runbooks/docs-site.md](runbooks/docs-site.md). Stacked on S-125 (#74), whose committed specs it renders.

- **`web/apps/docs`, Docusaurus 3.10.2**, in the pnpm workspace (`@northline/docs`). Versions checked on 2026-09-30:
  - redocusaurus 2.5.2 for the Redoc view;
  - `@scalar/docusaurus` 0.8.46 for the Scalar view;
  - `@easyops-cn/docusaurus-search-local` 0.55.3, a local index with no Algolia account.
- **docs/ is rendered where it is committed, nothing is copied.** The repository's docs are a named docs-plugin
  instance (`repo`, `path: ../../../docs`) with `include`/`exclude` globs:
  - no `spec/`;
  - no `api/` (rendered as the API reference instead);
  - no backlog CSV;
  - no agents' workstream brief.

  `markdown.format: 'detect'` keeps `.md` files CommonMark, so no existing page needed MDX escaping. The site's own
  guides are the default instance, so the theme and search always have one, whatever the variant. The only copies
  are build artefacts: the variant's specs in `static/openapi` (Scalar and the download links fetch them) and
  Scalar's standalone bundle.
- **Two builds instead of an edge-protected section: `public` and `internal`** (`NORTHLINE_DOCS_VARIANT`,
  `src/content.ts`).
  - The public build physically lacks the internal pages and specs, so a routing or edge mistake can't expose them.
    The page check fails the build if one appears.
  - `docs` (public) runs on `docs.<zone>` in every environment. `docs-internal` runs on `internal-docs.<zone>` only
    when enabled, behind an **IP allowlist** (an Envoy Gateway `SecurityPolicy`, `edge.docsInternal.allowedCIDRs`).
    The chart refuses it outside `local` without CIDRs or without the Envoy edge, and `validate.sh` checks both the
    render and the refusal.
  - SSO through northline-auth (`SecurityPolicy` `oidc` with a `docs` client) is the planned upgrade. It is not built
    because it needs an OAuth client registration and a session at the edge, which is more than this story.
  - Off by default in every environment: enabling it needs the operators' CIDRs.
- **Redoc and Scalar for every spec.**
  - Redoc pages come from redocusaurus. Its bundled Redoc 2.4 differs from the server's self-hosted 2.5.4, which is
    acceptable.
  - Scalar pages come from `@scalar/docusaurus`. The plugin loads the *unpinned latest* `@scalar/api-reference` from
    jsDelivr by default; the site serves the pinned 1.72.3 standalone bundle itself (`cdn` option), with telemetry,
    the hosted "Ask AI" agent and the "Generate SDKs" toolbar off. No page loads anything from another origin.
  - The API reference page links to the services' own Swagger UI / Scalar / Redoc in dev and staging through
    `/config.js` (`NL_DOCS_SWAGGER`, derived by the chart; empty in prod), following the Studio's runtime-config
    pattern (S-14).
- **i18n en + fr:**
  - UI chrome is translated (`i18n/fr`; the theme and search ship their own French);
  - the guides have French pages;
  - the repository docs are English, and the French site falls back to them, as the story allows.

  The Northline tokens theme Infima through `@northline/tokens/tokens.css` and `color-mix`, with no new hex in
  components. The hex-colour lint now also scans `apps/docs/src`.
- **The AI section.** `docs/ai/README.md` is a short placeholder: the in-product AI features (Spring AI with
  OpenRouter) are another story. Any page added under `docs/ai/` appears under **AI** in the internal sidebar.
- **Versioning not enabled.** The docs follow `main`, and the API documents carry their own version (`v1`). Cutting a
  docs version (`docusaurus docs:version`) is documented as an option, not done.
- **Container.** `web/Dockerfile` targets `docs` and `docs-internal` on nginx-unprivileged, like the Studio, and take
  the repository's `docs/` as the named build context `repo-docs`, since the web build context stays `web/`.
  - Both variants are built once in a shared stage, and the page check runs inside the image build.
  - The CSP allows only `'self'` (+ `'unsafe-inline'` scripts for Docusaurus' colour-mode and Scalar's init snippets)
    and `connect-src` to the api and auth origins (`NL_DOCS_CONNECT`).
  - Checked in headless Chromium against nginx with that CSP: the home page, the API index, Redoc, Scalar, guides,
    French and search render, and Scalar needs no `'unsafe-eval'`.
- **Fixed during that check:**
  - Prism `additionalLanguages` load only on the client in Docusaurus 3.10 and caused hydration mismatches (React
    #418) on every page with a code block. They were dropped: bash and Java blocks are now unhighlighted.
- **Known and left:** the Redoc pages still log one recoverable hydration mismatch (redocusaurus' server render vs
  client), and Redoc's sidebar badge from `cdn.redoc.ly` is blocked by the CSP (Redoc then hides it).
- **Deploy:**
  - chart: `apps.docs`, `apps.docs-internal`, `urls.docs`, `urls.docsInternal` (dev, staging, prod; empty on kind,
    where `docs` is off), routes and certificates through the S-17 edge;
  - Argo CD: the `SecurityPolicy` kind is whitelisted; `docs` / `docs-internal` added to the promotion files and
    `promote.sh`, so Argo CD deploys docs to dev once its digest is promoted;
  - Terraform: `docs` and `docs-internal` added to the registry repositories of all three clouds;
  - CI: the web image matrix (GitHub `deploy.yml`, GitLab `images:web`) builds both.

  Static export: `make docs-pages` plus manual GitHub `docs-pages.yml` (Pages from Actions) and GitLab `pages`
  (`PIPELINE_PART=pages`, not in `all`), public variant only.
- **Checks:**
  - `make docs [DOCS_VARIANT=public]` = build + `scripts/check-build.mjs`, which requires every runbook and every
    spec's Redoc and Scalar page in en and fr, and no internal page in the public variant;
  - vitest unit tests: content per variant, a spec list matching `redocly.yaml`, runtime config;
  - manual CI: GitHub `web.yml` › `docs`, GitLab `web:docs`.
- **No schema change, no secret.** New container variables `NL_DOCS_SWAGGER` and `NL_DOCS_CONNECT` are derived by the
  chart (runbooks README).
- **Not done / never run:**
  - no image was built here: the build is ~3 GB of Docker layers and the machine has ~4 GB free. The nginx
    configuration was run against the local build output in nginx-unprivileged 1.29 instead;
  - nothing is deployed, and no Pages site was published;
  - the IP allowlist was rendered and schema-validated, not enforced by a live Envoy Gateway;
  - French translations of the repository docs;
  - SSO for the internal site.

## 2026-09-30 — S-53 Services landing, service category, provider list

Branch `web/s-53-services-landing` (from main). Contracts: [CONSUMER_WEB_PLAN.md](CONSUMER_WEB_PLAN.md).

- **New api module `hire`** (consumer side of the Services journey, S-53 … S-56): a composition module like `studio`
  — no tables, only other modules' `api` packages (availability, catalogue, merchants, trust; booking and payments
  from S-55), nothing depends on it. Chosen over putting the reads in `booking` (it can't depend on availability or
  payments without a cycle: availability → booking, payments → booking) or `search` (projection only). Its read models
  are serialized as they are (as the Studio dashboard does): they exist only for these screens.
- **Public endpoints** (`GET /api/v1/public/**`, open since S-31; `?lang=en|fr`, default en):
  `GET /api/v1/public/services` → `{liveCategories, providers, groups: [{id, key, names, note, items: [{slug, names,
  kind, providers}]}]}` · `GET /api/v1/public/services/{slug}` → `{id, slug, names, group, kind, vehicle,
  regulatedRegistry, providers, quoteable, jobs: [{name, included, pricingMode, priceCents, durationMin}]}` (404 for an
  unknown slug) · `GET /api/v1/public/services/{slug}/providers?lat&lng&city` → `{categorySlug, kind, area, city,
  items: [{merchantId, slug, name, tier, brandColor, blurb, rating, reviewCount, onTimePct, disputePct, rebookPct,
  fromCents, pricingMode, instantBook, nextAvailable, zones}]}`. Landing and category: `Cache-Control: max-age=60,
  public`; providers 30 s. The category `slug` is the leaf part of the CategorySeeder id (`mobile-mechanic`; unique
  across the service root, checked). Validation (messages not in validation-rules.md, English in both locales like the
  other server rules): one of `lat`/`lng` alone → 422 "Send both lat and lng, or neither."; out of range → "That
  location is outside the map."; `city` over 60 → "At most 60 characters.".
- **A category's providers** = active `provider`/`both` businesses with a published business page and at least one
  live, approved service (`catalogue.services`) in that leaf. Counts on the landing and the category page are these,
  not filtered by location (server-rendered pages are the same for everyone); "live categories" = leaves with at least
  one. The design's "62 categories · 1,204 providers" are sample numbers.
- **Booking type per category** (`hire.domain.ServiceKind`): `catalogue.categories.booking_type` wins when set, but
  the seed sets none (and `seedCategories` owns the rows), so it comes from the group — automotive, home trades,
  tech: visit · cleaning & property, pets, education, childcare: home · events: event · personal care: appointment ·
  professional: consult — with leaf exceptions (movers → event as in the design; property management, career/life
  coach, web design, photo editing → consult; mobile hair & makeup, home care aide → home; dog grooming →
  appointment). `vehicle` (the wizard's vehicle questions) = the automotive group. Quotes for visits and events only
  (the design's `quoteable`), events are quote-only.
- **Service area = zones** (`availability.service_areas`, V041, names from Booking rules). **V114** adds
  `availability.service_zones`: each of the nine names with its city, a centre and an approximate circular area
  (Beltline 1.2 km … SE Calgary 10 km, Airdrie/Cochrane/Okotoks the towns) — reference data, the same everywhere, until
  `region.zones` has Calgary polygons. A business covers the customer when one of its zones contains the device's
  point, or, with only a city (the IP guess, the Calgary fallback, a saved address without coordinates), when one of
  its zones is in that city; no location at all = the region's fallback market (S-47's `GET /api/v1/geo/markets` → `fallback`, through the new `region.api.FallbackMarket`; nobody is covered when none is configured). The web sends the fallback market's city itself (S-47's `useDeliveryLocation`). Appointments and consultations (the customer goes to them)
  also match the business's own city. The heading's area is the zone the point is in (nearest centre when two
  overlap), else the city. `availability.api.ServiceAreas`.
- **Trust order** (design: "Sorted by trust · tier, on-time rate, dispute rate and re-book rate"): tier, then on-time
  (higher), disputes (lower), re-book (higher), rating, name; a business without a nightly quality score yet sorts after
  scored ones of its tier (`hire.domain.TrustRank`). The consult variant's "Sorted by recent sales…" isn't used: there
  is no sales ranking, and the note must say what the list does.
- **Next available** is computed live: `availability.api.ProviderSlots` (new, used again by S-55's calendar) = the
  Studio preview's `SlotPlanner` over every bookable member (hours, time off, closed holidays, confirmed jobs, S-32
  calendar busy blocks, travel buffer), merged, then minimum notice, "same day by 9 am", horizon and jobs per day. The
  per-day part of `HoursService.preview` moved into `DaySchedule` so both use it (Studio behaviour unchanged). "Next
  available" looks at most 14 days ahead. Cost: a few queries per member-day per provider — fine for a city's providers
  in one category; the search projection's `next_slot` (E-6) should replace it on the list later.
- **Not shown / not built from the design's list:** the distance ("1.2 km") — businesses have no base location, only
  zones, so the filter "Under 3 km" isn't offered; "EV certified" (no such attribute). Filters kept: Master tier,
  Instant book, Available today, Under $80 — applied in the browser to the covering providers (none on by default;
  the prototype's pre-ticked "Master tier" is demo state). Rows show "★ rating (reviews) · on-time" or "New on
  Northline", "from $79" / "from $45/h" / "Quote", and "Today 3 pm" / "Tomorrow 9 am" / "Thu 9 am" / "No openings in
  the next 2 weeks".
- **Copy:** the design details one category per booking type (mobile mechanic, cleaning, bar, barber, real estate).
  Its text is used for that family (automotive; the cleaning leaves; cocktail bar and bartender; barber & hair;
  real-estate agent) exactly; the other categories of a type get generic wording in the same shape (ours: blurb, "where",
  CTA hint, first two steps of visits) — e.g. a plumber isn't "Licensed technicians who bring the shop to you". The
  CTA noun ("See 14 mechanics") and the list heading noun ("Mobile mechanics · 14 come to Beltline") come from the
  design's `nounBy` for those families, "providers" / the category name otherwise. Group lines: the design's for its
  five groups, ours for pets, education, tech, childcare.
- **French category names** live in the consumer app (`features/services/taxonomy.ts`, all 10 groups and 108 leaves):
  `catalogue.categories.name_i18n` is English-only and `seedCategories` rewrites it, so a migration couldn't keep them.
  The API still returns `names` so a translated table takes over by itself; names without French are marked
  `lang="en"`.
- **Pages:** landing and category render on the server (loader → `ensureQueryData`, the screen `useSuspenseQuery`,
  title + description meta, 404 → the not-found screen); the provider list renders the category on the server and
  loads the covering providers in the browser once the location is known (device/saved coordinates are sent; the
  Calgary fallback and the IP city only as a city). Loading skeletons, empty ("No verified providers cover Beltline for
  this service yet." + See all services; "No providers match these filters." + Clear filters) and error (Retry) states.
- **Shared-contract changes (additive):** new route `/services/$category/quote` (screen key `quoteRequest`, S-56 —
  the category's "Describe the job, get 3 quotes"; `ScreenPending` until then); UI kit `BrandMark` (a business's
  initial on its brand colour, with a story); `availability.api.ServiceAreas`/`ProviderSlots`, `catalogue.api.
  ServiceOffers`, `merchants.api.PublicProviders`.
- **Not done:** search-backed ranking and `next_slot` (E-6); distance; per-category French taxonomy in the database;
  structured data (S-63).
- **Region-neutral** (the decision above): no province, city or time zone in this story's code or copy. The province
  (tax) is the business's own, else the default market's, and the time zone the province's market's — both from S-47's
  `region.api.Markets` (SEARCH_MARKETS / SEARCH_DEFAULT_MARKET); the city for a visitor without a location is the api's
  fallback market (new, additive `region.api.FallbackMarket`, implemented by `GeoService` — the same answer as `GET
  /api/v1/geo/markets`); `hire.application.RegionDefaults` reads them. The landing and a category return `provinces`
  (their live providers' `merchants.province`) and the copy names them as a parameter ("4 categories live in
  {region}"); registry names in the copy come from the category (`regulatedRegistry`). Existing literals still relied on
  (S-134): the web's shared `TIME_ZONE` (`@northline/ui`), `AlbertaHolidays` and `Team.ZONE` in the availability
  planner (moved into `DaySchedule`, unchanged), and the zone rows of V114 (reference data).

## 2026-09-30 — S-54 Public provider page from the storefront API (sections, reviews, service area)

Branch `web/s-54-provider-page`, **stacked on `web/s-53-services-landing`** (uses its `hire` module, service kinds and
copy).

- **Two public reads, one page.** The page itself is the storefront API as it was (`GET /api/v1/storefronts/{slug}`:
  enabled sections in the owner's order, brand colour, logo, tagline, announcement, CTA label, verified facts); new
  `GET /api/v1/public/providers/{slug}` (module `hire`) adds what Northline holds: rating and review count, the latest
  quality score's on-time / dispute / re-book figures, the live approved services (with each one's category and
  booking type), the service-area zones, the next free slot (S-53's `ProviderSlots`) and the three newest reviews;
  `GET /api/v1/public/providers/{slug}/reviews?offset&limit` pages the rest (10 by default, at most 20). Both
  `max-age=60, public`. 404 unless the business is an active `provider`/`both` with a published page. The loader
  fetches both in parallel on the server.
- **The business's kind** (visit, home, event, appointment, consult — the CTA title, the mode tag and the copy family)
  comes from the category most of its services are in; a business without services is a visit.
- **Sections in order** (storefront-sections.json): the hero (design `pv` hero: brand-colour band, logo or initial,
  name, tagline + "since <year of approval>", "<tier> tier · verified") and the trust figures and credential tags
  always open the page; then the enabled `about` (the Business-step description), `reviews` (three newest, "Show
  more reviews", the business's public reply under a review, "New on Northline — verified" before the first), `area`
  (the zones — "Comes to you in Beltline · Downtown."; appointments and consultations: "You go to them in <city>."),
  `faq` (the builder's pairs as disclosure widgets) and `policies` in the owner's order. `services` + `cta` are the
  aside (design): the service menu (name, duration, price or "Quote") only when `services` is enabled, as the spec says
  ("Only the Book button remains"), the button labelled with the page's CTA label (Book a visit / Request a quote /
  Order now / Reserve — the owner's choice wins over the design's per-type wording), "Not sure? Request a quote" for
  quoteable kinds, "Next available: … · <note>". The announcement is a strip above the hero.
- **Not rendered:** `gallery` (the builder can't add photos yet, S-53 preview shows the same), the map of the area
  (no map provider; the zones are listed), and `featured`/`catalogue`/`delivery`/`menu`/`hours`/`fulfil`/`permit`
  (store and menu sections; a `both` page's products belong to the shop pages, S-49/S-50). Credential tags from verified
  checks: licence → "AMVIC licensed", insurance → "$2M insured" (the insurance check requires ≥ $2M, onboarding
  decision), ID → "ID verified", site visit → "Site visited"; the design's "Red Seal journeyman" etc. are sample text
  with no data behind them. The consult variant's "sales · 12 mo" figure has no source; re-book is shown for everyone.
- **SEO-ready, structured data left to S-63:** server-rendered title ("Prairie Wrench · Mobile mechanic · Calgary ·
  Northline"), description (the business's description, else its tagline), canonical (the live custom domain when there
  is one — the owner's own address is the page's home — else `NL_SITE_ORIGIN/providers/<slug>`) and Open Graph tags.
  No `hreflang`: the language is a cookie, not a URL (S-63 decides).
- **Other hosts** (plan: "storefront pages on `pages.<zone>` and merchants' own domains are S-54/S-63 work"): the node
  server (`server/page-hosts.mjs`) classifies the `Host`: the site; `pages.<zone>` (`NL_PAGES_HOST`) where `/<slug>` is
  that page; any other host = a merchant's domain, resolved with S-31's `GET /api/v1/public/storefronts/by-host`
  through the consumer-bff (cached 60 s like the endpoint's `Cache-Control`, a failed lookup isn't cached → 503 "Try
  again in a moment."; unknown host → 404 "No Northline page is connected to this domain."). Only business pages are
  served; other page kinds and every other path redirect (302) to `NL_SITE_ORIGIN`. The server passes the page as
  `x-nl-page-mode/host/slug` headers (the browser's are dropped); the router's `rewrite` maps the host's `/` or
  `/<slug>` onto `/providers/<slug>` and back, so SSR and hydration agree while the address bar keeps the merchant's
  URL. Sign-in, booking and the cart live on the site (the consumer-bff client has one redirect URI; merchants' domains
  get no `/api` route), so on those hosts the page's links are absolute to `NL_SITE_ORIGIN` (`siteHref`) and "Show
  more reviews" becomes "See all reviews" on the site. Local development: the Vite dev server doesn't do host routing;
  the built server does with `NL_PAGES_HOST` (runbooks/local.md). New variables `NL_SITE_ORIGIN`, `NL_PAGES_HOST`
  (consumer app, optional, the chart sets them from `urls.consumer` / `urls.pages`); `publicConfig` carries `siteOrigin`
  and `page`, `useSiteConfig()` reads them.
- **New public API:** `trust.api.PublicReviews` (newest first; author display name, job label, reply — nothing else
  about the author).
- **Not done:** JSON-LD, sitemap, `hreflang` (S-63); the shell on a merchant's own domain still shows the site header,
  whose links and session/cart calls go to the site or fail quietly (no BFF route there) — a slimmer page chrome for
  other hosts is left to S-63; the CDN in front of these pages (edge caching) isn't configured.

## 2026-09-30 — S-55 Booking wizard (job details → location & access → schedule → payment → confirmed)

Branch `web/s-55-booking-wizard`, stacked on `web/s-54-provider-page` (itself on S-53) and on S-51
(`web/s-51-cart-checkout`, #56) for the payment step-up, `IdempotentRequests` and `PaymentSettings`.

- **Flow** (module `hire`, `BookingCheckoutService`): `GET /api/v1/public/providers/{slug}/slots?serviceId&from&days`
  (public, ≤ 14 days) → `POST /api/v1/me/bookings/holds {slug, serviceId, startsAt, hours?}` (201; the slot and a
  booking id chosen up front) → `POST /api/v1/me/bookings/checkout` (Idempotency-Key, `X-Step-Up`; validates every
  answer, prices it, opens the manual-capture PaymentIntent for `booking:<id>`) → the Payment Element confirms the card
  when the provider is Stripe (the fake gateway authorizes at once) → `POST /api/v1/me/bookings/holds/{holdId}/confirm`
  (201; `EscrowLifecycle.hold` checks the authorization and writes the escrow row, S-11; the booking is written, the hold
  freed) → `GET /api/v1/me/bookings/{id}`. Sign-in is required from the hold on (the calendar is public); the wizard
  keeps the answers in `sessionStorage` across the trip to the sign-in page and clears them once booked.
- **Real availability** = S-53's `ProviderSlots` (hours, time off, holidays, confirmed jobs, S-32 calendar busy blocks,
  travel buffer, notice, same-day cutoff, horizon) **plus other customers' live holds**. A slot shows free when at least
  one bookable member is free; the hold picks the first free member.
- **Slot holds in Valkey** (`availability.api.SlotHolds`, 10 min): a Lua script checks overlap (with the travel buffer)
  against the member's holds and places the hold atomically; keys share a `{m:<merchant>}` hash tag so the script is
  cluster-safe. A customer has at most one hold per business (a new one replaces it). Profiles `local`/`test` use an
  in-memory store with the same rules. At confirm, an advisory lock per member plus an overlap lookup on
  `booking.bookings` is the last guard (409 `slot_taken`). 409 `hold_expired` ("Your 10-minute hold ended. Pick the
  time again.") sends the wizard back to the calendar.
- **Access notes are private**: V115 `booking.access_notes` holds the access instructions and the day's phone number
  sealed with `SecretSealer` (context `booking.access_notes:<booking id>`); they never enter `bookings.details`, events
  or webhooks. The provider's Studio job card shows them only from 1 h before to 1 h after the start (design:
  "shared with the provider only for the two hours around the visit"). Between hold and confirm the answers ride with
  the hold, sealed too.
- **Payment step-up = S-51's rule** (`PaymentGate`): an `acr=mfa` sign-in pays directly; a phone-code sign-in sends an
  `X-Step-Up` proof from `/auth/step-up/*`; an account with neither gets 403 `second_factor_required` and enrols a
  passkey (the S-51 `StepUpDialog`). A free consultation holds no money and skips it.
- **Pricing** (`hire.domain.Pricing`): fixed = the service price; hourly = rate × hours (home services ask the hours;
  cleaning estimates them from bedrooms + add-ons, design 06); consultations free; quote-only services and services
  without instant book answer 422 ("…ask for a quote instead", S-56). Sales tax from `region.api.TaxRates` for the provider's province. The whole
  price + tax is held in escrow ("Hold $93.45 in escrow"). The rate is the provider's province's
  (`merchants.province`, else the default market's, `region.api.Markets`); the provider page returns it (`taxBps`) so the
  wizard's summary shows the same tax, labelled "Tax 5%" (the tax's name differs by province). Free cancellation until 12 h before is recorded on the
  booking (`free_cancel_until`) and shown; charging late cancellations is not part of this story.
- **Validation messages** (422, per field; en in both locales like the other server rules): "Describe the problem in
  at least 10 characters.", "Tell us the vehicle year, make and model.", "Pick or enter the address.", "Add access
  instructions (3+ characters).", "Enter a phone number like +1 403 555 0123.", "Accept the cancellation policy to
  continue.", "Agree to the Northline terms to continue.", "Choose one of the options.", and for the hold "Pick a
  time.".
- **`booking.confirmed`** (`booking.api.BookingConfirmed`, schema `booking.booking_confirmed.v1`, topic
  `booking.booking`): ids, type, times, price, deposit — no names, addresses, notes or phone numbers. The worker maps it
  to the public webhook `booking.confirmed` (`docs/spec/webhooks/booking.confirmed.v1.schema.json`, without the
  customer id) for S-33, and `CalendarSyncListener` writes it back to the member's connected calendar (S-32).
- **Schema (V115, additive):** `booking.access_notes`; `bookings.source` (`studio`|`customer`), `tax_cents`,
  `free_cancel_until`; index `ix_bookings_member_starts`.
- **Region-neutral:** "today" and calendar days use the provider's province's time zone (`region.api.Markets.zone`, S-47) on the server and the shared
  `TIME_ZONE` of `@northline/ui` in the browser (an existing literal, S-134); the planner underneath still uses
  availability's `Team.ZONE` and `AlbertaHolidays` (existing, S-134).
- **Not done:** cancelling the PaymentIntent when a hold expires after the card was confirmed but before `confirm`
  (the authorization lapses at Stripe on its own; a sweeper is follow-up work); saved cards, points and promo codes on
  bookings; photo upload in job details; late-cancellation fees; the customer's bookings list (`/account/orders`
  shows orders only).

## 2026-09-30 — S-56 Quotes: request from several providers, compare, accept with an escrow deposit

Branch `web/s-56-quotes`, stacked on `web/s-55-booking-wizard` (and so on S-54, S-53 and S-51).

- **Request** (`POST /api/v1/me/quote-requests`, module `hire` → `booking.api.CustomerQuotes`): the category, 1–3
  providers (their page slugs), the description (10–1,000 characters), the vehicle for automotive categories, the
  event date and guests for events, an optional budget, note, area and preferred date. Each provider must be published
  and offer a live service in that category; only quoteable categories (visits and events, S-53's `quoteable`) take
  requests. It is one `booking.quote_requests` row with `merchant_ids` — exactly what the Studio "Quote requests"
  column (Operations) already lists — `respond_by` = now + 2 h (design: "Master-tier providers answer within 2
  hours"), `expires_at` = now + 7 days. **Providers see the area only**: the street address, access instructions and
  the day's phone number are given to the one provider whose quote is accepted, at acceptance (sealed with the booking,
  S-55's `booking.access_notes`). Validation messages: "Choose 1 to 3 providers.", "Describe the job in at least 10
  characters.", "Tell us the vehicle year, make and model.", "Pick a date from tomorrow on.", "Enter the number of
  guests (1 to 2,000).", "One of these providers doesn't offer this service any more. Choose again.", "This service is
  booked directly — pick a time on a provider's page.".
- **Compare** (`GET /api/v1/me/quote-requests/{id}`): every provider asked, with its latest sent version (never a
  draft), cheapest first, then "waiting" / "declined". **Read** (`GET /api/v1/me/quotes/{id}`): every line (kind,
  description, note, qty, unit, amount), subtotal/GST/total, scope, exclusions, warranty, deposit (kind, rate, amount),
  proposed time and duration, validity, and every version from that provider; opening it moves `sent` → `viewed` (the
  provider sees it was read). Someone else's request or quote is 404.
- **Versioning** is the merchant side's (V040: immutable once sent; a revision is a new row, version + 1, and the prior
  one becomes `superseded`). A customer on an old version sees "revised — version N replaces it" with a link, and
  accepting it answers 409 `quote_revised`; an expired quote 409 `quote_expired`.
- **Accept = pay the escrow deposit** (S-11): `POST /api/v1/me/quotes/{id}/accept` (Idempotency-Key, `X-Step-Up`,
  the S-51 rule via S-55's `PaymentGate`) opens a manual-capture PaymentIntent for the booking chosen up front — the
  quote's deposit, or **the whole quote when it asks for none** (as instant bookings hold the whole price). The tax in
  the held amount is the deposit's share of the quote's GST. `POST …/accept/confirm` (Idempotency-Key) then checks
  the authorization (`EscrowLifecycle.hold`), accepts the quote (`quote.accepted`, existing v2 schema), and books the
  proposed time — the quote's `proposedAt`, else the request's preferred date at 9 am in the default market's time zone (`region.api.Markets`) — with a free member
  (`ProviderSlots.freeMember`; 409 `slot_taken` "The provider is no longer free at the proposed time…"), publishing
  `booking.confirmed` with the `quoteId` (S-55). **V116** `booking.quote_acceptances` keeps the booking id, the
  PaymentIntent and the amounts between the two calls (one row per quote version; replaced while not accepted).
- **Decline** (`POST /api/v1/me/quotes/{id}/decline`): the quote becomes `declined`; the request's other quotes stay
  open.
- **Screens:** `/services/$category/quote` (the job → where & when → who should quote; `?provider=` pre-ticks the
  business the customer came from — the provider page's "Not sure?" button, and the main button of event businesses,
  which are quote-only), `/quotes/requests/$requestId` (new, screen key `quoteCompare`: "N quotes received") and
  `/quotes/$quoteId` (design 06 `quote`). The request survives the trip to the sign-in page in `sessionStorage`.
- **Region-neutral:** no place in the code or copy; tax labels read "Tax 5%" (the rate from the quote). Existing
  literal relied on: `QuoteService.PROVINCE` ("AB", Operations) sets the tax of the quotes providers send (S-134).
- **Not done:** the balance of a deposit quote ("balance held 48 h before the event") — only the deposit is held at
  acceptance; messages on a quote ("Ask a question first", the design's thread) — no customer↔provider messaging API
  yet; notifying providers of a new request (the Studio column polls; push/e-mail are the notifications stories); a
  `quote.requested` event; photos on a request; the customer's list of requests (`/account/orders` is S-58).

## 2026-10-01 — S-134 Region-neutral platform: provinces and markets as configuration

User direction: "this is not just built for alberta, we just want to start for alberta". Every place fact now comes
from one region model; Alberta and Calgary are its first *configured* live region. Runbook: docs/runbooks/regions.md.

- **One model, extended from S-47 (no parallel model).** `region.api.Regions` (new) and `region.api.Markets` (S-44/S-47,
  kept) are both implemented by `region.application.RegionCatalogue` (replaces `region.config.MarketSettings`): the
  `region.regions` rows read through `MarketStore.profiles()`, cached `REGION_CACHE_TTL` (60 s) per instance with
  `Regions.refresh()` for the console (phase 3), overlaid with configuration. `ProvinceProfile` = code, names (en, fr,
  and the French forms "en Alberta"/"de l'Alberta"), time zones (first = default), launch status
  (`region.api.LaunchStatus`), privacy law (`region.api.PrivacyLaw`: pipeda · ab_pipa · bc_pipa · qc_law25), registry
  adapter keys, statutory holiday codes, tax bps. `MarketProfile` = city, province, zone (its own or its province's),
  centre, status, registry adapter keys. Service zones stay in `availability.service_zones`, now keyed to a market.
- **Where a business is:** `region.api.MerchantPlaces` (declared in region so booking and payments — which cannot depend
  on merchants: merchants → payments → booking — can ask; merchants implements it from `merchants.merchants.province`
  /`city`). Province = the business's own, else `REGION_DEFAULT_PROVINCE`; zone = its market's, else its province's,
  else the platform zone. `MerchantDirectory.MerchantProfile` gained `city`.
- **Configuration overrides:** `REGION_PROVINCES` (`CODE[=Zone/Id],…`: served whatever the row says; a zone given
  replaces the row's), `REGION_DEFAULT_PROVINCE`, `REGION_PLATFORM_ZONE`, `REGION_CACHE_TTL`; S-44's `SEARCH_MARKETS` /
  `SEARCH_DEFAULT_MARKET` are read as their fallbacks so existing deployments keep working. **Default change:** served
  provinces were `AB,BC,ON,QC` by configuration; they are now the live rows (V131: Alberta) plus `REGION_PROVINCES`
  (empty by default) — "we start in Alberta". The test profile keeps the four (`application-test.yml`). The default
  province no longer has to be listed in the served set (it must be a two-letter code; an unknown one is logged).
  Search reads served markets and zones from `Markets` (`SearchSettings` delegates; `SEARCH_MARKETS` parsing removed).
- **Schema (V130–V139, a new range "region platform" in IMPLEMENTATION_PLAN):**
  - V130 `region.regions` + `time_zones text[]`, `holidays text[]`, `privacy_law`, `registries text[]`; every province's
    zones, holidays, law and French forms (`name_i18n.fr_in/fr_of`); `region.tax_profiles` for all 13 (rates in force
    since 2025-04-01, the same as S-21's `CanadianTax`; a test keeps them equal) linked from the province rows. Zones per
    province: BC lists Mountain-time areas, ON the north-west, NL Labrador, NU three zones, etc.
  - V131 launch data: Alberta `live`; the Calgary, Edmonton, Airdrie markets (`ON CONFLICT DO NOTHING`, the dev seed
    V119's ids); Calgary's `calgary_business_licences` registry; `availability.service_zones` + `market_id`, `sort`,
    `default_on` with V114's nine zones assigned to Calgary (the five design defaults `default_on`). V117 had kept
    markets out of migrations; launch markets are data every environment needs (delivery, fallback market, service
    zones), so they are inserted here — as data, not code.
  - V132 `merchants.merchants.province` CHECK widened from (AB, BC, ON, QC) to any two-letter code; the api checks the
    province against the region model (it must be open: live, pilot or waitlist).
- **Holidays:** `region.domain.HolidayRule` computes each Canadian statutory holiday (Family Day, Louis Riel Day,
  Islander Day, Nova Scotia's February Heritage Day, Good Friday, Victoria Day, National Patriots' Day,
  Saint-Jean-Baptiste, National Indigenous Peoples Day, Canada Day, Nunavut Day, Civic Holiday, BC/Saskatchewan/New
  Brunswick Day, Discovery Day, Labour Day, National Day for Truth and Reconciliation, Thanksgiving, Remembrance Day,
  Christmas, Boxing Day…) with en/fr names; which a province observes is V130 data (general/statutory holidays per the
  provinces' employment standards; Alberta keeps the design's list incl. the optional Heritage Day and Boxing Day).
  Observed-day shifts (Sunday → Monday) are not modelled (as before). `AlbertaHolidays` is gone; the time-off view sends
  each holiday's name (en, fr) and the province's name. Message: "This day is not a statutory holiday in {province}."
  (server English like the other api messages; the Studio shows the province in its own copy).
- **Time zones:** a business's hours, slots, same-day cut-off, time off, holidays, calendar all-day events (the gateway
  now gets the zone), booking/order/dashboard "today", sales reports, earnings weeks/months (`FinanceReadModels` takes
  the zone as a parameter), payouts (`PayoutSchedule.nextAfter(…, zone)`; the scheduled run checks each business's own
  9:00), compliance quarter, registry expiry days, kitchen "today" and onboarding expiry dates use the business's zone
  (`MerchantPlaces`, payments' `BusinessTime`). A market's delivery runs, checkout options, order tracking and the
  shop's run days use the market's zone (`DeliveryRuns.zone`). Platform-wide work uses `REGION_PLATFORM_ZONE`: the
  nightly `@Scheduled` jobs (`zone = "${northline.region.platform-zone}"`), support SLA hours, account "member since"
  (api and auth), Stripe Tax reporting quarters (Northline reports them), the fake Stripe payout arrival, team-invite
  SMS. Worker quiet hours: the business's zone, read from the region rows in `JdbcRecipients` (the worker has no region
  module; same rule: market → province → default province → platform zone). Emails: `EMAIL_TIME_ZONE` (default the
  platform zone); `Notice.Texts` (SMS/push) use the recipient's quiet-hours zone.
- **Tax:** quotes use the business's own province (else the default province), not `"AB"`; `TaxRates` rounds half-up
  (QC 14.975 % = 1498 bps) and falls back to GST 5 % for a province without a profile (instead of a code map).
  `TaxTransactions` without a calculation or a merchant province uses the configured default province, else logs and
  skips the sale (no hard-coded launch market).
- **Delivery markets** = the region's live markets plus `northline.orders.delivery.markets` (now "extra markets", empty
  by default; the test profile lists its fictional cities). Shop and checkout without `?market=` use the region's
  fallback market (S-47 `FallbackMarket`); a blank `market=` is still 422 "Choose a city.".
- **Service zones** (Booking rules) = the business's market's `availability.service_zones`; the defaults are its
  `default_on` zones. `BookingRules.ZONES` is gone; `BookingRules.offeredIn(zones, …)` keeps the "every problem at once"
  422. Limit: `service_zones.name` is still the primary key (V114), so zone names must be unique across markets
  (follow-up when two markets need the same name).
- **Registries:** `RegistryPlan` takes `RegistryRoutes` (provincial adapter, municipal adapter, licence names it
  answers) built from the business's province's and city market's `registries` keys; none → an agent (`manual`) for
  provincial records and no municipal lookup. No more "no city counts as Calgary". Municipal licence names are
  configuration (`REGISTRY_CALGARY_LICENCES`, `Licensed` wrapper over any provider). `BusinessDetails
  .registryJurisdiction` = the business's province (CA for federal, the home jurisdiction for extra-provincial).
  The business's city = the first region market city its addresses name (`Cities` no longer has a list).
- **Onboarding provinces:** `merchants.domain.Province` lists all 13 codes; which may be picked is the region model's
  status (live, pilot, waitlist — a closed province is 422 "Northline isn't open in {province} yet."). The Studio lists
  live and pilot provinces (a brand-new account also waitlisted ones, 07d) from `GET /api/v1/geo/regions`.
- **Legal entity:** the email footer's mailing address has no default in code any more (`EmailProperties`,
  `EmailAutoConfiguration` refuses a blank one); `application.yml` keeps `EMAIL_MAILING_ADDRESS`'s default. The
  consumer footer's company line is `NL_LEGAL_ENTITY` (configuration; fallback "Northline Marketplace Inc.").
- **Web:** `@northline/ui` has no `TIME_ZONE`: `configurePlatformTimeZone` (from `GET /api/v1/geo/regions`; Studio:
  `VITE_NL_PLATFORM_TIME_ZONE` until then), `setTimeZone` (the Studio sets the merchant's market zone in the
  `/b/$merchantId` layout and the application's province zone in onboarding), `timeZone()` / `platformTimeZone()`,
  `formatDate(…, zone)`. The consumer passes the market's zone explicitly (`useZone`, `MarketZone`) — never module
  state, which SSR shares between requests. Ambient message values (`MessageValues`) fill `{province}`,
  `{provinceIn}`, `{provinceOf}`, `{city}`, `{privacyLaw}`; a simple `{name}` nobody filled reads empty instead of
  throwing (before the model loads).
- **Copy parameterised (design-faithful; place names only — record of each):** Studio availability "Statutory holidays ·
  {province}"; appointments "…under the {province} Consumer Protection Act" (fr "…Act {provinceOf}"); catalogue tax
  "{province} · marketplace-facilitator remitted by Northline", "Category allowed {provinceIn}"; compliance tax rows
  "{name} · GST 5%" per jurisdiction (the BC row lost "pilot": a status isn't part of a tax label), "{province} ·
  regulated automotive", "WCB {province} clearance", "Privacy acknowledgement ({privacyLaw})", obligations "{province}
  Consumer Protection Act" and "({privacyLaw})", the business's province from the region names; onboarding intros,
  structures' laws ("{province} Partnership Act", "{province} Business Corporations Act", "{province} BCA Part 21",
  "{province} Cooperatives Act", "{province} Societies Act"), "Corporation ({province})", "{province} corporation",
  why-texts ("…registered {provinceIn}", "…operating {provinceIn}", "{province} requires a registered trade name"),
  field labels/placeholders ("{province} corporate access #", "{province} Registries", "e.g. 2201456 {province} Ltd.",
  "Attorney for service {provinceIn}", "e.g. {city} + 40 km"), checklist ("{province} corporate registry lookup",
  "…+ {city} business licence", "Required for automotive services {provinceIn}.", "{province} Health Services permit",
  "{province} Food Safety Basics"); French uses the region's forms ("des services de santé {provinceOf}", "exerçant
  {provinceIn}"). Province option labels "{name} (pilot)/(waitlist)" and home-jurisdiction names come from the model.
  Consumer: "Join Northline · {province}" and "Stored in ca-central-1 under {privacyLaw}." (the visitor's province,
  else the default province; "PIPEDA and provincial privacy law" until known); the booking "Areas" placeholder became
  "e.g. two or three neighbourhoods" (a neighbourhood list was Calgary's). Legal pages (design 09/10, verbatim) and the
  legal-details schema's keys (`alberta_corporate_access_number`, structure `corp_ab`) are spec and unchanged.
- **Lint (fails the build):** server — Checkstyle `IllegalTokenText` id `RegionLiteral` on string literals and text
  blocks in every module (province and territory names, the launch cities, Canadian `America/…` zones, a bare
  province code); allowed: `src/test`, `src/tools`, `HolidayRule` (the model's own data), the province-/city-specific
  registry adapters (`OpenCorporatesAlbertaRegistry`, `CalgaryBusinessLicences`, `RegistriesConfig`). Chosen over an
  ArchUnit constant-pool test so the same rule covers api, worker, auth, bff, email and platform without a test per
  module; it also catches annotation values (`@Scheduled(zone = …)`, `@DefaultValue`). Web — `packages/ui/src/
  regionLiterals.test.ts` scans every app's and package's `src/` (tests, stories, fixtures skipped) with an explicit
  allowlist: Studio compliance "French required when serving Québec" (a fact about that province's language law) and
  the legal-details schema's home-jurisdiction codes. Comments are not checked.
- **Tests:** `SecondProvinceTest` opens Saskatchewan with region rows alone (no code, the console's future writes) and
  runs a provider and a kitchen there end to end: holidays (Saskatchewan Day, no Boxing Day) and the message naming
  the province, the Studio header's place, hours and slots in America/Regina, the market's service zones, quote tax
  11 %, pooled runs at Saskatoon local times, onboarding a kitchen in Saskatoon (registry → agent, no municipal lookup),
  and a closed province refused by name. `RegionCatalogueTest` shows a second province by configuration
  (`REGION_PROVINCES`) on fictional codes; `RegionsApiTest` the endpoint, Alberta's configuration and the tax-rate
  agreement; `HolidayRuleTest` the dates. Existing Alberta tests stay green (test merchants have no province → the
  default province; Calgary is a V131 market). Web: place-parameter tests (BC, QC in French), the lint, and the test
  helpers answer `/api/v1/geo/regions` with the launch configuration (`src/test/regions.ts`).
- **Also fixed:** `AvailabilityJdbc.lastSaved` threw for a business that had saved nothing (a null single row);
  Booking rules for a new business now load.
- **Not done / follow-ups:** the console screens to edit provinces, markets, zones and holidays (phase 3;
  `Regions.refresh()` is ready); `CanadianTax` (S-21's Stripe Tax fake and labels) still holds the rates beside
  `region.tax_profiles` (kept equal by a test) — one source would mean payments reading region at calculation time;
  observed-day holiday shifts; per-market zones on the consumer's provider list come from the visitor's market, not each
  provider's; service-zone names unique across markets; email dates use the configured email zone rather than each
  recipient business's; the registry adapter configuration keys keep their province/city names (`northline.registries
  .alberta`, `.calgary`) — they configure those adapters.

## 2026-09-30 — S-129 AI platform: LlmClient port, OpenRouter, fake, budgets, metrics and evals

Follows billionairedev24/samop-inv-ship-26 (`ai/LlmClient`, `adapter/openrouter`, `adapter/fake`, `DefaultAiService`,
`ObservabilityProxies`, `MockOpenRouter`, `AiEval`/`SimulatedModel`/`AiEvalLiveTest`), translated to Northline's
conventions, under the conditions of "AI provider and data residency" above.

- **Module `ca.northline.ai`, a platform with no business dependency.** `ai.api` holds the port (`LlmClient`,
  OpenAI chat-completions shape with tool calling, `complete` / `stream`, `configured()`, `model()`), `AiCompletions`
  (what features call), the tool SPI `AssistantTool`, `Prompts`, `AiFeature` and the errors. It depends only on
  `shared` (security). Features live in the module that owns their data and depend on `ai.api` alone (acceptance:
  "every AI feature depends only on the port"); the other direction would make cycles (payments → ai → merchants →
  payments). Tools are contributed the same way `NavBadgeContributor` badges are: beans in each module, over that
  module's own use cases.
- **Spring AI: not inside the adapter.** Spring AI 2.0 (GA 2026-06-12) supports Boot 4.0/4.1, and its OpenAI model now
  wraps the OpenAI Java SDK. The OpenRouter adapter stays a thin RestClient over `POST /chat/completions`, as in samop:
  OpenRouter's `usage.cost` and `provider` routing fields (`data_collection`, `zdr`) are first-class here, the tool loop
  must stay ours (tools run as the caller with our permission checks, writes stop for confirmation; Spring AI executes
  tools itself unless told not to), and streaming needs no reactive stack. Both sit behind the same port, so switching
  later is one adapter. MCP (S-127) uses Spring AI's MCP server separately; the version catalog's single `spring-ai`
  entry (2.0.0, added by S-127) is the one to use if the adapter ever moves to Spring AI's OpenAI model. `@HttpExchange` (code standards) is not
  used: a streamed body can't be returned through the proxy; one `RestClient` serves both calls.
- **Provider selection** `northline.ai.provider` = `fake` (default) | `openrouter`; staging/prod refuse `fake` at
  start-up (`AiConfiguration`, same pattern as tax/calendar/POS) and require `AI_PROVIDER`. `OPENROUTER_API_KEY` is
  **not** required: until a key exists every AI call answers 503 `ai_unavailable` (the pending item of the residency
  decision); `GET /api/v1/ai/status` lets the apps hide AI actions.
- **Models (researched 2026-09-30, OpenRouter prices):** standard tier `google/gemini-3.7-flash` ($0.75 / $3.75 per M
  in/out), light tier `google/gemini-3.5-flash-lite` ($0.30 / $2.50). Each `AiFeature` has a tier; any feature can be
  overridden with `OPENROUTER_MODEL_<FEATURE>`. Cheaper than Claude Haiku 4.5 ($1 / $5) with tool calling and good
  French; to be confirmed by the live eval once a key exists.
- **Data policy on every request:** `provider: {data_collection: "deny", zdr: true}` (configurable, default on), usage
  accounting on, attribution headers `HTTP-Referer` (Studio origin) and `X-Title: Northline`. Account settings
  (logging off, ZDR guardrail) are in docs/runbooks/ai.md.
- **Redaction on the port:** the only `LlmClient` bean is `ObservedLlmClient` around the chosen adapter. Every message
  goes through `PrivacyRedactor`, which reuses the logging `Redactor` (S-112: secrets and keys, emails, card numbers
  with the last 4 kept, phones, one-time codes, postal codes to the area) and adds SINs (Luhn) and bank accounts
  (cheque format, "account …", IBAN). It then records an observation `northline.ai.completion` (span + timer; tags provider, model, feature, outcome,
  streamed) and counters `northline.ai.tokens`, `northline.ai.cost` (USD) and the per-call summary
  `northline.ai.call.cost`. The feature reaches the port through a `ScopedValue`, not a parameter. samop used a
  BeanPostProcessor; a plain wrapper in the configuration is enough here since there is one bean.
- **Budgets:** per person requests/minute (20) and tokens/day (200k), per business tokens/day (1M), UTC
  days (a cost window needs no market time zone; region-neutral). Valkey keys `nl:ai:{p:<user>}:rpm:<minute>`, `…:tok:<day>`, `nl:ai:{m:<merchant>}:tok:<day>`; memory under
  local/test (like the DPoP replay cache). Checked before a request, charged after; Valkey down = 503 (fail closed: AI
  costs money). Over budget = 429 `ai_rate_limited` with `Retry-After` and `limit`. A person is a user id; a signed-out
  visitor (S-132) will be a hashed key.
- **Errors:** `AiUnavailable` → 503 `ai_unavailable`, `AiRateLimited` → 429 `ai_rate_limited` (ProblemDetail with
  `code`, global `AiWebAdvice`). Provider 429 → 429 `limit=provider`; 402/404/5xx, timeouts and broken streams → 503.
- **Tool loop:** at most `AI_MAX_TOOL_ROUNDS` (4) tool rounds, the last round offers no tools. Tools are offered only
  to roles that hold the tool's permission and `MerchantAccess.require` runs again before every run (fresh membership,
  `acr=mfa`). Domain errors and refusals go back to the model as `{error, detail}`. A `write()` tool is never run by
  the loop: it returns a `PendingAction` for the UI to confirm (S-130). Tool results are cut at 12k characters.
- **Schema (V150, first numbered V125):** schema `ai`, table `ai.usage` — one row per request (feature, person, business, provider, model,
  prompt version, calls, tokens, cost in micro-USD, latency, tool runs, outcome). No content. `ai` added to
  `SchemaOwnershipTests`.
- **Prompts** are versioned files `ai/prompts/<name>.v<N>.md`; the highest version is served and `name@vN` is recorded.
- **Evals:** `ca.northline.ai.eval` — `LabelledSet` (JSON sets in `src/test/resources/ai-eval/`), `SimulatedModel`
  (replays each case's `mock` through the mock OpenRouter and the real adapter), `EvalReport` (pass rate,
  precision/recall per label, tokens, cost; `build/ai-eval/*.md|json`), `EvalSuites` (every feature registers its
  suite), `AiEvalLiveTest` (only with `OPENROUTER_API_KEY`; each suite's gate, 0.8 by default). S-129's own set,
  `platform.json`, checks tool choice and grounding with stub tools (10 cases, en/fr, one "no tool covers it").
- **Config everywhere (S-23/S-32 pattern):** `server/.env.example`, runbooks README/local/dev/staging/prod,
  secrets.md, infrastructure.md, Helm (`secretNames.OPENROUTER_API_KEY`, `secretEnv` false, `AI_PROVIDER: openrouter`
  in staging/prod values), Terraform `secret_env` in the AWS, Google Cloud and Azure stacks (`openrouter-api-key`),
  new runbook `docs/runbooks/ai.md`, Grafana dashboard `deploy/observability/dashboards/northline-ai.json` (S-111 may
  move it next to its own dashboards).
- **Not done / never exercised:** no call has ever reached openrouter.ai (blocked from the build sandbox; the adapter
  is tested against a stand-in built from OpenRouter's documented API); the live eval has never run; the dashboard
  JSON was not loaded into a Grafana; the Valkey budget store is tested against a Valkey container, not a managed one.
  The Privacy Policy / PIA disclosure of OpenRouter is for the SEC stories.

## 2026-10-01 — S-127 Built-in MCP server (OAuth 2.1) for merchants, partners and ops

- **Inside the api, not a separate app.** The MCP server is module `ca.northline.mcp` in `server/api`. springdoc
  3.1.1's MCP support (`springdoc-openapi-starter-webmvc-mcp`) turns selected operations into tools, and Spring AI
  2.0.0's `spring-ai-starter-mcp-server-webmvc` serves them over Streamable HTTP on `/mcp` (SYNC, protocol
  2025-11-25). This is the pattern used in the samop reference. A tool call is an HTTP request back to the same api
  with the caller's own token and a per-process agent header. Tools are therefore exactly the Studio operations,
  with their merchant binding (S-30), roles, validation, idempotency and audit, and no business logic is duplicated.
  A separate app would need its own deploy, token exchange and network path for no gain at this size.
- **The tool list is an allowlist** (`AgentTools.ALL`, 33 tools: listings, orders and fulfilment, kitchen board,
  appointments, availability, messages, earnings/payouts read-only, reviews, two staff tools). Every other api
  operation is excluded from the MCP model by the `McpToolCustomizer`. **Refunds, payouts, checkout/payments, team,
  security and deletes are not tools.** `kitchen_hand_off` stays in: it is a fulfilment step that releases that order's
  escrow as it does in the Studio.
- **Scopes:** `mcp` (reads + resources), `mcp.write` (writes), `mcp.ops` (staff tools, also needs the `staff` role and
  `acr=mfa`), each next to `merchant`. Partners (S-30) map `api.read`/`api.write` onto the same split. springdoc's
  built-in guardrails (`require-approval`) are off; `McpGatewayFilter` enforces the policy in one place, before the
  MCP server: audience, second factor, scopes, rate limits, confirmations and audit.
- **Confirmation is two calls with identical arguments.** The first answers `confirmation_required` and changes
  nothing; the second, within 5 min, runs. The same change within 10 min after that is `already_done`. The change is
  marked done when it is confirmed, not when it succeeds: a confirmed call that fails is retried by changing an
  argument or after the window. That is the safer side for a client that retries blindly. MCP elicitation would be
  nicer, but clients support it unevenly; the two-step also works in clients without it. State is in Valkey
  (`nl:mcp:*`, `MCP_STORE=redis`; memory is refused under staging/prod).
- **Authorization per MCP 2025-11-25.** Protected resource metadata (RFC 9728) is served at the path form
  `/.well-known/oauth-protected-resource/mcp` and the root form. A `401` carries `resource_metadata` and `scope`. A
  missing scope is `403 insufficient_scope`, and a missing second factor is `403 insufficient_user_authentication`
  with `acr_values="mfa"` (RFC 9470). auth accepts `resource` (RFC 8707) in the authorization and token requests,
  only for the configured MCP resources (`invalid_target` otherwise; a token request may narrow but not widen). The
  resource goes into the access token's `aud`, and the api's MCP endpoint accepts only tokens addressed to it. Access
  tokens now also carry `client_id` (RFC 9068 § 2.2), for the audit trail.
- **No token passthrough the other way:** an MCP-audience token on `/api/**` without the MCP server's internal header
  is `403 mcp_token`. An agent can't use its token for operations outside the tool list.
- **Client registration: Client ID Metadata Documents, not Dynamic Client Registration.** DCR would let anyone write
  client rows. With CIMD the client is identified by an HTTPS URL that auth fetches under SSRF rules (public addresses
  only, no redirects, 5 s, 5 KB, optional host allowlist) and registers as a public PKCE client with consent, capped
  at `mcp`/`mcp.write` (never `mcp.ops`). `auth.oauth2_registered_client.client_id` is widened to `varchar(2048)`
  (**V025**, additive). A registered client `northline-mcp` covers clients that let you type a client id. Public
  clients may now use `http` loopback-IP redirects (RFC 8252) in every environment.
- **No refresh tokens for MCP clients.** Public clients refresh only with DPoP (S-29), and MCP clients don't do DPoP.
  Access tokens live 1 h, and the auth session makes the next sign-in silent. Consent uses Spring Authorization
  Server's default page for now; a branded consent screen in the Studio's design is a follow-up.
- **acr=mfa** is required for people: `northline-mcp` and URL clients are in `mfa-required-clients`, and the gateway
  checks it again.
- **Audit:** `mcp.tool_call` / `mcp.tool_confirmation` / `mcp.tool_refused` rows in `developer.audit_log`, with the
  client id. `AuditTrail.Entry.merchantId` became `@Nullable` for staff calls that have no business.
- **New operation** `PATCH /api/v1/merchants/{id}/listings/{listingId}/price-stock` (`QuickUpdateListing`), so the
  agent can't overwrite a whole listing to change a price. Products with variants and stock on services are refused
  with clear messages (`ListingMessages.QUICK_UPDATE_*`).
- **OpenAPI model:** `springdoc.pre-loading-enabled: true` (the MCP tools register only once the OpenAPI document is
  built), and `CurrentUser`/`CurrentMember` are ignored as request parameters in the document (they are resolved
  from the token; before, they showed up as a bogus `user` query parameter). Under prod the document is still
  generated (`springdoc.api-docs.enabled: true`, S-125 had it off) but not published: S-125's
  `northline.docs.enabled: false` removes the docs chain, and the api's own chains deny `/v3/api-docs`.
- **Edge:** `apps.api.mcp: true` routes `/mcp` and `/.well-known/oauth-protected-resource` on the api host. No new
  secret: the agent header is random per process.
- **Never run against real clients:** the OAuth + MCP session is tested with the MCP Java SDK client and tokens minted
  in the test, and auth's flow in MockMvc (WireMock for metadata documents). It has not been tried with Claude or the
  MCP Inspector against a deployed environment.

## 2026-10-01 — S-128 Developer docs MCP server

- **A second MCP server in the same app, at `/mcp/docs`**, not more tools on S-127's `/mcp`. It has its own resource
  URI (`MCP_DOCS_RESOURCE`, already accepted by auth since S-127), metadata (`/.well-known/oauth-protected-resource/mcp/docs`)
  and access rule. A merchant's agent shouldn't see internal runbooks, and a coding agent shouldn't need a business
  sign-in. It is built directly with the MCP Java SDK (stateless Streamable HTTP servlet,
  `HttpServletStatelessServerTransport`). Spring AI's auto-configuration serves one server, and a read-only docs
  server needs no session.
- **Content is packaged at build time.** `processResources` copies `docs/**/*.md` (not the backlog) and
  `docs/api/openapi/*.yaml` into `northline-devdocs/` on the api's classpath (about 2 MB). The image therefore serves
  the docs of the code it runs, with no repository checkout or network at runtime. S-125's committed OpenAPI
  documents are the source; the springdoc runtime model is not used (it isn't published in prod, and the committed
  specs are what the docs site shows). The YAML is parsed with SnakeYAML (Boot's) into Jackson 3 trees.
- **Tools:** `search_docs` (every word must match; ranked by occurrences, with headings ×3 and titles ×2 — plain and
  predictable, no index or embeddings), `list_documents`, `get_document` (by section, or in 24 000-character pages),
  `list_operations`, `get_operation` (schemas with `$ref`s inlined to depth 6; recursive ones keep their `$ref`). All
  are annotated read-only. Each document and spec is also an MCP resource.
- **Access:** `open` locally (no auth, as the story asks); `staff` in the cloud. Internal-only in prod means
  Northline staff with a second factor and a token for the docs resource, refused for anyone else with RFC 9728/9470
  challenges. `MCP_DOCS_ACCESS=open` is refused under staging/prod. Docs tokens are confined: `/api/**` answers
  `403 mcp_token`, and `/mcp` rejects them by audience.
- **Never run against real clients:** tested with the MCP Java SDK client in the api's tests; not tried with Claude
  Code or an IDE against a deployed environment.

## 2026-10-01 — S-111 OpenTelemetry tracing and metrics across api, auth, bff, worker

- **One stack, every app.** `spring-boot-starter-opentelemetry` (Micrometer Observation → OpenTelemetry SDK, OTLP/HTTP)
  in api, auth, bff (both BFFs) and worker; the worker keeps its Prometheus endpoint (S-26), the api its own. Shared
  wiring lives in `server/platform` (`ca.northline.platform.observability`): an `EnvironmentPostProcessor` adds
  `classpath:northline/observability-defaults.yml` with the **lowest** precedence (export behind
  `OTEL_EXPORT_ENABLED`, W3C only, Kafka template + listener observations, histograms, `service.namespace=northline`,
  `base-time-unit: seconds`, Spring Security's filter-chain spans off, JDBC settings), a `Sampler`, a
  `ContextPropagatingTaskDecorator` and an `ObservationPredicate` that skips `/actuator/**`. Defaults in a library
  instead of four `application.yml` edits keep the apps uniform and the change additive; any app file or variable
  still wins. The api's `application-cloud.yml` export switches moved there.
- **Vendor-neutral by construction:** the apps speak only OTLP to `OTEL_EXPORTER_OTLP_ENDPOINT` (Spring Boot 4.1 maps
  the standard `OTEL_*` variables). An **OpenTelemetry Collector in the Helm chart** (`templates/otel-collector.yaml`,
  contrib image pinned by digest) is the only component that knows the backend; exporters and pipelines are values
  (`observability.collector.exporters/pipelines`), set per cloud overlay: AWS X-Ray + CloudWatch EMF + CloudWatch Logs,
  Google Cloud Trace + Managed Prometheus + Cloud Logging, Azure Monitor; any OTLP backend via
  `test-values/observability-otlp.yaml` (Grafana Cloud shown). In the chart rather than an Argo CD add-on: it belongs
  to the environment's release (its ServiceAccount, NetworkPolicy, ExternalSecret), it is namespaced, and the
  AppProject already allows every kind it needs. The ADOT image works as a drop-in (`observability.collector.image`).
- **Sampling = `ConsistentSampling`:** trace-id ratio for spans with a remote parent too (parent-based only for local
  parents), so a browser's `-01` can't force sampling at the public edge, and BFF/api/worker reach the same decision
  for the same trace. One ratio for all apps (`observability.tracesSampleRatio` → `OTEL_TRACES_SAMPLER_ARG`): dev 1.0,
  staging 0.5, prod 0.1. Ratio 1.0 maps to always-on (OpenTelemetry's ratio sampler drops ids at exactly
  `Long.MAX_VALUE`). No tail sampling by default; documented as an extra processor.
- **Browser → BFF:** `@northline/client`'s `http()` sends a fresh `traceparent` on same-origin calls only (another
  origin's CORS may refuse the header). The browser exports **no** spans: that would need a public OTLP ingestion
  endpoint (abuse, cost) and the OpenTelemetry web SDK in both apps; the BFF's server span is the first stored one.
  Envoy Gateway tracing (an EnvoyProxy `telemetry.tracing` to the Collector) can add the edge hop later.
- **DB spans:** `net.ttddyy.observation:datasource-micrometer-spring-boot` 2.3.0 (built for Boot 4.1.1) in api, auth,
  worker; `jdbc.includes: [query]` (no connection/fetch spans), parameter values never recorded; the Collector deletes
  `jdbc.params*` anyway. **Kafka:** template and listener observations carry `traceparent` next to S-26's `nl-event-*`
  headers (auth's `user.registered` now gets one too).
- **Business metrics** (no ids in labels; counted after commit): `northline.auth.sign_ins{method,mfa,outcome}` and
  `northline.auth.lockouts{action}` in `JdbcSignInLog`; `northline.checkouts{ref_type,status}` when a checkout opens its
  escrow PaymentIntent (`CheckoutPaymentService` — the one call every checkout makes); `northline.payouts{outcome,kind}`
  and `northline.payouts.amount` (CAD dollars) from `PayoutSent`/`PayoutFailed` via a plain `@EventListener` (no
  outbox row per event); KDS latency from the ticket's own timestamps (`KitchenMetrics`, called by
  `KitchenLiveService`): promised minutes, accepted → ready (`late`), ready → handed off (`mode`). Consumer lag and DLQ
  counts were already there (Kafka client metrics, S-26's counters). Not measured: order placed → accepted (the
  ticket has no placed time; the live board's row does).
- **Dashboards as code:** a Python generator (`deploy/observability/grafana/dashboards.py`, `--check` for drift) writes
  10 Grafana JSON dashboards (overview, one per service incl. consumer-bff, sign-in, checkout and payouts, kitchens,
  events), PromQL on a `datasource` variable, viewer's time zone. **Alerts:** Prometheus rules with `promtool` unit
  tests (`deploy/observability/prometheus`), run through Docker by `scripts/observability.sh check`.
- **Local:** compose profile `observability` = the same Collector processors (`collector-local.yaml`) in front of
  `grafana/otel-lgtm:0.34.0`, Grafana on **3300** (3000 is the consumer app). `make up OBS=1` starts it and exports the
  `OTEL_*` variables to every app; `make obs-*` targets wrap `scripts/observability.sh` (callable without make).
- **Terraform:** workload identity `otel-collector` in the three stacks; AWS role gets `AWSXrayWriteOnlyAccess` +
  `CloudWatchAgentServerPolicy`; GCP gets `cloudtrace.agent`, `monitoring.metricWriter`, `logging.logWriter` and the
  three APIs; secrets `otel-backend-auth` (all clouds) and `applicationinsights-connection-string` (Azure) created empty.
- **Tests:** `OtlpReceiver` (platform test fixture, `java-test-fixtures`) decodes OTLP/protobuf
  (`io.opentelemetry.proto:opentelemetry-proto` 1.10.0-alpha, tests only). api `TracingTest` (caller's trace → SQL
  spans without values → Kafka `traceparent` + PRODUCER span; metrics exported; probes untraced), bff `BffTracingTest`
  (browser → SERVER → CLIENT → the api receives the same trace), worker `WorkerTracingTest` (the Kafka hop: CONSUMER
  span continues the producer's), auth `AuthTelemetryTest`, unit tests for sampling, defaults, payment and kitchen
  metrics, client `traceparent`.
- **Never run against the real services:** no backend account exists. The exporters' configurations pass
  `otelcol-contrib validate` (0.161.0) for AWS, Google Cloud and Azure, but no span has reached X-Ray, Cloud Trace,
  Application Insights or Grafana Cloud. The Terraform additions are `fmt`-checked, not applied.

## 2026-10-01 — S-112 Centralised logging with PII redaction

- **One redaction layer for every app:** `ca.northline.platform.logging.Redactor` (platform library, so api, auth,
  both BFFs and the worker share it). Two layers, as in the user's other services: a field with a sensitive *name*
  is masked whole; every other string is scanned for secrets (PEM keys, Authorization/Cookie echoes, bearer/basic/DPoP,
  JWTs, `sk_`/`rk_`/`whsec_`/`sk-…`/AWS/GitHub keys, `key=value` pairs), then emails, Luhn-valid card-like numbers
  (last four kept), North American and E.164 phone numbers, one-time codes after "verification / sign-in / security /
  backup / OTP code" (en/fr), Canadian postal codes (forward sortation area kept). Numbers glued to letters or `_`
  (ULIDs, Stripe ids, trace ids) are never touched; dates, amounts and the SMS adapters' masked numbers stay.
- **Format:** Spring Boot's structured logging, **ECS** by default under dev/staging/prod (`LOG_FORMAT` = `ecs` |
  `logstash` | `gelf` | `text`; anything else stops start-up), plain text under local/test where people read the
  console. The redaction is a `StructuredLoggingJsonMembersCustomizer` value processor over every string member
  (message, MDC, key-values, `error.message`, `error.stack_trace`). `traceId`/`spanId` are renamed `trace.id`/`span.id`
  (ECS names; top-level dotted keys — Boot's rename keeps them flat). Set by `LoggingDefaults` (an
  `EnvironmentPostProcessor`, lowest precedence) — no app yml changed.
- **Shipping through the Collector = OTLP, from the app:** `OtlpLogAppender` (Northline's own Logback appender over the
  OpenTelemetry logs bridge) is attached to the root logger when `management.logging.export.enabled` (i.e.
  `OTEL_EXPORT_ENABLED=true`); records carry the current trace context, logger, thread, MDC and exception, all
  redacted. Not the OpenTelemetry Logback instrumentation (it sends the raw message) and not a node-level
  `filelog` DaemonSet (cluster-wide hostPath access, a second add-on, and logs that bypass the in-app redaction's
  trace linkage). The console keeps the same redacted JSON for `kubectl logs` and cloud node agents.
- **Collector, second line:** `transform/redact` (OTTL `replace_pattern` / `replace_all_patterns`) on log bodies and
  log and span attributes, chart and local config generated from one list (`northline.redactPatterns`); RE2 has no
  look-behind or Luhn, so it is coarser. Run against a sample record with otelcol-contrib 0.161.0.
- **The S-20 local SMS case:** `LoggingSmsSender` (auth) and `LoggingSmsTransport` (shared library: api invitations,
  worker notifications) write the code / text **only under the `local` and `test` profiles**; elsewhere they log that it
  was withheld, and start-up warns. Consequence: a `dev` environment that keeps `SMS_PROVIDER=local` can no longer
  complete phone verification — dev needs Twilio or AWS for sign-ups (documented in README § SMS, logging.md). No
  escape hatch on purpose (the story: "make sure it can't happen outside local").
- **Tests:** `RedactorTest` (28 cases incl. the S-20 log line, the SMS text in English and French, and what must stay),
  `StructuredLogsTest` (a `dev` start logs redacted ECS JSON incl. MDC and exception; `local` stays text; `LOG_FORMAT`
  overrides), `OtlpLogAppenderTest`; `RedactionCheck` (platform test fixture) on **every app** — api `TracingTest`,
  auth `AuthTelemetryTest`, consumer-bff `BffTracingTest`, worker `WorkerTracingTest`: a PII-laden line logged in a
  span comes out redacted on the console (ECS) and over OTLP, the OTLP record linked to the span's trace;
  `SmsConfigTest` / `SmsTransportsTest` prove the stand-ins withhold under `dev`.
- **Not done:** log retention and deletion are the backend's (documented per backend, not automated); no log-based
  alerts (the metrics alerts of S-111 cover the same failures). **Never run against a real backend** (CloudWatch Logs,
  Cloud Logging, Azure Monitor, Loki/Grafana Cloud).

## 2026-10-01 — S-48 Search results with predictions and filters

Built on the S-44 contract (docs/CONSUMER_WEB_PLAN.md § Search) and S-47's location.

- **`/search` is server-rendered, the same HTML for everyone.** The URL carries the text, scope, sort and filters
  (`q`, `scope`, `category`, `sort`, `tier`, `maxPrice`, `delivery`, `openNow`, `instantBook`, `dietary`,
  `allergenFree`, `radiusKm` — the API's own names, so a shared link reproduces the search; anything malformed is
  dropped). The loader fetches the first page with no place (the API's default province); once the browser knows the
  location it asks again with `market` = the location's province and `lat`/`lng` (results stay on screen, dimmed,
  while it does). A failing first page doesn't fail the route: the page shows its own error state with Retry.
- **The design's filter list** ("On tonight's run", "Under $10", "Master sellers", "Halal", "Gluten-free") is what the
  header search lands on (`scope` all) and the Shop scope. **"Organic" is not offered**: the index has no organic data
  (S-44). The services scope shows the providers screen's chips (Master tier, Instant book, Available today = open now,
  Under $80), the food scope the food screen's (Open now, Halal, Vegan, Nut-free = peanuts + tree nuts). Additions
  where the design is silent (recorded): a "Show" group (Everything / Services / Shop / Food, with counts from the
  kinds facet when searching everything; changing scope drops the other scope's filters), a "Categories" group from
  the categories facet (toggles `category`), a "Distance" group (Any / under 3, 10, 25 km) only when the browser has
  coordinates, and "Clear all". The design's static "Shops" list is the merchants facet (top five, text as designed;
  labelled Providers / Kitchens / Businesses by scope — the API has no merchant filter, so they don't filter).
- **Sort** is the design's "Sorted by relevance ▾" as a native select (relevance, distance, price both ways, rating);
  distance appears only with coordinates. A URL asking for distance (sort or radius) without a location leaves it out
  of the API call (the API would 422) and says "Set your location to sort and filter by distance." with a link.
- **Cards** as designed (picture, name and price, "business · detail", one tag): detail = category, rating with count,
  distance; tag by priority sold out → on tonight's run → open now (food) → instant book (services) → Master/Trusted
  tier. "Quote" for quote-priced or unpriced listings, "from $" for businesses, "$/h" for hourly. Pictures: a catalogue
  image (`media:`) through the public media endpoint; dish photos (`object:`) have no public URL yet → the design's
  halftone placeholder in the merchant's swatch colour.
- **Where a result goes:** product → `/products/<offer id>?offer=<offer id>` (see the api change below), service →
  the provider page, dish → the kitchen page, business → provider or kitchen page; a shop has no page of its own yet
  (S-49), so it searches the Shop scope by its name. Categories from suggestions go to the services category or shop
  department page (leaf slug); others search within the category.
- **Paging:** "Show more results" with the API's `next` token (TanStack infinite query); "Showing N of M" (M shown as
  "10000+" past the API's exact count). A dead `after` (422 on `after`) shows the API's sentence; 429 → "Too many
  searches at once — try again in a minute."
- **Empty state:** "Nothing matches “{q}” here yet." with one action — "Clear filters" when filters are on, else
  "Browse services".
- **Predictions as you type** (header and home hero, design 06 `suggestions`): the UI kit's `SearchBar` gained an
  optional ARIA combobox (`suggestions` groups + `onPick`, ↑/↓/Enter/Esc, mousedown picks, highlighted ranges via
  `Highlighted`; stories `HeaderPredictions` / `HeroPredictions`). `features/search/SiteSearch` feeds it from
  `/search/suggest` (150 ms debounce, the visitor's province, the page language, previous list kept while typing) and
  "Your recent" from `localStorage['nl.recentSearches']` (last five, read after hydration; written when a search runs
  or a suggestion is opened; nothing is sent). Item meta: listings "{business} · {price}", businesses "{type} ·
  {tier} · ★ {rating}", categories their side ("Service", "Shop"…), recent "searched today/yesterday/N days ago". The
  prototype's footnote ("Elasticsearch completion suggester · fr/en synonyms…") is technical and not shown; the
  "fr → sourdough" synonym rows aren't returned by the API (S-44). On the results page the header field shows the
  searched text.
- **`robots: noindex, follow`** on `/search`: result pages are endless and query-specific; the listings themselves are
  indexed (S-63).
- **SSR and the per-client rate limit (S-44):** `server/node-server.mjs` now passes the visitor's address chain to the
  app as `x-nl-forwarded-for` (the ingress's `X-Forwarded-For` when `TRUST_PROXY=true`, then the peer; a browser's own
  header is dropped) and the server-side search sends it as `X-Forwarded-For` through the consumer-bff, so the api's
  limit counts the visitor rather than the SSR pod. The consumer-bff relays the header as is.
- **api change (catalogue, S-50's endpoint):** search results are offers, the product page takes a product. `GET
  /api/v1/public/shop/products/{id}` now also accepts one of the product's offer ids and answers the product (its own
  `productId` in the body); the consumer's product route redirects (301) such a URL to `/products/<productId>`, keeping
  the offer preselected (`?offer=`). No schema change. Test: `ProductPageApiTest.anOfferIdFromSearchNamesItsProduct`.
- **No migration, no new configuration.**
- **Tests:** `features/search/search.test.tsx` — the design's heading, filters, facets and cards in English and
  French; filters/sort/category/clear through the URL and onto the API query; scopes with their own chips; the
  location's province and coordinates, the distance group and sort; distance left out without a location; paging;
  empty, error + Retry and rate-limit states; recent searches; suggestions (listings, businesses, categories, recent
  in both languages, highlighting, keyboard pick, Escape, Enter); links per kind; malformed URL parameters. Shell and
  home tests now find the field as a combobox.
- **Not done:** Storybook interaction/a11y runs of the new stories (no browser in the sandbox; `pnpm test-storybook` in
  CI); multi-select facets (S-44 counts the whole filtered result); filtering by a merchant from the "Shops" list;
  "Organic", "Free delivery (Plus)" and "EV certified" (no data); the redirect of offer URLs is exercised by the api
  test only (no router-level test).

## 2026-10-01 — S-63 Legal pages and footer; SEO, sitemap, structured data

- **Legal pages: one copy, reused.** The Studio already served `/legal/terms.html` and `/legal/privacy.html` (design
  09/10 verbatim, generated by `scripts/legal-pages.mjs`); S-45 had generated a second copy into the consumer app.
  Both now come from a new workspace package `web/packages/legal` (`pages/terms.html`, `privacy.html`, `northline.css`)
  whose Vite plugin `legalPages()` serves `/legal/*` in development and writes the files into each app's browser build
  (Studio nginx and the consumer's Node server then serve them as static files). The generator writes there (an
  optional out dir), and the package's `node --test` regenerates the pages from the design and fails if the committed
  files differ — the verbatim rule is now checked. The documents stay English only (the design has no French
  version); the footer links carry `hreflang="en-CA"`. No React legal pages: they would be a second rendering of the
  same text.
- **Footer as designed** (company line, Privacy, Terms, language switch, Sell on Northline, Offer a service, Run a
  kitchen): the S-45 placeholder already matched; the company line is `NL_LEGAL_ENTITY` (S-134; the design's
  "Northline Marketplace Inc. · Calgary" is configuration, not code). Tests for both languages.
- **Language URLs (hreflang).** The language was cookie / Accept-Language only, so English and French shared a URL.
  `?lang=en|fr` now wins over both on the server and in the browser and is written to the cookie; English is the plain
  URL and `x-default`, French adds `lang=fr`; a page's canonical is its own language's URL. (Not done: the FR/EN toggle
  doesn't rewrite a `?lang=` already in the address bar; the next full load of that URL uses it again.)
- **`seo()` (`src/lib/seo.ts`)** builds each public route's head: title, description (whitespace collapsed, ≤ 300
  chars), canonical, hreflang en-CA / fr-CA / x-default, Open Graph (`og:locale` + alternate, `og:url`, `og:image`),
  `twitter:card`, JSON-LD scripts (`<` escaped). Applied to home, services landing, service category, provider list,
  provider page, shop landing and department (keeping `market`), product (keeping `market`), food landing (keeping
  `cuisine`) and restaurant. A business page with a live custom domain is canonical there (`https://<domain>/`). Screens
  not built yet (`pending()`) are `noindex` like the personal ones already were.
- **JSON-LD (`src/lib/structuredData.ts`):** provider `LocalBusiness` (address locality/region/country, area served,
  `OfferCatalog` of `Service` offers — fixed price, hourly as `UnitPriceSpecification` `HUR`, quotes without a price —
  and `AggregateRating` only when there are reviews); product `Product` with an `Offer` (one shop: availability,
  condition, seller) or `AggregateOffer` (low/high price, count) and a review-weighted `AggregateRating`; kitchen
  `Restaurant` (address, cuisines in the page's language, price range, delivery `OrderAction`, `Menu` → `MenuSection`
  → `MenuItem` with `Offer` and `suitableForDiet` from the dietary codes); home `Organization` (legal name =
  `NL_LEGAL_ENTITY`) and `WebSite` with a `SearchAction`. Prices in CAD as decimals.
- **Sitemap data (api, module `discovery`):** `GET /api/v1/public/sitemap` → `{pageSize: 5000, sections: [{name,
  count, pages}]}`, `GET /api/v1/public/sitemap/{section}?page=n` → `{items: [{key, customDomain, updatedAt}]}` (404
  unknown section, 422 "Choose a page between 1 and 50000.", `Cache-Control: public, max-age=3600`). Sections come from
  a new shared contract `ca.northline.shared.PublicPages` (like `NavBadgeContributor`) implemented by the owning
  modules on their own schema: merchants `providers` (active provider/both with a published page; the live custom
  domain) and `kitchens`; catalogue `products` (shop products with a live approved offer, not banned — what the product
  page shows), `departments` (shop leaf categories with such a product) and `services` (service leaf categories with a
  live approved service). Offset pages ordered by key (stable; sitemaps need random access). No migration.
- **robots.txt and sitemaps (consumer server, `server/seo.mjs`):** per host (`page-hosts.mjs` gained `hostKind`). Site:
  disallow personal/transactional/endless paths, `Sitemap:` the index; `/sitemap.xml` = index of
  `/sitemaps/pages.xml` (landing pages with alternates, legal documents without) and `/sitemaps/<section>-<n>.xml`
  (lastmod when known, hreflang alternates). A merchant's live domain: its own robots.txt and a one-URL sitemap, and
  its page is left out of the site's sitemap (cross-host sitemap entries would need both hosts verified).
  `pages.<zone>`: crawlable, no sitemap (its pages canonicalise elsewhere). Unknown domain 404, api down 503. The api's
  answers are cached ten minutes in the server; responses an hour. The dev server serves the same (site host).
- **`/search`** is S-48's (it sets `noindex, follow` there); robots.txt disallows `/search` here.
- **Tests:** api `SitemapApiTest` (sections, counts/pages, what's listed and what isn't — drafts, unpublished, inactive,
  banned, another type —, the custom domain, 404/422, cache header); web `src/lib/seo.test.ts` (tags, French
  canonical, own domain, noindex, script escaping, `?lang=`; every JSON-LD builder), `src/lib/routeHeads.test.ts` (the
  heads of the provider page — on the site and on its own domain in French —, product, restaurant, department, home and
  a pending screen, from loader data), `src/lib/sitemap.test.ts` (robots per host, index, sections, alternates, custom
  domain exclusion and host sitemap, 404/503, cache, XML escaping), footer en/fr; `@northline/legal` verbatim test.
- **Not done:** the legal documents in French (no French source in the design); `Vary` headers / an edge cache for
  cookie-negotiated pages; image sitemaps; listing every kitchen's dishes or services as their own URLs (they have no
  pages); validation against Google's Rich Results tool (never run — no network to it).

## 2026-10-01 — S-61 Sell or offer a service: entry into Studio onboarding (07a–07d)

- **`/sell` is design 06's account › `sell` panel as its own page** (the route S-45 reserved; the account menu's "Sell
  or offer a service" and the footer's Sell / Offer / Run a kitchen link there). Server-rendered and indexable (title,
  description); the account line is the only part that depends on the visitor and starts as a skeleton.
- **Into the Studio, never a second onboarding:** the three cards link to the Studio's existing onboarding
  (`/onboarding?type=provider|seller|kitchen`, 07a–07c) at the new public config `studioOrigin` (`NL_STUDIO_ORIGIN`,
  the chart's `urls.studio`; default the Studio dev server :3100). **Signed in** on the consumer site → the studio-bff's
  sign-in hand-off `/bff/login?next=/onboarding?type=…` (S-20/S-62): northline-auth already holds the person's session,
  so the authorization completes at once and they arrive in onboarding with the same account ("same login, same
  passkey"; the Studio then asks for the second factor business accounts need). **A guest** → `/onboarding?type=…`
  directly; the Studio's guard sends them to its own sign-in / register and back (`next`). The design's "Not a
  customer yet? Start fresh." → Studio `/register?next=/onboarding?…&new=1` (07d, the brand-new-account path),
  keeping the chosen type. Guests also get "Already shop on Northline? Sign in first — …" to the consumer sign-in
  coming back to `/sell` (ours; the design only draws the signed-in line).
- **`?type=`** outlines the chosen card (accent ring, "Chosen" for screen readers) — the design has no chosen state;
  order and copy stay as designed. The CTAs are plain links (another origin), named "… (opens the Studio)" for screen
  readers.
- **Copy:** as designed except place-specific words (region rule, DECISIONS 2026-09-30): "trade licence (AMVIC,
  RECA…)" → "trade licence for regulated trades" (those are one province's regulators); "AHS Food Handling Permit" →
  "{province} Food Handling Permit" from the visitor's province (the S-134 ambient values; "Food Handling Permit" before
  the region model answers). The design's figures ("Take rate 15% → 9% at Master", "Median 1.4 days") are kept as
  copy. French is ours (design/i18n-fr.js has none of these lines).
- **Configuration:** `NL_STUDIO_ORIGIN` (consumer web, optional; chart from `urls.studio`) — README, local, dev,
  staging, prod runbooks, `web/apps/consumer/.env.example`, chart helper. No server change, no migration.
- **Tests:** `features/sell/sell.test.tsx` — the three cards' copy, guest links (Studio onboarding per type, consumer
  sign-in back to `/sell?type=`, Start fresh with `new=1`), signed-in links through the studio-bff hand-off and the
  "Logged in as" line, the chosen card, the province's permit (BC) and French (QC), reached from the footer and the
  account menu; the link builders.
- **Not exercised end to end:** the cross-origin hand-off was not run against a live auth + both BFFs here (the BFF's
  `next` rule and the Studio's onboarding parameters are unchanged and covered by their own tests). If northline-auth's
  session has expired while the consumer-bff's has not, the Studio's sign-in asks again — accepted.

## 2026-10-01 — S-130 Studio AI assistant: chat with tools that run as the caller, streamed, plus screen insights

Stacked on S-129 (#82, branch `ai/s-129-platform`).

- **Where it lives:**
  - The assistant is in the `studio` composition module: `StudioAssistant` / `StudioAssistantService` and
    `AssistantController`. It already composes the dashboard, and it may read `merchants.api` (business name, type,
    province) and `region.api.Markets` (the business's time zone).
  - The tools live in the modules that own the data, as `AssistantTool` beans in each `application` package, over the
    same use cases the REST controllers and the S-127 MCP server call:
    - orders: `list_orders`, `pack_order` (write);
    - booking: `list_jobs`, `start_travel` (write);
    - catalogue: `list_listings`;
    - availability: `get_availability`;
    - payments: `earnings_overview`, `payouts_overview` (read-only, `FINANCE_READ`);
    - messaging: `list_threads`;
    - trust: `reviews_summary`.
- **The prompt carries only who the caller is and what they may do** (`studio-assistant.v1`): the business name and
  type, the role, the role's permissions in words, today's date and the time zone. Every fact comes from tool results.
- **As the caller:**
  - The platform offers a tool only to roles that hold its permission, and runs `MerchantAccess.require` again before
    each run.
  - Tools receive the authorized `merchantId`, plus the caller's id and role. Technicians see only their own jobs and
    threads, as on the screens.
  - A test proves that another business's lines on a shared order never reach the model.
- **Minimum data sent:**
  - No customer names, addresses or contact details, no message bodies, no bank details. Payouts say only
    `bankAccountOnFile`.
  - Review texts are cut at 400 characters, without author names.
  - The port's redaction still applies.
- **Time zone (region-neutral):** `ToolContext` and `AssistantTool.Call` gained `zone`, the business's market zone
  (`Markets.zone(province)`). Tools show local times and "today" in it. S-129's API changed accordingly, in this
  branch.
- **Writes need explicit confirmation:**
  - The loop never runs a `write()` tool. The answer carries `pending` (`tool`, `arguments`, a `preview` in the
    person's language), and the drawer shows Confirm / Cancel.
  - Confirm calls `POST …/assistant/actions`, which re-checks the tool's permission (`OPERATE` for both writes), runs
    it, and records `assistant.action_confirmed` in the audit trail.
  - No signed token binds the confirmation to the proposal. The caller could make the same change through the REST
    endpoint with the same permission, so the confirmation is a guarantee that the *model* never acts on its own, not
    an authorization.
- **Streaming:**
  - `POST …/assistant/chat/stream` writes SSE frames straight to the response, as samop does: `tool`, `delta`, then
    `done` or `error`. Nothing is written before the first frame, so a refusal up front is an ordinary 403 / 422 / 429
    / 503.
  - **The BFF relays it unbuffered.** Gateway MVC flushes `text/event-stream` by default
    (`streaming-media-types`). `SseRelayTest` proves this on a real server: the first frame arrives while the upstream
    is still writing.
  - The JSON endpoint `POST …/assistant/chat` stays for clients without SSE.
- **Insights** (`GET …/assistant/insights/{dashboard|earnings|listings}`, prompt `studio-insight.v1`, light model, JSON
  `{title, body, bullets}`):
  - They read the screen's data through the same tools as the caller. A technician gets 403 on earnings.
  - **Fetched only when the person clicks "Explain this screen"**, then cached 15 minutes in the browser. Each one is
    a model call, so loading one on every visit would spend the budget for nothing.
  - Shown under the screen and labelled "AI-generated from this screen's data · check before acting".
- **Usage (tokens, cost, latency)** is returned to owners only (`MANAGE`). Every role's calls are metered and recorded
  in `ai.usage`.
- **UI addition the product owner asked for:** the design (`design/02`) has no AI screens.
  - The addition is kept minimal and consistent with the locked design system:
    - an "Assistant" button with a Phosphor duotone `Sparkle` in the top bar, before the account menu;
    - the kit's right `Drawer` (440 px), with the kit's `ChatLog` / `ChatBubble`, `TextArea`, `Button`, `Alert` and
      `nl-chip` suggestions;
    - an `InsightCard` `Panel` at the foot of the dashboard, earnings and listings screens.
  - Tokens only (`Assistant.css`), en + fr-CA copy, 44 px targets.
  - The drawer always shows "AI-generated. It can be wrong — check before acting. Changes always need your
    confirmation."
  - With the fake model it says "Test model: answers are canned, not real."
  - Hidden entirely when `GET /api/v1/ai/status` says AI is unavailable.
- **Evals:** `ai-eval/assistant.json` holds 17 top questions across roles (owner, technician, bookkeeper), en/fr:
  - tool choice and content, including the two writes, which must be proposed and never run;
  - a technician's earnings question, which must be refused without leaking;
  - an out-of-scope request.
  `AssistantEval` drives the real `StudioAssistantService` (prompt, role filtering, loop) with stub tools mirroring the
  real tools' names and permissions. `AssistantToolsCatalogueTest` checks the mirror against the beans in the context.
- **No schema change.** Audit action `assistant.action_confirmed` (`developer.audit_log`).
- **Not done / not verified:**
  - The drawer keeps the conversation in memory only (closed tab = gone). Nothing is stored server-side.
  - No live-model run (no OpenRouter key yet).
  - The UI was tested with Testing Library, not in a browser.
  - No Storybook story: the drawer is a Studio feature composed from kit components that already have stories.

## 2026-10-01 — S-131 AI writing help: listing copy, quote lines, message replies, review summaries (en/fr)

Stacked on S-130 (#83), which is stacked on S-129 (#82). It reuses S-130's `ScriptedModelTest` and test heap.

- **Each feature lives in the module that owns its data** and depends only on `ai.api`. Each has a versioned prompt, a
  labelled eval set and suite, and an endpoint guarded like its screen:

  | Feature | Module | Endpoint | Permission | Prompt and model |
  |---|---|---|---|---|
  | listing copy | catalogue | `POST /api/v1/merchants/{id}/listing-copy` | EDIT | `listing-copy@v1`, standard |
  | quote lines | booking | `POST …/quote-requests/{requestId}/line-suggestions` | EDIT | `quote-lines@v1`, standard |
  | reply suggestions | messaging | `POST …/threads/{threadId}/reply-suggestions` | OPERATE; technicians only for their own jobs' threads, through `BrowseInbox` | `message-reply@v1`, light |
  | review summary | trust | `POST …/reviews/summary-draft` | EDIT | `review-summary@v1`, light |

- **Always a draft the person edits; never sent, saved or published by the AI:**
  - No endpoint writes anything. Every answer carries `aiAssisted: true` and the Studio labels it:
    - "AI-assisted draft … every listing is still vetted by Northline";
    - "AI-suggested lines … set the prices and check each one";
    - "AI-suggested replies — pick one to edit it before sending";
    - "AI-assisted summary … nothing is published automatically".
  - Listing copy fills the editor's fields only when the person picks the English or the French draft. Saving and
    submitting go through the normal flow, so **vetting is unchanged**.
  - A suggested reply only fills the message box.
  - Suggested quote lines are added as editable rows **without prices**: the prompt never asks for them, and the
    response has no price field. Discount lines are dropped.
- **French drafts are not stored.** Listings have one set of text fields. `i18n.content_translations` (`source`,
  `approved`, "MT draft → approved") exists, but catalogue has no write path for it yet. Both drafts are shown and the
  person picks one. Saving the other language as a translation is a follow-up with the catalogue workstream.
- **Review summary on the provider page:** the backlog says "on the provider page". A summary published there
  automatically would contradict "never auto-published", so it is drafted on the Studio Reviews screen. The owner or
  staff copy and edit it, and can put it on their page through the storefront editor. It needs at least 3 reviews
  (409 `too_few_reviews`). It reads the 40 latest reviews: rating, job and text cut at 500 characters, **without author
  names**.
- **Minimum data per feature:**
  - Listing copy sends the editor's facts: kind, name, category name, brand, attributes, included, duration, notes.
  - Quote lines send the request's title, description and area. The customer's name is never sent.
  - Replies send the last 8 messages as `{from: customer|business|northline, text}`, without names.
  - The port's redaction masks contact details anyway, and the prompts forbid contact details and off-platform payment
    in the output.
- **Output is clamped to the domain rules**, so a draft never fails the editor's validation:
  - listings: title ≤ 80, bullets ≤ 5 × 250, description ≤ 4,000;
  - quote lines ≤ 6, description ≤ 80, quantity 0.01–999;
  - three replies of ≤ 600 characters;
  - four themes.
- **Evals:**
  - `listing-copy.json` (6 cases, including notes with a phone number and "cash discount" that must not leak);
  - `quote-lines.json` (5, including en/fr and a vague request);
  - `message-reply.json` (5, including an e-transfer request that must be answered "through Northline", a refund ask
    that must not promise one, and French);
  - `review-summary.json` (3).
  `WritingHelpEvalTest` runs them against the simulated model, and `AiEvalLiveTest` runs them live.
- **No schema change.** Usage is recorded in `ai.usage` per feature (`listing_copy`, `quote_lines`, `message_reply`,
  `review_summary`).
- **Not done:**
  - French translations are not persisted (above).
  - Nothing is audited per draft, since nothing changed; the usage row is the record.
  - No live-model run.
  - UI tested with Testing Library only.

## 2026-10-01 — S-132 Consumer AI: natural-language search and help triage

Stacked on S-131 (#85) → S-130 (#83) → S-129 (#82).

- **Natural-language search** (`search` module, `POST /api/v1/search/interpret`, prompt `search-filters@v1`, light
  model):
  - It turns the typed text into **the S-44 search API's own parameters** (`GET /api/v1/search`, which is on main):
    `q`, `kind`, `minPrice` / `maxPrice` (cents), `minRating`, `tier`, `instantBook`, `openNow`, `delivery=tonight`,
    `dietary`, `allergenFree`, `radiusKm` and `sort`, plus a one-line `explanation` in the person's language.
  - The model only proposes. `SearchInterpreter` keeps only values the search API accepts:
    - codes from its enums; dietary tags and Health Canada allergen codes from the index's list;
    - prices ≥ 0 (swapped when reversed); rating 1–5; radius 1–100;
    - `sort=distance` and `radiusKm` only when the person shared a location (`hasLocation`), as the search API requires;
    - `relevance` and `registered` are dropped as no-ops.
  - It never runs the search and never sees results. The consumer app shows the filters as removable chips and calls
    `GET /api/v1/search` itself.
  - **Public, like search:**
    - The AI budget is per visitor: the user id when signed in, else `visitor:` plus a SHA-256 of the BFF's guest id,
      else of the client address. The raw values are never stored.
    - Search's per-address rate limit counts it too (429 `rate_limited`).
  - **UI:** the consumer search results page (S-48) isn't built yet (`routes/search.tsx` is pending). The contract is
    recorded in CONSUMER_WEB_PLAN.md for S-48 to call.
- **Help triage** (`messaging` module, help cases; `POST /api/v1/me/help/triage`, signed-in customers; prompt
  `help-triage@v1`, light model):
  - "Something's wrong" text (10–2,000 characters, optional `refType` order | booking) becomes a `category`:
    `missing_item`, `wrong_item`, `damaged`, `not_as_described`, `late`, `not_delivered`, `service_not_done`,
    `service_quality`, `no_show`, `billing`, `safety`, `account` or `other`. It also returns `urgent` and a neutral
    English `summary` for staff (≤ 300 characters).
  - **The route is a rule, not the model's**: `refund_request` (goods problems), `dispute` (late, service, no-show,
    billing) or `support` (safety, account, other).
  - **Never decides refunds:** it opens nothing and names no amount. The S-60 flow shows the suggestion, and the
    customer opens the case through the existing `payments.api.CustomerCases`, where merchants or staff decide as
    today. Safety is always urgent. An unknown category from the model becomes `other`.
  - Only the report text is sent; the order or booking itself isn't read. The categories are new: no case category
    existed, and S-60 adopts them. **UI left to S-60**, as agreed.
- **Evals:**
  - `search-filters.json` (10 cases): en/fr, location-dependent sorting, invented values (`keto`, `platinum`,
    `spaceship`) that must be dropped, and a plain query that must stay unfiltered.
  - `help-triage.json` (12 cases): precision and recall per category, safety recall and precision 1.0 in CI, summaries
    that must not decide ("approved", "refunded") or carry a phone number.
- `SimulatedModel` now matches a case on its **redacted** input, which is what the model receives.
- **No schema change.** Usage is recorded as `search_filters` and `help_triage`.
- **Not done:** no consumer UI (S-48 / S-60); no live model run.

## 2026-10-01 — S-133 AI trust & safety assist: screening, weekly anomaly scan, staff queue

Stacked on S-132 (#87) → S-131 (#85) → S-130 (#83) → S-129 (#82).

- **Suggestions only, staff decide.** The model can only open `trust.flags` with an explanation, for the console
  queue. It never changes vetting, hides a listing or review, blocks a message or penalizes a business.
  - The automated vetting still decides by its own rules. AI screening of a submitted listing runs
    alongside it and does not hold the listing.
  - Deciding a flag (`dismissed` | `actioned`) records who, when and why (V151 `decided_by`, `decided_at`,
    `decision_note`; audit `trust.flag_decided`, platform-level, so it isn't listed in the business's own audit log).
    What "actioned" means is a separate staff action.
- **Screening by polling, not by events.** One job (`TrustScreeningService`, every 15 min) reads each source after its
  mark (`trust.ai_screening_marks`: time + id). Two reasons:
  - Reviews have no creation event; they are generated from completed jobs by imports and seeds.
  - When the AI is down or over budget, the mark doesn't move and nothing is skipped. An event listener would have
    needed its own retry store.
  - Each source runs under a Postgres advisory lock (one replica at a time). The AI calls for a batch (default 25)
    run inside that transaction; this is acceptable at this batch size.
- **Module boundaries:** trust needs listing texts, but catalogue already depends on `trust.api` (ratings). So the
  reader is declared in `trust.api.ListingTexts` and implemented by catalogue (`ListingTextService`). The reverse
  would be a module cycle. Messages come from the new `messaging.api.MessageTexts`, since trust already depends on
  messaging. Reviews are trust's own.
- **Minimum data** (DECISIONS "AI provider and data residency"): only the item's own words plus one context line
  (kind, stars, who wrote to whom, category, price, vetting codes). No names, ids, thread subject or other items; see
  docs/ai/trust-safety.md for the table. The anomaly scan sends letters and counts only, never names, places or text.
  `trust.ai_screenings` stores no item text, only the verdict, categories and (for flags) the explanation.
- **System caller budget:** jobs call as `Caller.system(job)` (`system:trust-screening`, `system:anomaly-scan`). They
  have no per-minute rate and their own daily tokens, `AI_BUDGET_SYSTEM_TOKENS_PER_DAY` (2M). They are not throttled
  like a person, and a runaway job cannot spend a person's or business's budget.
- **Anomaly scan:**
  - The signals are deterministic (`AnomalyRules`: review burst, rating drop, ≥ 3 off-platform flags, ≥ 3 screening
    flags in the week, against the previous 8 weeks). The model only explains them.
  - Without the model, flags still go out with the rules' own explanation. "Every flag explains itself" holds either
    way.
  - Weeks are Monday–Sunday **UTC** (a cost and reporting window, region-neutral).
  - Markets: the region market id, else the business's own province, else `unplaced` (from `MerchantPlaces`).
  - Each market and week is claimed once (`trust.anomaly_scans` unique).
- **Dedup:** a screening flag is raised unless an *open* one exists for the same target and rule. A listing that is
  resubmitted after a dismissal can be flagged again. Detector flags keep their stricter "once ever" rule.
- **Schema (V151, first numbered V126):** `trust.ai_screenings`, `trust.ai_screening_marks`, `trust.anomaly_scans`, and the decision
  columns plus an index on `trust.flags`.
- **Console API** (`/api/v1/console/trust/flags`, staff with MFA by the path rule). Every flag gets an `explanation`:
  the model's, or one derived from its rule (off-platform detector, business report). **No console web app exists
  yet**, so this is API only.
- **Evals:**
  - `trust-screen.json`: 18 cases (11 flag, 7 tempting-but-fine), precision and recall of "flag", the expected
    category, and the explanation not repeating an address.
  - `anomaly-scan.json`: 6 market weeks, 8 businesses; the rules must pick the labelled ones with the right signals,
    and explanations must cite the numbers. Both are in `EvalSuites` for the live eval.
- **Not done / never exercised:** no live model run (precision and recall are measured only against the simulated
  model); no console UI; the scheduler's cron and interval were not run in a deployed environment (tests call the use
  cases directly).

## 2026-09-30 — S-58 Account area: orders & bookings, quotes, favourites, wallet & points

Branch `web/s-58-account-activity` (not stacked).

- **New module `account`** (`ca.northline.account`, schema `account`): the consumer's own area under `/api/v1/me`,
  single-factor sessions allowed. It owns only favourites and composes everything else from the other modules' `api`
  packages, which gained small read interfaces (each implemented by that module's own JDBC, so `SchemaOwnershipTests`
  holds — `account` was added to its schema list):
  `orders.api.CustomerOrders` (shop and food orders with lines, shops, window/ETA), `booking.api.CustomerHistory`
  (bookings with the `completed` event time; quote requests without an accepted quote, with each provider's latest open
  version), `payments.api.CustomerCaseQuery` (refund cases through `escrows.customer_id`, disputes through
  `opened_by`), `trust.api.LoyaltyPoints`, `identity.api.PlusMemberships`.
- **Endpoints:** `GET /me/activity` (Orders & bookings), `GET /me/upcoming` (the S-46 "Your week" contract, texts in
  the caller's language), `GET /me/wallet`, `GET /me/account-summary` (the S-45 menu contract — S-58 fills
  `reliability`, `points`, `plus`, `activeOrders`, `favourites`, `openCases`), `GET /me/favourites`,
  `PUT|DELETE /me/favourites/{businessId}` (idempotent; 404 when the business isn't active). All `no-store`.
- **One list, three filters:** design 06's "Active · 3 / Past / Refunds & cases · 1" tags filter one Data Table
  (`?view=active|past|cases`). Active = orders not yet delivered (placed → picked up), bookings not yet completed
  (requested → on site), quote requests waiting for or holding open quotes; active rows sort soonest first, past rows
  newest first, at most 100. A request with an accepted quote is not listed — its booking is (status "Deposit held"
  when only the quote's deposit was held). "Refunds & cases" = rows with a refund case or dispute on any of their escrow
  references (an order's lines, a food order, a booking); an open one turns the row's status into "Case RF-…" and its
  action into "View case" (`/account?tab=help&case=<id>`, S-60's tab).
- **Statuses** are codes the web words (en/fr): orders packing / ready / on the way / delivered / done; food paid /
  cooking / ready / on the way / done; bookings booked (nothing paid), escrow, deposit held, on the way, on site,
  completed, done; requests waiting / quote ready / declined / expired. Row titles follow the design: "Grocery run ·
  N shops" for pooled runs (also for non-grocery shops — the design's wording), "Delivery · N shops" for direct
  couriers, the kitchen's name for food, the job or request title otherwise. Actions: Track (orders under way), Details
  (a booking's confirmation `/providers/<slug>/book?step=done&booking=`, a past order), View quote (`/quotes/<id>` when
  one quote is open, else the comparison), Re-book (`/providers/<slug>`), View case.
- **"Done ★5"** isn't shown: there is no consumer review flow writing `trust.reviews` for these jobs yet.
- **Your week** (server-written, en/fr): active rows within seven days, plus requests with a quote ready; times in
  the default market's zone (`region.api.Markets.zone(null)` — no zone in code), money with the locale's CAD format.
- **Wallet & points — minimal read model (stubbed earning):** no loyalty backend existed. `trust.points_ledger` (V012)
  got `created_at` and `note` (V160); `LoyaltyPoints` reads the balance (not-yet-expired credits minus debits) and the
  points earned in each of the last eight weeks. **Nothing writes the ledger** (no earning, redemption or expiry
  rules; checkout's points line stays hidden, S-51) — the dev seed (V161) stands in. 100 points = $1 (design 06:
  1,240 pts = $12.40). Plus reads `identity.households.plus_plan` through `household_members`; V160 adds
  `plus_since` ("Active since …"). Nothing bills Plus (no subscription backend).
- **Wallet's "Payment methods" section** links to the Payment methods tab until S-59 lists the cards there.
- **Favourites:** `account.favourites (user_id, merchant_id, created_at)`. A card shows the business's tier, its
  main category (the leaf of the category id; French from the services taxonomy's `FR_NAMES`), how many of the
  person's own bookings/orders were with it and the last one, and one button: View quote (an open quote from it), Book
  (provider page), Order (kitchen page), Shop (a Shop-scope search by its name — shops have no pages yet, S-48's rule).
  The design's brand colour per card and "3× points this week" (merchant rewards) are not shown: no rewards data.
  "Offered first when you book" and "notified when they fund rewards or open new slots" are **not implemented** (no
  booking-order or notification hook reads favourites yet). ♡ was added to the provider page's header (signed-in
  only; it renders nothing until the browser knows who is signed in, so the SSR HTML is unchanged).
- **Other tabs** of `/account` (payments, profile, addresses, security, notifications, language, dietary, plus, help)
  show "This part of your account is on its way (S-59/S-60)." until those stories land. The tab list wraps into pills
  under 720 px (no horizontal scroll); `Sell or offer a service` links to S-61's `/sell`.
- **UI kit:** `@northline/ui` exports `./DataTable.css` so a server-rendered route can link the table's stylesheet
  (the consumer app's first Data Table).
- **Schema (V160, consumer-account range V160–V169 — renumbered from V140 by the ordering rule):** schema `account` +
  `account.favourites`; `trust.points_ledger.created_at`, `.note` + index `(user_id, created_at)`;
  `identity.households.plus_since`; indexes `identity.household_members(user_id)`, `booking.bookings(customer_id,
  starts_at desc)`, `payments.escrows(customer_id)`, `payments.disputes(opened_by)`. Dev seed V161: Amara's three
  favourites and 1,240 points.
- **Tests:** `AccountActivityApiTest` (orders, bookings and quotes with statuses, actions and hrefs, active first,
  someone else's rows hidden; a refund case on an order line; Your week in en and fr; wallet points/weeks/Plus and an
  empty wallet; favourites add/list/remove, idempotent, 404 for an inactive business; 401 signed out). vitest
  `features/account/account.test.tsx` (design copy and columns, actions navigate, Past / Refunds & cases filters,
  empty, error + Retry, guest sign-in, skeleton, French; wallet balance/value/chart/Plus, error, no points; favourites
  meta, buttons, Remove, empty, guest, French).

## 2026-10-01 — S-59 Account area: profile, addresses, payment methods (SetupIntent), security, notifications, language, dietary & accessibility, Plus

Branch `web/s-59-account-settings`, **stacked on S-58** (`web/s-58-account-activity`, #90).

- **Tabs built:** Payment methods, Profile, Addresses & household, Security & sign-in, Notifications, Language & region,
  Dietary & accessibility, Northline Plus (design 06 `at.*`); the wallet's "Payment methods" section now lists the
  cards. Help & cases is S-60. Every tab has a skeleton, an empty line where a list can be empty, a rosehip error with
  Retry, en + fr-CA copy, 44 px targets and no horizontal scroll (the notification table scrolls inside its own box
  below ~360 px rather than the page).
- **Endpoints** (all `/api/v1/me`, single-factor sessions accepted):
  - identity — `GET|PATCH /profile`, `POST /erasure-request`, `GET|POST /addresses`, `PATCH|DELETE /addresses/{id}`,
    `POST /addresses/{id}/default`, `GET /household`, `POST|DELETE /plus`;
  - payments — `GET /payment-methods`, `POST /payment-methods/setup-intents`, `POST /payment-methods {setupIntentId}`,
    `POST /payment-methods/{id}/default`, `DELETE /payment-methods/{id}`, `GET /billing-history`;
  - messaging — `GET|PUT /notifications`;
  - account — `GET|PATCH /preferences`, `GET /export`; `GET /account-summary` now also fills `paymentMethod`,
    `addresses {count, members}`, `signIn`, `quietHours` ("10 pm" / "22 h"), `dietary` (words in the caller's
    language) and `province` (the S-45 menu contract is complete).
- **Saved cards = Stripe SetupIntents** (new port `payments.application.SavedCardGateway`; `StripeSavedCards` with
  stripe-java, `FakeSavedCards` without a key — a test Visa ending 4242, in memory). Stripe is the source of truth;
  every list refreshes `payments.customer_cards` (brand, last four, expiry, default — never the number), which feeds
  the menu and the billing history's card column. The first saved card becomes the default; removing the default
  promotes the newest remaining card. A SetupIntent or card that isn't the caller's is 404. The card form is the
  Payment Element (`confirmSetup`, `redirect: if_required`) when the api says Stripe, else the read-only test-card
  stand-in (S-51's pattern). Apple Pay / Google Pay rows of the design are not offered (cards only, S-51).
  **Never run against real Stripe** — `StripeSavedCardsStripeMockTest` checks the requests against stripe-mock.
- **Billing history** = the caller's escrows (label, order number, amount + tax + Northline's own charges, state) and
  S-51's delivery-fee PaymentIntents, newest first, 50 at most; "Northline Plus · monthly" rows of the design don't
  exist (Plus isn't billed).
- **Profile:** first/last name and the receipts email (registration's messages; another account's email → 422 "That
  email is already used by another account."), pronouns (she / he / they / prefer not to say), birthday as month-day
  ("Enter a birthday like 03/14 (month / day)."). The verified mobile is read-only (changing it needs a code at
  northline-auth — not in this story). **Not built:** profile photo upload / remove (no avatar storage; initials are
  shown) and the reliability breakdown ("0 no-shows · 0 disputes lost · 14 jobs rated 5★") — only the score and its
  explanation. **"Delete account…"** records `identity.users.erasure_requested_at` after a confirmation; staff erase
  the account (no automated erasure job yet).
- **Addresses:** checkout's validation messages; a name ("Mum") is new (`label`); a saved address can be renamed and
  its unit/note changed — moving it means a new address; removal keeps the row (`deleted_at`, orders may point at it)
  and the next address becomes the default. S-51's `DeliveryAddresses` now ignores removed addresses. Household
  members are listed (names, "own login", "shares Plus"); **invitations are not built** ("Manage" shows the members).
- **Plus — free trial only, no billing:** "Start 30-day free trial" creates the person's household when needed and
  sets `plus_plan`, `plus_since` and `renews_at` = now + 30 days; a plan past `renews_at` is no longer active
  (`PlusMemberships`, wallet, menu). Cancel sets `none`. No Stripe Billing subscription, no charge after the trial, no
  Plus pricing at checkout yet.
- **Security tab = northline-auth's S-19 API through `@northline/auth-kit`:** the schemas, query and changes
  (`securityQuery`, add/remove passkey, revoke session, revoke others, `securityChangeError`) moved from the Studio's
  settings API into `packages/auth-kit/src/security.ts`; the Studio re-exports them unchanged. A phone-code session has
  no second-factor auth session, so the tab first asks to confirm with the passkey / authenticator (S-51's step-up
  endpoints renew the session's factor) — or, for an account without one, to add a passkey on this device (S-51's
  enrolment, only within 15 minutes of signing in; otherwise "sign in again"). A change answered `step_up_required`
  opens the same confirmation and is retried. "Security key (FIDO2) · Add" registers a WebAuthn credential labelled
  "Security key". **Deviations:** the design's "Require Face ID / passkey for payments over $100" would misstate S-51's
  rule (every payment from a phone-code session steps up), so the row reads "Require Face ID / passkey for payments ·
  Always"; "login alerts on" is not claimed (no sign-in alerts are sent); setting up an authenticator app after
  registration isn't offered (no such auth endpoint — the row shows Enabled / Not set up). "Sign out everywhere" signs
  every other session out (step-up as needed) and then this browser.
- **Download my data** (`GET /me/export`, `Content-Disposition: attachment`): profile, addresses, preferences,
  favourites, orders & bookings, wallet, refund cases. Not included: notification settings, saved-card summaries,
  messages, and northline-auth's data (passkeys, sessions — shown on the tab).
- **Notifications:** the design's seven rows × push/SMS/email with its defaults; security alerts are on everywhere and
  can't be turned off (422 "Security alerts always go to every channel."). Quiet hours share the person's
  `quiet_from` / `quiet_to` with their Studio matrix (one person, one night); `quiet_on`, the notification language
  (same as app / English / Français) and marketing email (weekly / rewards only / none — CASL) are customer-only
  columns. Changes are kept on the page until "Save preferences". **Stored only — no customer notification is sent
  yet** (the S-27 worker has no customer events); the SMS number and email shown are the profile's (read-only here).
- **Language & region:** English / Français switch the app in place on Save and set `identity.users.locale`
  (receipts, notifications). The design's "ਪੰਜਾਬੀ · Punjabi (beta)" is not offered: the app has no Punjabi copy.
  Province choices are the region model's live and pilot provinces (S-134; pilots marked "(pilot)"), plus "Follow my
  location"; the api accepts any province of the region model (no province list in code — S-134's lint). The French
  option's design wording "requis au Québec" is allowed in the web region lint with that reason; units and time format are stored (nothing formats with them yet); currency is CAD only.
- **Dietary & accessibility:** codes stored in `account.preferences`; the menu shows the dietary words. **Not yet
  used:** shop filtering by diet, flagging products, sharing notes with visiting providers, and applying the display
  choices (larger text, high contrast, reduce motion) to the page.
- **Schema (V162):** `identity.users.pronouns`, `birthday_month`, `birthday_day`, `erasure_requested_at`;
  `identity.addresses.label`, `created_at`, `deleted_at`; `account.preferences`; `payments.customer_cards`; index
  `payments.payment_intents(customer_id)`; `messaging.notification_prefs.customer_matrix`, `quiet_on`, `notify_lang`,
  `marketing`. Dev seed V163: Amara's two addresses (design copy), Kofi in her household, her dietary/accessibility
  choices.
- **Tests:** `AccountSettingsApiTest` (profile read/update and messages, email taken, erasure idempotent; addresses
  add/rename/default/remove, someone else's 404, messages; Plus trial start/conflict/cancel and the wallet; saved
  cards through the fake SetupIntents, default, remove, someone else's 404; billing history; notifications defaults,
  changes, security locked, quiet hours validation and the menu value; preferences incl. locale and menu values;
  export; 401s), `StripeSavedCardsStripeMockTest`; vitest `features/account/settings.test.tsx` (every tab's main path,
  validation messages, step-up prompt, French).

## 2026-10-01 — S-60 Refund / "something's wrong" flow into the case queue

Branch `web/s-60-something-wrong`, **stacked on S-59** (#93, itself on S-58 #90).

- **Design source:** design 06 has the cases table (Help & cases) and "View case" from Orders & bookings; the report
  screen itself is the consumer app's `refund` screen (`design/Consumer Screen.dc.html`, "Something's wrong" on
  `delivered`). Its copy is used as written ("Pick what went wrong. Your request opens a case…", "Request $X refund",
  "Case RF-2201 · in review", the four steps, "We'll notify you at each step…").
- **Never an instant refund.** A report opens, per escrow, a payments refund case through the new
  `CustomerCases.requestReview` (S-11's case queue: the escrow goes on hold, the business is emailed via
  `refund.case_updated`, the business accepts or contests within 24 h). Unlike `requestRefund` — whose "under $25 is
  approved unless contested within 48 h" rule the Studio describes — **review cases are never approved by the clock**:
  `Refund.requested(…, autoApprove = false)` makes the lapse job send them to a Northline agent. Payment happens only
  through the existing refund queue after an approval. A refund case on an already refunded escrow is now refused
  (409 `escrow_refunded`) for both paths.
- **Escrow windows respected** (`account.domain.ProblemRules`, `payments.api.CustomerEscrows`): an item can be reported
  while its escrow is held and fulfilled and before it releases — goods until 7 days after delivery, services until
  48 h after completion (a customer's sign-off / "All good" releases at once and closes the window). Food is released
  at handoff, so food problems can be reported for **24 h after handoff** (our number; the refund then comes back
  through S-11's transfer reversal). Statuses per item: open, reported (a case is open), closed (released / refunded
  / past the window), not_yet (not delivered / done), not_paid (no escrow). 409 codes: `already_reported`,
  `window_closed`, `not_fulfilled`, `not_paid`.
- **One case per escrow:** goods orders hold escrow per line, so each chosen line gets its own `RF-…` (the design's
  cases table lists them per item: "kale spoiled · $4.46"); a food order (one escrow) and a booking get one, whatever
  lines are picked (food: the picked lines' amount, capped at the escrow). The amount asked is the item's price plus
  its share of the tax, as the refund queue pays it.
- **Northline's case queue:** each report also opens one **customer case** in `messaging.tickets`
  (`requester_type = customer`, no business — so it never shows in a Studio's Help — topic `refund`, `HD-…`, the
  support SLA: urgent 15 min, else 4 h of support hours) with a `case` thread holding the report and photos, and in
  `context` the refund case ids and the triage. No console queue screen exists yet; staff work from the table (no
  `ticket.opened` event: its schema requires a business — follow-up).
- **S-132 triage is optional:** the web asks `POST /api/v1/me/help/triage` when the person writes a note (≥ 10
  characters, on blur) and shows "Sounds like: Damaged" with a button to use it; only when the person's chosen reason
  matches the suggestion are `triageCategory` / `triageSummary` sent. Any failure (AI off, budget, older api) shows
  nothing. Without triage the server maps the reason to S-132's categories (`damaged`, `missing_item`,
  `service_quality` …); `safety` makes the case urgent. The flow works the same without the AI module.
- **Reasons:** goods and food — Missing, Damaged, Wrong item, Poor quality, Late (design); services — Not done, Poor
  quality, Late, No-show, Charged wrongly (ours; the design shows only goods).
- **Photos:** `POST /me/case-uploads` (the help form's rules: JPG/PNG/HEIC/PDF, signature checked, ≤ 10 MB, ≤ 5 per
  report or note) into the messaging `AttachmentStorage` under `customers/<user>/…` (new `ObjectKeys.customerObject`)
  and `messaging.customer_uploads`; only the uploader can attach or read them.
- **Help & cases** (`/account?tab=help`, design 06 `at.help`): the person's refund cases and disputes with the design's
  statuses ("Seller reviewing · 14 h left", "Closed · $15 credit", …) and what they are about (the Orders & bookings
  row); `?case=` opens one with its timeline (Submitted → Seller reviews → Northline decides → Refund issued; steps
  marked done / now / next / not needed / not refunded) and the case's messages, where the person can add a message
  ("You can add photos or messages to the case any time"). **Not built:** "Chat with Northline" (no live support chat
  for customers); adding photos to an existing case from the web (the api accepts `attachmentIds` on notes).
- **Entry points:** Orders & bookings rows of delivered orders (goods: 7 days, food: 24 h) and completed paid jobs get
  "Something's wrong" (`report` action → `/account/problem/<order|food|booking>/<id>`); the order tracking page (S-52)
  when delivered and the food tracking page (S-57) after handoff link to it. The page itself checks the escrow.
- **Points adjusted** in the last step is the design's copy; no ledger writer exists yet (S-58).
- **Schema (V164):** `messaging.customer_uploads`; index `messaging.tickets (requester_type, requester_id,
  created_at)`. No new configuration.
- **Tests:** `SomethingWrongApiTest` (report → refund case in seller review with `auto = false`, escrow on hold,
  customer ticket with the refund and triage category, already reported 409, lapse goes to an agent not an approval;
  closed / not yet / not paid; a job as one case and safety urgent; food released at handoff still reportable, one
  case for two lines; validation messages and others' 404/401; photos, Help & cases list, detail steps, a note,
  others' 404; the `report` action in Orders & bookings). vitest `features/account/problem.test.tsx` (report with a
  photo and the four steps, required item and reason, triage suggestion used and sent, no suggestion when triage is
  off, closed window, error + Retry, French; Help & cases statuses, a case's timeline and a message).

## 2026-10-01 — S-135 BFF relay race: JDK HttpClient request factory in the api relay

- **Root cause: an empty request body streamed into a JDK HttpClient race.** It is not the docs viewers.
  - Gateway MVC (`RestClientProxyExchange`) streams a body whenever `getInputStream().isFinished()` is false. MockMvc
    reports that for every request, GETs included, until the stream is read. Tomcat reports it for a chunked request
    whose body is empty. Real GETs on Tomcat are already "finished" and go out without a body.
  - Spring's `JdkClientHttpRequest` then publishes the body through `OutputStreamPublisher`, which runs the writer on
    its own (virtual) thread. For an empty body the writer signals `onComplete` at once, without waiting for demand,
    which Reactive Streams allows.
  - In the JDK 25 client, `Http1Exchange.sendBodyAsync` subscribes first and stores `bodySubscriber` afterwards. A
    completion that arrives in between runs the write path, and the write path calls `requestMoreBody()` on the null
    field. The result is `NullPointerException: … "this.bodySubscriber" is null`, the S-125 symptom with the same stack.
  - The window opens when the exchange runs on a **pooled keep-alive connection that the server has already closed**.
    A stand-alone reproduction (`JdkClientHttpRequestFactory`, a server that drops each connection after answering)
    failed 12 of 200 requests with an empty streamed body and 0 of 200 with no body. Healthy connections failed 0 of
    2,000.
  - S-125's correlation with "a spec had been generated in the same process" was timing: generating the spec and
    serving the webjars left the WireMock api's kept-alive connections idle long enough to be closed. The guess that
    dev/staging would hit the race after someone opens `/bff/docs` does not hold. What matters is an empty chunked body
    on a stale connection, and it can happen with or without the docs.
- **Suspects ruled out:**
  - *A shared HttpClient:* the gateway's `RestClient` has its own request factory, and the failing state is one
    exchange's field.
  - *The HTTP/2 upgrade:* the stack is `Http1Exchange`, and the reproduction fails with an HTTP/1.1-only client.
  - *Connection reuse on its own:* with no body it never failed. Reuse widens the window but does not cause the race.
  - *Virtual threads:* they make the writer thread start immediately, so they make the race more likely but don't
    cause it.
- **Fix in the relay, not a global setting:** `bff.config.RelayBody` is the first `before` filter of the `/api/**`
  route. It wraps the servlet request so that `isFinished()` reads one byte ahead.
  - With no byte, the gateway sends no body (a GET is a plain GET, and an empty POST gets `Content-Length: 0`).
  - With at least one byte, the publisher's first write waits for the client's demand, which comes only after
    `bodySubscriber` is stored.
  - The look-ahead blocks, which is fine because the relay is blocking (virtual threads) and reads the body straight
    after.
  - Rejected alternatives:
    - `BufferingClientHttpRequestFactory` also buffers responses, which would break the S-130 assistant's SSE stream.
    - A custom `ProxyExchange` would copy the gateway's response handling.
    - `jdk.httpclient.*` system properties are JVM-wide.
  - SSE streaming is unchanged: `SseRelayTest` still passes, because response handling is untouched.
- **Tests:**
  - `RelayRaceTest` (bff) runs in a context that has generated its OpenAPI document and served Swagger UI's webjar,
    the Scalar page and `swagger-config`. Four workers send 400 requests each way:
    - GETs through MockMvc, plain GETs and GETs with an empty chunked body over a real connection, all against an api
      stand-in that closes every connection after answering, without saying so.
    - DELETE, empty and JSON POSTs, and chunked (empty, 2-chunk, 5 kB) and `Content-Length` bodies against a
      keep-alive stand-in, checking that the api receives exactly the bytes sent.
  - Without the fix both tests failed with the NPE, 2 of 2 runs. With it, `RelayRaceTest`, `ConsumerBffTest`,
    `SseRelayTest` and `BffSessionTest` passed **20 of 20 runs** in a row (`:bff:test --rerun`).
  - S-125's workaround is undone: `BffSessionTest` checks Swagger UI's `index.html` and the Scalar page again.
    `ConsumerBffOpenApiTest` keeps its own context, because the committed document shows the local cookie names.
- **Not fixed (HTTP itself, recorded for the lead):** a relayed POST, PUT, PATCH or DELETE that lands on a pooled
  connection the api has just closed fails with an `IOException` ("header parser received no bytes"). The browser sees
  a 500. The JDK client retries only GET and HEAD on a connection that turned out to be closed
  (`jdk.httpclient.enableAllMethodRetry` would retry everything, JVM-wide, which is unsafe for payments). Against the
  api's Tomcat this needs Tomcat to close an idle connection at the moment the relay reuses it. The JDK client drops
  idle connections after 30 s (`jdk.httpclient.keepalive.timeout`), so keeping the api's keep-alive timeout above
  that makes it rarer still. Money-moving POSTs carry an `Idempotency-Key` anyway, so the browser can retry them.

## 2026-10-01 — S-136 Calendar link disconnect deadlock

- **The competing transactions** are a calendar read and the member's disconnect, with locks taken in opposite orders.
  A read comes from the sync job, a notification or the read right after connecting or choosing calendars.
  - **The read** locked the calendar's `calendar_sources` row (`FOR NO KEY UPDATE SKIP LOCKED`), called the provider,
    rewrote the busy blocks and only then updated the parent `calendar_links` row: `last_sync_at`, plus a rotated
    Microsoft refresh token or the `reconnect` state on the way.
  - **The disconnect** deleted the `calendar_links` row first (`FOR UPDATE`), then, by `ON DELETE CASCADE`, the sources
    and everything under them.
  - The two locks were taken in opposite orders: source → link versus link → source. Postgres aborted one of the two
    transactions. `CalendarSyncApiTest`'s "choose calendars, then disconnect" hit it when the read queued by the choice
    was still running.
  - The write-back already locked the link first, and the channel transactions touch only channel rows, so neither
    took part.
- **Fix: one lock order, the link first, in the strongest mode the transaction will need, so it never upgrades
  later.**
  - `CalendarLinkRepository.lockWaiting` (`FOR NO KEY UPDATE`, waiting) replaces the unlocked `byId` at the start of
    a read and of "choose calendars".
  - `lockForDelete` (`FOR UPDATE`) starts a disconnect, before it reads the channels and mirrors. A read or write-back
    in flight therefore finishes first, and its mirrors are seen and deleted at the provider. None starts until the
    link is gone: a read waits and then finds no link, and the job's write-back skips it.
  - Rejected alternatives:
    - A retry on deadlock would also have to retry the member's DELETE request, and it hides the cause.
    - `FOR KEY SHARE` first and the update later deadlocks with any other transaction that updates the link and
      then touches a source, such as "choose calendars" after a token refresh.
- **Write-back now waits instead of skipping when it is asked for at once.** Reads hold the link for the length of
  the provider call, so the S-55 write-back of a newly confirmed booking (`writeBackMember`) and the one after
  connecting wait for a read in progress (`writeBack(link, wait = true)`) instead of skipping until the next 5-minute
  run. The job's write-back keeps `SKIP LOCKED`, so replicas still share the work. Reads of one member's calendars now
  run one after another; they already queued on the link update at the end.
- **Tests:** `CalendarLockOrderTest`:
  - **Forced interleaving:** a third transaction holds a busy block, so the read waits while holding the source. The
    disconnect starts, and the test waits until Postgres shows it blocked. Then the busy block is released.
  - **Unforced:** five rounds of four reads and a disconnect started together.
  - Without the fix both tests failed with `deadlock detected`. With it, they and `CalendarSyncApiTest` passed 5 of 5
    runs in a row, and `CalendarProvidersWireMockTest` passes.

## 2026-10-01 — S-137 Indexer refreshes before finding stale documents

- **Cause:** a whole-merchant refresh finds the documents of rows deleted from Postgres by *searching* the index for
  the merchant's ids. A search sees only refreshed segments, so a dish indexed less than a second before its deletion
  was invisible to it and survived until a later refresh of the same merchant. The reconcile sweep did not help,
  because a deleted row changes no `updated_at`.
- **Fix: `_refresh` the index before the id lookup** (`SearchProjection.indexedIds`, the language whose ids the lookup
  reads). A refresh of an index with nothing new is a no-op, and otherwise it writes one small segment, as the
  1-second refresh interval does anyway. Whole-merchant refreshes come from merchant-wide events and the reconcile
  sweep, at a rate in the order of the refresh interval.
- **The reindex backfill skips the lookup** (`SearchProjection.backfill`): it writes a merchant's first documents to
  indices created by that run, which hold nothing of the merchant yet, and those indices load with refresh off, so a
  refresh per merchant would only make thousands of tiny segments. The catch-up and the post-swap sweep of a reindex
  use the normal path and do get the refresh, which also fixes a deletion during a reindex being missed on the
  still-unrefreshed new index.
- **Rejected alternatives:**
  - `refresh=wait_for` on every write would hold every indexer event up to a second, which throttles consumption.
  - A Postgres table of indexed ids per merchant would give a deterministic lookup, at the cost of a migration and a
    write per document. It is the follow-up if refresh load ever shows up in the cluster's metrics.
- **Tests:**
  - `SearchIndexerTest.kitchenDishes_…` no longer republishes `menu_published` every 3 seconds: one event, then the
    deletion.
  - The new `aJustIndexedDish_deletedFromPostgres_isRemovedOnTheFirstMenuPublished` turns refresh off on the live
    indices, so only the projection's own refresh can make the new dish searchable. It indexes a dish, deletes the
    row and publishes one event; the dish is gone and its neighbour is kept. It failed without the fix (1 of 1 run); with it, `SearchIndexerTest` and `SearchReindexTest` passed 5 of 5 consecutive runs.
- **No schema, configuration or API change.**

## 2026-10-01 — S-139 Public 'docs' OAuth client so Swagger UI / Scalar 'Try it' can sign in

Runbook: [api-docs.md § Try it with your own sign-in](runbooks/api-docs.md). It follows the S-122 catalogue rules
(a public client, PKCE, no secret, https outside local/dev loopback) and S-29's rule that a public client refreshes
only with DPoP.

- **Client `docs`** ("Northline API docs (Swagger UI, Scalar)"):
  - public, authorization code + PKCE S256, no consent screen;
  - scopes `openid profile merchant` (the scopes the `oauth2` scheme of the specs lists);
  - grant type `authorization_code` only, with 10-minute access tokens and no refresh token, since the viewers
    don't do DPoP;
  - redirect URIs `${API_PUBLIC_URL}/swagger-ui/oauth2-redirect.html` and `${API_PUBLIC_URL}/docs/scalar`.

  The token is the person's own: the api applies their memberships, roles and `acr`, as for any client, so Studio
  calls still need a second factor. The client is not in `mfa-required-clients`, because consumer endpoints are
  documented too. People sign in on the Studio's sign-in page (`LoginPages` default), or not at all with an existing
  session.
- **Never in prod:** the client lives in a document of auth's `application.yml` activated on
  `local | test | dev | staging`. Profile documents, rather than a new `ClientSpec` key, keep the catalogue unchanged.
  `DocsClientTest` resolves the configuration per profile and asserts that dev and staging have the client and its
  CORS origin and that prod has neither. Prod also has no viewer (S-125).
- **CORS on `/oauth2/token` and `/oauth2/revoke`** (new `northline.auth.token-endpoint-origins`, empty by default,
  `${API_PUBLIC_URL}` in the same profile document):
  - Swagger UI and Scalar exchange the code with `fetch` from the api host, and Swagger UI's `X-Requested-With`
    header needs a preflight.
  - The CORS is POST only, without credentials, for that origin only. The viewer pages' CSP already allowed
    `connect-src <issuer>` (S-125).
  - The authorization-server filter chain matches only `POST /oauth2/token`, so the preflight `OPTIONS` lands in the
    web chain. Both chains therefore register the same token-endpoint CORS (`registerTokenEndpointCors`).
  - The authorization endpoint needs no CORS, because it is a top-level navigation in a popup.
- **Viewers** (the api's `application.yml`): Swagger UI has `oauth.client-id: docs`, its scopes and a fixed
  `oauth2-redirect-url` from `API_PUBLIC_URL` (never the request's host behind the ingress). Scalar has
  `scalar.authentication` with the preferred scheme `oauth2`, `clientId: docs`, PKCE SHA-256 and the redirect to its
  own page. springdoc copies these into Scalar's configuration. The auth and BFF viewers are unchanged: their
  endpoints use cookies, not bearer tokens.
- **No new variable:** auth now also reads `API_PUBLIC_URL` (already derived by the chart for every app and already
  used by S-127). The README and the dev/staging tables say so.
- **Tests:**
  - `DocsClientTest` (auth):
    - with a signed-in session, the authorization request for `docs` redirects straight to Swagger UI's
      `oauth2-redirect.html` with a code;
    - the preflight and the token exchange from `http://localhost:8080` get `Access-Control-Allow-Origin` without
      credentials, and the token is addressed to `northline-api`, for `client_id=docs`, with no refresh token;
    - another origin gets no CORS, and another redirect URI is refused;
    - the client is configured per profile as described above.
  - The api's `OpenApiSpecsTest` checks Swagger UI's `oauth2RedirectUrl` and Scalar's client id, redirect and PKCE.
- **Not exercised:** a click-through in a real browser against a deployed dev environment (popups, the Studio
  sign-in hand-off back to `/oauth2/authorize`). The flow was checked with MockMvc only.

## 2026-10-01 — S-138 Make Error Prone warnings errors (-Werror)

- **Remaining warnings on main, fixed in the code:** a clean `compileJava compileTestJava compileTestFixturesJava
  --rerun-tasks --continue` of every module found three, all in `:api`. Since #72, merged work had added them.
  - `RedisSlotHoldStore` (`LongDoubleConversion`): an explicit `(double)` cast on the ZSET score's lower bound.
    Epoch milliseconds are below 2^53, so they are exact as a double.
  - `CustomerBookings` (`InvalidParam`): the javadoc names `booking.bookingId()`, not a parameter `bookingId`.
  - `PublicKitchenService` (`BoxingComparator`): `thenComparingDouble` for the distance sort.

  No suppression was added.
- **`-Werror`** is added to every `JavaCompile` in `server/build.gradle.kts`, as the 2026-10-01 "warnings to zero"
  section proposed. javac fails on any warning, Error Prone's and NullAway's included, in main, test and test-fixture
  code. `disableWarningsInGeneratedCode` and the excluded `build/generated` paths stay, so MapStruct and Lombok output
  can't fail the build.
  - Proof: reintroducing the `LongDoubleConversion` warning makes `:api:compileJava` fail with
    `error: warnings found and -Werror specified`.
  - It is documented in BACKEND_CONVENTIONS § 10, with the command that lists every warning at once.
- **Open branches checked before switching it on:** S-58, S-59 and S-60 (the account area; PRs #90, #93, #95) and
  this run's S-135, S-136, S-137 and S-139 (#91, #92, #94, #96). Each branch tip was merged with this change, with no
  conflict, and compiled cleanly with `--rerun-tasks`: 0 warnings, and all seven compile. A later commit on one of
  them that adds a warning will fail its build after this merges. The fix is in the code, as above.
- **Not changed:** the disabled checks (`StringSplitter`, `MissingSummary`, `JavaTimeDefaultTimeZone`) and NullAway
  being off in tests.

## 2026-09-30 — S-40 French validation messages across Studio forms
- **One catalogue, English keys.** Messages stay English in the code — validation-rules.md's exact text and the modules' own constants — and `docs/spec/validation-messages.fr-CA.tsv` maps each to fr-CA (`English<TAB>French<TAB>status`, 579 lines). A TSV rather than ICU keys: nothing in the 250+ throw sites changes, the English stays the single source of the rule ids and tests, and a translator can work on it in a spreadsheet. The platform library packages it (`i18n/…`) and `ca.northline.platform.MessageCatalogue` resolves it for the api and northline-auth.
- **Server language = `Accept-Language`.** `ApiExceptionHandler` (api) and `AuthExceptionHandler` (northline-auth) translate the 422 messages, and the 403/409 ProblemDetail `detail`s they produce, when the header's highest-weighted `en`/`fr` range is French (`fr`, `fr-CA`, `fr;q=0.9` before `en;q=0.8`). No header, `*`, English first or an unreadable header → the spec's English, byte for byte (existing tests unchanged). Rule ids and fields never change. 429/503 details written by module-level advices (geo, search, Stripe webhooks) and 404 details stay English.
- **Run-time messages are templates.** A message built with `formatted`/`replace`/concatenation is a catalogue line with placeholders: the code's own (`%s`, `%d`, `%02d`, `%1$s`) or `{name}` for concatenations and Bean Validation attributes (`At most {max} characters.`). Parts are captured from the English and put into the French; exact lines win, longer templates are tried first. Amounts in a part are rewritten (`$1,234.50` → `1 234,50 $`). `{province:in}` asks for the place with its French preposition: the new `ca.northline.shared.PlaceNames` port, implemented by the region module from the province profiles (`nameFr`, `inFr`), so "Northline isn't open in Nova Scotia yet." becomes "Northline n’est pas encore offert en Nouvelle-Écosse." — no place name in code (region-neutral rule). Unknown places keep their name ("à {name}" with a preposition).
- **Status column / translator review.** `shipped` (210 lines) = the French the Studio or consumer web already showed, kept so a 422 and a client check read the same; `new` (341) = first French wording, ours; `review` (28) = new wording a translator must settle first: lines that carry API codes (`price_asc`, `lat`/`lng`, `delivery=tonight`, `mon, tue…`), English state or role codes inside French sentences (`This quote is %s…`, `Your role (%s)…`), labels of choices the UI names differently, and `Choose %s.` (gender of the attribute). **No line has been signed off by a professional translator** — the backlog's "reviewed by a translator" is not met by this PR and needs a translator pass over the TSV (start with `review`, then `new`).
- **Coverage is enforced.** `ValidationMessageCatalogueTests` (api) reads the api and auth sources and fails on any message without French: `message = …` of Bean Validation annotations, the message argument of `RuleViolation.of`, `new Violation`, `new Conflict` (literal or constant, ternaries included) and every constant of a `*Messages` class. `MessageCatalogueTest` (platform) checks the file parses, placeholders match between English and French, and the templates. Messages assembled from parts the scanner can't see must be added by hand (the food/search concatenations are).
- **Studio.** `http()` (`@northline/client`) sends `Accept-Language: fr-CA|en-CA` from the Studio's language switch (`setRequestLocale`, set at start and on every change) — the switch decides, not the browser. A call that sets its own header (messages, help) keeps it. Client checks keep the English zod messages and translate them at display through a per-feature dictionary keyed by the English (`lib/validation.ts` `useLocalizeMessage`; the existing `useMessageT`s of catalogue, messages and reviews follow the same idea): added for the quote composer, the extra-work approval and availability hours / time off, which showed English in French. `lib/validation.test.ts` fails when a client dictionary's French differs from the catalogue's for the same English. Aligned on the way: "At most N characters." is "Au plus N caractères." everywhere (the catalogue editor said "N caractères au maximum."), "Files can be up to 10 MB." is "Les fichiers peuvent faire jusqu’à 10 Mo.".
- **Not done.** Consumer web does not call `setRequestLocale` yet (its 422s follow the browser's `Accept-Language`, which is French for French browsers); email/SMS texts, MCP tool descriptions and AI errors are not validation messages and are not in the catalogue. Bulk-import row errors ("Price $4 is 92% below…") are report rows, not 422s, and stay as S-35 left them.

## 2026-09-30 — S-73 Guard kitchen endpoints to kitchen businesses
- **404, not 403.** A business whose type isn't `kitchen` (provider, seller, both) gets **404** `not_found` ("No kitchen with id …") on every Studio kitchen endpoint. Reason: 403 in this API means "you can't do this here" and carries `not_a_member` / `insufficient_role` / `mfa_required`, all fixable by who signs in; for a non-kitchen there is simply no kitchen resource, and no sign-in changes that. The Studio already treats 404 on `…/menus` and `…/modifier-groups` as "none" (onboarding `listingsApi.ts`), and its nav never shows kitchen screens to other types, so nothing in the web app changes.
- **Which endpoints.** All handlers of the kitchen controllers: `…/kitchen/*` (live board, prep bump, pause, setup, prep, fulfilment, hours, holiday hours, promos) and the other kitchen-only Studio resources, which returned empty data the same way: `…/menus`, `…/menu-items`, `…/modifier-groups`, `…/combos`, POS import (`…/pos/*`, `…/menus/{id}/pos-imports`, `…/pos-imports/*`). Public food endpoints (`/api/v1/public/kitchens`) and checkout are untouched.
- **Order of checks.** `food.web.KitchenOnlyAdvice` (`@ControllerAdvice` limited to those controllers, a `@ModelAttribute` method) runs after the `@RequiresMerchant` interceptor — a non-member still gets 403 and learns nothing about the business type — and before the request body is bound and validated, so a non-kitchen never sees a kitchen's 422s. The check is the use case `KitchenUseCases.RequireKitchen` over `KitchenMerchantFacts.kitchen(merchantId)` (merchants' `MerchantDirectory`, one indexed read per request).
- MCP and Studio-assistant tools call the food services directly, not these controllers, so this guard does not cover them (outside the story's `/kitchen/*` scope).

## 2026-09-30 — S-64 Orders public API for order lines; remove direct orders SQL from food
- **An SPI in `food.api`, implemented by orders — not a call into `orders.api`.** orders depends on food (it prices dishes with `FoodMenuPricing`, reads `FoodCheckoutFacts` / `KitchenProgress` and moves order state on the kitchen events), so food calling `orders.api` would be a module cycle that `ModularityTests` rejects. The query is therefore declared where it's used, as BACKEND_CONVENTIONS § 2 and the S-37 follow-up note prescribe: `food.api.KitchenOrderFeed` (`open(merchantId, dueBy)`, `order(merchantId, orderId)`, `lines(merchantId, orderIds)`), implemented by `orders.persistence.KitchenOrderFeedJdbc` on `orders.orders`, `orders.group_orders` and `orders.order_lines`. It is the orders module's public read of its order lines for the kitchen; the backlog's "food uses orders.api" is met in substance (orders owns and answers the query), not by package name. Modifier JSON is parsed on the orders side (`Line.modifiers` is a list of names).
- **fulfilment gets its first code.** The courier's pickup leg (`fulfilment.stops` / `runs` / `couriers`) was the other half of the live board's join. `fulfilment.api.CourierPickups.of(orderIds)` (first pickup stop per order: courier assigned, courier user, ETA, arrived) with `fulfilment.persistence.CourierPickupsJdbc`; fulfilment depends on nothing, so food may call it.
- **food keeps only its own tables.** `KitchenTicketJdbc` composes the feed, the pickups and `food.kitchen_tickets` / `food.menu_items` in memory: the same rows as the old single query (states placed/accepted/packing/ready, a line of this kitchen, scheduled orders 60 min ahead, handed-off tickets dropped, line titles falling back to the menu item's name, then "Item"). The order load (Σ qty × unit, slowest item's extra prep) and the escrow release's line ids (`FeedKitchenOrderLines`, replacing `KitchenOrderLinesJdbc`) use the feed; the `kds` badge counts the feed's placed/accepted/packing orders minus those whose ticket is past cooking, with the injected `Clock` instead of the database's `now()`. Cost: the live board is up to 5 queries per refresh instead of 2 (orders, tickets, lines, item names when a line has no title, pickups), each by merchant and/or a list of order ids.
- **Rule.** `SchemaOwnershipTests.ALLOWED` is empty (the three food exceptions are gone), and a new ArchUnit rule `kitchenReadsOrdersThroughTheFeed` forbids food from depending on any orders class or on fulfilment internals. BACKEND_CONVENTIONS updated.
- Outside the api monolith, the worker's and auth's reads of `merchants.*` (S-37 note) are unchanged.

## 2026-09-30 — S-66 Finance and compliance implement CaseReferences for the help form
- **Who implements what.** The plan said finance and compliance implement `messaging.api.CaseReferences` themselves. They can't: messaging already depends on payments (it listens to `DisputeDecided`, `RefundIssued`, `PayoutAccountChanged` …) and on merchants (`MerchantDirectory`, `TeamRoster`), so payments or merchants implementing a messaging SPI would be a module cycle (`ModularityTests`). So each owner publishes the read in its own `api` and messaging turns it into "Related to" entries:
  - payments: `payments.api.RecentPayouts.recent(merchantId, limit)` (implemented by `PayoutService` over the payout history) → `messaging.application.PayoutCaseReferences`: the 5 latest payouts, "Payout · $1,234.56 · Sep 26" / « Versement · 1 234,56 $ · 26 sept. » ("Instant payout" / « Versement instantané » for instant ones), dated in the business's time zone (region model: its market, else province, else the platform zone).
  - merchants (compliance ledger): `merchants.api.ComplianceDocuments.documents(merchantId)` (implemented by `ComplianceLedgerService` over the ledger rows the Compliance screen lists: licences, insurance, WCB, permits, certificates, inspections, attestations, GST; not KYC/bank/MFA/site visit) → `messaging.application.DocumentCaseReferences`: earliest expiry first, "Liability insurance · exp. Jan 2027" / « Assurance responsabilité · exp. janv. 2027 ».
- **Document names** are the row's recorded label, else a generic name per check type (en/fr), with the registry or reference where it identifies the document ("AMVIC licence", "Food permit · #FS-…", "GST/HST registration"). Generic on purpose — no provincial agency in code (region-neutral rule); the Studio's Compliance screen still uses its own names.
- Nothing else changes: the case form already accepted `refType` `payout | document`, and the Studio's "Related to" select shows whatever the endpoint returns (no web change). As before, the api does not check that a submitted `refId` is one of the offered records (the label is stored as sent).

## 2026-09-30 — S-67 Enforce kitchen auto-pause on late orders and the ±40 % menu price vetting rule
- **Auto-pause.** Since S-46 the customer side already refused orders at read time while "late orders ≥ N" (`KitchenCalendar`, checkout's `kitchen_closed`), but nothing recorded it, nothing told search or the kitchen, and the setting looked unenforced in the Studio. Now:
  - `food.kitchen_settings.auto_paused_at` (V170) marks the state; `KitchenUseCases.KitchenAutoPause.check` compares the late orders (accepted tickets past their ready-by time — the same count as the customer side) with the threshold and, on a change only, sets / clears the mark with a conditional update and publishes **`kitchen.auto_paused`** (`lateOrders`, `threshold`) or **`kitchen.auto_resumed`** on topic `food.kitchen` (new events + JSON schemas; the search indexer already refreshes the whole merchant on any `food.kitchen` event). Two replicas can't publish the same transition.
  - When: every minute (`KitchenAutoPauseScheduler`, not under `test`) — orders become late as time passes — and immediately after "Mark ready" / "Handed off", so catching up reopens the kitchen at once. Turning the setting off resumes on the next sweep.
  - The Studio's live board returns `autoPause {lateOrders, threshold, active}` and shows "Auto-paused. N orders are past the ready-by time (your limit is N)…" (en/fr). The manual 30-minute pause is unchanged and independent.
  - Late = cooking past ready-by; a new order not accepted in time doesn't count (no promise was made yet) — as S-46 defined it.
- **±40 % price vetting** (design 02c "prices within ±40 % of the cuisine median"):
  - Comparable dishes = live, approved dishes of the *other* active kitchens in the same city (the market key) that share one of the kitchen's public cuisines (`merchants.api.PublicDirectory`). The median needs at least 5 such dishes; with fewer, or for a kitchen with no public cuisine yet (not approved), there is no check. `food.application.PriceBenchmarks` / `PriceBenchmarksJdbc`.
  - A published dish whose price is outside ±40 % of that median is **held**: new visibility `price_check` (vetting `pending`, hidden from customers) until the owner (or a cook — EDIT) confirms the price with `POST …/menu-items/{id}/confirm-price` ("Keep this price"), or changes it to one inside the band. A changed price is checked again; a confirmed price stays confirmed. Item JSON gains `priceCheck {medianCents, deviationPct, confirmed}` when the price is an outlier.
  - Checked when a dish is saved as published (the median at that time is stored in `food.menu_items.price_median_cents`, V170; the confirmed price in `price_confirmed_cents`) and again for every published dish when Northline approves the kitchen (`reaudit`). Medians are not refreshed on their own as other kitchens change prices.
  - Order of the menu builder's status: Draft → Needs photo → **Price check** → Hidden until approved → Live.
  - "Flagged" is to the merchant, not to a staff queue: trust can't hear food's events (food already depends on `trust.api` — a cycle), and a console review queue for menu prices is a console story. Recorded as open.
- Migration **V170** (`V170__kitchen_autopause_price_check.sql`, range V170–V179 added to IMPLEMENTATION_PLAN by the ordering rule): `food.kitchen_settings.auto_paused_at`, `food.menu_items.price_median_cents`, `price_confirmed_cents`. Additive.

## 2026-09-30 — S-41 Tax documents as PDF (the GST reversal on refunds was done in S-21)
- **Library: Apache PDFBox 3.0.8** (`org.apache.pdfbox:pdfbox`, version in `libs.versions.toml`). Why: Apache-2.0 (iText 7+ is AGPL, ruled out for a closed platform; OpenPDF is LGPL/MPL), pure Java with no native or font-server dependencies (runs the same on every cloud and in the distroless image), actively maintained by the ASF, and the library the team already uses in samop, so its quirks are known. It only draws; the layout (one title, party lines, a table, notes, page footer) is ours in `payments.infra.PdfBoxStatementRenderer`. Fonts are the PDF standard Helvetica / Helvetica-Bold (nothing embedded; WinAnsi covers English and French — the narrow no-break space Java uses in French numbers is printed as a no-break space).
- **Documents and endpoints** (FINANCE_READ, like the CSVs): `GET …/reports/gst-summary.pdf?year=&lang=` ("{year} GST/HST collected summary" / « Sommaire de la TPS/TVH perçue {year} ») and `GET …/reports/annual-statement.pdf?year=&lang=` ("{year} annual statement" / « Relevé annuel {year} »), the same months and figures as the CSVs (`SalesReportService.yearMonths`, shared). `lang=en|fr` decides the language (a download link can't send headers), else `Accept-Language`; French only when asked for. File names `northline-gst-summary-2026.pdf`, `…-fr.pdf` for French. The CSVs stay as they are (accountants import them).
- **Bilingual for real:** month names, amounts ($1,234.56 / 1 234,56 $), dates and every label in the request's language; the PDF's `/Lang` is `en-CA` / `fr-CA` for screen readers.
- **Province from the merchant (region-neutral):** the header reads the business's legal name, its trading name when different, its GST/HST number, "Province: {name}" in the language and, on the GST/HST summary, "Sales-tax rate in {province}: {rate}" / « Taux de taxe de vente {en Nouvelle-Écosse | au Québec} : … » — the province, its French preposition and the rate come from the region model (`MerchantPlaces`, `ProvinceProfile.nameIn`, `TaxRates`). No province, tax name or zone is in code; tax is labelled "GST/HST" (« TPS/TVH ») everywhere, as the CSV and CRA do. Months are cut in the business's time zone.
- **Schema/API additions:** `payments.api.MerchantBillingFacts.statementParty(merchantId)` → `StatementParty(legalName, displayName, gstNumber)`, implemented by the merchants module from Settings › Business (payments can't call merchants.api — merchants depends on payments). `payments.application.TaxStatements` (use case), `StatementDocument` (print model) and `StatementRenderer` (port). No migration.
- **Studio:** Reports' "Tax summary (GST)" now downloads the PDF in the Studio's language; Payouts › Tax documents offers "PDF · CSV" for both documents (labelled links, en/fr).
- Not done: Quebec's QST (TVQ) is not split out — the read model has one tax total per month (S-21); a QST column would come with a QST-registered marketplace setup. The statements are not signed or archived; they are generated on request from the live read model.

## 2026-09-30 — S-68 Server-sent events for messages and live orders (replace polling)

- **One stream per Studio tab, not one per screen.** `GET /api/v1/merchants/{merchantId}/live` (`studio` module,
  `@RequiresMerchant(VIEW)`) carries every live screen's signals: `message` (data `{"ref": threadId}`), `kitchen`
  (`{"ref": orderId|null}`), `orders` (`{"ref": orderId}`), plus `ready` on open. The Studio shell opens it
  (`useStudioLive` in `routes/b.$merchantId.tsx`) so a new message refreshes the Messages badge on every screen. The
  planned `…/threads/stream` (messaging's "Real time" note above) is folded into it.
- **Signals, not data.** Events carry a topic and an id, never message text, names or amounts; the browser invalidates
  the matching TanStack queries and refetches with its own permissions. A technician therefore learns nothing from the
  id of a thread they cannot open, and the stream needs no per-role filtering or payload versioning.
- **Where signals come from.** `StudioLiveEvents` (module listeners, after commit, so the refetch sees the change):
  `message.sent` → `message`; `order.placed` → `kitchen` for `orderType=food`, else `orders`; `order.packed` →
  `orders`; `order.accepted` / `order.ready` / `order.handed_off` / kitchen paused / resumed → `kitchen`. Help cases'
  messages publish `message.sent` too, so open cases refresh as well.
- **Across replicas: Valkey pub/sub** behind the `StudioLive` port, chosen by `northline.live.bus` (`LIVE_BUS`):
  `redis` (channel `nl:studio:<merchantId>`, message `<topic>[:<id>]`; the cloud default) or `memory` (local, test;
  refused under staging/prod like `MCP_STORE`). Nothing is stored: a signal published while no replica holds a stream
  for the business is dropped, and a reconnecting browser catches up with one refetch (below). Publishing failures are
  logged, never thrown — a live hint must not fail the change it reports.
- **Stream lifetime 10 minutes** (`LIVE_STREAM`), keep-alive comment every 25 s, `retry: 3000`. Ending the stream makes
  EventSource reconnect through the BFF, which re-checks the session, refreshes the access token and re-reads the
  membership — so a removed member stops getting signals within 10 minutes. The BFF relays `text/event-stream` as it
  arrives (S-130's `streaming-media-types`, S-135's relay fix); nothing changed there.
- **Fallback to polling.** While the stream is not open (connecting, dropped, refused, or no EventSource) the screens
  poll at their old intervals (threads 15 s, open thread 5 s, KDS 15 s, help cases 30 s / 10 s). While it is open they
  keep a 60 s safety refresh (`LIVE_SAFETY_MS`) for changes that send no signal (another member reading a thread, a
  courier assigned, a scheduled order entering its 60-minute window, a prep bump on another tablet). The first `ready`
  needs no catch-up; every later one (a reconnect) invalidates messages, kitchen and orders once. If the server closes
  the stream for good (EventSource `CLOSED`: 403, or an api without the endpoint), the Studio retries after 30 s.
- **Orders screen too.** Goods orders (`orders`) refresh the Orders board, nav badges and dashboard; the story names the
  KDS, but the same signal costs nothing for sellers.
- **Not done:** no signal for thread reads/assignments, courier assignment or prep bumps (covered by the 60 s safety
  refresh); no per-event replay (`Last-Event-ID`) — reconnects refetch instead. The Valkey adapter is tested against a
  real Valkey 8 (Testcontainers, two adapter instances standing in for two replicas); a multi-replica deployment has not
  been exercised.

## 2026-09-30 — S-65 Per-variant images, bundles and compliance documents in the product editor

### Schema additions (V180, additive)
- `catalogue.offers.listing_type` (`product` | `bundle`, default `product`).
- New `catalogue.bundle_items` (bundle offer, position, item offer, variant, qty 1–99), FK to `offers` with cascade
  from the bundle and an index on the item offer.
- New `catalogue.listing_documents` (purpose `spec_sheet` | `invoice`, file name, PDF/PNG/JPEG type, size, storage key,
  uploader), cascading from the offer.
- Function `catalogue.bundle_stock(bundle)`: the smallest of item stock / qty.
- Per-variant images use the baseline `catalogue.variants.image_set` (V005), which was never written before.

### Variant images
- **Model:** each variant row keeps its own media ids, main first, at most 9 (the listing's rule). An empty list means
  the variant "inherits" the listing's images; that is the cell's text, and an image count shows as "main + N" as in
  design 02 (`varRows.img`).
- **Rules:**
  - Uploads go through the existing `POST …/media`, and only the business's own media are accepted (422
    `variants[i].imageIds`).
  - Images count as visible to customers once the offer is approved (`MediaRepository.approved` now also looks at
    variants), the same as S-123.
  - Automated vetting runs its duplicate-image check on variant images too.
  - Adding, removing or reordering a variant's images on an approved listing is an `images` change (S-39 re-vetting).
- **Customers:** the product page's variants carry `images` (approved URLs only). The consumer app shows the chosen
  option's photos, falling back to the offer's, and the cart shows the variant's first photo.

### Bundles
- **A bundle is a product listing** (`ProductDetails.type = bundle`) that names 1–10 of the business's own product
  offers with a quantity, and a variant when the product has variants. It reuses the product editor: identity and
  category, images, price and fulfilment, compliance and vetting all work as for products.
- **What a bundle has and doesn't have:**
  - It has no identifier (GTIN), no variants and no stock of its own. The compact constructor forces this.
  - Its stock is computed wherever stock is read: the editor, the listings table, the cart, shop pages and the search
    indexer (`bundle_stock`). It is not stored.
  - Quick price and stock updates refuse a stock for a bundle ("A bundle's stock follows its items.").
- **Rules:**
  - Each item must be the business's own product. Bundles inside bundles and the bundle itself are refused.
  - An item needs a variant when its product has variants, and the same item may not appear twice.
  - Completeness needs at least 2 units ("Add at least two items to the bundle."), so a single item with qty 1 is not
    a bundle.
  - Submitting for vetting needs every item to be an approved listing.
  - A product that is part of a bundle can't be deleted (409 `listing_in_bundle`).
  - A bundle's contents count as its **price** for S-39 re-vetting: they are what the price buys.
- **Purchasable:** the cart and checkout see a bundle like any offer, through the `SellableOffers` port.
  - Its stock is the whole bundles its items allow.
  - Taking stock locks the item rows in a stable order, then decrements every item, or none when one is short.
  - Giving stock back (a checkout that fails after taking it) returns every item.
  - Order lines reference the bundle offer, and sales count against the bundle.
- **Who can create a bundle:** the design's type picker shows Product · Service · Bundle for businesses that sell
  both. A seller-only business sees Product · Bundle, because a bundle is goods. That is a deviation from
  `showTypePicker: !sellerOnly && !provOnly`, made because sellers are the ones with products to bundle.
- **Editor:**
  - The Variants tab becomes "Bundle contents": a picker of the business's own products (not bundles), the variant,
    the quantity, each item's price and stock.
  - The offer tab shows the derived stock read-only, plus "Bought separately: $X · Customers save $Y".
  - The listings table's meta line starts with "Bundle".
- **Not done:** a bundle has no customer-facing "what's in the box" list beyond its own description, and the consumer
  pages show it as a plain product. Sales of a bundle do not count toward its items' `sales_30d`.

### Compliance documents
- **Endpoints:** `GET|POST …/listings/{id}/documents` and `GET|DELETE …/listings/{id}/documents/{documentId}`, for
  products only.
- **Upload rules:**
  - Multipart `file` plus `purpose` (`spec_sheet` | `invoice`), the design's two buttons.
  - PDF, PNG or JPEG, judged by the file's first bytes, at most 10 MB and 10 per listing. These are the onboarding
    documents' rules and message ("Upload a PDF, PNG or JPEG under 10 MB.").
  - The file name is cut to its last path segment and at most 200 characters.
- **Storage and access:** stored through the catalogue's `MediaStorage` (now with `delete`) under the business's
  prefix. The files are private: members with VIEW can list and download them (`no-store`, as an attachment), and
  members with EDIT can upload and remove them. Customers never see them.
- **Not done:** the console's vetting queue doesn't show them yet, so a reviewer reads them through the database or
  storage. No virus scan beyond what the object store does (S-10's scanner applies when configured). Documents are
  not required for submission: the design shows them as optional uploads.

## 2026-09-30 — S-69 Studio bundle size and code splitting

Measured on the production build. Sizes are gzip -9 in kB (1000 B). "Initial JS" means the entry script, the chunks
`index.html` preloads, and everything they import statically. `apps/studio/scripts/bundle-budget.mjs` computes it.

| | before | after |
|---|---|---|
| entry script | 184.1 kB (597 kB raw) | 159.6 kB (500 kB raw) |
| initial JS (incl. preloads) | 184.1 kB, 1 file | 202.0 kB, 18 files (the landing preloads) |
| JS fetched to show the dashboard | 209.6 kB, 17 files | 202.1 kB, 19 files |
| Lighthouse mobile, dashboard: FCP · LCP · Speed Index | 2.6 s · 3.1–3.4 s · 4.3–4.5 s | 0.8–1.0 s · 2.9–3.0 s · 1.1–1.3 s |
| Lighthouse performance score | 79–86 (6 runs) | 79–94 (8 runs); 94 with TBT 140 ms |

- **The main chunk was already under the target.** The backlog's "~550 kB" is raw size: the entry was 597 kB raw but
  184 kB gzip, under the 250 kB gzip goal before this story. The work went into what was in it and into the
  dashboard's critical path.
- **Icons out of the entry.** The menu's 22 Phosphor icons (69 kB raw; every Phosphor component carries all six
  weights) were in `features/shell/nav.ts`, which the route guards import. The menu moved to `navMenu.ts`, which only
  the layout imports. `nav.ts` keeps the screen and path rules.
- **Loaders travel with their screen.** The router plugin's `codeSplittingOptions.defaultBehavior` groups `loader` with
  `component`, so the features' `api.ts` modules and their zod schemas leave the entry (kitchen, settings,
  availability, appointments …).
  - Exceptions: the `/b/$merchantId` layout and the dashboard keep their loaders unsplit (per-route
    `codeSplitGroupings`), so the business and the dashboard data are requested together with the session check, not
    after a chunk download.
  - `SETTINGS_TABS` moved to `features/settings/tabs.ts`, because the settings route's search validation pulled in the
    whole settings api.
- **The landing chunks are preloaded.** A small Vite plugin (`preloadLanding` in `vite.config.ts`) adds
  `<link rel="modulepreload">` for the layout and dashboard chunks and their imports. The browser fetches them while
  the entry runs. Signed-out pages pay about 40 kB gzip for this, deliberately.
- **Nothing blocks the first paint any more.**
  - `/config.js` is `defer`, and still runs before the module script.
  - Google Fonts are preloaded in `index.html` and applied by `main.tsx`. The usual `onload="this.media='all'"`
    trick is an inline handler, which the Studio's CSP (`script-src 'self'`) forbids.
  - `index.html` paints a boot screen ("Northline Studio" on the page background) that React replaces.
- **Budget in the build.** `pnpm --filter @northline/studio build` now runs `scripts/bundle-budget.mjs` after
  `vite build`. It fails when the initial JS passes 250 kB gzip and names the biggest files. The Docker image build
  runs the same script.
- **Lighthouse ≥ 90 is met only on a quiet machine.**
  - The FCP, LCP and Speed Index gains are stable across runs.
  - Total Blocking Time is not: it varied 140–500 ms with the load of the shared build machine, because the simulated
    4× CPU slowdown multiplies contention. Its long tasks are the entry's evaluation and the first render.
  - Moving FCP earlier with the boot screen also moves those tasks after FCP, where TBT counts them.
  - The method: Lighthouse 12 (mobile, simulated throttling) against the built dist served gzip by a fixture server
    with a populated dashboard.
- **Not done:**
  - zod (95 kB raw) stays in the entry: the session and business checks that every route runs parse with it, and a
    `zod/mini` split would mean two zod copies.
  - intl-messageformat's parser (38 kB raw) stays too: precompiling the messages would change `defineMessages`.

## 2026-09-30 — S-70 Remaining schema TODO constraints and indexes

Every `-- TODO indexes/constraints` comment of the V002–V015 baseline was checked against a database migrated to
main (V164). Each one is now one of three things:
- **done earlier:** it exists from a later migration;
- **V181:** added by this story;
- **dropped:** with the reason given below.

The baseline files keep their comments, because applied migrations are never edited, so this list is the reference.

| table | TODO | status |
|---|---|---|
| identity.users | unique(phone), unique(email) | done earlier (V020, partial unique) |
| identity.users | RLS: self or admin | **dropped**. Every query runs as the api's database role and the module services enforce "self or staff" (CurrentUser / staff role). Row-level security would need a role per request and session variables on every pooled connection, for no rule the services don't already apply. |
| identity.passkeys | index(user_id) | done earlier |
| identity.sessions | index(user_id) | done earlier (V021) |
| identity.sessions | TTL job | **dropped**. The rows are the sign-in history that Settings › Security lists (S-19), not live sessions; those are in Valkey with their own TTL. How long the history is kept is a retention decision for the privacy policy, not an index. |
| identity.household_members | PK(household_id,user_id) | done earlier |
| identity.addresses | index(user_id) | done earlier (partial, `deleted_at is null`) |
| identity.addresses | GiST(geom) | **V181** `ix_addresses_geom` (partial, geom not null) |
| region.regions | unique(province) | done earlier (V130, partial on `kind = 'province'`) |
| region.zones | GiST(polygon) | done earlier |
| region.feature_flags | PK(key,region_id) | done earlier |
| merchants.merchants | index(type,status), index(tier), unique(business_number) | done earlier |
| merchants.merchant_principals | index(merchant_id), role check per structure | done earlier (V031 trigger `trg_principal_role`) |
| merchants.merchant_categories | PK, count ≤ limit trigger | done earlier (V016 `trg_category_limit`) |
| merchants.merchant_members | PK(merchant_id,user_id) | done earlier |
| merchants.verifications | index(merchant_id,status), index(expires_at) | done earlier |
| merchants.storefronts | unique(slug), unique(custom_domain) | done earlier (V030, V085) |
| merchants.storefront_sections | unique(storefront_id,position), kind allowed for page_kind | done earlier (V016 `trg_section_kind`) |
| merchants.service_areas | PK(merchant_id,zone_id) | done earlier |
| catalogue.categories | index(parent_id), gin(search_terms) | **V181** `ix_categories_parent`, `ix_categories_search_terms` |
| catalogue.catalog_products | unique(gtin) | done earlier (V050, partial) |
| catalogue.catalog_products | GIN(attributes) | **dropped**. No SQL query filters on attributes: attribute facets and filters are served by the Elasticsearch read model (S-44). A GIN index would cost every catalogue write for no reader. |
| catalogue.offers | unique(merchant_id,sku), index(product_id) | done earlier |
| catalogue.variants | unique(offer_id,sku) | done earlier |
| catalogue.services | index(merchant_id), index(category_id) | **V181** `ix_services_merchant`, `ix_services_category`. The (merchant_id, sku) unique index is partial, so it can't serve "every service of a business". |
| catalogue.media | index(phash), index(owner_type,owner_id) | done earlier |
| food.menus | index(merchant_id) | done earlier ((merchant_id, sort)) |
| food.menu_items | index(merchant_id,available) | done earlier |
| food.item_modifiers | PK(item_id,group_id) | done earlier |
| availability.availability_rules | index(merchant_id,member_user_id) | done earlier: the leading columns of the unique (merchant_id, member_user_id, weekday, effective_from) |
| availability.time_off | index(merchant_id,starts_on) | done earlier |
| booking.bookings | index(merchant_id,starts_at), index(customer_id) | done earlier |
| booking.bookings | exclusion constraint on member/time | **dropped**. Reasons below the table. |
| booking.booking_events | index(booking_id,at) | done earlier |
| booking.quotes | index(request_id), index(merchant_id,state) | done earlier |
| booking.quote_lines | unique(quote_id,position), discount/amount check | done earlier (V040) |
| orders.carts | index(customer_id) | done earlier (unique partial) |
| orders.orders | index(customer_id), index(state,window_id) | done earlier |
| orders.order_lines | index(order_id), index(merchant_id,state) | done earlier |
| orders.delivery_windows | index(zone_id,starts_at) | done earlier |
| orders.group_orders | unique(link_code) | **V181** `ux_group_orders_link_code` (partial) |
| fulfilment.runs | index(courier_id,state) | **V181** `ix_runs_courier_state` |
| fulfilment.stops | index(run_id,seq) | **V181** `ix_stops_run_seq` |
| fulfilment.couriers | Redis Streams · TTL 24 h | **dropped**. Not a database item: live courier positions travel over Valkey pub/sub (S-52 tracking), never through this table. |
| payments.payment_intents | unique(stripe_pi) | done earlier |
| payments.escrows | index(release_at), index(merchant_id,state) | done earlier |
| payments.payouts | index(merchant_id,at) | done earlier: (merchant_id, created_at desc). The table has no `at` column. |
| payments.ledger_entries | index(account,at), append-only | done earlier (V061 trigger) |
| payments.refunds, payments.disputes | index(state) | done earlier |
| trust.reviews | unique(ref_id,author_id), index(target_type,target_id) | done earlier (unique per target type) |
| trust.quality_scores | PK(merchant_id,date) | done earlier |
| trust.flags | index(state) | done earlier |
| trust.points_ledger | index(user_id) | done earlier ((user_id, created_at)) |
| messaging.threads | index(ref_type,ref_id) | done earlier |
| messaging.messages | index(thread_id,at) | done earlier |
| messaging.notifications | index(user_id,sent_at) | **V181** `ix_notifications_user_sent` |
| messaging.tickets | index(state,sla_due_at), index(agent_id) | done earlier (V071) |
| i18n.translations | PK(key,locale) | done earlier |
| i18n.translations | published as CDN bundles | **dropped**. Not a database item. UI strings ship in the web bundles (`defineMessages`), and this table has no writer. |
| i18n.content_translations | PK | done earlier |
| developer.api_keys | index(key_hash) | done earlier (unique) |
| developer.webhook_deliveries | index(endpoint_id,at) | done earlier |
| developer.outbox | Debezium reads WAL; rows purged after publish | **dropped**. Obsolete: the Modulith JDBC registry (`events.event_publication`) is the outbox, Debezium was removed, and nothing writes this table. Dropping the table itself is left out: migrations here are additive. |
| developer.audit_log | append-only | **V181** trigger `audit_log_append_only`: UPDATE and DELETE are refused. The one exception is a transaction that sets `northline.audit_retention = 'on'` and deletes rows older than seven years. |
| developer.audit_log | nightly export to cold storage · 7-year retention | **dropped** as a schema item. The export and the purge job are operations work that no story covers yet. The trigger above already admits the purge. |

**Why the bookings exclusion constraint was dropped.**
- Customer bookings already serialise per team member and refuse overlaps: `CustomerBookingJdbc.lockAndCheckOverlap`
  takes an advisory lock and runs an overlap query.
- The availability holds in Valkey (S-55) go further, with travel buffers that a plain time-range exclusion can't
  express.
- The constraint would need `btree_gist`.
- Only the customer booking path runs that check. Rows written by other paths (quote acceptance, the dev seed, older
  data) aren't guaranteed overlap-free, and any overlapping row in a deployed database would make the migration fail
  at deploy. That is a risk an additive migration must not take.

**Tests.** `SchemaTodosTest` checks the V181 indexes on the migrated test database, the group-order link uniqueness,
and the audit log rules: no update, no delete, and a retention delete only past seven years with the setting.

## 2026-09-30 — S-72 Bulk import: validate image URLs and support full updates on re-import

- **One set of SSRF rules.** The S-33 `EgressPolicy` and `HostResolver` moved from the worker to the shared `platform`
  library (`ca.northline.platform`). They are joined by `EgressDnsResolver`, the HttpClient 5 resolver that
  checks every resolved address and pins the connection to them, and `EgressPolicy.refuseLiteral` for IP-literal
  hosts. The worker's webhook transport uses the same classes, so its behaviour is unchanged. The api's new
  `SafeRemoteImages` (port `RemoteImages`) fetches import images with them.
- **Fetch rules:**
  - https only, no credentials in the URL, public addresses only;
  - no redirects: a redirect could point anywhere, so the merchant gives the final URL;
  - no cookies, no retries;
  - timeouts of 5 s to connect, 10 s per read and 20 s in all;
  - at most 15 MB, the upload limit.
  - `IMPORT_IMAGES_ALLOW_LOCAL=true` allows http:// and loopback, for local development and the tests. The cloud
    profiles refuse to start with it, the same rule as `WEBHOOKS_ALLOW_LOCAL`.
- **The `image_urls` column.** The three product templates gain it: up to 9 links, main first, separated by spaces,
  new lines or `|` (commas would split a CSV cell). Validation fetches every distinct URL once, 8 at a time on virtual
  threads, at most 500 per file ("Up to 500 image URLs per file."). It runs outside a database transaction, so no
  connection is held while fetching. A row whose image fails becomes an error row, with the first failing image's
  message:
  - "Image URL unreachable" — the design's text: an HTTP error, a timeout, a redirect, or an image too large;
  - "Image URL must be a public https:// link" — refused by the SSRF rules;
  - "Image URL is not a JPG or PNG image";
  - "Image URL is under 1000 px on the longest side";
  - "Image URL is not a valid link";
  - "Up to 9 image URLs per row".

  The report is kept in row order.
- **Importing images.** On import the URLs are fetched again and stored through the normal media upload, as the
  business's own images in order, and the listing's image source becomes `own`. An image that no longer loads at that
  moment is left out instead of failing the whole import; the completeness meter then shows it missing. Variant
  rows' images go to the listing: the first row of a parent supplies them. Per-variant images arrive with S-65.
- **Full updates on re-import.**
  - **Category templates:** a row for an existing SKU now updates every column it fills in: title, GTIN, brand, MPN,
    category, attributes, price, stock and images. An empty cell keeps the current value, so a sheet exported with
    blanks never wipes data.
  - **Validation for updates:** an update row checks the attributes it names against the listing's category, or the
    new category when the row changes it. Price and stock may be empty.
  - **Services template:** updates name, category, pricing mode, price, duration, buffer, what's included and
    instant book in the same way.
  - **Price & stock template:** still changes price and stock only.
  - **How it is saved:** updates go through the editor's use cases (`EditProduct`/`EditService.update`), so the
    editor's validation, S-39 re-vetting and events apply as in the Studio.
- **Variants on re-import.** Rows whose `parent_sku` is an existing product listing no longer fail ("Parent SKU
  already exists …").
  - Each row updates the variant with its SKU (price, stock, GTIN, and the name when size or colour is filled) or
    adds a new variant. A new variant needs a price and stock.
  - The first row's listing-level columns apply to the listing.
  - The offer shows the lowest variant price and the total stock, as the S-35 sync does.
  - Variants missing from the file are kept, not removed.
- **Not done:** the row errors stay English, like the existing import messages, which the design shows verbatim.
  Nothing has fetched a real third-party image host: the tests use WireMock on loopback, and the SSRF refusals are
  unit-tested with made-up addresses.

## 2026-09-30 — S-74 Appointments calendar: 'Open slot' and 'Held for quote' cells

- **One endpoint for both cells.** `GET /api/v1/merchants/{merchantId}/calendar-cells?from=<date>&days=1..31&durationMin=15..720`
  (VIEW; defaults 7 days and 60 min) returns `{ openSlots: [{startsAt}], quoteHolds: [{quoteId, requestId, ref,
  customerName, startsAt, durationMin}] }`.
  - It lives in the `studio` module because it composes two modules: availability already depends on booking, so
    booking can't call availability.
  - The day view asks for one day, the week view for seven. The jobs keep their own endpoint, and a failure of the
    cells leaves the calendar as it was.
- **"Open slot" is what a customer could book.**
  - It comes from `availability.api.ProviderSlots`, the same calendar customers book from (S-55): weekly hours minus
    time off, jobs, connected calendars and other customers' slot holds, with travel buffers and the booking rules
    (notice, cut-off, horizon).
  - A run of consecutive free start times shows once, at its first time ("Open slot 10:00"), at most 3 a day. The
    design shows one or two per day, not one per interval.
  - The default job length is 60 minutes; the client can pass another. Past days have no open slots.
- **"Held for quote · M. Tran" is a sent quote's proposed time.**
  - It covers the latest version of a quote that is still open: sent or viewed, not past `valid_until`, with
    `proposed_at` in the range (`BookingCalendar.quoteHolds`).
  - The customer shows in the short form (`PersonDirectory.shortName`). Without a name, the cell reads "Held for
    quote".
  - The cell is information only: it is not a booking and doesn't take the time from other customers. The design
    marks it dim like the open slot.
- **Rendering.** Both cells use the design's dim style (`neutral-200` background), as `<div>`s rather than buttons.
  They sit between the jobs in time order, and Sunday's column appears when it has any. Copy: en "Open slot" /
  "Held for quote · {name}", fr "Plage libre" / "Réservé pour un devis · {name}".

## 2026-09-30 — S-75 Storefront analytics (visits, bookings) and the provider-funded reward

- **Visits are a daily count, nothing else.** The consumer's provider and restaurant pages call
  `POST /api/v1/public/storefronts/{slug}/visits` (204) once per tab session; the browser decides that with a
  `sessionStorage` key (`nl.visit.<slug>`), which is cleared when the tab closes and is never sent anywhere. No cookie,
  IP, user agent, user id or referrer is stored or logged by the feature: `merchants.storefront_visits` is
  `(merchant_id, day, visits)`, with `day` in the business's time zone (S-134). Crawlers, link previews and monitors that
  name themselves in the user agent (and requests with none) are not counted; unpublished or unknown pages are 404. The
  endpoint is unauthenticated, so the count can be inflated by a script; it is a guide for the business, not a billing
  number. The edge's general rate limits are the only protection (no per-IP limit, which would need the IP).
- **Stats** (`GET /api/v1/merchants/{merchantId}/storefront-stats`, VIEW): visits over the last 30 days of the business,
  the per-day series, and "booked" = bookings made in that window (not cancelled; `BookingInsights.bookingsMade`) plus
  orders placed (`OrderInsights.volume`). The rate is booked / visits in basis points, null with no visits. Bookings and
  orders aren't attributed to a visit (that would need tracking), so the rate counts every booking, including ones that
  came from search or a repeat customer. The Studio page shows it in the design's lede ("1,204 visits last 30 days · 8.6%
  booked").
- **Provider-funded reward.** V012's `trust.merchant_rewards` gains `active`, `ends_on` (a date in the business's time
  zone: the design's "until Oct 1"; the baseline `ends_at` instant can't say that), `label` (≤ 60, "brake jobs"; empty =
  everything), `updated_at/by`, a 2-or-3 multiplier check and one row per business. `GET/PUT
  /api/v1/merchants/{merchantId}/reward` (VIEW / MANAGE: the owner pays for the points), `GET
  /api/v1/public/merchants/{merchantId}/reward` for the public page (204 when nothing runs today). Rules: 2× or 3×, an end
  date from today to 90 days out; switching off is always allowed and keeps the terms. Each change is audit-logged
  (`reward.started` / `reward.stopped`). The public provider page shows it as a credential tag.
- **Not built: crediting the points.** Nothing writes `trust.points_ledger` yet (S-58: no earning rules), so a running
  reward is shown but credits nothing, and `budget_cents`/`spent_cents` stay unused. The earning job will read
  `trust.api.ActiveRewards.running(merchantId)`.
- **Tests:** `StorefrontStatsApiTest` (people vs crawlers, only the number stored, 404 for unpublished pages, the 30-day
  window and cancelled bookings, 403s, the reward's on/off, public view, audit and every validation message);
  `StorefrontStats.test.tsx` (lede, reward switch, 422 next to the field, read-only technician, French);
  `provider.test.tsx` (one beacon per tab session with no body, the reward tag in en and fr).

## 2026-09-30 — S-76 Publishable keys for the website embed snippet

- **Key.** One publishable key per business, `pk_live_` + 32 url-safe characters (`developer.publishable_keys`, V183).
  It is public by design: it sits in the business's page source and only names the business. So it is stored as is
  (the secret `nl_live_` API keys stay hashed) and gives no access to anything beyond the published page. The
  `pk_test_` prefix isn't modelled: every environment issues `pk_live_` keys, the same rule as the secret keys.
- **Studio.** `GET/POST /api/v1/merchants/{merchantId}/settings/publishable-key` (VIEW / MANAGE): POST issues the
  first key or replaces it. Replacing stops the old key at once, after a confirm dialog that says so. `PUT …/origins`
  sets the websites the embed answers on. Settings › API "Embed your store" and the Business page's "Embed code"
  dialog show the real snippet (`<script src="<site>/embed.js" data-store="<slug>" data-key="pk_live_…" async>`) and
  replace the design's `pk_live_…` placeholder; the allowed websites are edited in Settings › API. Issuing, replacing
  and changing websites are audit-logged. The script URL is `CONSUMER_ORIGIN` + `/embed.js`
  (`northline.developer.embed.site-origin`; the api already received `CONSUMER_ORIGIN` from the chart).
- **Allowed websites.** Each entry is a browser origin: https only (http only for localhost), no path, query or
  credentials, at most 10. A bare host gets `https://`, and a default port is dropped. An empty list means any website.
  The check uses the browser's `Origin` header. That stops copying the snippet to another site in a browser; it is
  not a secret (a script outside a browser can send any Origin, and the answer is public page data anyway).
- **Embed.** `web/apps/consumer/public/embed.js` (served by the consumer site, no build step, no dependencies):
  - It finds its `<script data-key>` tags and calls `GET <site>/api/v1/public/embed?key=&store=` without cookies,
    then inserts the page's Book / Order button (its CTA label, in the page's language, en or fr) in the brand colour,
    with readable text. The button opens the business's Northline page.
  - The endpoint (merchants module, through `developer.api.EmbedKeys`) answers only for an active key of that page's
    business, on a published page, from an allowed website. Otherwise it returns 404, or 403 for a website that isn't
    allowed. Answers carry `Access-Control-Allow-Origin` (`*`, or the allowed origin with `Vary: Origin`) and
    are cached a minute.
  - Store pages have no consumer page yet (S-49), so their button opens the site's home.
- **Not done / never exercised.** The script has run only under jsdom in vitest and the endpoint only under MockMvc.
  It has not been tested on a real third-party site, through the consumer-bff relay (CORS headers are expected to pass
  through the gateway unchanged) or across browsers. There is no inline iframe mode (the consumer site sends
  `X-Frame-Options: DENY`). Embed bookings are not attributed to the sales report's `embed` source yet.
- **Tests:** `EmbedKeyApiTest` (issue, roll, old key 404, other business's key 404, unpublished 404, website limits with
  CORS headers, every validation message, 403s, audit), `embed.test.ts` (consumer: request, button, language, colour
  contrast, inactive key, single mount), `embed.test.tsx` (studio: snippet, create, confirm replace, websites with
  the 422 message, read-only role, French).

## 2026-09-30 — S-77 Seed data photos for kitchen items and listings

- **What #5 already did.** PR #5 bundled eight menu-item pictures (`server/api/src/main/resources/seed-media/kitchen`).
  `LocalKitchenPhotoStore` serves them for V108's `seed/<name>.jpg` keys until a photo is uploaded under the key.
  That covers every seeded menu item: V108 is the only seed with menu items. Nothing did the same for listings: V104's
  NL-P-88120 named `seed/nl-p-88120-{1,2,3}.jpg` with no bytes, and the other seeded listings had no images.
- **Completed here.**
  - The catalogue's `LocalMediaStorage` gets the same fallback from `seed-media/catalogue`: only lower-case
    `seed/<name>.jpg` keys, and an upload under the key wins.
  - 23 pictures are bundled (≈ 820 KB): NL-P-88120's three, plus one per seller-owned listing.
  - The dev seed `V184__seed_listing_photos.sql` (db/seed-dev, `local` profile only) adds a main image to Prairie
    Wrench Parts' brake pads and oil (V104) and to all 19 neighbourhood-shop listings (V113), as `own_images`.
  - Every live seeded listing now shows a picture in the Studio and on the consumer site.
  - The cabin air filter (V104) stays a draft without photos on purpose: it is the editor's missing-photo example.
- **They are sample pictures, not photographs.** They match #5's style: a colour gradient, the item's name and
  "Sample photo · dev seed", generated with Pillow (script in the PR description). Photo hosts (Wikimedia Commons and
  others) are not reachable from this environment, so no licensed photographs were fetched. Replacing a file with a
  real photo under the same name needs no other change. They are classpath resources of the api jar; the cloud
  profiles never read them, because the object-store adapters have no seed fallback and db/seed-dev is refused
  outside `local`/`test` (S-16).
- **Not covered:** booking job photos (V103 `seed://tires-done.jpg`) and onboarding documents (V102 PDFs). They are not
  listings or menu items and still show their placeholders.
- **Tests:** `LocalMediaStorageTest` (fallback, an upload wins, unknown or unsafe keys) and `SeedPhotosTest`
  (`local` profile: every seeded menu item and listing image key has bytes, every live seeded listing has an image,
  the public media endpoint serves one as image/jpeg).

## 2026-09-30 — S-90 Console shell with role switch, role-filtered nav and denied screen

- **App:** `web/apps/console`, a TanStack Router SPA like the Studio (static nginx image, port 8080; dev server :3200).
  Nothing in the console is public or indexed, so the consumer site's SSR buys nothing here. Plan and conventions for
  the later stories: `docs/CONSOLE_PLAN.md`.
- **console-bff** is the bff jar under a `console` profile (port 8083, cookie `__Host-NL_CONSOLE`, client `console-bff`,
  scopes `openid profile console`), like the consumer-bff. **Staff only, with a second factor:** northline-auth puts the
  platform `roles` in the ID token for the `console` scope (not for other clients). The BFF ends any sign-in whose ID
  token lacks `staff` or `acr=mfa`: it revokes the refresh token, drops the session and redirects to
  `/sign-in?error=staff_only|mfa_required`. Every later request re-checks, and a session that fails gets a 401. The api
  enforces the same anyway; the gate keeps non-staff out of the console entirely.
- **northline-auth:** `console-bff` is now always registered; `CONSOLE_BFF_SECRET_HASH` is required in the cloud (was
  optional until E-8). Its unauthenticated or single-factor authorization requests go to the console's own sign-in page
  (`northline.auth.console-login-page`, `console-clients`). `CONSOLE_ORIGIN` joins the JSON API's allowed origins and the
  WebAuthn origins.
- **Staff role model (schema V190).** `identity.platform_roles` accepts `trust_safety`, `dispatch`, `finance`, `support`
  and `analyst` next to `staff` and `admin` (CHECK widened only). It also gets `granted_by` and an index for platform
  audit rows (`merchant_id IS NULL`). `staff` stays the on/off switch for the console: every console user holds it plus
  one or more console roles. `StaffRole` (shared.security) maps roles to `ConsoleScreen`s and `ConsoleAction`s. The
  screens are design 03 `ROLES.views`. The actions are `ROLES.can` plus the Data Table's `CAN`, which adds `dispatch`
  for dispatchers and `support` for support and T&S. Admin has every action. Profile and on-call are open to any staff
  member, even with no console role.
- **Enforcement:** `@RequiresConsole(screen, actions)` on every `/api/v1/console/**` handler. `StaffAccessInterceptor`
  enforces it, and a handler without it is denied (`ConsoleEndpointsTest`). The path-level rule (role `STAFF` + `acr=mfa`)
  stays. 403 codes: `mfa_required`, `not_staff`, `role_not_held`, `insufficient_role`, `unguarded_endpoint`. The three
  existing console endpoints now carry their screen: trust flags → `trust` (+ `decide`), registry reviews → `verify`
  (+ `verify`), tax reconciliation → `finance` + `payouts`. Their tests now use role-bearing staff tokens
  (`TestJwt.staff(id, StaffRole...)`).
- **Role view:** the console acts with **one** held role at a time. It sends it as `X-Console-Role` and the api
  authorizes with that role alone. The default is the remembered view (localStorage) if still held, else the first
  held role in design order. The design shows one role name in the top bar, so a union view was not added. Switching
  records `console.role_view_switched` in `developer.audit_log` (`merchant_id` null, `role` = the active roles), as the
  design says "Logged". After a switch the console stays on the screen when the new role opens it, else goes to the
  overview (design).
- **Denied screen:** design `v.denied` renders the overview with the banner. The console shows the banner, then the
  overview, at the requested URL, so switching role lets the person in without navigating. The sidebar highlights
  nothing then.
- **Top bar:** "Ops · Alberta + BC pilot" is built from `GET /api/v1/geo/regions`: live provinces by name, pilot
  provinces by code + "pilot". The design's "Admin · Ops" (role · team) shows the role only: teams aren't modelled.
  "← Direction" (the design file's own navigation) is left out. The global search pill is there as designed. The design
  shows no results, so no search endpoint was added; CONSOLE_PLAN lists it as missing, with no owning story.
- **Sign-in page:** design 03's copy and steps (email or mobile → passkey / authenticator / backup code → "Signed in."),
  using northline-auth's JSON API through `@northline/auth-kit`, then the console-bff hand-off. **Staff SSO (Okta /
  Google Workspace) is not built.** The design's two SSO buttons are left out, and the copy that mentions SSO is kept
  verbatim (it is the target). "Welcome back, Priya. 7 verifications and 3 disputes are waiting." shows only the
  greeting: the counts need the S-91 / nav-badge data.
- **Account menu:** design items in order. "My audit trail" has no "today · 6" count yet (S-96). The role list shows
  held roles only, with "N views · actions" from the api's grant; admin shows "all views".
- **Not modelled yet:** co-signatures (two admins for a province, T&S lead for suspensions, a second approver for
  refunds > $500), per-role factor rules (passkey / app 2FA / SSO), nav badge counts, granting roles in the UI (SQL in
  the runbook until S-96).
- **Paths:** API & webhooks is `/integrations`, because `/api` belongs to the console-bff on the console host. The dev
  server proxies `^/api/` (not the `/api` prefix) for the same reason.
- **Panels:** the console uses the Studio's `--color-surface: var(--color-bg)` (decision of 2026-09-29), so its sidebar
  and panels match the Studio's.
- **Dev:** seed V191 adds Priya Natarajan (staff, every console role, backup codes `priya-n-00001…10`). Dev auth (api,
  `local`) now mints the `roles` claim from `identity.platform_roles` through a new `shared.security.PlatformRoles` port
  (identity implements it). `make up SERVICES="api console"`, `run-console[-dev]`, `run-bff-console`.
- **Deploy:** chart apps `console` (static, enabled) and `console-bff` (bff image + `console` profile, port 8083); the
  console host routes `/api`, `/bff`, `/oauth2`, `/login` to the console-bff; `CONSOLE_BFF_SECRET` in the secret maps,
  Terraform (created empty), the kind ESO store; Argo CD images and `promote.sh` (console-bff = the bff's digest); CI
  image matrices (GitHub, GitLab) and the web check build the console; Grafana dashboard `northline-console-bff`.
- **Tests:** api `ConsoleRolesApiTest` (me, role view + audit row, role not held, 422, screen/action refusals, role view
  narrowing, admin), `ConsoleEndpointsTest`, `StaffRoleTest`; auth `TokenClaimsTest` (console ID token roles, console
  sign-in page for no / single-factor sessions), `OAuthClientsStartupTest`; bff `ConsoleBffTest` (client, staff-only
  gate with revocation, relay with role header and stripped credentials, CSRF header-only, sign-out, other clients
  absent), `ConsoleBffOpenApiTest`, `SessionCookieSettingsTest`; console vitest (shell, role filter, denied, role switch,
  language, sign-in hand-off, `next` safety, route list).

## 2026-09-30 — S-91 Overview: GMV and health dashboard

- **One endpoint, composed in the console module:** `GET /api/v1/console/overview?province=&market=` (screen
  `overview`, every role). It reads each figure through a new query API on the module that owns the data:
  `orders.api.MarketplaceOrders`, `booking.api.MarketplaceBookings`, `payments.api.MarketplaceMoney`,
  `merchants.api.MarketplaceMerchants`, `catalogue.api.VettingQueue`, `trust.api.TrustQueues` and
  `fulfilment.api.FleetStatus`. There is no cross-module SQL (S-37). Two shared kernel types were added:
  `MerchantScope` (every business, or a set) and `Backlog` (count + oldest).
- **Region-aware:** a province (two-letter code) and/or a market id from the region model resolve to that place's
  businesses (`MarketplaceMerchants.idsIn`, by `merchants.province` and the market's city), and every module filters
  by those ids. Unknown places are a 422, with French in the catalogue. The design has no filter. Two selects ("All
  provinces", "All markets", from `GET /api/v1/geo/regions`) sit next to the kicker and write `?province=&market=`.
  The fleet stays platform-wide: couriers and runs have no market until zones carry one (S-81/S-84). Pools are listed
  per live market in scope, so with no filter the design's single "tonight's {city} pool" line becomes one line per
  live market.
- **Definitions** (CONSOLE_PLAN § Overview):
  - The periods are rolling 7 days, not calendar weeks, so "+9% w/w" compares like with like. The chart's W1…W12
    are 7-day periods ending now.
  - GMV is goods plus services. Goods are order lines (shop and food) by placed time. Services are booking prices
    by booking time.
  - Net revenue is the ledger's `revenue` account. On time means pooled orders delivered before their window ends.
  - The dispute rate is disputes opened ÷ orders plus bookings. The "goods" share is orders ÷ orders plus bookings.
  - Food counts as goods: the chart has two colours, goods and services.
- **Shown differently from the design:**
  - "avg delivery fee paid · cost $4.10" drops the cost: Northline has no delivery cost model yet (zone economics:
    S-81/S-84).
  - "Kafka lag" shows records (`kafka_consumer_fetch_manager_records_lag_max`), not seconds: lag in time isn't
    measured.
  - The work queue lists all six items for every role. Items for screens the active role doesn't open have no link.
  - SLA texts ("SLA 2 d", "SLA 4 h") are the design's copy, not computed policy.
  - "1 stuck delivery run · R-608 · 12 min" shows the minutes only: runs have no label in `fulfilment`.
  - Missing figures show "—", never zero (a province without deliveries has no on-time rate).
- **System health is behind a port** (`console.application.HealthSignals`, `northline.console.health.provider`):
  - `none` (the default everywhere) shows every tile as not measured.
  - `prometheus` runs one PromQL instant query per tile against any Prometheus-compatible query API (Grafana Cloud /
    Mimir, Amazon / Google / Azure managed Prometheus, self-hosted), with an optional bearer token. The queries and
    thresholds are configuration.
  - A failed query makes that tile unknown and never fails the overview.
  - **Never run against a real store:** it is tested only against a WireMock stand-in of the Prometheus HTTP API.
  - New variables: `CONSOLE_HEALTH_PROVIDER`, `CONSOLE_HEALTH_PROMETHEUS_URL`, `CONSOLE_HEALTH_PROMETHEUS_TIMEOUT`,
    and the optional secret `CONSOLE_HEALTH_PROMETHEUS_TOKEN` (runbooks, `.env.example`, Helm secret map, Terraform).
  - The courier app tile comes from the database (couriers offline during a run).
  - The "Tracking WS" tile reads a new gauge, `northline.tracking.streams` (open order-tracking SSE streams per
    replica, from `orders.application.TrackingStreams`).
- **Stuck runs:** a run is stuck when a stop is 10 minutes past its ETA and not reached. **Below a floor** means the
  latest quality score is under 80 (the Trusted floor in design 03's tier rules).
- **No schema change.** The overview refreshes every minute and keeps the previous figures while a filter loads.
- **Tests:**
  - `ConsoleOverviewApiTest`: every figure for one province no other test uses (Yukon) — GMV per period with
    refunded lines and cancelled orders left out, revenue net of a dispute, on time, dispute rate, delivery fee,
    work queue, live — plus the whole-platform shape, 422s, and 403s for no role / no MFA / not staff.
  - `PrometheusHealthSignalsTest` (WireMock: vector and scalar results, thresholds, bearer token, failures, no URL).
  - Console vitest `overview.test.tsx`: design copy and formats, links by role, filters writing the query with the
    role header, empty figures, error and retry, French money.

## 2026-10-01 — S-89 Checkout sets orders.fulfilment_mode and customer_eta

- **Already done by S-51 / S-57; verified, with the gaps closed.** The shop checkout (`CheckoutJdbc.createOrder`)
  writes `fulfilment_mode = 'delivery'` (pooled run or direct courier; the shop has no pickup) and no `customer_eta`.
  The food checkout (`FoodCheckoutJdbc`) writes `delivery` | `pickup` from the customer's choice and, for pickup,
  `customer_eta` = the scheduled window's start, else placed + the kitchen's pickup-ready minutes. The kitchen display
  (S-38/S-64) shows a pickup as "Pickup · customer N min away" and its ready card as **Handed to customer**; hand-off
  moves a pickup order to `delivered` (S-57's `FoodOrderProgress`), a courier one to `picked_up`.
- **`customer_eta` means the pickup customer's arrival** (V091's comment and the IMPLEMENTATION_PLAN contract), not a
  delivery ETA. Delivery ETAs belong to the courier's run (S-86/S-88), so a delivery order never has one.
- **Gap closed — "kitchen treats empty as delivery":** rows written before checkout existed (older dev seed, fixtures)
  had no mode. **V200** fills them with `delivery`, makes the column `NOT NULL DEFAULT 'delivery'` and adds
  `chk_orders_customer_eta_pickup` (`customer_eta` only on pickup orders). The kitchen feed's `coalesce(…, 'delivery')`
  is gone.
- **Not done:** pickup for shop orders (pickup-only offers can't join a run and the shop checkout offers no pickup;
  that is a product decision, not this story's).
- **Migration range:** the fulfilment workstream takes **V200–V209** (main was at V183, the console holds V190–V199);
  IMPLEMENTATION_PLAN updated.
- **Tests:** `FoodOrderingApiTest.pickupSetsTheModeAndTheCustomersArrivalAndTheKitchenHandsItToTheCustomer` (pickup
  checkout → mode + arrival time → kitchen display "customer arriving" → accept, ready, hand-off → order and tracking
  `delivered`), `…everyOrderHasAModeAndOnlyPickupsACustomerArrival` (V200 default and checks);
  `CartCheckoutApiTest` placing asserts a shop order's `delivery` mode and empty `customer_eta`.

## 2026-10-01 — S-78 Goods delivered/confirmed drive escrow release

Branch `fulfil/s-78-delivery-escrow`, **stacked on S-89** (`fulfil/s-89-fulfilment-mode`, V200) so the migrations
merge in order.

- **Events, in order:** fulfilment's **`delivery.completed`** (`fulfilment.api.DeliveryCompleted`, topic
  `fulfilment.delivery`, key = order id; the courier's drop-off with `proof` = `photo` | `signature` | `pin`) →
  orders moves the order to `delivered` (`delivered_at`, new `delivery_proof`) and publishes **`order.delivered`**
  (`orders.api.OrderDelivered`, topic `orders.order`). The customer's confirmation moves it to `confirmed` (new
  `confirmed_at`) and publishes **`order.confirmed`**. Both are ids only (schemas `fulfilment.delivery_completed.v1`,
  `orders.order_delivered.v1`, `orders.order_confirmed.v1`). S-78 defines `delivery.completed`; the courier's
  proof-of-delivery that publishes it is S-86's.
- **Escrow follows the order events** (`orders.application.GoodsEscrowRelease`, an `@ApplicationModuleListener`):
  `order.delivered` → `EscrowLifecycle.fulfilledIfHeld("order_line", line, deliveredAt)` for each line that isn't
  refunded — capture, and the **7-day window starts at the drop-off** (`EscrowKind.GOODS`, unchanged);
  `order.confirmed` → new `confirmedIfHeld` per line — **released at once**. Wired from orders, not payments, because
  orders already depends on payments (payments → orders would be a cycle), as food's `KitchenEscrowRelease` does
  for hand-off. The order row is locked (`select … for update`) by the delivery, the confirmation and both escrow
  handlers, so a drop-off and a confirmation of the same order never interleave.
- **Module direction:** orders now depends on `fulfilment.api` (the event). fulfilment depends on nothing; it must
  not depend on orders, which depends on food, which reads `fulfilment.api.CourierPickups` (S-64) — the cycle
  Modulith would reject. S-86 keeps that direction.
- **Delivery fee (the S-51 gap):** the fee's manual-capture PaymentIntent (`order_delivery`) is now **captured on
  delivery or confirmation**: new `EscrowLifecycle.captureDeliveryFee(orderId, at)` locks the reference's current
  PaymentIntent, captures it at Stripe (key `nl1:capture-delivery:<order>:<intent>`) and posts
  `stripe_balance` ↔ `revenue` + `tax_payable` (the checkout's tax calculation, `ref_type = order_delivery`) under
  `ref_type = order_delivery`. No escrow row: the fee is Northline's from the start and never transferred.
  Idempotent (state `captured` is skipped).
- **Customer confirmation:** `POST /api/v1/me/orders/{orderId}/confirm` (single-factor sessions, like tracking). Only
  the order's customer (404 otherwise); allowed from `picked_up` or `delivered` — confirming an order the courier
  has picked up but not yet marked counts as its delivery (`delivered_at` = now); before pickup 409 `not_delivered`
  "Your order hasn't been delivered yet."; cancelled/refunded 409 `order_closed`. Repeating it answers the order
  unchanged. **No Idempotency-Key:** the request carries no amount and is idempotent by the order's state, so a
  retried tap can't release twice.
- **Disputes pause the release** (S-60/S-80, unchanged rules, now tested on the goods window): a refund case or
  dispute on a line's escrow puts it `disputed`; the release job skips it past the 7 days; a denied refund or a
  merchant win resumes it, and the next release job pays it.
- **Tracking (S-52):** the order view adds `deliveryProof`, `confirmedAt`, `canConfirm` and `paysShopsAt` (delivery
  + 7 days while unconfirmed); `order.delivered` / `order.confirmed` push the SSE stream. Consumer page: "Delivered
  with photo proof.", "Shops are paid {date} unless you confirm sooner or report a problem.", **Got everything**
  (our copy, en + fr-CA; the design's step "you confirm, shops paid" has no button text).
- **Schema (V201):** `orders.orders.delivery_proof`, `confirmed_at` (+ check: only on confirmed/refunded orders).
- **Not done:** partner webhook `order.delivered` stays in `NOT_YET_PUBLISHED` (the event is per order, the
  webhook is per business — needs per-shop payloads); the delivery-fee sale isn't reported to Stripe Tax (the
  `tax_transactions` row needs an escrow; the ledger has the tax payable); a delivery-fee authorization isn't renewed
  before it lapses (runs are at most 2 days out; S-11's renewal covers escrows only); cancelling an order doesn't
  cancel its delivery-fee hold (no cancel flow exists).
- **Never run against real Stripe:** the delivery-fee capture ran against the fake gateway only.
- **Tests:** `DeliveryEscrowTest` (movable application clock, `support.MovableClock`; market "Deliveryville"; orders
  placed through the real cart → checkout → place, `support.ShopOrderFlow`): the drop-off starts each line's 7-day
  window and captures the fee (revenue + GST in the ledger), nothing releases a minute before the window ends and
  everything one minute after; confirmation releases at once, repeats and a late drop-off change nothing;
  confirming on the way counts as delivery, not before pickup (409 + message), others 404, guests 401, cancelled
  409; a refund case opened on day 2 keeps that line held past day 8 while the other line releases, and the denied
  case releases it; a replayed drop-off keeps the first time; a food order is delivered from `picked_up`.
  vitest: the consumer's confirmation (proof note, pay date, error then success, French).

## 2026-10-01 — S-86 Pooled run planning and dispatch service

Branch `fulfil/s-86-dispatch`, **stacked on S-78** (#116, itself on S-89 #115): it publishes S-78's
`delivery.completed`, and V202 must follow V201. Contract for the courier app (S-87) and the console (S-81):
`docs/runbooks/fulfilment.md`, summarised in `docs/CONSOLE_PLAN.md` § Delivery and dispatch (the console's plan arrived on
main with S-90 while this was in review).

- **Module direction:**
  - orders hands deliveries over through the new inbound port `fulfilment.api.DeliveryRequests`, called from
    orders' listener `DispatchHandover` on `order.placed` (once per shop, so pickups accumulate), `order.packed`, and
    the kitchen's `order.accepted` (ready-by) and `order.ready` (food packed).
  - orders follows fulfilment's events back.
  - fulfilment reads no orders table and depends on no module that depends on it. food reads `CourierPickups`, and
    orders depends on food, so fulfilment → orders would be a cycle. fulfilment depends only on identity (courier
    names), merchants (shop places) and shared.
  - The drop-off address is copied into `fulfilment.deliveries.dropoff`. Shop orders get it from
    `identity.api.DeliveryAddresses`, food orders from the checkout snapshot. It is cleared 30 days after the
    delivery ends.
- **Planning** (`DispatchService`; every minute in `DispatchJobs`, or `POST /console/fulfilment/plan`):
  - Pooled windows are planned once the customers' cut-off (`orderBy`) has passed: "Tonight's run created from
    orders".
  - Orders whose shops haven't packed still join, because shops pack by `packBy` (the Studio's "Pack by", already
    shown since S-49/operations) and the courier's pickup refuses an unpacked shop (409 `not_packed`).
  - A window holds at most `max-drops-per-run` (12, ours) drop-offs per run, then is split into parts.
  - Direct goods orders get their own run once every shop has packed. Food deliveries get one once accepted, with
    the pickup due at ready-by.
  - The planner holds a transaction-level advisory lock, so replicas never plan one window twice.
- **Stop order, recorded as `nearest-neighbour-v1`:**
  - pickups first, then drop-offs (a pooled run collects every sealed bag before delivering);
  - shops by nearest neighbour from the westernmost located shop; drop-offs by nearest neighbour from the last shop;
  - unlocated places after the located ones: shops by id, addresses by postal code, street, order id;
  - ETAs: 3 min/km straight line, at least 4 min, 8 min when unlocated; 5 min per shop, 3 min per door.
  - Deterministic; numbers are ours. Customer addresses aren't geocoded (S-49/S-51), so pooled drop-offs are in
    postal order today; food addresses carry coordinates.
- **Couriers:**
  - `fulfilment.couriers` gains `market`, `active` and `last_assigned_at`. Ops onboards a courier (an existing
    identity user) and schedules shifts (at most 12 h).
  - The courier starts a shift from 15 minutes before it (status `available`) and ends it when no run is open.
  - Assignment goes to the market's courier who is on a shift (ending after the run starts), available and longest
    without a run. It happens 60 min before a pooled run starts, at once for a direct run.
  - The run is locked `for update skip locked` and the courier claimed with `update … where status = 'available'`.
    A partial unique index allows one open run per courier. Ops can reassign a run that hasn't started.
- **Courier app API** (`/api/v1/courier/**`): scope `courier` **and** a DPoP-bound token (`cnf.jkt`, so Spring's
  DPoP filter verified the proof — S-29). It is checked at the filter (`SecurityConfig.dpopBound`); a bearer token
  with the scope is refused. The courier sees only their own run (others' stops 404). Stops:
  - **arrive;**
  - **pickup** (the sealed-bag scan is recorded as `scan_ok`);
  - **proof upload:** photo or signature, JPG/PNG/WebP by magic bytes, ≤ 5 MB, through the new `ProofStorage`
    port. The adapters are object storage, local disk under local/test, and a fail-loudly placeholder.
  - **drop-off** with `photo` | `signature` (uploaded first) | `pin` (the delivery's 4-digit PIN, compared in
    constant time).
  - The last pickup of an order publishes **`delivery.picked_up`** (orders → `picked_up`). A drop-off publishes
    S-78's **`delivery.completed`** (orders → `delivered`, escrow window, delivery fee).
  - Run states: `planned → loading → en_route → done`.
- **Dispatch events:** `run.planned` and `delivery.assigned` go on the new topic **`fulfilment.run`** (key = run
  id); `delivery.picked_up` goes on `fulfilment.delivery`. Ids only.
- **Ops view** (`/api/v1/console/fulfilment/**`, staff + second factor):
  - runs by market and time, with courier, progress, next ETA and `late` (a pending stop 15 min past its ETA);
  - a run's stops, an order's delivery, couriers with shift and run;
  - onboard a courier, schedule a shift, plan now, reassign.
  - Contract in the runbook for S-81.
  - **Console roles and audit (S-90, merged meanwhile):** every handler carries `@RequiresConsole`:
    - runs and couriers need the `delivery` screen (dispatch, admin);
    - changes also need its `dispatch` action;
    - an order's delivery needs the `orders` screen (dispatch, support, admin).

    Changes are written to the platform audit log (`developer.audit_log`, `merchant_id` null, the active console
    roles): `fulfilment.courier_added`, `fulfilment.shift_scheduled`, `fulfilment.run_assigned`,
    `fulfilment.runs_planned`. This meets S-81's "actions audit-logged" for these.
- **Studio:** a seller's order (Orders screen detail) now shows the courier's pickup at that shop: "R-701 · Courier
  due 6:10 pm", "Courier is here", "Picked up 6:12 pm" and "Finding a courier · pickup about …" (en + fr-CA).
  `CourierPickups` gains `atMerchant(merchantId, orderIds)` plus `pickedUpAt` and `runLabel`.
- **OpenAPI:** the courier paths are in the `public` document (the mobile apps' audience), not a new group.
- **Dev seed compatibility:** the V108 dev seed has two courier rows for one person and no market, and seed files
  aren't edited. The one-courier-per-person index therefore covers only couriers ops onboards (`market` set). A
  window without a market (the seed's R-611/R-612 serve every market) takes the order's delivery city as its market.
- **Schema (V202):**
  - new `fulfilment.deliveries`, `delivery_pickups` and `shifts`;
  - `couriers` + `market, active, last_assigned_at, created_at`, with unique `user_id`;
  - `runs` + `market, label, part, starts_at, ends_at, pack_by, heuristic, planned_at, assigned_at, started_at,
    done_at`, plus a unique `(window_id, part)` and one open run per courier;
  - `stops` + `merchant_id, state, done_at, proof_kind`, plus a check that a done drop-off has proof.
  - V010's columns are reused: `route` (ordered stops and ETAs), `proof_media_id` (the object key) and `scan_ok`.
- **Not done:**
  - failed deliveries (customer absent) and returns;
  - PIN attempt limits (the courier is authenticated and at the door);
  - courier earnings and tips payout;
  - geocoding shop and customer addresses;
  - cancelling a delivery when an order is cancelled (no cancel flow exists);
  - a dev-seed courier persona;
  - the courier app itself (S-87).
- **Never run against a real service:** object storage for proofs ran only on the local disk adapter. No real app
  has called the courier API; DPoP is exercised with generated keys (`DpopResourceServerTest`).
- **Tests:**
  - `DispatchApiTest` (market "Dispatchville", movable clock; orders through the real checkout):
    - tonight's run from 3 orders after the cut-off, not before and not twice;
    - stop order (pickups grouped, drop-offs by postal code, ETAs non-decreasing), `run.planned`, the console's
      run/order views, the Studio's pack-by and courier pickup;
    - the courier's full run: unpacked 409, drop-off before pickup 409, packing in the Studio, arrive/pickup,
      `delivery.picked_up` → order `picked_up`;
    - PIN missing, wrong and right; photo missing, not an image and accepted; `delivery.completed` → order
      `delivered`, escrow window started; courier available, shift ended;
    - token rules (none 401, customer 403, bearer 403, not a courier 403 `not_a_courier`, another courier's stop 404,
      shift end with open run 409, proof message 422);
    - console rules (customer/courier 403, no MFA `mfa_required`, vehicle 422, duplicate 409, 13-h shift 422);
    - reassignment until the run starts (then 409 `run_started`); a direct order planned once packed.
  - `CourierAssignmentConcurrencyTest`: 4 racing assigners over 6 runs and 3 couriers → exactly 3 assignments, no
    courier twice; two staff giving one courier two runs at once → 200 + 409.
  - `RoutePlannerTest` (heuristic, ETAs, determinism).
  - `DpopResourceServerTest.theCourierApi_takesOnlyAKeyBoundCourierToken` (real proof).
  - vitest: the Studio order's courier pickup (en, wording).

## 2026-10-01 — S-88 Live tracking via SSE and Redis pub/sub

Branch `fulfil/s-88-live-tracking`, **stacked on S-86**. No migration.

- **Privacy decision (recorded per the story):** courier positions are kept **only as the latest one per courier**, in
  Valkey (`nl:courier-pos:<courierId>`, TTL 5 min, replaced on every ping). They are **never written to Postgres**:
  - no table or column holds them (test: no coordinate column in the `fulfilment` schema);
  - no position history or trail exists anywhere;
  - events carry no position.

  This deliberately departs from V010's comment ("Redis Streams · TTL 24 h"): a 24-hour trail isn't needed for
  tracking, and proof of delivery is the stop's photo/signature/PIN and time (S-86), not a GPS trail.

  The customer sees the position only while their order is on its way (picked up, not delivered). Before pickup they
  see the courier's first name, the planned ETA and their drop-off PIN; after delivery, neither the position nor the
  PIN. Ops sees the latest position per courier (console couriers list) for S-81's ops map.
- **Pings:** `POST /api/v1/courier/location` (DPoP courier token):
  - only while on shift (409 `not_on_shift`);
  - rate-limited to one per `ping-interval` (2 s) per courier across replicas (`SET NX PX` in Valkey); a faster ping
    gets 429 `too_many_pings` with `Retry-After`.
  - The app is expected to send every 4 s, so customers' updates are ≤ 5 s apart (the acceptance criterion).
  - lat/lng are range-checked (422 "Send a latitude and longitude on the map.").
- **Fan-out:** a ping publishes "moved" on channel `nl:courier:<orderId>` for each on-its-way order on the courier's
  run. The same Valkey pub/sub pattern as S-52's `nl:order:*` and S-68's `nl:studio:*`. It is selected by the same
  `LIVE_BUS` switch (`redis` | `memory`; memory refused under staging/prod), so there is no new variable.
- **Module direction kept:** `fulfilment.api.CourierLocations` (`forOrder`, `subscribe`) is read by orders. orders'
  new `OrderStreams` (web; extracted from S-52's controller, counting streams for S-91's `TrackingStreams` gauge) subscribes each stream to both the order's tracking bus
  and the courier's moves.
- **Live ETA:** from the position, the run's own leg rules (S-86 `RoutePlanner.leg`) through the drop-offs still
  before this one, plus their dwell. Without a recent position the planned stop ETA stands.
- **Customer screens:**
  - **Goods tracking (S-52)** and **food tracking (S-57)** both carry `courier` (state, run, first name, ETA, stops
    before, position, time, PIN).
  - Food tracking gets its own stream, `GET /api/v1/me/food-orders/{id}/events` (event `food`, also pushed by the
    kitchen steps). The page now listens and keeps its 15 s polling as the fallback.
  - The consumer pages show "Kai is on the way. 2 stops before yours. At your door about 7:10 pm.", "Live · updated
    7:02 pm" and "Drop-off PIN 4821 · Give it to the courier if they ask." (ours, en + fr-CA), plus a courier marker on
    the route illustration. There are still no map tiles: no map provider is set up for the web.
- **Consumer BFF:** the streams take the consumer-bff's existing streaming relay. `SseRelayTest` now also proves an
  order tracking stream's first event arrives before the api finishes.
- **Kitchen courier status:**
  - `delivery.assigned` gains `merchantIds` and `orderType`.
  - New `delivery.courier_arrived` (pickup arrival; topic `fulfilment.delivery`).
  - The Studio's live stream (S-68) turns both into `kitchen` / `orders` signals, so the kitchen display's "courier
    arriving / waiting" and Orders' courier pickup update at once, instead of after the 60 s safety refresh.
- **Tests:**
  - `LiveTrackingApiTest` (market "Pingville", movable clock):
    - off-shift ping 409;
    - before pickup: name, PIN, no position;
    - after pickup: a ping is pushed on the customer's open stream; a second ping within 2 s gets 429 with
      `Retry-After: 2`, and 2 s later it is accepted and pushed again;
    - position, ETA and stops on the order; others' 404;
    - after delivery: no position, no PIN;
    - 422 messages; the ops courier list's position; no coordinate column or position table in Postgres; a
      non-courier 403.
  - `LivePositionsTest`: a real Valkey 8 with two adapter instances — the rate limit holds across replicas, only one
    key per courier, TTL, expiry, a move crosses replicas to that order only; the memory adapter on the app clock.
  - `FoodOrderingApiTest.aFoodDeliveryIsDispatchedAndItsTrackingStreams`: the food delivery reaches fulfilment with
    its coordinates, the stream pushes the kitchen's accept, the planned run shows "finding a courier" on the kitchen
    display, and the food tracking carries the courier part.
  - `SseRelayTest.theOrderTrackingStreamIsRelayedAsItIsWritten`.
  - `StudioLiveApiTest.couriersAssignedAndArrivingSignalTheKitchen`.
  - vitest: goods and food courier progress, live from the stream, PIN gone after delivery, French.
- **Not done / never run:**
  - No real phone has sent positions.
  - A multi-replica deployment hasn't been exercised (two adapter instances against one Valkey stand in for it).
  - No map tiles or geocoding on the web.
  - The courier app's background-location permission flow is S-87's.


## 2026-10-01 — S-79 Verification queue: review merchant checks, approve/reject with reasons

- **One queue of applications, built on the S-23/S-22 reviews** (merchants module, `/api/v1/console/verification/applications`;
  screen `verify`, deciding needs `verify` — admin and trust & safety): submitted businesses (status `pending`, oldest
  submission first) plus those decided in the last 7 days with their latest decision, each with its checklist, a risk
  and the waiting time. `GET …/{businessId}` adds the owners' Stripe Identity results (S-22), every registry lookup that
  went to an agent (S-23, open or decided) and the decision history. The path variable is `businessId`, not
  `merchantId`, because `{merchantId}` handlers are member endpoints (`@RequiresMerchant`).
- **Decisions = the design's two buttons.** "Approve" (pending → active at Registered: the existing
  `ApproveApplication`, which confirms submitted checks and publishes `merchant.approved`) and "Request info", which is
  how the backlog's "reject with reasons" is drawn: the agent picks the checks to redo and writes a note (both
  required); the application goes back to `applicant` at the Verification step and those rows become `rejected`, so
  the owner redoes them and submits again (and `ComplianceStatus` lists them). Requesting identity (`kyc`) also sends
  every owner whose Stripe check was handed in back to Stripe (`last_error = agent_rejected`; the Studio words it "A
  Northline agent couldn't confirm it"). Declining a business for good isn't in the design and isn't built (suspension
  is S-82). **Approve waits for the manual reviews:** 409 `reviews_open` while a registry lookup or an identity
  mismatch of the business is open.
- **Manual reviews:** registry lookups keep their S-23 endpoint (now with `CurrentStaff` and the audit log); identity
  mismatches get `POST …/{businessId}/identity-reviews/{checkId}/decision {approve|reject, note?}` — approve → owner
  verified (the agent checked the person), reject → the owner verifies again; the `kyc` row is re-derived.
- **Risk** (the design's Low / Medium / "High · no licence"): high when a licence or permit row (`licence`,
  `ahs_permit` types) isn't verified; medium when another check waits for an agent's review or failed; low otherwise.
  **Check states:** passed (verified), review (open registry review / identity mismatch), waiting (submitted evidence
  a human reads — uploads, numbers without an API), failed. The table shows ✓ / ✗ as designed and ○ for waiting.
- **Headline:** "{n} applications · median {d} days · SLA 2" — n = pending in scope; the median is submission → decision
  over the last 30 days (`application_decisions.submitted_at`); without decisions the median part is left out. SLA 2 is
  the design's copy.
- **Audit:** every decision writes `developer.audit_log` in its transaction (`merchant_id` = the business, role = the
  console role(s) acted with): `verification.application_approved`, `verification.info_requested`,
  `verification.identity_approved|identity_rejected`, `verification.registry_approved|registry_rejected`.
- **Notification (S-27 channels):** a new in-process event `merchants.api.ApplicationDecided` (decision, check keys,
  the agent's note; not externalized) → the messaging module emails every owner (`application-decision` template,
  en/fr, transactional — the answer to their own submission, so not subject to the notification matrix). SMS/push: the
  S-27 worker has no row for application decisions; not added.
- **Region filter:** `?province=&market=` (S-134). The overview's resolution moved to a shared kernel port
  `shared.PlaceFilter` (implemented by merchants, which knows where each business operates) so every queue resolves
  places and answers the same 422s. The console's `PlaceFilters` component (shell) is shared too.
- **Schema V210** (console queues range V210–V219: V19x would sort below fulfilment's V200–V202): `merchants.application_decisions` (decision, check keys, note ≤ 500, agent, role, submitted/decided
  times); `owner_identity_checks.reviewed_by|reviewed_at|review_note`; partial indexes for pending businesses and open
  identity reviews.
- **"Simulate approval" (local only) is kept** for Studio development: design 02 draws the button, and it exists only
  under the `local` profile. Every other environment approves through this queue.
- **Data Table:** `onAction` may resolve to `false` to skip the "done" toast (Request info opens the console's own
  dialog).
- **Messages (fr in the catalogue):** "Choose approve or request info.", "Choose what the business needs to fix.",
  "Pick checks from this application.", "Tell the business what to fix.", 409 "This application isn't waiting for a
  decision." / "Decide the open registry and identity reviews first." / "Only a submitted application can be sent
  back."
- **Not done:** the design's "welcome call fast-tracks Trusted" (tiers are S-93/S-82); sanctions screening and
  insurance OCR named in the lede (no provider exists — the lede is the design's copy); assigning applications to an
  agent; the Studio doesn't show the agent's note in the wizard (it is in the email).

## 2026-10-01 — S-92 Listing vetting queue: flagged listings approve/reject

Stacked on S-79 (it uses S-79's `shared.PlaceFilter`, the console's `PlaceFilters`, `useGrant`, `queues.css`).

- **One queue, three sources, composed in the console module** (`GET /api/v1/console/vetting?province=&market=`,
  screen `vetting`; deciding needs `vet` — admin and trust & safety): listings the automated checks flagged (catalogue
  `pending` with `vetting_flags`, S-39 re-vets included with their `revetReasons`), listings an **open S-133 trust flag**
  points at (target type `listing`, whatever their vetting — the AI never holds a listing), and **dishes held by the S-67
  price check** (published, outside ±40 % of the median, price not confirmed — the "console review queue for menu
  prices" S-67 left open). Each module answers through its `api` (`catalogue.api.ListingVetting`,
  `food.api.MenuPriceReviews`, `trust.api.ListingFlags`); no cross-module SQL. Decided listings stay listed 7 days with
  their decision ("Approved" / "Rejected · seller notified").
- **Approve** a flagged listing = as if the automated checks had passed (live on a first submission; a re-vetted one
  keeps the merchant's live / hidden choice; `listing.published` when customers can now see it). Approving a listing
  that only had a trust flag changes nothing in the catalogue and **dismisses** its flags. Approving a held dish keeps
  its price (as the owner's "Keep this price" would).
- **Reject** needs at least one reason (`prohibited | misleading | pricing | licence | images | other`; the design has
  no reason list, the codes are ours) and takes an optional note. A listing becomes `rejected` (hidden from customers,
  `listing.hidden` if it was visible; the merchant fixes and resubmits — the existing rejected → submit path), its open
  trust flags are **actioned**; a dish goes back to **draft**. The owners get the `listing-rejected` email (en/fr,
  transactional) with the reasons and the note.
- **What "actioned" does to a listing (the S-133 open question):** the listing is rejected, exactly like a reviewer's
  rejection (reason `other`, the staff note), whether the flag is actioned from the vetting queue or from the trust
  queue. Trust now publishes `trust.api.FlagDecided` for every staff decision; the catalogue reacts for target type
  `listing` (a listing already rejected is left alone, so the vetting path doesn't reject twice). Dismissed changes
  nothing. Other target types (reviews, messages) are S-93's.
- **Module wiring:** the owners' email is a `messaging.api.ListingRejectedNotice` event published by catalogue and food —
  messaging can't listen to catalogue or food events (catalogue → trust → messaging would be a cycle), so the notice
  type lives in messaging's api. Trust flag decisions now record the console role acted with (was the literal `staff`).
- **Headline:** "{n} listings auto-approved this week · {m} flagged for a human": auto-approved = listings submitted in
  the last 7 days whose vetting is approved with no reviewer decision; flagged = items waiting.
- **Rule and evidence wording** (design: "Price −72% vs median", "Missing licence", "Duplicate image", "Category rule",
  "Restricted claim"): built from codes and figures by the console (deviation from the category / cuisine median, the
  regulator from the category, the AI flag's own explanation, the re-vet reasons). AI flags show as "Restricted claim"
  with their explanation as evidence.
- **Audit:** `vetting.listing_approved|listing_rejected` (target `product|service`), `vetting.dish_approved|dish_rejected`
  (target `menu_item`), with the business's id and the console role; trust flags resolved alongside write
  `trust.flag_decided` as before.
- **Schema V211:** `catalogue.vetting_decisions` (decision, reasons, the flags seen, note ≤ 500, reviewer, role, time);
  partial indexes on pending offers and services. No food or trust schema change.
- **Messages (fr in the catalogue):** "Choose why the listing is rejected.", "Pick reasons from the list.", 409 "This
  listing isn't waiting for a decision." / "This dish isn't waiting for a price decision."
- **Not done:** the design's "restricted keywords" and "claims requiring proof" rules exist only as the S-133 AI
  screening (no keyword list in vetting yet — S-93 makes keyword lists configuration); the "SLA 4 business hours" is the
  design's copy, not computed; the Studio doesn't show the reviewer's reasons on a rejected listing (they are in the
  email).

## 2026-10-01 — S-80 Disputes: evidence review and decisions (DisputeDecisions)

Stacked on S-92 (#122), itself on S-79 (#121).

- **The agents' queue** (`GET /api/v1/console/disputes?province=&market=`, screen `disputes`: admin, trust & safety,
  finance, support): disputes with an agent (`agent`, `appealed` — the seller contested, or the goodwill offer was
  declined / expired) and **S-60 refund cases escalated after the seller's 24 h** (`agent_review`), oldest first, then
  cases agents decided in the last 7 days. Headline counts: cases for an agent, refund cases still in the seller window,
  disputes and refund cases closed in the last 7 days. Payments owns the data (`payments.api.AgentCases`); the console
  module adds the business name, province and S-38 quality score (`trust.api.QualityQuery`) — payments can't depend on
  trust (trust → messaging → payments).
- **Case view:** both parties' statements (the dispute's customer statement and seller response; a refund case's
  contest reason), the evidence list with downloads of stored files
  (`GET …/dispute/{id}/evidence/{evidenceId}`, `nosniff`), the customer's other disputes, the seller's prior disputes
  and how many were released to it. The design's "Reliability 4.9" and "message thread (14)" have no source yet and are
  left out.
- **Decisions = the design's four outcomes** (`POST …/{dispute|refund}/{id}/decision {outcome, refundCents?, note?}`,
  action `decide`): full refund, partial (any amount between $0 and the escrow; the console pre-fills 50 %), release to
  seller, **goodwill credit (platform pays)** — the escrow is released to the seller and the customer gets a Northline
  credit for the chosen amount (`payments.refunds` kind `credit`, charged to the platform). Money moves only through the
  existing case paths (S-11): refunds become `approved` and the refund queue job pays them; releases let the escrow go.
  **Never auto-refund:** nothing pays out at decision time. Refund cases take only full refund or release (they have
  no partial path).
- **Finance co-sign above $500** (design 03 Team: Finance — "refunds > $500 · Passkey + 2nd approver"): a decision that
  returns more than $500 (card refund or credit) is stored `awaiting_cosign`; nothing moves and the escrow stays on hold
  until another person with the `refund` action (finance, admin) co-signs (`POST …/decisions/{id}/cosign {approve |
  decline, note?}`). The decider can't co-sign (409 `cosign_self`). Declining returns the case to the agents. The $500
  is the design's number, a constant (`AgentCases.COSIGN_ABOVE_CENTS`), not configuration. The design's "Passkey"
  step-up is not modelled (every console session already has a second factor).
- **The note "visible to both parties"** is stored with the decision (`agent_decisions.note`,
  `payments.disputes.decision_note`) and carried by `dispute.decided` as an **optional `note`** (additive schema change)
  so the merchant's "dispute decided" email shows it. Customer-facing display of the note (consumer Help & cases) is not
  built.
- **Audit:** `disputes.decided`, `disputes.decision_awaiting_cosign`, `disputes.cosigned`, `disputes.cosign_declined`
  (target `dispute|refund`, the business's id, the console role).
- **Schema V212:** `payments.agent_decisions`; `payments.disputes.decision_note`; partial indexes on agent cases.
- **Messages (fr in the catalogue):** "Choose an outcome.", "A partial refund is more than $0 and less than the amount
  in escrow.", "A refund case is refunded in full or released to the seller.", "Choose approve or decline.", 409s
  "This case isn't waiting for an agent.", "This case already has a decision waiting for a finance co-sign.", "This
  decision was already co-signed or declined.", "Another person must co-sign this decision.".
- **Not done:** seller appeals ("seller may appeal once" is the design's copy; the `appealed` state exists but no
  appeal flow); "quality scores update tonight" relies on the existing nightly quality job; Stripe card disputes
  (chargebacks) stay with the issuer and don't appear in the queue.

## 2026-10-01 — S-93 Trust & safety rules, flags (incl. off-platform payment flags from messaging)

Stacked on S-80 (#123) → S-92 (#122) → S-79 (#121).

- **Rules as configuration** (`trust.domain.TrustRule`, `trust.rules`): tier thresholds and take rates (Registered /
  Trusted / Master, design 03 tier rules), the rating floor (4.2 over 90 days, 30 days to recover), provider no-shows (3
  in 30 days), customer no-shows (×2), the missing-photo escrow delay (48 h), the **off-platform payment phrases** and
  the **restricted keywords** for listings. Each rule is a typed JSON value with the design's numbers as defaults; a rule
  without a row uses its default (no seed data). `GET /api/v1/console/trust/rules`, `PUT …/rules/{key} {value}`
  (action `decide`) validates every field (422 per `value.<field>`), stores it and writes `trust.rule_changed`
  (platform-level, before/after).
- **What the rules drive today:** the phrases add to the message detector's built-in patterns (`messaging.api.
  OffPlatformPhrases`, implemented by trust — messaging can't call trust, trust → messaging); the restricted keywords
  are a new automated vetting check (`restricted_keyword` flag → the S-92 queue; `trust.api.ListingKeywordRules`); the
  rating floor drives "Simulate impact" (`GET …/rules/rating_floor/impact?rating=&province=&market=`: businesses with at
  least 5 reviews in the window, and how many average below it). **Display only for now:** tier thresholds and take
  rates (tiers aren't recomputed by a job yet; payments still reads the tier stored on the business), the no-show limits
  and the photo delay — the consequences list reads them, so the copy follows the configuration.
- **Flags:** `GET /api/v1/console/trust/flags/queue?province=&market=` lists open flags of the businesses in scope
  (with name and province) and those decided in the last 7 days. **Actions** `POST …/flags/{id}/action {warn | coach
  | confirm | suspend_listings | escalate, note?}` (action `decide`; `suspend_listings` also needs `suspend`) action
  the flag and record what was done in `trust.flags.action` (column of V012, first use). **Warn** emails the owners
  (`trust-warning`, en/fr, transactional; `messaging.api.TrustWarningNotice`) — the design's "warning, then
  suspension". The others are recorded (and audit-logged) only: coaching, instant-book-off and suspensions belong to the
  sellers oversight screen (S-82). A listing's flag keeps S-92's meaning ("actioned" rejects the listing; the console
  shows it as "Reject listing"). Dismiss uses the S-133 decision endpoint. `FlagDecided` now carries the action.
- **Which action a flag offers** (console): off-platform payment → Warn; quality below floor → Start coaching; a
  customer no-show → Confirm; regulated work without permit → Suspend listing rights; listing flags → Reject listing;
  anything else → Escalate to ops.
- **Copy:** the design's tier rules and consequences are built from the rule values ("20+ jobs · quality ≥ 80 · …",
  "Instant book, 12% take, badge"); the lede mentions ClickHouse as written in the design (scores are computed by the
  existing nightly job). Keyword lists, the rule dialog and flag wording for the api's codes are ours.
- **Schema V213:** `trust.rules`; index `trust.flags(merchant_id, state)`.
- **Messages (fr in the catalogue):** "Enter a number in the allowed range.", "Enter a whole number in the allowed
  range.", "Add at least one word or phrase.", "Each word or phrase is at most 60 characters.", "At most 200 words or
  phrases.", "Pick a rule from the list.", "Send the rule's value.", "Enter a rating from 1 to 5.", "Only a business
  can be warned.".
- **Not done:** the automatic consequences themselves (removing a business from search below the floor, instant book
  off after no-shows, the photo delay) — the rules are their configuration, the jobs are S-82's; appeals.

## 2026-10-01 — S-83 Support desk: agent queue, EN/FR macros, role-gated case actions

Stacked on S-93 → S-80 → S-92 → S-79.

- **The queue** (`messaging.application.SupportDesk`, `GET /api/v1/console/support/tickets`): every open helpdesk case
  (`messaging.tickets` — businesses' Help › Contact support, S-60 customer cases) of the businesses in the province /
  market scope; with no filter also customers' and couriers' cases (they have no business, so a province filter leaves
  them out). Sorted by SLA due time. The design's chips are filters with counts (All, Urgent, Unassigned, Mine, SLA at
  risk, Providers, Kitchens, Customers — plus **Sellers**, since sellers write in too). "SLA at risk" = waiting for
  Northline (not `waiting` on the requester) and due within 30 min. KPIs: open, urgent, median first reply (30 days,
  from the new `first_replied_at`), SLA at risk, resolved without escalation (30 days), CSAT (30 days, `tickets.csat` —
  nothing collects it yet, so it shows "—"), share of open cases in French. The design's "Québec pilot prep" note is
  left out (region-neutral).
- **Requester context** is what the case was opened with (`tickets.context`: portal, tier, role, recent events; a
  customer case's refund cases and triage summary) plus the reference label — no new lookups.
- **Case actions** (all need `support`; every one writes the audit log with the acting role, `target_type = ticket`):
  - reply → an `agent` message in the case conversation (the `case` thread the business sees under Help and the
    customer under their cases, created if an old case has none), state `waiting` ("Send & keep open") or `resolved`
    ("Send & resolve"); unassigned cases are assigned to the replier; `support.replied[_resolved]` records the macro
    used.
  - take → assigned to me (`new` → `in_progress`), `support.assigned`.
  - escalate to trust & safety → `escalated_at/by` and a `system` message "Escalated to trust & safety." (+ note) the
    requester sees; once (409 `already_escalated`). It does not create a trust flag (the T&S queue is flags; a case
    becomes one when T&S raises it) — open question.
  - Any action on a resolved case → 409 `case_resolved`.
- **Refund request to finance** (never an automatic refund): an agent asks with an amount and a reason
  (`messaging.support_refund_requests`, `pending`); someone with `refund` on the **finance screen** (finance, admin)
  approves or declines it, **never the person who asked** (409 `request_self`) — that is the design's "second approver",
  so no amount threshold applies here. An approved request decides the S-60 refund cases the case points at that are
  waiting for an agent (state `agent`, no co-sign pending) through `DisputeDecisions.decideRefund` — they become
  `approved` and the refund queue pays them; eligibility is checked first because an exception from payments would roll
  the whole decision back. For any other case the approval is finance's instruction on record (audit
  `support.refund_approved` with the cases it moved); the money movement for business-fee refunds has no path yet.
  `GET /api/v1/console/support/refund-requests` lists pending ones for the finance screen (S-85); in the support desk an
  admin can decide in place. The design's "Refunds > $50 … need a lead's co-sign" is replaced by this finance approval.
- **Macros**: canned replies with a title and text in English and French (`messaging.macros`, topic `support`, new
  `title_i18n`, `updated_by/at`), seeded with the design's six. Agents pick one and its text is inserted in the
  **requester's language** (`tickets.lang`), then edit before sending. Writing them needs the new action `macros`.
- **New role `support_lead`** ("macros EN/FR … editable by support leads"): the support screens and actions plus
  `macros`; admins also have `macros`. The CHECK on `identity.platform_roles.role` is widened (V214); the dev seed
  gives Priya the role (seed-dev V215). Grant it with SQL like the others (runbooks README § Console BFF).
- **Schema V214:** role check widened; `tickets.first_replied_at`, `escalated_at`, `escalated_by` and an open-cases
  index; `macros.title_i18n`, `updated_by`, `updated_at` + six seeded support macros; `messaging.support_refund_requests`.
  Seed-dev V215. No new configuration variables.
- **Messages (fr in the catalogue):** "Write a reply.", "Keep your reply under 5,000 characters.", "Enter an amount more
  than $0.", "Use lower-case letters, digits, dots and dashes for the key.", "Give the macro a title in English and
  French.", "Write the macro in English and French.", "That key is already used.", "Another person must decide this
  refund request.", "This refund request was already decided.", "This case is already with trust & safety.", "This case
  is resolved.", "Pick a filter from the list.".
- **Not done:** the requester isn't emailed or pushed about an agent reply (they see it in Help / their cases); CSAT
  survey; the design's per-case "one-click" suggestions (re-verify WCB, restore
  instant book, force-dispatch…) belong to the screens that own those actions — the desk offers assign / refund request
  / escalate; the finance screen's list of refund requests (S-85).

## 2026-10-01 — S-87 Courier app (MVP): shifts, stops, pickup/drop-off, proof of delivery

Branch `courier/s-87-courier-app`. **No migration** (the V220–V229 range offered for this story is unused) and no new
server environment variable. Runbook: [runbooks/courier-app.md](runbooks/courier-app.md).

- **Where the app lives:** a top-level `mobile/` pnpm workspace (`mobile/apps/courier`, `mobile/packages/mobile-kit`),
  not `web/apps`. Expo SDK 57 pins its own React (19.2.3), React Native 0.86.3, Metro and Jest; inside `web/` every web
  developer and the web CI would install them, and `web/pnpm-lock.yaml` (which parallel stories edit) would carry
  them. The consumer app (phase 4) goes to `mobile/apps/consumer`. Versions follow samop-inv-ship-26's `apps/mobile`.
- **S-97's branch** `mobile/s-97-setup` holds no commit beyond main (it was created and stopped), so nothing was reused
  from it; samop's app was the reference instead.
- **Shared plumbing** in `@northline/mobile-kit`, for the consumer app to reuse: the DPoP session (PKCE S256, the
  `use_dpop_nonce` retry, single-flight rotating refresh that stores the new refresh token before using the answer,
  `invalid_grant` ends the session, revoke at sign-out), the api client (proof with `ath` per request, one refresh on
  401, problem-details errors, `Retry-After`), secure storage, i18n (en, fr-CA, plurals, times in a given zone) and the
  theme. The theme reads `web/packages/tokens/tokens.json` through a `link:` dependency (the package now exports
  `./tokens.json`) and derives the ramps in OKLCH as `derived.css` does — React Native has no `color-mix`. Colour
  literals are refused by ESLint and a test.
- **Design:** `design/*.dc.html` and SCREENS.md have no courier screen (only console/consumer mentions of couriers), so
  the screens are **ours, an addition with no design in the spec**: minimal, Northline tokens and fonts, 48 dp targets,
  a stack without tabs. Listed in SCREENS.md § Courier app. Copy en + fr-CA ours. The icon is a placeholder drawn from
  the base tokens (`scripts/gen-icons.mjs`) until design supplies one.
- **Sign-in page:** the `courier-app` client's unauthenticated authorization request went to the **Studio's** sign-in
  page (every client not in `consumer-clients`/`console-clients` does). Couriers are people, not businesses: the
  client is now in `northline.auth.consumer-clients` (yml and the property default), so they land on the consumer
  site's page and its `continueTo` returns to the app (`MobileDpopApiTest.Courier`). `mobile-consumer` still lands on
  the Studio's page; the consumer app (phase 4) should move it the same way.
- **The DPoP key is a software key** (P-256 via @noble/curves) kept in expo-secure-store (Keychain / Keystore-
  encrypted, `AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY`: readable by the background task with the phone locked, never
  backed up or migrated), not the non-exportable Secure Enclave / StrongBox key mobile-auth.md § 1 asks for: Expo has no
  module for it and a native module could not be built or tried here. `DeviceKey` is the seam for a native
  implementation. **Follow-up.**
- **A refresh that got no answer** keeps its refresh token and is presented again later (mobile-auth.md § 4 allows
  this trade-off): couriers lose signal often, and forcing a sign-in on every lost answer is worse than the rare reuse
  detection that ends the sign-in.
- **Offline (the outbox):** arrive, pickup, proof upload and drop-off are queued on the phone and sent in order, one at
  a time, each with an `Idempotency-Key` repeated on every retry; no answer / 408 / 429 / 5xx retry with back-off or
  `Retry-After`; a refusal drops the action and the later ones for the same stop and shows the api's message; a proof
  upload refused with `stop_done` counts as sent; a sign-in that ended pauses the queue until the next sign-in.
  Starting or ending a shift is **not** queued (it has a time window and needs the server's answer). The queue holds
  stop ids, the proof kind, the PIN until sent and the proof file's path — no address; the run itself is kept in memory
  only, so drop-off addresses are not stored on the phone across restarts.
- **Server change for replays:** the api's stop actions were already idempotent by state, except that a stop of a run
  that is done answered 404. `CourierAppService.ownStop` now lets a done stop of the courier's own done run through, so
  a replayed action answers the run instead (no event, tested in `DispatchApiTest`). The api does not store
  `Idempotency-Key`s for these calls (payments' store belongs to payments); the header is sent for when it does.
- **Location:** only while a run is open, after the app's own explanation card (what customers see, latest position
  only, sharing ends with the run). "Always" → OS updates to a background task (Android foreground-service
  notification, iOS blue indicator); "while using" → updates while the app is open. A ping at most every 4 s, later on
  `nextAfterMs` / 429 `Retry-After`; only the newest fix, never buffered or retried (no position is stored on the
  phone, in line with S-88's latest-position-only decision); the pinger re-reads the run every 60 s from the
  background and stops on 204 or 409 `not_on_shift`.
- **Proof:** photo through expo-camera, scaled to 1600 px JPEG (the api takes ≤ 5 MB); signature strokes rasterised to a
  600 × 240 grey PNG in JS (no screenshot module); the PIN checked for four digits on the phone, its value by the api.
- **Stop detail:** the api gives a pickup no item list (fulfilment never reads orders' lines), so a pickup shows the
  shop, the order reference, whether it is packed and the sealed-bag confirmation (`scanOk`). There is **no call or
  message via Northline** for couriers (no masked-number or courier messaging API exists): no contact button, and the
  stop says customers' numbers aren't shared and to contact the dispatcher. **Follow-ups.**
- **Times** show in the run's market zone from the region model (`GET /api/v1/geo/regions`, market matched by id or
  city, else the platform zone); nothing names a province, city or zone (a test scans the app).
- **Variants:** development / preview / production with their own bundle ids; all use the one registered redirect
  `ca.northline.courier:/oauth2redirect` (the server matches exactly). App Links / Universal Links wait for the
  association files (mobile-auth.md).
- **Fixture backend** (`EXPO_PUBLIC_FIXTURES=1`): an in-memory northline-auth + courier API inside the app for the web
  smoke test, the screen tests and demos; `app.config.ts` refuses it for a production build.
- **CI:** `.github/workflows/courier.yml` (workflow_dispatch only) and `ci/gitlab/courier.yml` (`PIPELINE_PART=courier`,
  never part of `all`; the EAS build a further manual job). `make courier-*` targets (make/courier.mk).
- **Tests:** kit 29 (PKCE RFC 7636 vector, proofs verified with the public key, server-clock `iat`, nonce retry,
  single-flight refresh, reuse → signed out, offline refresh keeps the token, api 401 refresh, 204/409/422/429, theme
  contrast, i18n); app 43 (outbox order/back-off/Retry-After/refusals/replays/restart, pinger throttle/429/stale/stop,
  signature PNG, every screen on the fixture backend incl. offline drop-off, French, config against the auth server's
  client registration, en/fr parity, region and colour rules); `expo export` iOS + Android; headless-Chromium smoke of
  the web build. Server: `MobileDpopApiTest` (courier sign-in page), `DispatchApiTest` (replays on a done run).
- **Not done / never run:** no device, simulator, emulator or native build (no macOS or Android SDK here); never
  exercised: camera, background location and the foreground service, Keychain/Keystore, the system-browser sign-in
  against a real northline-auth, multipart upload from a phone, EAS Build/Submit, the stores and their background-
  location reviews. The acceptance criterion (a run end to end in staging) is not met yet: it needs an EAS project, a
  build and a test phone (runbook § Staging acceptance). Hardware-backed key, courier contact, pickup item lists, App
  Links: follow-ups above.

## 2026-10-01 — S-81 Orders monitor and delivery ops map

- **Orders monitor = orders and bookings in one list** (design 03 `orders`: "BK-7712 · Service", "NL-48188 · Order").
  `GET /api/v1/console/orders?view=&q=&province=&market=` lives in the `console` module and composes new query ports of
  the owning modules: `orders.api.OrderMonitor`, `booking.api.BookingMonitor`, `fulfilment.api.DeliveryAlerts`, plus
  identity's `PersonDirectory` (customer short names, "A. Osei") and merchants' `BusinessNames`. No cross-module SQL.
  Rows: everything placed or booked in the last 7 days, and every one still open, at most 500 per source and 300 shown
  (`truncated` says so; a reference search reaches older ones). "This week" = `MarketplaceOrders.placed` +
  `MarketplaceBookings.made` (cancelled out, as on the overview).
- **What "needs attention" means** (the spec only shows examples): **issue** — an order line with a reported issue
  (`issue_note`) or short / refunded, or a disputed booking; **stuck** — the order's run has a pending stop more than
  10 min past its ETA (the overview's "stuck" threshold); **late** — a pooled order past its window's end and not
  delivered, or a confirmed booking 15 min past its start with no en-route / on-site; **escrow > 48 h** — a completed
  booking whose customer hasn't signed off 48 h after the job's end. Goods' 7-day release window is not flagged (it is
  the rule, not a problem). The Issue column is built from these codes, never from the customer's own words.
- **Chips are views** with counts (`attention`, `live` = new + in progress + late + stuck, `escrow`, `late` = late +
  stuck, `all`); the default is "Needs attention", as in the design. Province / market filters use the overview's rule
  (`console.application.PlaceScope`, same 422 messages).
- **Opening an order** shows its delivery from S-86's `GET /api/v1/console/fulfilment/orders/{id}` in a drawer; a
  booking shows its row. The monitor itself is read-only (the design's table has no actions for these roles).
- **Ops map provider: no third-party map by default; an optional XYZ tile basemap.** The design's map is a schematic
  (grid, zone polygons, courier dots, legend), so the console draws the market's delivery zones (`region.zones`
  PostGIS polygons, new `region.api.DeliveryZones`) and the couriers' latest positions (S-88) in Web Mercator, fitted
  to the data — no place, centre or zoom in code. `CONSOLE_MAP_TILES` (an https XYZ template) + `CONSOLE_MAP_ATTRIBUTION`
  add raster tiles under it (any OSM-compatible or commercial tile server; served to the browser through
  `GET /api/v1/console/delivery/map`). **Google Maps was not used:** S-47's key is a server key restricted to Places
  and Geocoding by IP, and the Maps JavaScript API needs a key in the browser, against "keys server-side". The tile
  provider is configuration only; nothing was tried against a real tile server (CSP already allows `img-src https:`).
- **Dispatcher actions:** *Reassign* uses S-86's assign (a run that hasn't started; 409 `run_started` / `courier_busy`
  shown in the dialog). *Pause / Resume a courier* are new: `POST /api/v1/console/fulfilment/couriers/{id}/pause
  {reason}` and `/resume`, screen delivery + action `dispatch`, audited as `fulfilment.courier_paused` (with the reason)
  / `fulfilment.courier_resumed`. Paused = `fulfilment.couriers.active = false` (V202's column, already excluded from
  automatic assignment); manual assignment now refuses a paused courier too (409 `courier_busy`). A run the courier
  has stays theirs. The pause reason is a staff note in the platform audit log (`after.reason`), never shown to the
  courier or customers.
- **Couriers table** (name, vehicle, status, shift, run, with Pause / Resume) is an addition to design 03's delivery
  screen: the design has no place to act on a courier, and the story asks for it.
- **Not done:** the design's "Page Sam" and "Notify 7 customers" (no paging or bulk-notice integration exists) and
  "Publish pricing" (zone pricing edits belong to S-84's region switchboard; "Edit zones (GeoJSON)" links there for
  admins). Runs are planned per market window, not per zone, so the runs table's Zone column shows the run's market.
  **Cost/stop and margin** show "—": no courier cost model is recorded anywhere. A started run can't be reassigned
  (stops already picked up are with the courier); moving the rest of a stuck run is a follow-up.
- **No migration.** Migration range for the console's second batch (S-81, S-82, S-84, S-85, S-94, S-95, S-96):
  **V230–V239** (the console queues hold V210–V219; IMPLEMENTATION_PLAN).

## 2026-10-01 — S-82 Sellers directory and seller detail with oversight actions and audit

- **Where things live.** The directory and detail (`GET /api/v1/console/sellers`, `/{sellerId}`) are composed in the
  `console` module from new query ports of the owners: `merchants.api.SellerDirectory` (profile, categories, the checks
  that need attention, the oversight trail), `trust.api.SellerStanding` (latest quality score, open flag rules, in bulk),
  `orders.api.OrderMonitor.salesByMerchant` and `booking.api.BookingMonitor.salesByMerchant` (90-day GMV and counts),
  `payments.api.DisputeCounts`, plus `QualityQuery`, `RatingQuery`, `CategorySource` (names) and `PersonDirectory`
  (who acted). The actions belong to the merchants module: `POST /api/v1/console/merchants/{businessId}/suspend |
  reinstate | reverification | tier` (`{businessId}`, not `{merchantId}`: that name is reserved for `@RequiresMerchant`).
- **Role gates (design 03 `CAN`: the sellers table's perm is `suspend`).** Suspend, reinstate and change tier need the
  `suspend` action (admin, trust & safety); require re-verification needs `verify` (admin, trust & safety). Support
  opens the directory and detail read-only. The design's T&S lead co-sign ("Suspend requires a T&S lead co-sign") is
  not modelled (no lead role exists; CONSOLE_PLAN "Not modelled yet").
- **Each action takes a reason (1–500 characters) the business sees.** It is kept in the new
  `merchants.oversight_actions` (**V230**: action, reason, codes-only detail, actor, roles, time) — the detail page's
  "Timeline & audit" — and the platform audit log gets `merchant.suspended | reinstated | reverification_required |
  tier_changed` with the business id, the oversight id and the codes (no free text in `developer.audit_log`).
- **Effects.** Suspend: status `active|paused → suspended` (every public read already requires `active`: page,
  listings, search, checkout); open orders, jobs and escrow are untouched (escrow stays held until settled).
  Reinstate: `suspended → active`. Re-verification: a `verified` or `submitted` check (not `kyc`, which is the owners'
  S-22 identity flow) becomes `expired` now, so Compliance shows it due and the usual grace period applies. Tier:
  `merchants.tier` changes; the take rate follows the tier unless the business has its own (`take_rate_bps`).
- **Events → search and email.** `merchant.suspended`, `merchant.reinstated`, `merchant.tier_changed`,
  `merchant.reverification_required` on `merchants.merchant` (schemas v1; search re-reads the business on any event of
  that topic). The reason is not in the payload (free text): events carry `actionId`, and messaging's new
  `OversightEmailNotices` reads it back through `SellerDirectory.action` and emails the **owners** (template
  `seller-oversight`, en + fr, an account notice always sent).
- **"At risk" (the design's Flags column):** quality below the tier's floor, dispute rate above it (design 03 tier
  rules: Trusted ≥ 80 / ≤ 1.5 %, Master ≥ 85 / ≤ 1 %; S-93 makes the rules configurable — read them from there once it
  lands), an open trust flag, or a check that is due (to do, expired, rejected), under review (licences, registries,
  permits) or expiring within 30 days. Dispute rate = disputes opened ÷ orders + bookings, 90 days. The headline counts
  active businesses and the active ones at risk.
- **Hide from search (design 03 "Existing customers can still book").** V230 adds `merchants.search_hidden_at` and
  `search_hidden_cause` (`staff` | `rating_floor`). `POST …/{businessId}/search {hidden, reason}` (sellers ·
  `suspend`) hides or shows a business; 409 `already_hidden` / `not_hidden`. The search worker indexes a hidden active
  business as `hidden`, so its documents leave search exactly as a paused one's do (tested in `SearchIndexerTest`);
  its page, listings and checkout stay open, so existing customers can still book. Event
  `merchant.search_visibility_changed` (`actionId`, `hidden`, `cause`), trail `search_hidden` / `search_restored`,
  audit `merchant.search_hidden` / `merchant.search_restored`, owners emailed. The directory's Flags column and the
  detail header say "Hidden from search" (with "· rating floor" when the rules hid it).
- **The trust rules' consequences are enforced here (coordinator: S-93 left them as configuration).** A nightly job
  (`TrustEnforcementScheduler`, `CONSOLE_TRUST_ENFORCEMENT_CRON`, default `0 23 5 * * *` platform zone, off under the
  `test` profile) runs `console.application.TrustEnforcementService`: trust decides who
  (`trust.api.TrustConsequences`), merchants applies (`merchants.api.SellerSanctions`), so neither module depends on
  the other.
  - **Rating floor** (`trust.rules` `rating_floor`, default 4.2 over 90 days): a business with at least 5 reviews in
    the window whose average is below the floor is hidden from search (cause `rating_floor`); once its average is
    back at the floor, or it no longer has 5 reviews in the window, it is shown again automatically. A business staff
    hid is never shown again by the job, and the job never hides one twice.
  - **Off-platform payment, warning then suspension:** an open `off_platform_payment` flag raised after another of
    the business's off-platform flags was actioned "warn" within 180 days suspends it (`active|paused → suspended`).
  - Each one is an oversight action by actor `system` (role `system`), audited, emailed to the owners with the
    reason; the timeline reads "… by the trust rules".
  - The reasons the job writes are English only (they are stored once, like a staff member's reason).
- **Not done (design 03 shows them):** Coaching plan, Instant book off (no instant-book state exists to switch, so
  "instant book off after no-shows" is not enforced either), the completion-photo delay and customer no-show
  consequences (no such states), "Bulk message", "Message" and "Impersonate (read-only)" (no staff-to-business
  messaging or impersonation exists).
  The design's "Coaching" status has no equivalent. The directory returns at most 2,000 businesses (`truncated`;
  search by name finds the others).

## 2026-10-01 — S-84 Provinces, markets and zones switchboard (Off/Waitlist/Pilot/Live)

- **A UI over the S-134 region model, no new table.** `console.application.Switchboard` edits `region.regions` (stage,
  courier model, new markets) and `region.zones` (delivery zones, GeoJSON boundaries) through the new write port
  `region.api.RegionEditor` — it lives in the console module because region writing the audit log itself would make a
  module cycle (region → developer → identity → region) — writes the platform audit log in
  the same transaction (`region.stage_changed` — with the markets brought down —, `region.courier_model_changed`,
  `region.market_added`, `region.zone_created | zone_updated | zone_removed`; `merchant_id` null, codes only) and calls
  `Regions.refresh()` after commit, so the instance serves the change at once and the others within
  `REGION_CACHE_TTL`. Endpoints `/api/v1/console/regions/**` (CONSOLE_PLAN § Province switchboard).
- **Admin only:** screen `regions` + action `province` on every change (the role table already gives both to admin
  only). **Confirmation step:** every stage change carries `confirm` — the province's code, or the market's name — that
  the server checks (422), and the screen asks for it in a dialog. The design's "two co-signers for Off ↔ Live" is not
  modelled (no co-sign workflow; CONSOLE_PLAN "Not modelled yet"), nor the "waitlist emailed automatically on Live".
- **Going live needs the checklist** (409 `not_ready`): a tax profile, a holiday calendar, at least one business
  registry adapter key on the province (the story's list; a province whose records are checked by hand sets the
  `manual` key), and a market with a delivery zone that has a boundary. A market goes live only with such a zone
  (409), and **never above its province** (422 "A market can't be more open than its province."); lowering a province
  lowers its markets above the new stage (one audit entry lists them). A live market keeps at least one zone (409
  `last_zone`). Pilot and waitlist need no checklist.
- **New markets** need a centre in Canada's bounding box and a radius of 1–200 km; their id is `mkt-<city slug>` (a
  suffix when taken), stage off, the province's languages; the time zone stays the province's (set `time_zones` by
  SQL for a market in another zone).
- **Zone boundaries:** "Import GeoJSON" (a Polygon, MultiPolygon or a Feature holding one, lng/lat) → PostGIS
  `ST_MakeValid`, the largest polygon kept (`region.zones.polygon` is a single `geography(Polygon)`); 422 on anything
  unreadable. "Draw on map" and "From postal codes (FSA)" are not built. Fees and the minimum basket are typed in
  dollars ("Free" = 0).
- **Not done (design 03 shows them):** "Dry-run as customer", "Categories live" (no per-province category flags exist in
  the model), sellers per market, and the re-matching of addresses when a zone is removed (addresses are matched when
  saved; nothing stores a zone on an address). The lede and footer were reworded where the design promised these
  ("Flags propagate in 30 s" → "within a minute", the region cache period).

## 2026-10-01 — S-85 Finance and reconciliation (Stripe vs ledger)

- **What is reconciled.** The ledger already models cash at Stripe (`stripe_balance`, debited on capture, credited on
  refunds, chargebacks and payouts), so a day's **ledger Δ** = Σ debits − credits on it and the **Stripe balance Δ** =
  the platform's balance transactions that move it (charges +, refunds and dispute withdrawals −) plus the payouts
  Stripe accepted (from `payments.payouts`, which the S-12 webhooks keep). Transfers to connected accounts (separate
  charges and transfers: still at Stripe) and Stripe's fees (not posted in the ledger) are left out; fees are reported
  apart. Payouts are created on connected accounts, so they are compared from our webhook-fed table rather than the
  platform's balance transactions.
- **Object by object:** Stripe ids join the two sides (an escrow's PaymentIntent `stripe_charge`, a delivery fee's,
  `refunds.stripe_refund`, `disputes.stripe_dispute`, `payouts.stripe_payout`); one charge may hold several escrows.
  Differences: `missing_in_ledger`, `missing_at_stripe` (also a posting whose Stripe id is unknown), `amount_differs`.
  A day is `matched`, `mismatch`, or `resolved` (finance's note, kept while re-runs still differ).
- **New:** port `payments.application.StripeBalance` (stripe-java `GET /v1/balance_transactions`, every page; fake
  mirroring the ledger without a key, with a hook for tests), `ReconcileStripe` (nightly job for the two previous days,
  run / resolve / export from the console), tables **V231** `payments.reconciliation_days` / `reconciliation_items`,
  `payments.api.FinanceFigures` and the console's `GET /api/v1/console/finance`.
- **Roles:** the finance screen (admin, finance) reads; running a day, resolving it and S-21's Stripe Tax reconciliation
  need `payouts`. Runs from the console, resolutions and both exports are audited (`payments.reconciliation_run |
  reconciliation_resolved | reconciliation_exported`, `payments.ledger_exported`; ids and codes only — the note stays
  in the reconciliation table).
- **Screen figures:** escrow held (+ items); "Friday payout batch" became **payouts in flight** (pending / in transit,
  businesses) — payouts follow each business's own schedule, there is no single batch; net revenue of the last 7 days
  and its mix from the ledger's `revenue` account by reference (escrow fees = take rate, delivery fees, give-backs);
  take rate by tier = the tiers' default rates (`payments.domain.Tier`), approved businesses per tier, and each tier's
  share of the week's money held; tax of the current quarter (platform-zone) from S-21's read model — GST/HST on
  Northline's fees and what Northline remits as marketplace facilitator — with the GST/HST return due the last day of
  the month after the quarter.
- **Support's refund requests (coordinator: S-85 owns the finance list).** The screen lists S-83's pending requests
  (`GET /api/v1/console/support/refund-requests`, already there, `finance` screen) under "Refund requests from support":
  case code, requester, amount, subject, who asked and when, the agent's note. Approve / decline with an optional note
  uses S-83's `POST …/refund-requests/{id}/decision` (`refund`: admin, finance); one's own request shows "You asked for
  this one; someone else decides." (the api refuses it, 409 `request_self`). No new endpoint, role or audit code —
  S-83's `support.refund_approved | refund_declined` apply. The heading, the note and the empty text are ours (design
  03 has no such list). The province / market filter the endpoint takes is not offered (the finance screen has none).
- **Nightly run:** `PAYMENTS_RECONCILE_CRON` (`northline.payments.reconcile-cron`, default `0 41 4 * * *`, platform
  zone; prod runbook variables table, `.env.example`).
- **Not done / never run:** Plus subscriptions and provider-funded rewards are not recorded anywhere (shown "—").
  The real balance-transaction call has never run against Stripe (stripe-mock only). Exports are CSV only (the Data
  Table still offers its own CSV/XLSX/PDF of what is on screen).

## 2026-10-01 — S-94 Catalogue taxonomy: categories, regulators by province, category limits, suggested categories

- **Where things live.** Catalogue owns the taxonomy: new port `catalogue.api.TaxonomyAdmin` (validation, ids, the
  province rules) over `catalogue.categories`, the new `catalogue.regulators` and `catalogue.category_regulators`
  (**V232**). Merchants owns businesses' categories: `merchants.api.MerchantCategories` (limits, who holds what, the
  suggestions and moving businesses to a category) over `merchants.merchant_categories` and the new
  `merchants.category_limits` (**V233**). The console module orchestrates and audits (`ManageTaxonomy`,
  `/api/v1/console/taxonomy/**`), since catalogue already depends on merchants (no cycle). Screen `taxonomy`, admin
  only; every change needs `vet` (design 03: the taxonomy table's perm).
- **Categories.** Add a group (no parent, under a root) or a leaf (under a group of the same root); the id is the
  parent's id plus a slug of the English name, as the seeder makes them, so ids stay stable and readable; 409
  `category_exists`. Edit names (en, fr), booking type (services only), the default licence registry and the
  vulnerable-sector check; id, root and parent never change (listings and businesses point at the id). French goes to
  `name_i18n.fr` and to `catalogue.category_labels` (the shop reads the label, V111). **The dev seeder now leaves a
  console-edited row alone** (`catalogue.categories.edited_at`, `seedCategories` upserts only rows without it), so a
  re-seed never undoes an edit. Retiring or deleting a category is not built (listings, services and businesses
  reference it).
- **Regulators by province (design "Yes · AMVIC (AB), BC: none").** Regulators are a list staff keep: code, name,
  province, website. None are seeded: which body licenses what is per province, and code may not assume it. A
  category answers to one regulator per province (`category_regulators`), or staff say "not regulated here"; a
  province without a rule falls back to the category's default licence registry (`regulated_registry`, the column
  onboarding and vetting already read). A regulator's province can't change while categories point at it (409
  `regulator_in_use`). **Known gap:** onboarding's licence check and listing vetting still read the default registry
  only, not the per-province rule; switching them over is a follow-up.
- **Category limits — V016's trigger still applies.** The limit per business type (provider 10, seller 5, both 10,
  kitchen 3 to start) is in `merchants.category_limits`; V233 replaces only the function `trg_category_limit` calls, so
  the constraint trigger on `merchant_categories` keeps enforcing it on every insert and update (tested: with the
  kitchen limit at 4, a fifth category fails in the database). Onboarding's Business step validates and shows the
  same number (`CategoryLimitLookup`). Lowering a limit doesn't remove anyone's categories; the screen says how many
  businesses hold more, and they keep them until they change their categories.
- **Suggested categories (approve or merge).** Onboarding stores a free-text category as `suggested:<slug>` with the
  business's wording (S-37). The screen groups pending suggestions by id with the businesses that typed them.
  *Approve* creates the category (the suggestion's wording unless staff change it) and *merge* picks an existing one;
  either way every business holding the suggestion moves to the category — `approved`, or `requested` when the
  category is regulated (a default registry or a regulator in some province), as onboarding does for regulated
  picks. A business that already holds the category just loses the suggestion. Each moved business gets
  `merchant.category_assigned` in its audit log and a `merchant.categories_changed` event (schema v1; search re-reads
  the business).
- **Screen figures:** "service categories" = service leaves, "shop departments" = shop groups (the design's headline);
  sellers and "Live in" = active businesses holding the category and their provinces; median price = the live
  listings' median in the pricing mode most of them use (`/h` for hourly, "quote" when all are quoted; shop goods by
  their catalogue product's category).
- **Audit (codes only):** `catalogue.category_created | category_updated` (the names of the fields that changed, not
  the names themselves) `| category_regulated | regulator_created | regulator_updated | suggestion_approved |
  suggestion_merged`, `merchants.category_limit_changed` (before/after max), `merchant.category_assigned`.
- **Not done (design 03 shows them):** "Synonyms (fr/en)" and "Search boosting rules" — the search index reads
  neither (`search_terms` is unused today), so the buttons would do nothing; they need a search mapping change.
  Category name edits reach search documents when a business is next re-indexed (the worker's category cache is
  five minutes; there is no event per category).
- **Messages (fr in the catalogue):** "Enter the English name, 1 to 80 characters.", "Enter the French name, 1 to 80
  characters.", "Choose services, shop or food.", "Choose a group of the same root.", "Choose visit, home, event,
  appointment or consult.", "The licence registry is at most 80 characters.", "Use 2 to 40 lowercase letters, digits,
  - or _.", "Enter the regulator's name, 1 to 80 characters.", "The website starts with https:// and is at most 200
  characters.", "Choose a regulator from the list.", "That regulator is in another province.", "That group already
  has a category with this name.", "A regulator with this code already exists.", "Enter a limit from 1 to 50.",
  "Choose provider, seller, both or kitchen.". The dialogs and the sections below the table are ours (design 03 shows
  the table and its buttons only).

## 2026-10-01 — S-95 Reports & analytics: funnels and cohorts from privacy-safe aggregates

- **Counts only, computed where the data lives.** `GET /api/v1/console/reports[?province=]` (screen `reports`: admin,
  finance, analyst; read only) is composed in the console module from ports that answer with counts or opaque ids:
  `shared.CustomerActivity` (orders and booking: paid purchases as customer id + instant, sales by listing),
  `orders.api.ShopFunnel` (carts, checkouts started and paid), `merchants.api.StorefrontVisits.total`,
  `catalogue.api.ListingCategories`, `identity.api.SignupDates` (created-at only) and `region.api.WaitlistDemand`.
  Opaque customer ids exist only in memory while the counts are made; nothing is stored and the answer carries no id,
  name, contact or address (the API test checks a customer id is absent from the response).
- **Small-cell suppression:** any count from 1 to 4 (weekly active customers, funnel steps, a cohort's size, waitlist
  people) comes back null and the screen says "fewer than 5"; a withheld cohort also withholds its rates.
- **Region filter:** the region model's live and pilot provinces + All (design "Alberta | All"); a province means the
  businesses there (`shared.PlaceFilter`, as on every console screen), weeks and months in the province's zone.
- **Definitions.** Weekly active customers: distinct customers with a paid order (not cancelled) or a booking past
  "requested" (not cancelled) in each of the last 13 weeks (Monday first), against the 13 before (dotted). Funnel · shop
  over 90 days: storefront visits ("Searched / browsed"), carts with an item added, checkouts started, checkouts placed
  (paid). Cohorts: customers who bought (in scope) by the month their account was created, for the four months before
  this one, and the share who bought in each of the next three months (a month still to come shows "—"). Top
  categories: sales of the last 90 days by the listing's category (goods by their catalogue product's), top six.
  Supply & demand gaps: waitlist people in provinces not live yet.
- **Not recorded (shown as such, not invented):** "App opens" (no app telemetry exists), carts by province (a cart's
  items are offers; which business sells one is the catalogue's), "Searches with 0 results" and "Providers needed" (the
  search API doesn't count queries). "Schedule email" is not built. No migration.
- **Scaling note:** the ports read the source tables on each request (90 days, four months of cohorts). A nightly
  rollup table is the follow-up once volume needs it.
- **Charts:** the design system's `LineChart` (solid accent this period, dotted neutral previous, legend), meter bars
  for the funnel and `BarList` for categories; the weekly figures also open as a table ("Show as table"); cohort cells
  are tinted by rate with the number always printed.

## 2026-10-01 — S-96 Platform console: team and roles, audit log viewer, API keys, on-call rota, staff profile

- **Team replaces the SQL procedure.** `/team` (screen `team`: admin, trust & safety, finance) lists the console roles
  (design "Roles": Role · People · Can · Needs, from `StaffRole`) and the people holding them. Admins (`province`, the
  design's perm for the roles table) add and remove roles and "Invite" an existing account by email
  (`POST /api/v1/console/team/invite`; 422 when no account uses the email — creating accounts stays northline-auth's).
  Granting a role also grants `staff` (it opens the console); removing the last console role removes `staff`. 409
  `own_admin` (nobody removes their own admin role) and `last_admin`. Audit `console.role_granted | role_revoked` (the
  role code). Writes go through the new `identity.api.StaffDirectory`; the console module checks and audits. A change
  reaches the person's token at its next refresh (≤ 10 min). The runbook keeps SQL only to bootstrap the first admin.
  "Needs" shows "Second factor at sign-in" for every role: the design's per-role factor (Passkey / App 2FA / SSO) is not
  modelled — every role needs `acr=mfa` today.
- **Audit log viewer** over `developer.audit_log` (new `developer.api.AuditLogQuery`): filters by action area (a prefix
  such as `payments.`), person, business id, target and dates; newest first, 50 a page with an opaque cursor (422 for a
  token the api didn't make). Actor and business names are filled in for display; entries stay codes and ids as stored.
  "My audit trail" on the profile is the same list for the signed-in person (`GET /api/v1/console/me/audit`). The
  design's footer ("Immutable · exported nightly to cold storage · retained 7 years") is left out: no export job or
  retention policy exists yet.
- **Staff API keys** (`/integrations`, screen `api`: admin; issue and revoke need `keys`): every business's keys
  ("Partner keys": owner, scopes, last used, status). Staff issue a key for a business — the Studio's rules and scopes,
  secret shown once — and revoke one, through the developer module's own use cases (new `developer.api.PartnerKeys`), so
  the business's audit log gets `api_key.issued | api_key.revoked` with the staff member as actor and `ApiKeyRevoked`
  is published as before. Not built: the design's request metrics (no metrics source for them), the webhook event list
  and example (documentation, not data), GraphQL (doesn't exist; the lede says what exists).
- **On-call rota** (`/on-call`, every staff member; **V234** `identity.oncall_shifts`): shifts from 12 h ago to a week
  ahead, who is on call now. Admins add and remove shifts (`province`); the person on a shift — or an admin — hands it to
  a colleague ("Swap a shift"; 409 `not_your_shift`). Audit `console.oncall_shift_added | oncall_shift_swapped |
  oncall_shift_removed`. The escalation paths and targets are the design's copy (without its tool column: PagerDuty,
  Slack channels and an Unleash kill-switch aren't set up). Not built: incidents ("Declare incident", "Open incidents")
  and paging ("Page current on-call") — no incident store or paging integration exists.
- **Staff profile** (`/profile`, every staff member): name, email, roles and role view; **Security** (passkeys with
  add / remove, the authenticator, backup codes left, "Sign out everywhere") and **Devices & sessions** (sign one out)
  come from northline-auth's `/api/auth/security` through `@northline/auth-kit` (S-19, as the Studio and the consumer
  site); changes that need a recent second factor answer `step_up_required` and the screen says how to confirm. With no
  recent second factor the page asks the person to sign in again first. **Preferences**: the console's language. Not
  built: Company SSO (no SSO), regenerating backup codes from the console.
- **Messages (fr in the catalogue):** "Choose a role from the list.", "Enter the person's email.", "No Northline account
  uses that email. They sign up first, then you add the role.", "You can't remove your own admin role.", "Northline
  needs at least one admin.", "Say what the shift covers, 1 to 120 characters.", "A shift ends after it starts and lasts
  at most 7 days.", "Choose a staff member.", "Only the person on the shift or an admin can hand it over.", "Use a date
  and time like 2026-09-08T18:00:00Z.", "That page link is no longer valid. Start from the first page.", "Choose a
  business.".

## 2026-10-02 — S-97 Consumer app: tech decision and project setup

Branch `mobile/s-97-consumer-setup`. **No migration** (the V240–V244 range offered is unused) and no new server
environment variable; four new consumer-web variables (below). Plan: [MOBILE_PLAN.md](MOBILE_PLAN.md); runbook:
[runbooks/mobile.md](runbooks/mobile.md).

- **Tech decision: Expo SDK 57 + React Native 0.86 + expo-router**, as the backlog recommends and S-87 already uses,
  following samop-inv-ship-26's `apps/mobile` (Continuous Native Generation, `app.config.ts` as the single native
  source, EAS profiles per environment, privacy manifest, a prebuild check). The app is `mobile/apps/consumer`
  (`@northline/consumer-app`) in the `mobile/` workspace; the earlier `mobile/s-97-setup` branch had no commits and was
  ignored.
- **Tokens shared with the web** (the acceptance criterion): through `@northline/mobile-kit`'s theme, which reads
  `web/packages/tokens/tokens.json`; the kit gained the ramp steps design 01 uses (accent-800/900, accent-2-800,
  neutral-400/600/800/900, derived as `derived.css` does). Colour literals are ESLint errors and a test scans for them.
- **Kit extensions** (shared with the courier app, tested in the kit): the api client can call **anonymously**
  (`auth: 'optional' | 'none'`, for guests' public reads; an ended sign-in falls back to anonymous for optional
  calls), gives up a request after **20 s** (`NetworkError`, flaky networks), takes extra and default headers, has
  `put/patch/delete`, and keeps a 422's `rule` and the whole problem body on `ApiError`. **Push hook point for S-102:**
  `setPushRegistrar()` / `PushHooks.signedIn(api)` / `PushHooks.signingOut(api)` — the AuthProvider calls them after
  every sign-in and before sign-out; no-ops until S-102 installs a registrar; errors never block either.
- **The `mobile-consumer` client signs in on the consumer site's page** (S-87's open point): it joins `consumer-bff`
  and `courier-app` in `northline.auth.consumer-clients` (application.yml and the property default);
  `MobileDpopApiTest` now expects `CONSUMER_ORIGIN/sign-in` and the `continueTo` back to the app.
- **App Links / Universal Links files** (S-29's open point) are served by the consumer web's node server and its
  Vite dev server (`server/app-links.mjs`): `/.well-known/apple-app-site-association` (each bundle id claims `/app/*`
  or, for the courier ids, `/courier/*`; `webcredentials` for both, for native passkeys later) and
  `/.well-known/assetlinks.json` (`handle_all_urls` + `get_login_creds`), plus a no-store "Open the Northline app" page
  at `/app/oauth2redirect` and `/courier/oauth2redirect`. Configuration: `NL_APPLE_TEAM_ID`, `NL_IOS_BUNDLE_IDS`,
  `NL_ANDROID_PACKAGES`, `NL_ANDROID_CERT_SHA256`, set by the chart from the new `mobileApps` values (dev and staging
  list the `.dev` / `.preview` variants). **A file whose team id / certificate isn't configured answers 404** — nothing
  is guessed; none is configured anywhere yet (no Apple team, no Play app). The consumer app declares
  `applinks:`/`webcredentials:` for the site host and an `autoVerify` intent filter for `https://<site>/app/…` (only
  when the site origin is https).
- **Shell (design 01 `Consumer Screen`):** five tabs Home · Services · Cart · Orders · You with **text labels only**
  (the design has no icons in the tab bar), current tab accent-700 semibold, a rosehip count badge on Cart (S-99 feeds
  it); sub-screens on the root stack with the design's own header ("← Back" + centred title) instead of the native
  navigation header. The first launch opens Welcome; after Create account, Sign in or Browse first the app opens on
  Home (`nl.app.welcomed`). Personal screens show guests a sign-in prompt.
- **Every screen of design 01 is a route now** (`src/screens.ts`: route, story, tab, personal, api), stubs for B–D
  showing the design's header or name, the story and the api; a test checks the list against the design's `screen`
  options (+ `quote`, which the options omit) and MOBILE_PLAN. Routes chosen for deep links: `/product/[id]`,
  `/orders/[id]/{confirmed,track,delivered}`, `/problem/[kind]/[id]`, `/services/[category]`, `/providers/[slug]`,
  `/book/[slug]/{service,time,review}`, `/bookings/[id]/{booked,eta,sign-off,review}`, `/quotes/[id]`.
- **Parallel work:** copy is split per area (`src/i18n/{en,fr-CA}/{common,screens,journeyA,shop,services,account}.ts`)
  and fixtures per area (`src/fixtures/<area>.ts`) so S-99/S-100/S-101 don't edit the same files (MOBILE_PLAN
  § Working in parallel).
- **States:** `QueryView` (skeleton → error with Retry, keeping data already shown → empty → content), `ErrorState`
  (network / 429 / 5xx / ended sign-in / the api's own message), `EmptyState`, `OfflineBanner` (NetInfo),
  `SignInPrompt`. Queries retry twice with back-off on no answer or 5xx, never on 4xx; mutations never retry
  automatically.
- **Guest id:** a random `g_…` key per installation (key-value store) sent as `X-Northline-Guest` on every api call,
  as the consumer-bff does for the web (the api keys guest carts by it and merges at sign-in).
- **Browser sign-in** (S-97's `/sign-in`): the RFC 8252 flow with `ca.northline.app:/oauth2redirect`; S-98 adds the
  in-app flow. Copy ours ("Sign in in the browser", en/fr).
- **Environment config:** `EXPO_PUBLIC_API_URL`, `EXPO_PUBLIC_AUTH_ISSUER`, **`EXPO_PUBLIC_SITE_ORIGIN`** (new: legal
  pages, the https redirect, App Links), `EXPO_PUBLIC_FIXTURES`; EAS profiles development → dev, preview → staging,
  production → prod; preview/production refuse http and production refuses fixtures.
- **Native config:** location **while in use only** — the expo-location plugin's generic "Always" and motion texts
  are removed from Info.plist (`false`), background location / foreground service / camera / microphone / storage
  removed from the Android manifest; `allowBackup=false`; privacy manifest (name, email, phone, address, precise
  location, user id; linked; app functionality; no tracking). Icon: a placeholder pin drawn from the base tokens
  (`scripts/gen-icons.mjs`) until design supplies one.
- **Checks and CI:** `make mobile-consumer-{check,lint,typecheck,test,export,native-check,web-smoke,eas-build,…}`;
  `.github/workflows/mobile-consumer.yml` (workflow_dispatch only) and `ci/gitlab/mobile-consumer.yml`
  (`PIPELINE_PART=mobile-consumer`, never in `all`; the EAS build a further manual job). The native check runs
  `expo prebuild` for both platforms (development and production) and asserts 26 settings of the generated projects
  — the closest this environment gets to "builds for iOS and Android in CI": no binary is compiled (no Xcode, no
  Android SDK); `expo export` proves the JS bundles for both.
- **Not done / never run:** no device, simulator, emulator or native compile; never exercised: Keychain / Keystore,
  the system-browser sign-in against a real northline-auth, App Links verification by iOS / Android (no team id, no
  certificate), EAS Build / Submit. The app's DPoP key is the kit's software key (S-87's follow-up stands).

## 2026-10-02 — S-98 Consumer app Journey A: welcome, sign up, OTP, MFA, location

Branch `mobile/s-98-journey-a` (on S-97). **No migration** (V240–V244 unused) and no new environment variable.

- **Sign-in on the app's own screens (first-party), ending in the same OAuth code + PKCE + DPoP tokens.** Design 01
  draws sign up, the code and the second factor natively, and the brief asks for them as app screens; S-29/S-87's
  system-browser flow would have shown the consumer site's pages instead. So the app drives northline-auth's JSON
  sign-in API (the one S-62's pages use) in its own cookie session and hands off to the authorization endpoint:
  `GET /oauth2/authorize` first (northline-auth keeps the request), the JSON calls, then `GET continueTo` → the code on
  the claimed **https** redirect `${CONSUMER_ORIGIN}/app/oauth2redirect` (already registered for `mobile-consumer`;
  fetch can't land on the custom scheme), read from the URL fetch landed on, exchanged with DPoP. **No server change**:
  `AppAuthorizationResume`'s `continueTo` and the origin check (no `Origin` from a native request) already allow it;
  `MobileDpopApiTest.Sessions` now proves registration with a phone code and no second factor, and phone-code
  sign-in, ending in DPoP tokens on the https redirect. `continueTo` is followed only when it is the issuer's
  `/oauth2/authorize` for this client and this `state` (rebuilt on the configured issuer, so a proxy's host in it
  doesn't matter); without one (lost), the original request is asked again — the session is signed in by then.
  Kit: `AppSignIn` (`src/auth/handoff.ts`); `beginSignIn` takes a redirect URI and `PendingSignIn` carries it to the
  code exchange. Passkeys, Google and Apple still use the **system browser** on the consumer site's page (the app has
  no native passkey / Sign in with Apple / Google module): "Sign in with a passkey", "Continue with Apple", "Google".
- **The cookie session** lives in the platform's HTTP cookie store (NSHTTPCookieStorage / Android CookieManager via
  React Native's networking), never in JS; the half-done flow (what was typed, the PKCE verifier) lives in memory —
  closing the app mid-way means starting again.
- **Sign in (an addition: design 01's Welcome "Sign in" leads to Sign up):** `/sign-in` — "Welcome back", email or
  mobile, Send code → the code to the account's phone (S-62's consumer sign-in; the same answer whether or not an
  account matched), plus the browser buttons. Copy from the consumer web (S-62) where it has it, ours otherwise.
- **The second factor "as the consumer design allows":** design 01 says two factors are mandatory, but S-62 (design
  06) allows "SMS code · backup only" = no second factor (`POST /register/complete`), and the app follows S-62. The
  three options are shown with the design's words: **Passkey is shown but can't be picked** (no native passkey module;
  a line of ours says to add one later on the Northline site), **Authenticator app** ("Scan QR code": the QR for
  another device, the key, and "Open my authenticator app" = the `otpauth://` link for one on this phone; then its
  6-digit code), **SMS code** ("Continue with SMS"). Default selection: Authenticator app (the design's default,
  passkey, isn't available). Consumer tokens without `acr=mfa` are fine (S-29/S-62); paying asks for a step-up (S-51).
- **Code entry:** six boxes over one real field (`textContentType="oneTimeCode"`, `autoComplete="sms-otp"`: iOS and
  Android fill it from the SMS); six digits submit once by themselves when online; "Resend in 0:45" from the server's
  `resendAfterSeconds`, then "Resend code"; "Call me instead" = a voice code (the server throttles inside 45 s and the
  app shows how long to wait); "Edit" goes back to the form with what was typed.
- **Validation:** the rules and exact messages of docs/spec/validation-rules.md § Registration on the phone (the same as
  `@northline/auth-kit`), "Full name" split like the consumer site (last word = last name); the auth server's 422 rules
  are mapped to the same messages so French people read French; flow errors (`otp_throttled`, `rate_limited`,
  `code_not_sent`, `flow_not_started` → start again, no connection) in the kit's words. "Send code" stays off until the
  mobile and the terms are filled (S-62's rule).
- **Legal links** (Terms, Privacy Policy) open the consumer site's verbatim pages **outside the app** (`Linking.openURL`).
- **Location (A5):** the provinces from `GET /geo/markets` with their stage (live and pilot pickable; waitlist and
  not-yet greyed, as the design's Ontario/Québec), Google suggestions through the api, the unit / buzzer note, tags
  "Zone · …", "N pooled runs / day" and "Sales tax N%" (the design's "GST 5%" names a tax of one province; the rate
  comes from the province, the label is the consumer web's), Save — or the waitlist outside a live market (S-47).
  "Use my location" is the **only** place the app asks for the location permission; granted → `GET /geo/reverse`
  inside a live/pilot market; refused → a message (and "Open Settings" when it can't ask again) and the **api's
  fallback market** stands ("Showing {city} for now…"). The saved address is kept on the phone (`nl.location`, like
  the web's localStorage), not in `/me/addresses` (S-101 decides). `useDeliveryLocation()` (saved → the position only
  if location was already allowed → fallback) is the contract for journeys B–D.
- **Welcome's kicker** "Northline · {province}" names the person's province, else the region model's default
  (`GET /geo/regions`); never a fixed one.
- **Sign out** (the You tab, until S-101 builds Profile): the S-102 push hook, `/oauth2/revoke` (ends the sign-in,
  S-20), the key and tokens deleted, back to Home as a guest; plus "Language / Langue" and the legal links.
- **Offline-tolerant:** requests give up after 20 s; failures keep what was typed and say so; the hand-off can be
  retried ("Finish signing in") without repeating the sign-in; the offline banner shows on every screen.
- **Tests:** app 25 new (journeyA: every step on the fixture backend incl. the hand-off's two authorize calls and DPoP
  token exchange, rules, server errors, waitlist, permission granted/refused, push hooks at sign-in/out, French;
  rules: messages en/fr, 422 mapping, flow errors, the countdown); kit 4 new (hand-off: kept request, continueTo, no
  continueTo, refused continueTo, no code, offline); server 2 new in `MobileDpopApiTest`; the web smoke runs all of
  Journey A in headless Chromium.
- **Never run:** on a device — so never exercised: the platform cookie store carrying the auth session between the
  JSON calls and the authorize request, fetch following northline-auth's redirect to the consumer site and reporting
  the landed URL (React Native's `Response.url`), SMS autofill, the location permission dialogs, the Keychain.
  The fixture backend simulates the redirect-following. **This is the first thing to try on a phone.**

## 2026-09-30 — S-102 Push notifications (APNs/FCM) and deep links

Branch `mobile/s-102-push`. Mostly server side; runbook [push.md](runbooks/push.md).

- **Never run against Apple or Google.** No Apple developer key and no Firebase project exist. The APNs adapter (HTTP/2,
  token-based auth with the `.p8` key) and the FCM HTTP v1 adapter (OAuth 2.0 with a service account) follow the
  providers' documented APIs and are tested only against WireMock stand-ins; TLS/ALPN HTTP/2 to APNs, the providers'
  real answers and delivery to a phone are unverified (push.md § First real send checklist). No JOSE library in the
  worker: the APNs provider JWT (`ES256`, `SHA256withECDSAinP1363Format`) and the Google assertion (`RS256`) are signed
  with the JDK; FCM is called over HTTP/1.1, APNs over HTTP/2 (JDK `HttpClient`, `@HttpExchange` clients).
- **Port and providers.** S-27's `PushSender` port now takes a `PushMessage` (person, app, words in en and fr, deep
  link, ids, collapse key) and answers `DELIVERED | NO_DEVICE`. `northline.push.provider` (`PUSH_PROVIDER`): `local` =
  `PushSender.LOGGING` (default; also with no credentials), `native` = `DevicePushSender` (APNs for `ios`
  installations, FCM for `android`). Prod refuses `local` and requires the credentials (`required-env`,
  `values-prod.yaml`); dev and staging may keep `local` (a warning) until the accounts exist — unlike SMS/email, which
  staging refuses, because there is nothing to configure yet.
- **Native tokens, not Expo's push service.** The apps register the APNs device token / FCM registration token
  (`getDevicePushTokenAsync`); the worker talks to Apple and Google directly, with no third service holding tokens.
- **Device registry in the messaging module** (it owns notification preferences): `messaging.push_devices` (V245),
  one row per person × app × installation (a random id the app makes, not a hardware id), the token unique per app and
  platform — registering it for someone else moves it (shared phones). `PUT|DELETE /api/v1/me/devices/{installationId}`
  is open to DPoP-bound tokens only (SecurityConfig, like `/api/v1/courier/**`); the app comes from the token (scope
  `courier` → courier app, else consumer app), never from the body. Permission `denied`/`undetermined` is stored
  without a token. Removal: the app at sign-out (before revoking), the worker when a provider says the token is dead,
  and a daily prune of installations not refreshed for 90 days (`PUSH_STALE_AFTER`; the apps refresh at every start).
  Revoking a sign-in in Settings › Security doesn't remove its installation (the api isn't told): the prune does.
- **Customers' notifications** (new `PersonalNotices`): order packed (whole order `ready`) — the spec's "shipped": no
  parcel carrier exists, local couriers deliver —, out for delivery (`delivery.picked_up`), delivered, booking
  confirmed, the **evening-before reminder** (design 06 Account › Notifications "Evening-before reminder"; a worker
  job every 15 min, 18:00 the day before in the customer's zone, once per booking and start time — claim
  `booking-reminder`), ETA (`booking.en_route`: "on the way", the minutes are in the app), job completed (`sign_off`
  row), quote received / revised, refund case updates and refund paid. Rows: design 06's (`booking_reminders`,
  `order_updates`, `sign_off`, `quotes_messages`, `refunds_cases`); defaults copied into
  `docs/spec/notification-matrix-defaults.json` § `customer` and kept equal to the api's `CustomerNotificationPrefs`
  by `NotificationMatrixDefaultsSpecTest`. Push, SMS ("Northline: …") and email (new template `customer-update`: the
  same line, a button to the deep link, an unsubscribe link `customer.<row>` that the api's S-13 endpoint now honours
  for the customer matrix; security rows can't be unsubscribed). Quiet hours: the person's shared `quiet_from/to`,
  off with `quiet_on=false`, in the zone of `account.preferences.province` (region model), else the platform zone.
  Language: `notify_lang` en/fr, or "same as app" = each installation's own registered language for push and the
  account's language for SMS/email. Not sent: offers, security alerts, provider messages, support cases other than
  refund cases (no events for them).
- **Couriers:** `delivery.assigned` → "New run"; new event **`run.changed`** (`fulfilment.run`, schema
  `fulfilment.run_changed.v1`) published when dispatch gives a planned run to another courier — the courier who lost
  it is told. Push only, never held by quiet hours (they are on shift), not governed by any matrix.
- **Team members get no push yet.** Studio has no native app, so no installation registers for it; their pushes find
  no device (counted `unreachable`). The S-27 matrix and quiet hours are unchanged.
- **A second consumer group without retry topics.** Customers' order/delivery/run/booking/quote topics are read by
  `personal-notifications` (`retryDelaysSeconds: []`, `@RetryableTopic(attempts = "1")`): 15 more retry topics would
  pass Event Hubs Premium's 100 per processing unit (`TopicCatalogueTest`; 97 now). Its provider outages (push
  throttling included, SMS and email too) never fail the event: they go to `messaging.deferred_notifications`, which
  the deferred job retries every 5 min (or at the provider's `Retry-After`), 10 attempts, then an ERROR — the table now
  takes `email` rows and an `audience` (V245). Team notices keep S-27's retry topics; a refund event's customer notice
  rides the `notifications` group with them and is deferred the same way.
- **Back-off:** a throttled or failing provider is paused per worker replica — `Retry-After` when given, else 30 s
  doubled per failure, at most 15 min — and isn't called while paused. Delivered to one installation = sent.
- **Payloads:** title, body, `link` (https on `CONSUMER_ORIGIN`), `data` with `type` and ids, collapse key
  `order:<id>` / `booking:<id>` / `quote:<id>` / `case:<number>` / `run:<id>`. No names, emails, phones or addresses;
  order notices carry no order number. APNs and FCM have no Canadian region: this is what goes to them.
- **Deep links** live under the paths S-97's association files (#134, `app-links.mjs`) already claim: `/app/*` for the
  consumer app, `/courier/*` for the courier app — `/app/orders/<id>`, `/app/food/orders/<id>`, `/app/bookings/<id>`,
  `/app/quotes/<id>`, `/app/cases/<number>`, `/courier/run`; custom schemes `ca.northline.app://…` and
  `ca.northline.courier://run`. S-102 serves no `apple-app-site-association` / `assetlinks.json` of its own (an
  earlier commit on this branch did, removed to avoid doing it twice). The consumer web redirects each `/app/…` link
  to its web page (`server/deep-links.mjs`; bookings and cases to the account's orders list — no web page of their
  own), so a phone without the app never lands on a 404. The mapping is in MOBILE_PLAN.md § Contracts and
  push.md § 5.
- **mobile-kit** (`src/push`): `PushRegistration` (installation id in secure storage; sync only on change; one at a
  time; offline/5xx → `false`, sent by the next sync; `enable()` asks for permission only when the app calls it;
  `unregister()` before sign-out), `pushRegistrar()` for S-97's `PushRegistrar` hook point (the consumer app installs it
  with `setPushRegistrar(pushRegistrar({...}))` once it depends on `expo-notifications`), `expoPushPlatform`
  (a structural adapter over `expo-notifications`, so mobile-kit gains no dependency), `handleNotificationTaps`,
  `parseDeepLink` / `routeOf` (strict: known host, known path, ids `[A-Za-z0-9_-]{1,64}`). **Not wired** into the
  courier app nor the consumer app: both need `expo-notifications` (a native module, its config plugin, the Android
  notification channel) and EAS push credentials — a follow-up with the Apple/Firebase set-up.
- **Secrets:** `PUSH_APNS_KEY` (`push-apns-key`), `PUSH_FCM_SERVICE_ACCOUNT` (`push-fcm-service-account`) in Terraform
  `app_secrets` (AWS, GCP, Azure), Helm `externalSecrets.secretNames` and `apps.worker.secretEnv` (required in prod);
  `PUSH_APNS_KEY_ID`, `PUSH_APNS_TEAM_ID` and the optional URLs/topics in the runbooks and `server/.env.example`.
- **Schema (V245):** `messaging.push_devices`; `messaging.deferred_notifications.audience`, `merchant_id` nullable (an
  order from several shops has no one business), channel `email` allowed. Event `fulfilment.run_changed` v1.
- **Seen, not fixed:** the `webhooks` consumer sends `orders.order_delivered` to its DLQ ("has no merchantId" — the event
  carries none); unrelated to S-102, left for its owner.

## 2026-10-02 — S-99 Consumer app Journey B: shop — home, search, product, cart, checkout (Stripe), confirmed, tracking, delivered, refund

Branch `mobile/s-99-journey-b`. **No migration** (V250–V254 unused), **no server or web change**, no new environment
variable. One new app dependency: `@stripe/stripe-react-native` 0.64.0 (the version Expo SDK 57 pins). Plan:
[MOBILE_PLAN.md § B](MOBILE_PLAN.md); runbook: [runbooks/mobile.md § Payments](runbooks/mobile.md#payments-s-99).

- **The existing consumer endpoints were enough**: `/public/shop`, `/public/home`, `/public/shop/departments|products`,
  `/search`, `/cart`, `/me/checkout`, `/me/checkout/quote`, `/me/checkouts`, `/me/checkouts/{id}/place`,
  `/me/payment-methods`, `/me/orders/{id}` (+ `/confirm`), `/me/problems`, `/me/upcoming`, `/me/account-summary`, `/me`.
  The app calls them directly with its DPoP tokens; public reads go out anonymously for guests; the guest id rides on
  every call, so a guest's cart is theirs and the api merges it at sign-in.
- **Payment through a port, following the web (S-51).** `src/shop/payments.ts`: the api's `payment.provider` picks the
  adapter. `stripe` → Stripe's React Native SDK: a **new card** is entered in Stripe's PaymentSheet for the first
  PaymentIntent (card entry is native, outside the app's JS — the app never sees a card number), its PaymentMethod
  (read back with `retrievePaymentIntent`) confirms the order's other PaymentIntents (`confirmPayment` with
  `paymentMethodId`), as the web's Payment Element + `confirmCardPayment` do; a **saved card** (`GET
  /me/payment-methods`; its id is the Stripe PaymentMethod) confirms every PaymentIntent directly. 3-D Secure is the
  SDK's native challenge; a bank app returns through `ca.northline.app://stripe-redirect` (a new route,
  `app/stripe-redirect.tsx`, hands it to `handleURLCallback`; Android uses `setReturnUrlSchemeOnAndroid`). `fake` (the
  api's stand-in, local profile and the fixture backend) → nothing collected; the design's bank step (`pay3ds`) then
  place, as the web's `BankApproval`. The **publishable key comes from the api** with each checkout — no key in the
  build, nothing per environment. The SDK is required lazily and kept out of the web build (`stripeSdk.web.ts`): it
  is native-only and broke the web bundle at start-up.
- **Idempotency:** one `Idempotency-Key` per Pay attempt, kept for every retry until the payment succeeds; a refusal
  (4xx) starts a new attempt, no answer (offline, 5xx) keeps the key so a retry replays the same checkout and can't
  pay twice; `place` has its own key. A cancelled PaymentSheet keeps the started checkout (the replay returns it).
- **Step-up before paying (S-51):** `step_up_required` → the authenticator app's 6-digit code, checked by
  northline-auth's `POST /api/auth/step-up/totp` in the app's auth session (the platform cookie store, as S-98's
  sign-in), the proof sent as `X-Step-Up`. Passkeys need a native module (S-98), so the app offers the code only;
  `second_factor_required`, or a phone without an auth session (system-browser sign-in, expired) → the person is told
  to add a factor / pay on the consumer site (opens `/account?tab=security`).
- **Apple Pay / Google Pay not offered** (the design's black button): they need an Apple merchant id, the SDK's config
  plugin and Google Pay on the Stripe account — none exist. No config plugin is used (cards need none), so
  `app.config.ts` is unchanged; the native check still passes (the SDK's card scanner would need the camera, which
  stays removed).
- **Tracking polls every 15 s** (`GET /me/orders/{id}`, `refetchInterval`) instead of the SSE stream: React Native has
  no EventSource and a polyfill is another dependency for a screen that changes every few minutes. The **map** is the
  design's schematic drawn with react-native-svg (no map SDK), with where the courier is in words ("You're next",
  "N stops before yours"), the courier's name and the drop-off PIN from S-88's `courier`.
- **Delivered:** "Shops are paid when you confirm — or automatically on {date}" uses the api's `paysShopsAt` (7 days
  for goods, CLAUDE.md); the design's "24 h" is older. All good = `POST /me/orders/{id}/confirm`. The proof photo is a
  placeholder naming the proof kind (the api has no photo URL for the customer).
- **Not on the phone, for lack of an api (raised in MOBILE_PLAN § API gaps):** the cart's "Promo or points code",
  checkout's "Redeem points" and "You'll earn N points" (checkout has no points), the delivery rating and the tip (B9),
  "Because you booked …" (Home shows the web's "Trusted near you"), photos on a report (`/me/case-uploads` needs a
  photo picker — a native module and a permission; the case takes photos on the site), the courier's rating and
  "Message". "Organic" is not a search chip (no data in the index, as on the web).
- **Not used:** `/search/suggest` and `/search/interpret` (results follow the typing, debounced; the web doesn't use
  interpret either), `/me/help/triage` (optional on the web).
- **Home:** the design's location line is the delivery location's label (tap → Location and back); the greeting is the
  time of day in the market's zone + the first name from `GET /me`; the pooled-run card is `/public/shop`'s `run`
  ("Tonight's pooled run" when it is today), free with Plus (`/me/account-summary`); department tiles open Search on
  that department (`/public/shop/departments/{slug}`, the chips applied to its products); service tiles open S-100's
  `/services/{slug}`. "Your week" rows map the api's web paths to app routes (`appRoute`).
- **Search** is the Shop's (`kind=product`), the province as `market`, the position when known; chips "On tonight's
  run" (on by default, the design's), "Under $10" (`maxPrice=999`), "Master sellers", "Halal"; "sorted by" cycles
  relevance → price ↑ → price ↓ → rating; "Show more" pages.
- **Checkout's address:** the address saved on the phone's Location screen when complete (its "unit / buzzer / drop-off
  note" becomes the courier's note, ≤ 200), else the account's default saved address, else "Add your delivery
  address" (→ `/location?next=/checkout`). Taxes are listed by name and rate from the quote ("GST 5%" is not in code).
  The api's English rule messages are worded in French in the app (as the web's `SERVER_FR`).
- **Navigation:** returning to a tab from a flow uses `backToTab()` (dismiss, then navigate): a `push`/`replace` to a
  tab path stacked a second tab navigator (found by the web smoke test). Add to cart opens the Cart tab, as the design.
- **S-102's order links** (`routeOf` → `/orders/<id>`) land on a new route `app/orders/[id]/index.tsx` that opens the
  order's tracking.
- **Cart badge** (the one shared edit): `app/(tabs)/_layout.tsx` passes `useCartCount()` to the tab bar.
- **Shared files touched beyond the plan's list:** `__tests__/journeyA.test.tsx` and `shell.test.tsx` (wait for
  `home` instead of `stub-home`), `src/fixtures/server.ts` (the shop area and its state), `e2e/smoke.mjs` (`home`, and
  a Journey B step block), `package.json` / `pnpm-lock.yaml` (the SDK).
- **Tests:** `__tests__/shop.test.tsx`, 34 tests: every screen's loading / empty / error / offline states where it has
  them, en + fr-CA, what is sent (filters, guest id, Idempotency-Key reuse, X-Step-Up, card method), the Stripe
  adapter against a mocked SDK. The web smoke test runs Home → Search → Product → Cart → Checkout → Payment (stand-in)
  → Order confirmed in headless Chromium.
- **Never run:** on a phone, simulator or emulator; Stripe's SDK for real (PaymentSheet, 3-D Secure, the return link,
  saved cards) — no Stripe account exists and the tests mock the SDK; the step-up against a real northline-auth
  session cookie on a device. **First things to try on a device with a Stripe test account:** a 3-D Secure test card
  (4000 0027 6000 3184) through PaymentSheet, then a saved card, then an order with two shops (three PaymentIntents).

## 2026-09-30 — S-101 Consumer app Journey D: account — orders, quote received, account, security, wallet

Branch `mobile/s-101-journey-d`. **No migration** (V260–V264 unused), **no server or web change**, no new environment
variable, no new app dependency. Plan: [MOBILE_PLAN.md § D](MOBILE_PLAN.md); runbook: [runbooks/mobile.md](runbooks/mobile.md).

- **The existing consumer endpoints were enough** for the five design screens and the rows of the You tab:
  `/me/account-summary`, `/me/activity`, `/me/quotes/{id}` (+ `/accept`, `/accept/confirm`, `/decline`), `/me/profile`,
  `/me/erasure-request`, `/me/addresses` (+ default, delete), `/me/household`, `/me/plus`, `/me/wallet`,
  `/me/payment-methods` (+ setup-intents, default, delete), `/me/notifications`, `/me/preferences`, `/me/export`,
  `/me/favourites`, `/me/cases` (+ detail, notes), and northline-auth's `/api/auth/security` and
  `/api/auth/step-up/totp`. The gaps below were raised, not worked around with new endpoints (none of them is small:
  points have no earning rules yet, rewards and referrals no read model, quotes no consumer messaging).
- **Screens beyond design 01's five.** The design draws Addresses, Payment methods, Notifications settings, Favourites,
  Refunds & help as rows of `account` that lead "somewhere real"; the app builds them as routes of its own under
  `/account/…` (profile, addresses, payments, notifications, preferences, favourites, help) plus `/cases/[number]`, the
  path S-102's case links already open. `src/screens.ts` is unchanged (it lists the design's screens). "Personal
  details" and "Dietary, accessibility & region" are rows of ours (the web's Profile, Language & region and Dietary &
  accessibility tabs); "Language / Langue" stays in place on the You tab (S-98) and now also sets the account's
  language (`PATCH /me/preferences {language}`).
- **Notifications:** C's `/notifications` (S-100) is the design's inbox with the quiet-hours switch; the You row opens
  the settings (`/account/notifications`): the S-58/S-59 matrix (7 rows × push / SMS / email, security locked on),
  quiet hours (21–23 h → 6–8 h, as the web), the notification language and marketing email; only changed cells are
  sent. Push (S-102): the server column is editable now; "This phone" (the permission prompt, then the device
  registration) is a port, `setPhonePush`, installed together with S-102's registrar once `expo-notifications` and its
  config plugin are in the app — until then the row is hidden rather than offering something that can't work.
- **Orders & bookings:** "Active · n" / "Past" / "Refunds" are filtered in the app (`active`; rows with a case), as the
  web does — the api has no `view` parameter (MOBILE_PLAN listed one). Rows open the other journeys' screens by route
  (`src/account/routes.ts`); a food order and a request with several quotes open the consumer site (no app screen).
- **Quote received:** the design's accept button goes straight to escrow; the api needs where the job is, so a "Where
  is the job?" step comes first (prefilled from the address saved on the phone, given to that provider only). Idempotency
  as S-99: one key per acceptance body, kept for retries, renewed after a refusal; `/accept/confirm` has its own. The
  card reuses S-99's payments port (the default saved card, else PaymentSheet); the step-up is S-99's authenticator code.
  Title, validity ("Valid 70 h"), provider tier/rating/first verified fact and the proposed time make the design's
  meta line; tax is named by its rate from the quote (no "GST" in code).
- **Security centre** on northline-auth's S-19 API in the app's auth session; 401 → "Confirm it's you" with the
  authenticator code; no auth session (system-browser sign-in) → the website. Adding a passkey / security key /
  authenticator opens the website (WebAuthn and the QR code need native support the app lacks, S-98). "Login alerts by
  email · On" reflects the security row that is always on. **Download my data** = `GET /me/export` handed to React
  Native's share sheet (no file-system module in the app). **Sign out of all devices** = revoke-others, then this
  phone's own sign-out (push hook, revoke, keys deleted).
- **Delete account** sits under Personal details › "Your data" (the web's place), asking for erasure
  (`POST /me/erasure-request`; staff confirm), shown as "Deletion requested on …" afterwards.
- **Wallet:** balance, value, the 8-week chart (an image with each week read out), Plus "Try free" (monthly / annual,
  the web's prices and 30-day trial) and "Manage" (renewal, cancel), payment methods. **Payment methods** save a card
  with a SetupIntent through a port (`cardSetup.ts`): PaymentSheet in setup mode for `stripe`, nothing for the stand-in.
- **Fixtures: one owner per endpoint, one state per fact** (after S-100 merged). Order in `src/fixtures/server.ts`:
  account, auth, geo, shop, services — the account area is first because northline-auth's area answers 404 for any
  `/api/auth/*` it doesn't know, and the security API lives in the account area. Owners: `/me/activity` and
  `/me/notifications` → the account area (its activity list holds C's inbox items — the S-100 bookings `01J9BOOKING…`,
  NL-48213, the Sable & Soda quote — plus D's past rows; the quiet hours are `server.services.quiet`, which C's
  inbox switch and D's settings both write); `/me/favourites` → the services area (`server.services.favourites`, its GET
  now returns the api's Favourite rows with name, tier and slug); `/api/auth/step-up/totp` → the auth area (D's
  security API opens once the auth area accepted an authenticator code); addresses and cards → the shop area's state.
  The fixture names the person the auth area signed in.
- **Shared files touched:** `src/fixtures/server.ts` (the area, first), `src/fixtures/services.ts` (`/me/activity`
  and `/me/notifications` handed to the account area, richer favourites), `src/shop/Pay.tsx` (the step-up words), `__tests__/shell.test.tsx` and
  `journeyA.test.tsx` (the You tab and Wallet are real now), `e2e/smoke.mjs` (a Journey D step block; the You step's
  name line), `src/journeyA/You.tsx` deleted (S-98's temporary screen).
- **Fixed in S-99's file:** `src/shop/Pay.tsx` worded a step-up failure as `shop.stepUp.${reason}`, but the reasons
  are `wrong_code | locked | elsewhere` and the key is `shop.stepUp.wrong` — a wrong code showed the raw key. It now
  maps each reason to its key (`shop.test.tsx`: "words a wrong or locked authenticator code").
- **Gaps (MOBILE_PLAN § API gaps):** points activity, provider-funded rewards near you, "Invite a neighbour", "Ask a
  question" on a quote, a payment step-up threshold, and clearing the shop-in province back to "follow my location"
  (`PATCH /me/preferences` treats null as unchanged and rejects "" — the web has the same gap).
- **Tests:** `__tests__/account.test.tsx`, 51 tests (plus one in `shop.test.tsx`): every screen's loading / error + Try again / empty / offline /
  guest states, en + fr-CA, validation messages, what is sent (Idempotency-Key reuse, X-Step-Up, only changed matrix
  cells and preferences, profile and address bodies), the Stripe paths through mocked ports. The web smoke test runs
  Orders → a quote accepted → You → Wallet → Security (confirmed with the code) → Notifications in headless Chromium.
- **Never run:** on a phone, simulator or emulator; northline-auth's security API and step-up from a phone's cookie
  store; PaymentSheet in setup mode and the quote's hold with Stripe (no Stripe account; the tests mock the ports); the
  share sheet; the notification permission prompt and device registration from this screen (the app doesn't install
  push yet).

## 2026-10-02 — S-100 Consumer app Journey C: find & book a service

Branch `mobile/s-100-journey-c`. **No migration** (V255–V259 unused) and no new environment variable. Files of the
`services` area only (MOBILE_PLAN § Working in parallel); shared files touched by one line or one case: the fixture
server's `areas`, the auth fixture (`POST /api/auth/step-up/totp`), `app/(tabs)`'s services route, the shell test's
tab id and a step block in `e2e/smoke.mjs`. How it is built: MOBILE_PLAN § Journey C as built.

- **Server additions (module `hire`, `booking`, `payments`):**
  - `GET /me/bookings/{id}` (and the checkout / confirm answers) gain `merchantId`, `state`, `timeZone` (the business's,
    from its province through the region model), `steps` (`en_route | on_site | completed | signed_off` with times; no
    GPS, approvals or photos), `report`, `photoCount` and `releasesAt` (completion + 48 h while not signed off, paid
    jobs only). New `CustomerBookings.progress(customerId, bookingId)` reads the job's log for its own customer only.
  - `POST /me/bookings/{id}/sign-off` — the customer's "Release payment": `Booking.signOff` (already in the domain,
    never wired), the log row, and a new in-process event `BookingSignedOff` that `EscrowFulfilmentListener` turns into
    `EscrowLifecycle.confirmedIfHeld` (releases at once, whichever of completion and sign-off is handled first).
    Signing off again answers the same booking; a job that isn't completed is 409 `job_state`; someone else's is 404.
    `BookingSignedOff` is **not externalized** (no worker needs it; no Kafka schema).
  - `timeZone` on the calendar (`GET /public/providers/{slug}/slots`), the provider page and each provider card, so the
    app never guesses a zone. Additive JSON only; the consumer web's zod schemas ignore the new fields.
  - Tests: `BookingCheckoutApiTest.SignOff` (the job followed en route → on site → completed, the report, `releasesAt`,
    409 before completion, 404 for someone else, 401 signed out, idempotent repeat, the event once, the escrow's
    `release_at` due), time zone assertions in the calendar, provider page and provider list tests; OpenAPI regenerated.
- **Times** are always the business's (`timeZone` from the api): slots, the calendar's day buttons (the api's dates),
  "Today 3:00 p.m." on cards (and the "Today" filter uses the business's day), steps, cancellation deadline. Fixtures
  keep businesses in `Etc/GMT+5` so tests prove the phone's zone is not used.
- **Booking wizard choices the design leaves open:**
  - The design's single "Vehicle" field stays one field ("2018 Honda Civic · ABC 1234") and is split for the api
    (year make model · plate); unparseable → the api's "Tell us the vehicle year, make and model.".
  - The api needs a spot and access instructions for visits (S-55); the time step adds the consumer web's "Where is the
    vehicle?" / "How do we get in?" chips and "Access instructions" under "Where" (prefilled from the saved address).
  - The review step adds the two checkboxes the api requires (cancellation policy, terms), worded like the web's.
  - The app books **fixed-price, instant-book visits and appointments**. Hourly (home) jobs, events and consultations
    need the web wizard's extra steps: the app opens the provider's booking page on the consumer site. Quote-only
    services, services needing approval, and "Not sure? Ask for a quote" send a quote request to **this provider**
    (`POST /me/quote-requests`, category and provider slugs); the quote itself is Journey D's screen (`/quotes/[id]`,
    S-101), where the S-102 quote link lands.
  - The wizard's answers live in memory per provider (no personal data on the phone); a guest signs in at "Review
    booking" and the answers wait.
  - `Idempotency-Key`: one per set of checkout answers (`randomId`), so a retry after a failed card replays the same
    PaymentIntent, and changed answers don't collide with the stored one (409 `idempotency_key_reused`); one key for the
    confirmation.
  - Step-up (S-51) with the **authenticator code only** (`POST /api/auth/step-up/totp` in the app's auth session, as
    S-99 does); no auth session on the phone (browser sign-in) → "confirm on the website". No second factor at all →
    "Add a passkey" on the consumer site's security page.
- **Payments:** like the consumer web — the api opens a manual-capture PaymentIntent and says the provider. A port,
  `src/services/payments.ts`: `stripe` → `@stripe/stripe-react-native` 0.64.0 (the same version S-99 adds): a saved
  card's PaymentMethod via `confirmPayment`, or a new card in **PaymentSheet** (Stripe's native UI; 3-D Secure is
  Stripe's); `fake` → nothing to collect. The adapter is `stripe.native.ts` (iOS/Android only) with a `stripe.ts`
  stand-in for the web build: bundled for the web, the SDK broke the whole page at start ("__fbBatchedBridgeConfig is
  not set") even behind a lazy `require` (S-99 met the same and keeps its SDK off the web build too). The app never handles card numbers.
  The design's "Pay with Visa ··4471 · Change" is the saved cards (`GET /me/payment-methods`) plus "New card".
- **Copy left out because no data backs it:** "62 categories" (the api's count is used), the seasonal tags ("Snow
  removal season"; "Popular: {category}" from the api's provider counts instead), distances on provider cards, the
  member's SLA, "Earn N points", "Added to your calendar", the receipt PDF, the reliability score on the review.
- **Day-of ETA (C9)** shows the booking's state ("comes Thu · 9:00 a.m.", "is on the way", "has arrived", "has
  finished"), who comes, the address and the steps with times; it refreshes every 30 s while en route / on site (the
  push says so too). Not built (no api): the live map and minutes away, member photo / vehicle / plate, Call, sharing
  the access code; "Simulate job complete →" is a design demo control.
- **Sign-off (C10):** report, "N completion photos on file" (the customer can't download job media yet), receipt line,
  "Release payment" (the new endpoint), "Raise an issue" → Journey B's `/problem/booking/[id]`, the automatic release
  in N h from `releasesAt`; signed off: "Released. $… paid to …" and "Rate …".
- **Two-way review (C11):** no consumer endpoint posts reviews (MOBILE_PLAN § API gaps, raised, not worked around). The
  screen keeps the design's form but says on screen that the rating isn't sent yet; "Submit review" saves only the
  favourite (`PUT /me/favourites/{id}`) and returns to Orders.
- **Notifications (C8)** is `GET /me/activity` as an inbox (All / Bookings / Orders / Offers — no offers feed exists, so
  that filter is empty) with "Quiet hours 10:00 p.m. – 7:00 a.m." from `GET /me/notifications` and a switch for them
  (`PUT`). Rows open the booking (its deep-link screen), the order's tracking (Journey B) or Orders (Journey D).
- **Deep link:** `/bookings/<id>` (S-102) is a new route file in the area (`app/bookings/[id]/index.tsx`) that opens the
  sign-off once the job is completed / signed off and the ETA screen otherwise; guests see the sign-in prompt.
- **Provider page:** the design's "Message" button has no consumer messaging api; the second button is favourites
  (`GET`/`PUT`/`DELETE /me/favourites`). The hero is the accent colour (the provider page api has no brand colour; the
  cards' `brandColor` is used for their marks when it is a `#rrggbb`).
- **Tests:** api `BookingCheckoutApiTest` +2 (sign-off), time zone assertions in 3 classes; app
  `__tests__/services.test.tsx` 72 tests — every C screen's loading skeleton, error with Try again and offline banner
  (11 × 3), the empty states, each screen's behaviour on the fixture backend (what is sent: holds, checkout,
  confirmation, sign-off, quote request, favourites, quiet hours), the Stripe port (saved card, cancelled sheet,
  declined card), step-up and enrol, slot taken, hold expired, the deep link, French, and the rules (vehicle split,
  first-step messages en/fr, filters on the business's day, "ago"). The web smoke test (`e2e/smoke.mjs`) runs Journey
  C end to end in headless Chromium: browse as a guest, sign in, book a visit, notifications, sign-off, review.
- **Never exercised:** a phone, simulator or emulator; real Stripe (PaymentSheet, `confirmPayment`, 3-D Secure, saved
  cards) — only the port with a test double, and the api's fake gateway; the authenticator step-up against a real
  northline-auth from the app's cookie store; push → `/bookings/<id>` on a device. The web smoke test runs the journey
  in headless Chromium on the fixture backend.

## 2026-10-02 — S-103 App Store / Play Store release pipeline and store listings (en/fr)

**Nothing has been submitted to Apple or Google, and no EAS build, submit, update or metadata push has run: no Expo
organisation, Apple Developer account, App Store Connect record, Google Play Console account or Firebase project
exists.** Everything that talks to them is written against Expo's, fastlane's and the stores' documented behaviour and
is inert (skips with a notice) until the secrets exist. Runbook: `docs/runbooks/mobile-release.md`.

- **Tooling: EAS Build + EAS Submit + EAS Update + EAS Metadata, plus fastlane for what EAS doesn't do.** Both apps
  already had `eas.json`, `credentialsSource: remote`, `appVersionSource: remote` and `expo-updates` (S-87, S-97), so
  EAS stays the build and upload path. EAS Submit can only upload a new binary to a track; promoting the tested
  internal build to production as a staged rollout, changing / halting the rollout and sending a TestFlight build to
  App Review are fastlane lanes (`mobile/fastlane/Fastfile`: `android listing|promote|rollout|halt`, `ios
  submit_review`), as samop-inv-ship-26 does it. One Fastfile for both apps (`NL_APP`).
- **Flow:** version bump → production build (tag `<app>-vX.Y.Z`) → EAS submits with profile `internal` (TestFlight +
  Play internal, completed) → device checklist → iOS App Review with manual release and 7-day phased release; Android
  promote to production at 10 % → 50 % → 100 % (or halt). `submit.production` (Play production as a draft) stays as a
  manual fallback. Submit profiles share `submit.base.ios` (placeholders until the records exist).
- **Environments:** unchanged profiles — `development`/`development-device` = dev, `preview` = staging, `production` =
  prod; channel = profile name = EAS environment. The origins are the runbooks' (`api.<zone>/api/v1`, `auth.<zone>`,
  `<zone>`); the release check holds `eas.json` to one table (`packages/release/src/spec.mjs`). The courier app has no
  `EXPO_PUBLIC_SITE_ORIGIN` (it doesn't use one) — not added.
- **One version source:** each app's `package.json` `version`; `app.config.ts` reads it (`APP_VERSION`), the release
  tag must equal it, `make mobile-version-set` writes it. Build numbers stay EAS's (remote, auto-increment). The apps
  are versioned and released independently (`consumer-v*`, `courier-v*`).
- **OTA pinned to the runtime:** `runtimeVersion: { policy: 'fingerprint' }` kept; new `fingerprint.config.js` in both
  apps skips `extra` (the `EXPO_PUBLIC_*` values, project id) and the version numbers. Verified offline: a version bump
  and a different api URL leave the fingerprint unchanged; another variant changes it. `make mobile-update` exports
  the profile's variables before `eas update` (otherwise the bundle carries localhost URLs) and passes
  `--environment`. Rollback: `update:republish` of a good group or `update:roll-back-to-embedded`.
- **Push (S-102's follow-up):** `expo-notifications` (~57.0.21) and its config plugin added to **both** apps:
  `aps-environment` (`production` for preview and store builds, `development` for development), Android channel
  `updates` (what the worker sends to), `POST_NOTIFICATIONS` listed explicitly, `android.googleServicesFile` from the
  EAS file variable `GOOGLE_SERVICES_JSON` (absent → builds still work, no FCM token). The consumer native check now
  asserts the entitlement and the channel. **Not done:** installing the registrar (`setPushRegistrar` in
  `app/_layout.tsx` and the permission moment) — app code, and the consumer app is being changed concurrently; since
  the native module is now in the binary, that wiring can ship as an over-the-air update. Needs real accounts: the
  Push Notifications capability on the bundle ids, the APNs key and the Firebase projects (worker side, push.md), the
  `google-services.json` per environment (EAS).
- **Store listings as files:** App Store via EAS Metadata (`store/store.config.json`: texts, URLs, categories, age
  rating answers, manual + phased release); Play in fastlane supply's layout (`store/play/<locale>/…`), with Play's
  console-only settings, the IARC answers, target audience and app access in `store/play/details.json`. Locales
  **en-CA and fr-CA** in both stores (the brief's "en" taken as English (Canada), the stores' Canadian English).
  Region-neutral copy ("opening province by province"); the check refuses province, territory, city and zone names.
- **Privacy answers** (`store/privacy.json`, entered by hand — neither console has an API): consistent with what the
  apps send. Consumer: name, email, phone, address, precise location (optional, in use), user id, **device id (push
  token + installation id)**, purchase history, **payment info (Stripe's SDK in the app's sheet)**, customer support
  (problem reports), other user content (booking notes). Courier: precise location (incl. background during a run),
  photos, user id, device id, other user content (the customer's signature). No tracking, nothing shared (Stripe,
  APNs, FCM are service providers), encrypted in transit. The privacy manifests in both `app.config.ts` gained the
  missing types (device id, purchases, payment info, support, user content), and the check keeps manifest ↔ answers ↔
  Android permissions equal.
- **Age ratings:** consumer **18+** — the marketplace lists alcohol, tobacco and vape (seed categories) and the Terms
  require the age of majority; courier mild alcohol references, audience 18+. Apple's newer questionnaire items aren't
  in EAS Metadata — answered in the console.
- **Screenshots:** a documented list per app (`store/screenshots.json`: route, state, caption en/fr) and flat
  placeholders at the stores' sizes (iPhone 6.9" 1320 × 2868, Play 1080 × 1920, feature graphic 1024 × 500) written
  from the brand tokens by `make mobile-store-placeholders` (~740 KB for both apps). The listing step never uploads
  images; `--strict` refuses while `placeholder: true`.
- **Offline validation:** new workspace package `@northline/mobile-release` (`mobile/packages/release`, plain ESM with
  JSDoc, `tsc --checkJs`, `node --test`): eas.json rules (profiles, origins, channels, remote versions, autoIncrement,
  store distribution, AAB, no secrets / key paths / `releaseChannel`, submit profiles), listing limits per store
  (keywords counted in UTF-8 bytes), en/fr-CA parity and "not the English text", region-neutral text, https URLs,
  categories, every age-rating answer, no review account in the repo, privacy answers ↔ manifest ↔ permissions,
  screenshots and sizes, version source and fingerprint runtime. **Errors** fail `pnpm lint` (appended to the mobile
  workspace's lint) and so every mobile CI `checks` job; **pending** items (store ids, placeholder screenshots, account
  deletion) fail only `check --strict`, which the release pipeline runs before `listing`, `submit-review`, `promote`.
- **CI:** `.github/workflows/mobile-release.yml` (workflow_dispatch only; `contents: write` for the release tag) and
  `ci/gitlab/mobile-release.yml` (`PIPELINE_PART=mobile-release`, `RELEASE_*` inputs, EAS and store jobs `when:
  manual`; the job token can't push tags, so on GitLab the person creates the tag first). Store keys are written to the
  runner's temp directory per run and deleted. `actionlint` clean.
- **Secrets:** `EXPO_TOKEN`, `ASC_API_KEY_P8` (+ `ASC_API_KEY_ID`, `ASC_API_ISSUER_ID`), `PLAY_SERVICE_ACCOUNT_JSON`,
  `EAS_CONSUMER_PROJECT_ID` / `EAS_COURIER_PROJECT_ID` / `EAS_OWNER`, EAS file variable `GOOGLE_SERVICES_JSON` — in CI
  and EAS, not the cluster (secrets.md § Mobile release); no Helm / Terraform / `.env` change (no running app reads
  them). No schema, no server change.
- **Make:** `make/mobile-release.mk` (`MOBILE_APP=consumer|courier`): offline `mobile-release-check(-strict)`,
  `mobile-release-test`, `mobile-version(-set)`, `mobile-release-config`, `mobile-fingerprint`,
  `mobile-store-placeholders`; with accounts `mobile-eas-build`, `mobile-eas-submit`, `mobile-store-metadata`,
  `mobile-update`, `mobile-update-republish`, `mobile-update-rollback-embedded`.
- **Open (blocks going public, listed by `--strict`):** the App Store Connect app ids and Apple team id; real
  screenshots; **account deletion** — Apple 5.1.1(v) needs in-app deletion for the consumer app (it creates accounts)
  and Play a deletion link; S-105 builds erasure. Also: `https://northline.ca/help` (the support URL, as the design's
  footer names it) doesn't exist yet; the privacy policy is English only (fr-CA listing links the same page);
  `support@northline.ca` as the Play contact is assumed; whether the courier app should be unlisted / private; the
  store icon is still S-97's placeholder.
- **Never exercised:** `eas build/submit/update/metadata:push` (incl. the `EXPO_ASC_*` variables EAS Metadata reads in
  CI), every fastlane lane, both pipelines end to end, the stores' validation of the listing files, push delivery.
  What ran: the release checks and their 22 unit tests, the consumer `expo prebuild` native check with the push
  plugin, `expo config` per profile, the fingerprint comparisons, `actionlint`, `ruby -c`.

## 2026-10-02 — Phase 4 follow-ups: webhooks order_delivered DLQ, favourites refresh, province follow-location

Branch `fix/phase4-followups`. Three small fixes; no migration, no new configuration.

- **Webhooks dead-lettered `orders.order` events.** `WebhookPayloads.of` read `merchantId` before deciding whether an
  event is delivered at all, so every event on a webhooks topic without one failed, was retried and went to the
  `.dlq`: `orders.order_delivered` (seen in S-102) and also **`orders.order_confirmed`**. `merchantId` is now read
  only inside the mappings that use it; an event partners don't get is skipped, as the others already were.
- **`order.delivered` names its businesses — event change, not a module call.** The webhooks consumer runs in the
  worker, outside the api's modules, so "resolve the merchant through a module API" would have meant the worker reading
  `orders` tables (SchemaOwnershipTests' rule) or calling the api over HTTP. Instead `orders.api.OrderDelivered` gains
  **`merchantIds`** (every business with lines on the order, from `orders.order_lines`; one for food, several for a
  pooled goods order) as an **optional field of `orders.order_delivered` v1** — additive under S-34 (not required, so no
  `v2`; the contract check's breaking-change rule passes). Ids only, no customer id. A list rather than one
  `merchantId`: the event is per order, and one event per shop (as `order.placed`) would have run the escrow, tracking
  and customer-notice listeners once per shop.
- **Partner webhook `order.delivered` is now sent** (it was subscribable but in `NOT_YET_PUBLISHED`, DECISIONS S-33,
  S-78): one copy per business in `merchantIds`, each under its own `merchantId` with the same event id (dedupe is per
  endpoint). Public payload v1 `docs/spec/webhooks/order.delivered.v1.schema.json`: `data` = `orderId`, `orderType`,
  `proof` — no lines (the per-shop lines would need a per-shop event; partners have them from `order.placed`). An
  `order.delivered` published before `merchantIds` existed goes to nobody (logged), not to the DLQ.
  `WebhookPayloads.of` now returns a list (one payload per business).
- **Other events checked for the same gap:** on the webhooks consumer's topics (`booking.booking`, `payments.escrow`,
  `payments.refund`, `orders.order`) only `order.delivered` and `order.confirmed` lacked `merchantId`; the food events
  on `orders.order` (`order.accepted/ready/handed_off`) carry it. `order.confirmed` has no public webhook type, so it
  gets no `merchantIds` here (it is only no longer dead-lettered). The `fulfilment.*` events have no `merchantId`
  either, but no consumer needs one: the webhooks consumer doesn't read those topics, and the notifications and search
  consumers don't read `merchantId` from them.
- **Mobile favourites:** the provider profile's heart and the review's "add to my favourites" (Journey C) invalidated
  only `['services', 'favourites']`; they now also invalidate Journey D's `['account', 'summary']` and
  `['account', 'favourites']` (`favouritesChanged` in `src/services/parts.tsx`), so You's count and the Favourites
  list refresh at once instead of after their minute of staleness.
- **Province back to "follow my location":** the endpoint is `PATCH /api/v1/me/preferences` (not a PUT). **`province:
  ""`** (or blank) now clears the choice, the same convention the endpoint already uses for `allergies` and
  `accessNotes`; omitted or `null` still means unchanged, and `""` used to be a 422, so no existing client changes
  behaviour. JSON `null` as "clear" was not chosen: the request record can't tell a sent null from a missing field.
  The mobile Region screen always offers "Follow my location" now (it was hidden once a province was chosen) and the
  consumer web's Language & region page sends `""` for it (it sent nothing, so the choice never cleared); both show a
  line saying what it means (en + fr-CA). The mobile form compares a save with the api's last answer, so a second save
  right after the first sends the right change. MOBILE_PLAN § API gaps updated.
- **Tests:** api `DeliveryEscrowTest` +1 (the published `order.delivered` names both shops of a pooled order, no
  customer id), `AccountSettingsApiTest` +1 (choose → omitted / null keep it → `""` clears it, in preferences and the
  account summary → unknown code 422; the province is the region model's first, no literal); worker
  `WebhookPayloadsTest` +2 (one copy per business; an old `order.delivered` and `order.confirmed` map to nothing),
  `WebhookDeliveryTest` +1 (Kafka → both shops' subscribed endpoints get a signed `order.delivered`, an unsubscribed
  endpoint and another shop get nothing, `order.confirmed` and an old `order.delivered` are processed, nothing failed
  or dead-lettered); mobile `account.test.tsx` +3 (heart on C → You's count and the list at once; back to follow my
  location sends `""`; French); web `settings.test.tsx` +2. The mobile and web tests fail without their fix.
- **Not done:** `order.confirmed` as a partner webhook; per-shop lines in the `order.delivered` payload.

## 2026-09-30 — S-105 Privacy rights: access, correction and erasure (PIPEDA, provincial acts, Law 25)

Branch `security/s-105-privacy-rights`. Runbook: [runbooks/privacy-requests.md](runbooks/privacy-requests.md).

- **Region-neutral law.** Which law applies is the person's province in the region model (`region.regions.privacy_law`,
  V130); what the law requires is the new `region.privacy_laws` (V271): names en/fr, short names (PIPEDA / LPRPDE …),
  the regulator and its URL, response days, business-day counting, the one extension. New `region.api.PrivacyRegimes`
  (`forProvince`, `of(law, province)`, `deadline`); business days skip weekends and the province's holidays from the
  region model. No law, deadline or province is in code; messages name the law from the request (`law.shortName`) or
  the ambient `{privacyLaw}`. **Values drafted from the statutes, for legal review:** PIPEDA 30 + 30, Alberta PIPA 45 +
  30, BC PIPA 30 business days + 30, Québec 30 + none. The person's province: their default address, else the first
  business they are on the team of, else `REGION_DEFAULT_PROVINCE`. The clock starts at receipt.
- **One module, one SPI.** New module `privacy` (schema `privacy`, V270): `privacy.requests` (state machine, channel,
  law and province, due date, one extension, verification, decision, sealed contact and corrections, export key, link
  hash, open holds) and `privacy.erasure_steps`. Every module that owns a schema implements
  `shared.privacy.PersonalDataContributor` (export sections, idempotent erase, optional corrections) as a
  package-private `<Module>PersonalData` in its `persistence` package over its own tables — the privacy module never
  reads another schema. It lives in `shared` because privacy depends on identity, region, payments and developer, which
  would make a `privacy.api` SPI a cycle. `PrivacyContributorsTests` (ArchUnit): every module with a schema has one,
  they are package-private adapters, and the privacy module uses other modules' `api` packages only. `SchemaOwnershipTests`
  knows the `privacy` schema.
- **States:** awaiting_verification → verified → in_progress → completed, or withdrawn / rejected (codes:
  identity_not_verified, not_our_data, legal_exception, duplicate, frivolous). One open request per person and type
  (unique partial index; 409 `request_open`). Access starts when verified; erasure after `PRIVACY_ERASURE_GRACE` (7 days,
  never later than a day before the deadline; staff can start it now; the person can withdraw until then); a correction
  waits for staff. The request row is kept as the accountability record (ids and codes); the person's contact at the
  time and the corrections they asked are sealed (`SecretSealer`, bound to the request id) and wiped when it closes.
- **Identity check (step-up):** a fresh northline-auth step-up proof (`X-Step-Up`, the S-51 verifier through
  `payments.api.PaymentStepUp`) verifies at once — `acr=mfa` alone is not enough (a refreshed token keeps an old
  sign-in's acr). Without a proof the api texts a 6-digit code to the verified mobile (sha-256 stored, 15 min, 5 tries,
  resend after a minute) — phone-code accounts have no other factor and must be able to delete themselves in the app.
  Staff-recorded requests (email, mail) are verified by staff. 403 `step_up_required` is localised by the catalogue.
- **Erasure pipeline:** on start the account closes (`identity.api.PrivacyAccounts.close`: status `erased`, sign-ins
  ended with the new reason `erased` (V272), passkeys and console roles removed), then one step per contributor in
  order, identity last. Each contributor's erase runs in one transaction with its step row (crash-safe: a step is done
  or not), retried with back-off on failure, `held` steps every `PRIVACY_HOLD_RETRY`; replicas share work with `SKIP
  LOCKED`. A request completes when nothing is pending or failed — holds may remain (`holds_open`), the law's answer
  doesn't wait for a customer's open order. Kept data is pseudonymised under the blanked account id; the list per module
  and its reasons is in the runbook. Holds: open orders, upcoming bookings, held escrow, open refunds and disputes,
  deliveries under way, the only owner of an active / paused / pending / suspended business (an applicant's membership
  goes; the application stays orphaned).
- **Revoking sessions and tokens:** identity ends `identity.sessions` (northline-auth's `RevokedSessionFilter` drops the
  auth session); northline-auth's token customizer refuses any token, new or refreshed, for an `erased` account
  (`invalid_grant`), and its sign-in refuses one ("This account has been deleted."). `ErasedAccountsJob` (auth, every
  5 minutes) deletes the account's WebAuthn credentials, TOTP secret, backup codes, federated identities, consents and
  OAuth authorizations — the auth schema stays northline-auth's. Only `erased` is refused: suspended accounts keep their
  current behaviour (out of scope). An access token already issued lives out its 10 minutes.
- **Object storage behind the port:** `ObjectStore.deleteAll(prefix)` (S3 list + batch delete, GCS list, Azure list,
  the folder store, prefixed and guarded wrappers; the shared contract test covers all four providers). Messaging's
  `AttachmentStorage.deleteAll` removes `messaging/customers/<id>/`. Proof-of-delivery photos and dispute evidence are
  kept (chargeback evidence).
- **Events, ids only:** `privacy.personal_data_erased` (in-process) and `merchants.merchant_data_erased` (request id,
  business id) for each business whose public data changed. The latter is **published on `merchants.merchant`**, not a
  topic of its own: a `privacy.merchant` topic with the search indexer's three retry topics took Azure Event Hubs to
  102 of its 100 hubs (`TopicCatalogueTest`); on the business's topic it is ordered with the business's other events and
  the indexer already reads it (`Scope.Merchant`). The search index holds no personal data, so search removal is that
  refresh.
- **Reviews:** V272 replaces `trust.reviews_immutable()` so a transaction that sets `northline.privacy_change = on`
  (`PersonalDataSql.allowPrivacyChange`) may blank a review's words and author name or correct the name; the rating and
  everything else stays immutable, and words can only be blanked, never rewritten.
- **Access export:** every contributor's sections rendered by Postgres (`json_agg`: timestamps, jsonb and arrays as
  stored) in one JSON (`northline.privacy-export/v1`) plus a readable summary (en/fr), sealed together and stored at
  `privacy/exports/<id>.bin` (new `ExportStorage` port: object storage, a temp folder under `local`/`test`, a loud
  placeholder elsewhere with `STORAGE_PROVIDER=local`). Downloads through a link token (256 bits, stored hashed,
  `PRIVACY_LINK_TTL`) at `GET /api/v1/public/privacy-exports/{token}` — public, so the app's share sheet and a browser
  tab can fetch it; unknown or expired → 404. Bundles deleted after `PRIVACY_EXPORT_TTL` (hourly sweep). The old
  `GET /api/v1/me/export` (S-59, unverified, unsealed) is **removed**, and so is `POST /api/v1/me/erasure-request`
  (S-59, a flag staff acted on by hand): every client now uses the privacy requests. `identity.users.erasure_requested_at`
  stays (set when an erasure is verified, cleared when withdrawn) for the profile's "You asked to delete…" line.
- **Corrections** (fields people can't edit): `phone` (identity; E.164, unique), `receiptName` (payments), `reviewName`
  (trust), `legalName` (merchants, principal). Staff apply what they accept; each field audit-logged by code.
- **Console:** new role `privacy` (privacy officer; V272 widens `identity.platform_roles`), screen `privacy` (admin,
  privacy, support lead) and action `privacy` (the same three). Support leads take requests that arrive at the help
  desk. Endpoints `/api/v1/console/privacy-requests` (queue, record, detail, verify, extend, reject, start,
  corrections, retry), all `@RequiresConsole`. Console web: `/privacy` with the queue, the detail drawer (law, dates,
  what was asked, the erasure's steps with what is kept and held) and the actions; Team lists the new role.
- **Audit:** every transition, step, link, download and correction is a `developer.audit_log` row
  (`privacy.request_<what>`, target `privacy_request`); system steps have actor and role `system`.
- **Clients.** Consumer web: Account › Profile › "Your data" (copy, correction, deletion; texted code, passkey); the
  Security tab's "Download my data" links there. Consumer app: new `/account/data` screen (in-app deletion — App Store
  5.1.1(v) — with the texted code or the authenticator, the copy to the share sheet, corrections); Profile and Security
  link to it; `store/privacy.json` answers deletion `inApp: true` with the web URL; the courier app's stays pending
  (accounts made by Northline). Studio: Settings › Security › "Your personal data" (copy and deletion with the
  passkey / authenticator step-up) for team members and owners.
- **Also fixed:** the mobile `services.test` filter test passed `now` nowhere and failed on 2026-10-02 (the real date).
- **Schema (V270–V272):** `privacy` schema, `privacy.requests`, `privacy.erasure_steps`, `privacy.request_number_seq`;
  `region.privacy_laws`; `identity.users.erased_at`; `identity.sessions.revoke_reason` + `erased`;
  `identity.platform_roles.role` + `privacy`; `trust.reviews_immutable()` replaced. New optional variables
  `PRIVACY_ERASURE_GRACE`, `PRIVACY_EXPORT_TTL`, `PRIVACY_LINK_TTL`, `PRIVACY_HOLD_RETRY`, `PRIVACY_RUN_INTERVAL`
  (runbooks, `.env.example`); no secret.
- **Tests:** a contributor test per module (15: export, erase, kept / held, idempotent, corrections), `ErasurePipelineTest`
  (every module, a failing module retried and resumed, a hold completing the request and clearing later, events, audit,
  within the deadline), `PrivacyRequestsApiTest` (step-up, texted code, wrong code counted, French, grace period, law by
  province, withdrawal, export → link → download → expiry, someone else's 404), `PrivacyDeskApiTest` (roles, MFA, record,
  verify, extend once, a law with no extension, refuse, start, corrections, audit roles), `PrivacyRegimesTest`,
  `PrivacyContributorsTests`, storage contract `deleteAll` on four providers, auth `TokenClaimsTest` (no token for an
  erased account, purge), worker `ScopeTest`; web consumer `privacy.test.tsx`, console `privacy.test.tsx`, Studio
  settings; mobile `account.test.tsx` (Your data).
- **Not done / never run:** legal review of the deadlines; staff email to the person when a request is answered (the
  account shows the state; email templates are a follow-up); notices to third parties a correction was shared with;
  closing a business as part of an owner's erasure; revoking Google / Microsoft calendar grants at the provider and
  deleting the Stripe Customer object (cards are detached); verification by email for an account with no mobile and no
  second factor (staff verify); access tokens already issued live out their 10 minutes. Nothing ran against a real KMS,
  bucket or Stripe.

## 2026-09-30 — S-114 Backups, point-in-time restore and DR drill

Branch `ops/s-114-backups-dr`. Terraform for the three clouds, restore/masking/verification scripts, a local drill
that was run, and [docs/runbooks/backups-dr.md](runbooks/backups-dr.md) (RPO/RTO, procedures, roles, quarterly
drills). **No real cloud backup, copy or restore has ever run**: no cloud account exists; the Terraform is validated
and planned offline against mocked providers, and `scripts/dr/restore.sh` has only printed its `aws`/`gcloud`/`az`
commands (`--dry-run`). The local drill (PostgreSQL 17 + PostGIS containers) did run and passed. No migration, no
new application variable or secret, no Helm chart change.

- **Targets.** Postgres is the source of truth; prod targets: zone failure RPO 0 / RTO 5 min (HA), logical damage
  RPO 5 min / RTO 2 h (PITR into a new instance), regional loss RPO 15 min (AWS, Google Cloud) or 1 h (Azure) / RTO
  4 h. Object storage: versions (RPO 0), replica RPO 15 min / RTO 1 h. All targets until the first cloud drill.
- **Restores never overwrite.** Every restore creates a new instance; switching is a reviewed `configEnv.DB_URL` in
  `deploy/argocd/envs/<env>/values.yaml` (layered after Terraform's `infra.yaml`), so no chart change was needed and
  the migration Job follows the same value. `DB_PASSWORD` stays (a restored database keeps its roles).
- **Copy in the other Canadian region, per cloud** (contract input `backup_copy` on `postgres`, prod only via the
  stack's `backup.cross_region`): AWS replicates the automated backups (`aws_db_instance_automated_backups_replication`,
  14 days, PITR there) and prod now keeps automated backups when the instance is deleted; Google Cloud gets a
  **cross-region read replica** instead of moving backups, because Cloud SQL backups are encrypted with the primary
  region's CMEK key and could not be restored while that region is down; Azure uses geo-redundant backup to the paired
  region (only settable at creation — prod already had it, so nothing is replaced). The secondary region is derived
  from the primary (ca-central-1 ⇄ ca-west-1, northamerica-northeast1 ⇄ -northeast2, canadacentral ⇄ canadaeast), and
  module validations refuse any other region.
- **Keys for the copies.** "Encryption with the existing KMS keys" cannot hold literally on AWS and Google Cloud: keys
  are regional, and a copy encrypted with the primary region's key is unreadable in the disaster it is for. The
  existing `kms` module is instantiated a second time in the secondary region (`kms_backup`, one `data` key, the Cloud
  SQL and Cloud Storage service agents granted); `kms/aws` now sets `region` on its resources (a no-op for the
  primary). Azure reuses the existing `data` key: Key Vault keeps a read-only copy in the paired region.
- **Object storage** (contract input `replica` on `storage`, prod only): S3 replication with delete markers,
  Replication Time Control (15 min) and metrics; Google Cloud an event-driven Storage Transfer Service replication job
  (deletes not propagated); Azure object replication to a second account (change feed on the source; deletes not
  propagated). Replicas are versioned with lifecycle rules: a cooler class after 30 days, older versions expire after
  90. Existing objects need one batch copy on AWS / Google Cloud after the first apply (runbook).
- **Elasticsearch**: no repository of our own. Elastic Cloud already snapshots every deployment to `found-snapshots`;
  the ec provider can only configure snapshots on ECE; the read model is rebuilt from Postgres with S-71's alias
  reindex, which is mandatory after any database restore.
- **Kafka is not backed up**: topics are transient or re-derivable, the outbox (`events.event_publication`) is in
  Postgres, consumers dedupe; restoring old topic contents next to a restored database would replay events. Lost:
  events Kafka accepted that no consumer processed.
- **Valkey is not backed up**: sessions, caches, rate limits, 24 h idempotency keys (payments also record them in
  Postgres), slot holds, live tracking. AWS's existing ElastiCache snapshots stay but must not be restored.
- **Prod → staging masks inside prod's boundary.** The backlog's acceptance ("restore of prod snapshot to staging")
  overrides the earlier runbook rule "staging holds synthetic data only": staging may now hold a **masked** copy, and
  only through `scripts/dr/prod-to-staging.sh` — restore into a temporary instance in prod's account, mask in one
  transaction, `mask-check.sql` (fails → staging untouched), dump, restore into a re-created staging database as the
  owner role, fingerprints compared, mask check again on staging. staging.md and dev.md say so.
- **Masking is per schema SQL** (`db/dr/mask/<schema>.sql`), not S-105's code: S-105 (privacy rights) was not merged and
  its per-module `PersonalDataContributor`s erase one subject, not a whole database. Pseudonyms derive from row ids
  (unique e-mails `…@example.invalid`, `+1555…` phones by row, valid unique business/GST numbers, lowercase suffixes so
  the scan doesn't mistake them for postal codes); locations snap to ~1 km; credentials, tokens, sessions, passkeys,
  push devices and gate codes are deleted; webhooks are switched off and pointed at `webhooks.example.invalid`; API
  keys revoked. The immutability triggers of `trust.reviews` and `developer.audit_log` are disabled for the masking
  transaction only (the masking runs as the table owner). Quotes stay (merchant text, immutable). Follow-up once S-105
  merges: a test that every column a contributor erases is masked.
- **mask-check** = invariants per table + a scan of every text/JSON column for e-mails (outside example/documentation/
  platform domains), North American phone numbers (outside 555) and full postal codes; public business content is
  skipped by name. It catches a new personal column holding those three kinds of value, not names or free text.
- **Verification** (`db/dr/fingerprint.sql`): Flyway version/count/failed, exact row counts of every application table,
  order-independent checksums (count + sum of 60-bit row-hash prefixes: constant memory) of key tables, and `asof`
  checksums of append-only tables (ledger, audit log, Flyway history) up to a time — how a PITR copy is compared with
  the live database.
- **Tools**: client tools must match the server major (17); the scripts run `pg_dump`/`pg_restore`/`psql` from the
  PostGIS image through Docker when the local ones are older (`DR_PG_TOOLS`). Restores are refused on databases with
  other sessions; `restore.sh delete` only deletes instances named `*restore*`/`*mask*`/`*drill*`.
- **Local drill run (2026-10-02)**: base backup + WAL archive, PITR to a restore point after a simulated disaster
  (verified equal to the source at T; disaster undone), dump restore (identical), prod → staging masking (mask check
  passed both sides, fingerprints identical). PITR restore 7.0 s, prod → staging 37.4 s on a 76 MB database with
  200 000 ledger rows; numbers in the runbook's drill log. A 2 000 000-row run was not possible: the shared machine ran
  out of disk.
- **Not done**: no cloud run of anything; backups live in the same account/project/subscription as prod (a locked
  vault in a separate account is the next step); secrets are not replicated to the secondary region; no
  Elasticsearch copy in the secondary region; alerts on backup failure are listed per cloud in the runbook but not
  wired (S-113 was not merged — link its runbook when it is); Cloud SQL Enterprise keeps 7 days of logs (35 needs
  Enterprise Plus).

## 2026-10-02 — S-113 Alerting and on-call (SLOs for sign-in, checkout, payouts, KDS)

Branch `ops/s-113-alerting`. **No migration** (the V280–V284 range offered is unused). Runbook:
[runbooks/alerting.md](runbooks/alerting.md); one runbook per alert in [runbooks/alerts/](runbooks/alerts/README.md).

- **No pager has ever fired for real.** No metrics store with rules, no Alertmanager and no PagerDuty, Opsgenie or
  Grafana OnCall account exists in any environment. Everything here is checked offline only: `promtool check rules`
  and `promtool test rules` (Prometheus 3.5.0) on every rule, `amtool check-config` (Alertmanager 0.28.1) on the
  reference and the chart's rendered configurations, Sloth 0.12.0 regenerating the SLO rules without a diff,
  kubeconform on the rendered PrometheusRule / AlertmanagerConfig / GMP Rules. No alert has reached a phone, no
  receiver key has been used, and the Docker fallbacks of `make obs-rules-check` (images `prom/prometheus`,
  `prom/alertmanager`, `ghcr.io/slok/sloth`) could not be pulled in the build sandbox (rate limit / blocked) — they
  ran from the binaries.
- **SLOs as code = Sloth `prometheus/v1` specs** (`deploy/observability/slo`), not a home-grown generator and not
  OpenSLO: Sloth produces the standard multi-window multi-burn-rate rules (page 14.4x 1 h/5 m or 6x 6 h/30 m; ticket
  3x 1 d/2 h or 1x 3 d/6 h) and the `slo:*` recording rules dashboards read; its output is plain Prometheus rules, so
  every store loads it. The generated files are committed (reviewable, no tool needed to deploy) and drift-checked.
  Eight SLOs: sign-in availability 99.9 % / latency ≤ 1 s 99 %; checkout availability 99.9 % / latency ≤ 2.5 s 99 %;
  payout run success 99 % / payouts ≤ 1 h late 99 %; KDS ticket delivery ≤ 5 s 99.5 % / freshness (bus ≤ 2 s) 99.5 %.
  The targets are a first guess with no production traffic to calibrate them; revisit after a month of data.
- **SLIs where the user feels them.** Sign-in and checkout: the HTTP server metrics of the exact routes (5xx = bad, 4xx
  — wrong factor, declined card — good), not the business counters (which can't see a request that crashed). To count
  "within 1 s / 2.5 s" exactly, the platform's observability defaults add SLO histogram buckets
  (`management.metrics.distribution.slo`) for `http.server.requests` and the new timers; the rules match `le="1"` and
  `le="1.0"` (Prometheus 3 normalises). "Checkout" = `POST /api/v1/me/checkouts`, `…/{checkoutId}/place` and
  `/api/v1/me/bookings/checkout` (consumer orders, food included, and booking deposits).
- **New metrics** (no ids in labels): `northline.jobs.runs{job,outcome}` + `northline.jobs.last_success{job}` for every
  payments job step (`JobRuns`, called by `PaymentsScheduler`; the run's "success" = the step didn't throw);
  `northline.payouts.delay{kind=scheduled}` (sent − the business's payout time in its zone, negative = 0);
  `northline.kds.ticket_delivery` (food `OrderPlaced.occurredAt` → the kitchen's signal published, measured in
  `StudioLiveEvents` — it covers the outbox and listener delay, not the last hop to the browser, which carries no
  timestamp); `northline.studio.live.probe{outcome}` (`LiveBusProbe`: every replica signals itself through
  `StudioLive` every 30 s on channel `probe.<ulid>`, never a merchant id; one not back by the next probe is `lost`) —
  the KDS **freshness** SLI, because a broken Valkey pub/sub leaves streams open with keep-alives and the Studio never
  falls back to polling; `northline.events.outbox.pending` / `.oldest_age` (platform `OutboxBacklog`, auto-configured
  wherever `spring.modulith.events.jdbc.schema` is set: api and auth; one count/min query on the partial index,
  cached 15 s, NaN when unreadable). Consumer lag and DLQ counts already existed (S-26).
- **Threshold alerts added:** `NorthlineOutboxBacklog` (ticket, oldest > 5 min) / `NorthlineOutboxStuck` (page,
  > 30 min); `NorthlineConsumerLagCritical` (page, > 10,000 for 15 min, next to S-111's ticket at 1,000);
  `NorthlinePayoutRunStalled` (ticket 30 min, page 2 h since the last success) and `NorthlinePayoutRunMissing`
  (ticket, no replica reports it for 30 min) — a run that never happens burns no SLO. S-111's alerts keep their
  thresholds; their `runbook` annotation became the standard `runbook_url`, absolute, pointing at
  `docs/runbooks/alerts/<name>.md` on GitHub (Helm rewrites the base: `alerting.runbookBaseUrl`).
- **Routing: Alertmanager format, two receivers `page` and `ticket` selected by the `severity` label**, a page
  inhibiting the same SLO's ticket. Providers per receiver by configuration (`alerting.routing.page|ticket.provider`:
  `pagerduty` — Events API v2, critical vs warning; `opsgenie` — P1 vs P3; `webhook`; `none`); keys only from the
  secrets manager (ExternalSecret `northline-alerting`, Terraform creates the five secrets empty in all three clouds),
  only the selected providers' keys mapped. The reference config (`deploy/observability/alertmanager/alertmanager.yml`)
  holds obviously fake keys.
- **Helm toggle `alerting.enabled` (default off)** with `rules.format` `prometheusRule` (Prometheus Operator on any
  cluster), `gmpRules` (Google Managed Prometheus, group `interval` required) or `configMap` (raw files for Mimir /
  Grafana Cloud, Amazon Managed Prometheus, Azure's converter), and `routing.format` `alertmanagerConfig` or
  `configMap` (credentials as `*_file` under a mounted Secret) or `none`. The chart can't read files outside itself, so
  the rule files are copied into `deploy/helm/northline/files/alerting` by `make obs-slo` and the copies are checked
  byte for byte. Every alert gets `namespace` (the operator's AlertmanagerConfig matches its own namespace only) and
  `environment` labels. Off by default because no environment runs a rule loader yet; the Argo CD AppProject now
  allows `PrometheusRule`, `AlertmanagerConfig` and GMP `Rules`.
- **The rota has an API, but for people** (console, staff token with a second factor), so routing can't read it. Added a
  read-only machine export: `GET /api/v1/ops/oncall` (JSON: who is on now, shifts 12 h back to 8 days ahead, with
  work emails) and `/api/v1/ops/oncall.ics` (iCalendar, SUMMARY = email, which Grafana OnCall's iCal schedules map to
  users). One shared token `ONCALL_EXPORT_TOKEN` (header, or `?token=` for calendar clients), constant-time compare,
  404 while unset; its own security filter chain (`/api/v1/ops/**`, GET only) because the resource server would try to
  decode the token as a JWT. In the `internal` OpenAPI group. Not built: a sync of the rota into PagerDuty / Opsgenie
  schedule overrides (no account to test against), and the console's "Page current on-call" button — paging goes
  through Alertmanager so it works when the api is down; the api is never in the paging path.
- **Offline validation:** `make obs-rules-check` → `scripts/validate-alerts.py` (pure Python + PyYAML: severity
  page|ticket, summary, `runbook_url` to an existing page, no orphan runbook, SLO specs valid and generated, chart
  copies identical, page/ticket reach their receivers, no province/city/zone literal), then Sloth drift, promtool
  check + test, amtool on the reference and on the chart's alertmanager.yml for each provider — each tool from PATH,
  else Docker, else skipped with a notice. `make obs-check` (S-111) now ends with it. `deploy/helm/validate.sh` renders
  the three formats with each provider through kubeconform, checks the mapped keys and the namespace labels, and
  refuses unknown formats/providers.
- **Dashboards:** four SLO dashboards (`northline-slo-sign-in|checkout|payouts|kds`) over Sloth's recording rules
  (objective, budget left, burn rate now/period, SLI by window); payments job runs and payout delay, ticket delivery and
  probes, and the outbox backlog added to the existing flow dashboards.
- **Tests:** platform `OutboxBacklogTest` (one query for both gauges, 15 s cache, NaN on failure, schema name refused),
  `ObservabilityDefaultsTest` (+SLO buckets); api `JobRunsTest`, `PaymentMetricsTest` (+payout delay),
  `LiveBusProbeTest` (round trip, lost, foreign signal ignored), `StudioLiveEventsMetricsTest` (food only),
  `OncallExportServiceTest`, `OncallExportControllerTest` (404 when off, RFC 5545 escaping), `OncallExportApiTest`
  (JSON and iCal through the real security chain; 401 for no/wrong token and for a user's token),
  `OutboxBacklogMetricsTest` (the api binds the gauges); promtool: 12 SLO cases, 4 new threshold cases.
- **Variables:** `ONCALL_EXPORT_TOKEN` (api, optional secret, `oncall-export-token`), `LIVE_PROBE_INTERVAL` (api,
  `30s`); Alertmanager secrets `ALERTING_PAGERDUTY_ROUTING_KEY`, `ALERTING_OPSGENIE_API_KEY`,
  `ALERTING_PAGE_WEBHOOK_URL`, `ALERTING_TICKET_WEBHOOK_URL`.
- **Not done:** rules for Azure Monitor managed Prometheus and Amazon Managed Prometheus as Terraform resources (the
  ConfigMap files are loaded with their CLIs, documented); an in-cluster Prometheus/Alertmanager add-on; per-tenant
  (per-kitchen) SLOs; a measured "order placed → shown on the screen" (the browser reports nothing back).

## 2026-09-30 — S-115 Runbooks: key rotation, Stripe incidents, Kafka DLQ replay, reindex

- **Where the runbooks are.** `docs/runbooks/key-rotation.md` grew from S-7's signing keys into every key (§ 1 JWKS
  rollover, § 2 KMS data keys and envelope re-wrap, § 3 merchants' webhook secrets, § 4 Stripe keys and webhook
  secrets, § 5 DB / Kafka / push / OpenRouter / on-call export, § 6 External Secrets commands for AWS, Google Cloud and
  Azure). New `docs/runbooks/stripe-incidents.md` (outage, webhook backlog / replay, Stripe vs ledger, dispute spike,
  leaked keys). `events.md § 3` rewritten as the DLQ runbook (+ "Deferred notifications"); `search.md § 9` gained a
  reindex runbook (decision tree, partial reindex, rollback). Each section: symptoms, impact, decision tree, commands,
  verification, rollback, comms and an **Exercised** table (date, what ran, outcome, what couldn't run). S-113's alert
  stubs that these cover (event-dead-lettered, consumer-lag, checkout availability / latency, payout run /
  timeliness) link to them instead of repeating them. The S-7 headings were kept word for word (anchors used by the env
  runbooks and infrastructure.md). S-114's files (infrastructure.md, backups-dr.md, the env runbooks) got only one
  variable row each (dev, staging, prod) and a link — no edits near S-114's hunks.
- **"Exercised" = a scripted drill in an integration test, run on 2026-10-02** (Testcontainers: Kafka 4, PostGIS,
  Elasticsearch 9.1, the whole worker or the whole api), through the operator's entry points (`DlqReplayCommand`,
  `SearchReindexCommand`, the re-wrap job, the api's HTTP answer). No cloud account, Stripe account, Apple / Firebase /
  OpenRouter account or cluster exists, so every provider-side step (rolling a Stripe key, KMS versions, `aws` /
  `gcloud` / `az` secret commands, the cluster Jobs, MSK / Managed Kafka / Event Hubs) is written from the providers'
  documentation and marked **not exercised** in its table.
- **DLQ replay tool finished, not replaced** (S-26's `DlqReplayCommand` existed: list / replay / `--event` / `--limit`
  / `--force`). Added: filters `--event=a,b`, `--type`, `--since` / `--until` (instant or age `30m`/`6h`/`2d`, on the
  DLQ record's timestamp), `--failure=<text>`; a pace `--rate=N` records/second (default 20); **`--actor` and `--reason`
  required for `replay`** (refused otherwise; `list` stays free) and one platform audit entry per run in
  `developer.audit_log` (action `events.dlq_replayed`, role `operator`, `merchant_id` null, target the DLQ topic,
  `after` = actor, reason, filters, counts, ≤ 200 event ids; `actor_id` null because no session proves who ran a
  command — the console's audit viewer lists it under `events.`). A command rather than an admin endpoint: it needs
  Kafka and the database, not the api; it runs where the worker runs (a one-off Job), and an HTTP surface would need
  its own auth for a rarely used, powerful action.
- **The deferred notifications path.** `messaging.deferred_notifications` (SMS / push held by quiet hours, customers'
  sends after an outage) deleted a row after 10 failed attempts — an outage longer than ~50 minutes lost the message.
  **V290** (`messaging`, additive): `dead_at`, `last_error` + a partial index; a given-up row is kept as **dead** (the
  table's own DLQ), the job skips it, `pending()` doesn't count it, and a nightly purge (03:27 platform zone) deletes
  dead rows older than 30 days (`northline.notifications.deferred-dead-retention`). `last_error` holds the exception
  class only (provider messages can contain phone numbers). The same command requeues them: `list|replay --deferred`
  with `--channel`, `--type`, `--event`, `--since`/`--until` (on `dead_at`), `--limit`, `--at=<instant>` (due time;
  requeued rows aren't held for quiet hours again — the runbook says to requeue SMS/push by day), audit
  `notifications.deferred_requeued`. Sending stays the job's (it re-reads the person and their matrix; channel claims
  prevent doubles).
- **KMS data keys: a re-wrap job** instead of a re-seal (S-32 said "no bulk re-seal command yet"). `SecretSealer`
  gained `rewrap(sealed, context)` (unwrap with the stored key reference, wrap with the current key; the ciphertext is
  untouched) and `currentKeyRef()` (wraps a throw-away key and reads the reference back — the same for every provider,
  cached 5 min). Modules declare where they keep sealed values as `SealedColumn` beans (table, id, key-ref, wrapped-key,
  ciphertext columns, context prefix): availability `calendar_links`, catalogue `integrations`, food `pos_connections`,
  booking `access_notes` (its context prefix moved to `CustomerBookingStore.ACCESS_NOTE_CONTEXT` so the job and the
  service share it), and after the merge with S-105 privacy `requests` (`sealed_*`, prefix
  `PrivacyRequestStore.SEALED_CONTEXT`). S-105's export bundles (object storage, 7 days) are not re-wrapped: the old key
  stays enabled until they expired. A failed row is reported with its table, column and id (`Outcome.failedRows`). `KeyRewrap` (shared.crypto) runs every `KMS_REWRAP_EVERY` (1 h, first after 2 min) on every
  replica, ≤ 200 rows per table, updating only while the row still has the old reference (no coordination needed);
  metric `northline.crypto.rewrapped{table,outcome}`; `stale()` counts what is left. **Google Cloud references are now
  the key version** that encrypted (`EncryptResponse.name`), decrypt goes to the crypto key — so a new primary version
  is detectable; rows written before keep the key name and still open. **Local rotation:** `KMS_LOCAL_PREVIOUS_KEYS`
  (comma-separated, unwrap only). AWS automatic rotation keeps the ARN and needs no re-wrap; a new AWS key, a new Azure
  version or a new local key does. New variables `KMS_LOCAL_PREVIOUS_KEYS`, `KMS_REWRAP_EVERY` (api, optional; README,
  dev / staging / prod tables, local.md, `.env.example`; not secrets in Helm because the cloud doesn't use the local
  key). Slot holds in Valkey are left to expire (≤ 15 min).
- **Not rotatable yet (documented, not built):** `TOTP_KEY` and `WEBHOOK_SECRET_KEY` are single AES keys without a
  per-row key reference — rotating them needs a re-encryption migration with both keys loaded; the runbook treats a leak
  as an incident (re-enrolment / every business rotates its endpoint secret).
- **Merchants' webhook secrets:** the dual-secret window already existed (S-33: `overlapHours` 0–168, both secrets sign
  meanwhile); nothing built, only the runbook. Support can't rotate for a business (members only, by design).
- **Stripe outage degrades checkout to 503** (before: a 500 from the generic handler). New
  `shared.ProviderUnavailable` (code, message, Retry-After) mapped by `ApiExceptionHandler` to 503 with `Retry-After`
  and the message localised; `StripeConnectGateway.failure()` turns a Stripe `ApiConnectionException`,
  `RateLimitException` or any 5xx into `StripeUnavailable` (`payments_unavailable`, 60 s, "Payments are unavailable
  right now. Nothing was charged — try again in a few minutes." / « Les paiements sont indisponibles pour le moment.
  Rien n’a été débité — réessayez dans quelques minutes. », in the fr-CA catalogue) for the Connect, Tax and bank-linking
  adapters; a 4xx (bad key, invalid request) stays `StripeCallFailed`. Jobs see a RuntimeException either way and retry
  with the same idempotency key, and checkout's idempotency key is released on failure (S-51), so the same click works
  later. No checkout kill switch or banner was added (nothing to switch: the api degrades by itself; a status banner
  needs a mechanism that doesn't exist yet).
- **Search:** `SearchReindexCommand --partial --merchant=<ids> [--since=<instant>]` re-reads merchants into the live
  aliases (no new index, no swap; the live indexer's projection, lock and versions), and `--rollback` points both
  aliases back at the newest older index left by `--keep-old` in one request, then sweeps merchants changed since the
  newer index's creation time (parsed from its name) into it. Neither is in the chart (the `searchReindex` Job stays the
  full reindex); the runbook gives the one-off Job.
- **Stripe webhook backlog:** no new code. The requeue is SQL (`attempts = 0` for `failed` rows at the 10-attempt
  cap; the payments job applies them idempotently); missed events are resent with the Stripe CLI.
- **Tests (all run 2026-10-02):** worker `ConsumerFrameworkTest.runbookDrill_…` (filters, refusal without an operator,
  pace, audit entry, idempotency for the bystander group, no second replay), `CustomerNotificationsTest.runbookDrill_…`
  (dead row kept, ignored by the job, listed, requeue refused then audited, sent once), `SearchReindexTest.runbookDrill_…`
  (partial brings back a lost document, keep-old + rollback with the sweep, refusal with nothing older), plus the
  existing `NotificationsConsumerTest`, `WebhookDeliveryTest`; api `KeyRewrapTest` (local rotation drill on PostGIS in
  batches, ciphertexts unchanged; the four modules' columns valid on the migrated schema), `EnvelopeSealerTest`
  (+ local rotation, Google Cloud versions), `CryptoConfigurationTest`, `StripeOutageTest` (refused connection and a
  500 stand-in → `payments_unavailable`; 400 → not), `ProviderUnavailableTest` (503, Retry-After, en / fr), and the
  re-run S-7 / S-12 / S-33 / S-85 tests the runbooks cite.
- **Not done / never run for real:** anything against a real Stripe account, AWS / Google Cloud / Azure KMS and secret
  stores, Elastic Cloud, a managed Kafka, Apple / Firebase / OpenRouter, a Kubernetes cluster; a status banner for
  outages; re-encryption of `TOTP_KEY` / `WEBHOOK_SECRET_KEY`; a metric and alert for dead deferred notifications (the
  ERROR log line is the signal); submitting dispute evidence from Northline (S-12's open item).

## 2026-09-30 — S-108 CASL consent capture and unsubscribe for commercial messages

Branch `security/s-108-casl`. Runbook: [runbooks/casl.md](runbooks/casl.md). Migration **V300** (range V300–V304).

- **Never run against a real email or SMS provider, nor a real mailbox.** Commercial email went only to the library's
  in-memory sender and the worker's recording fakes; the RFC 8058 one-click POST was exercised with MockMvc, not by
  Gmail/Outlook/Yahoo; the commercial SMS's opt-out link only reached the recording SMS transport; DKIM coverage of the
  `List-Unsubscribe` headers is unchecked. casl.md § 8 is the first-real-send checklist. Counsel has not reviewed the
  consent wordings.
- **Classification is code, in the shared email library** (`ca.northline.email.MessageClasses`, used by the api and
  the worker): every notification row of both matrices (`team.<row>`, `customer.<row>`), messages outside them
  (`sms:*`, `push:*`), and each email template's existing `EmailContent.Purpose`. Only the customer row **`offers`**
  and the new template `marketing-offer` are commercial; every Studio row, every other customer row, invitations,
  codes and courier pushes are transactional/relationship (CASL s. 6(6)). Tests fail on an unclassified template file
  (`MessageClassesTest`), api row (`ConsentApiTest`) or worker-defaults row (`CommercialNoticesTest`); an unclassified
  row counts as commercial at send time (fail closed).
- **Express consent only; no implied consent.** The Privacy Policy promises "only with your express opt-in", so the
  2-year/6-month implied consents are not modelled (`basis` is `express` only). **No double opt-in:** consent is given
  in a signed-in session for the account's own address. **Merchants' own marketing** is not sent by the platform
  (the Privacy Policy forbids it), so it has no category.
- **Categories per channel:** `marketing_email`, `marketing_sms`, `marketing_push`. Push promos are treated as needing
  consent too (CASL's reach over app push is unsettled; the stricter reading).
- **Schema (V300):** `messaging.consent_records`, append-only (grant/withdrawal, `at`, `source`, `wording_version`,
  `language`, `address_hash` = SHA-256 of the lower-cased email or the E.164 phone, `ip_prefix` = /24 or /48,
  `user_agent_hash` = SHA-256, `actor_id` for staff). The current state is the newest row. Unsalted SHA-256 for the
  address: it must stay matchable by staff after an erasure, and a key would break lookups when rotated.
- **Wordings are versioned code** (`ConsentWordings`, en/fr-CA, `{legalName}` from configuration): a published
  wording is never edited (`ConsentWordingsTest` pins each version's hash); a stale version from a client is 409
  `consent_wording_changed`. The requester's legal name, mailing address and contact (CASL regulations s. 4) are
  returned with them (`requester`).
- **The design's controls became the consents.** Account › Notifications' "Offers & rewards" cells and "Marketing
  emails" now read and write `consent_records` (`/me/notifications` gains `consentSource` and `consentWordings`): the
  offers row's email cell and "Marketing emails" are one consent and move together in both clients; "None (CASL
  opt-out)" withdraws; "Weekly digest"/"Only rewards" keep the frequency in the old `marketing` column. **Deviation:**
  the design's defaults had offers push **on** and marketing **weekly** — pre-ticked consent is not consent under CASL,
  so the offers defaults are all off (`notification-matrix-defaults.json`, `CustomerNotificationPrefs`) and
  `marketing` reads `none` without a consent. **No grandfathering:** stored `marketing = weekly` values and offers
  cells from S-59 are not consent (no proof of what was shown); everyone starts without consent (no real users yet).
- **New endpoints:** `GET /api/v1/me/consents[?surface=account|studio]` (state, wording, history, requester), `PUT
  /api/v1/me/consents/{category}` (`granted`, `source` ∈ web_signup | app_signup | web_settings | app_settings |
  checkout | studio, `wordingVersion?`), console `GET /api/v1/console/consents?userId=|contact=` and `POST
  /api/v1/console/consents/withdrawals` — `@RequiresConsole(PRIVACY)` (admin, privacy officer, support lead; the
  withdrawal needs action `privacy`, audit `consent.withdrawn`). No new console screen: the lookup is a section of the
  privacy screen.
- **Unsubscribe:** the S-13 page and token format are reused; new token rows `consent.<category>` (and the customer's
  `customer.offers`) **withdraw the consent in the same request** (CASL allows 10 business days). The POST tells the
  mailbox's RFC 8058 one-click (`List-Unsubscribe=One-Click` → source `list_unsubscribe`) from the page's button
  (`unsubscribe_link`). The page now says "text messages" for an SMS opt-out and points customers to Account ›
  Notifications instead of Studio (also fixed for S-102's customer emails, and in the email footer's hint).
- **SMS STOP/ARRET is not handled:** the SMS port only sends (no inbound). Commercial texts carry an opt-out link to
  the unsubscribe page instead (CASL allows a link; the page shows the mailing address — regulations s. 3(2)). The
  source `sms_keyword` is reserved for an inbound webhook.
- **Send-time checks:** the `Mailer` gains `CommercialConsent` (the api: `ConsentService`; the worker: `JdbcConsents`
  over the same table; none = nothing commercial is sent) and refuses a commercial delivery without the recipient's
  id; the worker's `Deliveries` asks per channel before every send, including what quiet hours or an outage deferred.
  `Mailer.Delivery` gains `recipientId` (old 5-argument constructor kept); `Mailer.Outcome` and
  `Deliveries.Outcome` gain `NO_CONSENT`.
- **Sender identification check:** new `EMAIL_LEGAL_NAME` (`northline.email.legal-name`, required, default in the
  yml). `CommercialMessageCheck` refuses a rendered commercial email whose text or HTML lacks the legal name, the
  mailing address or the unsubscribe link, and a commercial SMS without the legal name and opt-out link. Commercial
  footers add "<legal name> sent you this marketing message because you agreed…" (en/fr).
- **No commercial producer yet.** The worker understands an offer (pseudo-event `messaging.offer`, payload
  `PersonalNotices.offer(...)`, re-read from `deferred_notifications` at send time) and the library has
  `marketing-offer`; no campaign/offer feature hands them over. Sign-up and checkout opt-in checkboxes are not built
  (the api accepts their sources).
- **Consent history:** consumer web and app (Account › Notifications › "Marketing messages"), Studio (Settings ›
  Notifications › "Marketing from Northline", email only, source `studio`), the console lookup, and S-105's access
  export (`messaging.consents`, without the hashes).
- **Retention, and S-107's overlap:** records stay while a consent is active and **3 years after its withdrawal**;
  `messaging.api.ConsentRetention` exposes the period and an idempotent `purgeExpiredProofs(now)` for S-107's
  retention jobs and report; until S-107 takes it over, messaging runs it daily (`CASL_PURGE_CRON`, 04:23 platform
  zone). Erasure (S-105's messaging contributor) withdraws granted consents (source `erasure`), drops `ip_prefix` and
  `user_agent_hash`, and keeps the rest as new `Retention.CONSENT_PROOF` (shared enum; export summary en/fr).
- **Also:** `ClientAddress` moved from `search.web` to the shared kernel (`ca.northline.shared`) (search's rate limit and the consent evidence use
  it); the Studio settings hint in notification email footers is now per audience (`settingsPlace`).
- **Tests:** email `MessageClassesTest` (classification, content check, legal name), `DefaultMailerTest` +2
  (consent asked at send time, no consent source = nothing commercial), `EmailTemplatesTest` (commercial footer),
  `EmailAutoConfigurationTest` +1; api `ConsentApiTest` (capture with evidence, validation and French, stale wording,
  Studio, 401; settings as consents; one-click and page unsubscribe, SMS link, idempotence; console lookup by contact,
  403s, withdrawal + audit; 3-year purge; classification), `ConsentWordingsTest`, `MessagingPersonalDataTest` +1
  (export, erasure withdrawal, evidence dropped, `consent_proof`, idempotent), `AccountSettingsApiTest` (marketing
  defaults to `none`); worker `CommercialNoticesTest` (nothing without consent whatever the stored cells; each
  channel with sender and unsubscribe; a withdrawal stops a deferred SMS; rows classified); web consumer
  `settings.test.tsx` +3, Studio `settings.test.tsx` +1, console `privacy.test.tsx` +2; mobile `account.test.tsx` +1
  (and the save test updated).
- **Not done:** inbound SMS STOP/ARRET; sign-up and checkout checkboxes; any commercial producer (campaigns, offers);
  counsel review of the wordings; a separate console screen (the privacy screen hosts the lookup).

## 2026-09-30 — S-107 Data retention jobs per the Privacy Policy retention schedule

Branch `security/s-107-retention`. Runbook: [runbooks/retention.md](runbooks/retention.md) (the full table, a run, the
console, metrics, mismatches); privacy-requests.md § 9 links it.

- **The schedule is a YAML catalogue, not code and not environment variables.** `privacy/retention-schedule.yml` (api
  resources), loaded by `RetentionCatalogue` with Spring's `Binder`: per category the policy clause and its words
  verbatim, the period (ISO-8601) and what starts it, `afterDisputeClosed`, `lawMinimum`, the legal basis and name
  (en/fr), the action (`delete` | `pseudonymise` | `aggregate`), the owning module, the hold codes and the enforcement
  (`job` | `pipeline` | `infrastructure` | `none` | `blocked`). Periods are the policy's: a variable would let an
  environment silently contradict the published policy. `RetentionScheduleTest` reads `web/packages/legal/pages/
  privacy.html` § 6 and fails on a clause without a category, a misquote, or a period the clause doesn't state.
- **Categories:** the policy's eight clauses map to 13 categories (a clause owned by several modules is one category per
  module); plus two the policy's section 6 doesn't name, kept and flagged: `developer.audit_log` (7 years) and S-108's `messaging.consent_records` (CASL proof, 3 years after withdrawal, through `messaging.api.ConsentRetention`). Fifteen in all; the
  table is in the runbook. Short-lived technical data other jobs already delete (exports, drop-off addresses, Stripe
  events, push devices, S-115's dead deferred notifications, dedupe claims, webhook log, idempotency keys, live
  positions, erased accounts' credentials) is listed as `operational` in the same file and shown in the report; those
  jobs were not moved.
- **One SPI, like S-105:** `shared.privacy.RetentionContributor` (`holds`, `relate`, `expired`, `purge`), implemented
  per module as a package-private `<Module>Retention` in `persistence` over its own schema (identity, orders, booking,
  payments, messaging, fulfilment, developer, privacy). It sits in `shared` for S-105's reason (privacy depends on
  those modules). `PrivacyContributorsTests` checks they are package-private persistence adapters;
  `RetentionScheduleTest` that every module with a job has one; the service refuses to start if a runnable category
  has no contributor or a contributor runs an unknown category.
- **Holds are S-105's (`PersonalDataContributor.Hold`), collected across modules.** Each module reports the holds it
  knows as references (`booking:<id>`, `order:<id>`, `order_line:<id>`, `food_order:<id>`, `dispute:<id>`), a decided
  dispute with its decision date and customer; `relate` translates names (orders: order line / food order → order).
  The privacy module keeps those in force for a category (its hold codes; a decided dispute for
  `afterDisputeClosed`, plus the law's minimum when `lawMinimum`) and passes the keys to the job as a SQL array. People
  with an open privacy request are held in every category (PIPEDA s. 8(8)). No cross-schema SQL anywhere.
- **CASL proof (S-108):** `messaging.consent_records` runs `ConsentRetention.purgeExpiredProofs` (idempotent) from the
  messaging job; dry runs count the same rows. S-108's own daily `@Scheduled` purge and `CASL_PURGE_CRON` are
  **retired**: one scheduler, one report, one audit trail and the not-run alert cover it. `RetentionScheduleTest`
  checks the schedule's P3Y equals `ConsentRetention.PROOF_PERIOD`; casl.md and the env runbooks updated.
- **Province-dependent periods come from the region model.** New `region.privacy_laws.decision_retention_days` (V301,
  exposed as `PrivacyRegime.decisionRetentionDays`): what a law keeps after a decision about a person. BC PIPA 365
  (s. 35(1)); PIPEDA, Alberta PIPA, Québec 0 (no number) — drafted for legal review. Applied to dispute evidence,
  delivery proofs and check-in locations by the customer's province (default address, else the default province, as
  S-105). The policy's periods themselves are national; Québec's 3-year prescription is flagged, not applied.
- **Account profile (30 days) reuses the erasure pipeline:** closing an account *is* an S-105 erasure, which blanks the
  profile within minutes. The `account.closed_profile` job finds erasures whose account closed more than 30 days ago
  with a step still pending or failed, makes those steps due and runs them through `PrivacyWork.run`; held steps are
  its "held". No second anonymiser.
- **Actions chosen where the policy is silent:** 7-year transaction records are *pseudonymised* (customer ids set to
  null, names, addresses and customer words cleared; amounts, tax, dates and the business stay; the ledger and payouts
  hold no customer data) rather than deleted — deleting would break merchants' balances and reports; checkout and
  accepted-quote snapshots (whose customer id is NOT NULL) are deleted, the order / booking row keeps the sale.
  Conversations, help-case conversations, uploads and delivery proofs are *deleted* (rows and files); dispute evidence
  is *pseudonymised* (statements and files go, the decision and amounts stay); check-in locations: the point is
  cleared, the transition stays; sign-ins deleted (northline-auth treats a missing sign-in as ended; refresh tokens
  live ≤ 30 days); the audit log deleted after 7 years through V181's trigger switch (`northline.audit_retention`),
  bounded by the trigger's own `now() - 7 years`.
- **Clocks chosen where the policy is silent:** "after the transaction" = a conversation's last message, a help case's
  resolution, a dispute's escrow `occurred_at`, a drop-off's `done_at`; 7 years from the order placed / the booking's
  end / the escrow created; sign-ins from their last activity (ended, else last seen, else started).
- **Runs:** nightly at `RETENTION_CRON` (02:47, platform zone), every replica; a category with a successful scheduled run
  in the last 20 h is skipped; batches of `RETENTION_BATCH` (500) in their own transaction under
  `pg_try_advisory_xact_lock('privacy.retention:<category>')`, at most `RETENTION_MAX_BATCHES` (40) a night; a dry run
  counts only. The existing scheduling pattern (Spring `@Scheduled` + Postgres advisory locks) instead of ShedLock — no
  module uses ShedLock. `RETENTION_DRY_RUN` makes nightly runs count only (recommended for a new environment's first
  week, documented in prod.md).
- **Objects through the storage ports:** `delete(key)` added to `AttachmentStorage`, `DisputeEvidenceStorage` and
  `ProofStorage` (object store, local folder and unconfigured adapters). Deleted in the batch's transaction after the
  rows are chosen; a failed delete fails the batch (retried next night); deleting a missing object is fine.
- **Bucket lifecycle where it suffices:** only access exports have a fixed age with no hold — Terraform's new
  `buckets.<name>.expire_prefixes` deletes `privacy/exports/` after 8 days on the buckets and their replicas (AWS
  lifecycle rule, GCS lifecycle rule, Azure management policy), a backstop behind the hourly sweep. Everything else
  depends on holds and is deleted by the jobs.
- **Search index:** holds no personal data (S-105) — nothing to purge.
- **Report:** console Privacy › Retention tab (screen `privacy`; runs need the `privacy` action — S-105's privacy
  officer, support lead and admin; no new role). `GET /api/v1/console/retention`, `/export` (CSV, RFC 4180, every
  field quoted, headers in the caller's language), `POST /runs {dryRun, category?}` (422 `category` for a code without
  a job). Every run, dry or not, scheduled or staff, is a `privacy.retention_runs` row and an audit entry
  `privacy.retention_run` (target `retention_category`). en + fr-CA.
- **Metrics and alerts:** `northline.retention.rows{category, action}`, `northline.retention.runs{category, mode,
  outcome}`, `northline.retention.last_success{category}` (read from the runs table, cached 60 s; before a first
  success, the first run of any category, so a category that never succeeds alerts). `NorthlineRetentionNotRun`
  (ticket, 2 days, for 1 h) and `NorthlineRetentionMissing` (ticket, 6 h), promtool tests, runbook page.
- **Fixed mismatches (the policy was clearly right):** prod bucket replicas kept older versions (deleted objects) 90 days
  — now 30 on all three clouds ("Backups roll off within 35 days of deletion"); nothing deleted sign-ins, messages,
  dispute evidence, delivery proofs, check-in locations or old transaction records, and the audit log's 7-year purge had
  no job — all have jobs now.
- **Flagged, not changed** (runbook § 7): the audit log is not in the policy (and part of it may be "security logs: 12
  months"); Québec's 3-year prescription vs "2 years"; KYC's "5 years after the relationship ends" can't trigger (no
  business closure or refusal exists — category `blocked`); Google Cloud and Azure replicas don't receive deletions
  (S-114), so files the jobs delete survive there; an erasure step held by an open order keeps that module's data past
  30 days; personal data the policy doesn't schedule is kept with no end (notification inbox, abandoned carts,
  addresses of open accounts, trust flags, AI usage, storefront visits, merchants' job photos); northline-auth's own
  token / authorization tables have no 12-month purge; payment intents without an escrow have no date to age by.
- **Schema (V301):** `privacy.retention_runs`; `region.privacy_laws.decision_retention_days` (+ BC 365); indexes for the
  scans (`identity.sessions` last activity, `messaging.threads` last message, resolved tickets, customer uploads,
  located booking events, decided disputes, escrows' `created_at`, proof stops, `orders.placed_at`,
  `developer.audit_log.at`). Range V301–V304 recorded in IMPLEMENTATION_PLAN.md (above S-108's V300).
- **Variables (all optional, no secret):** `RETENTION_ENABLED`, `RETENTION_CRON`, `RETENTION_BATCH`,
  `RETENTION_MAX_BATCHES`, `RETENTION_DRY_RUN` (runbooks dev/staging/prod/local, `.env.example`). No Helm change: the
  api reads them from `configEnv` like the other optional settings.
- **Tests:** `RetentionJobsTest` (every job's dry run and run against its own rows: sign-ins and an open request,
  conversations with files and order / dispute holds and the year after a decision, help cases and uploads, check-in
  locations with an upcoming booking, an open dispute and BC vs Alberta after a decision, dispute evidence files,
  delivery proofs, seven-year records with an open order, the audit log, the overdue erasure through the pipeline, the
  nightly run once per night with audit and gauges; the API's roles, MFA, French, CSV, 422 and audit roles);
  `RetentionScheduleTest` (the policy word for word); `PrivacyContributorsTests` (+ retention adapters); promtool
  cases; console `retention.test.tsx` (report, drawer, dry run with the role header, confirm before running, tabs,
  French). Terraform validated offline with the env roots' mocked plans (`scripts/validate.sh all`).
- **Not done / never run:** no bucket, lifecycle rule, replica or alert has run in a real cloud (the Terraform is
  validated offline only); the legal values (BC 365, the others 0, the clocks above) need counsel's review; a
  litigation hold (keep everything about a case past the policy) is not modelled — stopping the jobs is the only lever;
  northline-auth's tables and the worker's operational purges are not in the report's run history (they keep their own
  jobs and metrics).

## 2026-09-30 — S-109 Accessibility audit (WCAG 2.2 AA) of Studio, consumer web and console

Report, findings and the accessibility statement draft (en/fr): [docs/a11y/audit.md](a11y/audit.md). Result: no
critical or serious issue left; 2 moderate and 6 minor issues ticketed (S-140–S-148 in `docs/a11y/tickets.csv`).

- **Tools.** Component level: `vitest-axe` (axe-core 4.13) through `expectNoAxeViolations()` in the new
  `@northline/a11y` package, WCAG 2.0–2.2 A/AA tags; `color-contrast`, `target-size` and `region` off in jsdom (no
  layout) and covered by the token contrast test and the page sweep. `@northline/ui` gets a `dom` vitest project
  (`pnpm test` runs `unit` + `dom`; the Storybook project is unchanged). The apps' axe checks sit in their existing
  Testing Library tests, after the screen has loaded, not in separate files.
- **Page sweep without a backend.** The built apps (`vite preview`; the consumer's Node server) answer from fixtures
  **recorded from the apps' own vitest suites** (`src/record.ts` wraps `vi.stubGlobal('fetch', …)` when
  `NL_A11Y_RECORD` is set; inert otherwise; hooked into each app's `src/test/setup.ts`). Reduced to the first 2xx JSON
  answer per request, preferring a test of the feature the path names (a catch-all stub in another feature's test
  answered `/api/v1/cart` with shop data); ids in paths are wildcards. Hand-written overrides only where screens must
  agree (session, one merchant per portal, an admin's grants, the onboarding state). Chosen over hand-made fixtures
  (60+ endpoints) and over a real api (Gradle build, database; the smoke sweep already does that for the Studio). The
  fixtures (~800 kB JSON) are committed; `make a11y-record` refreshes them.
- **What fails the sweep:** WCAG critical/serious violations, a wrong `<html lang>`, horizontal scroll at 320 px.
  Moderate/minor, best practice, text spacing, hidden focus, running animations and targets under 44 px are reported
  in `a11y-results/pages/*.json`, not failing — they are the tickets' measure.
- **CI:** `web.yml` input `a11y` and GitLab `RUN_A11Y`, both default off, manual like everything else; never run here.
- **Design changes made for WCAG / the SCREENS rules** (the design is locked; these are the minimum): kit buttons,
  icon buttons, chips, tabs, menu and nav items 44 px (SCREENS "hit targets ≥ 44px"; were 40); input borders, check
  boxes, radio dots, multi-select boxes and the switch's off track `neutral-600` (were 300/400: 1.5–2.1:1);
  placeholders and four muted texts `neutral-700`; a 2 px focus ring on menu items; the KDS ticket list is no longer a
  live region; the kitchen menu builder shows move up/down buttons next to the drag handle; the catalogue editor's
  selected tab is styled from `aria-selected`. `tokens.json`/`derived.css` (the design copy) are untouched: the
  overrides live in `@northline/ui`'s stylesheets, which load after them.
- **Charts** carry their numbers as a visually hidden table (`LineChart` gains `format` and `dataTable`; the console's
  reports keep their own visible table and pass `dataTable={false}`).
- **One-time codes:** `codeValue()` drops spaces and dashes; code fields lose `maxLength={6}` (it truncated a pasted
  "123 456" before the handler ran) — the 6-digit rule stays the form's validation, client and server.
- **KDS announcements:** only orders that are new since the previous refresh, as one polite line; nothing on the first
  load, nothing when an order moves stage (the cook did it). Order cancellations are not announced (no event for it on
  the board yet).
- **`<html lang>`** is set by `I18nProvider` on every locale (an effect), so the Studio and console no longer start in
  `en-CA` for French users; the consumer's server-rendered `lang` is unchanged.
- **Dark mode:** there is none; nothing to audit. The contrast test takes a list of themes for when one comes.
- **Backlog:** the generator (`stories.py`/`build.py`) is kept outside the repo, so the open issues are in
  `docs/a11y/tickets.csv` in the backlog's columns with provisional ids S-140–S-148 (E-11, Sprint 22); the next
  generator run should take them in (it keeps ids by summary). Noted in `docs/backlog/README.md`.
- **Consumer app (cheap wins only):** a test walks every pressable on 17 key screens for role, name and a ≥ 44 pt touch
  area (height or hitSlop; padding-only sizing estimated as padding + one 18 pt line). One fix: the home address kicker
  (hitSlop to 48 pt, a label without "▾"). No other app change.
- **Docs site:** `docs/a11y/` is rendered (internal variant), category "Accessibility" / « Accessibilité ».
- **Not done / never run:** no screen reader was used (S-148); the page sweep runs on recorded fixtures, not real data,
  and was run here only (Chromium 1194); the CI jobs have never run; Safari/WebKit and Firefox not checked; the
  courier app was out of scope; the accessibility statement's mailbox is a placeholder and needs legal review with the
  other footer documents; no migration, no server change.
