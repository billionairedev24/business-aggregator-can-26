# Google Cloud network: custom-mode VPC with a regional cluster subnet (secondary ranges "pods" and "services" for
# GKE) and a data subnet, Cloud NAT for private nodes, Private Service Access (Cloud SQL private IP) and a
# Private Service Connect policy for Memorystore. Subnets are regional, so zone_count only matters on AWS.
# dryrun (docs/runbooks/dryrun-gcp.md): no managed data stores and public nodes, so no NAT, no PSA, no PSC policy.

locals {
  managed_egress = var.context.environment != "dryrun"
  project        = var.context.project_id
  cluster_cidr   = cidrsubnet(var.cidr, 4, 0)   # /20 nodes
  data_cidr      = cidrsubnet(var.cidr, 8, 210) # /24 PSC endpoints (Memorystore, Managed Kafka)
  pods_cidr      = "10.100.0.0/14"
  services_cidr  = "10.104.0.0/20"
  psa_prefix     = 20 # Private Service Access range for Cloud SQL
}

resource "google_compute_network" "this" {
  project                         = local.project
  name                            = var.context.name
  auto_create_subnetworks         = false
  routing_mode                    = "REGIONAL"
  delete_default_routes_on_create = false
}

resource "google_compute_subnetwork" "cluster" {
  project                  = local.project
  name                     = "${var.context.name}-cluster"
  region                   = var.context.region
  network                  = google_compute_network.this.id
  ip_cidr_range            = local.cluster_cidr
  private_ip_google_access = true

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = local.pods_cidr
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = local.services_cidr
  }

  log_config {
    aggregation_interval = "INTERVAL_10_MIN"
    flow_sampling        = contains(["dev", "dryrun"], var.context.environment) ? 0.1 : 0.5
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_subnetwork" "data" {
  project                  = local.project
  name                     = "${var.context.name}-data"
  region                   = var.context.region
  network                  = google_compute_network.this.id
  ip_cidr_range            = local.data_cidr
  private_ip_google_access = true

  log_config {
    aggregation_interval = "INTERVAL_10_MIN"
    flow_sampling        = 0.1
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_router" "this" {
  count   = local.managed_egress ? 1 : 0
  project = local.project
  name    = var.context.name
  region  = var.context.region
  network = google_compute_network.this.id
}

resource "google_compute_address" "nat" {
  count   = local.managed_egress ? (var.high_availability_nat ? 2 : 1) : 0
  project = local.project
  name    = "${var.context.name}-nat-${count.index}"
  region  = var.context.region
  labels  = var.context.tags
}

resource "google_compute_router_nat" "this" {
  count                              = local.managed_egress ? 1 : 0
  project                            = local.project
  name                               = var.context.name
  router                             = google_compute_router.this[0].name
  region                             = var.context.region
  nat_ip_allocate_option             = "MANUAL_ONLY"
  nat_ips                            = google_compute_address.nat[*].self_link
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# One instance each outside dryrun; the moved blocks keep the addresses of state written before the count existed.
moved {
  from = google_compute_router.this
  to   = google_compute_router.this[0]
}

moved {
  from = google_compute_router_nat.this
  to   = google_compute_router_nat.this[0]
}

moved {
  from = google_compute_global_address.private_service_access
  to   = google_compute_global_address.private_service_access[0]
}

moved {
  from = google_service_networking_connection.this
  to   = google_service_networking_connection.this[0]
}

moved {
  from = google_network_connectivity_service_connection_policy.memorystore
  to   = google_network_connectivity_service_connection_policy.memorystore[0]
}

# Private Service Access: Cloud SQL gets a private IP inside this VPC (no public IP).
resource "google_compute_global_address" "private_service_access" {
  count         = local.managed_egress ? 1 : 0
  project       = local.project
  name          = "${var.context.name}-psa"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = local.psa_prefix
  network       = google_compute_network.this.id
  labels        = var.context.tags
}

resource "google_service_networking_connection" "this" {
  count                   = local.managed_egress ? 1 : 0
  network                 = google_compute_network.this.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_service_access[0].name]
}

# Private Service Connect: Memorystore for Valkey creates its endpoints in the data subnet.
resource "google_network_connectivity_service_connection_policy" "memorystore" {
  count         = local.managed_egress ? 1 : 0
  project       = local.project
  name          = "${var.context.name}-memorystore"
  location      = var.context.region
  service_class = "gcp-memorystore"
  network       = google_compute_network.this.id
  labels        = var.context.tags

  psc_config {
    subnetworks = [google_compute_subnetwork.data.id]
  }
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.zone_count]
}
