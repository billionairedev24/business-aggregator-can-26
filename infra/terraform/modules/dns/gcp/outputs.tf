output "zone_id" {
  description = "Managed zone id."
  value       = google_dns_managed_zone.this.id
}

output "zone_name" {
  description = "Zone name."
  value       = var.zone_name
}

output "name_servers" {
  description = "NS records to create in the parent zone / at the registrar."
  value       = google_dns_managed_zone.this.name_servers
}

output "cloud" {
  description = "Google Cloud-only details."
  value       = { managed_zone = google_dns_managed_zone.this.name }
}
