# web/ — pnpm monorepo (tokens, ui + Storybook, auth-kit, client, studio, consumer, console). docs/runbooks/ci.md.
# Every target installs the dependencies first when web/pnpm-lock.yaml is newer than web/node_modules.

WEB_INSTALLED := $(ROOT)/web/node_modules/.modules.yaml

$(WEB_INSTALLED): $(ROOT)/web/pnpm-lock.yaml $(ROOT)/web/package.json
	$(PNPM) install --frozen-lockfile
	@touch $@

##@ Web (web/, pnpm)

.PHONY: web-install
web-install: $(WEB_INSTALLED) ## pnpm install (frozen lockfile); a no-op while node_modules is up to date

.PHONY: web-check
web-check: web-lint web-test web-build-studio web-build-console ## What CI's web checks run: hex colours, typecheck, vitest, Studio + console builds

.PHONY: web-build
web-build: $(WEB_INSTALLED) ## Build every web package that has a build script (tokens, studio, consumer, …)
	$(PNPM) -r build

.PHONY: web-build-studio
web-build-studio: $(WEB_INSTALLED) ## Build the Studio SPA (web/apps/studio/dist)
	$(PNPM) --filter @northline/studio build

.PHONY: web-build-console
web-build-console: $(WEB_INSTALLED) ## Build the platform console SPA (web/apps/console/dist; S-90)
	$(PNPM) --filter @northline/console build

.PHONY: web-build-consumer
web-build-consumer: $(WEB_INSTALLED) ## Build the consumer app (TanStack Start, SSR)
	$(PNPM) --filter @northline/consumer build

.PHONY: web-test
web-test: $(WEB_INSTALLED) ## vitest in every package
	$(PNPM) -r test

.PHONY: web-typecheck
web-typecheck: $(WEB_INSTALLED) ## tsc in every package
	$(PNPM) -r typecheck

.PHONY: web-lint
web-lint: $(WEB_INSTALLED) ## No hex colours in components (lint:colors) + typecheck
	$(PNPM) lint:colors
	$(PNPM) -r typecheck

# Prettier formats only the web files you changed (against BASE, default origin/main, plus uncommitted ones): most of
# web/ predates a Prettier config, so formatting everything would bury real changes. WEB_FORMAT_ALL=1 does all of it.
.PHONY: web-format
web-format: $(WEB_INSTALLED) ## Prettier on the web files changed since BASE (default origin/main); WEB_FORMAT_ALL=1 for all
	@cd $(ROOT) && if [ -n "$(WEB_FORMAT_ALL)" ]; then files="apps packages"; else \
		files=$$( { git diff --name-only --diff-filter=ACMR "$(or $(BASE),origin/main)" -- web; git ls-files --others --exclude-standard -- web; } \
		| grep -E '\.(ts|tsx|js|mjs|css|json|md)$$' | grep -v 'pnpm-lock' | sed 's|^web/||' | sort -u); fi; \
	if [ -z "$$files" ]; then echo "No changed web files."; else cd web && pnpm exec prettier --write --ignore-unknown $$files; fi

.PHONY: web-storybook
web-storybook: $(WEB_INSTALLED) ## Storybook dev server on :6006 (every component and state)
	$(PNPM) storybook

.PHONY: web-storybook-build
web-storybook-build: $(WEB_INSTALLED) ## Build the static Storybook (web/packages/ui/storybook-static)
	$(PNPM) --filter @northline/ui build-storybook

.PHONY: web-storybook-test
web-storybook-test: web-storybook-build ## Storybook interaction + a11y tests in headless Chromium (Playwright)
	$(PNPM) --filter @northline/ui test-storybook

.PHONY: a11y
a11y: $(WEB_INSTALLED) ## Accessibility page sweep (S-109): builds Studio, console, consumer; Playwright + axe on 32 screens (CHROMIUM=path)
	$(PNPM) --filter @northline/studio build
	$(PNPM) --filter @northline/console build
	$(PNPM) --filter @northline/consumer build
	$(PNPM) --filter @northline/a11y a11y

.PHONY: csp-check
csp-check: $(WEB_INSTALLED) ## S104-09: the consumer site's CSP in Chromium — a fresh nonce per page, none of its inline scripts without it, zero violations (checkout with a Stripe.js stand-in)
	$(PNPM) --filter @northline/consumer build
	$(PNPM) --filter @northline/a11y csp

.PHONY: a11y-record
a11y-record: $(WEB_INSTALLED) ## Re-record the sweep's mock api answers from the apps' vitest suites (packages/a11y/fixtures)
	$(PNPM) --filter @northline/a11y record

.PHONY: web-clean
web-clean:
	rm -rf $(ROOT)/web/apps/*/dist $(ROOT)/web/apps/*/.output $(ROOT)/web/apps/*/.tanstack \
		$(ROOT)/web/packages/ui/storybook-static $(ROOT)/web/packages/tokens/dist $(ROOT)/web/packages/a11y/a11y-results

.PHONY: run-studio
run-studio: $(WEB_INSTALLED) ## Studio dev server on :3100 through the studio-bff (real sign-in)
	$(PNPM) --filter @northline/studio dev

.PHONY: run-studio-dev
run-studio-dev: $(WEB_INSTALLED) ## Studio on :3100 with dev auth as DEV_USER (default Ravi Sandhu); needs only the api
	cd $(ROOT)/web && NL_DEV_USER=$(DEV_USER) VITE_NL_DEV_STEP_UP=1 pnpm --filter @northline/studio dev

.PHONY: run-consumer
run-consumer: $(WEB_INSTALLED) ## Consumer web on :3000 through the consumer-bff (SSR)
	$(PNPM) --filter @northline/consumer dev

.PHONY: run-consumer-dev
run-consumer-dev: $(WEB_INSTALLED) ## Consumer web with dev auth as CONSUMER_DEV_USER (Amara Osei); needs only the api
	cd $(ROOT)/web && NL_DEV_USER=$(CONSUMER_DEV_USER) NL_BFF_URL=http://localhost:8080 pnpm --filter @northline/consumer dev

.PHONY: run-console
run-console: $(WEB_INSTALLED) ## Platform console on :3200 through the console-bff (staff sign-in; S-90)
	$(PNPM) --filter @northline/console dev

.PHONY: run-console-dev
run-console-dev: $(WEB_INSTALLED) ## Console on :3200 with dev auth as CONSOLE_DEV_USER (Priya Natarajan, every role); needs only the api
	cd $(ROOT)/web && NL_DEV_USER=$(CONSOLE_DEV_USER) pnpm --filter @northline/console dev
