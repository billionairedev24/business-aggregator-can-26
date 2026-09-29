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
deletion protection. Every resource carries `app=northline`, `env=<env>`, `owner`, `data-residency=ca`,
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
```

| variable | output | module output | AWS | Google Cloud | Azure |
|---|---|---|---|---|---|
| `STORAGE_PROVIDER` | `config_env` | `storage.storage_provider` | `s3` | `gcs` | `azure` |
| `STORAGE_BUCKET` | `config_env` | `storage.bucket_names["uploads"]` | `northline-<env>-uploads` | `northline-<env>-uploads` | container `uploads` |
| `STORAGE_REGION` | `config_env` | `storage.storage_region` | region | region | region |
| `STORAGE_ENDPOINT` | `config_env` | `storage.storage_endpoint` | empty (SDK default) | empty | `https://<account>.blob.core.windows.net/` |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | — | — | empty: workload identity | empty | empty |
| `KMS_PROVIDER` | `config_env` | `kms.kms_provider` | `aws` | `gcp` | `azure` |
| `KMS_KEY_ID` | `config_env` | `kms.key_refs["signing"]` | key ARN | `projects/…/cryptoKeys/signing` | `https://<vault>.vault.azure.net/keys/signing` |
| `TOTP_KEY`, `WEBHOOK_SECRET_KEY`, `STUDIO_BFF_SECRET`, `STUDIO_BFF_SECRET_HASH`, `CONSUMER_BFF_SECRET_HASH`, `CONSOLE_BFF_SECRET_HASH`, `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `GOOGLE_CLIENT_SECRET`, `APPLE_CLIENT_SECRET`, `SMS_AUTH_TOKEN` | `secret_env` | `secrets.secret_refs` | `northline/<env>/<name>` | `northline-<env>-<name>` | `<name>` in vault `nl-<env>-sec-…` |

The app secrets are created **empty** (AWS, Google Cloud) or not at all (Azure: Key Vault has no empty secrets);
Terraform never sees their values. Set them as the environment runbook describes (`openssl rand -base64 32`, the
bcrypt hash, the Stripe keys):

```sh
aws secretsmanager put-secret-value --secret-id northline/dev/totp-key --secret-string "$(openssl rand -base64 32)"
printf %s "$(openssl rand -base64 32)" | gcloud secrets versions add northline-dev-totp-key --data-file=- --project northline-dev
az keyvault secret set --vault-name <nl-dev-sec-…> --name totp-key --value "$(openssl rand -base64 32)"
```

Other outputs, for the stories that consume them:

| output | used by |
|---|---|
| `kubernetes.workload_identities.<name>.service_account_annotations` / `.pod_labels` | Helm charts (S-14): ServiceAccounts `northline-api`, `-auth`, `-bff`, `-worker`, and `external-secrets` |
| `secrets_provider`, `secret_store` | the External Secrets `SecretStore` (S-6): provider `aws` / `gcpsm` / `azurekv` |
| `registry.repository_urls`, `registry.login_command` | image builds and pushes (S-14) |
| `dns.name_servers` | delegation (S-17): NS records for `dev.northline.ca` / `staging.northline.ca` in the `northline.ca` zone; the prod zone's servers at the registrar |
| `network.cloud.nat_public_ips` | allow-lists that need the cluster's egress IPs (Elastic Cloud traffic filters, partners) |
| `kms.key_ids["data"]` | encryption at rest of Kubernetes Secrets, buckets, registry, secrets |

## 5. Cost notes for a small dev environment

Rough list prices per month before credits (USD, 730 h, September 2026 — check each cloud's calculator). S-2
resources only; the data stores (S-3) add to this.

| | AWS (`ca-central-1`) | Google Cloud (`northamerica-northeast1`) | Azure (`canadacentral`) |
|---|---|---|---|
| Kubernetes control plane | EKS ≈ 73 | GKE: the free tier covers one zonal cluster ≈ 0 | AKS Free tier ≈ 0 |
| Nodes (dev: 1 node) | 1 × t3.large ≈ 70 | 1 × e2-standard-4 **spot** ≈ 35 | 1 × D4s_v5 ≈ 160 |
| NAT + public IP | NAT gateway ≈ 35 + data | Cloud NAT + 1 static IP ≈ 10 | NAT gateway ≈ 35 + IP ≈ 4 |
| Keys, secrets, DNS, registry | KMS 2 keys ≈ 2, Secrets Manager 11 × 0.40 ≈ 5, Route 53 ≈ 1, ECR storage | Cloud KMS ≈ 1, Secret Manager ≈ 1, Cloud DNS ≈ 1 | Key Vault ≈ 1, ACR Standard ≈ 20, DNS ≈ 1 |
| **≈ total** | **≈ 185** | **≈ 50** | **≈ 220** |

To spend less in dev: scale node pools to zero out of hours (`min_count = 0` in `envs/<cloud>/dev/main.tf`, then
scale the pool; the cluster autoscaler brings it back), use spot nodes (`spot = true`; already on in Google Cloud
dev), keep dev on the cloud with the largest credit balance, and destroy dev when idle (§ 6).

## 6. Teardown

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
- The state buckets (bootstrap) have `prevent_destroy`; remove it deliberately if an account is closed.
