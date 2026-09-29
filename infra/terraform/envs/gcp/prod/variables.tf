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

variable "project_id" {
  description = "Google Cloud project for this environment."
  type        = string
}
