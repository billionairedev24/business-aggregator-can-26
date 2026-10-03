# End-to-end suite (S-117): web/e2e, Playwright on the real stack. docs/runbooks/e2e.md.

##@ End-to-end (S-117)

# One full stack per machine: `make e2e` waits for this lock (flock; another make e2e, a load test, …).
E2E_LOCK ?= $(ROOT)/.run/stack.lock
E2E_OUT ?= $(ROOT)/e2e-out
export E2E_OUT
# Extra `playwright test` arguments, e.g. E2E_ARGS="--grep @smoke" or E2E_ARGS=tests/4-payout.spec.ts
E2E_ARGS ?=
export E2E_ARGS
ENV ?=
# Run under flock when it exists (Linux, CI); macOS has none — then nothing else should run the stack meanwhile.
e2e_locked = mkdir -p $(dir $(E2E_LOCK)) && if command -v flock >/dev/null 2>&1; then flock $(E2E_LOCK) $(1); else $(1); fi

.PHONY: e2e
e2e: $(WEB_INSTALLED) ## End-to-end suite: DISPOSABLE Postgres + api, auth, 3 BFFs, 3 web apps (local profile), Playwright, stop (E2E_ARGS, E2E_LOCK)
	@cd $(ROOT) && $(call e2e_locked,ci/e2e.sh run)

.PHONY: e2e-smoke
e2e-smoke: ## Only the Studio smoke sweep of the suite (every Studio screen, 3 widths/languages) — same stack as make e2e
	@$(MAKE) e2e E2E_ARGS="--grep @smoke"

.PHONY: e2e-up
e2e-up: $(WEB_INSTALLED) ## Start the e2e stack and leave it running (writing tests: then make e2e-test, finally make e2e-down)
	@cd $(ROOT) && ci/e2e.sh up

.PHONY: e2e-test
e2e-test: ## Run the suite against the stack make e2e-up started (E2E_ARGS)
	@cd $(ROOT) && ci/e2e.sh test

.PHONY: e2e-down
e2e-down: ## Stop what make e2e-up started (and its Postgres container)
	@cd $(ROOT) && ci/e2e.sh down

.PHONY: e2e-target
e2e-target: $(WEB_INSTALLED) ## The suite against a deployed environment: ENV=dev|staging (URLs, test data, personas: docs/runbooks/e2e.md); never prod
	@case "$(ENV)" in "") echo "ENV=dev|staging is required" >&2; exit 2;; prod*) echo "ENV=$(ENV): the suite creates businesses, orders and payouts — never against prod" >&2; exit 2;; esac
	@cd $(ROOT)/web && set -a && { [ ! -f e2e/env/$(ENV).env ] || . e2e/env/$(ENV).env; } && set +a \
		&& E2E_MODE=target E2E_ENV=$(ENV) E2E_DATA=$${E2E_DATA:-$(ROOT)/web/e2e/data/$(ENV).json} \
		pnpm --filter @northline/e2e exec playwright test $(E2E_ARGS)
