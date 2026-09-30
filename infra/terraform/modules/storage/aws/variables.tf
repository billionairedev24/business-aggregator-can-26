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
