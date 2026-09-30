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
    condition     = contains(["northamerica-northeast1", "northamerica-northeast2"], var.context.region)
    error_message = "Canadian data residency: region must be Google Cloud northamerica-northeast1 (Montréal) or northamerica-northeast2 (Toronto)."
  }

  validation {
    condition     = contains(["dev", "staging", "prod"], var.context.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "repositories" {
  description = "Image repositories (one per deployable)."
  type        = list(string)
  default     = ["api", "auth", "bff", "worker", "studio", "consumer"]
}

variable "kms_key" {
  description = "{ id = kms.key_ids[\"data\"] } for customer-managed encryption; null = the provider's default encryption. An object so that whether it is set is known at plan time, before the key exists."
  type = object({
    id = string
  })
  default = null
}

variable "readers" {
  description = "Principals that pull images (the node identity): { label => principal }."
  type        = map(string)
  default     = {}
}

variable "keep_images" {
  description = "Tagged images kept per repository by the cleanup policy."
  type        = number
  default     = 30
}
