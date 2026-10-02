provider "google" {
  project = var.project_id
  region  = var.region
  default_labels = {
    app              = "northline"
    env              = "staging"
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
  default_labels = {
    app              = "northline"
    env              = "staging"
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }
}

# Elastic Cloud: reads EC_API_KEY from the environment.
provider "ec" {}

# staging: prod topology (multi-zone, replicas) at smaller sizes.
module "northline" {
  source = "../../../stacks/gcp"

  environment = "staging"
  region      = var.region
  owner       = var.owner
  project_id  = var.project_id

  cidr                  = "10.30.0.0/16"
  zone_count            = 3
  high_availability_nat = true
  node_pools = {
    # Regional cluster: counts are per zone (x3).
    general = { machine_type = "e2-standard-4", min_count = 1, max_count = 2 }
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
    postgres = { instance_size = "db-custom-2-7680", storage_gb = 50, high_availability = true, backup_retention_days = 7 }
    cache    = { node_size = "STANDARD_SMALL", replicas = 1 }
    # Managed Kafka is sized in vCPUs (minimum 3); tier is ignored.
    kafka  = { tier = "-", capacity = 3, storage_gb = 100 }
    search = { size = "2g", zone_count = 2 }
  }
}
