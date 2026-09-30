# Staging runbook — pre-production

Production's shape at a smaller size: the same profile settings except log level and OpenAPI, Stripe in **test mode**, synthetic data only. Every release goes through staging before prod.

Spring profile **`staging`** (it activates `cloud` automatically). Shape: `ca.northline` at info, OpenAPI/Swagger UI on, no dev seed, no dev auth, Kafka externalization on, `Secure` cookies, required variables checked at start-up. Sizing: prod topology (multi-zone, replicas) at smaller instance sizes. Data: synthetic only — never copy production data here. Stripe: **required** (test mode): the api refuses to start without it so staging never runs the fake gateway.

> **Status (2026-09-29): not deployable end to end yet.** The apps start under this profile (checked against local stand-ins), and the Terraform for the cloud foundation and the managed data stores exists but has not been applied yet — no cloud account exists ([infrastructure.md](infrastructure.md), S-2, S-3). Container images and the Helm chart exist (S-14, [deploy.md](deploy.md); rehearsed on kind, never installed on a cloud cluster); External Secrets are wired (S-6, [secrets.md](secrets.md)); migrations run as a Job before each rollout (S-16); GitOps (S-15) and TLS/DNS (S-17) don't exist yet, and several features only have local fakes — see [Blockers](#blockers). Nothing below claims more than exists.

Other environments: [dev](dev.md), [prod](prod.md) · [local](local.md) · [overview and variable matrix](README.md)

## Managed services

Pick **one** provider per environment; `infra/terraform/envs/<aws|gcp|azure>/staging` creates it ([infrastructure.md](infrastructure.md)). Everything in a Canadian region: AWS `ca-central-1` (Montréal) or `ca-west-1` (Calgary), Google Cloud `northamerica-northeast1` (Montréal) or `northamerica-northeast2` (Toronto), Azure `canadacentral` (Toronto) or `canadaeast` (Québec City).

