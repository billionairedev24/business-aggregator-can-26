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

# S-17: what the edge add-ons need to write this zone (docs/runbooks/edge.md). The identities come from the
# kubernetes module (Azure Workload Identity: the webhook injects the client id), so nothing here is a credential.
output "cert_manager_dns01" {
  description = "cert-manager ACME DNS-01 solver block for this zone (Issuer spec.acme.solvers[].dns01)."
  value = {
    azureDNS = {
      subscriptionID    = data.azurerm_client_config.current.subscription_id
      resourceGroupName = var.context.resource_group_name
      hostedZoneName    = azurerm_dns_zone.this.name
      environment       = "AzurePublicCloud"
    }
  }
}

output "external_dns" {
  description = "external-dns Helm values that point it at this zone (azure.json holds ids only; auth is workload identity)."
  value = {
    provider      = { name = "azure" }
    domainFilters = [var.zone_name]
    env           = []
    extraArgs     = []
    secretConfiguration = {
      enabled   = true
      mountPath = "/etc/kubernetes/"
      data = {
        "azure.json" = jsonencode({
          tenantId                     = data.azurerm_client_config.current.tenant_id
          subscriptionId               = data.azurerm_client_config.current.subscription_id
          resourceGroup                = var.context.resource_group_name
          useWorkloadIdentityExtension = true
        })
      }
    }
  }
}
