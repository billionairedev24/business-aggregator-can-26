variable "context" {
  description = "Shared naming, placement and tags (module contract, infra/terraform/README.md). name = resource prefix such as northline-dev; project_id is Google Cloud only, resource_group_name Azure only; zone = the one zone of zonal resources (Google Cloud: the dev and dryrun clusters; null = <region>-a)."
  type = object({
    name                = string
    environment         = string
    region              = string
    tags                = map(string)
    project_id          = optional(string)
    resource_group_name = optional(string)
    zone                = optional(string)
  })

  validation {
    condition     = contains(["northamerica-northeast1", "northamerica-northeast2"], var.context.region)
    error_message = "Canadian data residency: region must be Google Cloud northamerica-northeast1 (Montréal) or northamerica-northeast2 (Toronto)."
  }

  validation {
    condition     = contains(["dev", "staging", "prod", "dryrun"], var.context.environment)
    error_message = "environment must be dev, staging, prod or dryrun."
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
