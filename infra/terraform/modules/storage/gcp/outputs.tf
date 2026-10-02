output "storage_provider" {
  description = "Value for STORAGE_PROVIDER."
  value       = "gcs"
}

output "bucket_names" {
  description = "Purpose => bucket name (STORAGE_BUCKET is bucket_names[\"uploads\"])."
  value       = { for k, v in google_storage_bucket.this : k => v.name }
}

output "storage_region" {
  description = "Value for STORAGE_REGION (S3 signing region; empty on Google Cloud and Azure, which don't read it)."
  value       = ""
}

output "storage_endpoint" {
  description = "Value for STORAGE_ENDPOINT (empty: the client's default; an endpoint means an emulator to the api)."
  value       = ""
}

output "storage_encryption_key" {
  description = "Value for STORAGE_ENCRYPTION_KEY: the customer-managed key the api names on every write (AWS: KMS key ARN, SSE-KMS; Google Cloud: Cloud KMS key name, CMEK; Azure: an encryption scope). Empty = the bucket's default encryption."
  value       = var.kms_key == null ? "" : var.kms_key.id
}

output "replica_bucket_names" {
  description = "S-114: purpose => replica bucket (container on Azure) in the secondary region; empty without a replica."
  value       = { for k, v in google_storage_bucket.replica : k => v.name }
}

output "replica_region" {
  description = "S-114: region of the replica; empty without one."
  value       = try(var.replica.region, "")
}

output "cloud" {
  description = "Google Cloud-only details."
  value = {
    bucket_urls       = { for k, v in google_storage_bucket.this : k => v.url }
    replica_jobs      = { for k, v in google_storage_transfer_job.replica : k => v.name }
    transfer_identity = try(data.google_storage_transfer_project_service_account.this[0].email, "")
  }
}
