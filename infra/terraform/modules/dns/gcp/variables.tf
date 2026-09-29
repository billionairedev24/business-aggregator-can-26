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

variable "zone_name" {
  description = "DNS zone, e.g. dev.northline.ca (delegated from the northline.ca zone) or northline.ca (prod)."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9-]+\\.)+[a-z]{2,}$", var.zone_name))
    error_message = "zone_name must be a lower-case domain without a trailing dot."
  }
}
