# Runbooks

One runbook per environment. Each lists what the environment needs — services, variables, secrets, third-party
accounts — and how to run, deploy and roll back **with what exists today**. Terraform for the cloud foundation and the managed data
stores exists (S-2, S-3; not applied yet); External Secrets, container images, Helm charts and GitOps arrive with later
stories (S-6, S-14, S-15, S-16, S-17); the runbooks say where a step is still manual or missing.

| runbook | for |
|---|---|
| [local.md](local.md) | your machine: clone → signed-in Studio, with your own Postgres/Valkey or Docker stand-ins |
| [dev.md](dev.md) | the shared cloud development environment |
| [staging.md](staging.md) | pre-production: prod shape, Stripe test mode |
| [prod.md](prod.md) | production (Calgary launch) |
| [infrastructure.md](infrastructure.md) | Terraform on AWS / Google Cloud / Azure: accounts, state bucket, plan/apply, outputs → variables, cost, teardown (S-2/S-3) |
| [object-storage.md](object-storage.md) | uploads in S3 / RustFS, Cloud Storage or Azure Blob: variables, buckets, least-privilege access per cloud (S-10) |
| [email.md](email.md) | transactional email: Mailpit locally, SES / SendGrid / Azure Communication Services / SMTP set-up, SPF/DKIM/DMARC, CASL (S-13) |
| [ci.md](ci.md) | CI pipelines on GitHub Actions and GitLab CI, manual trigger only (S-4/S-5, infra checks S-2/S-3) |

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
| Dev seed (`db/seed-dev`) | yes | **no** | **no** | **no** |
| Stripe | fake gateway, or stripe-mock (`--profile payments`) | fake, or test keys | **test keys required** | **live keys required** |
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
  `application-staging.yml` / `application-prod.yml` add `payments: STRIPE_SECRET_KEY, STRIPE_PUBLISHABLE_KEY`).
  The check is `ca.northline.platform.RequiredEnvironmentCheck` (module `server/platform`).
