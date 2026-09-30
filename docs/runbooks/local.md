# Local runbook — from clone to a signed-in Studio

Everything runs on your machine. You can use **your own Postgres and Valkey** or start **Docker stand-ins** for
anything you don't have, one service at a time (compose profiles). The Spring profile `local` needs only Postgres.

## 1. Prerequisites

| what | version | notes |
|---|---|---|
| git | any | |
| JDK | **25** | `export JAVA_HOME=/path/to/jdk-25`. Gradle 9.8 comes with the wrapper (`server/gradlew`). |
| Node.js + pnpm | Node 22+, pnpm 10.17 | `corepack enable` picks the pnpm version from `web/package.json`. |
| PostgreSQL | **17** with **PostGIS 3.5** | yours, or Docker (`--profile db`). Extensions used: `postgis`, `citext`, `pgcrypto`. |
| Valkey or Redis | Valkey 8 / Redis 7+ | optional: only for `local,valkey` (sessions in Valkey) and the worker. Yours, or Docker (`--profile cache`). |
| Docker + Compose v2.20+ | | only for the stand-ins you don't run yourself (Kafka, Elasticsearch, Mailpit, S3 storage, stripe-mock). |
| An authenticator app or `oathtool` | | to sign in as a seeded persona with real auth (step 5). |

Ports used by default: api **8080**, northline-auth **9000**, studio-bff **8082**, Studio **3100**, Postgres 5432,
Valkey 6379, Kafka 9092, Elasticsearch 9200, Mailpit 1025/8025, S3 storage 9100/9101, stripe-mock 12111.

## 2. Configure

```sh
git clone https://github.com/billionairedev24/business-aggregator-can-26.git northline && cd northline
cp .env.example .env                                   # docker compose: which stand-ins, ports
cp server/.env.example server/.env                     # api / auth / bff / worker settings
cp web/apps/studio/.env.example web/apps/studio/.env   # Studio dev server
```

- `server/.env` is read by the apps at start-up (not by tests) and by the Gradle DB tasks. Every value in the example
  is already the built-in default, so you only change what differs on your machine — typically `DB_URL`,
  `DB_USER`, `DB_PASSWORD`, and `REDIS_*` if your Valkey has a password or another port.
  Format: `KEY=value`, no quotes, no `export`. A real environment variable always wins over the file.
- All three `.env` files are git-ignored.

## 3. Postgres

**Your own Postgres 17 + PostGIS** — create a role and database once (as a superuser, because `postgis` is not a
trusted extension):

```sh
psql -U postgres -c "create role northline login password 'northline'"
psql -U postgres -c "create database northline owner northline"
psql -U postgres -d northline -c "create extension if not exists postgis; create extension if not exists citext; create extension if not exists pgcrypto"
```

Installing PostGIS: macOS `brew install postgresql@17 postgis`; Debian/Ubuntu (PGDG repo)
`apt install postgresql-17 postgresql-17-postgis-3`. Then set `DB_URL`/`DB_USER`/`DB_PASSWORD` in `server/.env`.

**Or Docker:** `docker compose --profile db up -d` (PostGIS 17-3.5 on `PG_PORT`, default 5432, user/password/db
`northline`). If 5432 is taken by your own Postgres, set `PG_PORT=5433` in `.env` and
`DB_URL=jdbc:postgresql://localhost:5433/northline` in `server/.env`.

**Migrate and seed** (from `server/`; the tasks read `DB_*` from `server/.env`, or take `-Pdb.url=… -Pdb.user=…
-Pdb.password=…`):

```sh
cd server
./gradlew :api:flywayMigrate -Pdb.devSeed=true   # db/migrations + db/seed-dev personas (optional: the api and auth also migrate on start under `local`)
# The dev seed is local-only (S-16): refused under dev/staging/prod profiles or for a non-local database host, and not
# packaged in the boot jars/images — `bootRun` and the tests see it; a jar started with `local` needs
# SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:../db/seed-dev (as ci/studio-smoke.sh does).
./gradlew :api:seedCategories                    # db/seed/categories.json — required once, nothing else loads categories
```

