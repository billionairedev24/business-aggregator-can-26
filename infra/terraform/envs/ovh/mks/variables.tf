variable "ovh_endpoint" {
  description = "OVHcloud API endpoint of the account: ovh-ca for an OVHcloud Canada account (ca.ovh.com), ovh-eu for a European one. Credentials come from the environment (OVH_APPLICATION_KEY, OVH_APPLICATION_SECRET, OVH_CONSUMER_KEY, or OVH_CLIENT_ID / OVH_CLIENT_SECRET) — docs/runbooks/ovh.md § API credentials."
  type        = string
  default     = "ovh-ca"

  validation {
    condition     = contains(["ovh-ca", "ovh-eu"], var.ovh_endpoint)
    error_message = "ovh_endpoint must be ovh-ca (OVHcloud Canada) or ovh-eu."
  }
}

variable "service_name" {
  description = "Public Cloud project id (32 hex characters) for the cluster, the database and Object Storage."
  type        = string
}

variable "region" {
  description = "OVHcloud Public Cloud region. Canadian data residency: BHS5 (Beauharnois) — one location for the cluster, its private network, the database and Object Storage (no egress between them)."
  type        = string
  default     = "BHS5"

  validation {
    condition     = contains(["BHS5"], var.region)
    error_message = "Canadian data residency: region must be BHS5 (Beauharnois)."
  }
}

variable "kubernetes_version" {
  description = "Managed Kubernetes minor version (e.g. \"1.33\"); null = OVHcloud's latest."
  type        = string
  default     = null
}

variable "node_flavor" {
  description = "Instance flavor of the node pool (b3-16: 4 vCPU / 16 GB; b3-8 is too small for everything with in-cluster Postgres)."
  type        = string
  default     = "b3-16"
}

variable "node_count" {
  description = "Nodes in the pool. 2 carries everything with room for one to be replaced. `make ovh-down TARGET=ovh-mks` applies 0 (hourly nodes stop billing; the control plane is free), `make ovh-up` back to this value."
  type        = number
  default     = 2

  validation {
    condition     = var.node_count >= 0 && var.node_count <= 5
    error_message = "node_count: 0 (scaled down) to 5."
  }
}

variable "monthly_billed" {
  description = "Bill the nodes monthly (about half the hourly price over a full month, but each node is then due for the month and scaling to 0 saves nothing). false for a cluster that is scaled down between demos."
  type        = bool
  default     = false
}

variable "api_allowed_cidrs" {
  description = "CIDRs allowed to reach the Kubernetes API (your IP: curl -s ifconfig.me). Empty = no restriction (the API still needs the kubeconfig's client certificate)."
  type        = list(string)
  default     = []
}

variable "network_cidr" {
  description = "The private network's subnet (vRack): nodes, the database's private endpoint, the load balancer."
  type        = string
  default     = "10.60.0.0/24"

  validation {
    condition     = can(cidrhost(var.network_cidr, 0)) && tonumber(split("/", var.network_cidr)[1]) <= 26
    error_message = "network_cidr must be an IPv4 CIDR of at least /26 (e.g. 10.60.0.0/24)."
  }
}

variable "vlan_id" {
  description = "vRack VLAN id of the private network (unique within the project's vRack)."
  type        = number
  default     = 60
}

variable "managed_postgres" {
  description = "OVHcloud Managed PostgreSQL when data_services.postgres.mode = managed: plan essential (1 node, ≈ $50–65/month unverified) or business (2 nodes, HA), flavor, PostgreSQL 17 (PostGIS is not offered on 18), disk size in GB (null = the flavor's)."
  type = object({
    plan      = optional(string, "essential")
    flavor    = optional(string, "db1-4")
    version   = optional(string, "17")
    disk_size = optional(number)
  })
  default = {}

  validation {
    condition     = contains(["essential", "business"], var.managed_postgres.plan) && var.managed_postgres.version == "17"
    error_message = "managed_postgres: plan essential or business; version 17 (OVHcloud does not enable PostGIS on PostgreSQL 18)."
  }
}

variable "bucket_name_suffix" {
  description = "Suffix for the bucket names when the plain name is taken."
  type        = string
  default     = ""
}

variable "dns_zone_name" {
  description = "DNS zone of the public hosts (values-dryrun.yaml urls). Only used to print the records to create."
  type        = string
  default     = "demo.northline.ca"
}

variable "data_services" {
  description = "Per data service. postgres.mode: managed (OVHcloud Managed PostgreSQL, the default), in-cluster (deploy/helm/northline-data) or external (db_url). cache / search / kafka: in-cluster (default) or external, the Google Cloud dry run's semantics."
  type = object({
    postgres = optional(object({
      mode    = optional(string, "managed")
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
    condition = (
      contains(["managed", "in-cluster", "external"], var.data_services.postgres.mode) &&
      alltrue([for s in ["cache", "search", "kafka"] : contains(["in-cluster", "external"], var.data_services[s].mode)])
    )
    error_message = "data_services.postgres.mode must be managed, in-cluster or external; cache, search and kafka in-cluster or external."
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
