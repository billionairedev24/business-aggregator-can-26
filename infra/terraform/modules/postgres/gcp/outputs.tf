output "db_host" {
  description = "Private IP."
  value       = google_sql_database_instance.this.private_ip_address
}

output "db_port" {
  description = "Port."
  value       = 5432
}

output "db_name" {
  description = "Database name."
  value       = google_sql_database.this.name
}

output "db_user" {
  description = "Value for DB_USER."
  value       = var.app_user
}

output "db_url" {
  description = "Value for DB_URL."
  value       = "jdbc:postgresql://${google_sql_database_instance.this.private_ip_address}:5432/${google_sql_database.this.name}?sslmode=require"
}

output "db_password_secret_ref" {
  description = "Secret holding DB_PASSWORD (the app role's password)."
  value       = google_secret_manager_secret.app.secret_id
}

output "admin_secret_ref" {
  description = "Secret holding the password of the postgres (cloudsqlsuperuser) user."
  value       = google_secret_manager_secret.admin.secret_id
}

output "backup" {
  description = "S-114: what protects the database (docs/runbooks/backups-dr.md): automated backups with point-in-time recovery for retention_days (pitr_days of transaction logs), and the copy in the secondary region (copy_kind empty = none)."
  value = {
    retention_days = var.backup_retention_days
    pitr_days      = min(7, var.backup_retention_days)
    copy_region    = try(var.backup_copy.region, "")
    copy_kind      = var.backup_copy == null ? "" : "cross-region-read-replica"
    copy_id        = try(google_sql_database_instance.copy[0].name, "")
  }
}

output "cloud" {
  description = "Google Cloud-only details."
  value = {
    instance        = google_sql_database_instance.this.name
    connection_name = google_sql_database_instance.this.connection_name
    admin_username  = "postgres"
  }
}
