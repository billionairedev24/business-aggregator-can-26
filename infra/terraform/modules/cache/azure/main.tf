# Azure Managed Redis (Redis Enterprise based; Azure Cache for Redis is being retired): the EnterpriseCluster
# clustering policy exposes a single endpoint (the apps use no cluster client), TLS only (port 10000), access-key
# authentication (REDIS_PASSWORD = the primary key, written to the secrets store), public access off with a private
# endpoint in the data subnet, high availability (replica) when replicas > 0. Encryption at rest: service-managed
# keys (customer-managed keys need a user-assigned identity — later).

resource "random_string" "suffix" {
  length  = 4
  special = false
  upper   = false
}

locals {
  name = "${var.context.name}-redis-${random_string.suffix.result}"
}

resource "azurerm_managed_redis" "this" {
  name                      = local.name
  location                  = var.context.region
  resource_group_name       = var.context.resource_group_name
  sku_name                  = var.node_size
  high_availability_enabled = var.replicas > 0
  public_network_access     = "Disabled"
  tags                      = var.context.tags

  default_database {
    access_keys_authentication_enabled = true
    client_protocol                    = "Encrypted"
    clustering_policy                  = "EnterpriseCluster"
    eviction_policy                    = "VolatileLRU"
  }
}

# Private endpoint in the data subnet + private DNS zone linked to the VNet, so the service's hostname resolves to a
# private address inside the VNet.
resource "azurerm_private_dns_zone" "redis" {
  name                = "privatelink.redis.azure.net"
  resource_group_name = var.context.resource_group_name
  tags                = var.context.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "redis" {
  name                = "${var.context.name}-redis"
  private_dns_zone_id = azurerm_private_dns_zone.redis.id
  virtual_network_id  = var.network_id
  tags                = var.context.tags
}

resource "azurerm_private_endpoint" "redis" {
  name                = "${local.name}-pe"
  location            = var.context.region
  resource_group_name = var.context.resource_group_name
  subnet_id           = var.subnet_ids[0]
  tags                = var.context.tags

  private_service_connection {
    name                           = "${local.name}-psc"
    private_connection_resource_id = azurerm_managed_redis.this.id
    subresource_names              = ["redisEnterprise"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "default"
    private_dns_zone_ids = [azurerm_private_dns_zone.redis.id]
  }
}

resource "azurerm_key_vault_secret" "password" {
  name         = "${var.secret_store.prefix}redis-password"
  value        = azurerm_managed_redis.this.default_database[0].primary_access_key
  key_vault_id = var.secret_store.id
  content_type = "REDIS_PASSWORD"
  tags         = var.context.tags
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.allowed_cidrs, var.kms_key, var.deletion_protection] # private endpoint only
}
