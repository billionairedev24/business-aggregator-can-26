provider "aws" {
  region = var.region

  default_tags {
    tags = {
      app              = "northline"
      env              = "dev"
      owner            = var.owner
      "data-residency" = "ca"
      "managed-by"     = "terraform"
    }
  }
}

# dev: smallest sizes, NAT shared, nothing protected from destroy; can be scaled to zero out of hours.
module "northline" {
  source = "../../../stacks/aws"

  environment = "dev"
  region      = var.region
  owner       = var.owner

  cidr                  = "10.20.0.0/16"
  zone_count            = 2
  high_availability_nat = false
  node_pools = {
    general = { machine_type = "t3.large", min_count = 1, max_count = 3 }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = "dev.northline.ca"
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = false
}