- **Providers are chosen by configuration** so that moving between AWS, Google Cloud and Azure is a variable change.
  The property names live in `ca.northline.platform.*Properties` (default `local`); the cloud adapters come with the
  stories below.

  | property | variable | values | adapters arrive in |
  |---|---|---|---|
  | `northline.storage.provider` | `STORAGE_PROVIDER` | `local` · `s3` (AWS S3, MinIO/RustFS, any S3 API) · `gcs` · `azure` | **done** (S-10, api uploads — [object-storage.md](object-storage.md)) |
  | `northline.kms.provider` | `KMS_PROVIDER` | `local` · `aws` · `gcp` · `azure` | **done** (S-7, auth token signing keys — [key-rotation.md](key-rotation.md)) |
  | `northline.email.provider` | `EMAIL_PROVIDER` | `local` (SMTP to Mailpit) · `smtp` · `ses` · `sendgrid` · `azure` | **done** (S-13, api invitations and money notices — [email.md](email.md)) |
  | `northline.sms.provider` | `SMS_PROVIDER` | `local` · `twilio` · `aws` (End User Messaging SMS and voice) · `azure` (reserved) | **done** (S-8, auth phone codes — [SMS and voice codes](#sms-and-voice-codes-s-8)) |

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
| `STUDIO_BFF_SECRET_HASH` | | ✓ | | | yes |
| `CONSUMER_BFF_SECRET_HASH`, `CONSOLE_BFF_SECRET_HASH` | | ✓ | | | no — the client is registered only once its hash is set ([OAuth clients](#oauth-clients-s-122)) |
| `OAUTH_CLIENTS_SYNC_ON_STARTUP` | | ✓ | | | no (`true`; `false` = register only with the Job) |
| `GOOGLE_CLIENT_ID`/`_SECRET`, `APPLE_CLIENT_ID`/`_SECRET` | | ✓ | | | no (placeholders until S-18) |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | ✓ | | | | staging and prod |
| `STRIPE_API_BASE` | ✓ | | | | never in the cloud (stripe-mock only) |
| `WEBHOOK_SECRET_KEY` | ✓ | | | | yes |
| `STORAGE_PROVIDER`, `STORAGE_BUCKET` | ✓ | | | | staging and prod (`local` refused there — S-10, [object-storage.md](object-storage.md)) |
| `STORAGE_REGION`, `STORAGE_ENDPOINT`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PATH_STYLE`, `STORAGE_ENCRYPTION_KEY` | ✓ | | | | no (`STORAGE_ENDPOINT` needed for `azure`) |
| `KMS_PROVIDER`, `KMS_KEY_ID` | | ✓ | | | `KMS_PROVIDER` everywhere, `KMS_KEY_ID` in staging and prod (S-7, [key-rotation.md](key-rotation.md)) |
| `KMS_PUBLISHED_KEY_IDS`, `KMS_REGION`, `KMS_ENDPOINT`, `SIGNING_KEYS_DIR`, `SIGNING_KEYS_ROTATE_EVERY` | | ✓ | | | no |
| `EMAIL_PROVIDER`, `EMAIL_FROM` | ✓ | | | ✓ | staging and prod (`local` refused there — S-13, [email.md](email.md)) |
| `EMAIL_UNSUBSCRIBE_KEY`, `API_PUBLIC_URL` | ✓ | | | | staging and prod (unsubscribe links — [email.md](email.md#variables)) |
| `EMAIL_REPLY_TO`, `EMAIL_MAILING_ADDRESS`, `EMAIL_CONTACT`, `EMAIL_REGION`, `EMAIL_ENDPOINT`, `EMAIL_API_KEY`, `EMAIL_CONFIGURATION_SET`, `EMAIL_RETRY_*`, `SMTP_*` | ✓ | | | ✓ | per provider: `EMAIL_API_KEY` with `sendgrid`, `EMAIL_ENDPOINT` with `azure`, `SMTP_HOST` with `smtp` ([email.md](email.md#variables)) |
| `SMS_PROVIDER`, `SMS_FROM` | | ✓ | | | staging and prod (`local` refused there; `dev` may keep `local`) |
| `SMS_ACCOUNT_ID`, `SMS_AUTH_TOKEN` | | ✓ | | | with `SMS_PROVIDER=twilio` |
| `SMS_VOICE_FROM`, `SMS_REGION`, `SMS_ENDPOINT` | | ✓ | | | no (`= SMS_FROM`; SDK default region; provider API) |
| `TRUSTED_PROXIES` | | ✓ | | | no (private ranges + loopback; narrow it to the ingress subnet) |
| `RATE_LIMIT_STORE` | | ✓ | | | no (`redis`; `memory` only under `local`/`test`) |
| `OTEL_EXPORT_ENABLED` | ✓ | | | | no (false until S-111) |
| `SERVER_PORT` | ✓ | ✓ | ✓ | | no (8080 / 9000 / 8082) |

Studio build (web): `VITE_NL_AUTH_ORIGIN` (= `AUTH_ISSUER`) is baked into the bundle at build time, so each
environment needs its own Studio build until runtime configuration exists.

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
| `consumer-bff` | confidential | `CONSUMER_BFF_SECRET_HASH` set (`optional: true`) | `${CONSUMER_ORIGIN}/login/oauth2/code/northline` | openid profile orders bookings |
| `console-bff` | confidential | `CONSOLE_BFF_SECRET_HASH` set (`optional: true`) | `${CONSOLE_ORIGIN}/login/oauth2/code/console` | openid profile console |
| `mobile-consumer`, `courier-app` | public (PKCE, no secret) | `local` only today (S-28, S-87) | `ca.northline.app:/oauth2redirect`, `ca.northline.courier:/oauth2redirect` | openid profile orders/deliveries offline_access |

Defaults for anything not set: grant types `authorization_code` + `refresh_token`, PKCE required, no consent screen,
access token 10 min, rotating refresh token 12 h (= the session idle limit), ES256 ID tokens.

**Rules, checked before anything is written** (the app or the Job stops with every problem listed):

| | `local`, `test` | `dev` | `staging`, `prod` |
|---|---|---|---|
| redirect / post-logout URIs | any absolute URI | `https`, or `http` on `localhost`/`127.0.0.1`/`[::1]` (the local rehearsal) | `https` only |
| confidential secret | an encoded value: `{bcrypt}…`, `{noop}…` | same; `{noop}` logs a warning | encoded and hashed — `{noop}` refused |

Everywhere: no wildcards or fragments; a public client may also use a reverse-domain private-use scheme
(`ca.northline.app:/…`, RFC 8252) and has no secret; a confidential client needs `secret-hash` (a plain secret is
refused; use bcrypt cost ≥ 10, e.g. `htpasswd -bnBC 12`); clients may not share a secret; `authorization_code` needs a redirect URI and PKCE; staging/prod need at
least one client.

**Rotating a BFF secret** (no code change): generate a new secret, put its bcrypt hash in
`STUDIO_BFF_SECRET_HASH` and the plain value in the bff's `STUDIO_BFF_SECRET`, run the Job (or restart auth), then
restart the bff. Between the two the bff's old secret is refused (sign-ins fail for that minute); do it in a quiet
window. (Spring Authorization Server holds one secret per client, so there is no overlap period.)

**Adding a client later** — mobile apps (S-28 consumer app, S-87 courier app) or partners (S-29): add a block to
`application.yml` (every environment) or to an environment-only file mounted with
`SPRING_CONFIG_ADDITIONAL_LOCATION=/config/oauth-clients.yml`, then run the Job:

```yaml
northline.oauth.clients:
  mobile-consumer:
    type: public
    redirect-uris: [ "ca.northline.app:/oauth2redirect" ]   # or a claimed https App Link / Universal Link
    scopes: [ openid, profile, orders, offline_access ]
    refresh-token-ttl: 30d
    dpop-required: true          # recorded in the client settings; enforcement arrives with the mobile stories
  partner-acme:                  # S-29: client credentials; private_key_jwt (jwk-set-url) is still to be added
    type: confidential
    secret-hash: ${PARTNER_ACME_SECRET_HASH}
    grant-types: [ client_credentials ]
    scopes: [ partner.orders.read ]
```

Keys: `type` (`confidential` | `public`), `optional`, `name`, `secret-hash`, `redirect-uris`,
`post-logout-redirect-uris`, `scopes`, `grant-types`, `require-pkce`, `require-consent`, `access-token-ttl`,
`refresh-token-ttl`, `dpop-required`. Retiring a client: remove it from configuration (it is then only reported), and
delete it by hand (`delete from auth.oauth2_registered_client where client_id = '…'`) once nothing uses it.

## SMS and voice codes (S-8)

northline-auth sends the registration phone code (6 digits, 10 min) by SMS, or by a voice call when the person picks
"Call me instead". The adapter is chosen by `SMS_PROVIDER`:

| `SMS_PROVIDER` | what happens | needs |
|---|---|---|
| `local` (default) | the code is **written to the auth log** (`grep "Verification code"`), nothing is sent. Refused under `staging`/`prod`; `dev` logs a warning | — |
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

- Sliding windows; a success resets the account and session counters of that action (never the IP's). Lockouts of
  the same subject double while earlier ones are remembered (24 h). All numbers are properties under
  `northline.auth.rate-limits.limits.<action>.<account|ip|session>` (`max`, `window`, `lockout`, `max-lockout`) in
  `server/auth/src/main/resources/application.yml`; override one with e.g.
  `NORTHLINE_AUTH_RATELIMITS_LIMITS_OTPSEND_IP_MAX=40`.
- **Store:** Valkey/Redis (`REDIS_*`, keys `nl:auth-rl:{<action>}:<scope>:<hash>:…`, no email or IP in clear, TTLs
  on everything), shared by every replica. `local` keeps them in memory and logs
  `Rate limits (S-9) are kept IN MEMORY …`; `--spring.profiles.active=local,valkey` uses your Valkey. `memory` is
  refused under `staging`/`prod`. If Valkey is unreachable the attempt is allowed and an error is logged (the
  per-flow rules still apply).
- **Client IP:** `X-Forwarded-For` (and `-Proto`, `-Host`) are believed only from `TRUSTED_PROXIES` (CIDRs, default
  loopback + private ranges); the client is the right-most address that isn't a trusted proxy. Set it to the ingress
  / load-balancer subnet in every cloud environment, or anyone inside the private network can pick their own IP.
- **Unlocking someone** before the lockout ends: delete their keys in Valkey
  (`valkey-cli --scan --pattern 'nl:auth-rl:*' | xargs valkey-cli del` clears everything — keys are hashed, so a
  targeted unlock needs the hash; waiting is usually simpler).

## Choosing a cloud

The apps need only standard protocols (JDBC/PostgreSQL, the Redis protocol, the Kafka protocol with SASL/SSL, the
Elasticsearch HTTP API, S3/GCS/Blob via the storage port), so the same build runs on AWS, Google Cloud or Azure. The
per-provider service mapping is in each cloud runbook ("Managed services"); the Terraform for each cloud is
`infra/terraform/envs/<aws|gcp|azure>/<env>` ([infrastructure.md](infrastructure.md)). Canadian regions only:
AWS `ca-central-1` (Montréal) / `ca-west-1` (Calgary), Google Cloud `northamerica-northeast1` (Montréal) /
`northamerica-northeast2` (Toronto), Azure `canadacentral` (Toronto) / `canadaeast` (Québec City).
