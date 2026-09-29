provider "aws" {
  region = var.region

  default_tags {
    tags = {
      app              = "northline"
      env              = "prod"
      owner            = var.owner
      "data-residency" = "ca"
      "managed-by"     = "terraform"
    }
  }
}

# prod: multi-zone HA, deletion protection on.
module "northline" {
  source = "../../../stacks/aws"

  environment = "prod"
  region      = var.region
  owner       = var.owner

  cidr                  = "10.40.0.0/16"
  zone_count            = 3
  high_availability_nat = true
  node_pools = {
    general = { machine_type = "m7i.xlarge", min_count = 3, max_count = 9 }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = "northline.ca"
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = true
}