## 4. Studio with dev auth (fastest — api + Postgres only)

```sh
cd server && ./gradlew :api:bootRun --args='--spring.profiles.active=local'   # :8080, accepts X-Dev-User
curl -H 'X-Dev-User: 01J9ZD3V00000000000000RAV1' localhost:8080/api/v1/me/businesses
```

In `web/apps/studio/.env` set `NL_DEV_USER=01J9ZD3V00000000000000RAV1` (Ravi Sandhu, owner of the three seeded
businesses) and `VITE_NL_DEV_STEP_UP=1`, then:

```sh
cd web && pnpm install && pnpm dev          # http://localhost:3100 — already signed in as Ravi
```

Dev auth exists only under the `local` profile (`DevAuthFilter`); it logs a banner at start-up. Settings › Security
needs the auth server (step 5).

## 5. Studio with real sign-in (auth + api + bff, still Postgres only)

```sh
cd server
./gradlew :auth:bootRun --args='--spring.profiles.active=local'   # :9000 — migrates + seeds too; SMS codes are logged
./gradlew :api:bootRun  --args='--spring.profiles.active=local'   # :8080
./gradlew :bff:bootRun  --args='--spring.profiles.active=local'   # :8082 — in-memory sessions
```

Leave `NL_DEV_USER` empty in `web/apps/studio/.env` and run `cd web && pnpm dev`. Open http://localhost:3100 →
**Sign in** → `ravi.sandhu@example.com` → Authenticator app → the code from
`oathtool --totp -b NORTHLINERAVIDEVTOTPSECRET234567` (or backup code `ravis-00001` … `ravis-00010`, single use).
Other personas and factors: README § Local sign-in. "Create account" works end to end: the 6-digit phone code is
printed in the auth log (`Verification code for …`). Passkeys work on `localhost` (not `127.0.0.1`).

