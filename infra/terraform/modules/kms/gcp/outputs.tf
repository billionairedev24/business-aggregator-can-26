output "kms_provider" {
  description = "Value for KMS_PROVIDER."
  value       = "gcp"
}

output "key_ids" {
  description = "Key name => crypto key id (for other modules' kms_key_id)."
  value       = { for k, v in google_kms_crypto_key.this : k => v.id }
}

output "key_refs" {
  description = "Key name => value for KMS_KEY_ID (the crypto key name; the adapter picks the primary/latest version)."
  value       = { for k, v in google_kms_crypto_key.this : k => v.id }
}

output "cloud" {
  description = "Google Cloud-only details."
  value       = { key_ring = google_kms_key_ring.this.id }
}
