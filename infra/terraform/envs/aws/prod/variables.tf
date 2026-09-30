variable "region" {
  description = "Canadian region (AWS ca-central-1 (Montréal) or ca-west-1 (Calgary))."
  type        = string
  default     = "ca-central-1"

  validation {
    condition     = contains(["ca-central-1", "ca-west-1"], var.region)
    error_message = "Canadian data residency: region must be AWS ca-central-1 (Montréal) or ca-west-1 (Calgary)."
  }
}

variable "owner" {
  description = "Value of the owner tag/label on every resource."
  type        = string
  default     = "platform"
}

variable "admin_principals" {
  description = "IAM role/user ARNs that administer the cluster (EKS access entries)."
  type        = list(string)
  default     = []
}

variable "api_allowed_cidrs" {
  description = "CIDRs allowed to reach the Kubernetes API endpoint. Required in prod."
  type        = list(string)
  default     = []

  validation {
    condition     = length(var.api_allowed_cidrs) > 0
    error_message = "prod: set api_allowed_cidrs (office/VPN/CI egress CIDRs); the API must not be open to the internet."
  }
}

variable "bucket_name_suffix" {
  description = "Suffix for globally unique bucket names, if the plain name is taken."
  type        = string
  default     = ""
}

variable "signing_key_ids" {
  description = "Token signing key rotation (docs/runbooks/key-rotation.md): active overrides KMS_KEY_ID (null = the key Terraform created), published = KMS_PUBLISHED_KEY_IDS."
  type = object({
    active    = optional(string)
    published = optional(list(string), [])
  })
  default = {}
}

variable "sms_origination_identity" {
  description = "ARN of the End User Messaging phone number or pool for phone codes (SMS_PROVIDER=aws, S-8); null = Twilio (manual inputs)."
  type        = string
  default     = null
}
