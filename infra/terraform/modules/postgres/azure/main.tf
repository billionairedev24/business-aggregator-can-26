# Azure Database for PostgreSQL – Flexible Server 17: VNet-integrated (delegated subnet from the network module,
# private DNS zone, no public access), TLS required (require_secure_transport, the default), PostGIS/citext/pgcrypto
# allow-listed in azure.extensions (the bootstrap SQL creates them), backups with point-in-time restore,
# zone-redundant HA when high_availability, geo-redundant backups in prod (the paired region canadaeast ⇄
# canadacentral is Canadian). Encryption at rest: service-managed keys (a customer-managed key needs a user-assigned
# identity and, with geo-redundant backup, a second key in the paired region — later).

resource "random_string" "suffix" {
  length  = 4
  special = false
  upper   = false
}

locals {
  name = "${var.context.name}-pg-${random_string.suffix.result}"
}

resource "random_password" "admin" {
  length  = 32
  special = false
}

resource "random_password" "app" {
  length  = 32
  special = false
}

resource "azurerm_private_dns_zone" "this" {
  name                = "${var.context.name}.private.postgres.database.azure.com"
  resource_group_name = var.context.resource_group_name
  tags                = var.context.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "this" {
  name                = "${var.context.name}-postgres"
  private_dns_zone_id = azurerm_private_dns_zone.this.id
  virtual_network_id  = var.network_id
  tags                = var.context.tags
}

resource "azurerm_postgresql_flexible_server" "this" {
  name                          = local.name
  location                      = var.context.region
  resource_group_name           = var.context.resource_group_name
  version                       = var.postgres_version
  sku_name                      = var.instance_size
  storage_mb                    = var.storage_gb * 1024
  auto_grow_enabled             = true
  delegated_subnet_id           = var.subnet_ids[0]
  private_dns_zone_id           = azurerm_private_dns_zone.this.id
  public_network_access_enabled = false
  administrator_login           = "northline_admin"
  administrator_password        = random_password.admin.result
  backup_retention_days         = var.backup_retention_days
  geo_redundant_backup_enabled  = var.deletion_protection
  zone                          = "1"
  tags                          = var.context.tags

  authentication {
    password_auth_enabled = true
  }

  dynamic "high_availability" {
    for_each = var.high_availability ? [1] : []
    content {
      mode                      = "ZoneRedundant"
      standby_availability_zone = "2"
    }
  }

  maintenance_window {
    day_of_week  = 0
    start_hour   = 8
    start_minute = 0
  }

  depends_on = [azurerm_private_dns_zone_virtual_network_link.this]

  lifecycle {
    ignore_changes = [zone, high_availability[0].standby_availability_zone]
  }
}

resource "azurerm_postgresql_flexible_server_configuration" "this" {
  for_each = {
    "azure.extensions"                    = "POSTGIS,CITEXT,PGCRYPTO,PG_STAT_STATEMENTS"
    "require_secure_transport"            = "on"
    "log_min_duration_statement"          = "500"
    "idle_in_transaction_session_timeout" = "600000"
  }
  name      = each.key
  server_id = azurerm_postgresql_flexible_server.this.id
  value     = each.value
}

resource "azurerm_postgresql_flexible_server_database" "this" {
  name      = var.database_name
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_management_lock" "this" {
  count      = var.deletion_protection ? 1 : 0
  name       = "${local.name}-no-delete"
  scope      = azurerm_postgresql_flexible_server.this.id
  lock_level = "CanNotDelete"
  notes      = "deletion_protection = true (Terraform)"
}

resource "azurerm_key_vault_secret" "admin" {
  name         = "${var.secret_store.prefix}db-admin-password"
  value        = random_password.admin.result
  key_vault_id = var.secret_store.id
  content_type = "northline_admin password of the PostgreSQL server"
  tags         = var.context.tags
}

resource "azurerm_key_vault_secret" "app" {
  name         = "${var.secret_store.prefix}db-app-password"
  value        = random_password.app.result
  key_vault_id = var.secret_store.id
  content_type = "DB_PASSWORD"
  tags         = var.context.tags
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.allowed_cidrs, var.kms_key] # the delegated subnet is only reachable from the VNet
}
