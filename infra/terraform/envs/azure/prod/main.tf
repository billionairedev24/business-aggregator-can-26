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

# Elastic Cloud: reads EC_API_KEY from the environment.
provider "ec" {}

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
  signing_key_ids     = var.signing_key_ids

  # S-114: copies of the database and the buckets in the other Canadian region (docs/runbooks/backups-dr.md).
  backup = { cross_region = true, copy_retention_days = 14 }

  data_stores = {
    postgres = { instance_size = "GP_Standard_D4ds_v5", storage_gb = 128, high_availability = true, backup_retention_days = 35 }
    cache    = { node_size = "Balanced_B3", replicas = 1 }
    # Event Hubs: Premium — Standard allows only 10 event hubs, Northline needs about 50 (topics + .dlq).
    kafka  = { tier = "Premium", capacity = 2, storage_gb = 0 }
    search = { size = "4g", zone_count = 2 }
  }
}
