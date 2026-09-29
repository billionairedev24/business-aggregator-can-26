output "db_host" {
  description = "Endpoint host."
  value       = aws_db_instance.this.address
}

output "db_port" {
  description = "Port."
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Database name."
  value       = var.database_name
}

output "db_user" {
  description = "Value for DB_USER."
  value       = var.app_user
}

output "db_url" {
  description = "Value for DB_URL."
  value       = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${var.database_name}?sslmode=require"
}

output "db_password_secret_ref" {
  description = "Secret holding DB_PASSWORD (the app role's password)."
  value       = aws_secretsmanager_secret.app.name
}

output "admin_secret_ref" {
  description = "Secret holding the admin credentials (username/password JSON, managed and rotated by RDS)."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "cloud" {
  description = "AWS-only details."
  value = {
    instance_id       = aws_db_instance.this.identifier
    security_group_id = aws_security_group.this.id
    admin_username    = aws_db_instance.this.username
  }
}
