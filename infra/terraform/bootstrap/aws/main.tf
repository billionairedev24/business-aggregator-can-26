# One-off, per AWS account: the S3 bucket (and DynamoDB lock table) that hold Terraform state for every Northline
# environment in that account. Run with local state (this root never stores its own state remotely):
#   terraform init && terraform apply -var owner=platform
# then copy the outputs into envs/aws/<env>/backend.hcl.

terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
  }
}

variable "region" {
  description = "Canadian region for the state bucket."
  type        = string
  default     = "ca-central-1"

  validation {
    condition     = contains(["ca-central-1", "ca-west-1"], var.region)
    error_message = "Canadian data residency: region must be ca-central-1 or ca-west-1."
  }
}

variable "owner" {
  description = "Owner tag."
  type        = string
  default     = "platform"
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      app              = "northline"
      env              = "shared"
      owner            = var.owner
      "data-residency" = "ca"
      "managed-by"     = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "state" {
  bucket = "northline-tfstate-${data.aws_caller_identity.current.account_id}"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.state]
}

resource "aws_dynamodb_table" "lock" {
  name         = "northline-tfstate-lock"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }
}

output "backend_hcl" {
  description = "Settings for envs/aws/<env>/backend.hcl (replace <env>)."
  value       = <<-EOT
    bucket         = "${aws_s3_bucket.state.bucket}"
    key            = "aws/<env>/terraform.tfstate"
    region         = "${var.region}"
    encrypt        = true
    use_lockfile   = true
    # dynamodb_table = "${aws_dynamodb_table.lock.name}"
  EOT
}
