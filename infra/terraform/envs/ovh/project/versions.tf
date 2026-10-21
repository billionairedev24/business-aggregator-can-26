terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    ovh = {
      source  = "ovh/ovh"
      version = "~> 2.22"
    }
  }

  # Local state, kept out of git: scripts/ovh/project.sh inits it at .ovh/project-<location>.tfstate (S-161).
  backend "local" {}
}
