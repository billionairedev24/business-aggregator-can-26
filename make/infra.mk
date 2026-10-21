# Infrastructure — Terraform modules, stacks and env roots for AWS, Google Cloud and Azure (S-2/S-3), and OVHcloud. Offline: no cloud
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
tf-validate: ## fmt check, module contract, init -backend=false + validate, terraform test (CLOUD=all|aws|gcp|azure|ovh)
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

##@ Dry-run targets: Google Cloud or OVHcloud (docs/runbooks/README.md § Google vs OVH)

.PHONY: deploy
deploy: ## Install/upgrade the data services and Northline charts (TARGET=gcp-dryrun|ovh-vps|ovh-mks; REGISTRY, IMAGE_TAG)
	$(ROOT)/scripts/deploy-target.sh $(TARGET)

.PHONY: ovh-bootstrap
ovh-bootstrap: ## ovh-vps: k3s, firewall and Helm on the VPS over SSH, kubeconfig + tunnel here (HOST=user@ip; default from Terraform)
	$(ROOT)/scripts/ovh/bootstrap-k3s.sh $(HOST)

.PHONY: ovh-tunnel
ovh-tunnel: ## ovh-vps: open the SSH tunnel to the k3s API (127.0.0.1:16443)
	$(ROOT)/scripts/ovh/bootstrap-k3s.sh tunnel $(HOST)

.PHONY: ovh-secrets-init
ovh-secrets-init: ## Create .ovh/TARGET.env: generated secrets filled in, Stripe/Twilio/registry to fill (TARGET=ovh-vps|ovh-mks)
	$(ROOT)/scripts/ovh/secrets.sh init $(or $(TARGET),ovh-vps)

.PHONY: ovh-secrets
ovh-secrets: ## Write the Kubernetes Secrets from .ovh/TARGET.env and Terraform's S3/database credentials
	$(ROOT)/scripts/ovh/secrets.sh apply $(or $(TARGET),ovh-vps)

.PHONY: ovh-down
ovh-down: ## Apps to 0 between demos; ovh-mks also node pool → 0 (TARGET=ovh-vps|ovh-mks; the VPS bills monthly anyway)
	$(ROOT)/scripts/ovh/scale.sh down $(or $(TARGET),ovh-vps)

.PHONY: ovh-up
ovh-up: ## Back from ovh-down: ovh-mks nodes from terraform.tfvars, apps to their previous replicas
	$(ROOT)/scripts/ovh/scale.sh up $(or $(TARGET),ovh-vps)

.PHONY: ovh-status
ovh-status: ## Nodes, Deployments and StatefulSets of the OVHcloud target (TARGET=ovh-vps|ovh-mks)
	$(ROOT)/scripts/ovh/scale.sh status $(or $(TARGET),ovh-vps)

.PHONY: ovh-smoke
ovh-smoke: ## Smoke test after a deploy: rollouts, health, the public address, HTTPS once DNS points at it (TARGET=ovh-vps|ovh-mks)
	$(ROOT)/scripts/ovh/smoke.sh $(or $(TARGET),ovh-vps)

.PHONY: ovh-gh-setup
ovh-gh-setup: ## Once per target, on your Mac: the GitHub Environment + every secret/variable of the ovh pipeline (gh CLI; TARGET=ovh-vps|ovh-mks)
	$(ROOT)/scripts/ovh/gh-setup.sh $(or $(TARGET),ovh-vps)

.PHONY: ovh-tf-init
ovh-tf-init: ## terraform init of the target's root; with TF_STATE_BUCKET (+ TF_STATE_ACCESS_KEY/SECRET_KEY) on the shared Object Storage state (MIGRATE=1 moves a local state there)
	$(ROOT)/scripts/ovh/tf-state.sh ensure $(or $(TARGET),ovh-vps)
	$(ROOT)/scripts/ovh/tf-state.sh init $(or $(TARGET),ovh-vps)

.PHONY: demo-data
demo-data: ## Demo marketplace through the product (ENV=local|dryrun|ovh-vps|ovh-mks; RESET=1 / RESET=all; ONLY=bakery,wine) — docs/runbooks/demo.md
	$(ROOT)/scripts/demo-data.sh --env $(or $(ENV),local) $(if $(filter all,$(RESET)),--reset-all,$(if $(RESET),--reset)) $(if $(ONLY),--only $(ONLY))

.PHONY: demo-data-test
demo-data-test: ## The demo plan's rules (counts, references, no place names) and the driver's helpers (node --test)
	cd $(ROOT) && node --test scripts/demo/demo-data.test.mjs
