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

# Elastic Cloud: reads EC_API_KEY from the environment.
provider "ec" {}

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

  data_stores = {
    postgres = { instance_size = "db.m7g.large", storage_gb = 100, high_availability = true, backup_retention_days = 35 }
    cache    = { node_size = "cache.m7g.large", replicas = 2 }
    # MSK: brokers must be a multiple of the data subnets (3 zones here).
    kafka  = { tier = "kafka.m7g.large", capacity = 3, storage_gb = 200 }
    search = { size = "4g", zone_count = 2 }
  }
}
