output "storage_provider" {
  description = "Value for STORAGE_PROVIDER."
  value       = "azure"
}

output "bucket_names" {
  description = "Purpose => container name (STORAGE_BUCKET is bucket_names[\"uploads\"])."
  value       = { for k, v in azurerm_storage_container.this : k => v.name }
}

output "storage_region" {
  description = "Value for STORAGE_REGION (S3 signing region; empty on Google Cloud and Azure, which don't read it)."
  value       = ""
}

output "storage_endpoint" {
  description = "Value for STORAGE_ENDPOINT: the account's blob endpoint https://<account>.blob.core.windows.net (required by the api on Azure)."
  value       = trimsuffix(azurerm_storage_account.this.primary_blob_endpoint, "/")
}

output "storage_encryption_key" {
  description = "Value for STORAGE_ENCRYPTION_KEY: the customer-managed key the api names on every write (AWS: KMS key ARN, SSE-KMS; Google Cloud: Cloud KMS key name, CMEK; Azure: an encryption scope). Empty = the bucket's default encryption."
  # Azure: empty. With kms_key set, the account itself is encrypted with the Key Vault key (customer-managed key on the
  # account), so every blob already uses it; an encryption scope per request would add nothing.
  value = ""
}

output "replica_bucket_names" {
  description = "S-114: purpose => replica bucket (container on Azure) in the secondary region; empty without a replica."
  value       = { for k, v in azurerm_storage_container.replica : k => v.name }
}

output "replica_region" {
  description = "S-114: region of the replica; empty without one."
  value       = try(var.replica.region, "")
}

output "cloud" {
  description = "Azure-only details."
  value = {
    storage_account         = azurerm_storage_account.this.name
    storage_account_id      = azurerm_storage_account.this.id
    replica_storage_account = try(azurerm_storage_account.replica[0].name, "")
  }
}
