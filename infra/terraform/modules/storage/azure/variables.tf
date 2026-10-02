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

variable "buckets" {
  description = "Private buckets (containers on Azure), keyed by purpose. The name is <context.name>-<key> (e.g. northline-dev-uploads) on AWS and Google Cloud, a container named <key> on Azure."
  type = map(object({
    versioning           = optional(bool, true)
    noncurrent_days      = optional(number, 30)
    cors_allowed_origins = optional(list(string), [])
  }))
}

variable "name_suffix" {
  description = "Appended to bucket names on AWS/Google Cloud when the plain name is taken (bucket names are global)."
  type        = string
  default     = ""
}

variable "kms_key" {
  description = "{ id = kms.key_ids[\"data\"] } for customer-managed encryption; null = the provider's default encryption. An object so that whether it is set is known at plan time, before the key exists."
  type = object({
    id = string
  })
  default = null
}

variable "writers" {
  description = "Principals with read/write access to the objects: { label => principal } (the api workload identity)."
  type        = map(string)
  default     = {}
}

variable "force_destroy" {
  description = "Allow destroying non-empty buckets (dev only)."
  type        = bool
  default     = false
}

variable "replica" {
  description = "S-114: replicate every bucket to the other Canadian region of the cloud (new and changed objects, deletes as delete markers / versions), versioned, with lifecycle rules: current objects move to a cooler class after cool_after_days, older versions expire after noncurrent_days. null = no replica. AWS: S3 replication to a bucket <name>-replica; Google Cloud: an event-driven Storage Transfer Service replication job to a bucket <name>-replica; Azure: object replication to a second storage account. kms_key = a key in that region on AWS and Google Cloud (kms module instantiated there); on Azure the primary key vault's key (Key Vault keeps a read-only copy in the paired region)."
  type = object({
    region = string
    kms_key = optional(object({
      id = string
    }))
    cool_after_days = optional(number, 30)
    noncurrent_days = optional(number, 90)
  })
  default = null

  validation {
    condition     = var.replica == null || (contains(["canadacentral", "canadaeast"], try(var.replica.region, "")) && try(var.replica.region, "") != var.context.region)
    error_message = "Canadian data residency: replica.region must be the other Canadian region of Azure (canadacentral ⇄ canadaeast, the paired regions)."
  }
}
