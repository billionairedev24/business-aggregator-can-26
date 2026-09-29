# Offline plan with mocked providers: evaluates every expression, count/for_each and validation of this root
# without cloud credentials or state. Run: terraform init -backend=false && terraform test
mock_provider "azurerm" {
  # Well-formed values where the provider parses them at plan time (mocks return random strings).
  mock_resource "azurerm_kubernetes_cluster" {
    override_during = plan
    defaults = {
      id              = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-northline/providers/Microsoft.ContainerService/managedClusters/northline"
      oidc_issuer_url = "https://canadacentral.oic.prod-aks.azure.com/tenant/issuer/"
    }
  }
  mock_data "azurerm_client_config" {
    defaults = {
      subscription_id = "00000000-0000-0000-0000-000000000000"
      tenant_id       = "11111111-1111-1111-1111-111111111111"
      object_id       = "22222222-2222-2222-2222-222222222222"
    }
  }
}

mock_provider "random" {}

variables {
  subscription_id = "00000000-0000-0000-0000-000000000000"
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
    region = "eastus"
  }

  expect_failures = [var.region]
}
