# Cloud SQL for PostgreSQL 17 (Enterprise edition): private IP only through Private Service Access (no public IP),
# TLS required (ENCRYPTED_ONLY), CMEK with the data key (the Cloud SQL service agent is granted by the stack),
# automated backups kept in the same Canadian region with point-in-time recovery, REGIONAL availability when
# high_availability. PostGIS/citext/pgcrypto are supported extensions; the bootstrap SQL creates them. Alternative
# to the private IP: the Cloud SQL Auth Proxy sidecar (DB_URL=jdbc:postgresql://127.0.0.1:5432/northline).
# Instance names cannot be reused for about a week after deletion.

resource "random_id" "suffix" {
  byte_length = 2
}

locals {
  name = "${var.context.name}-pg-${random_id.suffix.hex}"
}

resource "random_password" "admin" {
  length  = 32
  special = false
}

resource "random_password" "app" {
  length  = 32
  special = false
}

resource "google_sql_database_instance" "this" {
  project             = var.context.project_id
  name                = local.name
  region              = var.context.region
  database_version    = "POSTGRES_${var.postgres_version}"
  root_password       = random_password.admin.result
  encryption_key_name = try(var.kms_key.id, null)
  deletion_protection = var.deletion_protection

  settings {
    edition                     = "ENTERPRISE"
    tier                        = var.instance_size
    availability_type           = var.high_availability ? "REGIONAL" : "ZONAL"
    disk_type                   = "PD_SSD"
    disk_size                   = var.storage_gb
    disk_autoresize             = true
    deletion_protection_enabled = var.deletion_protection
    user_labels                 = var.context.tags

    ip_configuration {
      ipv4_enabled                                  = false
      private_network                               = var.network_id
      ssl_mode                                      = "ENCRYPTED_ONLY"
      enable_private_path_for_google_cloud_services = true
    }

    backup_configuration {
      enabled                        = true
      start_time                     = "07:00"
      location                       = var.context.region # backups stay in Canada
      point_in_time_recovery_enabled = true
      transaction_log_retention_days = min(7, var.backup_retention_days)

      backup_retention_settings {
        retained_backups = var.backup_retention_days
        retention_unit   = "COUNT"
      }
    }

    maintenance_window {
      day          = 7
      hour         = 8
      update_track = "stable"
    }

    insights_config {
      query_insights_enabled = true
    }

    database_flags {
      name  = "log_min_duration_statement"
      value = "500"
    }

    database_flags {
      name  = "idle_in_transaction_session_timeout"
      value = "600000"
    }
  }
}

resource "google_sql_database" "this" {
  project  = var.context.project_id
  name     = var.database_name
  instance = google_sql_database_instance.this.name
}

resource "google_secret_manager_secret" "admin" {
  project             = var.secret_store.id
  secret_id           = "${var.secret_store.prefix}db-admin-password"
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

resource "google_secret_manager_secret_version" "admin" {
  secret      = google_secret_manager_secret.admin.id
  secret_data = random_password.admin.result
}

resource "google_secret_manager_secret" "app" {
  project             = var.secret_store.id
  secret_id           = "${var.secret_store.prefix}db-app-password"
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

resource "google_secret_manager_secret_version" "app" {
  secret      = google_secret_manager_secret.app.id
  secret_data = random_password.app.result
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.subnet_ids, var.allowed_cidrs] # private IP comes from the Private Service Access range
}
