# Runbooks

One runbook per environment. Each lists what the environment needs — services, variables, secrets, third-party
accounts — and how to run, deploy and roll back **with what exists today**. Terraform for the cloud foundation and the managed data
stores exists (S-2, S-3; not applied yet); container images and the Helm chart exist (S-14, [deploy.md](deploy.md));
External Secrets are wired into the chart (S-6, [secrets.md](secrets.md)); migrations run as a Job before each rollout (S-16); Argo CD delivery is defined (S-15, [gitops.md](gitops.md));
the edge — Gateway, certificates, DNS records — is defined (S-17, [edge.md](edge.md)); the runbooks
say where a step is still manual or missing.

| runbook | for |
|---|---|
| [local.md](local.md) | your machine: clone → signed-in Studio, with your own Postgres/Valkey or Docker stand-ins |
| [dev.md](dev.md) | the shared cloud development environment |
| [staging.md](staging.md) | pre-production: prod shape, Stripe test mode |
| [prod.md](prod.md) | production (Calgary launch) |
| [infrastructure.md](infrastructure.md) | Terraform on AWS / Google Cloud / Azure: accounts, state bucket, plan/apply, outputs → variables, cost, teardown (S-2/S-3) |
| [object-storage.md](object-storage.md) | uploads in S3 / RustFS, Cloud Storage or Azure Blob: variables, buckets, least-privilege access per cloud (S-10) |
| [calendar-sync.md](calendar-sync.md) | Google and Microsoft calendar two-way sync: Google Cloud and Microsoft Entra app registrations, redirect and notification URIs per environment, secrets, KMS envelope key, operations (S-32) |
| [commerce-sync.md](commerce-sync.md) | Shopify, Square and Lightspeed catalogue sync: app registration per platform, redirect and webhook URLs on the api host, secrets, local fakes, operations (S-35) |
| [pos-menu-import.md](pos-menu-import.md) | kitchens' POS menu import: Square (shared with S-35), Clover and Toast (partner-gated) set-up, the shared OAuth callback, preview and re-import diff, allergens, local fake (S-36) |
| [google-maps.md](google-maps.md) | addresses on the consumer site: Google Maps Platform project, Places API (New) and Geocoding API, the server key and its restrictions, billing and quotas, local fixtures, operations (S-47) |
| [registries.md](registries.md) | business registry lookups: Corporations Canada API, Alberta Corporate Registry (search service or registry-agent searches), City of Calgary licences (Socrata), manual review queue, re-checks (S-23) |
| [stripe.md](stripe.md) | Stripe Connect Express: platform account setup (test/live), money flow, idempotency, local stripe-mock, operations (S-11), webhooks (S-12), Stripe Tax (S-21) |
| [email.md](email.md) | transactional email: Mailpit locally, SES / SendGrid / Azure Communication Services / SMTP set-up, SPF/DKIM/DMARC, CASL (S-13) |
| [notifications.md](notifications.md) | team notifications: who sends which email / SMS / push (api vs worker), matrix and quiet hours, failures, push stub (S-13/S-27) |
| [webhooks.md](webhooks.md) | partner webhooks: payloads and signature for integrators, delivery design (per-endpoint scheduling, retries, auto-disable), SSRF rules, operations (S-33) |
| [search.md](search.md) | the Elasticsearch read model: index layout and naming, analyzers per language, synonyms, the search-indices Job, least-privilege access (S-42); the indexer, visibility rules, versions, the reconcile sweep, merchant locations (S-43); the search API and its contract for the consumer web (S-44); the full reindex with an alias swap (S-71) |
| [logging.md](logging.md) | structured JSON logs (ECS), the redaction layer (emails, phones, tokens, cards, postal codes, codes), shipping over OTLP through the Collector, the local SMS stand-in rule (S-112) |
| [observability.md](observability.md) | traces, metrics and logs over OpenTelemetry: the Collector per environment and its exporters (any OTLP backend, AWS X-Ray/CloudWatch, Google Cloud Operations, Azure Monitor), sampling, business metrics, dashboards as code, alerts, the local Grafana LGTM stack (S-111) |
| [events.md](events.md) | domain events: wire format, the worker's consumer framework (dedupe, retries, DLQ), alerts and metrics, DLQ replay (S-25/S-26) |
| [docs-site.md](docs-site.md) | the Docusaurus documentation site: public variant on docs.<zone>, internal variant behind an IP allowlist, build, images, Pages export (S-126) |
| [api-docs.md](api-docs.md) | OpenAPI 3.1 documents per audience (api, auth, BFFs), Swagger UI / Scalar / Redoc in local, dev and staging, the committed specs and their drift check, Redocly lint, none in prod (S-125) |
| [ci.md](ci.md) | CI pipelines on GitHub Actions and GitLab CI, manual trigger only (S-4/S-5, infra checks S-2/S-3) |
| [fulfilment.md](fulfilment.md) | deliveries, pooled run planning and its stop-order heuristic, courier shifts and assignment, proof of delivery, events, the courier app API (S-87) and the console's dispatch API (S-81), privacy (S-86) |
| [courier-app.md](courier-app.md) | the courier app (S-87, `mobile/`, Expo): run it locally, the fixture backend, checks, EAS builds, store accounts, signing, permission texts, what has never run on a device |
| [mobile-auth.md](mobile-auth.md) | the consumer and courier apps: sign-in with PKCE, DPoP-bound tokens, nonces, rotating refresh tokens and reuse detection, calling the api, sign-out, sessions (S-29) |
| [mcp.md](mcp.md) | the built-in MCP server for AI agents (Claude, IDEs, the MCP Inspector): connecting, OAuth 2.1 sign-in and consent, scopes, tools and confirmations, limits, audit, operations (S-127); the developer docs MCP server over docs/ and the OpenAPI documents (S-128) |
| [partners.md](partners.md) | partner API clients: `client_credentials` with `private_key_jwt`, keys (JWK Set URL or registered), scopes, business binding, rotation, revocation, rate limits, audit (S-30) |
| [federation.md](federation.md) | Google and Apple sign-in: console set-up, redirect URIs per environment, secrets, the Apple client secret (S-18) |
| [secrets.md](secrets.md) | secrets in AWS Secrets Manager / Secret Manager / Key Vault through External Secrets Operator: inventory, set-up, rotation (S-6) |
| [edge.md](edge.md) | public hosts per environment, DNS delegation, Let's Encrypt certificates (cert-manager), external-dns, Envoy Gateway, HSTS/TLS policy, WAF options per cloud, storefront custom domains (S-17) |
| [custom-domains.md](custom-domains.md) | merchants' own domains: TXT + CNAME verification, lifecycle and grace period, the api's Gateway/cert-manager reconciler (shards past 64 listeners), Let's Encrypt limits, abuse controls, the by-host routing contract (S-31) |
| [gitops.md](gitops.md) | Argo CD: app of apps per environment, promotion by PR (digests in `images.yaml`), dev auto-sync, staging/prod manual sync by deployers, roll back, kind rehearsal (S-15) |
| [deploy.md](deploy.md) | container images (Jib, Dockerfile) to any registry, the Helm chart per environment and cloud, install/upgrade/roll back, local rehearsal on kind (S-14) |

## Environment matrix

