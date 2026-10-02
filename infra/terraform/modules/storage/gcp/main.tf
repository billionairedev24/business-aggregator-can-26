# Cloud Storage: private single-region buckets in the Canadian region, uniform access, public access prevention,
# versioning with a noncurrent-version expiry, CMEK (the Cloud Storage service agent is granted by the stack).

locals {
  names = { for k, _ in var.buckets : k => "${var.context.name}-${k}${var.name_suffix}" }
}

resource "google_storage_bucket" "this" {
  for_each                    = var.buckets
  project                     = var.context.project_id
  name                        = local.names[each.key]
  location                    = upper(var.context.region)
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = var.force_destroy
  labels                      = merge(var.context.tags, { purpose = each.key })

  versioning {
    enabled = each.value.versioning
  }

  lifecycle_rule {
    condition {
      days_since_noncurrent_time = each.value.noncurrent_days
      with_state                 = "ARCHIVED"
    }
    action {
      type = "Delete"
    }
  }

  lifecycle_rule {
    condition {
      age = 7
    }
    action {
      type = "AbortIncompleteMultipartUpload"
    }
  }

  dynamic "encryption" {
    for_each = var.kms_key == null ? [] : [try(var.kms_key.id, null)]
    content {
      default_kms_key_name = encryption.value
    }
  }

  dynamic "cors" {
    for_each = length(each.value.cors_allowed_origins) > 0 ? [each.value.cors_allowed_origins] : []
    content {
      origin          = cors.value
      method          = ["GET", "PUT", "HEAD"]
      response_header = ["*"]
      max_age_seconds = 3600
    }
  }
}

resource "google_storage_bucket_iam_member" "writers" {
  for_each = merge([
    for b in keys(var.buckets) : { for label, member in var.writers : "${b}/${label}" => { bucket = b, member = member } }
  ]...)
  bucket = google_storage_bucket.this[each.value.bucket].name
  role   = "roles/storage.objectUser"
  member = each.value.member
}

# ---- S-114: replica in the other Canadian region ----------------------------------------------------------------
# A versioned bucket <name>-replica in var.replica.region (CMEK with that region's key; the stack grants the Cloud
# Storage service agent on it) and an event-driven Storage Transfer Service replication job per bucket: new and
# changed objects are copied within minutes (deletes are not propagated, so the replica also keeps what was deleted
# until its lifecycle expires it). Current objects move to NEARLINE after cool_after_days; noncurrent versions expire
# after noncurrent_days. Objects older than the job need a one-off batch transfer (runbook). Failed copies show in the
# job's operations and the storagetransfer.googleapis.com metrics (docs/runbooks/backups-dr.md § Alerts).

locals {
  replica_buckets = var.replica == null ? {} : var.buckets
  replica_kms_key = var.replica == null ? null : var.replica.kms_key # known at plan time (not try(): unknown ids)
}

data "google_storage_transfer_project_service_account" "this" {
  count   = var.replica == null ? 0 : 1
  project = var.context.project_id
}

resource "google_storage_bucket" "replica" {
  for_each                    = local.replica_buckets
  project                     = var.context.project_id
  name                        = "${local.names[each.key]}-replica"
  location                    = upper(var.replica.region)
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = var.force_destroy
  labels                      = merge(var.context.tags, { purpose = "${each.key}-replica" })

  versioning {
    enabled = true
  }

  lifecycle_rule {
    condition {
      age        = var.replica.cool_after_days
      with_state = "LIVE"
    }
    action {
      type          = "SetStorageClass"
      storage_class = "NEARLINE"
    }
  }

  lifecycle_rule {
    condition {
      days_since_noncurrent_time = var.replica.noncurrent_days
      with_state                 = "ARCHIVED"
    }
    action {
      type = "Delete"
    }
  }

  dynamic "encryption" {
    for_each = local.replica_kms_key == null ? [] : [var.replica.kms_key.id]
    content {
      default_kms_key_name = encryption.value
    }
  }
}

# The Storage Transfer Service agent reads the source and writes the replica.
resource "google_storage_bucket_iam_member" "transfer_source" {
  for_each = { for pair in setproduct(keys(local.replica_buckets), ["roles/storage.objectViewer", "roles/storage.legacyBucketReader"]) : "${pair[0]}/${pair[1]}" => { bucket = pair[0], role = pair[1] } }
  bucket   = google_storage_bucket.this[each.value.bucket].name
  role     = each.value.role
  member   = "serviceAccount:${data.google_storage_transfer_project_service_account.this[0].email}"
}

resource "google_storage_bucket_iam_member" "transfer_sink" {
  for_each = { for pair in setproduct(keys(local.replica_buckets), ["roles/storage.objectAdmin", "roles/storage.legacyBucketWriter"]) : "${pair[0]}/${pair[1]}" => { bucket = pair[0], role = pair[1] } }
  bucket   = google_storage_bucket.replica[each.value.bucket].name
  role     = each.value.role
  member   = "serviceAccount:${data.google_storage_transfer_project_service_account.this[0].email}"
}

resource "google_storage_transfer_job" "replica" {
  for_each    = local.replica_buckets
  project     = var.context.project_id
  description = "${local.names[each.key]} -> ${var.replica.region} replica (S-114)"

  replication_spec {
    gcs_data_source {
      bucket_name = google_storage_bucket.this[each.key].name
    }
    gcs_data_sink {
      bucket_name = google_storage_bucket.replica[each.key].name
    }
    transfer_options {
      overwrite_when = "DIFFERENT"
      metadata_options {
        kms_key       = "KMS_KEY_DESTINATION_BUCKET_DEFAULT"
        storage_class = "STORAGE_CLASS_DESTINATION_BUCKET_DEFAULT"
      }
    }
  }

  logging_config {
    log_actions       = ["COPY"]
    log_action_states = ["FAILED"]
  }

  depends_on = [google_storage_bucket_iam_member.transfer_source, google_storage_bucket_iam_member.transfer_sink]
}
