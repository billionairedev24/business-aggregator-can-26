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

variable "secret_names" {
  description = "Application secrets created empty (their values are set by an operator, never by Terraform), e.g. totp-key."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for n in var.secret_names : can(regex("^[a-z0-9-]+$", n))])
    error_message = "Secret names are lower-case letters, digits and dashes."
  }
}

variable "readers" {
  description = "Principals that read every secret of this environment: { label => principal } (External Secrets Operator)."
  type        = map(string)
  default     = {}
}

variable "kms_key" {
  description = "{ id = kms.key_ids[\"data\"] }; null = the provider's default encryption. An object so that whether it is set is known at plan time, before the key exists."
  type = object({
    id = string
  })
  default = null
}

variable "deletion_protection" {
  description = "Longest recovery window / purge protection (prod)."
  type        = bool
  default     = false
}
