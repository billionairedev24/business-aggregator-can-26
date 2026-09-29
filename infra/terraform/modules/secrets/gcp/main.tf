# Secret Manager: secrets named <context.name>-<name>, replicated only inside the Canadian region (user-managed
# replication; automatic replication would copy them worldwide). Readers (External Secrets Operator) get
# secretAccessor on every secret with the environment's prefix, including those the data-store modules create (S-3).
# Secrets are created empty: values are added by an operator (gcloud secrets versions add).

data "google_project" "this" {
  project_id = var.context.project_id
}

locals {
  prefix = "${var.context.name}-"
}

resource "google_secret_manager_secret" "this" {
  for_each  = toset(var.secret_names)
  project   = var.context.project_id
  secret_id = "${local.prefix}${each.value}"

  deletion_protection = var.deletion_protection
  labels              = var.context.tags

  replication {
    user_managed {
      replicas {
        location = var.context.region
        dynamic "customer_managed_encryption" {
          for_each = var.kms_key == null ? [] : [try(var.kms_key.id, null)]
          content {
            kms_key_name = customer_managed_encryption.value
          }
        }
      }
    }
  }
}

resource "google_project_iam_member" "reader" {
  for_each = var.readers
  project  = var.context.project_id
  role     = "roles/secretmanager.secretAccessor"
  member   = each.value

  condition {
    title       = "${var.context.name}-secrets"
    description = "Secrets of ${var.context.name} only"
    expression  = "resource.name.startsWith(\"projects/${data.google_project.this.number}/secrets/${local.prefix}\")"
  }
}
