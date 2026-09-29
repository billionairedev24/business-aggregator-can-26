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
}
