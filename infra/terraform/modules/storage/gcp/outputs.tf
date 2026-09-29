output "storage_provider" {
  description = "Value for STORAGE_PROVIDER."
  value       = "gcs"
}

output "bucket_names" {
  description = "Purpose => bucket name (STORAGE_BUCKET is bucket_names[\"uploads\"])."
  value       = { for k, v in google_storage_bucket.this : k => v.name }
}

output "storage_region" {
  description = "Value for STORAGE_REGION."
  value       = var.context.region
}

output "storage_endpoint" {
  description = "Value for STORAGE_ENDPOINT (empty: the client's default)."
  value       = ""
}

output "cloud" {
  description = "Google Cloud-only details."
  value       = { bucket_urls = { for k, v in google_storage_bucket.this : k => v.url } }
}
