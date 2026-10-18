terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.5"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 8.5"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
    # The stack's Elastic Cloud module is not instantiated in the dry run (Meilisearch instead), but the provider is
    # still required by the stack's module tree; it needs no API key while nothing uses it.
    ec = {
      source  = "elastic/ec"
      version = "~> 0.13"
    }
  }
}
