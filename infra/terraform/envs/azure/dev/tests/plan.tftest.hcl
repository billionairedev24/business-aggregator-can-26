# Offline plan with mocked providers: evaluates every expression, count/for_each and validation of this root
# without cloud credentials or state. Run: terraform init -backend=false && terraform test
mock_provider "azurerm" {
  # Well-formed values where the provider parses them at plan time (mocks return random strings).
  mock_resource "azurerm_kubernetes_cluster" {
    override_during = plan
    defaults = {
      id              = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-northline/providers/Microsoft.ContainerService/managedClusters/northline"
      oidc_issuer_url = "https://canadacentral.oic.prod-aks.azure.com/tenant/issuer/"
    }
  }
  mock_data "azurerm_client_config" {
    defaults = {
      subscription_id = "00000000-0000-0000-0000-000000000000"
      tenant_id       = "11111111-1111-1111-1111-111111111111"
      object_id       = "22222222-2222-2222-2222-222222222222"
    }
  }
}

mock_provider "random" {}

variables {
  subscription_id = "00000000-0000-0000-0000-000000000000"
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
    target          = module.northline.module.storage.azurerm_storage_account.this
    values = {
      id                    = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-northline/providers/Microsoft.Storage/storageAccounts/nlst000000"
      primary_blob_endpoint = "https://nlst000000.blob.core.windows.net/"
    }
  }

  assert {
    condition     = output.config_env["STORAGE_BUCKET"] == "uploads" && output.config_env["STORAGE_REGION"] == ""
    error_message = "Azure: STORAGE_BUCKET is the container name."
  }

  assert {
    condition     = startswith(output.config_env["STORAGE_ENDPOINT"], "https://") && !endswith(output.config_env["STORAGE_ENDPOINT"], "/")
    error_message = "Azure: STORAGE_ENDPOINT must be the account's blob endpoint URL (required by the api)."
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
    region = "eastus"
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

run "s25_event_hubs_from_the_topic_catalogue" {
  command = plan

  assert {
    condition     = output.data_stores.kafka.cloud.event_hubs["payments.payout.dlq"].partitions == 1
    error_message = "Every catalogue topic's .dlq is an event hub (deploy/kafka/topics.yaml, dlq partitions)."
  }

  assert {
    condition     = output.data_stores.kafka.cloud.event_hubs["catalogue.listing.search-indexer.retry-0"].partitions == 6
    error_message = "Consumer retry topics are event hubs with the source topic's partitions."
  }

  assert {
    condition     = output.data_stores.kafka.cloud.event_hubs["payments.payout"].retention_hours == 168
    error_message = "Retention comes from the catalogue."
  }
}

run "edge" {
  command = plan

  assert {
    condition     = contains(keys(output.helm_values.edge.certManager.issuer.dns01), "azureDNS")
    error_message = "helm_values.edge must carry this cloud's cert-manager DNS-01 solver (S-17)."
  }

  assert {
    condition     = output.gitops_addon_values["external-dns"].provider.name == "azure" && output.gitops_addon_values["external-dns"].txtOwnerId == "northline-dev"
    error_message = "external-dns must use this cloud's provider and own its records as northline-dev (S-17)."
  }

  assert {
    condition     = alltrue([for k in ["external-secrets", "external-dns", "cert-manager"] : contains(keys(output.gitops_addon_values), k)])
    error_message = "gitops_addon_values must hold the identity of every platform add-on (S-6, S-17)."
  }
}
