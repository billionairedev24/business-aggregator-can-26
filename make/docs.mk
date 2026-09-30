# Documentation — OpenAPI specs (S-125) and the documentation site (S-126). The targets exist from S-124 on so
# `make help` and CI name them; each story fills its own.

.PHONY: openapi docs docs-clean

##@ Docs and API specs

openapi: ## Regenerate docs/api/openapi/*.json|yaml from the code (S-125)
	@echo "make openapi arrives with S-125 (OpenAPI 3.1 for every HTTP service)."; exit 2

docs: ## Build the documentation site (S-126)
	@echo "make docs arrives with S-126 (Docusaurus documentation site)."; exit 2

docs-clean:
