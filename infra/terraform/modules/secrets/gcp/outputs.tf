output "secrets_provider" {
  description = "External Secrets Operator provider for this store (SecretStore spec.provider)."
  value       = "gcpsm"
}

output "store" {
  description = "Handle passed to the data-store modules (S-3): where and how they write generated secrets."
  value = {
    id         = var.context.project_id
    prefix     = local.prefix
    kms_key_id = try(var.kms_key.id, null)
  }
}

output "secret_refs" {
  description = "Secret name => reference for an ExternalSecret remoteRef.key."
  value       = { for k, v in google_secret_manager_secret.this : k => v.secret_id }
}

output "cloud" {
  description = "Google Cloud-only details."
  value       = { secret_ids = { for k, v in google_secret_manager_secret.this : k => v.id } }
}
