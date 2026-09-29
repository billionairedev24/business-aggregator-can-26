output "redis_host" {
  description = "Value for REDIS_HOST (resolves to the private endpoint inside the VNet)."
  value       = azurerm_managed_redis.this.hostname
}

output "redis_port" {
  description = "Value for REDIS_PORT."
  value       = azurerm_managed_redis.this.default_database[0].port
}

output "redis_ssl" {
  description = "Value for REDIS_SSL."
  value       = true
}

output "redis_username" {
  description = "Value for REDIS_USERNAME (empty: access-key authentication)."
  value       = ""
}

output "redis_password_secret_ref" {
  description = "Secret holding REDIS_PASSWORD."
  value       = azurerm_key_vault_secret.password.name
}

output "cloud" {
  description = "Azure-only details."
  value = {
    managed_redis_id = azurerm_managed_redis.this.id
  }
}
