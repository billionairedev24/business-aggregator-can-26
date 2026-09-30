# Azure network: one VNet with an AKS node subnet (egress through a NAT gateway with a fixed public IP), a
# private-endpoint subnet for data services (Managed Redis, Event Hubs, Key Vault later) and a subnet delegated to
# PostgreSQL Flexible Server. Azure subnets span zones, so zone_count only matters on AWS; high_availability_nat
# adds a second outbound IP.

locals {
  rg              = var.context.resource_group_name
  aks_cidr        = cidrsubnet(var.cidr, 4, 0)   # /20 nodes (Azure CNI overlay: pods use a separate overlay range)
  endpoints_cidr  = cidrsubnet(var.cidr, 8, 210) # /24 private endpoints
  postgres_cidr   = cidrsubnet(var.cidr, 8, 211) # /24 delegated to PostgreSQL Flexible Server
  outbound_ip_num = var.high_availability_nat ? 2 : 1
}

resource "azurerm_virtual_network" "this" {
  name                = var.context.name
  location            = var.context.region
  resource_group_name = local.rg
  address_space       = [var.cidr]
  tags                = var.context.tags
}

resource "azurerm_subnet" "aks" {
  name                 = "aks"
  resource_group_name  = local.rg
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [local.aks_cidr]
}

resource "azurerm_subnet" "endpoints" {
  name                              = "endpoints"
  resource_group_name               = local.rg
  virtual_network_name              = azurerm_virtual_network.this.name
  address_prefixes                  = [local.endpoints_cidr]
  private_endpoint_network_policies = "Enabled"
}

resource "azurerm_subnet" "postgres" {
  name                 = "postgres"
  resource_group_name  = local.rg
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [local.postgres_cidr]

  delegation {
    name = "postgres-flexible-server"
    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_network_security_group" "aks" {
  name                = "${var.context.name}-aks"
  location            = var.context.region
  resource_group_name = local.rg
  tags                = var.context.tags
}

resource "azurerm_subnet_network_security_group_association" "aks" {
  subnet_id                 = azurerm_subnet.aks.id
  network_security_group_id = azurerm_network_security_group.aks.id
}

resource "azurerm_public_ip" "nat" {
  count               = local.outbound_ip_num
  name                = "${var.context.name}-nat-${count.index}"
  location            = var.context.region
  resource_group_name = local.rg
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = var.context.tags
}

resource "azurerm_nat_gateway" "this" {
  name                    = var.context.name
  location                = var.context.region
  resource_group_name     = local.rg
  sku_name                = "Standard"
  idle_timeout_in_minutes = 10
  tags                    = var.context.tags
}

resource "azurerm_nat_gateway_public_ip_association" "this" {
  count                = local.outbound_ip_num
  nat_gateway_id       = azurerm_nat_gateway.this.id
  public_ip_address_id = azurerm_public_ip.nat[count.index].id
}

resource "azurerm_subnet_nat_gateway_association" "aks" {
  subnet_id      = azurerm_subnet.aks.id
  nat_gateway_id = azurerm_nat_gateway.this.id
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.zone_count]
}
