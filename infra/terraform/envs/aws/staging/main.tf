provider "aws" {
  region = var.region

  default_tags {
    tags = {
      app              = "northline"
      env              = "staging"
      owner            = var.owner
      "data-residency" = "ca"
      "managed-by"     = "terraform"
    }
  }
}

# staging: prod topology (multi-zone, replicas) at smaller sizes.
module "northline" {
  source = "../../../stacks/aws"

  environment = "staging"
  region      = var.region
  owner       = var.owner

  cidr                  = "10.30.0.0/16"
  zone_count            = 3
  high_availability_nat = true
  node_pools = {
    general = { machine_type = "m7i.large", min_count = 2, max_count = 4 }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = "staging.northline.ca"
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = false
}
