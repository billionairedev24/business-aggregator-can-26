# Offline plan with mocked providers: evaluates every expression, count/for_each and validation of this root
# without cloud credentials or state. Run: terraform init -backend=false && terraform test
mock_provider "google" {
  # Well-formed values where the provider parses them at plan time (mocks return random strings).
  mock_resource "google_container_cluster" {
    override_during = plan
    defaults = {
      endpoint = "203.0.113.10"
    }
  }
  mock_data "google_project" {
    defaults = { number = "123456789012" }
  }
  mock_data "google_storage_project_service_account" {
    defaults = { email_address = "service-123456789012@gs-project-accounts.iam.gserviceaccount.com" }
  }
}

mock_provider "google-beta" {}

variables {
  project_id        = "northline-prod"
  api_allowed_cidrs = ["203.0.113.0/24"]
}

mock_provider "ec" {
  mock_data "ec_stack" {
    defaults = { version = "9.1.3" }
  }
}

run "plan" {
  command = plan
}

run "config_env_keys" {
  command = plan

  assert {
    condition     = alltrue([for k in ["STORAGE_PROVIDER", "STORAGE_BUCKET", "STORAGE_REGION", "STORAGE_ENDPOINT", "STORAGE_ENCRYPTION_KEY", "KMS_PROVIDER", "KMS_KEY_ID", "KMS_PUBLISHED_KEY_IDS", "KMS_ENCRYPTION_KEY_ID", "DB_URL", "DB_USER", "REDIS_HOST", "REDIS_PORT", "REDIS_SSL", "KAFKA_BOOTSTRAP", "KAFKA_SECURITY_PROTOCOL", "KAFKA_SASL_MECHANISM", "ES_URIS", "ES_USERNAME"] : contains(keys(output.config_env), k)])
    error_message = "config_env must carry the variable names the apps read (docs/runbooks/README.md)."
  }

  assert {
    condition     = alltrue([for k in ["DB_PASSWORD", "KAFKA_SASL_JAAS_CONFIG", "ES_PASSWORD", "TOTP_KEY"] : contains(keys(output.secret_env), k)])
    error_message = "secret_env must name the secret behind each secret variable."
  }
}

run "s7_s10_values" {
  command = plan

  override_resource {
    override_during = plan
    target          = module.northline.module.kms.google_kms_crypto_key.this["signing"]
    values          = { id = "projects/p/locations/northamerica-northeast1/keyRings/northline/cryptoKeys/signing" }
  }

  assert {
    condition     = output.config_env["STORAGE_ENDPOINT"] == "" && output.config_env["STORAGE_REGION"] == ""
    error_message = "Google Cloud Storage: STORAGE_ENDPOINT must stay empty (an endpoint means an emulator to the api)."
  }

  assert {
    condition     = endswith(output.config_env["KMS_KEY_ID"], "/cryptoKeyVersions/1")
    error_message = "Google Cloud: KMS_KEY_ID must be the key version resource name (S-7)."
  }

  assert {
    condition     = output.config_env["KMS_PUBLISHED_KEY_IDS"] == ""
    error_message = "KMS_PUBLISHED_KEY_IDS is empty outside a rotation."
  }
}

run "signing_key_rotation" {
  command = plan

  variables {
    signing_key_ids = { active = "old-key", published = ["new-key", "older-key"] }
  }

  assert {
    condition     = output.config_env["KMS_KEY_ID"] == "old-key" && output.config_env["KMS_PUBLISHED_KEY_IDS"] == "new-key,older-key"
    error_message = "signing_key_ids must drive KMS_KEY_ID and KMS_PUBLISHED_KEY_IDS (docs/runbooks/key-rotation.md)."
  }
}

run "rejects_region_outside_canada" {
  command = plan

  variables {
    region = "us-central1"
  }

  expect_failures = [var.region]
}

run "helm_values" {
  command = plan

  assert {
    condition     = output.helm_values.externalSecrets.enabled && contains(["aws", "gcp", "azure"], output.helm_values.externalSecrets.provider)
    error_message = "helm_values.externalSecrets must enable External Secrets with this cloud's provider (S-6)."
  }

  assert {
    condition     = alltrue([for k in ["DB_PASSWORD", "TOTP_KEY", "STUDIO_BFF_SECRET_HASH", "WEBHOOK_SECRET_KEY", "STRIPE_WEBHOOK_SECRET", "EMAIL_UNSUBSCRIBE_KEY"] : contains(keys(output.helm_values.externalSecrets.remoteKeys), k)])
    error_message = "helm_values.externalSecrets.remoteKeys must name the secret behind each secret variable the chart maps."
  }

  assert {
    condition     = alltrue([for k in ["api", "auth", "bff", "worker"] : contains(keys(output.helm_values.workloadIdentities), k)]) && !contains(keys(output.helm_values.workloadIdentities), "external-secrets")
    error_message = "helm_values.workloadIdentities carries the four app ServiceAccounts (the ESO controller's is installed with ESO)."
  }
}

run "edge" {
  command = plan

  assert {
    condition     = contains(keys(output.helm_values.edge.certManager.issuer.dns01), "cloudDNS")
    error_message = "helm_values.edge must carry this cloud's cert-manager DNS-01 solver (S-17)."
  }

  assert {
    condition     = output.gitops_addon_values["external-dns"].provider.name == "google" && output.gitops_addon_values["external-dns"].txtOwnerId == "northline-prod"
    error_message = "external-dns must use this cloud's provider and own its records as northline-prod (S-17)."
  }

  assert {
    condition     = alltrue([for k in ["external-secrets", "external-dns", "cert-manager"] : contains(keys(output.gitops_addon_values), k)])
    error_message = "gitops_addon_values must hold the identity of every platform add-on (S-6, S-17)."
  }
}

# S-114: prod keeps a copy of the database and of the buckets in the other Canadian region, never outside Canada.
run "backups_cross_region" {
  command = plan

  assert {
    condition     = output.backup.secondary_region == "northamerica-northeast2" && output.backup.postgres.copy_region == "northamerica-northeast2" && output.backup.postgres.copy_kind == "cross-region-read-replica"
    error_message = "prod must copy the database to the other Canadian region (northamerica-northeast2) (docs/runbooks/backups-dr.md)."
  }

  assert {
    condition     = output.backup.postgres.retention_days == 35 && output.backup.storage.replica_region == "northamerica-northeast2" && length(output.backup.storage.replica_buckets) == length(output.backup.storage.buckets)
    error_message = "prod: 35 days of point-in-time recovery and a replica of every bucket in northamerica-northeast2."
  }

  assert {
    condition     = !output.backup.kafka.backed_up && !output.backup.cache.backed_up && output.backup.search.snapshot_repository == "found-snapshots"
    error_message = "Kafka and Valkey are rebuilt, not restored; search snapshots go to Elastic Cloud's found-snapshots."
  }
}

run "backups_follow_the_primary_region" {
  command = plan

  variables {
    region = "northamerica-northeast2"
  }

  assert {
    condition     = output.backup.secondary_region == "northamerica-northeast1" && output.backup.storage.replica_region == "northamerica-northeast1"
    error_message = "With the primary in northamerica-northeast2, the copies go to northamerica-northeast1 (still Canada)."
  }
}
