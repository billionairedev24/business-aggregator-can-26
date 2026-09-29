# Offline plan with mocked providers: evaluates every expression, count/for_each and validation of this root
# without cloud credentials or state. Run: terraform init -backend=false && terraform test
mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["ca-central-1a", "ca-central-1b", "ca-central-1d"] }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
}

variables {
  api_allowed_cidrs = ["203.0.113.0/24"]
}

run "plan" {
  command = plan
}

run "config_env_keys" {
  command = plan

  assert {
    condition     = alltrue([for k in ["STORAGE_PROVIDER", "STORAGE_BUCKET", "STORAGE_REGION", "KMS_PROVIDER"] : contains(keys(output.config_env), k)])
    error_message = "config_env must carry the variable names the apps read (docs/runbooks/README.md)."
  }
}

run "rejects_region_outside_canada" {
  command = plan

  variables {
    region = "us-east-1"
  }

  expect_failures = [var.region]
}
