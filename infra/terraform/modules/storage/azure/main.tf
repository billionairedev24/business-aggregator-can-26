# Azure Blob Storage: one StorageV2 account per environment (ZRS outside dev), Entra ID only (shared keys off),
# HTTPS/TLS 1.2, no public blobs, versioning + soft delete, one private container per bucket, customer-managed key
# from Key Vault when kms_key is set. Writers get Storage Blob Data Contributor.

data "azurerm_client_config" "current" {}

resource "random_string" "suffix" {
  length  = 6
  special = false
  upper   = false
}

locals {
  kms_vault_name = var.kms_key == null ? null : regex("^https://([^.]+)\\.", try(var.kms_key.id, null))[0]
  kms_vault_id   = var.kms_key == null ? null : "/subscriptions/${data.azurerm_client_config.current.subscription_id}/resourceGroups/${var.context.resource_group_name}/providers/Microsoft.KeyVault/vaults/${local.kms_vault_name}"
  # https://<vault>.vault.azure.net/keys/<name>/<version> → https://<vault>.vault.azure.net/keys/<name>
  kms_key_versionless = var.kms_key == null ? null : regex("^(https://[^/]+/keys/[^/]+)", try(var.kms_key.id, null))[0]
  cors_origins        = distinct(flatten([for b in var.buckets : b.cors_allowed_origins]))
}

resource "azurerm_storage_account" "this" {
  name                              = "nl${var.context.environment}st${random_string.suffix.result}"
  location                          = var.context.region
  resource_group_name               = var.context.resource_group_name
  account_kind                      = "StorageV2"
  account_tier                      = "Standard"
  account_replication_type          = var.context.environment == "dev" ? "LRS" : "ZRS"
  https_traffic_only_enabled        = true
  min_tls_version                   = "TLS1_2"
  allow_nested_items_to_be_public   = false
  shared_access_key_enabled         = false
  default_to_oauth_authentication   = true
  infrastructure_encryption_enabled = true
  tags                              = var.context.tags

  identity {
    type = "SystemAssigned"
  }

  blob_properties {
    versioning_enabled = anytrue([for b in var.buckets : b.versioning])

    delete_retention_policy {
      days = 7
    }

    container_delete_retention_policy {
      days = 7
    }

    dynamic "cors_rule" {
      for_each = length(local.cors_origins) > 0 ? [local.cors_origins] : []
      content {
        allowed_origins    = cors_rule.value
        allowed_methods    = ["GET", "PUT", "HEAD"]
        allowed_headers    = ["*"]
        exposed_headers    = ["ETag"]
        max_age_in_seconds = 3600
      }
    }
  }
}

resource "azurerm_storage_container" "this" {
  for_each              = var.buckets
  name                  = each.key
  storage_account_id    = azurerm_storage_account.this.id
  container_access_type = "private"
}

resource "azurerm_storage_management_policy" "this" {
  storage_account_id = azurerm_storage_account.this.id

  dynamic "rule" {
    for_each = var.buckets
    content {
      name    = "noncurrent-${rule.key}"
      enabled = true
      filters {
        blob_types   = ["blockBlob"]
        prefix_match = ["${rule.key}/"]
      }
      actions {
        version {
          delete_after_days_since_creation = rule.value.noncurrent_days
        }
      }
    }
  }
}

resource "azurerm_role_assignment" "cmk" {
  count                = var.kms_key == null ? 0 : 1
  scope                = local.kms_vault_id
  role_definition_name = "Key Vault Crypto Service Encryption User"
  principal_id         = azurerm_storage_account.this.identity[0].principal_id
}

resource "azurerm_storage_account_customer_managed_key" "this" {
  count              = var.kms_key == null ? 0 : 1
  storage_account_id = azurerm_storage_account.this.id
  key_vault_key_id   = local.kms_key_versionless # versionless: follows key rotation

  depends_on = [azurerm_role_assignment.cmk]
}

resource "azurerm_role_assignment" "writers" {
  for_each             = var.writers
  scope                = azurerm_storage_account.this.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = each.value
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.name_suffix, var.force_destroy]
}