| | local | dev | staging | prod |
|---|---|---|---|---|
| Spring profile (`SPRING_PROFILES_ACTIVE`) | `local` (optionally `local,valkey`) | `dev` (+ `cloud`, automatic) | `staging` (+ `cloud`) | `prod` (+ `cloud`) |
| Where | your machine | Kubernetes in a Canadian region of AWS, Google Cloud or Azure | same | same |
| Postgres 17 + PostGIS | yours, or `docker compose --profile db` | managed | managed, HA | managed, HA + PITR |
| Valkey / Redis | not needed (`valkey` add-on: yours, or `--profile cache`) | managed | managed | managed, replicated |
| Kafka | off (`--profile events` for the worker) | managed | managed | managed, RF 3 |
| Elasticsearch | off (`--profile search` for the worker) | Elastic Cloud / ECK | same | same |
| Object storage (`STORAGE_PROVIDER`) | `local` folders, or `s3` + RustFS (`--profile storage`) | `s3` / `gcs` / `azure` (`local` = uploads fail) | `s3` / `gcs` / `azure` **required** | same |
| Sign-in | dev auth (`X-Dev-User`) or northline-auth with seeded personas | northline-auth | northline-auth | northline-auth |
| Dev seed (`db/seed-dev`) | yes | **no** (not in the images; refused — S-16) | **no** | **no** |
| Stripe | fake gateway, or stripe-mock (`--profile payments`) | fake, or test keys | **test keys required** | **live keys required** |
| Business registries (`REGISTRY_*_PROVIDER`) | `fixtures` | `fixtures` (warning), `manual` or the APIs | **`manual` / APIs** (`fixtures` refused) | same |
| Calendar sync (`CALENDAR_PROVIDER`) | `local`: fake Google / Outlook (connects at once, a lunch block tomorrow) | `local`, or `oauth` with the dev app registrations | **`oauth`** (`local` refused) | same |
| Custom domains (`DOMAINS_DNS_PROVIDER`, `DOMAINS_EDGE_PROVIDER`) | `local`: in-memory DNS zone ("Simulate DNS records →") and edge | `doh` + `kubernetes` (chart), Let's Encrypt **staging** | **`doh`/`jndi` + `kubernetes`** (`local` refused), Let's Encrypt staging | same, Let's Encrypt production |
| Catalogue sync (`COMMERCE_PROVIDER`) | `local`: fake Shopify / Square / Lightspeed with fixture catalogues | `local`, or `oauth` with the dev apps (Square sandbox) | **`oauth`** (`local` refused) | same |
| POS menu import (`POS_PROVIDER`) | `local`: fake Square / Clover / Toast with a fixture menu | `local`, or `oauth` with the dev apps (sandboxes) | **`oauth`** (`local` refused) | same |
| Addresses (`PLACES_PROVIDER`) | `local`: fixture Canadian addresses | `local`, or `google` with the dev key | **`google`** (`local` refused) | same |
| AI (`AI_PROVIDER`, S-129) | `fake`: deterministic answers, no key | `fake`, or `openrouter` with the dev key | **`openrouter`** (`fake` refused; no key yet = AI answers 503) | same |
| Identity verification (`IDENTITY_PROVIDER`) | `local`: pick the outcome on a page | `local` (owners can't finish) or `stripe` with test keys | **`stripe`**, test mode | **`stripe`**, live mode |
| SMS / email | logged | *no provider yet (S-8, S-13)* | same | same |
| Required variables checked at start-up | none | yes | yes (+ Stripe, storage) | yes (+ Stripe, storage) |
| Log level `ca.northline` | debug | debug | info | info |
| OpenAPI / Swagger UI | on | on | on | **off** |

## How configuration works

- **Environment variables everywhere.** Every connection, credential and public URL in api, auth, bff and worker is
  `${VARIABLE:local-default}` in `application.yml`. The same image runs in every environment and on every cloud; only
  the variables change. No secret is committed: the defaults are local-only values such as `northline/northline`.
  The full list with comments is [`server/.env.example`](../../server/.env.example).
- **Profiles carry the environment's shape, not its values.** `local` swaps in fakes (dev auth, logged SMS,
  in-memory sessions, local files, dev seed). `dev`, `staging` and `prod` each activate the `cloud` group profile
  (`application-cloud.yml`), which turns the fakes off, keeps cookies `Secure`, runs Kafka externalization, and
  **checks at start-up that every required variable is set**. A missing one stops the app before it touches anything:

  ```
  APPLICATION FAILED TO START
  Description:
  The active profiles [dev, cloud] need environment variables that are not set:
    cache: REDIS_HOST
    database: DB_URL, DB_USER, DB_PASSWORD
    …
  Action:
  Set these variables (…). docs/runbooks/dev.md lists every variable, whether it is required and where its value comes from.
  ```

  The lists live under `northline.required-env.<purpose>` in each app's `application-cloud.yml` (and
  `application-staging.yml` / `application-prod.yml` add `payments: STRIPE_SECRET_KEY, STRIPE_PUBLISHABLE_KEY,
  STRIPE_WEBHOOK_SECRET, STRIPE_CONNECT_WEBHOOK_SECRET` and `tax: TAX_PROVIDER`).
  The check is `ca.northline.platform.RequiredEnvironmentCheck` (module `server/platform`).
