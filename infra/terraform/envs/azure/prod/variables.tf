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

variable "subscription_id" {
  description = "Azure subscription for this environment."
  type        = string
}
