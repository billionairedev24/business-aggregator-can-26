variable "region" {
  description = "Canadian region (Azure canadacentral (Toronto) or canadaeast (Québec City))."
  type        = string
  default     = "canadacentral"

  validation {
    condition     = contains(["canadacentral", "canadaeast"], var.region)
    error_message = "Canadian data residency: region must be Azure canadacentral (Toronto) or canadaeast (Québec City)."
  }
}

variable "owner" {
  description = "Value of the owner tag/label on every resource."
  type        = string
  default     = "platform"
}

variable "admin_principals" {
  description = "Entra object ids (users or groups) granted AKS RBAC Cluster Admin."
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

variable "subscription_id" {
  description = "Azure subscription for this environment."
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
