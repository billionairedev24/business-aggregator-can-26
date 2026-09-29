# Dev runbook — shared cloud development environment

The first cloud environment: the team's integration target and the place to try the cloud adapters as their stories land. Smallest sizes, one zone, data is disposable.

Spring profile **`dev`** (it activates `cloud` automatically). Shape: `ca.northline` at debug, OpenAPI/Swagger UI on, no dev seed, no dev auth, Kafka externalization on, `Secure` cookies, required variables checked at start-up. Sizing: smallest tiers, single zone, no replicas; can be stopped out of hours. Data: synthetic only; may be wiped at any time. Stripe: optional — blank = the payments module's fake gateway; Stripe Connect settings answer 409 `stripe_unavailable`. Test-mode keys (`sk_test_…`, `pk_test_…`) recommended.

> **Status (2026-09-29): not deployable end to end yet.** The apps start under this profile (checked against local stand-ins), but there are no container images, Helm charts or Terraform yet (S-2, S-3, S-14, S-15), and several features only have local fakes — see [Blockers](#blockers). Nothing below claims more than exists.

Other environments: [staging](staging.md), [prod](prod.md) · [local](local.md) · [overview and variable matrix](README.md)

## Managed services

Pick **one** provider per environment; everything in a Canadian region: AWS `ca-central-1` (Montréal) or `ca-west-1` (Calgary), Google Cloud `northamerica-northeast1` (Montréal) or `northamerica-northeast2` (Toronto), Azure `canadacentral` (Toronto) or `canadaeast` (Québec City).

| need | AWS | Google Cloud | Azure | notes |
|---|---|---|---|---|
| PostgreSQL 17 + PostGIS | Amazon RDS for PostgreSQL 17 (or Aurora PostgreSQL) | Cloud SQL for PostgreSQL 17 (or AlloyDB) | Azure Database for PostgreSQL – Flexible Server 17; allow-list `POSTGIS,CITEXT,PGCRYPTO` in `azure.extensions` | extensions `postgis`, `citext`, `pgcrypto` created once by the admin role; TLS (`?sslmode=require` in `DB_URL`). Cloud SQL: private IP, or the Cloud SQL Auth Proxy sidecar (`DB_URL=jdbc:postgresql://127.0.0.1:5432/northline`) |
| Valkey / Redis | Amazon ElastiCache for Valkey | Memorystore for Valkey | Azure Managed Redis (or Azure Cache for Redis) | the apps use one endpoint (no cluster client): cluster mode disabled / non-clustered endpoint; in-transit TLS → `REDIS_SSL=true` |
| Kafka | Amazon MSK (provisioned) with SASL/SCRAM | Google Cloud Managed Service for Apache Kafka (SASL/PLAIN) | Azure Event Hubs, Standard tier or higher (Kafka endpoint, SASL/PLAIN) | IAM/OAuth-only options (MSK Serverless, OAUTHBEARER) need client libraries the apps don't have yet. Confluent Cloud works on all three |
| Elasticsearch 9 | Elastic Cloud on AWS (`ca-central-1`) or ECK on EKS | Elastic Cloud on Google Cloud (`northamerica-northeast1`) or ECK on GKE | Elastic Cloud on Azure (`canadacentral`) or ECK on AKS | Amazon OpenSearch Service is **not** a drop-in: the apps use the Elasticsearch 9 client |
| Object storage (S-10) | Amazon S3 | Cloud Storage | Azure Blob Storage | one private bucket/container per environment; no adapter yet |
| Keys / KMS (S-7) | AWS KMS (`ECC_NIST_P256`, `SIGN_VERIFY`) | Cloud KMS (`EC_SIGN_P256_SHA256`, HSM) | Azure Key Vault keys (`EC-HSM`, P-256) | token signing keys, signed inside the KMS; set-up and rotation: [key-rotation.md](key-rotation.md) |
| Secrets manager (S-6) | AWS Secrets Manager | Secret Manager | Azure Key Vault (secrets) | synced into Kubernetes Secrets by External Secrets Operator; the apps only see environment variables |
| Email (S-13) | Amazon SES (`ca-central-1`) | SendGrid, Mailgun or any SMTP provider (no first-party service) | Azure Communication Services Email | no adapter yet |
| SMS / voice (S-8) | Twilio (or Amazon SNS) | Twilio | Twilio (or Azure Communication Services SMS) | Canadian sender numbers need registration; no adapter yet |
| DNS + TLS (S-17) | Route 53 + ACM (or cert-manager) | Cloud DNS + Certificate Manager (or cert-manager) | Azure DNS + cert-manager | hosts listed under "Public URLs" below |
| Container registry (S-14) | Amazon ECR | Artifact Registry | Azure Container Registry | in the same Canadian region |
| Kubernetes (S-14, S-15) | Amazon EKS | GKE | AKS | Helm charts and Argo CD are not written yet |

### Public URLs (proposed — S-17 decides)

| host | serves |
|---|---|
| `https://business.dev.northline.ca` | Studio static files (`web/apps/studio` build) **and**, on the same origin, the studio-bff for `/api/**`, `/bff/**`, `/oauth2/**`, `/login/**` (path routing at the ingress) |
| `https://auth.dev.northline.ca` | northline-auth (OIDC issuer, sign-in JSON API) |
| `https://dev.northline.ca` | consumer web (not built yet, E-7) |
| `https://console.dev.northline.ca` | platform console (not built yet, E-8) |
| api | internal only (`ClusterIP`): browsers reach it through the BFF |

## Environment variables

Every app reads its configuration from environment variables; nothing environment-specific is baked into the jars. **Required** = the app refuses to start without it and prints the missing names. Secrets go into the cloud's secrets manager and reach the pods as a Kubernetes Secret; the rest is a ConfigMap. Local equivalents and comments: [`server/.env.example`](../../server/.env.example).

| variable | app | required | example | comes from |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | all | **yes** | `dev` | deployment manifest |
| `DB_URL` | api, auth, worker | **yes** | `jdbc:postgresql://<db-host>:5432/northline?sslmode=require` | Terraform output (S-2/S-3) → ConfigMap; until then from the cloud console |
| `DB_USER` | api, auth, worker | **yes** | `northline_app` | created with the database (Terraform / DBA) |
| `DB_PASSWORD` | api, auth, worker | **yes** | — | secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret` |
| `DB_POOL_SIZE` | api, auth, worker | no | `10` (worker `5`) | sizing: replicas × pool size must stay under the server's connection limit |
| `REDIS_HOST` | api, auth, bff, worker | **yes** | `<cache-endpoint>` | Terraform output (S-2/S-3) → ConfigMap; until then from the cloud console |
| `REDIS_PORT` | api, auth, bff, worker | no | `6379` (Azure Managed Redis `10000`, Azure Cache `6380`) | Terraform output (S-2/S-3) → ConfigMap; until then from the cloud console |
| `REDIS_USERNAME`, `REDIS_PASSWORD` | api, auth, bff, worker | no | ElastiCache RBAC user / Memorystore AUTH string / Azure access key | secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret` |
| `REDIS_SSL` | api, auth, bff, worker | no | `true` (in-transit encryption) | Terraform output (S-2/S-3) → ConfigMap; until then from the cloud console |
| `KAFKA_BOOTSTRAP` | api, worker | **yes** | `b-1.<cluster>:9096,b-2.<cluster>:9096` (MSK SCRAM) · `<ns>.servicebus.windows.net:9093` (Event Hubs) | Terraform output (S-2/S-3) → ConfigMap; until then from the cloud console |
| `KAFKA_SECURITY_PROTOCOL` | api, worker | no (PLAINTEXT) | `SASL_SSL` | deployment manifest |
| `KAFKA_SASL_MECHANISM` | api, worker | no (PLAIN) | `SCRAM-SHA-512` (MSK) · `PLAIN` (GCP, Event Hubs, Confluent) | deployment manifest |
| `KAFKA_SASL_JAAS_CONFIG` | api, worker | with SASL | `org.apache.kafka.common.security.scram.ScramLoginModule required username="…" password="…";` | secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret` |
| `ES_URIS` | api, worker | **yes** | `https://<deployment>.es.<region>.<cloud>.elastic-cloud.com:443` | Elastic Cloud console / ECK service |
| `ES_USERNAME`, `ES_PASSWORD` | api, worker | with security on | `northline_app` / — | secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret` |
| `AUTH_ISSUER` | api, auth, bff | **yes** | `https://auth.dev.northline.ca` | DNS plan (S-17) |
| `AUTH_INTERNAL_URL` | bff | no (= `AUTH_ISSUER`) | `http://northline-auth.northline-dev.svc:9000` | Kubernetes service name |
| `API_URL` | bff | **yes** | `http://northline-api.northline-dev.svc:8080` | Kubernetes service name |
| `STUDIO_ORIGIN` | api, auth | **yes** | `https://business.dev.northline.ca` | DNS plan (S-17) |
| `CONSUMER_ORIGIN` | auth | **yes** | `https://dev.northline.ca` | DNS plan (S-17) |
| `CONSOLE_ORIGIN` | auth | **yes** | `https://console.dev.northline.ca` | DNS plan (S-17) |
| `WEBAUTHN_RP_ID` | auth | **yes** | `dev.northline.ca` | registrable domain shared by the Studio and consumer origins; changing it invalidates every passkey |
| `COOKIE_DOMAIN` | auth, bff | no | empty (host-only cookies — recommended) | deployment manifest |
| `TOTP_KEY` | auth | **yes** | `openssl rand -base64 32` | secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret`. Never rotate without re-encrypting `auth.totp_secrets`: losing it breaks every authenticator enrolment |
| `STUDIO_BFF_SECRET` | bff | **yes** | `openssl rand -base64 32` | secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret` |
| `STUDIO_BFF_SECRET_HASH` | auth | **yes** | `{bcrypt}$2y$12$…` of `STUDIO_BFF_SECRET` | secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret`. The three BFF secrets must differ (the authorization server rejects duplicates) |
| `CONSUMER_BFF_SECRET_HASH`, `CONSOLE_BFF_SECRET_HASH` | auth | no | `{bcrypt}…` of their own secrets | secrets manager → External Secrets (S-6) → Kubernetes Secret, once the consumer / console BFF is deployed: the client is registered only when its hash is set ([README § OAuth clients](README.md#oauth-clients-s-122)) |
| `OAUTH_CLIENTS_SYNC_ON_STARTUP` | auth | no | `true` (default); `false` = clients only via the "Register OAuth clients" Job | deployment manifest |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | auth | no (S-18) | `…apps.googleusercontent.com` | Google Cloud console → OAuth client; redirect `https://auth.dev.northline.ca/login/oauth2/code/google` |
| `APPLE_CLIENT_ID`, `APPLE_CLIENT_SECRET` | auth | no (S-18) | Services ID / signed client-secret JWT | Apple Developer → Sign in with Apple; redirect `https://auth.dev.northline.ca/login/oauth2/code/apple` |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | api | no | `sk_test_…` / `pk_test_…` | Stripe dashboard (Connect platform account) → secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret` |
| `STRIPE_API_BASE` | api | never | — | stripe-mock only (local) |
| `WEBHOOK_SECRET_KEY` | api | **yes** | `openssl rand -base64 32` | secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret`. Encrypts partner webhook signing secrets; keep it stable |
| `STORAGE_PROVIDER`, `STORAGE_BUCKET`, `STORAGE_REGION`, `STORAGE_ENDPOINT` | api | no (S-10) | `s3` / `gcs` / `azure`, `northline-dev-uploads`, the Canadian region | Terraform output (S-2/S-3) → ConfigMap; until then from the cloud console |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | api | no | empty in the cloud (workload identity) | — |
| `KMS_PROVIDER`, `KMS_KEY_ID` | auth | `KMS_PROVIDER` **yes** | `aws` + key ARN / `gcp` + key **version** name / `azure` + versioned key URL | Terraform output (S-2/S-3) → ConfigMap; until then created by hand ([key-rotation.md](key-rotation.md#creating-the-key-until-terraform-does-it--s-2)). `dev` may also run `local` with `SIGNING_KEYS_DIR` on a volume every auth replica mounts. |
| `KMS_PUBLISHED_KEY_IDS` | auth | no | empty; the next or previous key during a rotation | [key-rotation.md](key-rotation.md#rotating--cloud-providers) |
| `KMS_REGION`, `KMS_ENDPOINT` | auth | no | AWS only: `ca-central-1`, a VPC endpoint URL | deployment manifest |
| `TRUSTED_PROXIES` | auth | no (private ranges + loopback) | the ingress / load balancer subnet, e.g. `10.20.0.0/22` (comma-separated CIDRs) | network plan (S-2); only these peers may set `X-Forwarded-For/-Proto/-Host` — the client IP the rate limits and the sign-in log use ([README § Rate limits](README.md#rate-limits-s-9)) |
| `RATE_LIMIT_STORE` | auth | no (`redis`) | leave unset: `memory` is refused here | — |
| `SIGNING_KEYS_DIR`, `SIGNING_KEYS_ROTATE_EVERY` | auth | with `KMS_PROVIDER=local` | `/var/lib/northline/auth-keys` (shared volume), `90d` | deployment manifest |
| `EMAIL_PROVIDER`, `EMAIL_FROM`, `SMTP_*` | api, worker | no (S-13) | `ses` / `sendgrid` / `azure` | email provider account |
| `SMS_PROVIDER`, `SMS_FROM`, `SMS_ACCOUNT_ID`, `SMS_AUTH_TOKEN` | api, auth, worker | no (S-8) | `twilio`, `+1587…`, `AC…`, — | Twilio console → secrets manager → External Secrets (S-6) → Kubernetes Secret; until then `kubectl create secret` |
| `OTEL_EXPORT_ENABLED` | api | no | `false` until a collector exists (S-111) | deployment manifest |
| `VITE_NL_AUTH_ORIGIN` (Studio build) | web/apps/studio | **yes** at build time | `https://auth.dev.northline.ca` | CI build argument; one Studio build per environment |

Generating the secrets:

```sh
openssl rand -base64 32                                   # TOTP_KEY, WEBHOOK_SECRET_KEY, each BFF secret
# bcrypt hash of a BFF secret for auth (STUDIO_BFF_SECRET_HASH etc.); any bcrypt tool works:
docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 "" "$SECRET" | tr -d ':\n' | sed 's/^/{bcrypt}/'
```

## Third-party accounts

| account | needed for | status |
|---|---|---|
| Stripe (Connect Express, Tax) | payments, payouts, merchant onboarding — test mode | optional in dev (fake gateway without keys) |
| Twilio (or the chosen SMS provider) | phone verification codes at registration | blocked on S-8 |
| Google Cloud OAuth client, Apple Developer (Sign in with Apple) | "continue with Google / Apple" | S-18; redirect URIs on the auth host |
| Email provider (SES / SendGrid / Azure Communication Services) | invitations and notifications, with SPF/DKIM/DMARC on the sending domain | S-13 |
| Elastic Cloud (unless ECK) | search | subscription in a Canadian region |
| Cloud account with startup credits (AWS / Google Cloud / Azure) | everything else | one provider per environment |
| Domain registrar for `northline.ca` | DNS delegation to the cloud's DNS | S-17 |

## Canadian data residency

- Every data service — Postgres (and its backups and snapshots), Valkey, Kafka, Elasticsearch, object storage, KMS keys, secrets, logs — lives in a Canadian region (list above). Cross-region copies go only to the other Canadian region.
- Elastic Cloud: create the deployment in the Canadian region of the chosen cloud.
- Leave Canada by design, disclosed in the Privacy Policy (`design/10`): Stripe (payments, identity), the SMS provider, Google/Apple sign-in, and the email provider unless it runs in Canada (Amazon SES in `ca-central-1` does).
- No personal data in event payloads (CLAUDE.md), so Kafka holds ids only; logs must not carry PII (S-112).
- Staging and dev hold synthetic data only; never restore a production backup into them.

## Blockers

What still stops a complete deployment. Under the `local`/`test` profiles each of these has a fake; under `dev` the app starts and the feature fails loudly (the "unconfigured adapter" convention in `docs/DECISIONS.md`).

| port / piece | outside local | effect | fixed by |
|---|---|---|---|
| `SmsSender` (auth) | `UnconfiguredSmsSender` throws | **nobody can register** (the phone code can't be sent) — so an environment without seed data has no users | S-8 |
| Object storage: `MediaStorage` (catalogue), `DocumentStorage` (merchants), `AttachmentStorage` (messaging), `KitchenPhotoStore` (food), `MediaStore` (booking), dispute evidence (payments) | unconfigured adapters throw, or answer 409 `storage_unavailable` | no uploads: product images, onboarding documents and logos, message attachments, kitchen photos, quote/job photos, dispute evidence | S-10 |
| `IdentityVerification` (merchants) | unconfigured adapter throws | onboarding identity check | S-22 |
| `RegistryLookup` (merchants) | unconfigured adapter throws | business and licence checks | S-23 |
| `BankLinking` (merchants) | unconfigured adapter throws | instant bank linking in onboarding | S-24 |
| `DomainVerifier` (merchants) | unconfigured adapter throws | custom storefront domains | S-31 |
| `TeamInviteSender` (merchants) | unconfigured adapter reports "not sent" | invitations only by copied link | S-13, S-27 |
| `CommerceSync` (catalogue) | unconfigured adapter throws | Shopify / Square / Lightspeed connections | S-35 |
| `CalendarSync` (availability) | 409 `calendar_sync_unavailable` | Google / Microsoft calendar sync | S-32 |
| `PaymentGateway` / `ConnectAccountGateway` (payments, merchants) | stripe-java when `STRIPE_SECRET_KEY` is set | works with keys; end-to-end Connect flows and webhooks still to finish | S-11, S-12 |
| Google / Apple sign-in (auth) | placeholder client ids | the buttons fail | S-18 |
| Search indexer (worker) | consumer is a stub (`TODO(implement)`) | nothing reaches Elasticsearch | S-42, S-43 |

Delivery pieces that don't exist yet: Terraform per cloud (S-2), managed data stores (S-3), External Secrets (S-6), container images and Helm charts (S-14), Argo CD (S-15), migrations as a deploy step (S-16), DNS/TLS (S-17), Kafka topic provisioning (S-25), observability (S-111–S-113), backups and DR drill (S-114). The worker has no HTTP health endpoint yet (add with S-14).

## Deploy, migrate, roll back (what is possible today)

1. **Provision** the services above in the Canadian region (console/CLI until S-2/S-3). In Postgres, as the admin role: create the database and the app role, then `create extension if not exists postgis; create extension if not exists citext; create extension if not exists pgcrypto;` (on Azure allow-list them first).
2. **Secrets and config**: create every required variable above (secrets manager for secrets). Check a set of values locally first: [local.md § 7](local.md#7-rehearse-the-cloud-shape-locally-optional).
3. **Build**: `cd server && ./gradlew build` → `server/{api,auth,bff,worker}/build/libs/<app>.jar` (Java 25). Images: no Dockerfiles or charts yet (S-14); a stop-gap is Spring Boot's buildpacks task, e.g. `./gradlew :api:bootBuildImage --imageName=<registry>/northline-api:<git-sha>` (not validated yet). Studio: `cd web && pnpm install && VITE_NL_AUTH_ORIGIN=https://auth.dev.northline.ca pnpm --filter @northline/studio build` → `web/apps/studio/dist` (static files).
4. **Kafka topics**: `KAFKA_TOPICS_CMD='kafka-topics.sh --command-config client.properties' KAFKA_TOPICS_BOOTSTRAP=<bootstrap> KAFKA_REPLICATION_FACTOR=3 scripts/topics.sh` (client.properties holds the SASL settings). Retention, ACLs and DLQ policy: S-25.
5. **Migrate**: the api applies `db/migrations` with Flyway when it starts (never `db/seed-dev`: the cloud profiles don't include it); northline-auth doesn't migrate. To migrate ahead of a release from a machine that can reach the database: `./gradlew :api:flywayMigrate -Pdb.url=… -Pdb.user=… -Pdb.password=…` (**never** `-Pdb.devSeed=true`). A separate migration job: S-16.
6. **Register OAuth clients** (S-122): once the schema is migrated (step 5, or after the api's first start) — a Kubernetes Job with the auth image and the auth Deployment's environment running `OAuthClientsCommand sync` ([README § OAuth clients](README.md#oauth-clients-s-122)); from a machine that can reach the database: `SPRING_PROFILES_ACTIVE=dev <auth variables> ./gradlew :auth:oauthClients --args='sync'`. Idempotent; northline-auth also reconciles at every start (`OAUTH_CLIENTS_SYNC_ON_STARTUP`), so the Job matters when that is off and to see drift (`list`). Redirect URIs must be `https` (loopback `http` is tolerated under dev), and secrets `{bcrypt}` hashes (`{noop}` only warns under dev).
7. **Start in order**: api (one replica first, so one process migrates) → northline-auth (any number of replicas: the signing key is in the KMS, or with `local` on the volume they share) → studio-bff → worker → Studio static files and ingress routes. Probes: `/actuator/health/liveness` and `/actuator/health/readiness` on api (8080), auth (9000) and bff (8082).
8. **Verify**: `curl https://auth.dev.northline.ca/.well-known/openid-configuration` shows the issuer `https://auth.dev.northline.ca`; the readiness probes answer `UP`, and the api's `/actuator/health` (Postgres, Valkey, Elasticsearch) is `UP`; the Studio loads at `https://business.dev.northline.ca` and **Sign in** reaches the auth server and the sign-in hands off to the studio-bff (the `studio-bff` client exists).

**Roll back**: redeploy the previous jars/images with the previous configuration. Flyway is forward-only, so every migration must keep the previous release working (expand → migrate → contract across releases). A bad migration is fixed forward with a new migration; dev data is disposable — drop and recreate the database if needed. A configuration rollback is the previous secret/ConfigMap version plus a restart.

## Signing key rotation

northline-auth signs tokens with the KMS key `KMS_KEY_ID`; the private key never leaves the KMS, restarts and extra
replicas keep every token valid, and `/oauth2/jwks` publishes the keys (full procedure, commands per cloud and the
emergency path: [key-rotation.md](key-rotation.md)). Routine rotation (at least yearly), each step a normal rolling
deploy of northline-auth only:

1. Create a new key (AWS) or key version (Google Cloud, Azure) with the same permissions.
2. `KMS_PUBLISHED_KEY_IDS=<new>` → deploy → `curl -s https://auth.<domain>/oauth2/jwks | jq '.keys[].kid'` shows two
   kids → wait ≥ 5 min (JWK set caches of the api and bff).
3. `KMS_KEY_ID=<new>`, `KMS_PUBLISHED_KEY_IDS=<old>` → deploy.
4. After ≥ 1 h: `KMS_PUBLISHED_KEY_IDS=` → deploy → disable the old key/version in the KMS.

Compromised key: new key, `KMS_KEY_ID=<new>` and `KMS_PUBLISHED_KEY_IDS=` in one deploy, disable the old key, restart
api and bff (drops their JWK set caches). Nobody is signed out: refresh tokens and sessions are not signed with it.

## Readiness checklist

- [ ] Cloud account and Canadian region chosen; billing alerts on
- [ ] Postgres 17 with `postgis`, `citext`, `pgcrypto`; app role without superuser; TLS on
- [ ] Valkey/Redis (single endpoint, TLS), Kafka (SASL_SSL) with topics created, Elasticsearch 9 reachable from the cluster
- [ ] Every **required** variable set; `SPRING_PROFILES_ACTIVE=dev`; apps start without a "need environment variables" failure
- [ ] `TOTP_KEY`, `WEBHOOK_SECRET_KEY` and the BFF secrets generated, stored in the secrets manager and backed up
- [ ] Three distinct BFF client secrets; the bff's `STUDIO_BFF_SECRET` matches auth's `STUDIO_BFF_SECRET_HASH`
- [ ] OAuth clients registered: `oauthClients list` shows `studio-bff: up to date` (step "Register OAuth clients")
- [ ] DNS and TLS for `https://business.dev.northline.ca` and `https://auth.dev.northline.ca`; ingress routes `/api`, `/bff`, `/oauth2`, `/login` on the Studio host to the bff
- [ ] Studio built with `VITE_NL_AUTH_ORIGIN=https://auth.dev.northline.ca`
- [ ] Token signing key created in the KMS, `KMS_PROVIDER` / `KMS_KEY_ID` set, workload identity may sign with it; JWK set checked ([key-rotation.md](key-rotation.md)); rotation date in the calendar
- [ ] Stripe keys (test mode) — optional
