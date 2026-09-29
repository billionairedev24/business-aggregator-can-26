provider "google" {
  project = var.project_id
  region  = var.region
  default_labels = {
    app              = "northline"
    env              = "dev"
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
    env              = "dev"
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }
}

# Elastic Cloud: reads EC_API_KEY from the environment.
provider "ec" {}

# dev: smallest sizes, NAT shared, nothing protected from destroy; can be scaled to zero out of hours.
module "northline" {
  source = "../../../stacks/gcp"

  environment = "dev"
  region      = var.region
  owner       = var.owner
  project_id  = var.project_id

  cidr                  = "10.20.0.0/16"
  zone_count            = 3
  high_availability_nat = false
  node_pools = {
    general = { machine_type = "e2-standard-4", min_count = 1, max_count = 3, spot = true }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = "dev.northline.ca"
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = false

  data_stores = {
    postgres = { instance_size = "db-custom-1-3840", storage_gb = 20, high_availability = false, backup_retention_days = 7 }
    cache    = { node_size = "SHARED_CORE_NANO", replicas = 0 }
    # Managed Kafka is sized in vCPUs (minimum 3); tier is ignored.
    kafka  = { tier = "-", capacity = 3, storage_gb = 100 }
    search = { size = "2g", zone_count = 1 }
  }
}
