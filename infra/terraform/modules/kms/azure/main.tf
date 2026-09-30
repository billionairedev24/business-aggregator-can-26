# Azure Key Vault (keys): one RBAC-mode vault per environment holding the keys. encrypt = RSA 3072 wrap/unwrap
# (envelope key for AKS etcd, storage), sign = EC P-256 sign/verify (ES256 tokens, S-7). The operator running
# Terraform gets Key Vault Crypto Officer; key users get Key Vault Crypto User on their key.
# Role assignments take a few minutes to propagate: a first apply may need a re-run.

data "azurerm_client_config" "current" {}

resource "random_string" "suffix" {
  length  = 6
  special = false
  upper   = false
}

locals {
  grants = merge([
    for key, users in var.key_users : {
      for label, principal in users : "${key}/${label}" => { key = key, principal = principal }
    }
  ]...)
}

resource "azurerm_key_vault" "this" {
  name                          = "nl-${var.context.environment}-kms-${random_string.suffix.result}"
  location                      = var.context.region
  resource_group_name           = var.context.resource_group_name
  tenant_id                     = data.azurerm_client_config.current.tenant_id
  sku_name                      = var.context.environment == "prod" ? "premium" : "standard"
  rbac_authorization_enabled    = true
  purge_protection_enabled      = true # required for AKS KMS and storage CMK
  soft_delete_retention_days    = var.deletion_protection ? 90 : 7
  public_network_access_enabled = true
  tags                          = var.context.tags

  network_acls {
    bypass         = "AzureServices"
    default_action = "Allow"
  }
}

resource "azurerm_role_assignment" "operator" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Crypto Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_key_vault_key" "this" {
  for_each     = var.keys
  name         = each.key
  key_vault_id = azurerm_key_vault.this.id
  key_type     = each.value.usage == "sign" ? (var.context.environment == "prod" ? "EC-HSM" : "EC") : (var.context.environment == "prod" ? "RSA-HSM" : "RSA")
  key_size     = each.value.usage == "sign" ? null : 3072
  curve        = each.value.usage == "sign" ? "P-256" : null
  key_opts     = each.value.usage == "sign" ? ["sign", "verify"] : ["wrapKey", "unwrapKey", "encrypt", "decrypt"]
  tags         = var.context.tags

  dynamic "rotation_policy" {
    for_each = each.value.usage == "encrypt" ? [each.value.rotation_days] : []
    content {
      expire_after         = "P${rotation_policy.value + 30}D"
      notify_before_expiry = "P29D"
      automatic {
        time_after_creation = "P${rotation_policy.value}D"
      }
    }
  }

  depends_on = [azurerm_role_assignment.operator]
}

# "Key Vault Crypto User" on the key only (versionless scope, so every version after a rotation is covered): sign +
# get key, what northline-auth calls (S-7).
resource "azurerm_role_assignment" "key_user" {
  for_each             = local.grants
  scope                = azurerm_key_vault_key.this[each.value.key].resource_versionless_id
  role_definition_name = "Key Vault Crypto User"
  principal_id         = each.value.principal
}
