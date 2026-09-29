output "es_uris" {
  description = "Value for ES_URIS."
  value       = module.elastic.https_endpoint
}

output "es_username" {
  description = "Value for ES_USERNAME (the deployment superuser until a least-privilege user exists)."
  value       = module.elastic.username
}

output "es_password_secret_ref" {
  description = "Secret holding ES_PASSWORD."
  value       = azurerm_key_vault_secret.password.name
}

output "cloud" {
  description = "Elastic Cloud details."
  value = {
    deployment_id = module.elastic.deployment_id
    version       = module.elastic.version
    ess_region    = "azure-canadacentral"
  }
}
