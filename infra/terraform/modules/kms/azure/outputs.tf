output "kms_provider" {
  description = "Value for KMS_PROVIDER."
  value       = "azure"
}

output "key_ids" {
  description = "Key name => versioned key URL (AKS KMS and storage CMK take this form)."
  value       = { for k, v in azurerm_key_vault_key.this : k => v.id }
}

output "key_refs" {
  description = "Key name => value for KMS_KEY_ID: the versioned key URL https://<vault>.vault.azure.net/keys/<name>/<version> (S-7 signs with an explicit version)."
  value       = { for k, v in azurerm_key_vault_key.this : k => v.id }
}

output "cloud" {
  description = "Azure-only details."
  value = {
    key_vault_id  = azurerm_key_vault.this.id
    key_vault_uri = azurerm_key_vault.this.vault_uri
  }
}
