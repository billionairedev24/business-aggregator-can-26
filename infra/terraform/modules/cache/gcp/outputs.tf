output "redis_host" {
  description = "Value for REDIS_HOST (PSC address of the primary endpoint)."
  value       = try(local.primary.ip_address, null)
}

output "redis_port" {
  description = "Value for REDIS_PORT."
  value       = try(local.primary.port, 6379)
}

output "redis_ssl" {
  description = "Value for REDIS_SSL."
  value       = true
}

output "redis_username" {
  description = "Value for REDIS_USERNAME (empty)."
  value       = ""
}

output "redis_password_secret_ref" {
  description = "Secret holding REDIS_PASSWORD (null: Memorystore for Valkey has no static password)."
  value       = null
}

output "cloud" {
  description = "Google Cloud-only details."
  value = {
    instance        = google_memorystore_instance.this.name
    server_ca_certs = google_memorystore_instance.this.managed_server_ca
  }
}
