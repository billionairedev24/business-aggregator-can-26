# tflint for every module and root: `tflint --init` once, then `tflint --recursive --config "$PWD/.tflint.hcl"`
# from infra/terraform (CI: .github/workflows/infra.yml, ci/gitlab/infra.yml).

config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "aws" {
  enabled = true
  version = "0.49.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}

plugin "google" {
  enabled = true
  version = "0.40.0"
  source  = "github.com/terraform-linters/tflint-ruleset-google"
}

plugin "azurerm" {
  enabled = true
  version = "0.32.0"
  source  = "github.com/terraform-linters/tflint-ruleset-azurerm"
}

# Every variable and output carries a description; module and provider versions are pinned.
rule "terraform_documented_variables" {
  enabled = true
}

rule "terraform_documented_outputs" {
  enabled = true
}

rule "terraform_naming_convention" {
  enabled = true
}

# prevent_destroy cannot depend on a variable, and dev/staging must stay destroyable. Stateful resources are
# protected by deletion_protection (resource locks, provider flags) instead.
rule "azurerm_resources_missing_prevent_destroy" {
  enabled = false
}
