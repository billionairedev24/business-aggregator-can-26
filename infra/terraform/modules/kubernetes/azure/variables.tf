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
  description = "network.cluster_subnet_ids: subnets for the nodes."
  type        = list(string)
}

variable "kubernetes_version" {
  description = "Kubernetes minor version (e.g. \"1.34\"); null = the provider's current default."
  type        = string
  default     = null
}

variable "node_pools" {
  description = "Node pools. machine_type is the cloud's instance type (m7i.large / e2-standard-4 / Standard_D4s_v5). On AKS the pool named system (or else the first by key order) is the system pool."
  type = map(object({
    machine_type = string
    min_count    = number
    max_count    = number
    disk_size_gb = optional(number, 50)
    spot         = optional(bool, false)
    labels       = optional(map(string), {})
  }))

  validation {
    condition     = length(var.node_pools) > 0 && alltrue([for p in var.node_pools : p.min_count <= p.max_count && p.max_count > 0])
    error_message = "At least one node pool, each with 0 <= min_count <= max_count and max_count > 0."
  }
}

variable "kms_key" {
  description = "{ id = kms.key_ids[\"data\"] }: encrypts Kubernetes Secrets at rest (envelope encryption). null disables it. An object so that whether it is set is known at plan time, before the key exists."
  type = object({
    id = string
  })
  default = null
}

variable "api_allowed_cidrs" {
  description = "CIDRs allowed to reach the public Kubernetes API endpoint (operators, CI). Empty = anywhere (not for prod)."
  type        = list(string)
  default     = []
}

variable "admin_principals" {
  description = "Cluster administrators: IAM role/user ARNs (AWS), IAM members such as user:ops@northline.ca (Google Cloud), Entra object ids (Azure)."
  type        = list(string)
  default     = []
}

variable "workload_identities" {
  description = "Cloud identities for Kubernetes service accounts (IRSA / GKE Workload Identity / Azure Workload Identity), keyed by a short name such as api."
  type = map(object({
    namespace       = string
    service_account = string
  }))
  default = {}
}

variable "deletion_protection" {
  description = "Refuse to destroy the cluster (prod)."
  type        = bool
  default     = false
}
