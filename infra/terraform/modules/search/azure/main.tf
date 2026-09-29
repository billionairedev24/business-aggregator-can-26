# Elasticsearch 9 on Elastic Cloud in Azure Toronto (Elastic's azure-canadacentral; canadaeast maps to canadacentral,
# still in Canada). network_id/subnet_ids are unused: Elastic Cloud is reached over HTTPS; restrict it with
# allowed_cidrs (the NAT gateway IPs) or Azure Private Link (later).

module "elastic" {
  source          = "../elastic-cloud"
  name            = var.context.name
  ess_region      = "azure-canadacentral"
  template_prefix = "azure"
  size            = var.size
  zone_count      = var.zone_count
  elastic_version = var.elastic_version
  allowed_cidrs   = var.allowed_cidrs
  tags            = var.context.tags
}

resource "azurerm_key_vault_secret" "password" {
  name         = "${var.secret_store.prefix}es-password"
  value        = module.elastic.password
  key_vault_id = var.secret_store.id
  content_type = "ES_PASSWORD"
  tags         = var.context.tags
}

# Contract inputs this implementation does not need (README § Module contract); referenced so the omission is explicit.
locals {
  # tflint-ignore: terraform_unused_declarations
  unused_contract_inputs = [var.network_id, var.subnet_ids, var.kms_key, var.deletion_protection]
}
