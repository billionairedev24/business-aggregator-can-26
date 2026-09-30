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
