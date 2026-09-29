# Amazon MSK (provisioned, KRaft) with SASL/SCRAM-SHA-512 over TLS on port 9096 (IAM auth needs a client library the
# apps don't have). Topic auto-creation is OFF: topics come from scripts/topics.sh (S-25). Encryption at rest with the
# data key (MSK requires a customer-managed key for SCRAM secrets anyway), TLS between brokers and clients.
# The SCRAM user's secret must be named AmazonMSK_*; the apps read the ready-made JAAS line from the secrets store.

locals {
  # MSK version string: check `aws kafka list-kafka-versions --region ca-central-1` and move to 4.x (".kraft") when
  # listed; the apps' Kafka 4 clients work with 3.x brokers.
  kafka_version = "3.9.x"
  name          = "${var.context.name}-kafka"
  rf            = min(3, var.capacity)
  min_isr       = local.rf >= 3 ? 2 : 1
  scram_user    = "northline-app"
  jaas_config   = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${local.scram_user}\" password=\"${random_password.scram.result}\";"
}

resource "aws_security_group" "this" {
  name        = local.name
  description = "Kafka ${local.name}"
  vpc_id      = var.network_id
  tags        = merge(var.context.tags, { Name = local.name })
}

resource "aws_vpc_security_group_ingress_rule" "sasl_scram" {
  for_each          = toset(var.allowed_cidrs)
  security_group_id = aws_security_group.this.id
  description       = "Kafka SASL/SCRAM from ${each.value}"
  cidr_ipv4         = each.value
  from_port         = 9096
  to_port           = 9096
  ip_protocol       = "tcp"
}

resource "aws_msk_configuration" "this" {
  name           = "${local.name}-${replace(local.kafka_version, ".", "-")}"
  kafka_versions = [local.kafka_version]
  server_properties = join("\n", [
    "auto.create.topics.enable=false",
    "default.replication.factor=${local.rf}",
    "min.insync.replicas=${local.min_isr}",
    "num.partitions=3",
    "unclean.leader.election.enable=false",
    "allow.everyone.if.no.acl.found=true",
  ])

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_msk_cluster" "this" {
  cluster_name           = local.name
  kafka_version          = local.kafka_version
  number_of_broker_nodes = var.capacity

  broker_node_group_info {
    instance_type   = var.tier
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.this.id]
    storage_info {
      ebs_storage_info {
        volume_size = var.storage_gb
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  client_authentication {
    unauthenticated = false
    sasl {
      scram = true
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = try(var.kms_key.id, null)
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  tags = var.context.tags
}

resource "random_password" "scram" {
  length  = 40
  special = false
}

# MSK reads SCRAM credentials from Secrets Manager: name AmazonMSK_*, encrypted with a customer-managed key.
resource "aws_secretsmanager_secret" "scram" {
  name                    = "AmazonMSK_${local.name}-${local.scram_user}"
  description             = "SCRAM credentials of ${local.scram_user} on ${local.name} (read by MSK)"
  kms_key_id              = try(var.kms_key.id, null)
  recovery_window_in_days = var.deletion_protection ? 30 : 7
  tags                    = var.context.tags

  lifecycle {
    precondition {
      condition     = var.kms_key != null
      error_message = "MSK SCRAM secrets must be encrypted with a customer-managed KMS key: pass kms_key."
    }
  }
}

resource "aws_secretsmanager_secret_version" "scram" {
  secret_id     = aws_secretsmanager_secret.scram.id
  secret_string = jsonencode({ username = local.scram_user, password = random_password.scram.result })
}

resource "aws_msk_single_scram_secret_association" "this" {
  cluster_arn = aws_msk_cluster.this.arn
  secret_arn  = aws_secretsmanager_secret.scram.arn

  depends_on = [aws_secretsmanager_secret_version.scram]
}

resource "aws_secretsmanager_secret" "jaas" {
  name                    = "${var.secret_store.prefix}kafka-sasl-jaas-config"
  description             = "KAFKA_SASL_JAAS_CONFIG for ${local.name}"
  kms_key_id              = try(var.secret_store.kms_key.id, null)
  recovery_window_in_days = var.deletion_protection ? 30 : 7
  tags                    = var.context.tags
}

resource "aws_secretsmanager_secret_version" "jaas" {
  secret_id     = aws_secretsmanager_secret.jaas.id
  secret_string = local.jaas_config
}
