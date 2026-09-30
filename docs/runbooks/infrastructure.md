# Infrastructure runbook — Terraform on AWS, Google Cloud or Azure

Stories S-2 (network, Kubernetes, keys, registry, DNS, storage, secrets) and S-3 (managed data stores). Code and
module contract: [`infra/terraform/README.md`](../../infra/terraform/README.md). This runbook covers what an operator
needs to create an environment, what comes out of it, and how the outputs become the apps' environment variables
([dev](dev.md), [staging](staging.md), [prod](prod.md)).

> **Status (2026-09-29): written, not applied.** No cloud account or credential exists yet. Every root passes
> `terraform validate` and a plan against mocked providers (`terraform test`); nothing has been planned against a
> real account. Expect small fixes on the first real `plan`/`apply` (quotas, API propagation, names already taken).

Pick **one** cloud per environment; everything stays in a Canadian region:

| cloud | regions (validated) | env roots |
|---|---|---|
| AWS | `ca-central-1` (Montréal, default), `ca-west-1` (Calgary, opt-in region) | `infra/terraform/envs/aws/{dev,staging,prod}` |
| Google Cloud | `northamerica-northeast1` (Montréal, default), `northamerica-northeast2` (Toronto) | `infra/terraform/envs/gcp/{dev,staging,prod}` |
| Azure | `canadacentral` (Toronto, default), `canadaeast` (Québec City) | `infra/terraform/envs/azure/{dev,staging,prod}` |

A region outside Canada is rejected by variable validation in the env roots and in every module.

## 1. Prerequisites

Tools on the operator's machine: Terraform 1.9 or newer (tested with 1.16.4), `kubectl`, `jq`, and the cloud's CLI.

### AWS
- **Accounts:** an AWS Organization with one account per environment (at least prod separate from dev/staging).
  Billing alerts on. `ca-west-1` must be enabled in *Account › AWS Regions* before use; `ca-central-1` is on by default.
- **Operator identity:** IAM Identity Center (SSO) user with `AdministratorAccess` in the target account for the
  first apply (the stack creates IAM roles, KMS key policies and EKS access entries). Later, a narrower
  `northline-terraform` role. `aws sso login --profile northline-dev` and `export AWS_PROFILE=northline-dev`.
- **Quotas to check in the region:** Elastic IPs (1 in dev, 3 in staging/prod), NAT gateways, VPCs, EKS clusters.
- **Residency guard (recommended):** a Service Control Policy denying actions outside `ca-central-1`/`ca-west-1`
  (except global services: IAM, Route 53, CloudFront, Organizations, STS).

### Google Cloud
- **Projects:** one project per environment (`northline-dev`, `northline-staging`, `northline-prod`), linked to the
  billing account with the startup credits, plus the project that holds the state bucket (can be prod).
- **Residency guard (recommended):** organization policy `constraints/gcp.resourceLocations` = `in:canada-locations`.
- **Operator identity:** `roles/owner` on the project for the first apply (the stack enables APIs and grants IAM),
  or: Kubernetes Engine Admin, Compute Network Admin, Cloud KMS Admin, Service Account Admin, Project IAM Admin,
  Secret Manager Admin, Storage Admin, Artifact Registry Admin, DNS Administrator, Service Usage Admin,
  Service Networking Admin (+ Cloud SQL Admin, Memorystore Admin, Managed Kafka Admin for S-3).
  `gcloud auth application-default login`.
- **APIs:** the stack enables the ones it uses; enable these two first so Terraform can do that:
  `gcloud services enable serviceusage.googleapis.com cloudresourcemanager.googleapis.com --project <project>`.

### Azure
- **Subscriptions:** one per environment (or one subscription and a resource group per environment); the stack
  creates `rg-northline-<env>`.
- **Residency guard (recommended):** Azure Policy "Allowed locations" = `canadacentral`, `canadaeast` on the
  subscription.
- **Operator identity:** `Owner` on the subscription for the first apply (the stack creates role assignments), or
  `Contributor` + `Role Based Access Control Administrator`. `az login && az account set --subscription <id>`.
- **Resource providers:** registered automatically by the provider; if the tenant forbids it:
  `az provider register -n Microsoft.ContainerService` (and `Microsoft.KeyVault`, `Microsoft.Network`,
  `Microsoft.Storage`, `Microsoft.ContainerRegistry`, `Microsoft.ManagedIdentity`, `Microsoft.DBforPostgreSQL`,
  `Microsoft.Cache`, `Microsoft.EventHub`).
- `kubelogin` for `kubectl` (AKS local accounts are disabled; sign-in is Entra ID).

