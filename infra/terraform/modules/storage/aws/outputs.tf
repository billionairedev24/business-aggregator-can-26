output "storage_provider" {
  description = "Value for STORAGE_PROVIDER."
  value       = "s3"
}

output "bucket_names" {
  description = "Purpose => bucket name (STORAGE_BUCKET is bucket_names[\"uploads\"])."
  value       = { for k, v in aws_s3_bucket.this : k => v.bucket }
}

output "storage_region" {
  description = "Value for STORAGE_REGION."
  value       = var.context.region
}

output "storage_endpoint" {
  description = "Value for STORAGE_ENDPOINT (empty: the SDK's regional default)."
  value       = ""
}

output "storage_encryption_key" {
  description = "Value for STORAGE_ENCRYPTION_KEY: the customer-managed key the api names on every write (AWS: KMS key ARN, SSE-KMS; Google Cloud: Cloud KMS key name, CMEK; Azure: an encryption scope). Empty = the bucket's default encryption."
  value       = var.kms_key == null ? "" : var.kms_key.id
}

output "replica_bucket_names" {
  description = "S-114: purpose => replica bucket (container on Azure) in the secondary region; empty without a replica."
  value       = { for k, v in aws_s3_bucket.replica : k => v.bucket }
}

output "replica_region" {
  description = "S-114: region of the replica; empty without one."
  value       = try(var.replica.region, "")
}

output "cloud" {
  description = "AWS-only details."
  value = {
    bucket_arns         = { for k, v in aws_s3_bucket.this : k => v.arn }
    replica_bucket_arns = { for k, v in aws_s3_bucket.replica : k => v.arn }
    replication_role    = try(aws_iam_role.replication[0].arn, "")
  }
}
