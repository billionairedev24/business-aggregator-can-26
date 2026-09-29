output "secrets_provider" {
  description = "External Secrets Operator provider for this store (SecretStore spec.provider)."
  value       = "azurekv"
}

output "store" {
  description = "Handle passed to the data-store modules (S-3): where and how they write generated secrets."
  value = {
    id         = azurerm_key_vault.this.id
    prefix     = ""
    kms_key_id = null
  }

  depends_on = [azurerm_role_assignment.operator]
}

output "secret_refs" {
  description = "Secret name => reference for an ExternalSecret remoteRef.key (set the values with az keyvault secret set)."
  value       = { for n in var.secret_names : n => n }
}

output "cloud" {
  description = "Azure-only details."
  value = {
    key_vault_uri = azurerm_key_vault.this.vault_uri
    key_vault_id  = azurerm_key_vault.this.id
  }
}
