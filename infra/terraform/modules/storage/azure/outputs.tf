output "storage_provider" {
  description = "Value for STORAGE_PROVIDER."
  value       = "azure"
}

output "bucket_names" {
  description = "Purpose => container name (STORAGE_BUCKET is bucket_names[\"uploads\"])."
  value       = { for k, v in azurerm_storage_container.this : k => v.name }
}

output "storage_region" {
  description = "Value for STORAGE_REGION."
  value       = var.context.region
}

output "storage_endpoint" {
  description = "Value for STORAGE_ENDPOINT (the account's blob endpoint)."
  value       = azurerm_storage_account.this.primary_blob_endpoint
}

output "cloud" {
  description = "Azure-only details."
  value = {
    storage_account    = azurerm_storage_account.this.name
    storage_account_id = azurerm_storage_account.this.id
  }
}
