# Google Cloud Managed Service for Apache Kafka: a cluster sized in vCPUs (4 GiB RAM each), reachable through Private
# Service Connect in the data subnet, CMEK with the data key (service agent granted by the stack). Clients use
# SASL/PLAIN over TLS on port 9092 with a service account: username = the account's email, password = its key
# (base64 JSON). The key is generated here and only the ready-made JAAS line is stored in the secrets store.
# OAUTHBEARER with Workload Identity would avoid the key but needs Google's login handler in the apps (later).
# Topics: the service exposes no auto.create.topics.enable setting to rely on; create every topic and its .dlq
# explicitly (scripts/topics.sh, S-25). Replication factor 3 is the service's default and minimum for durability.

locals {
  name        = "${var.context.name}-kafka"
  client_sa   = substr("${var.context.environment}-kafka-client", 0, 30)
  jaas_config = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${google_service_account.client.email}\" password=\"${google_service_account_key.client.private_key}\";"
}

resource "google_managed_kafka_cluster" "this" {
  project    = var.context.project_id
  cluster_id = local.name
  location   = var.context.region
  labels     = var.context.tags

  capacity_config {
    vcpu_count   = tostring(var.capacity)
    memory_bytes = tostring(var.capacity * 4 * 1024 * 1024 * 1024)
  }

  broker_capacity_config {
    disk_size_gib = tostring(var.storage_gb)
  }

  gcp_config {
    kms_key = try(var.kms_key.id, null)
    access_config {
      dynamic "network_configs" {
        for_each = var.subnet_ids
        content {
          subnet = network_configs.value
        }
      }
    }
  }

  rebalance_config {
    mode = "AUTO_REBALANCE_ON_SCALE_UP"
  }
}

resource "google_service_account" "client" {
  project      = var.context.project_id
  account_id   = local.client_sa
  display_name = "Kafka client ${var.context.name} (SASL/PLAIN)"
}

resource "google_project_iam_member" "client" {
  project = var.context.project_id
  role    = "roles/managedkafka.client"
  member  = "serviceAccount:${google_service_account.client.email}"
}

resource "google_service_account_key" "client" {
  service_account_id = google_service_account.client.name
}

resource "google_secret_manager_secret" "jaas" {
  project             = var.secret_store.id
  secret_id           = "${var.secret_store.prefix}kafka-sasl-jaas-config"
  labels              = var.context.tags
  deletion_protection = var.deletion_protection

  replication {
    user_managed {
      replicas {
        location = var.context.region
        dynamic "customer_managed_encryption" {
          for_each = var.secret_store.kms_key == null ? [] : [var.secret_store.kms_key.id]
          content {
            kms_key_name = customer_managed_encryption.value
          }
        }
      }
    }
  }
}

resource "google_secret_manager_secret_version" "jaas" {
  secret      = google_secret_manager_secret.jaas.id
  secret_data = local.jaas_config
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.network_id, var.allowed_cidrs, var.tier, var.topics] # PSC endpoints in subnet_ids; sized by capacity; topics via the Kafka admin API (S-25)
}
