# Offline plan with mocked providers: evaluates every expression, count/for_each and validation of this root
# without cloud credentials or state. Run: terraform init -backend=false && terraform test
mock_provider "google" {
  # Well-formed values where the provider parses them at plan time (mocks return random strings).
  mock_resource "google_container_cluster" {
    override_during = plan
    defaults = {
      endpoint = "203.0.113.10"
    }
  }
  mock_data "google_project" {
    defaults = { number = "123456789012" }
  }
  mock_data "google_storage_project_service_account" {
    defaults = { email_address = "service-123456789012@gs-project-accounts.iam.gserviceaccount.com" }
  }
}

mock_provider "google-beta" {}

variables {
  project_id = "northline-staging"
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
    region = "us-central1"
  }

  expect_failures = [var.region]
}
