# Infrastructure — Terraform modules, stacks and env roots for AWS, Google Cloud and Azure (S-2/S-3). Offline: no cloud
# credentials, no state. docs/runbooks/infrastructure.md, ci.md. validate.sh needs bash 4+ and GNU find
# (macOS: brew install bash findutils, then put them first on PATH).

CLOUD ?= all

##@ Infrastructure (Terraform)

.PHONY: infra-validate
infra-validate: tf-validate tf-lint ## Every offline Terraform check (what CI's infra jobs run)

.PHONY: tf-fmt
tf-fmt: ## terraform fmt -recursive infra/terraform
	cd $(ROOT)/infra/terraform && terraform fmt -recursive .

.PHONY: tf-fmt-check
tf-fmt-check: ## terraform fmt -check (no changes)
	cd $(ROOT)/infra/terraform && terraform fmt -check -recursive -diff .

.PHONY: tf-validate
tf-validate: ## fmt check, module contract, init -backend=false + validate, terraform test (CLOUD=all|aws|gcp|azure)
	@mkdir -p "$${TF_PLUGIN_CACHE_DIR:-$$HOME/.terraform.d/plugin-cache}"
	cd $(ROOT)/infra/terraform && TF_PLUGIN_CACHE_DIR="$${TF_PLUGIN_CACHE_DIR:-$$HOME/.terraform.d/plugin-cache}" bash scripts/validate.sh $(CLOUD)

.PHONY: tf-lint
tf-lint: ## tflint with the terraform, aws, google and azurerm rulesets
	cd $(ROOT)/infra/terraform && tflint --init --config "$$PWD/.tflint.hcl" && tflint --recursive --config "$$PWD/.tflint.hcl" --format compact

##@ GCP dry run (docs/runbooks/dryrun-gcp.md)

.PHONY: dryrun-down
dryrun-down: ## Scale the dry run's node pool to 0 between demos (the credit then pays only disks, LB, DNS)
	$(ROOT)/scripts/dryrun-nodes.sh down

.PHONY: dryrun-up
dryrun-up: ## Bring the dry run's nodes back (NODES=2) — pods and data come back with them, ≈ 5 minutes
	$(ROOT)/scripts/dryrun-nodes.sh up $(or $(NODES),2)

.PHONY: dryrun-status
dryrun-status: ## The dry run's node pool: machine type, Spot, autoscaling, running nodes
	$(ROOT)/scripts/dryrun-nodes.sh status

.PHONY: demo-data
demo-data: ## Demo marketplace through the product (ENV=local|dryrun; RESET=1 / RESET=all; ONLY=bakery,wine) — docs/runbooks/demo.md
	$(ROOT)/scripts/demo-data.sh --env $(or $(ENV),local) $(if $(filter all,$(RESET)),--reset-all,$(if $(RESET),--reset)) $(if $(ONLY),--only $(ONLY))