### Elastic Cloud (search, every cloud)
- **Account:** an Elastic Cloud organization with billing (or the marketplace subscription of the chosen cloud, so it
  draws on that cloud's credits). The deployment is created in the Canadian region of the chosen cloud
  (`aws-ca-central-1`, `gcp-northamerica-northeast1`, `azure-canadacentral`).
- **Operator credential:** an Elastic Cloud API key (*Organization › API keys*, role *Admin* or *Editor* on
  deployments), exported as `EC_API_KEY` wherever `terraform plan/apply` runs. Without it, the `ec` provider fails
  every env root's plan: search is part of the stack.

### Data-store quotas (S-3)
- **AWS:** RDS DB instances, ElastiCache nodes, MSK brokers per account (defaults are enough for one environment;
  check before the second).
- **Google Cloud:** Cloud SQL instances, Memorystore for Valkey nodes, Managed Kafka vCPUs in the region;
  the Private Service Access range from the network module is shared by every Cloud SQL instance of the network.
- **Azure:** Event Hubs Premium processing units and Azure Managed Redis are not offered to every subscription type
  (free/sponsored subscriptions may need a quota request).

## 2. Bootstrap the state bucket (once per cloud account / subscription)

The bootstrap roots keep their own state locally (keep the `terraform.tfstate` file somewhere safe, or import the
resources later). State buckets are versioned, encrypted, private, in Canada, and protected from `destroy`.

```sh
# AWS: S3 bucket northline-tfstate-<account-id> + DynamoDB table northline-tfstate-lock
cd infra/terraform/bootstrap/aws   && terraform init && terraform apply
# Google Cloud: GCS bucket <project>-northline-tfstate
cd infra/terraform/bootstrap/gcp   && terraform init && terraform apply -var project_id=<state-project>
# Azure: rg-northline-tfstate / storage account nltfstate<random> / container tfstate
cd infra/terraform/bootstrap/azure && terraform init && terraform apply -var subscription_id=<id>
terraform output -raw backend_hcl
```

Then, in each env root you use: copy `backend.hcl.example` to `backend.hcl` (git-ignored), fill it from
`backend_hcl` (replace `<env>`), uncomment the block in `backend.tf` (commit that change), and
`terraform init -backend-config=backend.hcl`. AWS locking: `use_lockfile = true` (S3-native, Terraform ≥ 1.10) is
the default; `dynamodb_table` is the classic alternative and the table exists for it (deprecated by Terraform 1.11+
but still supported, and needed by older Terraform/OpenTofu). GCS and Azure Blob lock natively.

## 3. Plan and apply an environment

```sh
cd infra/terraform/envs/<cloud>/<env>
cp terraform.tfvars.example terraform.tfvars      # git-ignored: project_id / subscription_id, admins, API CIDRs
export EC_API_KEY=…                                # Elastic Cloud API key (search); never commit it
terraform init -backend-config=backend.hcl
terraform plan -out tfplan
terraform apply tfplan
```

| variable | meaning |
|---|---|
| `region` | Canadian region (defaults above) |
| `owner` | value of the `owner` tag/label (default `platform`) |
| `admin_principals` | cluster admins: IAM ARNs (AWS), `user:`/`group:` members (Google Cloud), Entra object ids (Azure) |
| `api_allowed_cidrs` | who can reach the Kubernetes API; **required in prod** (office/VPN/CI egress) |
| `bucket_name_suffix` | only if `northline-<env>-uploads` is already taken (bucket names are global) |
| `project_id` / `subscription_id` | Google Cloud / Azure only |

Sizes live in `envs/<cloud>/<env>/main.tf`: dev = smallest, one zone where the service allows it, shared NAT, no
deletion protection; staging = prod topology (three zones, replicas) at smaller sizes; prod = multi-zone HA with
deletion protection. The data stores are sized by the `data_stores` block in the same file (instance types in each
cloud's own names; see § 5). Every resource carries `app=northline`, `env=<env>`, `owner`, `data-residency=ca`,
`managed-by=terraform` (AWS default tags, Google Cloud labels, Azure tags).

Known first-apply hiccups: IAM / Azure role assignments and newly enabled Google APIs take a minute or two to
propagate — if a resource fails with *permission denied* right after its grant was created, run `apply` again.
Google Cloud key rings cannot be deleted, so re-creating an environment with the same name reuses (or must import)
the key ring.

Then fetch cluster credentials: `terraform output -json kubernetes | jq -r .kubeconfig_command | sh`.

## 4. Outputs → the apps' environment variables

Each env root exposes two maps keyed by the **application's variable names** ([README § Variables at a
glance](README.md#variables-at-a-glance)):

- `config_env`: non-secret values → the `northline-infra` ConfigMap.
- `secret_env`: variable name → **reference** of the secret in the cloud's secrets manager (never the value) →
  ExternalSecret (External Secrets Operator, S-6).

```sh
terraform output -json config_env | jq -r 'to_entries[] | "\(.key)=\(.value)"' > infra.env
kubectl -n northline-<env> create configmap northline-infra --from-env-file=infra.env --dry-run=client -o yaml | kubectl apply -f -

# ExternalSecret data entries (S-6 installs External Secrets Operator and the SecretStore):
terraform output -json secret_env | jq '[to_entries[] | {secretKey: .key, remoteRef: {key: .value}}]'

# S-6: everything the Helm chart needs, as one values file (configEnv, workloadIdentities, externalSecrets):
terraform output -json helm_values > northline-<env>-values.json
```

With the chart (S-14/S-6) the ConfigMap and the ExternalSecrets come from `helm_values` — [deploy.md](deploy.md),
[secrets.md](secrets.md); the `kubectl create configmap` line above is only for installs without the chart.

| variable | output | module output | AWS | Google Cloud | Azure |
|---|---|---|---|---|---|
| `STORAGE_PROVIDER` | `config_env` | `storage.storage_provider` | `s3` | `gcs` | `azure` |
| `STORAGE_BUCKET` | `config_env` | `storage.bucket_names["uploads"]` | `northline-<env>-uploads` | `northline-<env>-uploads` | the **container** name `uploads` |
| `STORAGE_REGION` | `config_env` | `storage.storage_region` | region (S3 signing region) | empty (not read) | empty (not read) |
| `STORAGE_ENDPOINT` | `config_env` | `storage.storage_endpoint` | empty (SDK default) | empty (an endpoint means an emulator) | `https://nl<env>st<suffix>.blob.core.windows.net` (**required** by the api on Azure) |
| `STORAGE_ENCRYPTION_KEY` | `config_env` | `storage.storage_encryption_key` | the `data` key ARN (SSE-KMS on every write) | the `data` key name `projects/…/cryptoKeys/data` (CMEK per object) | empty: the storage account is encrypted with the Key Vault `data` key, so no encryption scope is needed |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PATH_STYLE` | — | — | unset: workload identity (`northline-api`) | unset | unset |
| `KMS_PROVIDER` | `config_env` | `kms.kms_provider` | `aws` | `gcp` | `azure` |
| `KMS_KEY_ID` | `config_env` | `kms.key_refs["signing"]`, or `signing_key_ids.active` | key ARN | key **version** `projects/…/cryptoKeys/signing/cryptoKeyVersions/1` | **versioned** key URL `https://<vault>.vault.azure.net/keys/signing/<version>` |
| `KMS_PUBLISHED_KEY_IDS` | `config_env` | `signing_key_ids.published` (comma-joined) | empty outside a rotation | same | same |
| `KMS_ENCRYPTION_KEY_ID` | `config_env` | `kms.key_refs["tokens"]` (S-32) | key ARN | crypto key name `projects/…/cryptoKeys/tokens` | **versioned** key URL `https://<vault>.vault.azure.net/keys/tokens/<version>` (RSA wrap) |
| `SMS_PROVIDER`, `SMS_FROM`, `SMS_REGION` | `config_env` (AWS, only with `sms_origination_identity`) | env root variable | `aws`, the number/pool ARN, region | — (Twilio: set by the operator) | — (Twilio: set by the operator) |
| `DB_URL` | `config_env` | `postgres.db_url` | `jdbc:postgresql://<rds-endpoint>:5432/northline?sslmode=require` | `jdbc:postgresql://<private-ip>:5432/northline?sslmode=require` | `jdbc:postgresql://<server>.postgres.database.azure.com:5432/northline?sslmode=require` |
| `DB_USER` | `config_env` | `postgres.db_user` | `northline_app` (role created by the bootstrap SQL, § 5.1) | same | same |
| `DB_PASSWORD` | `secret_env` | `postgres.db_password_secret_ref` | `northline/<env>/db-app-password` | `northline-<env>-db-app-password` | `db-app-password` |
| `REDIS_HOST` | `config_env` | `cache.redis_host` | ElastiCache primary endpoint | PSC address of the primary endpoint | `<name>.<region>.redis.azure.net` (private endpoint) |
| `REDIS_PORT` | `config_env` | `cache.redis_port` | `6379` | `6379` | `10000` |
| `REDIS_SSL` | `config_env` | `cache.redis_ssl` | `true` | `true` (trust the instance CA, § 5.2) | `true` |
| `REDIS_USERNAME` | — | `cache.redis_username` | empty (default user + AUTH token) | empty | empty (access key) |
| `REDIS_PASSWORD` | `secret_env` | `cache.redis_password_secret_ref` | `northline/<env>/redis-password` (AUTH token) | **none**: Memorystore for Valkey has no static password (§ 5.2) | `redis-password` (primary access key) |
| `KAFKA_BOOTSTRAP` | `config_env` | `kafka.kafka_bootstrap` | `b-1.…:9096,b-2.…:9096` (SASL/SCRAM listeners) | `bootstrap.<cluster>.<region>.managedkafka.<project>.cloud.goog:9092` | `<namespace>.servicebus.windows.net:9093` |
| `KAFKA_SECURITY_PROTOCOL` | `config_env` | `kafka.kafka_security_protocol` | `SASL_SSL` | `SASL_SSL` | `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | `config_env` | `kafka.kafka_sasl_mechanism` | `SCRAM-SHA-512` | `PLAIN` | `PLAIN` |
| `KAFKA_SASL_JAAS_CONFIG` | `secret_env` | `kafka.kafka_sasl_jaas_config_secret_ref` | `northline/<env>/kafka-sasl-jaas-config` (SCRAM user `northline-app`) | `northline-<env>-kafka-sasl-jaas-config` (service-account key) | `kafka-sasl-jaas-config` (Send + Listen connection string) |
| `ES_URIS` | `config_env` | `search.es_uris` | `https://<deployment>.es.ca-central-1.aws.elastic-cloud.com:443` | `https://<deployment>.es.northamerica-northeast1.gcp.elastic-cloud.com:443` | `https://<deployment>.es.canadacentral.azure.elastic-cloud.com:443` |
| `ES_USERNAME` | `config_env` | `search.es_username` | `elastic` (deployment superuser until a least-privilege user exists, § 5.4) | same | same |
| `ES_PASSWORD` | `secret_env` | `search.es_password_secret_ref` | `northline/<env>/es-password` | `northline-<env>-es-password` | `es-password` |
| `TOTP_KEY`, `WEBHOOK_SECRET_KEY`, `STUDIO_BFF_SECRET`, `STUDIO_BFF_SECRET_HASH`, `CONSUMER_BFF_SECRET_HASH`, `CONSOLE_BFF_SECRET_HASH`, `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET`, `GOOGLE_CLIENT_SECRET`, `APPLE_PRIVATE_KEY`, `SMS_AUTH_TOKEN`, `EMAIL_UNSUBSCRIBE_KEY`, `EMAIL_API_KEY`, `SMTP_PASSWORD`, `SHOPIFY_CLIENT_SECRET`, `SQUARE_CLIENT_SECRET`, `SQUARE_WEBHOOK_SIGNATURE_KEY`, `LIGHTSPEED_CLIENT_SECRET` (S-35), `CLOVER_CLIENT_SECRET`, `TOAST_CLIENT_SECRET` (S-36) (Stripe webhook and email secrets since S-6), the S-23 registry keys, `GOOGLE_CALENDAR_CLIENT_SECRET`, `MICROSOFT_CALENDAR_CLIENT_SECRET` (S-32) | `secret_env` | `secrets.secret_refs` | `northline/<env>/<name>` | `northline-<env>-<name>` | `<name>` in vault `nl-<env>-sec-…` |

What Terraform grants for these (least privilege, [object-storage.md](object-storage.md), [key-rotation.md](key-rotation.md)):
`northline-api` gets object read/write/delete on the uploads bucket (AWS: `s3:GetObject/PutObject/DeleteObject` on
`bucket/*` + `s3:ListBucket` on the bucket, and `kms:GenerateDataKey`/`kms:Decrypt` on the `data` key; Google Cloud:
`roles/storage.objectUser` on the bucket; Azure: "Storage Blob Data Contributor" on the container). No presigned-URL
permissions (`signBlob`, "Storage Blob Delegator") until something uses them. `northline-auth` gets sign + read the
public key on the `signing` key only (AWS `kms:Sign`, `kms:GetPublicKey`; Google Cloud `roles/cloudkms.signer` +
`roles/cloudkms.publicKeyViewer`; Azure "Key Vault Crypto User" on the key). The api verifies tokens through the JWK
set; since S-32 it may encrypt and decrypt with the `tokens` key only (AWS `kms:Encrypt`/`Decrypt`/`GenerateDataKey*`;
Google Cloud `roles/cloudkms.cryptoKeyEncrypterDecrypter`; Azure "Key Vault Crypto User" on the key — wrapKey/unwrapKey),
to seal calendar refresh tokens ([calendar-sync.md](calendar-sync.md#the-envelope-key-kms_encryption_key_id)).

Variables the apps need that Terraform does **not** set — add them to the ConfigMap / secrets yourself (values in
the environment runbooks): `SPRING_PROFILES_ACTIVE`, `AUTH_ISSUER`, `AUTH_INTERNAL_URL`, `API_URL`, the `*_ORIGIN`s,
`WEBAUTHN_RP_ID`, `TRUSTED_PROXIES`, the SMS provider (S-8: `SMS_PROVIDER=twilio`, `SMS_FROM`, `SMS_ACCOUNT_ID`,
`SMS_VOICE_FROM` — only `SMS_AUTH_TOKEN` has a secret in `secret_env`; on AWS, End User Messaging can be wired instead
with `sms_origination_identity`), `OAUTH_CLIENTS_SYNC_ON_STARTUP` (S-122; default `true`), and the Google/Apple ids
(S-18: `GOOGLE_CLIENT_ID`, `APPLE_CLIENT_ID`, `APPLE_TEAM_ID`, `APPLE_KEY_ID`; the two secrets are in `secret_env`). `CONSUMER_BFF_SECRET_HASH` / `CONSOLE_BFF_SECRET_HASH` are optional since S-122: leave their secrets
without a value until those BFFs exist (map them in the ExternalSecret only once set).

### Signing key rotation with Terraform (S-7)

The procedure is [key-rotation.md § Rotating — cloud providers](key-rotation.md#rotating--cloud-providers); Terraform
only carries the two variables, through `signing_key_ids` in the env root's `terraform.tfvars`:

```hcl
signing_key_ids = { active = "<old id>", published = ["<new id>"] }   # step 2: publish
signing_key_ids = { active = "<new id>", published = ["<old id>"] }   # step 3: switch
signing_key_ids = { active = "<new id>" }                              # step 4: retire
```

each followed by `terraform apply`, the ConfigMap refresh above and a rolling restart of northline-auth.
Google Cloud and Azure rotate by adding a **version** of the same key (`gcloud kms keys versions create`,
`az keyvault key rotate`): the key-level grants cover it. On Azure, pin `active` to the current versioned URL
**before** creating the new version — the key's Terraform `id` follows the newest version, so an unpinned apply would
switch `KMS_KEY_ID` without the publish step. AWS rotates by creating a new **key**: until the stack manages several
signing keys, create it by hand and give the auth role `kms:Sign` + `kms:GetPublicKey` on its ARN (key-rotation.md).

The data-store secrets (`DB_PASSWORD`, `REDIS_PASSWORD`, `KAFKA_SASL_JAAS_CONFIG`, `ES_PASSWORD`, plus the admin
ones in § 5) are **generated by Terraform** and written with their values, so they pass through the state: keep
state only in the bootstrapped bucket. The app secrets are created **empty** (AWS, Google Cloud) or not at all
(Azure: Key Vault has no empty secrets); Terraform never sees their values. Set them as the environment runbook describes (`openssl rand -base64 32`, the
bcrypt hash, the Stripe keys):

```sh
aws secretsmanager put-secret-value --secret-id northline/dev/totp-key --secret-string "$(openssl rand -base64 32)"
printf %s "$(openssl rand -base64 32)" | gcloud secrets versions add northline-dev-totp-key --data-file=- --project northline-dev
az keyvault secret set --vault-name <nl-dev-sec-…> --name totp-key --value "$(openssl rand -base64 32)"
```

Other outputs, for the stories that consume them:

| output | used by |
|---|---|
| `kubernetes.workload_identities.<name>.service_account_annotations` / `.pod_labels` | Helm charts (S-14): ServiceAccounts `northline-api`, `-auth`, `-bff`, `-worker`, and `external-secrets`, `external-dns`, `cert-manager` (S-17) |
| `secrets_provider`, `secret_store` | the External Secrets `SecretStore` (S-6): provider `aws` / `gcpsm` / `azurekv` |
| `helm_values` (env roots, S-6) | `deploy/helm/northline` values: `configEnv`, `workloadIdentities` (api, auth, bff, worker), `externalSecrets` (provider, region / projectID / vaultUrl from the stack's `external_secrets` output, `remoteKeys` = `secret_env` without empty entries), `edge.certManager.issuer.dns01` (S-17: the cloud's cert-manager DNS-01 solver for the zone) — goes to `deploy/argocd/envs/<env>/infra.yaml` |
| `gitops_addon_values` (env roots, S-17) | the Argo CD add-ons' per-environment values (`deploy/argocd/envs/<env>/addons/<name>.yaml`): the workload identity of `external-secrets`, `external-dns`, `cert-manager`; external-dns's provider, zone filter (`domainFilters`), zone id / project / `azure.json` and `txtOwnerId` ([edge.md](edge.md#setting-it-up-per-environment)) |
| `registry.repository_urls`, `registry.login_command` | image builds and pushes (S-14) |
| `dns.name_servers` | delegation (S-17, [edge.md § DNS delegation](edge.md#dns-delegation)): NS records for `dev.northline.ca` / `staging.northline.ca` in the `northline.ca` zone; the prod zone's servers at the registrar. Only the `external-dns` and `cert-manager` identities may change records in the zone (`module.dns` `record_writers`) |
| `network.cloud.nat_public_ips` | allow-lists that need the cluster's egress IPs (Elastic Cloud traffic filters, partners) |
| `kms.key_ids["data"]` | encryption at rest of Kubernetes Secrets, buckets, registry, secrets |
| `kms.key_refs["signing"]` | the signing key id Terraform created (`KMS_KEY_ID` unless `signing_key_ids.active` overrides it) |
| `data_stores.postgres.admin_secret_ref`, `.cloud.admin_username` | the bootstrap SQL (§ 5.1), S-16 migration job, S-114 backups |
| `data_stores.cache.cloud` | Google Cloud: `server_ca_certs` to trust (§ 5.2); AWS: reader endpoint |
| `data_stores.kafka.replication_factor`, `.topic_policy`, `.cloud` | topic creation (§ 5.3, S-25); Azure: `admin_jaas_secret_name`, `event_hubs` (the event hubs created from the catalogue) |
| `data_stores.search.cloud.deployment_id` | Elastic Cloud console / API, least-privilege user (§ 5.4) |

## 5. Data stores after the first apply (S-3)

Everything below is private to the network: PostgreSQL, Valkey and Kafka have no public endpoint, so run the
one-off commands **from inside the cluster** (a throw-away pod in `northline-<env>`), never by opening them to the
internet. Sizes are the `data_stores` block of `envs/<cloud>/<env>/main.tf`:

| | dev | staging | prod |
|---|---|---|---|
| PostgreSQL 17 | smallest burstable, 1 zone, 7-day backups | HA (standby in another zone), 7 days | HA, 35-day backups + PITR, deletion protection, final snapshot; Azure: geo-redundant backup to the paired Canadian region |
| Valkey / Redis | 1 node | primary + 1 replica, automatic failover | primary + replicas across zones; AWS/Google Cloud keep RDB snapshots |
| Kafka | MSK 2 × `kafka.t3.small` / Managed Kafka 3 vCPU / Event Hubs Premium 1 PU | MSK 3 brokers / 3 vCPU / 1 PU | MSK 3 × `kafka.m7g.large` / 6 vCPU / 2 PU; RF 3, `min.insync.replicas=2` |
| Elasticsearch 9 | 2 GB, 1 zone | 2 GB × 2 zones | 4 GB × 2 zones |

### 5.1 PostgreSQL: app role and extensions (once per environment)

Terraform creates the instance, the `northline` database (RDS: at creation; Cloud SQL / Azure: a database resource)
and the password of the app role, but **not the role itself** (no provider can reach the private database from the
operator's machine). As the admin role:

```sh
# admin password: AWS = RDS-managed secret (JSON), Google Cloud / Azure = the db-admin-password secret
terraform output -json data_stores | jq .postgres        # host, admin_secret_ref, cloud.admin_username
aws secretsmanager get-secret-value --secret-id "<admin_secret_ref>" --query SecretString --output text | jq -r .password
gcloud secrets versions access latest --secret northline-<env>-db-admin-password --project <project>
az keyvault secret show --vault-name <nl-<env>-sec-…> --name db-admin-password --query value -o tsv
# the app password, same way: northline/<env>/db-app-password · northline-<env>-db-app-password · db-app-password

kubectl -n northline-<env> run psql --rm -it --restart=Never --image=postgres:17 -- \
  psql "host=<db host> port=5432 dbname=northline user=<admin user> sslmode=require"
```

```sql
create role northline_app login password '<db-app-password>';
grant northline_app to current_user;            -- PG 16+: lets the admin hand the database over
alter database northline owner to northline_app; -- Flyway (as DB_USER) creates the module schemas
\c northline
create extension if not exists postgis;
create extension if not exists citext;
create extension if not exists pgcrypto;
```

Admin users: `northline_admin` (AWS, Azure), `postgres` (Google Cloud, member of `cloudsqlsuperuser`). On Azure the
three extensions are already allow-listed (`azure.extensions`). If the app password is rotated in the secrets store
later, run `alter role northline_app password '…'` with the new value.

### 5.2 Valkey / Redis

- The apps use one endpoint and no cluster client: cluster mode is disabled everywhere (Azure Managed Redis:
  `EnterpriseCluster` policy, which presents a single endpoint). TLS is always on (`REDIS_SSL=true`).
- **AWS / Azure:** password authentication (AUTH token / primary access key) from `secret_env.REDIS_PASSWORD`.
- **Google Cloud (open item):** Memorystore for **Valkey** has no static password: it offers IAM authentication (a
  short-lived token the client must refresh) or none. The module uses *none*, so access is limited to the VPC through
  Private Service Connect, and `REDIS_PASSWORD` stays empty. Its TLS certificate is signed by a **per-instance CA**
  that the JVM does not trust: import `data_stores.cache.cloud.server_ca_certs` into a truststore mounted in the pods
  (`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=…`), which belongs to the Helm charts (S-14). *Since S-14:* `values-gcp.yaml` trusts it through a Spring Boot SSL bundle (`SPRING_DATA_REDIS_SSL_BUNDLE=redis` + the PEM from ConfigMap `northline-redis-ca`, [deploy.md](deploy.md)) — not yet tried against a real instance. Before that, Google Cloud
  cannot serve Valkey to the apps as they are.

### 5.3 Kafka: topics and credentials

**One catalogue, three readers (S-25).** `deploy/kafka/topics.yaml` lists every topic (`<module>.<aggregate>`, key =
aggregate id), the defaults (6 partitions, 7 days, `delete`), the DLQ policy (`<topic>.dlq`: 1 partition, 30 days)
and the worker's consumer groups with their retry delays (`<topic>.<group>.retry-<n>`, one per delay, the source
topic's partitions, 1 day). From it:

| reader | where | what it does |
|---|---|---|
| **provisioning Job** `northline-kafka-topics-<hash>` | Helm pre-install/pre-upgrade hook (Argo CD PreSync), worker image, `TopicsCommand` | Kafka admin API with the app's credentials: `apply` creates missing topics, sets drifted retention / cleanup policy / `min.insync.replicas` back, reports partition drift and unmanaged topics. Never deletes, never changes partition counts. |
| **Terraform** (Azure only) | `modules/kafka/catalogue` → `modules/kafka/azure` `azurerm_eventhub.topic` | one event hub per topic (partitions, retention in hours, cleanup policy), `prevent_destroy` |
| **`scripts/topics.sh`** | docker compose `events` profile, your own Kafka | creates what is missing with the Kafka CLI, prints `DRIFT` lines (`KAFKA_TOPICS_STRICT=1`: exit 3) |

Tests keep them together: the api fails when an `@Externalized` topic is missing from the catalogue
(`ExternalizedTopicsCatalogueTest`); the worker fails when `topics.sh --list` and the Java derivation differ, when a
`@RetryableTopic` listener's policy differs from its consumer entry, or when the catalogue outgrows one Event Hubs
Premium processing unit (100 event hubs) (`TopicCatalogueTest`); `TopicProvisionerTest` runs both the script and the
provisioner against Kafka 4 in Testcontainers; `modules/kafka/catalogue/tests` and the Azure env test check the
Terraform derivation.

Per cloud:

| | who creates topics | credential | `kafkaTopics` (chart) | notes |
|---|---|---|---|---|
| AWS MSK | the Job (`apply`) | `kafka-sasl-jaas-config` (the app's SCRAM user; no ACLs, `allow.everyone.if.no.acl.found=true`) | `command: apply`; prod `minInsyncReplicas: "2"` | replication = the cluster's `default.replication.factor` (3; 2 on the two-broker dev) unless `kafkaTopics.replicationFactor` |
| Google Cloud Managed Kafka | the Job (`apply`) | `kafka-sasl-jaas-config` (service account with `roles/managedkafka.client`) | `command: apply` | replication 3 (service default). The key needs `iam.disableServiceAccountKeyCreation` **not** enforced on the project — new organizations enforce it by default, and the apply fails on `google_service_account_key` until the owner exempts the project |
| Azure Event Hubs | **Terraform** (`terraform apply` of the env root) | — (ARM); the Job reads with the app's Send + Listen key | `command: plan` (report only) | Premium: 100 event hubs per PU, retention ≤ 90 days; replication is the service's. The `kafka-admin-jaas-config` (Manage) secret stays for manual repairs only |

**Changing the catalogue.** Add a topic when a module gets a new `@Externalized` aggregate (the api test tells you),
and a consumer entry with its `retryDelaysSeconds` when the worker gets a listener (the worker test tells you). The
next deploy's Job (or `terraform apply` on Azure) creates them before any pod starts. Changing retention or cleanup
policy: edit the entry; `apply` corrects it everywhere. **Never** remove or rename an entry and expect a delete:
nothing deletes topics; a retired topic is removed by hand once no producer, consumer or DLQ record needs it.
**Partitions** are only ever raised by hand, knowingly: more partitions remap keys, so per-aggregate ordering breaks
for keys in flight — drain producers first, then `kafka-topics.sh --alter --partitions N` (Event Hubs Premium:
change `partition_count`), then update the catalogue so the drift report is clean again.

**Running it by hand** (from a pod in the cluster, or anywhere that reaches Kafka):

```sh
# the Job's logs after a deploy
kubectl -n northline-<env> logs $(kubectl -n northline-<env> get jobs -l app.kubernetes.io/component=kafka-topics -o name --sort-by=.metadata.creationTimestamp | tail -1)
# the same command, locally or from a debug pod: KAFKA_* as in the worker's environment
cd server && KAFKA_BOOTSTRAP=… KAFKA_SECURITY_PROTOCOL=SASL_SSL KAFKA_SASL_MECHANISM=… KAFKA_SASL_JAAS_CONFIG='…' \
  ./gradlew :worker:kafkaTopics --args='plan'        # or verify (exit 3 on drift) / apply
java -cp @/app/jib-classpath-file ca.northline.worker.topics.TopicsCommand plan   # inside the worker image
```

Output lines: `CREATED`, `CORRECTED` (config set back), `MISSING` / `DRIFT` (plan/verify, or partition drift after
apply), `UNMANAGED` (exists, not in the catalogue — left alone), `UNREAD` (the service doesn't return that topic's
configs, e.g. Event Hubs with a Send/Listen key), then a summary. With `scripts/topics.sh` against a cloud cluster
(repairs only), put the SASL settings in a `client.properties` and pass
`KAFKA_TOPICS_CMD='kafka-topics.sh --command-config client.properties' KAFKA_TOPICS_BOOTSTRAP=<bootstrap>
KAFKA_REPLICATION_FACTOR=<data_stores.kafka.replication_factor>`.

**Tearing down an Azure environment:** the event hubs carry `prevent_destroy`, so first
`terraform state rm 'module.northline.module.kafka.azurerm_eventhub.topic'`, then destroy (the namespace takes the
event hubs with it).

**Alerts:** DLQ records, consumer lag and replay: [events.md](events.md). ACLs per app are not set: MSK runs
with `allow.everyone.if.no.acl.found=true`, and Managed Kafka / Event Hubs authorize per credential.

### 5.4 Elasticsearch (Elastic Cloud)

- The deployment accepts traffic only from the cluster's NAT egress IPs (IP traffic filter from
  `network.cloud.nat_public_ips`). To call it from a laptop, add a temporary rule in the Elastic Cloud console.
- `ES_USERNAME` is the deployment's `elastic` superuser for now. Before prod data, create a least-privilege
  `northline_app` user/role (index privileges on the Northline indices only), store its password in the `es-password`
  secret and set `ES_USERNAME`. The role's privileges (the `listings_*` indices and the synonym sets):
  [search.md § 5](search.md#5-elastic-cloud-access-least-privilege).
- Amazon OpenSearch Service is **not** an option for the apps as they are (Elasticsearch 9 Java client; README § Why
  Elastic Cloud). ECK on the cluster is the self-managed alternative and would replace `modules/search`.

## 6. Cost notes for a small dev environment

Rough list prices per month before credits (USD, 730 h, September 2026 — check each cloud's calculator). Foundation
(S-2) first, then the data stores (S-3).

| | AWS (`ca-central-1`) | Google Cloud (`northamerica-northeast1`) | Azure (`canadacentral`) |
|---|---|---|---|
| Kubernetes control plane | EKS ≈ 73 | GKE: the free tier covers one zonal cluster ≈ 0 | AKS Free tier ≈ 0 |
| Nodes (dev: 1 node) | 1 × t3.large ≈ 70 | 1 × e2-standard-4 **spot** ≈ 35 | 1 × D4s_v5 ≈ 160 |
| NAT + public IP | NAT gateway ≈ 35 + data | Cloud NAT + 1 static IP ≈ 10 | NAT gateway ≈ 35 + IP ≈ 4 |
| Keys, secrets, DNS, registry | KMS 2 keys ≈ 2, Secrets Manager 16 × 0.40 ≈ 7, Route 53 ≈ 1, ECR storage | Cloud KMS ≈ 1, Secret Manager ≈ 1, Cloud DNS ≈ 1 | Key Vault ≈ 1, ACR Standard ≈ 20, DNS ≈ 1 |
| **≈ foundation** | **≈ 185** | **≈ 50** | **≈ 220** |
| PostgreSQL 17 | RDS `db.t4g.micro` + 20 GB gp3 ≈ 18 | Cloud SQL 1 vCPU / 3.75 GB + 20 GB SSD ≈ 55 | Flexible Server `B1ms` + 32 GB ≈ 20 |
| Valkey / Redis | ElastiCache `cache.t4g.micro` ≈ 10 | Memorystore for Valkey shared-core nano ≈ 25 | Azure Managed Redis `Balanced_B0` ≈ 40 |
| Kafka | MSK 2 × `kafka.t3.small` + 40 GB ≈ 70 | Managed Kafka 3 vCPU / 12 GiB + 100 GiB ≈ 280 | Event Hubs **Premium** 1 PU ≈ 730 |
| Elasticsearch | Elastic Cloud 2 GB, 1 zone ≈ 100 | same ≈ 100 | same ≈ 100 |
| **≈ total with data stores** | **≈ 385** | **≈ 510** | **≈ 1 110** |

Kafka dominates on Google Cloud and Azure. Event Hubs Premium is chosen because Standard allows only 10 event hubs
per namespace and Northline needs about 50 (topics + `.dlq`); for a cheaper dev on Azure, the options are Confluent
Cloud (Azure `canadacentral`) or dev on AWS. Managed Kafka's floor is 3 vCPUs.

To spend less in dev: scale node pools to zero out of hours (`min_count = 0` in `envs/<cloud>/dev/main.tf`, then
scale the pool; the cluster autoscaler brings it back), use spot nodes (`spot = true`; already on in Google Cloud
dev), keep dev on the cloud with the largest credit balance, stop RDS / Cloud SQL / Flexible Server out of hours
(each restarts on its own after 7 days on AWS and Azure), and destroy dev when idle (§ 7).

## 7. Teardown

```sh
cd infra/terraform/envs/<cloud>/<env> && terraform destroy
```

- **prod** has `deletion_protection = true` (EKS/GKE deletion protection, an Azure resource lock on AKS, longest
  key/secret recovery windows): set it to `false` in `main.tf`, apply, then destroy. Never destroy prod without
  an exported backup (S-114).
- Things that outlive a destroy: **AWS** KMS keys wait 7 days (30 in prod) before deletion and Secrets Manager
  secrets 7 days (30) — re-creating the same environment within that window fails on the secret names until they
  are restored or `aws secretsmanager delete-secret --force-delete-without-recovery`; **Google Cloud** key rings are
  permanent and key versions are destroyed after 24 h (30 days in prod), enabled APIs stay on; **Azure** Key Vaults
  are soft-deleted (the keys vault always has purge protection, so its random name is not reused), the resource
  group is deleted with the environment.
- Data stores (S-3): with `deletion_protection = true`, RDS, Cloud SQL and Memorystore refuse deletion, Azure
  PostgreSQL has a `CanNotDelete` lock, and RDS / ElastiCache take a final snapshot (`<name>-final`; delete it by
  hand when no longer needed: the next destroy of a re-created prod would reuse the name and fail). Cloud SQL
  instance names are blocked for about a week after deletion (the module adds a random suffix). **The Elastic Cloud deployment and the MSK cluster
  have no deletion protection**: a prod `destroy` deletes them, so snapshot Elasticsearch first (or rebuild the
  index from Postgres, the source of truth).
- The state buckets (bootstrap) have `prevent_destroy`; remove it deliberately if an account is closed.
