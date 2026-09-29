provider "azurerm" {
  subscription_id     = var.subscription_id
  storage_use_azuread = true # storage accounts have shared keys disabled

  features {
    key_vault {
      purge_soft_delete_on_destroy = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = false
    }
  }
}

# dev: smallest sizes, NAT shared, nothing protected from destroy; can be scaled to zero out of hours.
module "northline" {
  source = "../../../stacks/azure"

  environment = "dev"
  region      = var.region
  owner       = var.owner

  cidr                  = "10.20.0.0/16"
  zone_count            = 3
  high_availability_nat = false
  node_pools = {
    system = { machine_type = "Standard_D4s_v5", min_count = 1, max_count = 3 }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = "dev.northline.ca"
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = false
}
