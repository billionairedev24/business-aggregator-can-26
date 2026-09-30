# Azure DNS public zone. Delegate it from the parent zone (or the registrar) with name_servers.

resource "azurerm_dns_zone" "this" {
  name                = var.zone_name
  resource_group_name = var.context.resource_group_name
  tags                = var.context.tags
}

data "azurerm_client_config" "current" {}

# S-17: external-dns (records for the public hosts) and cert-manager (DNS-01 TXT records) may change this zone only.
resource "azurerm_role_assignment" "record_writers" {
  for_each             = var.record_writers
  scope                = azurerm_dns_zone.this.id
  role_definition_name = "DNS Zone Contributor"
  principal_id         = each.value
}
