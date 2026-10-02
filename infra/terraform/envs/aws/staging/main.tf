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

# Elastic Cloud: reads EC_API_KEY from the environment.
provider "ec" {}

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
  signing_key_ids     = var.signing_key_ids

  # S-114: backups and point-in-time recovery in this region only; staging is re-created from a masked prod copy, not restored.
  backup = { cross_region = false }

  sms_origination_identity = var.sms_origination_identity

  data_stores = {
    postgres = { instance_size = "db.t4g.medium", storage_gb = 50, high_availability = true, backup_retention_days = 7 }
    cache    = { node_size = "cache.t4g.small", replicas = 1 }
    # MSK: brokers must be a multiple of the data subnets (3 zones here).
    kafka  = { tier = "kafka.t3.small", capacity = 3, storage_gb = 50 }
    search = { size = "2g", zone_count = 2 }
  }
}
