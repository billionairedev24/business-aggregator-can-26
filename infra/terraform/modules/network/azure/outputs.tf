output "network_id" {
  description = "VNet resource id."
  value       = azurerm_virtual_network.this.id
}

output "network_name" {
  description = "VNet name."
  value       = azurerm_virtual_network.this.name
}

output "cidr" {
  description = "VNet address space."
  value       = var.cidr
}

output "cluster_subnet_ids" {
  description = "Subnet for AKS nodes."
  value       = [azurerm_subnet.aks.id]
}

output "data_subnet_ids" {
  description = "Subnet for private endpoints of data services."
  value       = [azurerm_subnet.endpoints.id]
}

output "cloud" {
  description = "Azure-only details."
  value = {
    postgres_subnet_id = azurerm_subnet.postgres.id
    nat_gateway_id     = azurerm_nat_gateway.this.id
    nat_public_ips     = azurerm_public_ip.nat[*].ip_address
    aks_subnet_cidr    = local.aks_cidr
  }
}
