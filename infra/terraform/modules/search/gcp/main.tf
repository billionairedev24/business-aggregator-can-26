# Elasticsearch 9 on Elastic Cloud in Google Cloud Montréal (Elastic's gcp-northamerica-northeast1; Toronto maps to
# Montréal unless Elastic lists a Toronto region — still in Canada). network_id/subnet_ids are unused: Elastic Cloud is
# reached over HTTPS; restrict it with allowed_cidrs (the Cloud NAT IPs) or Private Service Connect (later).

module "elastic" {
  source          = "../elastic-cloud"
  name            = var.context.name
  ess_region      = "gcp-northamerica-northeast1"
  template_prefix = "gcp"
  size            = var.size
  zone_count      = var.zone_count
  elastic_version = var.elastic_version
  allowed_cidrs   = var.allowed_cidrs
  tags            = var.context.tags
}

resource "google_secret_manager_secret" "password" {
  project             = var.secret_store.id
  secret_id           = "${var.secret_store.prefix}es-password"
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

resource "google_secret_manager_secret_version" "password" {
  secret      = google_secret_manager_secret.password.id
  secret_data = module.elastic.password
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.network_id, var.subnet_ids, var.kms_key]
}
