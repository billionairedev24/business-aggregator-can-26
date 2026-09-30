# Northline infrastructure (Terraform)

Stories **S-2** (modules: one interface, AWS / Google Cloud / Azure implementations) and **S-3** (managed data
stores: PostgreSQL 17 + PostGIS, Valkey/Redis, Kafka, Elasticsearch). Everything lives in a Canadian region.
**Nothing has been applied yet**: there are no cloud accounts or credentials. Every root passes `terraform validate`
and a full `terraform plan` against mocked providers (`terraform test`), so the first real plan needs only
credentials (plus `EC_API_KEY` for Elastic Cloud) and a state bucket.

How to operate it (accounts, bootstrap, plan/apply, outputs → environment variables, cost, teardown):
[`docs/runbooks/infrastructure.md`](../../docs/runbooks/infrastructure.md).

## Layout

```
infra/terraform/
├── modules/<capability>/{aws,gcp,azure}   one contract per capability, three implementations
│   ├── network        VPC / VPC network / VNet, subnets for nodes and data services, NAT
│   ├── kubernetes     EKS / GKE / AKS, node pools, IRSA / GKE Workload Identity / Azure Workload Identity
│   ├── kms            AWS KMS / Cloud KMS / Key Vault keys: "data" (envelope encryption) and "signing" (ES256)
│   ├── registry       ECR / Artifact Registry / ACR
│   ├── dns            Route 53 / Cloud DNS / Azure DNS public zone
│   ├── storage        S3 / Cloud Storage / Blob Storage buckets (uploads)
│   ├── secrets        Secrets Manager / Secret Manager / Key Vault (secrets), read by External Secrets Operator
│   ├── postgres       RDS / Cloud SQL / Flexible Server — PostgreSQL 17 + PostGIS (S-3)
│   ├── cache          ElastiCache for Valkey / Memorystore for Valkey / Azure Managed Redis (S-3)
│   ├── kafka          MSK / Managed Service for Apache Kafka / Event Hubs Kafka endpoint (S-3)
│   └── search         Elastic Cloud (elastic/ec provider) in the cloud's Canadian region (S-3);
│                      search/elastic-cloud is the shared implementation
├── stacks/{aws,gcp,azure}                  composes the modules for one environment (identical for dev/staging/prod)
├── envs/{aws,gcp,azure}/{dev,staging,prod} root modules: providers, backend, sizes; `terraform apply` runs here
│   └── tests/plan.tftest.hcl               offline plan with mocked providers + region guard
├── bootstrap/{aws,gcp,azure}               one-off roots that create the remote-state bucket
└── scripts/
    ├── validate.sh                          fmt -check, contract check, validate + test for every root (CI runs this)
    └── check-contract.sh                    the three implementations of each capability share variables and outputs
```

Pick **one** cloud per environment (the runbooks' rule). Every environment exists for every cloud, so moving an
environment to another cloud is a matter of applying the other env root and switching the variables.

## Module contract

Every implementation of a capability declares **exactly the same variables** (same names, types, defaults and
descriptions) and **the same output names**; `scripts/check-contract.sh` enforces it. Cloud-specific values stay
inside the implementation, or go out through the `cloud` output (an object whose shape differs per cloud).

Shared input of every module:

```hcl
context = {
  name                = "northline-dev"   # resource prefix
  environment         = "dev"             # dev | staging | prod
  region              = "ca-central-1"    # validated: Canadian regions of that cloud only
  tags                = { app = "northline", env = "dev", owner = "platform", "data-residency" = "ca", "managed-by" = "terraform" }
  project_id          = "…"               # Google Cloud only
  resource_group_name = "…"               # Azure only
}
```

Principals (who may use a key, read a secret, write a bucket, pull an image) are always passed as
`{ label => principal }` maps with static labels, where the principal is an IAM role ARN (AWS), an IAM member such
as `serviceAccount:…` (Google Cloud) or an Entra object id (Azure) — exactly what `kubernetes.workload_identities[*].principal`
returns. Customer-managed keys are passed as `kms_key = { id = … }` (an object, so "is encryption on" is known at plan
time before the key exists).

| capability | inputs (besides `context`) | outputs (besides `cloud`) |
|---|---|---|
| network | `cidr`, `zone_count`, `high_availability_nat` | `network_id`, `network_name`, `cidr`, `cluster_subnet_ids`, `data_subnet_ids` |
| kubernetes | `network_id`, `subnet_ids`, `kubernetes_version`, `node_pools`, `kms_key`, `api_allowed_cidrs`, `admin_principals`, `workload_identities`, `deletion_protection` | `cluster_name`, `cluster_endpoint`, `cluster_ca_certificate`, `oidc_issuer_url`, `node_identity`, `workload_identities`, `kubeconfig_command` |
| kms | `keys`, `key_users`, `deletion_protection` | `kms_provider`, `key_ids`, `key_refs` |
| registry | `repositories`, `kms_key`, `readers`, `keep_images` | `registry_url`, `repository_urls` |
| dns | `zone_name`, `record_writers` | `zone_id`, `zone_name`, `name_servers`, `cert_manager_dns01`, `external_dns` |
| storage | `buckets`, `name_suffix`, `kms_key`, `writers`, `force_destroy` | `storage_provider`, `bucket_names`, `storage_region`, `storage_endpoint`, `storage_encryption_key` |
| secrets | `secret_names`, `readers`, `kms_key`, `deletion_protection` | `secrets_provider`, `store`, `secret_refs` |
| postgres | `network_id`, `subnet_ids`, `allowed_cidrs`, `kms_key`, `secret_store`, `deletion_protection`, `instance_size`, `storage_gb`, `high_availability`, `backup_retention_days`, `database_name`, `app_user`, `postgres_version` | `db_host`, `db_port`, `db_name`, `db_user`, `db_url`, `db_password_secret_ref`, `admin_secret_ref` |
| cache | `network_id`, `subnet_ids`, `allowed_cidrs`, `kms_key`, `secret_store`, `deletion_protection`, `node_size`, `replicas` | `redis_host`, `redis_port`, `redis_ssl`, `redis_username`, `redis_password_secret_ref` |
| kafka | `network_id`, `subnet_ids`, `allowed_cidrs`, `kms_key`, `secret_store`, `deletion_protection`, `tier`, `capacity`, `storage_gb` | `kafka_bootstrap`, `kafka_security_protocol`, `kafka_sasl_mechanism`, `kafka_sasl_jaas_config_secret_ref`, `kafka_replication_factor`, `kafka_topic_policy` |
| search | `network_id`, `subnet_ids`, `allowed_cidrs`, `kms_key`, `secret_store`, `deletion_protection`, `size`, `zone_count`, `elastic_version` | `es_uris`, `es_username`, `es_password_secret_ref` |

The data-store modules generate their credentials (random passwords, SCRAM users, access keys) and write them to the
environment's secrets store (`secret_store = module.secrets.store`), so External Secrets finds every secret under
one prefix. Those values do pass through Terraform state: keep state in the bootstrapped, encrypted, access-controlled
bucket only.

Where a cloud has no use for an input it says so in `main.tf` (`unused_contract_inputs`), for example `zone_count`
on Google Cloud and Azure (regional subnets) or `kms_key` on ACR (customer-managed keys need Premium).

### Per-cloud choices worth knowing

| | AWS | Google Cloud | Azure |
|---|---|---|---|
| network | VPC; public, private (nodes) and isolated data subnets per zone; NAT per zone or shared; S3 gateway endpoint; flow logs outside dev | custom VPC; regional node subnet with `pods`/`services` ranges; data subnet; Cloud NAT with static IPs; Private Service Access (Cloud SQL) and a PSC policy (Memorystore) | VNet; `aks`, `endpoints` and a `postgres` subnet delegated to Flexible Server; NAT gateway with static IP |
| Kubernetes | EKS, API auth mode, secrets encrypted with KMS, managed node groups (AL2023), IRSA, core add-ons incl. EBS CSI | GKE Standard, zonal in dev / regional otherwise, private nodes, Dataplane V2, Workload Identity, secrets encrypted with Cloud KMS | AKS, CNI overlay + Cilium, Entra RBAC (local accounts off), Workload Identity, KMS etcd encryption, user-assigned kubelet identity |
| workload identity → ServiceAccount | `eks.amazonaws.com/role-arn` | `iam.gke.io/gcp-service-account` | `azure.workload.identity/client-id` + pod label `azure.workload.identity/use: "true"` |
| signing key (`KMS_KEY_ID`, S-7) | key ARN, `ECC_NIST_P256` | key **version** name `…/cryptoKeys/signing/cryptoKeyVersions/1`, `EC_SIGN_P256_SHA256` (HSM in prod) | **versioned** key URL, EC P-256 (HSM in prod) |
| uploads (`STORAGE_*`, S-10) | bucket `northline-<env>-uploads`, SSE-KMS with the `data` key (`STORAGE_ENCRYPTION_KEY` = its ARN) | bucket `northline-<env>-uploads`, CMEK `data` (`STORAGE_ENCRYPTION_KEY` = its name) | container `uploads` (`STORAGE_BUCKET`) in account `nl<env>st<suffix>`, `STORAGE_ENDPOINT` = its blob endpoint; account-level CMK, so `STORAGE_ENCRYPTION_KEY` stays empty |
| secrets | `northline/<env>/<name>`, created empty | `northline-<env>-<name>`, user-managed replication in the region only, created empty | one vault per environment; Key Vault has no empty secrets, so the operator creates them |
| PostgreSQL 17 | RDS in the isolated data subnets, SG limited to the VPC, `rds.force_ssl`, gp3 + KMS, PITR, Multi-AZ when HA, master password managed by RDS | Cloud SQL Enterprise, private IP only (Private Service Access), `ENCRYPTED_ONLY`, CMEK, backups pinned to the region, PITR, REGIONAL when HA | Flexible Server in the delegated subnet + private DNS zone, `azure.extensions=POSTGIS,CITEXT,PGCRYPTO,PG_STAT_STATEMENTS`, zone-redundant HA, geo-redundant backup in prod (paired region is Canadian) |
| Valkey / Redis | ElastiCache for Valkey 8, cluster mode off, TLS + AUTH token, Multi-AZ failover with replicas | Memorystore for Valkey 8, cluster mode off, PSC endpoint, TLS (server CA to trust), **no password** (IAM auth only; the apps have no IAM client yet) | Azure Managed Redis, `EnterpriseCluster` single endpoint, TLS on port 10000, access key, private endpoint |
| Kafka | MSK 3.9 (KRaft), SASL/SCRAM-SHA-512 on 9096, `auto.create.topics.enable=false`, RF 3 (2 in dev) | Managed Kafka (vCPU-sized, min 3), SASL/PLAIN on 9092 with a service-account key, PSC | Event Hubs **Premium** (Standard caps at 10 event hubs; Northline has ~50 with `.dlq`), SASL/PLAIN with `$ConnectionString`, private endpoint; topics need the Manage right to create |
| Elasticsearch 9 | Elastic Cloud `aws-ca-central-1` | Elastic Cloud `gcp-northamerica-northeast1` | Elastic Cloud `azure-canadacentral` |

### Kafka differences that matter

- **Topic auto-creation:** never relied upon. MSK has it switched off in its configuration; Managed Kafka and Event
  Hubs don't let us set it. Every topic of `deploy/kafka/topics.yaml` (with its `.dlq` and the consumers' retry
  topics) is created before the apps start (S-25; `data_stores.kafka.topic_policy` says how per cloud): on MSK and
  Managed Kafka by the chart's provisioning Job (Kafka admin API); on Event Hubs by this Terraform —
  `modules/kafka/catalogue` reads the same file and `modules/kafka/azure` creates one `azurerm_eventhub` per topic
  (`prevent_destroy`), so no pod needs the *Manage* right (the `kafka-admin-jaas-config` secret stays for repairs).
  The kafka modules share a `topics` input for this; AWS and Google Cloud ignore it.
- **SASL:** MSK = `SCRAM-SHA-512` (credentials in a Secrets Manager secret named `AmazonMSK_*`, encrypted with the
  customer-managed key); Google Cloud = `PLAIN` with username = service-account email and password = its base64 key;
  Event Hubs = `PLAIN` with username `$ConnectionString` and the connection string as password. All three use
  `SASL_SSL`; the apps receive the complete JAAS line in `KAFKA_SASL_JAAS_CONFIG`. IAM/OAUTHBEARER options (MSK IAM,
  Google's login handler, Entra ID) would remove the static credential but need client libraries the apps don't
  have yet.

### Why Elastic Cloud and not Amazon OpenSearch Service

The apps use the **Elasticsearch 9** Java client (`co.elastic.clients`). OpenSearch is a fork of Elasticsearch 7.10:
the ES 8/9 client refuses to talk to it (product check on the `X-Elastic-Product` header), and the APIs have diverged
since (vector/kNN, some query and mapping options, security APIs). Moving to OpenSearch would mean the OpenSearch Java
client and a re-test of every index mapping and query in the search projection, so Elastic Cloud (in the same
cloud's Canadian region, through the `elastic/ec` provider) is the portable default on all three clouds. ECK on the
cluster is the self-managed alternative. The deployment is reachable over HTTPS and, by default, only from the
cluster's NAT egress IPs (IP traffic filter); PrivateLink / Private Service Connect / Private Link filters are later
work.

## Workload identities

The stacks create one cloud identity per Kubernetes service account below (namespace `northline-<env>`), and grant:

| name | service account | grants |
|---|---|---|
| `api` | `northline-api` | uploads bucket: object read/write/delete + list (AWS; plus the `data` key for SSE-KMS), `roles/storage.objectUser` (Google Cloud), "Storage Blob Data Contributor" on the container (Azure) — docs/runbooks/object-storage.md |
| `auth` | `northline-auth` | signing key: sign + get public key only (AWS `kms:Sign`/`kms:GetPublicKey`, Google Cloud `roles/cloudkms.signer` + `publicKeyViewer`, Azure "Key Vault Crypto User" on the key); AWS with `sms_origination_identity`: `sms-voice:SendTextMessage`/`SendVoiceMessage` on that number (S-8) |
| `bff` | `northline-bff` | — |
| `worker` | `northline-worker` | — |
| `external-secrets` | `external-secrets/external-secrets` | read every secret of the environment |
| `external-dns` | `external-dns/external-dns` | change records in the environment's DNS zone only (S-17): Route 53 `ChangeResourceRecordSets` on the zone + zone listing, Cloud DNS `roles/dns.admin` on the zone + `roles/dns.reader` on the project, Azure "DNS Zone Contributor" on the zone |
| `cert-manager` | `cert-manager/cert-manager` | the same, for DNS-01 challenges (TXT records) when the Issuer uses DNS-01 (S-17) |

The api needs no KMS access (it verifies tokens through the JWK set), so only `auth` is a user of the signing key.
Rotation of the signing key goes through the env root variable `signing_key_ids` (`active` → `KMS_KEY_ID`,
`published` → `KMS_PUBLISHED_KEY_IDS`; docs/runbooks/infrastructure.md § 4).

The Helm charts (S-14) put `kubernetes.workload_identities[<name>].service_account_annotations` on the
ServiceAccounts and `pod_labels` on the pods; the apps then need no static cloud credentials
(`STORAGE_ACCESS_KEY`/`STORAGE_SECRET_KEY` stay empty).

Every env root also outputs `helm_values` (S-6): `configEnv`, the four app `workloadIdentities` and `externalSecrets`
(store settings from the stack's `external_secrets` output + `remoteKeys` = `secret_env`) and `edge` (S-17: the
cert-manager DNS-01 solver) — one values file for `deploy/helm/northline` (docs/runbooks/deploy.md,
docs/runbooks/secrets.md) — and `gitops_addon_values` (S-17): the add-ons' identities and external-dns's zone
settings (docs/runbooks/edge.md).

## Checks

```sh
cd infra/terraform
scripts/validate.sh            # or: scripts/validate.sh aws|gcp|azure
tflint --init && tflint --recursive --config "$PWD/.tflint.hcl"
```

`validate.sh` runs `terraform fmt -check -recursive`, `scripts/check-contract.sh`, then `init -backend=false` +
`validate` in every module, stack, bootstrap and env root, and `terraform test` in every env root (a plan with
mocked providers: every expression, `count`/`for_each` and variable validation is evaluated, and a non-Canadian
region is rejected). Set `TF_PLUGIN_CACHE_DIR` to download each provider once. CI runs the same script, manually
only: GitHub **Actions › infra › Run workflow**, GitLab **Run pipeline** with `PIPELINE_PART=infra`
([ci.md](../../docs/runbooks/ci.md)).

Versions: Terraform `>= 1.9, < 2` (tested with 1.16.4); providers pinned in each env root: `hashicorp/aws ~> 6.66`,
`hashicorp/google` and `google-beta ~> 8.5`, `hashicorp/azurerm ~> 5.7`, `hashicorp/random ~> 3.9`,
`elastic/ec ~> 0.13`. Modules state only lower/upper bounds.

### Provider lock files

`.terraform.lock.hcl` files are not committed yet: they were generated offline from a local provider mirror, which
records hashes for one platform only. On the first `terraform init` with registry access, run once per env root and
commit the result:

```sh
terraform providers lock -platform=linux_amd64 -platform=darwin_arm64 -platform=darwin_amd64 -platform=windows_amd64
```

## Adding or changing a capability

1. Change `variables.tf` identically in `aws/`, `gcp/` and `azure/` (only the context region list differs), and keep
   the output names aligned; `scripts/check-contract.sh` fails otherwise.
2. Wire it in `stacks/<cloud>/main.tf` and, if it feeds the apps, into `config_env` / `secret_env` in
   `stacks/<cloud>/outputs.tf` under the application's variable name.
3. Update the output → variable table in `docs/runbooks/infrastructure.md` and the environment runbooks.
4. `scripts/validate.sh` and `tflint`.
