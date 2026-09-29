# Memorystore for Valkey 8, cluster mode disabled (one primary endpoint), reached through Private Service Connect in
# the data subnet (policy from the network module), TLS in transit (SERVER_AUTHENTICATION: the apps must trust the
# instance's server CA, output cloud.server_ca_certs), CMEK with the data key, replicas across zones when replicas > 0.
# Authentication: Memorystore for Valkey offers IAM auth or none — no static password (the Memorystore *for Redis*
# AUTH string does not exist here). IAM auth needs a token-refreshing client the apps don't have yet, so access is
# limited to the VPC (PSC) and REDIS_PASSWORD stays empty.

locals {
  name = "${var.context.name}-valkey"
  endpoints = flatten([
    for e in google_memorystore_instance.this.endpoints : [
      for c in e.connections : c.psc_auto_connection
    ]
  ])
  primary = try([for c in local.endpoints : c if c.connection_type == "CONNECTION_TYPE_PRIMARY"][0], null)
}

resource "google_memorystore_instance" "this" {
  project                     = var.context.project_id
  instance_id                 = local.name
  location                    = var.context.region
  engine_version              = "VALKEY_8_0"
  mode                        = "CLUSTER_DISABLED"
  shard_count                 = 1
  replica_count               = var.replicas
  node_type                   = var.node_size
  transit_encryption_mode     = "SERVER_AUTHENTICATION"
  authorization_mode          = "AUTH_DISABLED"
  kms_key                     = try(var.kms_key.id, null)
  deletion_protection_enabled = var.deletion_protection
  labels                      = var.context.tags

  desired_auto_created_endpoints {
    network    = var.network_id
    project_id = var.context.project_id
  }

  zone_distribution_config {
    mode = var.replicas > 0 ? "MULTI_ZONE" : "SINGLE_ZONE"
    zone = var.replicas > 0 ? null : "${var.context.region}-a"
  }

  persistence_config {
    mode = var.deletion_protection ? "RDB" : "DISABLED"
  }

  engine_configs = {
    "maxmemory-policy" = "volatile-lru"
  }
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.subnet_ids, var.allowed_cidrs, var.secret_store] # PSC subnet comes from the network's policy
}
