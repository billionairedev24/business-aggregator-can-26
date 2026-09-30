variable "context" {
  description = "Shared naming, placement and tags (module contract, infra/terraform/README.md). name = resource prefix such as northline-dev; project_id is Google Cloud only, resource_group_name Azure only."
  type = object({
    name                = string
    environment         = string
    region              = string
    tags                = map(string)
    project_id          = optional(string)
    resource_group_name = optional(string)
  })

  validation {
    condition     = contains(["canadacentral", "canadaeast"], var.context.region)
    error_message = "Canadian data residency: region must be Azure canadacentral (Toronto) or canadaeast (Québec City)."
  }

  validation {
    condition     = contains(["dev", "staging", "prod"], var.context.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "network_id" {
  description = "network.network_id."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets for the service: network.data_subnet_ids (Azure PostgreSQL: network.cloud.postgres_subnet_id)."
  type        = list(string)
}

variable "allowed_cidrs" {
  description = "CIDRs allowed to connect (the network CIDR, i.e. the cluster)."
  type        = list(string)
}

variable "kms_key" {
  description = "{ id = kms.key_ids[\"data\"] } for encryption at rest with a customer-managed key where the service supports it without extra setup; null = the provider's default encryption. An object so that whether it is set is known at plan time, before the key exists."
  type = object({
    id = string
  })
  default = null
}

variable "secret_store" {
  description = "secrets.store: where generated credentials are written (names <prefix><name>), so External Secrets can read them."
  type = object({
    id     = string
    prefix = string
    kms_key = optional(object({
      id = string
    }))
  })
}

variable "deletion_protection" {
  description = "Refuse to destroy, keep final snapshots / longest retention (prod)."
  type        = bool
  default     = false
}

variable "size" {
  description = "Memory per zone of the Elasticsearch hot tier (Elastic Cloud size, e.g. 2g, 4g, 8g)."
  type        = string
  default     = "2g"
}

variable "zone_count" {
  description = "Availability zones of the Elasticsearch hot tier (1 dev, 2-3 prod)."
  type        = number
  default     = 1
}

variable "elastic_version" {
  description = "Elastic Stack version regex for the ec_stack lookup (the apps use the Elasticsearch 9 client)."
  type        = string
  default     = "^9\\.[0-9]+\\.[0-9]+$"
}
