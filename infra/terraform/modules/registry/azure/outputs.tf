output "registry_url" {
  description = "Registry login server."
  value       = azurerm_container_registry.this.login_server
}

output "repository_urls" {
  description = "Deployable => image repository URL."
  value       = { for r in var.repositories : r => "${azurerm_container_registry.this.login_server}/northline/${r}" }
}

output "cloud" {
  description = "Azure-only details."
  value = {
    registry_id   = azurerm_container_registry.this.id
    login_command = "az acr login --name ${azurerm_container_registry.this.name}"
  }
}
