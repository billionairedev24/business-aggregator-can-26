output "zone_id" {
  description = "DNS zone resource id."
  value       = azurerm_dns_zone.this.id
}

output "zone_name" {
  description = "Zone name."
  value       = azurerm_dns_zone.this.name
}

output "name_servers" {
  description = "NS records to create in the parent zone / at the registrar."
  value       = tolist(azurerm_dns_zone.this.name_servers)
}

output "cloud" {
  description = "Azure-only details."
  value       = {}
}
