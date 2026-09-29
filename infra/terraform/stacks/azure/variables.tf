variable "environment" {
  description = "dev, staging or prod."
  type        = string
}

variable "region" {
  description = "Canadian Azure region."
  type        = string
}

variable "owner" {
  description = "Owner tag (team or person accountable for the environment)."
  type        = string
}

variable "cidr" {
  description = "VNet address space."
  type        = string
}

variable "zone_count" {
  description = "Availability zones (2 or 3)."
  type        = number
}

variable "high_availability_nat" {
  description = "One NAT gateway per zone."
  type        = bool
}

variable "kubernetes_version" {
  description = "AKS version; null = the latest supported."
  type        = string
  default     = null
}

variable "node_pools" {
  description = "AKS node pools; the first key is the system pool (see modules/kubernetes)."
  type = map(object({
    machine_type = string
    min_count    = number
    max_count    = number
    disk_size_gb = optional(number, 50)
    spot         = optional(bool, false)
    labels       = optional(map(string), {})
  }))
}

variable "api_allowed_cidrs" {
  description = "CIDRs allowed to reach the Kubernetes API."
  type        = list(string)
  default     = []
}

variable "admin_principals" {
  description = "Entra object ids (users or groups) granted AKS RBAC Cluster Admin."
  type        = list(string)
  default     = []
}

variable "dns_zone_name" {
  description = "DNS zone of the environment."
  type        = string
}

variable "bucket_name_suffix" {
  description = "Suffix for globally unique bucket names."
  type        = string
  default     = ""
}

variable "deletion_protection" {
  description = "Protect stateful resources from destroy (prod)."
  type        = bool
}

variable "data_stores" {
  description = "Sizes of the managed data stores (S-3); instance types/tiers are the cloud's own names (see modules/<store>/variables.tf)."
  type = object({
    postgres = object({
      instance_size         = string
      storage_gb            = number
      high_availability     = bool
      backup_retention_days = number
    })
    cache = object({
      node_size = string
      replicas  = number
    })
    kafka = object({
      tier       = string
      capacity   = number
      storage_gb = number
    })
    search = object({
      size       = string
      zone_count = number
    })
  })
}
