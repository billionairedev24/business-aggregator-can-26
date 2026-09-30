# Elasticsearch 9 on Elastic Cloud in AWS ca-central-1 (Elastic's aws-ca-central-1; ca-west-1 has no Elastic Cloud
# region, so it maps to ca-central-1, still in Canada). Amazon OpenSearch Service is NOT used: the apps use the
# Elasticsearch 9 Java client, which checks it talks to Elasticsearch and does not speak OpenSearch's API (a fork of
# 7.10) — switching would mean replacing the client and the index mappings/queries (see infra/terraform/README.md).
# network_id/subnet_ids are unused: Elastic Cloud is reached over HTTPS; restrict it with allowed_cidrs (the NAT IPs)
# or AWS PrivateLink (ec_deployment_traffic_filter type vpce, later).

module "elastic" {
  source          = "../elastic-cloud"
  name            = var.context.name
  ess_region      = "aws-ca-central-1"
  template_prefix = "aws"
  size            = var.size
  zone_count      = var.zone_count
  elastic_version = var.elastic_version
  allowed_cidrs   = var.allowed_cidrs
  tags            = var.context.tags
}

resource "aws_secretsmanager_secret" "password" {
  name                    = "${var.secret_store.prefix}es-password"
  description             = "ES_PASSWORD for ${var.context.name} (Elastic Cloud)"
  kms_key_id              = try(var.secret_store.kms_key.id, null)
  recovery_window_in_days = var.deletion_protection ? 30 : 7
  tags                    = var.context.tags
}

resource "aws_secretsmanager_secret_version" "password" {
  secret_id     = aws_secretsmanager_secret.password.id
  secret_string = module.elastic.password
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.network_id, var.subnet_ids, var.kms_key]
}
