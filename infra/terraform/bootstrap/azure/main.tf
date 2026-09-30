# One-off, per subscription: the storage account + container that hold Terraform state for the Northline Azure
# environments. Run with local state:
#   terraform init && terraform apply -var subscription_id=<id>
# then copy the output into envs/azure/<env>/backend.hcl.

terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.7"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}

variable "subscription_id" {
  description = "Subscription that owns the state storage account."
  type        = string
}

variable "region" {
  description = "Canadian region for the state storage account."
  type        = string
  default     = "canadacentral"

  validation {
    condition     = contains(["canadacentral", "canadaeast"], var.region)
    error_message = "Canadian data residency: region must be canadacentral or canadaeast."
  }
}

variable "owner" {
  description = "Owner tag."
  type        = string
  default     = "platform"
}

provider "azurerm" {
  subscription_id     = var.subscription_id
  storage_use_azuread = true
  features {}
}

data "azurerm_client_config" "current" {}

locals {
  tags = {
    app              = "northline"
    env              = "shared"
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }
}

resource "random_string" "suffix" {
  length  = 8
  special = false
  upper   = false
}

resource "azurerm_resource_group" "state" {
  name     = "rg-northline-tfstate"
  location = var.region
  tags     = local.tags
}

resource "azurerm_storage_account" "state" {
  name                            = "nltfstate${random_string.suffix.result}"
  resource_group_name             = azurerm_resource_group.state.name
  location                        = var.region
  account_tier                    = "Standard"
  account_replication_type        = "ZRS"
  min_tls_version                 = "TLS1_2"
  https_traffic_only_enabled      = true
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  default_to_oauth_authentication = true
  tags                            = local.tags

  blob_properties {
    versioning_enabled = true
    delete_retention_policy {
      days = 30
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_role_assignment" "operator" {
  scope                = azurerm_storage_account.state.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_storage_container" "state" {
  name                  = "tfstate"
  storage_account_id    = azurerm_storage_account.state.id
  container_access_type = "private"
  depends_on            = [azurerm_role_assignment.operator]
}

output "backend_hcl" {
  description = "Settings for envs/azure/<env>/backend.hcl (replace <env>)."
  value       = <<-EOT
    resource_group_name  = "${azurerm_resource_group.state.name}"
    storage_account_name = "${azurerm_storage_account.state.name}"
    container_name       = "${azurerm_storage_container.state.name}"
    key                  = "azure/<env>/terraform.tfstate"
    use_azuread_auth     = true
  EOT
}