| need | AWS | Google Cloud | Azure | notes | Terraform (`infra/terraform/modules/`) |
|---|---|---|---|---|---|
| PostgreSQL 17 + PostGIS | Amazon RDS for PostgreSQL 17 (or Aurora PostgreSQL) | Cloud SQL for PostgreSQL 17 (or AlloyDB) | Azure Database for PostgreSQL – Flexible Server 17; allow-list `POSTGIS,CITEXT,PGCRYPTO` in `azure.extensions` | extensions `postgis`, `citext`, `pgcrypto` created once by the admin role ([infrastructure.md § 5.1](infrastructure.md#51-postgresql-app-role-and-extensions-once-per-environment)); TLS (`?sslmode=require` in `DB_URL`). Cloud SQL: private IP, or the Cloud SQL Auth Proxy sidecar (`DB_URL=jdbc:postgresql://127.0.0.1:5432/northline`) | `postgres` (S-3) |
| Valkey / Redis | Amazon ElastiCache for Valkey | Memorystore for Valkey | Azure Managed Redis (or Azure Cache for Redis) | the apps use one endpoint (no cluster client): cluster mode disabled / non-clustered endpoint; in-transit TLS → `REDIS_SSL=true`. Memorystore for Valkey has no password and a per-instance CA the pods must trust ([infrastructure.md § 5.2](infrastructure.md#52-valkey--redis)) | `cache` (S-3) |
| Kafka | Amazon MSK (provisioned) with SASL/SCRAM | Google Cloud Managed Service for Apache Kafka (SASL/PLAIN) | Azure Event Hubs Premium (Kafka endpoint, SASL/PLAIN; Standard caps at 10 event hubs, Northline needs about 50) | IAM/OAuth-only options (MSK Serverless, OAUTHBEARER) need client libraries the apps don't have yet. Confluent Cloud works on all three. Topics are never auto-created ([infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials)) | `kafka` (S-3) |
| Elasticsearch 9 | Elastic Cloud on AWS (`ca-central-1`) or ECK on EKS | Elastic Cloud on Google Cloud (`northamerica-northeast1`) or ECK on GKE | Elastic Cloud on Azure (`canadacentral`) or ECK on AKS | Amazon OpenSearch Service is **not** a drop-in: the apps use the Elasticsearch 9 client. Terraform uses Elastic Cloud (`elastic/ec`) on every cloud | `search` (S-3) |
| Object storage (S-10) | Amazon S3 | Cloud Storage | Azure Blob Storage | one private bucket (container on Azure) per environment in the Canadian region, created by Terraform with encryption, versioning, lifecycle and least-privilege access for `northline-api`; settings per cloud and the manual set-up without Terraform: [object-storage.md](object-storage.md#cloud-set-up-until-terraform-does-it--s-2) | `storage` (S-2; `STORAGE_*` of S-10) |
| Keys / KMS (S-7) | AWS KMS (`ECC_NIST_P256`, `SIGN_VERIFY`) | Cloud KMS (`EC_SIGN_P256_SHA256`, HSM) | Azure Key Vault keys (`EC-HSM`, P-256) | token signing keys, signed inside the KMS; Terraform creates the `signing` key (HSM in prod on Google Cloud and Azure) and lets only `northline-auth` sign and read its public key; set-up and rotation: [key-rotation.md](key-rotation.md) | `kms` (S-2; `KMS_*` of S-7) |
| Secrets manager (S-6) | AWS Secrets Manager | Secret Manager | Azure Key Vault (secrets) | synced into one Kubernetes Secret per app by External Secrets Operator (chart `externalSecrets`, [secrets.md](secrets.md)); the apps only see environment variables | `secrets` (S-2) |
| Email (S-13) | Amazon SES (`ca-central-1`, `EMAIL_PROVIDER=ses`) | SendGrid (`sendgrid`) or any SMTP relay (`smtp`) — no first-party service | Azure Communication Services Email (`azure`, data location Canada) | verified sending domain with SPF/DKIM/DMARC; set-up per cloud: [email.md](email.md) | — (DNS records by hand until S-17) |
| SMS / voice (S-8) | Twilio (`SMS_PROVIDER=twilio`) or AWS End User Messaging SMS and voice (`aws`) | Twilio (no first-party SMS service) | Twilio (Azure Communication Services: reserved, not implemented) | Canadian sender number (long code, or verified toll-free); set-up: [README § SMS and voice codes](README.md#sms-and-voice-codes-s-8) | — (AWS: optional `sms_origination_identity` grants `northline-auth` `sms-voice:Send*` on the number and fills `SMS_*`) |
| DNS + TLS (S-17) | Route 53 + ACM (or cert-manager) | Cloud DNS + Certificate Manager (or cert-manager) | Azure DNS + cert-manager | hosts listed under "Public URLs" below | `dns` zone (S-2); records/TLS S-17 |
| Container registry (S-14) | Amazon ECR | Artifact Registry | Azure Container Registry | in the same Canadian region; images `<registry>/<app>:<git sha>` ([deploy.md](deploy.md)) | `registry` (S-2) |
| Kubernetes (S-14, S-15) | Amazon EKS | GKE | AKS | Helm chart `deploy/helm/northline` with `values-staging.yaml` + `values-<cloud>.yaml` ([deploy.md](deploy.md)); Argo CD is S-15 | `network` + `kubernetes` (S-2) |

### Public URLs (proposed — S-17 decides)

| host | serves |
|---|---|
| `https://business.staging.northline.ca` | Studio static files (`web/apps/studio` build) **and**, on the same origin, the studio-bff for `/api/**`, `/bff/**`, `/oauth2/**`, `/login/**` (path routing at the ingress) |
| `https://auth.staging.northline.ca` | northline-auth (OIDC issuer, sign-in JSON API) |
| `https://staging.northline.ca` | consumer web (not built yet, E-7) |
| `https://console.staging.northline.ca` | platform console (not built yet, E-8) |
| api | internal only (`ClusterIP`): browsers reach it through the BFF |

## Environment variables

Every app reads its configuration from environment variables; nothing environment-specific is baked into the jars. **Required** = the app refuses to start without it and prints the missing names. Secrets go into the cloud's secrets manager and reach the pods as a Kubernetes Secret; the rest is a ConfigMap. Local equivalents and comments: [`server/.env.example`](../../server/.env.example).

| variable | app | required | example | comes from |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | all | **yes** | `staging` | deployment manifest |
| `DB_URL` | api, auth, worker | **yes** | `jdbc:postgresql://<db-host>:5432/northline?sslmode=require` | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `DB_USER` | api, auth, worker | **yes** | `northline_app` | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap; the role itself is created once by the bootstrap SQL ([infrastructure.md § 5.1](infrastructure.md#51-postgresql-app-role-and-extensions-once-per-environment)) |
| `DB_PASSWORD` | api, auth, worker | **yes** | — | generated by Terraform into the secrets manager, named by `secret_env` (S-3) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets` |
| `DB_POOL_SIZE` | api, auth, worker | no | `10` (worker `5`) | sizing: replicas × pool size must stay under the server's connection limit |
| `REDIS_HOST` | api, auth, bff, worker | **yes** | `<cache-endpoint>` | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `REDIS_PORT` | api, auth, bff, worker | no | `6379` (Azure Managed Redis `10000`, Azure Cache `6380`) | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `REDIS_USERNAME`, `REDIS_PASSWORD` | api, auth, bff, worker | no | username empty everywhere; password = ElastiCache AUTH token / Azure access key / **none** on Memorystore for Valkey | `REDIS_PASSWORD`: generated by Terraform into the secrets manager, named by `secret_env` (S-3) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets` |
| `REDIS_SSL` | api, auth, bff, worker | no | `true` (in-transit encryption) | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `KAFKA_BOOTSTRAP` | api, worker | **yes** | `b-1.<cluster>:9096,b-2.<cluster>:9096` (MSK SCRAM) · `<ns>.servicebus.windows.net:9093` (Event Hubs) | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `KAFKA_SECURITY_PROTOCOL` | api, worker | no (PLAINTEXT) | `SASL_SSL` | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `KAFKA_SASL_MECHANISM` | api, worker | no (PLAIN) | `SCRAM-SHA-512` (MSK) · `PLAIN` (GCP, Event Hubs, Confluent) | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `KAFKA_SASL_JAAS_CONFIG` | api, worker | with SASL | `org.apache.kafka.common.security.scram.ScramLoginModule required username="…" password="…";` | generated by Terraform into the secrets manager, named by `secret_env` (S-3) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets` |
| `ES_URIS` | api, worker | **yes** | `https://<deployment>.es.<region>.<cloud>.elastic-cloud.com:443` | Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `ES_USERNAME`, `ES_PASSWORD` | api, worker | with security on | `elastic` (superuser until a `northline_app` user exists, [infrastructure.md § 5.4](infrastructure.md#54-elasticsearch-elastic-cloud)) / — | `ES_USERNAME`: Terraform `config_env` (S-3, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap; `ES_PASSWORD`: generated by Terraform into the secrets manager, named by `secret_env` (S-3) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets` |
| `AUTH_ISSUER` | api, auth, bff | **yes** | `https://auth.staging.northline.ca` | DNS plan (S-17) |
| `AUTH_INTERNAL_URL` | bff | no (= `AUTH_ISSUER`) | `http://northline-auth.northline-staging.svc:9000` | Kubernetes service name |
| `API_URL` | bff | **yes** | `http://northline-api.northline-staging.svc:8080` | Kubernetes service name |
| `STUDIO_ORIGIN` | api, auth | **yes** | `https://business.staging.northline.ca` | DNS plan (S-17) |
| `CONSUMER_ORIGIN` | auth | **yes** | `https://staging.northline.ca` | DNS plan (S-17) |
| `CONSOLE_ORIGIN` | auth | **yes** | `https://console.staging.northline.ca` | DNS plan (S-17) |
| `WEBAUTHN_RP_ID` | auth | **yes** | `staging.northline.ca` | registrable domain shared by the Studio and consumer origins; changing it invalidates every passkey |
| `COOKIE_DOMAIN` | auth, bff | no | empty (host-only cookies — recommended) | deployment manifest |
| `TOTP_KEY` | auth | **yes** | `openssl rand -base64 32` | secrets manager (secret created empty by Terraform, named in `secret_env`) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets`. Never rotate without re-encrypting `auth.totp_secrets`: losing it breaks every authenticator enrolment |
| `STUDIO_BFF_SECRET` | bff | **yes** | `openssl rand -base64 32` | secrets manager (secret created empty by Terraform, named in `secret_env`) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets` |
| `STUDIO_BFF_SECRET_HASH` | auth | **yes** | `{bcrypt}$2y$12$…` of `STUDIO_BFF_SECRET` | secrets manager (secret created empty by Terraform, named in `secret_env`) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets`. The three BFF secrets must differ (the authorization server rejects duplicates) |
| `CONSUMER_BFF_SECRET_HASH`, `CONSOLE_BFF_SECRET_HASH` | auth | no | `{bcrypt}…` of their own secrets | secrets manager (secret created empty by Terraform, named in `secret_env`) → External Secrets (S-6) → Kubernetes Secret, once the consumer / console BFF is deployed: the client is registered only when its hash is set ([README § OAuth clients](README.md#oauth-clients-s-122)) |
| `OAUTH_CLIENTS_SYNC_ON_STARTUP` | auth | no | `true` (default); `false` = clients only via the "Register OAuth clients" Job | deployment manifest |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | auth | no (S-18) | `…apps.googleusercontent.com` | Google Cloud console → OAuth client; redirect `https://auth.staging.northline.ca/login/oauth2/code/google` |
| `APPLE_CLIENT_ID`, `APPLE_CLIENT_SECRET` | auth | no (S-18) | Services ID / signed client-secret JWT | Apple Developer → Sign in with Apple; redirect `https://auth.staging.northline.ca/login/oauth2/code/apple` |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | api | **yes** | `sk_test_…` / `pk_test_…` | Stripe dashboard (Connect platform account) → secrets manager (secret created empty by Terraform, named in `secret_env`) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets` |
| `STRIPE_API_BASE` | api | never | — | stripe-mock only (local) |
| `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET` | api | **yes** | `whsec_…` | the two webhook endpoints in the Stripe dashboard ([stripe.md](stripe.md#5-webhooks-s-12)) → secrets manager → External Secrets (S-6) → Kubernetes Secret |
| `WEBHOOK_SECRET_KEY` | api | **yes** | `openssl rand -base64 32` | secrets manager (secret created empty by Terraform, named in `secret_env`) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets`. Encrypts partner webhook signing secrets; keep it stable |
| `STORAGE_PROVIDER`, `STORAGE_BUCKET` | api | **yes** (`local` is refused) | `s3` / `gcs` / `azure`, `northline-staging-uploads` (Azure: the container, e.g. `uploads`) | Terraform `config_env` (module `storage`, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap; without Terraform from the cloud console ([object-storage.md](object-storage.md)) |
| `STORAGE_REGION`, `STORAGE_ENDPOINT` | api | no (Azure: `STORAGE_ENDPOINT` yes) | `ca-central-1` (S3); `https://nlstagingst<suffix>.blob.core.windows.net` (Azure: the account's blob endpoint); empty otherwise | Terraform `config_env` (module `storage`, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap |
| `STORAGE_ENCRYPTION_KEY` | api | no | empty (provider-managed keys) or the KMS key ARN / Cloud KMS key name / Azure encryption scope | Terraform `config_env` (module `storage`): the environment's `data` key — KMS key ARN (AWS), Cloud KMS key name (Google Cloud); empty on Azure, where the storage account itself is encrypted with the Key Vault key → ConfigMap |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PATH_STYLE` | api | no | empty / `false` in the cloud (workload identity: EKS Pod Identity or IRSA, GKE / AKS Workload Identity) | — (Terraform grants the `northline-api` workload identity least-privilege access to the bucket / container, [object-storage.md](object-storage.md)) |
| `KMS_PROVIDER`, `KMS_KEY_ID` | auth | **yes** | `aws` + key ARN / `gcp` + key **version** name / `azure` + versioned key URL | Terraform `config_env` (module `kms`, key `signing`: key ARN / `…/cryptoKeyVersions/1` / versioned key URL, [infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) → ConfigMap; without Terraform created by hand ([key-rotation.md](key-rotation.md#creating-the-key-until-terraform-does-it--s-2)). `local` is refused. |
| `KMS_PUBLISHED_KEY_IDS` | auth | no | empty; the next or previous key during a rotation | Terraform `config_env` from `signing_key_ids` in the env root (empty by default); during a rotation [key-rotation.md](key-rotation.md#rotating--cloud-providers) |
| `KMS_REGION`, `KMS_ENDPOINT` | auth | no | AWS only: `ca-central-1`, a VPC endpoint URL | deployment manifest |
| `TRUSTED_PROXIES` | auth | no (private ranges + loopback) | the ingress / load balancer subnet, e.g. `10.20.0.0/22` (comma-separated CIDRs) | network plan (S-2); only these peers may set `X-Forwarded-For/-Proto/-Host` — the client IP the rate limits and the sign-in log use ([README § Rate limits](README.md#rate-limits-s-9)) |
| `RATE_LIMIT_STORE` | auth | no (`redis`) | leave unset: `memory` is refused here | — |
| `CLIENT_CITY_HEADER` | auth | no (empty) | `CloudFront-Viewer-City`, or the custom header your load balancer / Front Door fills with the client's city | ingress / CDN configuration; believed only from `TRUSTED_PROXIES` ([README § Sessions](README.md#sessions-s-19)) |
| `SESSION_STEP_UP_MAX_AGE` | auth | no (`10m`) | leave unset | how recent a second factor revoking sessions / removing passkeys needs (S-19) |
| `SESSION_CHECK_INTERVAL` | bff | no (`60s`) | leave unset | a revoked session's BFF session ends within this (S-19) |
| `EMAIL_PROVIDER`, `EMAIL_FROM` | api | **yes** (`local` refused) | `ses` / `sendgrid` / `azure` / `smtp`, `Northline <no-reply@northline.ca>` | email provider account ([email.md](email.md)) → ConfigMap |
| `EMAIL_UNSUBSCRIBE_KEY`, `API_PUBLIC_URL` | api | **yes** | `openssl rand -base64 32`, `https://api.staging.northline.ca` | key → secrets manager → External Secrets → Kubernetes Secret; URL → ConfigMap (the ingress must expose `/api/v1/email/unsubscribe` on it) |
| `EMAIL_REGION`, `EMAIL_ENDPOINT`, `EMAIL_API_KEY`, `EMAIL_CONFIGURATION_SET`, `EMAIL_REPLY_TO`, `SMTP_*` | api | per provider ([email.md § Variables](email.md#variables)) | `ca-central-1` (ses) · ACS endpoint (azure) · `SG.…` (sendgrid) | `EMAIL_API_KEY`, `SMTP_PASSWORD` → secrets manager |
| `SMS_PROVIDER`, `SMS_FROM`, `SMS_ACCOUNT_ID`, `SMS_AUTH_TOKEN` | auth | **`SMS_PROVIDER`, `SMS_FROM`** (`local` refused); the Twilio pair with `twilio` | `twilio`, `+15875550100` (or `MG…`), `AC…`, — | Twilio console → ConfigMap, `SMS_AUTH_TOKEN` → secrets manager (secret created empty by Terraform, named in `secret_env`) → External Secrets (S-6, [secrets.md](secrets.md)) → Secret `northline-<app>-secrets`. AWS End User Messaging instead (`aws`): Terraform `config_env` sets `SMS_PROVIDER`, `SMS_FROM`, `SMS_REGION` when `sms_origination_identity` is given ([infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)) |
| `SMS_VOICE_FROM`, `SMS_REGION`, `SMS_ENDPOINT` | auth | no | `+15875550101` (needed when `SMS_FROM` is `MG…`), `ca-central-1` (`aws`), — | [README § SMS and voice codes](README.md#sms-and-voice-codes-s-8) |
| `OTEL_EXPORT_ENABLED` | api | no | `false` until a collector exists (S-111) | deployment manifest |
| `VITE_NL_AUTH_ORIGIN` (Studio build) | web/apps/studio | no since S-14: the Studio image reads `NL_AUTH_ORIGIN` at start (chart: `urls.auth`); the build-time value is only a fallback | `https://auth.staging.northline.ca` | CI build argument; one Studio build per environment |

Generating the secrets:

```sh
openssl rand -base64 32                                   # TOTP_KEY, WEBHOOK_SECRET_KEY, each BFF secret
# bcrypt hash of a BFF secret for auth (STUDIO_BFF_SECRET_HASH etc.); any bcrypt tool works:
docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 "" "$SECRET" | tr -d ':\n' | sed 's/^/{bcrypt}/'
```

## Third-party accounts

| account | needed for | status |
|---|---|---|
| Stripe (Connect Express, Tax) | payments, payouts, merchant onboarding — test mode | required |
| Twilio (or AWS End User Messaging SMS and voice) | phone verification codes at registration (SMS + voice) | required; set-up in [README § SMS and voice codes](README.md#sms-and-voice-codes-s-8) (upgraded account, Canada-only geo permissions, Canadian sender) |
| Google Cloud OAuth client, Apple Developer (Sign in with Apple) | "continue with Google / Apple" | S-18; redirect URIs on the auth host |
| Email provider (SES / SendGrid / Azure Communication Services, or an SMTP relay) | invitations and money notices, with SPF/DKIM/DMARC on the sending domain | required; set-up in [email.md](email.md) (SES: production access out of the sandbox) |
| Elastic Cloud (unless ECK) | search | subscription (or the cloud marketplace's) + an API key exported as `EC_API_KEY` for Terraform; the deployment is created in the chosen cloud's Canadian region |
| Cloud account with startup credits (AWS / Google Cloud / Azure) | everything else | one provider per environment |
| Domain registrar for `northline.ca` | DNS delegation to the cloud's DNS | S-17 |

## Canadian data residency

- Every data service — Postgres (and its backups and snapshots), Valkey, Kafka, Elasticsearch, object storage, KMS keys, secrets, logs — lives in a Canadian region (list above). Cross-region copies go only to the other Canadian region.
- Elastic Cloud: the deployment is created by Terraform in the Canadian region of the chosen cloud (`aws-ca-central-1`, `gcp-northamerica-northeast1`, `azure-canadacentral`).
- Leave Canada by design, disclosed in the Privacy Policy (`design/10`): Stripe (payments, identity), the SMS provider, Google/Apple sign-in, and the email provider unless it runs in Canada (Amazon SES in `ca-central-1` does).
- No personal data in event payloads (CLAUDE.md), so Kafka holds ids only; logs must not carry PII (S-112).
- Staging and dev hold synthetic data only; never restore a production backup into them.

## Blockers

What still stops a complete deployment. Under the `local`/`test` profiles each of these has a fake; under `staging` the app starts and the feature fails loudly (the "unconfigured adapter" convention in `docs/DECISIONS.md`).

| port / piece | outside local | effect | fixed by |
|---|---|---|---|
| `IdentityVerification` (merchants) | unconfigured adapter throws | onboarding identity check | S-22 |
| `RegistryLookup` (merchants) | unconfigured adapter throws | business and licence checks | S-23 |
| `BankLinking` (merchants) | unconfigured adapter throws | instant bank linking in onboarding | S-24 |
| `DomainVerifier` (merchants) | unconfigured adapter throws | custom storefront domains | S-31 |
| SMS notices (bank change) and SMS team invitations | not sent by the api (the SMS port is in northline-auth) | bank-change notice by email only; mobile invitations by copied link | S-27 |
| `CommerceSync` (catalogue) | unconfigured adapter throws | Shopify / Square / Lightspeed connections | S-35 |
| `CalendarSync` (availability) | 409 `calendar_sync_unavailable` | Google / Microsoft calendar sync | S-32 |
| `PaymentGateway` / `ConnectAccountGateway` (payments, merchants) | stripe-java when `STRIPE_SECRET_KEY` is set | Connect accounts, escrow charges, transfers, payouts, refunds (S-11) and webhooks (S-12) work with keys and signing secrets, set up per [stripe.md](stripe.md) | — |
| Google / Apple sign-in (auth) | placeholder client ids | the buttons fail | S-18 |
| Search indexer (worker) | consumer is a stub (`TODO(implement)`) | nothing reaches Elasticsearch | S-42, S-43 |

Terraform for the cloud foundation exists but is unapplied (S-2, [infrastructure.md](infrastructure.md)). The managed data stores are in the same Terraform, also unapplied (S-3). Delivery pieces that don't exist yet: Argo CD (S-15), DNS/TLS (S-17), Kafka topic provisioning (S-25), observability (S-111–S-113), backups and DR drill (S-114). Container images and the Helm chart exist since S-14 ([deploy.md](deploy.md)); the worker now has a health-only HTTP port (8084).

## Deploy, migrate, roll back

1. **Provision** with Terraform: `infra/terraform/envs/<cloud>/staging` creates the network, Kubernetes cluster, keys, registry, DNS zone, uploads bucket, secrets and the data stores (PostgreSQL, Valkey/Redis, Kafka, Elastic Cloud) ([infrastructure.md](infrastructure.md)); load `config_env` into the ConfigMap and set the app secret values (the data-store secrets are generated). In Postgres, once, as the admin role from a pod in the cluster: create the app role `northline_app` with the generated password, hand it the `northline` database and create `postgis`, `citext`, `pgcrypto` ([infrastructure.md § 5.1](infrastructure.md#51-postgresql-app-role-and-extensions-once-per-environment)).
2. **Secrets and config**: create every required variable above (secrets manager for secrets). Check a set of values locally first: [local.md § 7](local.md#7-rehearse-the-cloud-shape-locally-optional).
3. **Build and push the images** (S-14, [deploy.md § Build and push](deploy.md#build-and-push)): manually from CI — GitHub Actions › **deploy** (`registry`, `image-tag`), GitLab `PIPELINE_PART=images` — or `cd server && ./gradlew jib -Pimage.registry=$REGISTRY -Pimage.tag=$IMAGE_TAG` and `docker buildx build web --target studio|consumer …`. Images `<registry>/{api,auth,bff,worker,studio,consumer}:<git sha>`; one Studio image serves every environment (the auth origin `https://auth.staging.northline.ca` is read at start from `NL_AUTH_ORIGIN`, set by the chart from `urls.auth`).
4. **Kafka topics**: `KAFKA_TOPICS_CMD='kafka-topics.sh --command-config client.properties' KAFKA_TOPICS_BOOTSTRAP=<bootstrap> KAFKA_REPLICATION_FACTOR=<data_stores.kafka.replication_factor> scripts/topics.sh` from a pod in the cluster (client.properties holds the SASL settings; on Azure use the `kafka-admin-jaas-config` secret, which has the Manage right: [infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials)). Retention, ACLs and DLQ policy: S-25.
5. **Migrate** (S-16, [deploy.md § Migrations](deploy.md#migrations-s-16)): the chart's `northline-migrate` Job applies `db/migrations` and seeds the categories **before** the pods roll (Helm pre-install/pre-upgrade hook, Argo CD PreSync); a failed migration fails the release and the running pods keep serving. The images don't contain `db/seed-dev`, and the dev seed is refused under this profile. Logs: `kubectl -n northline-staging logs job/northline-migrate -c migrate`. Without Helm, from a machine that can reach the database: `./gradlew :api:flywayMigrate :api:seedCategories -Pdb.url=… -Pdb.user=… -Pdb.password=…`.
6. **Register OAuth clients** (S-122): once the schema is migrated (step 5) — the chart's post-install/upgrade hook Job `northline-oauth-clients` (S-14) with the auth image and the auth Deployment's environment running `OAuthClientsCommand sync` ([README § OAuth clients](README.md#oauth-clients-s-122)); from a machine that can reach the database: `SPRING_PROFILES_ACTIVE=staging <auth variables> ./gradlew :auth:oauthClients --args='sync'`. Idempotent; northline-auth also reconciles at every start (`OAUTH_CLIENTS_SYNC_ON_STARTUP`), so the Job matters when that is off and to see drift (`list`). Redirect URIs must be `https`, and secrets `{bcrypt}` hashes (`{noop}` is refused).
7. **Install or upgrade with Helm** ([deploy.md § Install, upgrade, roll back](deploy.md#install-upgrade-roll-back)): `helm upgrade --install northline deploy/helm/northline -n northline-staging -f values-staging.yaml -f values-<cloud>.yaml -f <config_env + workload identities from Terraform> --set global.image.registry=… --set global.image.tag=… --wait`. Secrets: the cloud overlay turns on External Secrets — every secret variable is read from the secrets manager into one Secret per app ([secrets.md](secrets.md)); install External Secrets Operator once per cluster first. The chart runs the OAuth client Job (step 6) after the pods are Ready. The migration Job runs first (step 5); the api doesn't migrate at start. Probes: `/actuator/health/liveness` and `/actuator/health/readiness` on api (8080), auth (9000), bff (8082) and worker (8084); `/healthz` on studio and consumer.
8. **Verify**: `curl https://auth.staging.northline.ca/.well-known/openid-configuration` shows the issuer `https://auth.staging.northline.ca`; the readiness probes answer `UP`, and the api's `/actuator/health` (Postgres, Valkey, Elasticsearch) is `UP`; the Studio loads at `https://business.staging.northline.ca` and **Sign in** reaches the auth server and the sign-in hands off to the studio-bff (the `studio-bff` client exists).

**Roll back**: `helm rollback northline <revision> -n northline-staging --wait` (previous images and ConfigMaps; `helm history` lists the revisions) — [deploy.md](deploy.md#install-upgrade-roll-back). Flyway is forward-only, so every migration must keep the previous release working (expand → migrate → contract across releases). A bad migration is fixed forward with a new migration, or by restoring the point-in-time backup taken before the release into a new instance and pointing `DB_URL` at it (S-114 sets up and drills this). A configuration rollback is the previous secret/ConfigMap version plus a restart.

## Object storage

Uploads go to the bucket named by `STORAGE_BUCKET` through the provider in `STORAGE_PROVIDER` (`s3` | `gcs` | `azure`),
under `<module>/<merchantId>/<ulid>.<ext>`, with the pod's workload identity. Bucket settings, the least-privilege
policy per cloud, encryption keys, lifecycle and troubleshooting: [object-storage.md](object-storage.md). `STORAGE_PROVIDER=local` stops the api at start-up here. After a
deploy: the api logs `Object storage: <provider> bucket=…`; upload a document in the Studio and open it again.

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
- [ ] `terraform apply` in `infra/terraform/envs/<cloud>/staging` (state in the bootstrapped bucket); `config_env` in the ConfigMap; secret values set
- [ ] Postgres 17 with `postgis`, `citext`, `pgcrypto`; app role without superuser; TLS on
- [ ] Valkey/Redis (single endpoint, TLS), Kafka (SASL_SSL) with topics created, Elasticsearch 9 reachable from the cluster
- [ ] Every **required** variable set; `SPRING_PROFILES_ACTIVE=staging`; apps start without a "need environment variables" failure
- [ ] `TOTP_KEY`, `WEBHOOK_SECRET_KEY` and the BFF secrets generated, stored in the secrets manager and backed up
- [ ] Three distinct BFF client secrets; the bff's `STUDIO_BFF_SECRET` matches auth's `STUDIO_BFF_SECRET_HASH`
- [ ] OAuth clients registered: `oauthClients list` shows `studio-bff: up to date` (step "Register OAuth clients")
- [ ] DNS and TLS for `https://business.staging.northline.ca` and `https://auth.staging.northline.ca`; ingress routes `/api`, `/bff`, `/oauth2`, `/login` on the Studio host to the bff
- [ ] Studio image deployed with `urls.auth` = `https://auth.staging.northline.ca` (served as `/config.js`; one image for every environment, S-14)
- [ ] Token signing key created in the KMS, `KMS_PROVIDER` / `KMS_KEY_ID` set, workload identity may sign with it; JWK set checked ([key-rotation.md](key-rotation.md)); rotation date in the calendar
- [ ] Stripe keys (test mode) set
- [ ] Point-in-time restore enabled on Postgres; restore tested (S-114)
- [ ] Alerts on readiness, error rate and DLQ depth (S-111–S-113)
