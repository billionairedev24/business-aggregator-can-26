# Runbooks

One runbook per environment. Each lists what the environment needs — services, variables, secrets, third-party
accounts — and how to run, deploy and roll back **with what exists today**. Container images, Helm charts,
Terraform and GitOps arrive with later stories (S-2, S-3, S-6, S-14, S-15, S-16, S-17); the runbooks say where a step
is still manual or missing.

| runbook | for |
|---|---|
| [local.md](local.md) | your machine: clone → signed-in Studio, with your own Postgres/Valkey or Docker stand-ins |
| [dev.md](dev.md) | the shared cloud development environment |
| [staging.md](staging.md) | pre-production: prod shape, Stripe test mode |
| [prod.md](prod.md) | production (Calgary launch) |
| `ci.md` | CI pipelines (owned by the CI stories S-4/S-5) |

## Environment matrix

| | local | dev | staging | prod |
|---|---|---|---|---|
| Spring profile (`SPRING_PROFILES_ACTIVE`) | `local` (optionally `local,valkey`) | `dev` (+ `cloud`, automatic) | `staging` (+ `cloud`) | `prod` (+ `cloud`) |
| Where | your machine | Kubernetes in a Canadian region of AWS, Google Cloud or Azure | same | same |
| Postgres 17 + PostGIS | yours, or `docker compose --profile db` | managed | managed, HA | managed, HA + PITR |
| Valkey / Redis | not needed (`valkey` add-on: yours, or `--profile cache`) | managed | managed | managed, replicated |
| Kafka | off (`--profile events` for the worker) | managed | managed | managed, RF 3 |
| Elasticsearch | off (`--profile search` for the worker) | Elastic Cloud / ECK | same | same |
| Object storage | local folder (`--profile storage` ready for S-10) | *no adapter yet (S-10)* | same | same |
| Sign-in | dev auth (`X-Dev-User`) or northline-auth with seeded personas | northline-auth | northline-auth | northline-auth |
| Dev seed (`db/seed-dev`) | yes | **no** | **no** | **no** |
| Stripe | fake gateway, or stripe-mock (`--profile payments`) | fake, or test keys | **test keys required** | **live keys required** |
| SMS / email | logged | *no provider yet (S-8, S-13)* | same | same |
| Required variables checked at start-up | none | yes | yes (+ Stripe) | yes (+ Stripe) |
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
  `application-staging.yml` / `application-prod.yml` add `payments: STRIPE_SECRET_KEY, STRIPE_PUBLISHABLE_KEY`).
  The check is `ca.northline.platform.RequiredEnvironmentCheck` (module `server/platform`).
- **Providers are chosen by configuration** so that moving between AWS, Google Cloud and Azure is a variable change.
  The property names exist now (`ca.northline.platform.*Properties`, default `local`); only the `local` adapters
  exist, and the cloud adapters come with the stories below.

  | property | variable | values | adapters arrive in |
  |---|---|---|---|
  | `northline.storage.provider` | `STORAGE_PROVIDER` | `local` · `s3` (AWS S3, MinIO/RustFS, any S3 API) · `gcs` · `azure` | S-10 |
  | `northline.kms.provider` | `KMS_PROVIDER` | `local` · `aws` · `gcp` · `azure` | S-7 (signing keys) |
  | `northline.email.provider` | `EMAIL_PROVIDER` | `local` · `smtp` · `ses` · `sendgrid` · `azure` | S-13 |
  | `northline.sms.provider` | `SMS_PROVIDER` | `local` · `twilio` · `sns` · `azure` | S-8 |

  Secrets reach the apps as environment variables in every cloud (External Secrets from AWS Secrets Manager, Google
  Secret Manager or Azure Key Vault — S-6), so there is no `secrets.provider` switch. Each app logs its choice at
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
| `KAFKA_BOOTSTRAP` | ✓ | | | ✓ | yes |
| `KAFKA_SECURITY_PROTOCOL`, `KAFKA_SASL_MECHANISM`, `KAFKA_SASL_JAAS_CONFIG` | ✓ | | | ✓ | no (PLAINTEXT) — set for managed Kafka |
| `ES_URIS` | ✓ | | | ✓ | yes |
| `ES_USERNAME`, `ES_PASSWORD` | ✓ | | | ✓ | no — set for Elastic Cloud |
| `AUTH_ISSUER` | ✓ | ✓ | ✓ | | yes |
| `AUTH_INTERNAL_URL` | | | ✓ | | no (= `AUTH_ISSUER`) |
| `API_URL` | | | ✓ | | yes |
| `STUDIO_ORIGIN` | ✓ | ✓ | | | yes |
| `CONSUMER_ORIGIN`, `CONSOLE_ORIGIN`, `WEBAUTHN_RP_ID` | | ✓ | | | yes |
| `COOKIE_DOMAIN` | | ✓ | ✓ | | no (host-only cookies) |
| `TOTP_KEY` | | ✓ | | | yes |
| `STUDIO_BFF_SECRET` | | | ✓ | | yes |
| `STUDIO_BFF_SECRET_HASH`, `CONSUMER_BFF_SECRET_HASH`, `CONSOLE_BFF_SECRET_HASH` | | ✓ | | | yes |
| `GOOGLE_CLIENT_ID`/`_SECRET`, `APPLE_CLIENT_ID`/`_SECRET` | | ✓ | | | no (placeholders until S-18) |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | ✓ | | | | staging and prod |
| `STRIPE_API_BASE` | ✓ | | | | never in the cloud (stripe-mock only) |
| `WEBHOOK_SECRET_KEY` | ✓ | | | | yes |
| `STORAGE_*` (`PROVIDER`, `BUCKET`, `REGION`, `ENDPOINT`, `ACCESS_KEY`, `SECRET_KEY`, `PATH_STYLE`) | ✓ | | | | no (until S-10) |
| `KMS_PROVIDER`, `KMS_KEY_ID` | ✓ | ✓ | | | no (until S-7) |
| `EMAIL_PROVIDER`, `EMAIL_FROM`, `SMTP_*` | ✓ | | | ✓ | no (until S-13) |
| `SMS_PROVIDER`, `SMS_FROM`, `SMS_ACCOUNT_ID`, `SMS_AUTH_TOKEN` | ✓ | ✓ | | ✓ | no (until S-8) |
| `OTEL_EXPORT_ENABLED` | ✓ | | | | no (false until S-111) |
| `SERVER_PORT` | ✓ | ✓ | ✓ | | no (8080 / 9000 / 8082) |

Studio build (web): `VITE_NL_AUTH_ORIGIN` (= `AUTH_ISSUER`) is baked into the bundle at build time, so each
environment needs its own Studio build until runtime configuration exists.

## Choosing a cloud

The apps need only standard protocols (JDBC/PostgreSQL, the Redis protocol, the Kafka protocol with SASL/SSL, the
Elasticsearch HTTP API, S3/GCS/Blob via the storage port), so the same build runs on AWS, Google Cloud or Azure. The
per-provider service mapping is in each cloud runbook ("Managed services"). Canadian regions only:
AWS `ca-central-1` (Montréal) / `ca-west-1` (Calgary), Google Cloud `northamerica-northeast1` (Montréal) /
`northamerica-northeast2` (Toronto), Azure `canadacentral` (Toronto) / `canadaeast` (Québec City).
