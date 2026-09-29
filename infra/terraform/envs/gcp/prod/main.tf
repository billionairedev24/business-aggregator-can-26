provider "google" {
  project = var.project_id
  region  = var.region
  default_labels = {
    app              = "northline"
    env              = "prod"
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
    env              = "prod"
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }
}

# Elastic Cloud: reads EC_API_KEY from the environment.
provider "ec" {}

# prod: multi-zone HA, deletion protection on.
module "northline" {
  source = "../../../stacks/gcp"

  environment = "prod"
  region      = var.region
  owner       = var.owner
  project_id  = var.project_id

  cidr                  = "10.40.0.0/16"
  zone_count            = 3
  high_availability_nat = true
  node_pools = {
    # Regional cluster: counts are per zone (x3).
    general = { machine_type = "n2-standard-4", min_count = 1, max_count = 3 }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = "northline.ca"
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = true

  data_stores = {
    postgres = { instance_size = "db-custom-4-16384", storage_gb = 100, high_availability = true, backup_retention_days = 35 }
    cache    = { node_size = "STANDARD_SMALL", replicas = 2 }
    # Managed Kafka is sized in vCPUs (minimum 3); tier is ignored.
    kafka  = { tier = "-", capacity = 6, storage_gb = 200 }
    search = { size = "4g", zone_count = 2 }
  }
}
