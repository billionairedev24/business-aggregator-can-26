variable "ovh_location" {
  description = "Where the target lives (infra/terraform/stacks/ovh/locations.json, S-160): us-east-va (Vint Hill, Virginia — OVHcloud US, the default for now), us-west-or (Hillsboro, Oregon — OVHcloud US) or ca-bhs (Beauharnois, Québec — OVHcloud Canada). Decides the API endpoint (ovh-us / ovh-ca), the Public Cloud region, Object Storage, the database region and the VPS datacentre (the VPS is ordered there in the console). The account must belong to that OVHcloud entity: us.ovhcloud.com and ca.ovhcloud.com accounts are separate. Credentials come from the environment (OVH_APPLICATION_KEY, OVH_APPLICATION_SECRET, OVH_CONSUMER_KEY, or OVH_CLIENT_ID / OVH_CLIENT_SECRET) — docs/runbooks/ovh.md § Today. A US location holds fake demo data only (dev profile): Canadian data residency applies to real customer data, which must be on ca-bhs before launch (DECISIONS 2026-10-20, S-160)."
  type        = string
  default     = "us-east-va"

  validation {
    condition     = contains(["us-east-va", "us-west-or", "ca-bhs"], var.ovh_location)
    error_message = "ovh_location must be us-east-va or us-west-or (OVHcloud US, demo data only) or ca-bhs (OVHcloud Canada)."
  }
}

variable "service_name" {
  description = "Public Cloud project id (32 hex characters) that holds Object Storage — a project costs nothing by itself."
  type        = string
}

variable "vps_service_name" {
  description = "The VPS's service name once ordered in the console (Bare Metal Cloud › VPS, e.g. vps-1a2b3c4d.vps.ovh.us in the US, vps-1a2b3c4d.vps.ovh.ca in Canada). Empty = no VPS yet: only Object Storage is planned, no firewall."
  type        = string
  default     = ""
}

variable "edge_firewall" {
  description = "OVHcloud Network Firewall on the VPS's IPv4 (in front of the VPS, before any packet reaches it): only SSH from admin_cidrs, HTTP and HTTPS, plus replies to the VPS's own connections. scripts/ovh/bootstrap-k3s.sh adds the same rules on the host (ufw). Ignored where the location has no Network Firewall (locations.json edge_firewall = false): ufw alone then."
  type        = bool
  default     = true
}

variable "admin_cidrs" {
  description = "Source CIDRs allowed to reach SSH (22) — your IP: curl -s ifconfig.me, as 203.0.113.7/32. At most 3. kubectl reaches k3s through an SSH tunnel; 6443 is never open."
  type        = list(string)
  default     = []

  validation {
    condition     = length(var.admin_cidrs) <= 3 && alltrue([for c in var.admin_cidrs : can(cidrhost(c, 0)) && !strcontains(c, ":")])
    error_message = "admin_cidrs: at most 3 IPv4 CIDRs (e.g. 203.0.113.7/32)."
  }
}

variable "ci_ssh_cidr" {
  description = "One extra SSH source for the length of a pipeline run (.github/workflows/ovh.yml sets the runner's address/32 before it connects and removes it at the end — scripts/ovh/ci.sh). Empty = no such rule. Never set it in terraform.tfvars."
  type        = string
  default     = ""

  validation {
    condition     = var.ci_ssh_cidr == "" || (can(cidrhost(var.ci_ssh_cidr, 0)) && !strcontains(var.ci_ssh_cidr, ":"))
    error_message = "ci_ssh_cidr: empty or one IPv4 CIDR (e.g. 203.0.113.7/32)."
  }
}

variable "dns_zone_name" {
  description = "DNS zone of the public hosts (values-dryrun.yaml urls: demo.northline.ca, studio.demo.northline.ca, …). Only used to print the records to create."
  type        = string
  default     = "demo.northline.ca"
}

variable "bucket_name_suffix" {
  description = "Suffix for the bucket names when the plain name is taken."
  type        = string
  default     = ""
}

variable "data_services" {
  description = "Per data service, in-cluster (deploy/helm/northline-data on the VPS, the default) or external (endpoint here, credential in .ovh/ovh-vps.env): the Google Cloud dry run's semantics."
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
