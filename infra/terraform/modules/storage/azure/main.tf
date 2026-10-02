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
  # S-114: known at plan time even while the key id is not (try() would make it unknown).
  replica_kms_key = var.replica == null ? null : var.replica.kms_key
  # S-107: one management policy rule per container and expiring prefix (names: letters, digits, '-').
  expiring = merge([for c, b in var.buckets : {
    for prefix, days in b.expire_prefixes :
    "expire-${c}-${trimsuffix(replace(prefix, "/", "-"), "-")}" => { prefix = "${c}/${prefix}", days = days }
  }]...)
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
    versioning_enabled = anytrue([for b in var.buckets : b.versioning]) || var.replica != null
    # S-114: object replication reads the change feed of the source account.
    change_feed_enabled           = var.replica != null
    change_feed_retention_in_days = var.replica == null ? null : 7

    delete_retention_policy {
      days = 30 # docs/runbooks/object-storage.md: blob soft delete 30 days, container soft delete 7 days
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

  dynamic "rule" {
    for_each = local.expiring
    content {
      name    = rule.key
      enabled = true
      filters {
        blob_types   = ["blockBlob"]
        prefix_match = [rule.value.prefix]
      }
      actions {
        base_blob {
          delete_after_days_since_creation_greater_than = rule.value.days
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

# Least privilege (docs/runbooks/object-storage.md): "Storage Blob Data Contributor" on each container, not the account.
resource "azurerm_role_assignment" "writers" {
  for_each = merge([
    for b in keys(var.buckets) : { for label, principal in var.writers : "${b}/${label}" => { bucket = b, principal = principal } }
  ]...)
  scope                = azurerm_storage_container.this[each.value.bucket].id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = each.value.principal
}

# ---- S-114: replica in the paired Canadian region ---------------------------------------------------------------
# A second StorageV2 account in var.replica.region (LRS: it is the second copy already), the same containers, and an
# object replication policy (asynchronous, minutes; new and changed blobs, from now on — copy_blobs_created_after
# "Everything" also brings the existing blobs over once). Deleting a blob in the source does not delete the replica.
# Encrypted with the primary key vault's key (customer-managed keys may live in another region; Key Vault keeps a
# read-only copy in the paired region, so the key stays usable when the primary region is down). Lifecycle: current
# blobs move to Cool after cool_after_days, versions expire after noncurrent_days. Replication health: the
# policy's metrics (metrics_enabled) in Azure Monitor (docs/runbooks/backups-dr.md § Alerts).

resource "azurerm_storage_account" "replica" {
  count                             = var.replica == null ? 0 : 1
  name                              = "nl${var.context.environment}rp${random_string.suffix.result}"
  location                          = var.replica.region
  resource_group_name               = var.context.resource_group_name
  account_kind                      = "StorageV2"
  account_tier                      = "Standard"
  account_replication_type          = "LRS"
  https_traffic_only_enabled        = true
  min_tls_version                   = "TLS1_2"
  allow_nested_items_to_be_public   = false
  shared_access_key_enabled         = false
  default_to_oauth_authentication   = true
  infrastructure_encryption_enabled = true
  tags                              = merge(var.context.tags, { purpose = "replica" })

  identity {
    type = "SystemAssigned"
  }

  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = 30
    }

    container_delete_retention_policy {
      days = 7
    }
  }
}

resource "azurerm_storage_container" "replica" {
  for_each              = var.replica == null ? {} : var.buckets
  name                  = each.key
  storage_account_id    = azurerm_storage_account.replica[0].id
  container_access_type = "private"
}

resource "azurerm_storage_management_policy" "replica" {
  count              = var.replica == null ? 0 : 1
  storage_account_id = azurerm_storage_account.replica[0].id

  rule {
    name    = "cool-and-expire-versions"
    enabled = true
    filters {
      blob_types = ["blockBlob"]
    }
    actions {
      base_blob {
        tier_to_cool_after_days_since_modification_greater_than = var.replica.cool_after_days
      }
      version {
        delete_after_days_since_creation = var.replica.noncurrent_days
      }
    }
  }

  dynamic "rule" {
    for_each = local.expiring
    content {
      name    = rule.key
      enabled = true
      filters {
        blob_types   = ["blockBlob"]
        prefix_match = [rule.value.prefix]
      }
      actions {
        base_blob {
          delete_after_days_since_creation_greater_than = rule.value.days
        }
      }
    }
  }
}

resource "azurerm_role_assignment" "replica_cmk" {
  count                = local.replica_kms_key == null ? 0 : 1
  scope                = "/subscriptions/${data.azurerm_client_config.current.subscription_id}/resourceGroups/${var.context.resource_group_name}/providers/Microsoft.KeyVault/vaults/${regex("^https://([^.]+)\\.", var.replica.kms_key.id)[0]}"
  role_definition_name = "Key Vault Crypto Service Encryption User"
  principal_id         = azurerm_storage_account.replica[0].identity[0].principal_id
}

resource "azurerm_storage_account_customer_managed_key" "replica" {
  count              = local.replica_kms_key == null ? 0 : 1
  storage_account_id = azurerm_storage_account.replica[0].id
  key_vault_key_id   = regex("^(https://[^/]+/keys/[^/]+)", var.replica.kms_key.id)[0]

  depends_on = [azurerm_role_assignment.replica_cmk]
}

resource "azurerm_storage_object_replication" "replica" {
  count                          = var.replica == null ? 0 : 1
  source_storage_account_id      = azurerm_storage_account.this.id
  destination_storage_account_id = azurerm_storage_account.replica[0].id
  metrics_enabled                = true

  dynamic "rules" {
    for_each = var.buckets
    content {
      source_container_name      = azurerm_storage_container.this[rules.key].name
      destination_container_name = azurerm_storage_container.replica[rules.key].name
      copy_blobs_created_after   = "Everything"
    }
  }
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.name_suffix, var.force_destroy]
}
