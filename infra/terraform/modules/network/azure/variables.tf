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

variable "cidr" {
  description = "Address space of the network (a /16 is carved into cluster, data and edge subnets)."
  type        = string
  default     = "10.20.0.0/16"

  validation {
    condition     = can(cidrhost(var.cidr, 0)) && tonumber(split("/", var.cidr)[1]) <= 16
    error_message = "cidr must be a valid IPv4 CIDR of /16 or larger."
  }
}

variable "zone_count" {
  description = "Availability zones to spread subnets over (AWS needs at least 2 for EKS; regional subnets on Google Cloud and Azure ignore it)."
  type        = number
  default     = 3

  validation {
    condition     = var.zone_count >= 2 && var.zone_count <= 3
    error_message = "zone_count must be 2 or 3."
  }
}

variable "high_availability_nat" {
  description = "One NAT gateway per zone (true) or a single shared one (false, cheaper; dev)."
  type        = bool
  default     = false
}
