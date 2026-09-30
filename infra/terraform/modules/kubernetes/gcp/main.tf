# GKE Standard: regional (staging/prod) or zonal (dev) cluster with private nodes, Workload Identity, Dataplane V2,
# application-layer secrets encryption with Cloud KMS, one node pool per entry, and a Google service account per
# workload identity bound to its Kubernetes service account.

data "google_project" "this" {
  project_id = var.context.project_id
}

locals {
  project = var.context.project_id
  # dev: one zone to save cost; otherwise the whole region.
  location      = var.context.environment == "dev" ? "${var.context.region}-a" : var.context.region
  workload_pool = "${local.project}.svc.id.goog"
}

# Node service account with the minimum roles (logs, metrics, image pulls are granted by the registry module).
resource "google_service_account" "nodes" {
  project      = local.project
  account_id   = "${var.context.name}-nodes"
  display_name = "GKE nodes ${var.context.name}"
}

resource "google_project_iam_member" "nodes" {
  for_each = toset([
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/monitoring.viewer",
    "roles/stackdriver.resourceMetadata.writer",
    "roles/autoscaling.metricsWriter",
  ])
  project = local.project
  role    = each.value
  member  = "serviceAccount:${google_service_account.nodes.email}"
}

# The GKE service agent encrypts Secrets with the data key.
resource "google_kms_crypto_key_iam_member" "gke_agent" {
  count         = var.kms_key == null ? 0 : 1
  crypto_key_id = try(var.kms_key.id, null)
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:service-${data.google_project.this.number}@container-engine-robot.iam.gserviceaccount.com"
}

resource "google_container_cluster" "this" {
  project             = local.project
  name                = var.context.name
  location            = local.location
  network             = var.network_id
  subnetwork          = var.subnet_ids[0]
  min_master_version  = var.kubernetes_version
  deletion_protection = var.deletion_protection
  datapath_provider   = "ADVANCED_DATAPATH"
  networking_mode     = "VPC_NATIVE"
  resource_labels     = var.context.tags

  remove_default_node_pool = true
  initial_node_count       = 1

  release_channel {
    channel = var.context.environment == "prod" ? "STABLE" : "REGULAR"
  }

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
  }

  master_authorized_networks_config {
    dynamic "cidr_blocks" {
      for_each = length(var.api_allowed_cidrs) > 0 ? var.api_allowed_cidrs : ["0.0.0.0/0"]
      content {
        cidr_block   = cidr_blocks.value
        display_name = "allowed-${cidr_blocks.key}"
      }
    }
  }

  workload_identity_config {
    workload_pool = local.workload_pool
  }

  dynamic "database_encryption" {
    for_each = var.kms_key == null ? [] : [try(var.kms_key.id, null)]
    content {
      state    = "ENCRYPTED"
      key_name = database_encryption.value
    }
  }

  security_posture_config {
    mode               = "BASIC"
    vulnerability_mode = "VULNERABILITY_BASIC"
  }

  gateway_api_config {
    channel = "CHANNEL_STANDARD"
  }

  depends_on = [google_kms_crypto_key_iam_member.gke_agent]
}

resource "google_container_node_pool" "this" {
  for_each = var.node_pools
  project  = local.project
  name     = each.key
  location = local.location
  cluster  = google_container_cluster.this.name
  version  = var.kubernetes_version

  # Regional clusters: min/max are per zone.
  autoscaling {
    min_node_count = each.value.min_count
    max_node_count = each.value.max_count
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  node_config {
    machine_type    = each.value.machine_type
    disk_size_gb    = each.value.disk_size_gb
    disk_type       = "pd-balanced"
    spot            = each.value.spot
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    labels          = merge(each.value.labels, { "northline.ca/pool" = each.key })
    resource_labels = var.context.tags

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
  }

  lifecycle {
    ignore_changes = [initial_node_count]
  }
}

resource "google_project_iam_member" "admin" {
  for_each = toset(var.admin_principals)
  project  = local.project
  role     = "roles/container.admin"
  member   = each.value
}

# ---- Workload Identity -----------------------------------------------------------------------------------------

resource "google_service_account" "workload" {
  for_each     = var.workload_identities
  project      = local.project
  account_id   = substr("${var.context.environment}-${each.key}", 0, 30)
  display_name = "${var.context.name} ${each.value.namespace}/${each.value.service_account}"
}

resource "google_service_account_iam_member" "workload" {
  for_each           = var.workload_identities
  service_account_id = google_service_account.workload[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${local.workload_pool}[${each.value.namespace}/${each.value.service_account}]"
}
