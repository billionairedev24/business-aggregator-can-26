# Amazon S3: private buckets, owner-enforced, SSE-KMS with bucket keys, versioning, TLS-only,
# read/write for the given IAM roles (IRSA).

locals {
  names = { for k, _ in var.buckets : k => "${var.context.name}-${k}${var.name_suffix}" }
}

resource "aws_s3_bucket" "this" {
  for_each      = var.buckets
  bucket        = local.names[each.key]
  force_destroy = var.force_destroy
  tags          = merge(var.context.tags, { purpose = each.key })
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = aws_s3_bucket.this
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key == null ? "AES256" : "aws:kms"
      kms_master_key_id = try(var.kms_key.id, null)
    }
    bucket_key_enabled = var.kms_key != null
  }
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  versioning_configuration {
    status = var.buckets[each.key].versioning ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id

  rule {
    id     = "noncurrent-and-incomplete"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.buckets[each.key].noncurrent_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

resource "aws_s3_bucket_cors_configuration" "this" {
  for_each = { for k, v in var.buckets : k => v if length(v.cors_allowed_origins) > 0 }
  bucket   = aws_s3_bucket.this[each.key].id
  cors_rule {
    allowed_methods = ["GET", "PUT", "HEAD"]
    allowed_origins = each.value.cors_allowed_origins
    allowed_headers = ["*"]
    max_age_seconds = 3600
  }
}

resource "aws_s3_bucket_policy" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [each.value.arn, "${each.value.arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      }],
      # Least privilege for the api (docs/runbooks/object-storage.md): object read/write/delete, plus ListBucket so a
      # missing key answers 404 instead of 403.
      [for statement in [{
        Sid       = "WritersObjects"
        Effect    = "Allow"
        Principal = { AWS = values(var.writers) }
        Action    = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource  = "${each.value.arn}/*"
        }, {
        Sid       = "WritersList"
        Effect    = "Allow"
        Principal = { AWS = values(var.writers) }
        Action    = "s3:ListBucket"
        Resource  = each.value.arn
      }] : statement if length(var.writers) > 0]
    )
  })

  depends_on = [aws_s3_bucket_public_access_block.this]
}

# SSE-KMS: the writers also need the key (bucket policy alone is not enough).
resource "aws_iam_role_policy" "writer_kms" {
  for_each = var.kms_key == null ? {} : var.writers
  name     = "s3-kms-${var.context.name}"
  role     = element(split("/", each.value), length(split("/", each.value)) - 1)
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["kms:Decrypt", "kms:GenerateDataKey"]
      Resource = try(var.kms_key.id, null)
    }]
  })
}

# ---- S-114: replica in the other Canadian region ----------------------------------------------------------------
# S3 replication (new objects, new versions, delete markers) into <name>-replica in var.replica.region, SSE-KMS with that
# region's key, versioned; current objects move to STANDARD_IA after cool_after_days, older versions expire after
# noncurrent_days. Replication Time Control + metrics: the bucket reports OperationsFailedReplication and
# ReplicationLatency in CloudWatch, which the alerts watch (docs/runbooks/backups-dr.md § Alerts). Objects written
# before replication was switched on need one S3 Batch Replication job (runbook).

locals {
  replica_buckets = var.replica == null ? {} : var.buckets
  replica_kms_key = var.replica == null ? null : var.replica.kms_key # known at plan time (not try(): unknown ids)
  replica_names   = { for k, _ in local.replica_buckets : k => "${var.context.name}-${k}-replica${var.name_suffix}" }
}

resource "aws_s3_bucket" "replica" {
  for_each      = local.replica_buckets
  region        = var.replica.region
  bucket        = local.replica_names[each.key]
  force_destroy = var.force_destroy
  tags          = merge(var.context.tags, { purpose = "${each.key}-replica" })
}

resource "aws_s3_bucket_public_access_block" "replica" {
  for_each                = aws_s3_bucket.replica
  region                  = var.replica.region
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "replica" {
  for_each = aws_s3_bucket.replica
  region   = var.replica.region
  bucket   = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "replica" {
  for_each = aws_s3_bucket.replica
  region   = var.replica.region
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = local.replica_kms_key == null ? "AES256" : "aws:kms"
      kms_master_key_id = try(var.replica.kms_key.id, null)
    }
    bucket_key_enabled = local.replica_kms_key != null
  }
}

resource "aws_s3_bucket_versioning" "replica" {
  for_each = aws_s3_bucket.replica
  region   = var.replica.region
  bucket   = each.value.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "replica" {
  for_each = aws_s3_bucket.replica
  region   = var.replica.region
  bucket   = each.value.id

  rule {
    id     = "cool-and-expire-noncurrent"
    status = "Enabled"
    filter {}
    transition {
      days          = var.replica.cool_after_days
      storage_class = "STANDARD_IA"
    }
    noncurrent_version_expiration {
      noncurrent_days = var.replica.noncurrent_days
    }
    expiration {
      expired_object_delete_marker = true
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.replica]
}

resource "aws_s3_bucket_policy" "replica" {
  for_each = aws_s3_bucket.replica
  region   = var.replica.region
  bucket   = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [each.value.arn, "${each.value.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.replica]
}

resource "aws_iam_role" "replication" {
  count = var.replica == null ? 0 : 1
  name  = "${var.context.name}-s3-replication"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = var.context.tags
}

resource "aws_iam_role_policy" "replication" {
  count = var.replica == null ? 0 : 1
  name  = "s3-replication"
  role  = aws_iam_role.replication[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Effect   = "Allow"
        Action   = ["s3:GetReplicationConfiguration", "s3:ListBucket"]
        Resource = [for b in aws_s3_bucket.this : b.arn]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObjectVersionForReplication", "s3:GetObjectVersionAcl", "s3:GetObjectVersionTagging"]
        Resource = [for b in aws_s3_bucket.this : "${b.arn}/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ReplicateObject", "s3:ReplicateDelete", "s3:ReplicateTags"]
        Resource = [for b in aws_s3_bucket.replica : "${b.arn}/*"]
      }],
      var.kms_key == null ? [] : [{
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = var.kms_key.id
      }],
      local.replica_kms_key == null ? [] : [{
        Effect   = "Allow"
        Action   = ["kms:Encrypt", "kms:GenerateDataKey"]
        Resource = var.replica.kms_key.id
      }]
    )
  })
}

resource "aws_s3_bucket_replication_configuration" "this" {
  for_each = local.replica_buckets
  bucket   = aws_s3_bucket.this[each.key].id
  role     = aws_iam_role.replication[0].arn

  rule {
    id     = "replica-${var.replica.region}"
    status = "Enabled"
    filter {}

    delete_marker_replication {
      status = "Enabled"
    }

    source_selection_criteria {
      sse_kms_encrypted_objects {
        status = "Enabled"
      }
    }

    destination {
      bucket        = aws_s3_bucket.replica[each.key].arn
      storage_class = "STANDARD"

      dynamic "encryption_configuration" {
        for_each = local.replica_kms_key == null ? [] : [var.replica.kms_key.id]
        content {
          replica_kms_key_id = encryption_configuration.value
        }
      }

      replication_time {
        status = "Enabled"
        time {
          minutes = 15
        }
      }

      metrics {
        status = "Enabled"
        event_threshold {
          minutes = 15
        }
      }
    }
  }

  depends_on = [aws_s3_bucket_versioning.this, aws_s3_bucket_versioning.replica]
}
