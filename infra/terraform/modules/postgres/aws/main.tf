# Amazon RDS for PostgreSQL 17: private (isolated data subnets, security group limited to allowed_cidrs), TLS
# enforced by the parameter group, storage and Performance Insights encrypted with the data key, automated backups
# with point-in-time recovery, Multi-AZ when high_availability. PostGIS, citext and pgcrypto are available on RDS
# without allow-listing; the bootstrap SQL creates them. The master password is managed by RDS in Secrets Manager;
# the app role's password is generated here and written to the secrets store.

locals {
  name        = "${var.context.name}-pg"
  major       = split(".", var.postgres_version)[0]
  secret_name = "${var.secret_store.prefix}db-app-password"
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = var.subnet_ids
  tags       = var.context.tags
}

resource "aws_security_group" "this" {
  name        = local.name
  description = "PostgreSQL ${local.name}"
  vpc_id      = var.network_id
  tags        = merge(var.context.tags, { Name = local.name })
}

resource "aws_vpc_security_group_ingress_rule" "postgres" {
  for_each          = toset(var.allowed_cidrs)
  security_group_id = aws_security_group.this.id
  description       = "PostgreSQL from ${each.value}"
  cidr_ipv4         = each.value
  from_port         = 5432
  to_port           = 5432
  ip_protocol       = "tcp"
}

resource "aws_db_parameter_group" "this" {
  name        = "${local.name}-${local.major}"
  family      = "postgres${local.major}"
  description = "Northline ${var.context.environment}"
  tags        = var.context.tags

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  parameter {
    name  = "idle_in_transaction_session_timeout"
    value = "600000"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier                            = local.name
  engine                                = "postgres"
  engine_version                        = var.postgres_version
  instance_class                        = var.instance_size
  allocated_storage                     = var.storage_gb
  max_allocated_storage                 = var.storage_gb * 5
  storage_type                          = "gp3"
  storage_encrypted                     = true
  kms_key_id                            = try(var.kms_key.id, null)
  db_name                               = var.database_name
  username                              = "northline_admin"
  manage_master_user_password           = true
  master_user_secret_kms_key_id         = try(var.kms_key.id, null)
  db_subnet_group_name                  = aws_db_subnet_group.this.name
  vpc_security_group_ids                = [aws_security_group.this.id]
  parameter_group_name                  = aws_db_parameter_group.this.name
  publicly_accessible                   = false
  multi_az                              = var.high_availability
  backup_retention_period               = var.backup_retention_days
  backup_window                         = "07:00-08:00"
  maintenance_window                    = "sun:08:30-sun:09:30"
  copy_tags_to_snapshot                 = true
  auto_minor_version_upgrade            = true
  allow_major_version_upgrade           = false
  deletion_protection                   = var.deletion_protection
  skip_final_snapshot                   = !var.deletion_protection
  final_snapshot_identifier             = var.deletion_protection ? "${local.name}-final" : null
  performance_insights_enabled          = true
  performance_insights_kms_key_id       = try(var.kms_key.id, null)
  performance_insights_retention_period = 7
  enabled_cloudwatch_logs_exports       = ["postgresql"]
  ca_cert_identifier                    = "rds-ca-rsa2048-g1"
  iam_database_authentication_enabled   = true
  tags                                  = var.context.tags
}

resource "random_password" "app" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = local.secret_name
  description             = "DB_PASSWORD: ${var.app_user} on ${local.name}"
  kms_key_id              = try(var.secret_store.kms_key.id, null)
  recovery_window_in_days = var.deletion_protection ? 30 : 7
  tags                    = var.context.tags
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id     = aws_secretsmanager_secret.app.id
  secret_string = random_password.app.result
}
