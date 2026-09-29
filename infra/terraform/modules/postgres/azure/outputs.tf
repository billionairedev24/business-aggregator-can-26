output "db_host" {
  description = "Server FQDN (resolves privately inside the VNet)."
  value       = azurerm_postgresql_flexible_server.this.fqdn
}

output "db_port" {
  description = "Port."
  value       = 5432
}

output "db_name" {
  description = "Database name."
  value       = azurerm_postgresql_flexible_server_database.this.name
}

output "db_user" {
  description = "Value for DB_USER."
  value       = var.app_user
}

output "db_url" {
  description = "Value for DB_URL."
  value       = "jdbc:postgresql://${azurerm_postgresql_flexible_server.this.fqdn}:5432/${azurerm_postgresql_flexible_server_database.this.name}?sslmode=require"
}

output "db_password_secret_ref" {
  description = "Secret holding DB_PASSWORD (the app role's password)."
  value       = azurerm_key_vault_secret.app.name
}

output "admin_secret_ref" {
  description = "Secret holding the northline_admin password."
  value       = azurerm_key_vault_secret.admin.name
}

output "cloud" {
  description = "Azure-only details."
  value = {
    server_id      = azurerm_postgresql_flexible_server.this.id
    admin_username = azurerm_postgresql_flexible_server.this.administrator_login
  }
}
