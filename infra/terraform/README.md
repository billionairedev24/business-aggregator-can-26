# Northline infrastructure (Terraform)

Stories **S-2** (modules: one interface, AWS / Google Cloud / Azure implementations) and **S-3** (managed data
stores). Everything lives in a Canadian region. **Nothing has been applied yet**: there are no cloud accounts or
credentials. Every root passes `terraform validate` and a full `terraform plan` against mocked providers
(`terraform test`), so the first real plan needs only credentials and a state bucket.

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
│   └── secrets        Secrets Manager / Secret Manager / Key Vault (secrets), read by External Secrets Operator
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
| dns | `zone_name` | `zone_id`, `zone_name`, `name_servers` |
| storage | `buckets`, `name_suffix`, `kms_key`, `writers`, `force_destroy` | `storage_provider`, `bucket_names`, `storage_region`, `storage_endpoint` |
| secrets | `secret_names`, `readers`, `kms_key`, `deletion_protection` | `secrets_provider`, `store`, `secret_refs` |

Where a cloud has no use for an input it says so in `main.tf` (`unused_contract_inputs`), for example `zone_count`
on Google Cloud and Azure (regional subnets) or `kms_key` on ACR (customer-managed keys need Premium).

### Per-cloud choices worth knowing

| | AWS | Google Cloud | Azure |
|---|---|---|---|
| network | VPC; public, private (nodes) and isolated data subnets per zone; NAT per zone or shared; S3 gateway endpoint; flow logs outside dev | custom VPC; regional node subnet with `pods`/`services` ranges; data subnet; Cloud NAT with static IPs; Private Service Access (Cloud SQL) and a PSC policy (Memorystore) | VNet; `aks`, `endpoints` and a `postgres` subnet delegated to Flexible Server; NAT gateway with static IP |
| Kubernetes | EKS, API auth mode, secrets encrypted with KMS, managed node groups (AL2023), IRSA, core add-ons incl. EBS CSI | GKE Standard, zonal in dev / regional otherwise, private nodes, Dataplane V2, Workload Identity, secrets encrypted with Cloud KMS | AKS, CNI overlay + Cilium, Entra RBAC (local accounts off), Workload Identity, KMS etcd encryption, user-assigned kubelet identity |
| workload identity → ServiceAccount | `eks.amazonaws.com/role-arn` | `iam.gke.io/gcp-service-account` | `azure.workload.identity/client-id` + pod label `azure.workload.identity/use: "true"` |
| signing key (`KMS_KEY_ID`) | key ARN, `ECC_NIST_P256` | crypto key name, `EC_SIGN_P256_SHA256` (HSM in prod) | versionless key URL, EC P-256 (HSM in prod) |
| secrets | `northline/<env>/<name>`, created empty | `northline-<env>-<name>`, user-managed replication in the region only, created empty | one vault per environment; Key Vault has no empty secrets, so the operator creates them |

## Workload identities

The stacks create one cloud identity per Kubernetes service account below (namespace `northline-<env>`), and grant:

| name | service account | grants |
|---|---|---|
| `api` | `northline-api` | signing key (sign/verify), uploads bucket (read/write) |
| `auth` | `northline-auth` | signing key (sign/verify) |
| `bff` | `northline-bff` | — |
| `worker` | `northline-worker` | — |
| `external-secrets` | `external-secrets/external-secrets` | read every secret of the environment |

The Helm charts (S-14) put `kubernetes.workload_identities[<name>].service_account_annotations` on the
ServiceAccounts and `pod_labels` on the pods; the apps then need no static cloud credentials
(`STORAGE_ACCESS_KEY`/`STORAGE_SECRET_KEY` stay empty).

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
`hashicorp/google` and `google-beta ~> 8.5`, `hashicorp/azurerm ~> 5.7`, `hashicorp/random ~> 3.9`. Modules state
only lower/upper bounds.

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
