# Azure DNS public zone. Delegate it from the parent zone (or the registrar) with name_servers.

resource "azurerm_dns_zone" "this" {
  name                = var.zone_name
  resource_group_name = var.context.resource_group_name
  tags                = var.context.tags
}
