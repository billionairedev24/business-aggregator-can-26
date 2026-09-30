# Infrastructure — Terraform modules, stacks and env roots for AWS, Google Cloud and Azure (S-2/S-3). Offline: no cloud
# credentials, no state. docs/runbooks/infrastructure.md, ci.md. validate.sh needs bash 4+ and GNU find
# (macOS: brew install bash findutils, then put them first on PATH).

.PHONY: infra-validate tf-fmt tf-fmt-check tf-validate tf-lint

CLOUD ?= all

##@ Infrastructure (Terraform)

infra-validate: tf-validate tf-lint ## Every offline Terraform check (what CI's infra jobs run)

tf-fmt: ## terraform fmt -recursive infra/terraform
	cd $(ROOT)/infra/terraform && terraform fmt -recursive .

tf-fmt-check: ## terraform fmt -check (no changes)
	cd $(ROOT)/infra/terraform && terraform fmt -check -recursive -diff .

tf-validate: ## fmt check, module contract, init -backend=false + validate, terraform test (CLOUD=all|aws|gcp|azure)
	@mkdir -p "$${TF_PLUGIN_CACHE_DIR:-$$HOME/.terraform.d/plugin-cache}"
	cd $(ROOT)/infra/terraform && TF_PLUGIN_CACHE_DIR="$${TF_PLUGIN_CACHE_DIR:-$$HOME/.terraform.d/plugin-cache}" bash scripts/validate.sh $(CLOUD)

tf-lint: ## tflint with the terraform, aws, google and azurerm rulesets
	cd $(ROOT)/infra/terraform && tflint --init --config "$$PWD/.tflint.hcl" && tflint --recursive --config "$$PWD/.tflint.hcl" --format compact
