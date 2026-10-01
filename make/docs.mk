# Documentation — OpenAPI specs (S-125) and the documentation site (S-126).

##@ Docs and API specs

# S-125 (docs/runbooks/api-docs.md): the specs are what the apps' OpenApiSpecsTest fetch from a booted context
# (api, northline-auth, studio-bff, consumer-bff and console-bff); -Popenapi.write=true writes them instead of comparing.
REDOCLY_VERSION ?= 2.57.0
openapi_tests = --no-parallel :api:test --tests '*OpenApiSpecsTest' :auth:test --tests '*OpenApiSpecsTest' \
	:bff:test --tests '*BffSessionTest' --tests '*ConsumerBffOpenApiTest' --tests '*ConsoleBffOpenApiTest'

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

# S-126 (docs/runbooks/docs-site.md): web/apps/docs, Docusaurus. DOCS_VARIANT=internal (everything, default) or public
# (guides + public/partner/webhook/OAuth references — what docs.<zone> serves in production).
DOCS_VARIANT ?= internal

.PHONY: docs
docs: $(WEB_INSTALLED) ## Build the documentation site (DOCS_VARIANT=internal|public; en + fr) and check every page exists
	cd $(ROOT)/web && NORTHLINE_DOCS_VARIANT=$(DOCS_VARIANT) pnpm --filter @northline/docs build
	cd $(ROOT)/web && NORTHLINE_DOCS_VARIANT=$(DOCS_VARIANT) pnpm --filter @northline/docs test:build

.PHONY: docs-serve
docs-serve: ## Serve the last make docs build on http://localhost:3300 (search works here, not in docs-dev)
	$(PNPM) --filter @northline/docs serve

.PHONY: docs-dev
docs-dev: $(WEB_INSTALLED) ## Docusaurus dev server on http://localhost:3300 with live reload (English only)
	cd $(ROOT)/web && NORTHLINE_DOCS_VARIANT=$(DOCS_VARIANT) pnpm --filter @northline/docs start

.PHONY: docs-pages
docs-pages: ## Static export of the public site for GitHub/GitLab Pages into web/apps/docs/build (DOCS_URL, DOCS_BASE_URL)
	$(MAKE) docs DOCS_VARIANT=public
	@# Pages have no running services: no Swagger UI links (static/config.js lists the local ones).
	printf 'window.__NL_DOCS__ = { swagger: [] };\n' > $(ROOT)/web/apps/docs/build/config.js

.PHONY: docs-clean
docs-clean:
	rm -rf $(ROOT)/web/apps/docs/build $(ROOT)/web/apps/docs/.docusaurus $(ROOT)/web/apps/docs/static/openapi $(ROOT)/web/apps/docs/static/scalar
