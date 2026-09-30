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

output "cloud" {
  description = "Google Cloud-only details."
  value       = { bucket_urls = { for k, v in google_storage_bucket.this : k => v.url } }
}
