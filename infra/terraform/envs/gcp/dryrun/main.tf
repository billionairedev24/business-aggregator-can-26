provider "google" {
  project = var.project_id
  region  = var.region
  zone    = local.zone
  default_labels = {
    app              = "northline"
    env              = "dryrun"
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
  zone    = local.zone
  default_labels = {
    app              = "northline"
    env              = "dryrun"
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }
}

locals {
  zone = coalesce(var.zone, "${var.region}-a")
}

# dryrun — a short-lived demo environment on GKE with fake data only (docs/runbooks/dryrun-gcp.md, DECISIONS
# 2026-10-18): one zone, one small node pool, no managed data stores — Postgres, Valkey, Meilisearch and Kafka run in
# the cluster (deploy/helm/northline-data) or at an external provider (data_services), nothing protected from destroy.
module "northline" {
  source = "../../../stacks/gcp"

  environment = "dryrun"
  region      = var.region
  zone        = local.zone
  owner       = var.owner
  project_id  = var.project_id

  cidr                  = "10.40.0.0/16"
  zone_count            = 2 # unused on Google Cloud (regional subnets); the cluster is zonal anyway
  high_availability_nat = false
  node_pools = {
    general = {
      machine_type = var.machine_type
      min_count    = var.node_count.min
      max_count    = var.node_count.max
      disk_size_gb = 50
      spot         = var.spot_nodes
    }
  }
  api_allowed_cidrs   = var.api_allowed_cidrs
  admin_principals    = var.admin_principals
  dns_zone_name       = var.dns_zone_name
  bucket_name_suffix  = var.bucket_name_suffix
  deletion_protection = false
  signing_key_ids     = var.signing_key_ids

  # Nothing to recover: fake data, torn down after the demos.
  backup = { cross_region = false }

  data_stores   = null
  data_services = var.data_services
}
