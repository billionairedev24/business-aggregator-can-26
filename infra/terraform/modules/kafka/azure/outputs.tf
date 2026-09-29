output "kafka_bootstrap" {
  description = "Value for KAFKA_BOOTSTRAP."
  value       = "${azurerm_eventhub_namespace.this.name}.servicebus.windows.net:9093"
}

output "kafka_security_protocol" {
  description = "Value for KAFKA_SECURITY_PROTOCOL."
  value       = "SASL_SSL"
}

output "kafka_sasl_mechanism" {
  description = "Value for KAFKA_SASL_MECHANISM."
  value       = "PLAIN"
}

output "kafka_sasl_jaas_config_secret_ref" {
  description = "Secret holding KAFKA_SASL_JAAS_CONFIG (the whole JAAS line, Send+Listen)."
  value       = azurerm_key_vault_secret.jaas.name
}

output "kafka_replication_factor" {
  description = "KAFKA_REPLICATION_FACTOR for scripts/topics.sh (Event Hubs manages replication; the value is accepted and ignored)."
  value       = 3
}

output "kafka_topic_policy" {
  description = "How topics come into existence on this service."
  value       = "Topics are event hubs: create every topic and its .dlq with scripts/topics.sh using the kafka-admin-jaas-config secret (Manage right). Standard allows 10 per namespace — use Premium for the full topic set."
}

output "cloud" {
  description = "Azure-only details."
  value = {
    namespace_id           = azurerm_eventhub_namespace.this.id
    admin_jaas_secret_name = azurerm_key_vault_secret.admin_jaas.name
  }
}