**OAuth clients:** auth registers `studio-bff`, `consumer-bff`, `console-bff`, `mobile-consumer` and `courier-app` in
its database at every start (local values in `application-local.yml`); `./gradlew :auth:oauthClients --args='list'`
compares configuration and database without starting the server ([README § OAuth clients](README.md#oauth-clients-s-122)).
The mobile apps (DPoP, S-29) sign in against a local auth too — redirect `http://localhost:3000/app/oauth2redirect` or
the custom scheme; their DPoP proof ids and nonces are kept in memory under `local` (`local,valkey`: in Valkey). Walk
through the flow with curl: [mobile-auth.md § Try it locally](mobile-auth.md#try-it-locally).

**Signing keys:** northline-auth keeps its ES256 key pair in `~/.northline/auth-signing-keys/signing-keys.jwks.json`
(created on first start; `SIGNING_KEYS_DIR` moves it), so restarting auth keeps you signed in and two auth instances
pointed at the same directory share tokens. Rotate or inspect it with `./gradlew :auth:signingKeys --args='status'`
([key-rotation.md](key-rotation.md)). Cloud KMS providers aren't needed locally (`KMS_PROVIDER=local`, the default).

**Rate limits (S-9):** under `local` they are kept in memory (the auth log says `Rate limits (S-9) are kept IN
MEMORY`), so a restart clears a lockout; with `local,valkey` they live in your Valkey like in the cloud. Limits and
how to clear them: [README § Rate limits](README.md#rate-limits-s-9). Every request from your browser comes from
`127.0.0.1`: 30 sign-in lookups in 10 minutes lock the IP for 10 minutes — restart auth (memory) to clear it.
With `local,valkey`, stopping Valkey lets sign-in through (`RATE_LIMIT_WHEN_UNAVAILABLE=open`, the local default);
set it to `closed` to see the staging/prod behaviour (codes and second factors answer 503, S-20).

**Sessions in your Valkey** (as in the cloud): start auth and bff with `--spring.profiles.active=local,valkey`.
Sessions are stored under `nl:auth:*` and `nl:studio-bff:*` and survive restarts; auth's rate limits under `nl:auth-rl:*`. Valkey from Docker:
`docker compose --profile cache up -d`.

## 5b. Consumer web (S-45)

`web/apps/consumer` is TanStack Start with server-side rendering on :3000 ([CONSUMER_WEB_PLAN.md](../CONSUMER_WEB_PLAN.md)).
Its BFF is the same bff jar with the `consumer` profile, on :8081:

```sh
cd server
./gradlew :auth:bootRun --args='--spring.profiles.active=local'            # :9000
./gradlew :api:bootRun  --args='--spring.profiles.active=local'            # :8080
./gradlew :bff:bootRun  --args='--spring.profiles.active=local,consumer'   # :8081 — consumer-bff (guests allowed)
cd ../web && pnpm dev:consumer                                             # http://localhost:3000
```

The dev server proxies `/api`, `/bff`, `/oauth2`, `/login` to the consumer-bff (`NL_BFF` overrides the target) and
renders pages on the server, fetching public data from `NL_BFF_URL` (default `http://localhost:8081`). Without the
bff and auth: in `web/apps/consumer/.env` set `NL_DEV_USER=01J9ZD3V0000000000000C0001` (Amara Osei, the seeded
customer; single-factor like a real consumer) — or `NL_DEV_GUEST=1` to browse as a guest — plus
`NL_BFF_URL=http://localhost:8080`; `/api` then goes to the api with `X-Dev-User` and `/bff/session` is answered by
the dev server. Location: the header asks for the browser's position once per visit (Chrome DevTools › Sensors
overrides it); `localStorage['nl.location']` holds an address chosen on the Location screen. Language: the FR/EN
toggle writes the `nl.locale` cookie, so the server renders the next page in French.

Shop (S-49…): the dev seed `db/seed-dev/V113__consumer_shop.sql` adds design 06's Calgary shops (Glenmore Bakery,
Bridgeland Butcher, Sunnyside Greens, Prairie Pantry, …) with approved, live products — run `seedCategories` too so
every department has its name. The pages are rendered for `?market=` (default Calgary; Edmonton and Airdrie have no
seeded shops, so their pages show the empty state). Pooled runs are created on demand from
`northline.orders.delivery.runs` (application.yml): tonight 6–9 pm, tomorrow 8–11 am.

## 6. Optional stand-ins

Start any of them with `docker compose --profile <name> up -d`, or list them in `COMPOSE_PROFILES` in `.env` and run
`docker compose up -d`. `--profile all` starts everything except `tools`.

| profile | service | point the apps at it (`server/.env`) | used by |
|---|---|---|---|
| `db` | Postgres 17 + PostGIS | `DB_URL=jdbc:postgresql://localhost:5432/northline` | everything |
| `cache` | Valkey 8 | `REDIS_HOST=localhost`, `REDIS_PORT=6379` | `local,valkey`, worker, non-`local` runs |
| `events` | Kafka 4 (KRaft) + one-shot topic creation (`scripts/topics.sh` from `deploy/kafka/topics.yaml`, ~1 min the first time, seconds after) | `KAFKA_BOOTSTRAP=localhost:9092` | worker; api without `local` |
| `search` | Elasticsearch 9 (security off) | `ES_URIS=http://localhost:9200` (+ `SEARCH_PROVIDER=elasticsearch` for the api under `local`) | worker; the api's search |
| `mail` | Mailpit — inbox at http://localhost:8025 | `SMTP_HOST=localhost`, `SMTP_PORT=1025` (the defaults) | api email (`EMAIL_PROVIDER=local`, S-13) |
| `storage` | S3-compatible storage (RustFS) + bucket `northline-local`; console http://localhost:9101 | `STORAGE_ENDPOINT=http://localhost:9100`, `STORAGE_ACCESS_KEY=northline`, `STORAGE_SECRET_KEY=northline-dev-secret`, `STORAGE_PATH_STYLE=true` | api with `STORAGE_PROVIDER=s3` (S-10) |
| `payments` | stripe-mock | `STRIPE_SECRET_KEY=sk_test_123`, `STRIPE_API_BASE=http://localhost:12111` (+ `TAX_PROVIDER=stripe` for Stripe Tax, S-21) | api payments + Stripe Connect (+ Stripe Tax) instead of the fakes |
| `tools` | Kafka UI :8190, Kibana :5601 | — | you |

Notes:
- **Storage:** by default (`STORAGE_PROVIDER=local`) uploads go to folders in the temp directory. To use the bucket,
  set `STORAGE_PROVIDER=s3` plus the variables above in `server/.env` and restart the api — uploads then land in
  RustFS under `<module>/<merchantId>/…` ([object-storage.md](object-storage.md#local-rustfs)). MinIO no longer
  publishes images on Docker Hub, so the `storage` profile runs RustFS (same S3 API); an existing MinIO or any
  S3-compatible server works the same way through `STORAGE_ENDPOINT`.
- **Mail:** the api emails team invitations and money notices to Mailpit (`EMAIL_PROVIDER=local`, the default); without
  Mailpit each email's text is logged instead. Template previews: http://localhost:8080/api/v1/dev/emails
  ([email.md](email.md#local-mailpit)).
- **Owners' identity (S-22):** `IDENTITY_PROVIDER=local` (the default) — "This is me · verify now" in Onboarding ›
  Verification opens http://localhost:8080/api/v1/dev/identity-sessions/vs_fake_… where you pick how Stripe Identity
  ends (verified, name mismatch, `document_expired`, …); emailed links land in Mailpit and open the same page. To try
  the real adapter, set `IDENTITY_PROVIDER=stripe` with a test-mode `STRIPE_SECRET_KEY` and forward webhooks with
  `stripe listen` ([stripe.md § Identity](stripe.md#8-identity-s-22)).
- **Business registries (S-23):** the three sources answer from `server/api/src/main/resources/registries/fixtures.json`
  (e.g. Alberta `2021456789` = Prairie Wrench, Calgary `BL 22-118840` = Pho Dau Bo); unknown numbers go to the manual
  review queue (`GET /api/v1/console/registry-reviews`, staff). To try Calgary's real dataset:
  `REGISTRY_CALGARY_PROVIDER=socrata` (no account needed). [registries.md](registries.md)
- **Calendar sync (S-32):** `CALENDAR_PROVIDER=local` (the default) fakes Google and Outlook: Availability › Calendar
  sync & team › Connect goes straight back through the OAuth callback, the member gets a "Work" and a "Family"
  calendar, tomorrow 12:00–13:00 is busy in the preview, and bookings written back are logged (`Local calendar
  (google): wrote …`). Refresh tokens are sealed with the development key (`KMS_PROVIDER=local`). To try the real
  providers from your laptop: `CALENDAR_PROVIDER=oauth` with your own Google / Microsoft test app registrations and
  the redirect URIs `http://localhost:3100/api/v1/calendar/oauth/<google|outlook>/callback`; push notifications stay
  off (they need a public HTTPS `API_PUBLIC_URL`), the 5-minute read does the work. [calendar-sync.md](calendar-sync.md)
- **Custom domains (S-31):** `DOMAINS_DNS_PROVIDER=local` and `DOMAINS_EDGE_PROVIDER=local` (the defaults): enter a
  domain in Studio › Business page › Custom domain, then **Simulate DNS records →** (dev builds only) writes its CNAME
  and TXT records into the api's in-memory zone and checks them; a published page of an approved business goes live at
  once and `curl 'localhost:8080/api/v1/public/storefronts/by-host?host=<domain>'` returns it. Real DNS from your laptop:
  `DOMAINS_DNS_PROVIDER=doh`. [custom-domains.md](custom-domains.md#9-local-and-tests)
- **Catalogue sync (S-35):** `COMMERCE_PROVIDER=local` (the default) fakes Shopify, Square and Lightspeed: Products ›
  Bulk upload › Connect comes straight back connected and imports the fixture catalogue
  (`server/api/src/main/resources/commerce-fixtures/`) as drafts; stock moves by the hour so "Sync now" shows updates.
  No webhooks (no public HTTPS); the hourly read does the work. Real platforms from a laptop need a public HTTPS
  `API_PUBLIC_URL` (a tunnel) for their redirect URL. [commerce-sync.md](commerce-sync.md)
- **POS menu import (S-36):** `POS_PROVIDER=local` (the default) fakes Square, Clover and Toast: Kitchen › Menu › Import
  › From your POS connects at once (Toast: any GUID but the nil one) and previews the fixture menu
  (`server/api/src/main/resources/pos-fixtures/menu.json`, Pho Dau Bo). [pos-menu-import.md](pos-menu-import.md)
- **Your own Kafka:** create the topics with
  `KAFKA_TOPICS_CMD=kafka-topics.sh KAFKA_TOPICS_BOOTSTRAP=localhost:9092 scripts/topics.sh`, or with the provisioner
  the deployed environments use: `cd server && ./gradlew :worker:kafkaTopics --args='apply'` (`plan` / `verify` change
  nothing). Both read `deploy/kafka/topics.yaml`; `scripts/topics.sh --list` prints every derived topic. Topics are
  never auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`), so a new topic goes into the catalogue first
  ([infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials)).
- **Elasticsearch (S-42):** after `--profile search`, create the synonym sets and the indices the way the deploy Job
  does: `cd server && ./gradlew :worker:searchIndices --args='apply'` (`plan` / `verify` change nothing). Run it again
  after editing `deploy/search/synonyms-*.txt` — the change is live at once ([search.md](search.md)).
- **Worker:** `cd server && ./gradlew :worker:bootRun` (no profile) needs Postgres, Kafka (`events`) and
  Elasticsearch (`search`) with the indices created (`:worker:searchIndices`). The search indexer (S-43) then fills them from
  the events the api publishes (only when the api runs without `local`, which turns Kafka off) and, every minute, from
  rows changed without an event; the dev seed's businesses have locations (`db/seed-dev/V121`) ([search.md § 6](search.md#6-the-indexer-s-43)).
- **Partner webhooks (S-33):** the worker delivers what Settings › API endpoints subscribe to. To receive them on
  your machine, set `WEBHOOKS_ALLOW_LOCAL=true` in `server/.env` (allows `http://localhost` endpoints; private,
  link-local and metadata addresses stay refused) and add an endpoint such as `http://localhost:4000/hooks` — any
  local HTTP listener works; "Send test event" in the endpoint's Deliveries drawer sends one at once.
  `WEBHOOK_SECRET_KEY` empty = the fixed development key, the same one the api uses ([webhooks.md](webhooks.md)).
- Stop: `docker compose --profile all down` (add `-v` to delete the data volumes).

## 7. Rehearse the cloud shape locally (optional)

To check a `dev`/`staging`/`prod` configuration before deploying it, run the apps with that profile against the
stand-ins. Use a separate database — the cloud profiles never load the dev seed:

```sh
docker compose --profile db --profile cache --profile events --profile search up -d
docker compose exec postgres psql -U northline -c 'create database northline_dev'
export SPRING_PROFILES_ACTIVE=dev DB_URL=jdbc:postgresql://localhost:5432/northline_dev DB_USER=northline DB_PASSWORD=northline \
  REDIS_HOST=localhost KAFKA_BOOTSTRAP=localhost:9092 ES_URIS=http://localhost:9200 \
  AUTH_ISSUER=http://localhost:9000 API_URL=http://localhost:8080 STUDIO_ORIGIN=http://localhost:3100 \
  CONSUMER_ORIGIN=http://localhost:3000 CONSOLE_ORIGIN=http://localhost:3200 WEBAUTHN_RP_ID=localhost \
  TOTP_KEY=$(openssl rand -base64 32) WEBHOOK_SECRET_KEY=$(openssl rand -base64 32) STUDIO_BFF_SECRET=s1 \
  KMS_PROVIDER=local SIGNING_KEYS_DIR=/tmp/northline-dev-keys \
  STUDIO_BFF_SECRET_HASH='{noop}s1' CONSUMER_BFF_SECRET_HASH='{noop}s2' CONSOLE_BFF_SECRET_HASH='{noop}s3'
cd server && ./gradlew :api:bootRun     # first: the api applies the migrations
./gradlew :auth:bootRun & ./gradlew :bff:bootRun & ./gradlew :worker:bootRun
curl localhost:8080/actuator/health/readiness
```

Leave a variable out and the app stops with the list of what is missing. Under `dev` there are no seeded users: register in
the Studio — `SMS_PROVIDER` defaults to `local`, so the phone code is in the auth log (`grep "Verification code"`). Move `server/.env` aside while you do this, or its
values fill in what you meant to leave out.

**On Kubernetes:** the same rehearsal with the real images and Helm chart runs on a local kind cluster —
`deploy/kind/up.sh` ([deploy.md § Local: kind](deploy.md#local-kind), S-14).

## 8. Troubleshooting

| symptom | fix |
|---|---|
| `Bind for 0.0.0.0:5432 failed: port is already allocated` | another Postgres uses the port: change `PG_PORT` in `.env` and `DB_URL` in `server/.env`, or use your own Postgres and drop `db` from the compose profiles. Same for the other ports. |
| `extension "postgis" is not available` | install PostGIS for your Postgres 17 (step 3). |
| `permission denied to create extension "postgis"` | create the three extensions once as a superuser (step 3). |
| Flyway `Validate failed` / checksum mismatch | a local database from an older checkout: drop and recreate it, then migrate again. |
| `APPLICATION FAILED TO START … need environment variables that are not set` | you started with `dev`/`staging`/`prod`. Use `--spring.profiles.active=local`, or set the listed variables (step 7). |
| `WRONGPASS` / `NOAUTH` from Valkey | set `REDIS_PASSWORD` (and `REDIS_USERNAME` for an ACL user) in `server/.env`. |
| A value from `server/.env` has quotes in it | the file is read as Java properties: write `KEY=value` without quotes. |
| Studio keeps returning to Sign in | the bff (8082) or auth (9000) is not running, or `NL_DEV_USER` is set while you meant real auth. Cookies are `Secure` outside `local`, so run auth and bff with `local` on http. |
| "Settings › Security" shows an error under dev auth | expected: it needs northline-auth (step 5). |
| Authenticator code rejected | your clock is off; sync it. Codes are 30 s, ±1 step, and a used code can't be reused. |
| `KMS_PROVIDER=local is not allowed under staging/prod` | rehearse `staging`/`prod` with a real KMS key (`KMS_PROVIDER`, `KMS_KEY_ID`; AWS also works against LocalStack with `KMS_ENDPOINT`), or rehearse `dev` |
| "Too many attempts. Try again in …" while developing | an S-9 rate limit: wait, or restart auth (`local` keeps limits in memory), or with `local,valkey` delete `nl:auth-rl:*` in Valkey |
| Passkey prompt fails | use `http://localhost:3100`, not `127.0.0.1` (WebAuthn RP id is `localhost`). |
| Worker logs `UNKNOWN_TOPIC_OR_PARTITION` | topics missing: wait for the `kafka-topics` one-shot to finish (`docker compose logs kafka-topics`) or run `scripts/topics.sh`. |
| Elasticsearch exits with code 137 | not enough memory for Docker: lower `ES_HEAP` in `.env` (e.g. `512m`). |
| `pull access denied for minio/minio` | MinIO images are gone from Docker Hub; the `storage` profile uses RustFS. |
