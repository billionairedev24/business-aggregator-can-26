# Azure Container Registry: one registry per environment (repositories are created on first push), admin user off,
# AcrPull for the readers (AKS kubelet identity). Premium in prod (zone redundancy, retention policy); Standard
# otherwise. kms_key is not used: ACR customer-managed keys need Premium plus a dedicated identity (later).

resource "random_string" "suffix" {
  length  = 6
  special = false
  upper   = false
}

resource "azurerm_container_registry" "this" {
  name                          = "northline${var.context.environment}${random_string.suffix.result}"
  location                      = var.context.region
  resource_group_name           = var.context.resource_group_name
  sku                           = var.context.environment == "prod" ? "Premium" : "Standard"
  admin_enabled                 = false
  public_network_access_enabled = true
  zone_redundancy_enabled       = var.context.environment == "prod"
  tags                          = var.context.tags
}

resource "azurerm_role_assignment" "readers" {
  for_each             = var.readers
  scope                = azurerm_container_registry.this.id
  role_definition_name = "AcrPull"
  principal_id         = each.value
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [try(var.kms_key.id, null), var.keep_images]
}
