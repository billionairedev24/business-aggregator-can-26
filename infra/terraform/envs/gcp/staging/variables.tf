variable "region" {
  description = "Canadian region (Google Cloud northamerica-northeast1 (Montréal) or northamerica-northeast2 (Toronto))."
  type        = string
  default     = "northamerica-northeast1"

  validation {
    condition     = contains(["northamerica-northeast1", "northamerica-northeast2"], var.region)
    error_message = "Canadian data residency: region must be Google Cloud northamerica-northeast1 (Montréal) or northamerica-northeast2 (Toronto)."
  }
}

variable "owner" {
  description = "Value of the owner tag/label on every resource."
  type        = string
  default     = "platform"
}

variable "admin_principals" {
  description = "IAM members (user:… / group:…) granted roles/container.admin."
  type        = list(string)
  default     = []
}

variable "api_allowed_cidrs" {
  description = "CIDRs allowed to reach the Kubernetes API endpoint."
  type        = list(string)
  default     = []
}

variable "bucket_name_suffix" {
  description = "Suffix for globally unique bucket names, if the plain name is taken."
  type        = string
  default     = ""
}

variable "project_id" {
  description = "Google Cloud project for this environment."
  type        = string
}

variable "signing_key_ids" {
  description = "Token signing key rotation (docs/runbooks/key-rotation.md): active overrides KMS_KEY_ID (null = the key Terraform created), published = KMS_PUBLISHED_KEY_IDS."
  type = object({
    active    = optional(string)
    published = optional(list(string), [])
  })
  default = {}
}
