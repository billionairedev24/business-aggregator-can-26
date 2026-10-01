# Documentation — OpenAPI specs (S-125) and the documentation site (S-126).

##@ Docs and API specs

# S-125 (docs/runbooks/api-docs.md): the specs are what the apps' OpenApiSpecsTest fetch from a booted context
# (api, northline-auth, studio-bff and consumer-bff); -Popenapi.write=true writes them instead of comparing.
REDOCLY_VERSION ?= 2.57.0
openapi_tests = --no-parallel :api:test --tests '*OpenApiSpecsTest' :auth:test --tests '*OpenApiSpecsTest' \
	:bff:test --tests '*BffSessionTest' --tests '*ConsumerBffTest'

.PHONY: openapi
openapi: ## Regenerate docs/api/openapi/*.yaml from the code (boots api, auth, bff in tests; Docker), then lint
	$(GRADLE) $(openapi_tests) -Popenapi.write=true
	@cd $(ROOT) && git status --short -- docs/api/openapi
	@$(MAKE) openapi-lint

.PHONY: openapi-check
openapi-check: ## Fail when the code and the committed specs differ (./gradlew build runs the same check)
	$(GRADLE) $(openapi_tests)

.PHONY: openapi-lint
openapi-lint: ## Lint every committed spec with Redocly CLI (redocly.yaml; pnpm dlx, pinned REDOCLY_VERSION)
	cd $(ROOT) && pnpm --package=@redocly/cli@$(REDOCLY_VERSION) dlx redocly lint --config redocly.yaml

.PHONY: api-docs
api-docs: ## Where the viewers are: Swagger UI, Scalar and Redoc of each running app (local, dev, staging)
	@printf '  api   http://localhost:8080/docs   (Swagger UI /swagger-ui.html · Scalar /docs/scalar · Redoc /docs/redoc)\n'
	@printf '  auth  http://localhost:9000/docs   (same paths)\n'
	@printf '  bff   http://localhost:8082/bff/docs, consumer-bff http://localhost:8081/bff/docs   (under /bff)\n'

.PHONY: docs
docs: ## Build the documentation site (S-126)
	@echo "make docs arrives with S-126 (Docusaurus documentation site)."; exit 2

.PHONY: docs-clean
docs-clean:
