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
