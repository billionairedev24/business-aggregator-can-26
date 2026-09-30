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

# Elastic Cloud: reads EC_API_KEY from the environment.
provider "ec" {}

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
  signing_key_ids     = var.signing_key_ids

  sms_origination_identity = var.sms_origination_identity

  data_stores = {
    postgres = { instance_size = "db.t4g.micro", storage_gb = 20, high_availability = false, backup_retention_days = 7 }
    cache    = { node_size = "cache.t4g.micro", replicas = 0 }
    # MSK: brokers must be a multiple of the data subnets (dev: 2 zones).
    kafka  = { tier = "kafka.t3.small", capacity = 2, storage_gb = 20 }
    search = { size = "2g", zone_count = 1 }
  }
}
