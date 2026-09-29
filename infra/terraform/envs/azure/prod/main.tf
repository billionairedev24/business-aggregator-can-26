provider "azurerm" {
  subscription_id     = var.subscription_id
  storage_use_azuread = true # storage accounts have shared keys disabled

  features {
    key_vault {
      purge_soft_delete_on_destroy = false
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
}

# prod: multi-zone HA, deletion protection on.
module "northline" {
  source = "../../../stacks/azure"

  environment = "prod"
  region      = var.region
  owner       = var.owner

  cidr                  = "10.40.0.0/16"
  zone_count            = 3
  high_availability_nat = true
  node_pools = {
    system = { machine_type = "Standard_D2s_v5", min_count = 3, max_count = 3 }
    apps   = { machine_type = "Standard_D4s_v5", min_count = 3, max_count = 9 }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = "northline.ca"
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = true
}
