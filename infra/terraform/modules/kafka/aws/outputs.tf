output "kafka_bootstrap" {
  description = "Value for KAFKA_BOOTSTRAP (SASL/SCRAM listeners, port 9096)."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
}

output "kafka_security_protocol" {
  description = "Value for KAFKA_SECURITY_PROTOCOL."
  value       = "SASL_SSL"
}

output "kafka_sasl_mechanism" {
  description = "Value for KAFKA_SASL_MECHANISM."
  value       = "SCRAM-SHA-512"
}

output "kafka_sasl_jaas_config_secret_ref" {
  description = "Secret holding KAFKA_SASL_JAAS_CONFIG (the whole JAAS line)."
  value       = aws_secretsmanager_secret.jaas.name
}

output "kafka_replication_factor" {
  description = "KAFKA_REPLICATION_FACTOR for the topic provisioning Job and scripts/topics.sh."
  value       = local.rf
}

output "kafka_topic_policy" {
  description = "How topics come into existence on this service."
  value       = "auto.create.topics.enable=false (MSK configuration): the provisioning Job creates every topic of deploy/kafka/topics.yaml (Kafka admin API, S-25) before the apps start."
}

output "cloud" {
  description = "AWS-only details."
  value = {
    cluster_arn       = aws_msk_cluster.this.arn
    security_group_id = aws_security_group.this.id
    kafka_version     = local.kafka_version
  }
}
