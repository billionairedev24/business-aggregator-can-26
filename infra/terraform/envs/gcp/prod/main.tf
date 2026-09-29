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
}
