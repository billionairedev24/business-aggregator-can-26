output "kafka_bootstrap" {
  description = "Value for KAFKA_BOOTSTRAP (bootstrap.<cluster>.<region>.managedkafka.<project>.cloud.goog:9092)."
  value       = google_managed_kafka_cluster.this.bootstrap_address
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
  description = "Secret holding KAFKA_SASL_JAAS_CONFIG (the whole JAAS line)."
  value       = google_secret_manager_secret.jaas.secret_id
}

output "kafka_replication_factor" {
  description = "KAFKA_REPLICATION_FACTOR for the topic provisioning Job and scripts/topics.sh."
  value       = 3
}

output "kafka_topic_policy" {
  description = "How topics come into existence on this service."
  value       = "Do not rely on auto-creation (not configurable on Managed Kafka): the provisioning Job creates every topic of deploy/kafka/topics.yaml (Kafka admin API, S-25) before the apps start."
}

output "cloud" {
  description = "Google Cloud-only details."
  value = {
    cluster          = google_managed_kafka_cluster.this.name
    client_sa_email  = google_service_account.client.email
    client_key_notes = "Rotate by tainting google_service_account_key.client and re-applying; the JAAS secret gets a new version."
  }
}
