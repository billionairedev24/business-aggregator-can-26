# Amazon ElastiCache for Valkey: one replication group with cluster mode disabled (a single primary endpoint, as the
# apps use no cluster client), TLS in transit (REDIS_SSL=true), AUTH token (REDIS_PASSWORD, written to the secrets
# store), encryption at rest with the data key, replicas + automatic failover + Multi-AZ when replicas > 0.

locals {
  name = "${var.context.name}-valkey"
}

resource "aws_elasticache_subnet_group" "this" {
  name       = local.name
  subnet_ids = var.subnet_ids
  tags       = var.context.tags
}

resource "aws_security_group" "this" {
  name        = local.name
  description = "Valkey ${local.name}"
  vpc_id      = var.network_id
  tags        = merge(var.context.tags, { Name = local.name })
}

resource "aws_vpc_security_group_ingress_rule" "valkey" {
  for_each          = toset(var.allowed_cidrs)
  security_group_id = aws_security_group.this.id
  description       = "Valkey from ${each.value}"
  cidr_ipv4         = each.value
  from_port         = 6379
  to_port           = 6379
  ip_protocol       = "tcp"
}

# Own parameter group (cluster mode off): sessions, idempotency keys and slot holds carry TTLs, so only keys with a
# TTL are evicted under memory pressure.
resource "aws_elasticache_parameter_group" "this" {
  name        = "${local.name}-8"
  family      = "valkey8"
  description = "Northline ${var.context.environment}"
  tags        = var.context.tags

  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }

  parameter {
    name  = "cluster-enabled"
    value = "no"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "random_password" "auth" {
  length  = 48
  special = false
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = local.name
  description                = "Northline ${var.context.environment} sessions, cache, rate limits"
  engine                     = "valkey"
  engine_version             = "8.0"
  parameter_group_name       = aws_elasticache_parameter_group.this.name
  node_type                  = var.node_size
  num_cache_clusters         = 1 + var.replicas
  automatic_failover_enabled = var.replicas > 0
  multi_az_enabled           = var.replicas > 0
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.this.id]
  at_rest_encryption_enabled = true
  kms_key_id                 = try(var.kms_key.id, null)
  transit_encryption_enabled = true
  auth_token                 = random_password.auth.result
  snapshot_retention_limit   = var.deletion_protection ? 7 : 1
  snapshot_window            = "06:00-07:00"
  maintenance_window         = "sun:07:30-sun:08:30"
  auto_minor_version_upgrade = true
  apply_immediately          = !var.deletion_protection
  final_snapshot_identifier  = var.deletion_protection ? "${local.name}-final" : null
  tags                       = var.context.tags
}

resource "aws_secretsmanager_secret" "auth" {
  name                    = "${var.secret_store.prefix}redis-password"
  description             = "REDIS_PASSWORD: AUTH token of ${local.name}"
  kms_key_id              = try(var.secret_store.kms_key.id, null)
  recovery_window_in_days = var.deletion_protection ? 30 : 7
  tags                    = var.context.tags
}

resource "aws_secretsmanager_secret_version" "auth" {
  secret_id     = aws_secretsmanager_secret.auth.id
  secret_string = random_password.auth.result
}
