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

variable "network_id" {
  description = "network.network_id."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets for the service: network.data_subnet_ids (Azure PostgreSQL: network.cloud.postgres_subnet_id)."
  type        = list(string)
}

variable "allowed_cidrs" {
  description = "CIDRs allowed to connect (the network CIDR, i.e. the cluster)."
  type        = list(string)
}

variable "kms_key" {
  description = "{ id = kms.key_ids[\"data\"] } for encryption at rest with a customer-managed key where the service supports it without extra setup; null = the provider's default encryption. An object so that whether it is set is known at plan time, before the key exists."
  type = object({
    id = string
  })
  default = null
}

variable "secret_store" {
  description = "secrets.store: where generated credentials are written (names <prefix><name>), so External Secrets can read them."
  type = object({
    id     = string
    prefix = string
    kms_key = optional(object({
      id = string
    }))
  })
}

variable "deletion_protection" {
  description = "Refuse to destroy, keep final snapshots / longest retention (prod)."
  type        = bool
  default     = false
}

variable "instance_size" {
  description = "Instance class/tier: db.t4g.micro (AWS), db-custom-1-3840 (Google Cloud), B_Standard_B1ms (Azure)."
  type        = string
}

variable "storage_gb" {
  description = "Initial storage (grows automatically)."
  type        = number
  default     = 20
}

variable "high_availability" {
  description = "Standby in another zone (Multi-AZ / REGIONAL / ZoneRedundant)."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "Automated backup retention; point-in-time recovery covers the same window."
  type        = number
  default     = 7

  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 35
    error_message = "backup_retention_days must be between 1 and 35."
  }
}

variable "database_name" {
  description = "Database created for the apps (DB_URL)."
  type        = string
  default     = "northline"
}

variable "app_user" {
  description = "Login role for the apps (DB_USER). Terraform generates its password into the secrets store; the role itself is created by the bootstrap SQL in docs/runbooks/infrastructure.md."
  type        = string
  default     = "northline_app"
}

variable "postgres_version" {
  description = "PostgreSQL major version."
  type        = string
  default     = "17"
}

variable "backup_copy" {
  description = "S-114: keep a copy of the database in the other Canadian region of the cloud, so a regional loss is recoverable; null = backups only in the primary region. AWS: automated backups replicated to that region (point-in-time restore there, retention_days); Google Cloud: a cross-region read replica to promote (Cloud SQL backups are encrypted with the primary region's key); Azure: geo-redundant backup to the paired region (geo-restore; the region must be the pair, retention follows backup_retention_days). kms_key = a key in that region (kms module instantiated there; ignored on Azure, service-managed keys)."
  type = object({
    region = string
    kms_key = optional(object({
      id = string
    }))
    retention_days = optional(number, 14)
  })
  default = null

  validation {
    condition     = var.backup_copy == null || (contains(["canadacentral", "canadaeast"], try(var.backup_copy.region, "")) && try(var.backup_copy.region, "") != var.context.region)
    error_message = "Canadian data residency: backup_copy.region must be the other Canadian region of Azure (canadacentral ⇄ canadaeast, the paired regions)."
  }

  validation {
    condition     = var.backup_copy == null || (try(var.backup_copy.retention_days, 0) >= 1 && try(var.backup_copy.retention_days, 0) <= 35)
    error_message = "backup_copy.retention_days must be between 1 and 35."
  }
}
