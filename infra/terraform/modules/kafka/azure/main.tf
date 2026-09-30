# Azure Event Hubs as Kafka: a namespace (Standard or Premium; Basic has no Kafka endpoint) exposes the Kafka
# protocol on <namespace>.servicebus.windows.net:9093, SASL_SSL with SASL/PLAIN where the username is the literal
# $ConnectionString and the password a connection string. Public access off; private endpoint in the data subnet.
#
# Differences from Apache Kafka that matter here:
# - Topics are event hubs. Standard allows only 10 per namespace; Northline has about 50 (topics + .dlq), so staging
#   and prod need Premium (100 per processing unit) — or Confluent Cloud on Azure.
# - Topics are created HERE, as azurerm_eventhub resources from the topic catalogue (var.topics = modules/kafka/catalogue,
#   S-25), not through Kafka's CreateTopics: an event hub is an ARM resource, so Terraform owns it like the namespace,
#   and no pod needs the Manage right. The provisioning Job runs in `verify` mode on Azure (reports drift only).
#   Partition count is fixed after creation on Standard; retention is at most 7 days on Standard, 90 on Premium.
#   No compacted topics on Standard.
# - The apps get a Send+Listen rule; the Manage rule remains for manual repairs (scripts/topics.sh) only.
# Encryption at rest: service-managed keys (customer-managed keys need Premium + an identity — later).

resource "random_string" "suffix" {
  length  = 4
  special = false
  upper   = false
}

locals {
  name        = "${var.context.name}-ehns-${random_string.suffix.result}"
  jaas_config = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$ConnectionString\" password=\"${azurerm_eventhub_namespace_authorization_rule.app.primary_connection_string}\";"
  admin_jaas  = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$ConnectionString\" password=\"${azurerm_eventhub_namespace_authorization_rule.admin.primary_connection_string}\";"
}

resource "azurerm_eventhub_namespace" "this" {
  name                          = local.name
  location                      = var.context.region
  resource_group_name           = var.context.resource_group_name
  sku                           = var.tier
  capacity                      = var.capacity
  auto_inflate_enabled          = var.tier == "Standard"
  maximum_throughput_units      = var.tier == "Standard" ? max(var.capacity, 4) : null
  minimum_tls_version           = "1.2"
  local_authentication_enabled  = true # SASL/PLAIN with connection strings
  public_network_access_enabled = false
  tags                          = var.context.tags

  lifecycle {
    precondition {
      condition     = contains(["Standard", "Premium"], var.tier)
      error_message = "Event Hubs tier must be Standard or Premium (Basic has no Kafka endpoint)."
    }
  }
}

resource "azurerm_eventhub_namespace_authorization_rule" "app" {
  name                = "northline-app"
  namespace_name      = azurerm_eventhub_namespace.this.name
  resource_group_name = var.context.resource_group_name
  listen              = true
  send                = true
  manage              = false
}

resource "azurerm_eventhub_namespace_authorization_rule" "admin" {
  name                = "northline-topics-admin"
  namespace_name      = azurerm_eventhub_namespace.this.name
  resource_group_name = var.context.resource_group_name
  listen              = true
  send                = true
  manage              = true
}

# Private endpoint in the data subnet + private DNS zone linked to the VNet, so the service's hostname resolves to a
# private address inside the VNet.
resource "azurerm_private_dns_zone" "eventhubs" {
  name                = "privatelink.servicebus.windows.net"
  resource_group_name = var.context.resource_group_name
  tags                = var.context.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "eventhubs" {
  name                = "${var.context.name}-eventhubs"
  private_dns_zone_id = azurerm_private_dns_zone.eventhubs.id
  virtual_network_id  = var.network_id
  tags                = var.context.tags
}

resource "azurerm_private_endpoint" "eventhubs" {
  name                = "${local.name}-pe"
  location            = var.context.region
  resource_group_name = var.context.resource_group_name
  subnet_id           = var.subnet_ids[0]
  tags                = var.context.tags

  private_service_connection {
    name                           = "${local.name}-psc"
    private_connection_resource_id = azurerm_eventhub_namespace.this.id
    subresource_names              = ["namespace"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "default"
    private_dns_zone_ids = [azurerm_private_dns_zone.eventhubs.id]
  }
}

resource "azurerm_key_vault_secret" "jaas" {
  name         = "${var.secret_store.prefix}kafka-sasl-jaas-config"
  value        = local.jaas_config
  key_vault_id = var.secret_store.id
  content_type = "KAFKA_SASL_JAAS_CONFIG"
  tags         = var.context.tags
}

resource "azurerm_key_vault_secret" "admin_jaas" {
  name         = "${var.secret_store.prefix}kafka-admin-jaas-config"
  value        = local.admin_jaas
  key_vault_id = var.secret_store.id
  content_type = "JAAS line with the Manage right, for scripts/topics.sh only"
  tags         = var.context.tags
}

# One event hub per catalogue topic (topic, .dlq, consumer retry topics). prevent_destroy: removing a topic from the
# catalogue must never delete the event hub and its events (S-25: topics are never deleted by automation). Tearing
# down a whole environment: `terraform state rm 'module.northline.module.kafka.azurerm_eventhub.topic'` first
# (docs/runbooks/infrastructure.md § 5.3).
resource "azurerm_eventhub" "topic" {
  for_each = var.topics

  name            = each.key
  namespace_id    = azurerm_eventhub_namespace.this.id
  partition_count = each.value.partitions

  retention_description {
    cleanup_policy          = title(each.value.cleanup_policy) # Delete | Compact (Premium only)
    retention_time_in_hours = each.value.retention_hours
  }

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = var.tier == "Premium" || each.value.retention_hours <= 168
      error_message = "Event Hubs Standard keeps events at most 7 days; use Premium (retention up to 90 days)."
    }
  }
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.allowed_cidrs, var.kms_key, var.storage_gb, var.deletion_protection]
}
