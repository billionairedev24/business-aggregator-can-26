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

variable "keys" {
  description = "Keys to create. usage = encrypt (symmetric envelope key for disks, buckets, secrets, databases) or sign (EC P-256 for token signing, KMS_KEY_ID, S-7)."
  type = map(object({
    usage         = string
    rotation_days = optional(number, 365)
  }))

  validation {
    condition     = alltrue([for k in var.keys : contains(["encrypt", "sign"], k.usage)])
    error_message = "usage must be encrypt or sign."
  }
}

variable "key_users" {
  description = "Who may use each key: key name => { label => principal }. Principals are IAM role ARNs (AWS), IAM members (Google Cloud, e.g. serviceAccount:x@p.iam.gserviceaccount.com) or Entra object ids (Azure). Labels must be static."
  type        = map(map(string))
  default     = {}
}

variable "deletion_protection" {
  description = "Longest deletion window / purge protection (prod)."
  type        = bool
  default     = false
}
