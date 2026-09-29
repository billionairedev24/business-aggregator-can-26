terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.66, < 7.0"
    }
    ec = {
      source  = "elastic/ec"
      version = ">= 0.13, < 1.0"
    }
  }
}