- **Providers are chosen by configuration** so that moving between AWS, Google Cloud and Azure is a variable change.
  The property names live in `ca.northline.platform.*Properties` (default `local`); the cloud adapters come with the
  stories below.

  | property | variable | values | adapters arrive in |
  |---|---|---|---|
  | `northline.storage.provider` | `STORAGE_PROVIDER` | `local` · `s3` (AWS S3, MinIO/RustFS, any S3 API) · `gcs` · `azure` | **done** (S-10, api uploads — [object-storage.md](object-storage.md)) |
  | `northline.kms.provider` | `KMS_PROVIDER` | `local` · `aws` · `gcp` · `azure` | **done** (S-7, auth token signing keys — [key-rotation.md](key-rotation.md); S-32, api envelope encryption of stored secrets with `KMS_ENCRYPTION_KEY_ID` — [calendar-sync.md](calendar-sync.md#the-envelope-key-kms_encryption_key_id)) |
  | `northline.calendar.provider` | `CALENDAR_PROVIDER` | `local` (fake Google and Outlook) · `oauth` (Google Calendar API, Microsoft Graph; each once its client is set) | **done** (S-32, calendar two-way sync — [calendar-sync.md](calendar-sync.md)) |
  | `northline.domains.dns.provider` | `DOMAINS_DNS_PROVIDER` | `local` (in-memory zone) · `doh` (DNS over HTTPS, RFC 8484) · `jndi` (the JDK's DNS client) | **done** (S-31, custom domain verification — [custom-domains.md](custom-domains.md)) |
  | `northline.domains.edge.provider` | `DOMAINS_EDGE_PROVIDER` | `local` (in memory) · `kubernetes` (shard Gateways, cert-manager Certificates, HTTPRoutes in the app's namespace) | **done** (S-31 — [custom-domains.md](custom-domains.md)) |
  | `northline.commerce.provider` | `COMMERCE_PROVIDER` | `local` (fake Shopify, Square and Lightspeed) · `oauth` (Shopify Admin GraphQL, Square Catalog + Inventory, Lightspeed X-Series; each once its app is set) | **done** (S-35, catalogue sync — [commerce-sync.md](commerce-sync.md)) |
  | `northline.pos.provider` | `POS_PROVIDER` | `local` (fake Square, Clover and Toast) · `oauth` (Square Catalog, Clover REST v3, Toast menus v2; each once its credentials are set) | **done** (S-36, kitchens' POS menu import — [pos-menu-import.md](pos-menu-import.md)) |
  | `northline.places.provider` | `PLACES_PROVIDER` | `local` (fixture addresses) · `google` (Places API (New) + Geocoding API with `GOOGLE_MAPS_API_KEY`) | **done** (S-47, consumer Location screen and pill — [google-maps.md](google-maps.md)) |
  | `northline.console.health.provider` | `CONSOLE_HEALTH_PROVIDER` | `none` (system health unknown) · `prometheus` (PromQL over the HTTP API of any Prometheus-compatible store) | **done** (S-91, the console overview's system health — [observability.md](observability.md#console-health-s-91)) |
  | `northline.ai.provider` | `AI_PROVIDER` | `fake` (deterministic, offline) · `openrouter` (OpenRouter's OpenAI-compatible API; 503 without `OPENROUTER_API_KEY`) | **done** (S-129, the `LlmClient` port for every AI feature — [ai.md](ai.md)) |
  | `northline.email.provider` | `EMAIL_PROVIDER` | `local` (SMTP to Mailpit) · `smtp` · `ses` · `sendgrid` · `azure` | **done** (S-13, api invitations and money notices — [email.md](email.md); S-27 worker: `payout.failed`) |
  | `northline.tax.provider` | `TAX_PROVIDER` | `local` (fixed Canadian rates) · `stripe` (Stripe Tax) | **done** (S-21, api sales tax — [stripe.md § 6](stripe.md#6-stripe-tax-s-21)) |
  | `northline.identity.provider` | `IDENTITY_PROVIDER` | `local` (fake with an outcome page) · `stripe` (Stripe Identity) | **done** (S-22, owners' identity verification — [stripe.md § Identity](stripe.md#8-identity-s-22)) |
  | `northline.registries.<source>.provider` | `REGISTRY_CORPORATIONS_CANADA_PROVIDER`, `REGISTRY_ALBERTA_PROVIDER`, `REGISTRY_CALGARY_PROVIDER` | `fixtures` · `manual` · `api` (Corporations Canada) / `opencorporates` (Alberta) / `socrata` (Calgary) | **done** (S-23, business registry lookups — [registries.md](registries.md)) |
  | `northline.sms.provider` | `SMS_PROVIDER` | `local` · `twilio` · `aws` (End User Messaging SMS and voice) · `azure` (reserved) | **done** (S-8, auth phone codes — [SMS and voice codes](#sms-and-voice-codes-s-8); S-27: shared library `server/sms`, also api invitations and worker notifications — [notifications.md](notifications.md)) |

  Secrets reach the apps as environment variables in every cloud (External Secrets from AWS Secrets Manager, Google
  Secret Manager or Azure Key Vault — S-6, [secrets.md](secrets.md)), so there is no `secrets.provider` switch. Each app logs its choice at
  start-up: `Providers: storage=local kms=local email=local sms=local`.
- **Local convenience.** The apps import `server/.env` (or `.env` in the working directory) when it exists, except
  under the `test` profile; real environment variables win. Docker Compose reads the root `.env`; Vite reads
  `web/apps/studio/.env`.

## Variables at a glance

Required = the app refuses to start under `dev`/`staging`/`prod` without it. Per-environment examples and where each
value comes from are in [dev.md](dev.md#environment-variables), [staging.md](staging.md#environment-variables) and
[prod.md](prod.md#environment-variables).

| variable | api | auth | bff | worker | required in the cloud |
|---|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ✓ | ✓ | ✓ | ✓ | yes (`dev`, `staging` or `prod`) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | ✓ | ✓ | | ✓ | yes |
| `DB_POOL_SIZE` | ✓ | ✓ | | ✓ | no (10; worker 5) |
| `REDIS_HOST` | ✓ | ✓ | ✓ | ✓ | yes |
| `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL` | ✓ | ✓ | ✓ | ✓ | no (6379, none, none, false) |
| `KAFKA_BOOTSTRAP` | ✓ | ✓ | | ✓ | yes (auth since S-28: user.registered) |
| `KAFKA_SECURITY_PROTOCOL`, `KAFKA_SASL_MECHANISM`, `KAFKA_SASL_JAAS_CONFIG` | ✓ | ✓ | | ✓ | no (PLAINTEXT) — set for managed Kafka |
| `ES_URIS` | ✓ | | | ✓ | yes |
| `ES_USERNAME`, `ES_PASSWORD` | ✓ | | | ✓ | no — set for Elastic Cloud |
| `AUTH_ISSUER` | ✓ | ✓ | ✓ | | yes |
| `AUTH_INTERNAL_URL` | | | ✓ | | no (= `AUTH_ISSUER`) |
| `API_URL` | | | ✓ | | yes |
| `STUDIO_ORIGIN` | ✓ | ✓ | | | yes |
| `CONSUMER_ORIGIN` | ✓ (S-76: the embed script's site) | ✓ | | | yes |
| `CONSOLE_ORIGIN`, `WEBAUTHN_RP_ID` | | ✓ | | | yes |
| `TOTP_KEY` | | ✓ | | | yes |
| `STUDIO_BFF_SECRET` | | | ✓ | | yes |
| `STUDIO_BFF_SECRET_HASH` | | ✓ | | | yes |
| `CONSUMER_BFF_SECRET` | | | ✓ (`consumer` profile) | | yes, for the consumer-bff (S-45, [Consumer BFF](#consumer-bff-s-45)) |
| `CONSUMER_BFF_SECRET_HASH` | | ✓ | | | yes (S-45: the consumer-bff exists) |
| `CONSOLE_BFF_SECRET` | | | ✓ (`console` profile) | | yes, for the console-bff (S-90, [Console BFF](#console-bff-s-90)) |
| `CONSOLE_BFF_SECRET_HASH` | | ✓ | | | yes (S-90: the console-bff exists) |
| `OAUTH_CLIENTS_SYNC_ON_STARTUP` | | ✓ | | | no (`true`; `false` = register only with the Job) |
| `GOOGLE_CLIENT_ID`/`_SECRET`, `APPLE_CLIENT_ID`, `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY` | | ✓ | | | staging, prod (S-18, [federation.md](federation.md)); empty = that provider off |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | ✓ | | | | staging and prod |
| `STRIPE_API_BASE` | ✓ | | | | never in the cloud (stripe-mock only) |
| `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET` | ✓ | | | | staging and prod (S-12; [stripe.md](stripe.md#5-webhooks-s-12)) |
| `TAX_PROVIDER` | ✓ | | | | staging and prod: `stripe` (`local` refused there — S-21, [stripe.md § 6](stripe.md#6-stripe-tax-s-21)) |
| `TAX_CODE_SERVICE`, `TAX_CODE_GOODS`, `TAX_CODE_FOOD`, `TAX_RECONCILE_CRON` | ✓ | | | | no (Stripe's general service / goods / prepared-food codes; 03:17 in the platform zone) |
| `IDENTITY_PROVIDER` | ✓ | | | | staging and prod (`stripe`; `local` refused there — S-22, [stripe.md § Identity](stripe.md#8-identity-s-22)) |
| `REGISTRY_CORPORATIONS_CANADA_PROVIDER`, `REGISTRY_ALBERTA_PROVIDER`, `REGISTRY_CALGARY_PROVIDER` | ✓ | | | | staging and prod (`fixtures` refused there — S-23, [registries.md](registries.md)) |
| `REGISTRY_CORPORATIONS_CANADA_URL`/`_KEY`/`_KEY_HEADER`, `REGISTRY_ALBERTA_URL`/`_KEY`, `REGISTRY_CALGARY_URL`/`_DATASET`/`_APP_TOKEN`, `REGISTRY_RECHECK_AFTER`, `REGISTRY_RECHECK_CRON` | ✓ | | | | per provider ([registries.md](registries.md#set-up-per-environment)) |
| `WEBHOOK_SECRET_KEY` | ✓ | | | ✓ | yes (the same value in both: the api encrypts partner webhook secrets, the worker decrypts them to sign — S-33) |
| `WEBHOOKS_ALLOW_LOCAL` | | | | ✓ | no (`false`; `true` only locally — http://localhost endpoints; refused in the cloud) |
| `IMPORT_IMAGES_ALLOW_LOCAL` | ✓ | | | | no (`false`; `true` only locally — bulk-import image URLs on http:// or loopback, never private or metadata addresses; refused in the cloud — S-72, [webhooks.md § SSRF rules](webhooks.md#ssrf-rules-platform-egresspolicy)) |
| `SEARCH_PROVIDER` | ✓ | | | | no (`elasticsearch`; `local` = no index, the `local` profile's default, refused in staging/prod — [search.md § 7](search.md#7-the-search-api-s-44)) |
| `SEARCH_CACHE_TTL`, `SEARCH_RATE_LIMIT` | ✓ | | | | no (`30s`, `120`/min per address) |
| `REGION_PROVINCES`, `REGION_DEFAULT_PROVINCE`, `REGION_CACHE_TTL` | ✓ | | | ✓ (`REGION_DEFAULT_PROVINCE`) | no (none extra — the live region rows are served, V131: Alberta; `AB`; `60s`) — S-134, [regions.md](regions.md); S-44's `SEARCH_MARKETS` / `SEARCH_DEFAULT_MARKET` are still read as their fallbacks |
| `REGION_PLATFORM_ZONE` | ✓ | ✓ | | ✓ | no (`America/Edmonton`: nightly jobs, support hours, account dates — work that belongs to no market; [regions.md](regions.md)) |
| `EMAIL_TIME_ZONE` | ✓ | | | ✓ | no (= `REGION_PLATFORM_ZONE`: the zone dates in emails are written in) |
| `REGISTRY_CALGARY_LICENCES` | ✓ | | | | no (`mobile permit,calgary business licence`: licence names the municipal dataset answers — [registries.md](registries.md)) |
| `SEARCH_RECONCILE_ENABLED`, `SEARCH_RECONCILE_EVERY` | | | | ✓ | no (`true`, `1m` — [search.md § 6](search.md#6-the-indexer-s-43)) |
| `WEBHOOKS_MAX_IN_FLIGHT`, `WEBHOOKS_CONNECT_TIMEOUT`, `WEBHOOKS_RESPONSE_TIMEOUT`, `WEBHOOKS_TOTAL_TIMEOUT`, `WEBHOOKS_DISABLE_AFTER`, `WEBHOOKS_LOG_RETENTION` | | | | ✓ | no (64, 5s, 10s, 15s, 3d, 30d — [webhooks.md](webhooks.md)) |
| `STORAGE_PROVIDER`, `STORAGE_BUCKET` | ✓ | | | | staging and prod (`local` refused there — S-10, [object-storage.md](object-storage.md)) |
| `STORAGE_REGION`, `STORAGE_ENDPOINT`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PATH_STYLE`, `STORAGE_ENCRYPTION_KEY` | ✓ | | | | no (`STORAGE_ENDPOINT` needed for `azure`) |
| `KMS_PROVIDER`, `KMS_KEY_ID` | ✓ (`KMS_PROVIDER`) | ✓ | | | `KMS_PROVIDER` everywhere, `KMS_KEY_ID` in staging and prod (S-7, [key-rotation.md](key-rotation.md)) |
| `KMS_ENCRYPTION_KEY_ID` | ✓ | | | | staging and prod (S-32: the api's envelope key, Terraform output; [calendar-sync.md](calendar-sync.md#the-envelope-key-kms_encryption_key_id)) |
| `KMS_LOCAL_KEY` | ✓ | | | | no (`KMS_PROVIDER=local` outside local/test only; refused in staging/prod) |
| `KMS_PUBLISHED_KEY_IDS`, `SIGNING_KEYS_DIR`, `SIGNING_KEYS_ROTATE_EVERY` | | ✓ | | | no |
| `KMS_REGION`, `KMS_ENDPOINT` | ✓ | ✓ | | | no (AWS region chain; LocalStack) |
| `CALENDAR_PROVIDER` | ✓ | | | | staging and prod: `oauth` (`local` refused there — S-32, [calendar-sync.md](calendar-sync.md)) |
| `GOOGLE_CALENDAR_CLIENT_ID`/`_SECRET`, `MICROSOFT_CALENDAR_CLIENT_ID`/`_SECRET` | ✓ | | | | no — empty = that provider shows "Not available yet" ([calendar-sync.md](calendar-sync.md#set-up-per-environment)) |
| `MICROSOFT_CALENDAR_TENANT`, `CALENDAR_SYNC_INTERVAL`, `CALENDAR_WEBHOOK_RATE_LIMIT` | ✓ | | | | no (`common`, `PT5M`, 600/min) |
| `DOMAINS_DNS_PROVIDER`, `DOMAINS_EDGE_PROVIDER`, `DOMAINS_TARGET_HOST` | ✓ | | | | staging and prod (`local` refused there; the chart sets all three with `edge.domainReconciler.enabled` — S-31, [custom-domains.md](custom-domains.md#5-configuration)) |
| `DOMAINS_DOH_URL`, `DOMAINS_DNS_SERVERS`, `DOMAINS_EDGE_ADDRESSES`, `DOMAINS_BLOCKED_SUFFIXES`, `DOMAINS_ISSUE_PER_HOUR`, `DOMAINS_REQUEST_COOLDOWN`, `DOMAINS_MAX`, `DOMAINS_VERIFY_WINDOW`, `DOMAINS_GRACE_PERIOD`, `DOMAINS_CHECK_COOLDOWN`, `DOMAINS_CHECK_INTERVAL`, `DOMAINS_EDGE_INTERVAL`, `DOMAINS_EDGE_*` | ✓ | | | | no (CIRA Canadian Shield, the pod's resolver, none, `northline.ca`, 20, 1 h, 1000, 7 d, 72 h, 15 s, 1 min, 1 min; `DOMAINS_EDGE_*` from the chart) |
| `COMMERCE_PROVIDER` | ✓ | | | | staging and prod: `oauth` (`local` refused there — S-35, [commerce-sync.md](commerce-sync.md)) |
| `SHOPIFY_CLIENT_ID`/`_SECRET`, `SHOPIFY_API_VERSION`, `SQUARE_CLIENT_ID`/`_SECRET`, `SQUARE_WEBHOOK_SIGNATURE_KEY`, `SQUARE_BASE_URL`, `LIGHTSPEED_CLIENT_ID`/`_SECRET` | ✓ | | | | no — empty = that platform shows "Not available yet" ([commerce-sync.md](commerce-sync.md#variables-api)) |
| `COMMERCE_POLL_INTERVAL`, `COMMERCE_RECONCILE_INTERVAL`, `COMMERCE_WEBHOOK_RATE_LIMIT` | ✓ | | | | no (`PT1H`, `P1D`, 600/min) |
| `POS_PROVIDER` | ✓ | | | | staging and prod: `oauth` (`local` refused there — S-36, [pos-menu-import.md](pos-menu-import.md)) |
| `CLOVER_CLIENT_ID`/`_SECRET`, `CLOVER_AUTH_URL`, `CLOVER_API_URL`, `TOAST_CLIENT_ID`/`_SECRET`, `TOAST_API_URL` | ✓ | | | | no — empty = that POS shows "Not available yet" ([pos-menu-import.md](pos-menu-import.md#variables-api)) |
| `PLACES_PROVIDER`, `GOOGLE_MAPS_API_KEY` | ✓ | | | | staging and prod: `google` + the key (`local` refused there — S-47, [google-maps.md](google-maps.md)) |
| `PLACES_RATE_LIMIT` | ✓ | | | | no (60 address lookups per browsing session and minute) |
| `CONSOLE_HEALTH_PROVIDER`, `CONSOLE_HEALTH_PROMETHEUS_URL`, `CONSOLE_HEALTH_PROMETHEUS_TIMEOUT` | ✓ | | | | no (`none` = the console overview's system health shows unknown; `prometheus` + the URL — S-91, [observability.md § Console health](observability.md#console-health-s-91)) |
| `CONSOLE_HEALTH_PROMETHEUS_TOKEN` | ✓ | | | | no — secret, when the metrics store needs a bearer token |
| `CONSOLE_MAP_TILES`, `CONSOLE_MAP_ATTRIBUTION` | ✓ | | | | no (empty = the console's delivery ops map draws the zones on its own grid; an `https://…/{z}/{x}/{y}.png` XYZ tile template + the provider's credit — S-81, [§ Console map](#console-map-s-81)) |
| `AI_PROVIDER` | ✓ | | | | staging and prod: `openrouter` (`fake` refused there — S-129, [ai.md](ai.md)) |
| `OPENROUTER_API_KEY` | ✓ | | | | no — empty = every AI feature answers 503 `ai_unavailable` (secret; [ai.md](ai.md#variables)) |
| `OPENROUTER_MODEL`, `OPENROUTER_LIGHT_MODEL`, `OPENROUTER_MODEL_<FEATURE>`, `OPENROUTER_BASE_URL`, `OPENROUTER_REFERER`, `OPENROUTER_TITLE`, `OPENROUTER_DATA_COLLECTION`, `OPENROUTER_ZDR`, `OPENROUTER_*_TIMEOUT`, `AI_MAX_TOOL_ROUNDS`, `AI_BUDGET_*`, `AI_REQUESTS_PER_MINUTE`, `AI_FAKE_*` | ✓ | | | | no (`google/gemini-3.7-flash`, `google/gemini-3.5-flash-lite`, blank, openrouter.ai, `STUDIO_ORIGIN`, `Northline`, `deny`, `true`, 5 s / 60 s, 4, 200k / 1M tokens a day, 20/min, off — [ai.md](ai.md#variables)) |
| `EMAIL_PROVIDER`, `EMAIL_FROM` | ✓ | | | ✓ | staging and prod (`local` refused there — S-13, [email.md](email.md)) |
| `EMAIL_UNSUBSCRIBE_KEY`, `API_PUBLIC_URL` | ✓ | | | ✓ | staging and prod (unsubscribe links; the worker signs them for `payout.failed`, S-27 — [email.md](email.md#variables); S-32 calendar notification URLs) |
| `API_PUBLIC_URL` (auth) | | ✓ | | | no (`http://localhost:8080`; the chart derives it: S-127's MCP resource, and outside prod the `docs` client's redirect URIs and token-endpoint CORS origin — S-139) |
| `EMAIL_REPLY_TO`, `EMAIL_MAILING_ADDRESS`, `EMAIL_CONTACT`, `EMAIL_REGION`, `EMAIL_ENDPOINT`, `EMAIL_API_KEY`, `EMAIL_CONFIGURATION_SET`, `EMAIL_RETRY_*`, `SMTP_*` | ✓ | | | ✓ | per provider: `EMAIL_API_KEY` with `sendgrid`, `EMAIL_ENDPOINT` with `azure`, `SMTP_HOST` with `smtp` ([email.md](email.md#variables)) |
| `SMS_PROVIDER`, `SMS_FROM` | ✓ | ✓ | | ✓ | staging and prod (`local` refused there; `dev` may keep `local`). api: team invitations, worker: notifications (S-27) |
| `SMS_ACCOUNT_ID`, `SMS_AUTH_TOKEN` | ✓ | ✓ | | ✓ | with `SMS_PROVIDER=twilio` |
| `SMS_VOICE_FROM`, `SMS_REGION`, `SMS_ENDPOINT` | ✓ | ✓ | | ✓ | no (`= SMS_FROM`; SDK default region; provider API) |
| `TRUSTED_PROXIES` | | ✓ | | | no (private ranges + loopback; narrow it to the ingress subnet) |
| `RATE_LIMIT_STORE` | | ✓ | | | no (`redis`; `memory` only under `local`/`test`) |
| `RATE_LIMIT_WHEN_UNAVAILABLE` | | ✓ | | | no (`closed` in staging/prod, `open` elsewhere — S-20, [Rate limits](#rate-limits-s-9)) |
| `REPLAY_STORE`, `DPOP_NONCE_LIFETIME` | | ✓ | | | no (`redis` — `memory` only under `local`/`test`; `5m`) — S-29, [mobile-auth.md](mobile-auth.md) |
| `CLIENT_CITY_HEADER` | | ✓ | ✓ (`consumer` profile) | | no (empty: no city in the session list — S-19; no IP guess for the consumer location pill — S-45) |
| `SESSION_STEP_UP_MAX_AGE` | | ✓ | | | no (`10m`: how recent a second factor revoking sessions / removing passkeys needs) |
| `SESSION_CHECK_INTERVAL` | | | ✓ | | no (`60s`: how often the BFF checks its session wasn't revoked) |
| `MCP_RESOURCE` | ✓ | ✓ | | | no (`${API_PUBLIC_URL}/mcp`: the MCP server's canonical URI — the api checks token audiences against it, auth accepts it as a resource indicator; set both to the same value — S-127, [mcp.md](mcp.md)) |
| `MCP_DOCS_RESOURCE` | ✓ | ✓ | | | no (`${API_PUBLIC_URL}/mcp/docs`, the developer docs MCP server — S-128; same value in both) |
| `MCP_DOCS_ACCESS` | ✓ | | | | no (`staff` in the cloud — `open` refused under staging/prod; `open` locally — [mcp.md § Developer docs](mcp.md#developer-docs-s-128)) |
| `MCP_STORE`, `MCP_CALLS_PER_MINUTE`, `MCP_WRITES_PER_MINUTE` | ✓ | | | | no (`redis` in the cloud, `memory` locally — refused in staging/prod; 60 tool calls and 10 changes per person per minute — [mcp.md](mcp.md#limits)) |
| `LIVE_BUS`, `LIVE_STREAM` | ✓ | | | | no (`redis` in the cloud — Valkey pub/sub so the Studio's live stream works on every api replica; `memory` locally — refused in staging/prod; `10m` per stream before the browser reconnects — S-68); S-88: couriers' latest positions (`nl:courier-pos:*`, TTL, no history) and their moves to the customers' tracking streams use the same switch |
| `MCP_CLIENT_METADATA_DOCUMENTS`, `MCP_CLIENT_METADATA_HOSTS` | | ✓ | | | no (`true`: agents may identify with a Client ID Metadata Document; empty = from any public HTTPS host — [mcp.md](mcp.md#client-registration)) |
| `LOG_FORMAT` | ✓ | ✓ | ✓ | ✓ | no — `ecs` JSON under dev/staging/prod, plain `text` under local/test (S-112, [logging.md](logging.md)) |
| `OTEL_EXPORT_ENABLED`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_TRACES_SAMPLER_ARG`, `OTEL_RESOURCE_ATTRIBUTES` | ✓ | ✓ | ✓ | ✓ | no — the chart sets them when its Collector is on (S-111, [observability.md](observability.md)); locally `make up OBS=1` |
| `SERVER_PORT` | ✓ | ✓ | ✓ | | no (8080 / 9000 / 8082; the consumer-bff 8081) |

Documentation site (S-126, [docs-site.md](docs-site.md)): the `docs` / `docs-internal` images read `NL_DOCS_SWAGGER` and
`NL_DOCS_CONNECT`, which the chart derives from `urls.*` and `apps.api.docsRoutes`; nothing to set by hand.

Studio (web): the container image reads `NL_AUTH_ORIGIN` (= `AUTH_ISSUER`; the chart sets it from `urls.auth`) at
start and serves it as `/config.js`, so one Studio image serves every environment (S-14). `VITE_NL_AUTH_ORIGIN` is
only the build-time fallback (the Vite dev server). Worker: `SERVER_PORT` 8084, health only (S-14).

Consumer web (S-45, `web/apps/consumer`, TanStack Start SSR on Node): `PORT` (3000), `NL_BFF_URL` (the consumer-bff for
server-side rendering; the chart sets `http://northline-consumer-bff:8081`), `NL_AUTH_ORIGIN` (= `urls.auth`; the
browser signs out of northline-auth there), `TRUST_PROXY` (`true` behind the Gateway; it also makes server-rendered searches pass the visitor's `X-Forwarded-For` on to the api's per-client limit, S-48). `GET /healthz` answers `ok`.
S-54: `NL_SITE_ORIGIN` (= `urls.consumer`, the site's own origin; business pages served elsewhere link back to it) and
`NL_PAGES_HOST` (= the host of `urls.pages`): with it set, `pages.<zone>/<slug>` and merchants' own domains (looked up
with `GET /api/v1/public/storefronts/by-host`, S-31) serve the business's public page; without it (local) every host is
the site. Both optional, set by the chart. S-63: `NL_SITE_ORIGIN` is also the origin of every canonical URL, hreflang
alternate and sitemap entry, and the server answers `/robots.txt`, `/sitemap.xml` and `/sitemaps/*.xml` itself (from
`GET {NL_BFF_URL}/api/v1/public/sitemap`; a merchant's domain gets its own robots.txt and one-page sitemap) — set it to
the public origin in every environment that should be indexed. Nothing to configure for the legal pages (they are in
the image).

the site. Both optional, set by the chart. S-61: `NL_STUDIO_ORIGIN` (= `urls.studio`, set by the chart; default
`http://localhost:3100`) — `/sell` links into the Studio's onboarding there (signed in: the Studio BFF's
`/bff/login?next=/onboarding?type=…` hand-off; a guest: `/onboarding?type=…`, which signs in on the Studio).

## Consumer BFF (S-45)

The consumer web app has its own BFF: the **bff image with the `consumer` profile added last**
(`SPRING_PROFILES_ACTIVE=dev,consumer`; locally `--spring.profiles.active=local,consumer`), deployed as
`northline-consumer-bff` (chart `apps.consumer-bff`, port 8081). It serves `/api`, `/bff`, `/oauth2` and `/login` on the
consumer host (the apex) and `/api`, `/bff` on `pages.` (signing in happens on the apex). Compared with the Studio's:

| | studio-bff | consumer-bff |
|---|---|---|
| OAuth client | `studio-bff`, scopes openid profile merchant | `consumer-bff` (registration `northline`), scopes openid profile orders bookings |
| client secret | `STUDIO_BFF_SECRET` | `CONSUMER_BFF_SECRET` (auth: `CONSUMER_BFF_SECRET_HASH`) |
| session cookie | `__Host-NL_STUDIO` | `__Host-NL_CONSUMER`; sessions under `nl:consumer-bff:*` |
| signed out | `/bff/session` 401, `/api/**` 401 | **guests**: `/bff/session` 200 `{user: null, guestId}`, `/api/**` relayed without a token (the api answers its public endpoints, 401 otherwise) |
| second factor | the api refuses merchant endpoints without `acr=mfa` | not needed (consumer tokens carry `acr=mfa` only after a passkey / authenticator) |

Same as the Studio's: CSRF double-submit (`__Host-XSRF-TOKEN`, header `X-XSRF-TOKEN` only — guests' POSTs too), the
S-19 revocation check, sign-out revoking the refresh token, `next` limited to local paths, no framing.

- **Guest id:** 128 random bits in the BFF session (created by `GET /bff/session`, kept across sign-in), relayed to the
  api as `X-Northline-Guest` on every `/api` call of that session; the browser's own `X-Northline-Guest`,
  `Authorization` and `X-Dev-User` headers are dropped. It keys a guest's cart; it is never authentication.
- **IP city:** with `CLIENT_CITY_HEADER` set (e.g. `CloudFront-Viewer-City`, or a header the Gateway fills), `GET
  /bff/session` adds `location.city` — the location pill's first guess. Only set it when the edge overwrites that
  header on every request (Envoy alone passes the client's through); it is display-only either way.
- **Secrets:** `consumer-bff-secret` (plain, the consumer-bff) and `consumer-bff-secret-hash` (`{bcrypt}` of the same
  value, auth) in the secrets manager — Terraform creates both empty ([secrets.md](secrets.md)). Rotate like the
  Studio's (below), with the `CONSUMER_` variables.

## Console BFF (S-90)

The platform console (`web/apps/console`, design 03, [CONSOLE_PLAN.md](../CONSOLE_PLAN.md)) has its own BFF: the **bff
image with the `console` profile added last** (`SPRING_PROFILES_ACTIVE=dev,console`; locally
`--spring.profiles.active=local,console`), deployed as `northline-console-bff` (chart `apps.console-bff`, port 8083). It
serves `/api`, `/bff`, `/oauth2` and `/login` on `console.<zone>`; the console app (nginx, like the Studio) serves the
rest. Compared with the Studio's:

| | studio-bff | console-bff |
|---|---|---|
| OAuth client | `studio-bff`, scopes openid profile merchant | `console-bff` (registration `console`), scopes openid profile console |
| client secret | `STUDIO_BFF_SECRET` | `CONSOLE_BFF_SECRET` (auth: `CONSOLE_BFF_SECRET_HASH`) |
| session cookie | `__Host-NL_STUDIO` | `__Host-NL_CONSOLE`; sessions under `nl:console-bff:*` |
| who gets a session | anyone signed in (the api checks memberships) | **staff with a second factor only**: the ID token (the `console` scope adds `roles`) must list `staff` and carry `acr=mfa`; otherwise the sign-in is ended at once (refresh token revoked) and the browser lands on `/sign-in?error=staff_only` or `?error=mfa_required` |
| sign-in page | the Studio's | the console's own (`northline.auth.console-login-page` = `${CONSOLE_ORIGIN}/sign-in`; `CONSOLE_ORIGIN` may call the auth JSON API and use passkeys) |

Same as the Studio's: CSRF double-submit (`__Host-XSRF-TOKEN`, header `X-XSRF-TOKEN` only), the S-19 revocation check,
sign-out revoking the refresh token, `next` limited to local paths, no framing. The browser's `X-Console-Role` header
(the console's role view) is relayed; the api checks the person holds that role.

**Staff roles** live in `identity.platform_roles` (`staff` opens the console; `admin`, `trust_safety`, `dispatch`,
`finance`, `support`, `support_lead` (S-83, V214: support plus editing the desk's macros), `analyst` decide the screens
and actions — V190) and reach the api in the access token's `roles`
claim (10 min). Grant or take one away with SQL until the Team screen (S-96) does it; it applies at the person's next
token refresh:

```sql
INSERT INTO identity.platform_roles (user_id, role, granted_by) VALUES ('<user id>', 'staff', '<admin id>'), ('<user id>', 'finance', '<admin id>');
DELETE FROM identity.platform_roles WHERE user_id = '<user id>' AND role = 'finance';
```

- **Secrets:** `console-bff-secret` (plain, the console-bff) and `console-bff-secret-hash` (`{bcrypt}` of the same
  value, auth) in the secrets manager — Terraform creates both empty ([secrets.md](secrets.md)). Rotate like the
  Studio's (below), with the `CONSOLE_` variables.
- **Health:** `/actuator/health/{liveness,readiness}` on 8083; dashboard `northline-console-bff` (S-111).

## Console map (S-81)

The console's delivery ops map (design 03 `delivery`) draws a market's delivery zones (`region.zones` polygons) and
the couriers' latest positions itself, in Web Mercator fitted to the data. A raster basemap under them is optional:

| variable | value |
|---|---|
| `CONSOLE_MAP_TILES` | empty (default: the design's grid, no third party) · an **https** XYZ tile URL template with `{z}`, `{x}`, `{y}` — a self-hosted OpenStreetMap tile server, or a commercial one (MapTiler, Stadia, Thunderforest…). The api refuses another shape at start |
| `CONSOLE_MAP_ATTRIBUTION` | the provider's required credit, shown on the map (e.g. `© OpenStreetMap contributors`) |

The staff member's browser fetches the tiles (the console's CSP allows `img-src https:`), so a key in the template is
visible to staff: use a key the provider restricts to the console's origin, never S-47's Google server key. Respect
the provider's usage policy (the public `tile.openstreetmap.org` is not for production use). Nothing was tried against
a real tile server.

## OAuth clients (S-122)

northline-auth's OAuth clients are **configuration**: `northline.oauth.clients.<client-id>` in
`server/auth/src/main/resources/application.yml` (+ `application-local.yml` for local values). They are reconciled
into `auth.oauth2_registered_client` — created when missing, updated when different, **never deleted** (a client in
the database but not in configuration is logged as `stored but not in configuration (left as is)`):

- **at every start** of northline-auth (all profiles; replicas serialise on a Postgres advisory lock), unless
  `OAUTH_CLIENTS_SYNC_ON_STARTUP=false`;
- **by the admin command**, without starting the server — the deploy step "Register OAuth clients" and the
  Kubernetes Job:

  ```sh
  ./gradlew :auth:oauthClients --args='list'   # configuration vs database, writes nothing (profile: SPRING_PROFILES_ACTIVE, else local)
  ./gradlew :auth:oauthClients --args='sync'   # create / update
  # from the built jar (Job: same image and envFrom as the auth Deployment, args = sync):
  java -cp northline-auth.jar -Dloader.main=ca.northline.auth.clients.OAuthClientsCommand \
       org.springframework.boot.loader.launch.PropertiesLauncher sync
  ```

  It prints one line per client: `create`, `update <fields>` (e.g. `update secret` — values are never printed),
  `up to date`, or `stored but not in configuration`. It reads exactly the server's configuration, so give it the
  same environment (the required-variable check applies too).

| client | type | registered when | redirect URI | scopes |
|---|---|---|---|---|
| `studio-bff` | confidential (`client_secret_basic`) | always — `STUDIO_BFF_SECRET_HASH` is required | `${STUDIO_ORIGIN}/login/oauth2/code/studio` | openid profile merchant |
| `consumer-bff` | confidential | always since S-45 — `CONSUMER_BFF_SECRET_HASH` is required | `${CONSUMER_ORIGIN}/login/oauth2/code/northline` (locally also the consumer dev server, `http://localhost:3000/…`) | openid profile orders bookings |
| `console-bff` | confidential | always since S-90 — `CONSOLE_BFF_SECRET_HASH` is required | `${CONSOLE_ORIGIN}/login/oauth2/code/console` (locally also the console dev server, `http://localhost:3200/…`) | openid profile console (the ID token carries `roles` for this scope) |
| `mobile-consumer` ("Northline") | public (PKCE S256, no secret), **DPoP required** (S-29) | always | `${CONSUMER_ORIGIN}/app/oauth2redirect` (App Link / Universal Link), `ca.northline.app:/oauth2redirect` | openid profile orders bookings offline_access; refresh 30 d |
| `partner:<name>` (S-30) | client credentials, `private_key_jwt` (no secret) | when declared under `northline.oauth.partners` (chart value `partners`) | — | `api.read` / `api.write`, bound to named businesses; 15 min tokens — [partners.md](partners.md) |
| `northline-mcp` ("Northline MCP (AI agents)", S-127) | public (PKCE S256), **consent screen**, needs `acr=mfa` | always | `http://127.0.0.1/callback`, `http://127.0.0.1/oauth/callback` (any port), `https://claude.ai/api/mcp/auth_callback`, `https://claude.com/api/mcp/auth_callback` | openid profile merchant mcp mcp.write mcp.ops; 1 h access tokens, no refresh token — [mcp.md](mcp.md) |
| `https://…` (any HTTPS URL, S-127) | public (PKCE S256), consent screen, needs `acr=mfa` | registered on first use from the agent's [Client ID Metadata Document](mcp.md#client-registration) | from the document | at most openid profile merchant mcp mcp.write |
| `courier-app` ("Northline Courier") | public (PKCE S256, no secret), **DPoP required** (S-29) | always | `${CONSUMER_ORIGIN}/courier/oauth2redirect`, `ca.northline.courier:/oauth2redirect` | openid courier deliveries; refresh 12 h; signs in on the consumer site's page (S-87) — [courier-app.md](courier-app.md) |
| `docs` ("Northline API docs (Swagger UI, Scalar)", S-139) | public (PKCE S256), no consent | **local, test, dev, staging only** (never prod) | `${API_PUBLIC_URL}/swagger-ui/oauth2-redirect.html`, `${API_PUBLIC_URL}/docs/scalar` | openid profile merchant; 10 min access tokens, no refresh token; CORS on `/oauth2/token` for `${API_PUBLIC_URL}` — [api-docs.md](api-docs.md) |

Defaults for anything not set: grant types `authorization_code` + `refresh_token`, PKCE required, no consent screen,
access token 10 min, rotating refresh token 12 h (= the session idle limit), ES256 ID tokens. How the apps use their
clients (DPoP proofs, nonces, refresh, reuse detection, sign-out): [mobile-auth.md](mobile-auth.md).

**Rules, checked before anything is written** (the app or the Job stops with every problem listed):

| | `local`, `test` | `dev` | `staging`, `prod` |
|---|---|---|---|
| redirect / post-logout URIs | any absolute URI | `https`, or `http` on `localhost`/`127.0.0.1`/`[::1]` (the local rehearsal) | `https` only |
| confidential secret | an encoded value: `{bcrypt}…`, `{noop}…` | same; `{noop}` logs a warning | encoded and hashed — `{noop}` refused |

Everywhere: no wildcards or fragments; a public client may also use a reverse-domain private-use scheme
(`ca.northline.app:/…`, RFC 8252) or `http` on a loopback IP literal (`127.0.0.1`, `[::1]`, any port — desktop agents, S-127) and has no secret; a confidential client needs `secret-hash` (a plain secret is
refused; use bcrypt cost ≥ 10, e.g. `htpasswd -bnBC 12`); clients may not share a secret; `authorization_code` needs a redirect URI and PKCE; staging/prod need at
least one client.

**Rotating a BFF secret** (no code change): generate a new secret, put its bcrypt hash in
`STUDIO_BFF_SECRET_HASH` and the plain value in the bff's `STUDIO_BFF_SECRET`, run the Job (or restart auth), then
restart the bff. Between the two the bff's old secret is refused (sign-ins fail for that minute); do it in a quiet
window. (Spring Authorization Server holds one secret per client, so there is no overlap period.)

**Adding a client later** — another app or BFF (partners have their own block, `northline.oauth.partners`, see
[partners.md](partners.md)): add a block to
`application.yml` (every environment) or to an environment-only file mounted with
`SPRING_CONFIG_ADDITIONAL_LOCATION=/config/oauth-clients.yml`, then run the Job:

```yaml
northline.oauth.clients:
  kiosk-app:                     # another public app: PKCE, DPoP required to refresh (S-29)
    type: public
    name: Northline Kiosk
    redirect-uris: [ "ca.northline.kiosk:/oauth2redirect" ]   # or a claimed https App Link / Universal Link
    scopes: [ openid, orders ]
    refresh-token-ttl: 30d
    dpop-required: true          # every token request needs a DPoP proof; tokens are bound to the app's key
```

Keys: `type` (`confidential` | `public`), `optional`, `name`, `secret-hash`, `redirect-uris`,
`post-logout-redirect-uris`, `scopes`, `grant-types`, `require-pkce`, `require-consent`, `access-token-ttl`,
`refresh-token-ttl`, `dpop-required` (enforced since S-29; a public client that refreshes must set it). Retiring a client: remove it from configuration (it is then only reported), and
delete it by hand (`delete from auth.oauth2_registered_client where client_id = '…'`) once nothing uses it.

## SMS and voice codes (S-8)

northline-auth sends the registration phone code (6 digits, 10 min) by SMS, or by a voice call when the person picks
"Call me instead". The adapter is chosen by `SMS_PROVIDER`:

| `SMS_PROVIDER` | what happens | needs |
|---|---|---|
| `local` (default) | the code is **written to the auth log** (`grep "Verification code"`) under the `local` profile only, nothing is sent. Refused under `staging`/`prod`; under `dev` the code is **withheld** (S-112: no code ever reaches shipped logs — [logging.md](logging.md#the-local-sms-stand-in-s-20)), so dev needs `twilio` or `aws` to finish a phone verification | — |
| `twilio` (recommended) | SMS via Programmable Messaging (`POST /2010-04-01/Accounts/{sid}/Messages.json`), voice via a call that reads the code twice (`Calls.json` with inline TwiML, Amazon Polly voices Joanna / Chantal) | `SMS_ACCOUNT_ID`, `SMS_AUTH_TOKEN`, `SMS_FROM`; `SMS_VOICE_FROM` when `SMS_FROM` is a Messaging Service |
| `aws` | AWS End User Messaging SMS and voice (`pinpoint-sms-voice-v2`: `SendTextMessage` transactional, `SendVoiceMessage` Polly) — keeps an all-AWS deployment on one bill and IAM | `SMS_FROM` (phone number id/ARN or pool), `SMS_REGION=ca-central-1`; credentials from workload identity (IRSA / Pod Identity) with `sms-voice:SendTextMessage` + `sms-voice:SendVoiceMessage` |
| `azure` | reserved (Azure Communication Services SMS + Call Automation) — start-up fails with "not implemented yet" | — |

Google Cloud has no first-party SMS service; on GKE use `twilio`. A provider with missing settings stops start-up
naming every missing variable; `staging`/`prod` also list `SMS_PROVIDER` and `SMS_FROM` among the variables checked
before start-up (purpose `sms-provider`).

**Language:** the Studio sends its UI language (`Accept-Language: fr-CA` / `en-CA`); French gets
"Northline : votre code de vérification est 123456. Il expire dans 10 minutes. Ne le partagez jamais.", everyone else
the English text. Each SMS is one GSM-7 segment (≤ 160 characters).

**Failures** (no automatic retry — a retried request can deliver twice, and the person can resend):

| provider answer | the person sees |
|---|---|
| the number can't get it (Twilio 21211, 21214, 21217, 21401, 21610 STOP, 21612, 21614 not a mobile, 13223/13224; AWS invalid destination, opted out) on the form | the mobile field error "Enter a valid Canadian mobile…" (existing message) |
| anything else on the form (credentials, sender, geo permissions 21408, throttling, 5xx, time-out) | `503 code_not_sent` → "We couldn't send a code to this number right now. Try again in a moment." |
| a resend by text / a call fails | `503 code_not_sent` → "…or choose Call me instead." / "…or resend the code by text." — the code already sent keeps working |

Every failure is logged at WARN with the masked number (`+1 403 *** **48`) and the provider's code; the rate limits
(S-9) count every attempt.

### Twilio account setup (once per environment)

1. Create a Twilio account (one per environment, or subaccounts of one: `dev`, `staging`, `prod`), upgrade it from
   trial (a trial sends only to verified numbers and prefixes the text), enable two-factor sign-in for the console.
2. **Geo permissions:** Messaging → Settings → Geo permissions: allow **Canada** only (and the US if needed);
   Voice → Settings → Geo permissions: the same, low-risk numbers only. This is the main toll-fraud control.
3. **Sender:** buy a Canadian long code (e.g. +1 587 / +1 403, SMS + voice capable) or a toll-free number (+1 833…,
   needs toll-free verification before it can send, ~1–3 weeks). Canadian carriers filter unregistered A2P traffic:
   for volume, a **Messaging Service** with the number in its sender pool is recommended (`SMS_FROM=MG…`), and then
   `SMS_VOICE_FROM=+1…` must be a voice-capable number (a Messaging Service can't place calls).
4. **Credentials:** the account (or subaccount) SID `AC…` is `SMS_ACCOUNT_ID`, its auth token `SMS_AUTH_TOKEN`
   (the adapter authenticates with these two; Twilio API keys `SK…` are not supported yet). Keep the token in the
   secrets manager (External Secrets, S-6). Rotate it from the console (create the secondary token, deploy it,
   promote it), then restart auth.
5. Optional: enable Twilio's SMS Pumping Protection (Messaging Service → Fraud Guard) and set a usage trigger
   (Console → Usage → Triggers) as a spending alarm.
6. Check: sign up in the Studio with your own mobile; the auth log shows `Twilio SMS to +1 587 *** **01 accepted: SM…`.

```sh
# staging / prod (auth only)
SMS_PROVIDER=twilio
SMS_ACCOUNT_ID=AC…              # secrets manager
SMS_AUTH_TOKEN=…                # secrets manager
SMS_FROM=+15875550100           # or MG… (Messaging Service)
SMS_VOICE_FROM=                 # required when SMS_FROM is MG…
```

**AWS instead:** in End User Messaging SMS (`ca-central-1`) request a Canadian long code or toll-free number with SMS
and voice, move the account out of the SMS sandbox, set a monthly spend limit, then `SMS_PROVIDER=aws`,
`SMS_REGION=ca-central-1`, `SMS_FROM=<phone-number-id or ARN>`, and grant the auth service account the two
`sms-voice:Send*` actions on that number.

## Rate limits (S-9)

northline-auth limits sign-in and registration attempts per **account** (what was typed: a known account by its id,
an unknown email/mobile by its normalised value — both behave the same, so a 429 never reveals whether an account
exists), per **client IP** and per **auth session**. Over a limit the answer is `429` with `Retry-After`,
`{"code":"rate_limited","retryAfterSeconds":…}`; the Studio shows "Too many attempts. Try again in m:ss." (en/fr) and
disables the button until then. Every lockout is written to `developer.audit_log` (`auth.rate_limited`: action,
scopes, seconds, IP; actor = the account when known). These limits come on top of the per-flow rules (5 wrong tries
per phone code, 45 s resend cool-down, 5 failed factors per sign-in attempt or step-up session).

| action | account | IP | session | first lockout (doubles each time, max 24 h) |
|---|---|---|---|---|
| phone code sent / re-sent / voice call (`otp-send`, every "Send code" counts) | 5 / h | 20 / h | 5 / h | 1 h |
| wrong phone code (`otp-verify`) | 10 / h | 50 / h | 10 / h | 15 min |
| email or mobile looked up at sign-in (`sign-in-lookup`) | — (typing someone's email must not lock them out) | 30 / 10 min | 20 / 10 min | 10 min |
| wrong authenticator code / backup code / failed passkey (`totp-verify`, `backup-code-verify`, `passkey-assertion`) | 10 / 15 min each | 30 / 15 min | 10 / 15 min | 15 min |
| failed step-up for payouts (`step-up`) | 10 / 15 min | 30 / 15 min | 10 / 15 min | 15 min |
| revoke a session / sign out others / remove a passkey (`security-change`, S-19, every call counts) | 20 / h | 60 / h | 20 / h | 15 min |
| partner access token issued (`partner-token`, S-30; account = the partner) | 60 / h | 600 / h | — | 15 min |

- Sliding windows; a success resets the account and session counters of that action (never the IP's). Lockouts of
  the same subject double while earlier ones are remembered (24 h). All numbers are properties under
  `northline.auth.rate-limits.limits.<action>.<account|ip|session>` (`max`, `window`, `lockout`, `max-lockout`) in
  `server/auth/src/main/resources/application.yml`; override one with e.g.
  `NORTHLINE_AUTH_RATELIMITS_LIMITS_OTPSEND_IP_MAX=40`.
- **Store:** Valkey/Redis (`REDIS_*`, keys `nl:auth-rl:{<action>}:<scope>:<hash>:…`, no email or IP in clear, TTLs
  on everything), shared by every replica. `local` keeps them in memory and logs
  `Rate limits (S-9) are kept IN MEMORY …`; `--spring.profiles.active=local,valkey` uses your Valkey. `memory` is
  refused under `staging`/`prod`.
- **Valkey unreachable (S-20):** `RATE_LIMIT_WHEN_UNAVAILABLE` decides for the guessable steps — sending and checking
  phone codes, authenticator and backup codes, passkey assertions, step-up. `closed` (the **staging/prod default**)
  answers `503` `{"code":"sign_in_unavailable","retryAfterSeconds":30}` with `Retry-After: 30`; the Studio shows
  "Signing in is paused for a few minutes while we fix a problem on our side. Try again shortly." (en/fr). `open` (the
  default everywhere else) lets the attempt through and logs `Rate limits unavailable: <action> allowed (fail open)`;
  the per-flow rules still apply. Typing the email/mobile and the signed-in Security changes always stay open (no
  secret is checked there; the latter already need a recent second factor). Setting `open` under staging/prod is a
  break-glass for a long Valkey outage — the auth log warns at start-up; remove it once Valkey is back. Alert on
  `Rate limits unavailable` in the auth log (ERROR) — it means sign-in is paused (closed) or unguarded (open).
- **Client IP:** `X-Forwarded-For` (and `-Proto`, `-Host`) are believed only from `TRUSTED_PROXIES` (CIDRs, default
  loopback + private ranges); the client is the right-most address that isn't a trusted proxy. Set it to the ingress
  / load-balancer subnet in every cloud environment, or anyone inside the private network can pick their own IP.
  Behind Envoy Gateway the proxies' addresses are pod addresses, so staging/prod also narrow the NetworkPolicy of
  the public apps to the `envoy-gateway-system` namespace (S-20, `networkPolicy.ingressFrom` in
  `values-staging.yaml` / `values-prod.yaml`) — nothing else in the cluster can reach auth or the bff with forged
  headers.
- **Unlocking someone** before the lockout ends: delete their keys in Valkey
  (`valkey-cli --scan --pattern 'nl:auth-rl:*' | xargs valkey-cli del` clears everything — keys are hashed, so a
  targeted unlock needs the hash; waiting is usually simpler).

## Consumer sign-in (S-62)

The consumer site (`/sign-in`, `/register`, design 06) uses northline-auth's JSON API like the Studio, plus:

| endpoint | what |
|---|---|
| `POST /api/auth/sign-in` `{identifier}` | as the Studio (email or mobile; unknown accounts continue identically) |
| `POST /api/auth/sign-in/code` `{channel?: sms\|voice}` | a 6-digit code to the account's mobile → `{resendAfterSeconds, channel}`; resend after 45 s, voice once per SMS code; an unknown account gets the same answer and nothing is sent |
| `POST /api/auth/sign-in/code/verify` `{code}` | signed in with the phone only (`identity.sessions.method = phone_otp`, no `acr`) |
| `POST /api/auth/register/complete` | after the phone code: the account without a second factor ("SMS code · Backup only"; `mfa_primary = sms`) |

Limits: the registration's `otp-send` / `otp-verify` (above), 5 tries per code, 10 min per code.

**Business apps still need a second factor.** A session without one (phone code only) gets no code for the clients in
`northline.auth.mfa-required-clients` (default `studio-bff`, `console-bff`): the browser is sent to that app's
sign-in page, where signing in with a passkey / authenticator / backup code replaces the session. The api refuses
merchant and staff endpoints without `acr=mfa` anyway. Unauthenticated authorization requests of the clients in
`northline.auth.consumer-clients` (default `consumer-bff`, `courier-app` — S-87: couriers are people, not businesses) go to `northline.auth.consumer-login-page`
(`${CONSUMER_ORIGIN}/sign-in`); every other client to `login-page` (the Studio's). No new variables: both pages come
from `STUDIO_ORIGIN` / `CONSUMER_ORIGIN`.

## Cookies and CSRF (S-20)

| cookie | app | local | dev / staging / prod | attributes |
|---|---|---|---|---|
| auth server session | auth | `NL_AUTH` | `__Host-NL_AUTH` | HttpOnly, SameSite=Lax, Secure (not under `local`), host-only (`auth.<zone>`), 12 h idle |
| BFF session | bff | `NL_STUDIO` | `__Host-NL_STUDIO` | HttpOnly, SameSite=Lax, Secure, host-only (`studio.<zone>`), 12 h idle |
| consumer BFF session (S-45) | bff (`consumer`) | `NL_CONSUMER` | `__Host-NL_CONSUMER` | the same, host-only (the apex; `pages.` gets its own) |
| console BFF session (S-90) | bff (`console`) | `NL_CONSOLE` | `__Host-NL_CONSOLE` | the same, host-only (`console.<zone>`) |
| language (S-45) | consumer web | `nl.locale` | `nl.locale` | `en`/`fr`, readable, SameSite=Lax, 1 year — no personal data |
| CSRF token | bff | `XSRF-TOKEN` | `__Host-XSRF-TOKEN` | readable by the Studio, SameSite=Strict, Secure in the cloud, path `/` |

- `__Host-` cookies are Secure, path `/` and carry no Domain, so a sibling subdomain (the consumer apex, `pages.`
  storefronts) can neither plant nor overwrite them. `COOKIE_DOMAIN` was removed: nothing needs a shared cookie (the
  Studio calls auth cross-origin, same site, with credentials).
- CSRF: the BFF accepts the token only in `X-XSRF-TOKEN` (never a `_csrf` form field); the auth JSON API accepts
  state-changing calls only from `STUDIO_ORIGIN` / `CONSUMER_ORIGIN` (CORS + an Origin check; a request without
  `Origin` that Fetch Metadata marks `cross-site` is refused).
- Signing out of the Studio revokes the BFF's refresh token, which ends the whole sign-in at northline-auth too — the
  auth server's session is dropped on its next request even if the browser's own `POST /api/auth/sign-out` was lost.
- Changing the cookie names signs everyone out once (the old cookies are simply ignored).

## Sessions (S-19)

A **session** is one successful sign-in (`identity.sessions` row). The auth server's HTTP session carries its id
(`SESSION_<id>` authority), every OAuth authorization issued from that HTTP session — the BFF's refresh token, an app's
— is linked to it (`auth.authorization_sessions`), and ID tokens carry it as `sid`. Studio Settings › Security lists
the open ones (device, city when the ingress provides it, approximate IP, signed in, last active, apps, *This
device*), and lets the person sign one out, sign out all others, or remove a passkey.

**How fast a revoked session ends** (every step is automatic):

| what | when |
|---|---|
| refresh tokens issued from it (BFF, apps) | at once — the authorizations are deleted in the revoking transaction; `/oauth2/token` answers `invalid_grant`, `/oauth2/introspect` `active:false` |
| an app's DPoP-bound access token (api) | stateless like the BFF's: until it expires, **≤ 10 min** — but it only works with the app's key, which never leaves the phone |
| the auth server's own HTTP session | on its next request (`RevokedSessionFilter`): no silent `/oauth2/authorize` code, JSON API 401 |
| the BFF session | at its next request after `SESSION_CHECK_INTERVAL` (default **60 s**): the BFF introspects its refresh token; a refused refresh also ends it |
| an access token already issued (JWT, api) | stateless: until it expires, **≤ 10 min** — it only ever lives inside the BFF, whose session is gone within a minute |

- **Step-up:** the three changes need a second factor used in this auth session within `SESSION_STEP_UP_MAX_AGE`
  (default `10m`); otherwise `403 step_up_required` and the Studio asks for the passkey or an authenticator code
  (`/api/auth/step-up/*`, which renews the session's factor time) and retries. Rate limit: `security-change` above.
- **Never the last factor:** a passkey can go only if another passkey or the authenticator app remains (backup codes
  don't count) → `409 last_factor`. Removing the last passkey makes the authenticator the primary factor.
- **Audit:** `developer.audit_log` `auth.session_revoked` (target the session, `reason` `revoked` | `revoked_others`)
  and `auth.passkey_removed` (target the credential id, label). Signing out ("Sign out", "Not you?") ends the session
  the same way (`revoke_reason = signed_out`).
- **"Current":** the auth server's session plus the BFF's `sid` the Studio passes (`?current=`). After a "Confirm it's
  you" sign-in the browser has two sessions; both show *This device* and survive "sign out all other sessions".
- **City:** set `CLIENT_CITY_HEADER` to the header your ingress / CDN fills with the client's city — CloudFront
  `CloudFront-Viewer-City`; Google Cloud external Application Load Balancer: a custom request header
  `X-Client-City: {client_city}`; Azure Front Door: a rules-engine header from the client's geo match. Read only from
  `TRUSTED_PROXIES`; empty = no city (the list shows the approximate IP only: `203.0.113.x`, IPv6 `/48`).
- **Operator actions:** end every session of a person (lost device, compromised account) with SQL in one transaction:
  `UPDATE identity.sessions SET revoked_at = now(), revoke_reason = 'revoked' WHERE user_id = :u AND revoked_at IS NULL;
  DELETE FROM auth.oauth2_authorization WHERE principal_name = :u;` — the same effect as the Studio's buttons.
- Sessions from before S-19 carry no session id: they aren't listed and end with their 12 h idle timeout.
- **Mobile apps (S-29):** signing in to an app is a sign-in of its own (the phone's browser), listed with the app's
  name ("Northline", "Northline Courier") for as long as its refresh token lives (30 d / 12 h); revoking it here stops
  the app's next refresh. The app signing out (`/oauth2/revoke`) ends it (`signed_out`). A rotated refresh token
  presented again ends it too (`revoke_reason = refresh_token_reused`, audit `auth.refresh_token_reused` +
  `auth.session_revoked`): the whole refresh-token family is revoked and the person signs in again on that phone.

## Choosing a cloud

The apps need only standard protocols (JDBC/PostgreSQL, the Redis protocol, the Kafka protocol with SASL/SSL, the
Elasticsearch HTTP API, S3/GCS/Blob via the storage port), so the same build runs on AWS, Google Cloud or Azure. The
per-provider service mapping is in each cloud runbook ("Managed services"); the Terraform for each cloud is
`infra/terraform/envs/<aws|gcp|azure>/<env>` ([infrastructure.md](infrastructure.md)). Canadian regions only:
AWS `ca-central-1` (Montréal) / `ca-west-1` (Calgary), Google Cloud `northamerica-northeast1` (Montréal) /
`northamerica-northeast2` (Toronto), Azure `canadacentral` (Toronto) / `canadaeast` (Québec City).
