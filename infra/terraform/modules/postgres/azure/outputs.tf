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

output "backup" {
  description = "S-114: what protects the database (docs/runbooks/backups-dr.md): automated backups with point-in-time recovery for retention_days (pitr_days of transaction logs), and the copy in the secondary region (copy_kind empty = none)."
  value = {
    retention_days = var.backup_retention_days
    pitr_days      = var.backup_retention_days
    copy_region    = azurerm_postgresql_flexible_server.this.geo_redundant_backup_enabled ? coalesce(try(var.backup_copy.region, null), local.paired_region) : ""
    copy_kind      = azurerm_postgresql_flexible_server.this.geo_redundant_backup_enabled ? "geo-redundant-backup" : ""
    copy_id        = azurerm_postgresql_flexible_server.this.geo_redundant_backup_enabled ? azurerm_postgresql_flexible_server.this.id : ""
  }
}

output "cloud" {
  description = "Azure-only details."
  value = {
    server_id      = azurerm_postgresql_flexible_server.this.id
    admin_username = azurerm_postgresql_flexible_server.this.administrator_login
  }
}
