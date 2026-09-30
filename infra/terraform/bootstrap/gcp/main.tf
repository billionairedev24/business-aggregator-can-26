# One-off: the GCS bucket that holds Terraform state for the Northline Google Cloud environments. Put it in a small
# shared project (or the prod project). Run with local state:
#   terraform init && terraform apply -var project_id=<state-project>
# then copy the output into envs/gcp/<env>/backend.hcl.

terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.5"
    }
  }
}

variable "project_id" {
  description = "Project that owns the state bucket."
  type        = string
}

variable "region" {
  description = "Canadian region for the state bucket."
  type        = string
  default     = "northamerica-northeast1"

  validation {
    condition     = contains(["northamerica-northeast1", "northamerica-northeast2"], var.region)
    error_message = "Canadian data residency: region must be northamerica-northeast1 or northamerica-northeast2."
  }
}

variable "owner" {
  description = "Owner label."
  type        = string
  default     = "platform"
}

provider "google" {
  project = var.project_id
  region  = var.region
}

resource "google_storage_bucket" "state" {
  name                        = "${var.project_id}-northline-tfstate"
  location                    = upper(var.region)
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  labels = {
    app              = "northline"
    env              = "shared"
    owner            = var.owner
    "data-residency" = "ca"
    "managed-by"     = "terraform"
  }

  versioning {
    enabled = true
  }

  lifecycle_rule {
    condition {
      num_newer_versions = 20
      with_state         = "ARCHIVED"
    }
    action {
      type = "Delete"
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

output "backend_hcl" {
  description = "Settings for envs/gcp/<env>/backend.hcl (replace <env>)."
  value       = <<-EOT
    bucket = "${google_storage_bucket.state.name}"
    prefix = "gcp/<env>"
  EOT
}
