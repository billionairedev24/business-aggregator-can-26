variable "region" {
  description = "Canadian region (Google Cloud northamerica-northeast1 (Montréal) or northamerica-northeast2 (Toronto)). An external data service elsewhere (e.g. Layerbase in Ashburn) does not move the cluster: a non-Canadian region needs a new owner decision first (docs/DECISIONS.md)."
  type        = string
  default     = "northamerica-northeast1"

  validation {
    condition     = contains(["northamerica-northeast1", "northamerica-northeast2"], var.region)
    error_message = "Canadian data residency: region must be Google Cloud northamerica-northeast1 (Montréal) or northamerica-northeast2 (Toronto)."
  }
}

variable "zone" {
  description = "The one zone of the cluster and of every in-cluster volume; null = <region>-a."
  type        = string
  default     = null

  validation {
    condition     = var.zone == null || startswith(coalesce(var.zone, "-"), "${var.region}-")
    error_message = "zone must be a zone of region (e.g. northamerica-northeast1-a)."
  }
}

variable "owner" {
  description = "Value of the owner tag/label on every resource."
  type        = string
  default     = "platform"
}

variable "project_id" {
  description = "Google Cloud project for the dry run (a project of its own: deleting it is the teardown)."
  type        = string
}

variable "machine_type" {
  description = "Machine type of the one node pool."
  type        = string
  default     = "e2-standard-4"
}

variable "node_count" {
  description = "Node pool autoscaling bounds. Two e2-standard-4 carry everything; a free-trial billing account caps the project at 8 vCPUs (exactly two), so max 3 needs an upgraded account (the credit stays). Between demos scale to 0 with `make dryrun-down` instead of changing this."
  type = object({
    min = number
    max = number
  })
  default = { min = 2, max = 2 }
}

variable "spot_nodes" {
  description = "Spot VMs (about a third of the on-demand price; Google may reclaim a node at any time with 30 s notice, its pods restart on the other node or when the node comes back). Demo day: false (on demand, the pool is re-created), back to true afterwards."
  type        = bool
  default     = true
}

variable "dns_zone_name" {
  description = "Public DNS zone of the dry run's hosts (delegated from its parent with the name servers in `terraform output dns`)."
  type        = string
  default     = "demo.northline.ca"
}

variable "data_services" {
  description = "Per data service, in-cluster (deploy/helm/northline-data) or external (endpoint here, credential in Secret Manager): stacks/gcp variable data_services."
  type = object({
    postgres = optional(object({
      mode    = optional(string, "in-cluster")
      db_url  = optional(string, "")
      db_user = optional(string, "northline_app")
    }), {})
    cache = optional(object({
      mode     = optional(string, "in-cluster")
      host     = optional(string, "")
      port     = optional(number, 6379)
      ssl      = optional(bool, false)
      username = optional(string, "")
    }), {})
    search = optional(object({
      mode = optional(string, "in-cluster")
      url  = optional(string, "")
    }), {})
    kafka = optional(object({
      mode              = optional(string, "in-cluster")
      bootstrap         = optional(string, "")
      security_protocol = optional(string, "PLAINTEXT")
      sasl_mechanism    = optional(string, "")
    }), {})
  })
  default = {}

  validation {
    condition     = alltrue([for s in ["postgres", "cache", "search", "kafka"] : contains(["in-cluster", "external"], var.data_services[s].mode)])
    error_message = "data_services.<service>.mode must be in-cluster or external."
  }

  validation {
    condition = (
      (var.data_services.postgres.mode != "external" || startswith(var.data_services.postgres.db_url, "jdbc:postgresql://")) &&
      (var.data_services.cache.mode != "external" || var.data_services.cache.host != "") &&
      (var.data_services.search.mode != "external" || startswith(var.data_services.search.url, "https://")) &&
      (var.data_services.kafka.mode != "external" || var.data_services.kafka.bootstrap != "")
    )
    error_message = "An external service needs its endpoint: postgres.db_url (jdbc:postgresql://…?sslmode=require, with PostGIS, citext and pgcrypto available), cache.host, search.url (https://…), kafka.bootstrap."
  }
}

variable "admin_principals" {
  description = "IAM members (user:… / group:…) granted roles/container.admin."
  type        = list(string)
  default     = []
}

variable "api_allowed_cidrs" {
  description = "CIDRs allowed to reach the Kubernetes API endpoint (your IP: curl -s ifconfig.me)."
  type        = list(string)
  default     = []
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
