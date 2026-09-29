# Azure Key Vault (secrets): one RBAC-mode vault per environment. The operator running Terraform gets
# Key Vault Secrets Officer (the data-store modules write generated secrets, S-3); readers (External Secrets
# Operator) get Key Vault Secrets User on the vault. Key Vault cannot hold an empty secret, so secret_names are
# not created here: the operator sets them (az keyvault secret set) and secret_refs lists the expected names.
# kms_key is not used: vault contents are always encrypted with HSM-backed service keys.

data "azurerm_client_config" "current" {}

resource "random_string" "suffix" {
  length  = 6
  special = false
  upper   = false
}

resource "azurerm_key_vault" "this" {
  name                          = "nl-${var.context.environment}-sec-${random_string.suffix.result}"
  location                      = var.context.region
  resource_group_name           = var.context.resource_group_name
  tenant_id                     = data.azurerm_client_config.current.tenant_id
  sku_name                      = "standard"
  rbac_authorization_enabled    = true
  purge_protection_enabled      = var.deletion_protection
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
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "reader" {
  for_each             = var.readers
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = each.value
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [try(var.kms_key.id, null)]
}
