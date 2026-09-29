# Shared by search/{aws,gcp,azure}: an Elastic Cloud (Elasticsearch Service) deployment in the Canadian region of the
# chosen cloud, Elasticsearch 9 hot tier, optional IP traffic filter. Each cloud module adds the region mapping and
# writes the password into its secrets store. Authentication: the ec provider reads EC_API_KEY.

terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    ec = {
      source  = "elastic/ec"
      version = ">= 0.13, < 1.0"
    }
  }
}

variable "name" {
  description = "Deployment name."
  type        = string
}

variable "ess_region" {
  description = "Elastic Cloud region id, e.g. aws-ca-central-1."
  type        = string
}

variable "template_prefix" {
  description = "aws, gcp or azure: picks <prefix>-general-purpose (else an io-optimized template) from the region's templates."
  type        = string
}

variable "size" {
  description = "Hot tier memory per zone."
  type        = string
}

variable "zone_count" {
  description = "Hot tier zones."
  type        = number
}

variable "elastic_version" {
  description = "Version regex for ec_stack."
  type        = string
}

variable "allowed_cidrs" {
  description = "IP traffic filter; empty = reachable from anywhere with credentials."
  type        = list(string)
}

variable "tags" {
  description = "Deployment tags."
  type        = map(string)
}

data "ec_stack" "this" {
  version_regex = var.elastic_version
  region        = var.ess_region
}

data "ec_deployment_templates" "this" {
  region = var.ess_region
}

locals {
  template_ids = [for t in data.ec_deployment_templates.this.templates : t.id]
  template_id = coalesce(
    one([for id in local.template_ids : id if id == "${var.template_prefix}-general-purpose"]),
    try([for id in local.template_ids : id if startswith(id, "${var.template_prefix}-io-optimized")][0], null),
    "${var.template_prefix}-general-purpose",
  )
}

resource "ec_deployment_traffic_filter" "this" {
  count  = length(var.allowed_cidrs) > 0 ? 1 : 0
  name   = "${var.name}-cluster-egress"
  region = var.ess_region
  type   = "ip"

  dynamic "rule" {
    for_each = var.allowed_cidrs
    content {
      source = rule.value
    }
  }
}

resource "ec_deployment" "this" {
  name                   = var.name
  region                 = var.ess_region
  version                = data.ec_stack.this.version
  deployment_template_id = local.template_id
  traffic_filter         = ec_deployment_traffic_filter.this[*].id
  tags                   = var.tags

  elasticsearch = {
    hot = {
      size        = var.size
      zone_count  = var.zone_count
      autoscaling = {}
    }
  }
}

output "https_endpoint" {
  description = "Elasticsearch HTTPS endpoint."
  value       = ec_deployment.this.elasticsearch.https_endpoint
}

output "username" {
  description = "Elasticsearch superuser name."
  value       = ec_deployment.this.elasticsearch_username
}

output "password" {
  description = "Elasticsearch superuser password."
  value       = ec_deployment.this.elasticsearch_password
  sensitive   = true
}

output "deployment_id" {
  description = "Deployment id."
  value       = ec_deployment.this.id
}

output "version" {
  description = "Elastic Stack version."
  value       = ec_deployment.this.version
}
