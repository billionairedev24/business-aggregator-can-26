terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 8.5, < 9.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.9, < 4.0"
    }
  }
}
