variable "environment" {
  description = "dev, staging or prod."
  type        = string
}

variable "region" {
  description = "Canadian AWS region."
  type        = string
}

variable "owner" {
  description = "Owner tag (team or person accountable for the environment)."
  type        = string
}

variable "cidr" {
  description = "VPC CIDR."
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
  description = "EKS minor version; null = AWS default."
  type        = string
  default     = null
}

variable "node_pools" {
  description = "EKS managed node groups (see modules/kubernetes)."
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
  description = "IAM role/user ARNs that administer the cluster."
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

variable "signing_key_ids" {
  description = "Token signing key rotation (docs/runbooks/key-rotation.md § Rotating — cloud providers). active = KMS_KEY_ID (null = the signing key Terraform created: AWS key ARN, Google Cloud key version name, Azure versioned key URL); published = KMS_PUBLISHED_KEY_IDS (keys published without signing: the next key before a switch, the previous one after it)."
  type = object({
    active    = optional(string)
    published = optional(list(string), [])
  })
  default = {}
}

variable "sms_origination_identity" {
  description = "ARN of the AWS End User Messaging phone number or pool that sends phone codes (S-8, SMS_PROVIDER=aws), requested by hand; null = Twilio, configured by the operator."
  type        = string
  default     = null

  validation {
    condition     = var.sms_origination_identity == null || can(regex("^arn:aws[a-z-]*:sms-voice:ca-(central|west)-1:[0-9]{12}:(phone-number|pool)/.+$", var.sms_origination_identity))
    error_message = "sms_origination_identity must be the ARN of an End User Messaging phone number or pool in a Canadian region."
  }
}

variable "backup" {
  description = "S-114 backups and disaster recovery (docs/runbooks/backups-dr.md). cross_region = keep a copy of the database and of every bucket in the other Canadian region of this cloud (prod); copy_retention_days = how long that region keeps the database's replicated backups (AWS); replica_cool_after_days / replica_noncurrent_days = the replica buckets' lifecycle."
  type = object({
    cross_region            = optional(bool, false)
    copy_retention_days     = optional(number, 14)
    replica_cool_after_days = optional(number, 30)
    replica_noncurrent_days = optional(number, 90)
  })
  default = {}
}
