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
    condition     = contains(["ca-central-1", "ca-west-1"], var.context.region)
    error_message = "Canadian data residency: region must be AWS ca-central-1 (Montréal) or ca-west-1 (Calgary)."
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

variable "tier" {
  description = "Broker instance type (AWS, e.g. kafka.t3.small), ignored on Google Cloud (sized by vCPU), namespace SKU on Azure (Standard or Premium)."
  type        = string
}

variable "capacity" {
  description = "Brokers (AWS; a multiple of the subnet count), vCPUs (Google Cloud; >= 3, 4 GiB RAM each), throughput/processing units (Azure)."
  type        = number
}

variable "storage_gb" {
  description = "Disk per broker (AWS, Google Cloud); ignored on Azure (Event Hubs retention is time-based)."
  type        = number
  default     = 100
}

variable "topics" {
  description = "Topics to create as cloud resources where the service needs that (Azure Event Hubs): the `topics` output of modules/kafka/catalogue, name => { partitions, retention_hours, cleanup_policy }. MSK and Google Managed Kafka get their topics through the Kafka admin API (the provisioning Job, S-25) and ignore this."
  type = map(object({
    partitions      = number
    retention_hours = number
    cleanup_policy  = string
  }))
  default = {}
}
