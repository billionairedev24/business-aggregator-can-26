output "redis_host" {
  description = "Value for REDIS_HOST (primary endpoint)."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "redis_port" {
  description = "Value for REDIS_PORT."
  value       = aws_elasticache_replication_group.this.port
}

output "redis_ssl" {
  description = "Value for REDIS_SSL."
  value       = true
}

output "redis_username" {
  description = "Value for REDIS_USERNAME (empty: the default user with the AUTH token)."
  value       = ""
}

output "redis_password_secret_ref" {
  description = "Secret holding REDIS_PASSWORD (null when the service has no password)."
  value       = aws_secretsmanager_secret.auth.name
}

output "cloud" {
  description = "AWS-only details."
  value = {
    reader_endpoint   = aws_elasticache_replication_group.this.reader_endpoint_address
    security_group_id = aws_security_group.this.id
  }
}
