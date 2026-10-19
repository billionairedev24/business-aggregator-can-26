# The pieces both OVHcloud targets share (docs/runbooks/ovh.md): Object Storage buckets and their least-privilege S3
# users, and the apps' non-secret configuration (config_env) — the same variable names as every other cloud.
# Not a module under modules/: OVHcloud is not one of the three clouds of the module contract (scripts/check-contract.sh).

variable "service_name" {
  description = "OVHcloud Public Cloud project id (32 hex characters; Public Cloud › the project › its id). Object Storage lives in a Public Cloud project, also for the VPS target."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.service_name))
    error_message = "service_name is the Public Cloud project id: 32 lowercase hex characters."
  }
}

variable "region" {
  description = "OVHcloud Public Cloud region. Canadian data residency: BHS5 (Beauharnois, Québec) only — Toronto is added once its services (Object Storage, Managed Kubernetes, Managed PostgreSQL) are confirmed there."
  type        = string
  default     = "BHS5"

  validation {
    condition     = contains(["BHS5"], var.region)
    error_message = "Canadian data residency: region must be an OVHcloud Canadian region (BHS5, Beauharnois)."
  }
}

variable "target" {
  description = "Which OVHcloud target this is: ovh-vps (one VPS with k3s) or ovh-mks (Managed Kubernetes). Names the buckets and users."
  type        = string

  validation {
    condition     = contains(["ovh-vps", "ovh-mks"], var.target)
    error_message = "target must be ovh-vps or ovh-mks."
  }
}

variable "namespace" {
  description = "Kubernetes namespace of the apps and of deploy/helm/northline-data (in-cluster service endpoints)."
  type        = string
  default     = "northline-dryrun"
}

variable "bucket_name_suffix" {
  description = "Suffix for the bucket names when the plain name is taken (Object Storage bucket names are shared by every OVHcloud customer of the region)."
  type        = string
  default     = ""
}

variable "data_services" {
  description = "Per data service, in-cluster (deploy/helm/northline-data) or external (endpoint here, credential in the operator's secrets file .ovh/<target>.env): the same semantics as the Google Cloud dry run's variable."
  type = object({
    postgres = object({
      mode    = string
      db_url  = string
      db_user = string
    })
    cache = object({
      mode     = string
      host     = string
      port     = number
      ssl      = bool
      username = string
    })
    search = object({
      mode = string
      url  = string
    })
    kafka = object({
      mode              = string
      bootstrap         = string
      security_protocol = string
      sasl_mechanism    = string
    })
  })

  validation {
    condition     = alltrue([for s in ["postgres", "cache", "search", "kafka"] : contains(["in-cluster", "external"], var.data_services[s].mode)])
    error_message = "data_services.<service>.mode must be in-cluster or external."
  }
}

variable "storage_class_name" {
  description = "StorageClass of the in-cluster volumes (k3s: local-path; MKS: csi-cinder-high-speed)."
  type        = string
}

variable "postgres_backup_retention_days" {
  description = "Days of Postgres base backups and WAL kept in the backup bucket (in-cluster Postgres; CloudNativePG retention)."
  type        = number
  default     = 7
}
