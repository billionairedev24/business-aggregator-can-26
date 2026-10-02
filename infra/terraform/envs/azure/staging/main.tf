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

# Elastic Cloud: reads EC_API_KEY from the environment.
provider "ec" {}

# staging: prod topology (multi-zone, replicas) at smaller sizes.
module "northline" {
  source = "../../../stacks/azure"

  environment = "staging"
  region      = var.region
  owner       = var.owner

  cidr                  = "10.30.0.0/16"
  zone_count            = 3
  high_availability_nat = true
  node_pools = {
    system = { machine_type = "Standard_D2s_v5", min_count = 2, max_count = 3 }
    apps   = { machine_type = "Standard_D4s_v5", min_count = 1, max_count = 4 }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = "staging.northline.ca"
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = false
  signing_key_ids     = var.signing_key_ids

  # S-114: backups and point-in-time recovery in this region only; staging is re-created from a masked prod copy, not restored.
  backup = { cross_region = false }

  data_stores = {
    postgres = { instance_size = "GP_Standard_D2ds_v5", storage_gb = 64, high_availability = true, backup_retention_days = 7 }
    cache    = { node_size = "Balanced_B1", replicas = 1 }
    # Event Hubs: Premium — Standard allows only 10 event hubs, Northline needs about 50 (topics + .dlq).
    kafka  = { tier = "Premium", capacity = 1, storage_gb = 0 }
    search = { size = "2g", zone_count = 2 }
  }
}
