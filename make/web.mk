# web/ — pnpm monorepo (tokens, ui + Storybook, auth-kit, client, studio, consumer). docs/runbooks/ci.md.
# Every target installs the dependencies first when web/pnpm-lock.yaml is newer than web/node_modules.

.PHONY: web-install web-check web-build web-build-studio web-build-consumer web-test web-typecheck web-lint \
	web-format web-storybook web-storybook-build web-storybook-test web-clean run-studio run-studio-dev run-consumer \
	run-consumer-dev

WEB_INSTALLED := $(ROOT)/web/node_modules/.modules.yaml

$(WEB_INSTALLED): $(ROOT)/web/pnpm-lock.yaml $(ROOT)/web/package.json
	$(PNPM) install --frozen-lockfile
	@touch $@

##@ Web (web/, pnpm)

web-install: $(WEB_INSTALLED) ## pnpm install (frozen lockfile); a no-op while node_modules is up to date

web-check: web-lint web-test web-build-studio ## What CI's web checks run: hex colours, typecheck, vitest, Studio build

web-build: $(WEB_INSTALLED) ## Build every web package that has a build script (tokens, studio, consumer, …)
	$(PNPM) -r build

web-build-studio: $(WEB_INSTALLED) ## Build the Studio SPA (web/apps/studio/dist)
	$(PNPM) --filter @northline/studio build

web-build-consumer: $(WEB_INSTALLED) ## Build the consumer app (TanStack Start, SSR)
	$(PNPM) --filter @northline/consumer build

web-test: $(WEB_INSTALLED) ## vitest in every package
	$(PNPM) -r test

web-typecheck: $(WEB_INSTALLED) ## tsc in every package
	$(PNPM) -r typecheck

web-lint: $(WEB_INSTALLED) ## No hex colours in components (lint:colors) + typecheck
	$(PNPM) lint:colors
	$(PNPM) -r typecheck

# Prettier formats only the web files you changed (against BASE, default origin/main, plus uncommitted ones): most of
# web/ predates a Prettier config, so formatting everything would bury real changes. WEB_FORMAT_ALL=1 does all of it.
web-format: $(WEB_INSTALLED) ## Prettier on the web files changed since BASE (default origin/main); WEB_FORMAT_ALL=1 for all
	@cd $(ROOT) && if [ -n "$(WEB_FORMAT_ALL)" ]; then files="apps packages"; else \
		files=$$( { git diff --name-only --diff-filter=ACMR "$(or $(BASE),origin/main)" -- web; git ls-files --others --exclude-standard -- web; } \
		| grep -E '\.(ts|tsx|js|mjs|css|json|md)$$' | grep -v 'pnpm-lock' | sed 's|^web/||' | sort -u); fi; \
	if [ -z "$$files" ]; then echo "No changed web files."; else cd web && pnpm exec prettier --write --ignore-unknown $$files; fi

web-storybook: $(WEB_INSTALLED) ## Storybook dev server on :6006 (every component and state)
	$(PNPM) storybook

web-storybook-build: $(WEB_INSTALLED) ## Build the static Storybook (web/packages/ui/storybook-static)
	$(PNPM) --filter @northline/ui build-storybook

web-storybook-test: web-storybook-build ## Storybook interaction + a11y tests in headless Chromium (Playwright)
	$(PNPM) --filter @northline/ui test-storybook

web-clean:
	rm -rf $(ROOT)/web/apps/*/dist $(ROOT)/web/apps/*/.output $(ROOT)/web/apps/*/.tanstack \
		$(ROOT)/web/packages/ui/storybook-static $(ROOT)/web/packages/tokens/dist

run-studio: $(WEB_INSTALLED) ## Studio dev server on :3100 through the studio-bff (real sign-in)
	$(PNPM) --filter @northline/studio dev

run-studio-dev: $(WEB_INSTALLED) ## Studio on :3100 with dev auth as DEV_USER (default Ravi Sandhu); needs only the api
	cd $(ROOT)/web && NL_DEV_USER=$(DEV_USER) VITE_NL_DEV_STEP_UP=1 pnpm --filter @northline/studio dev

run-consumer: $(WEB_INSTALLED) ## Consumer web on :3000 through the consumer-bff (SSR)
	$(PNPM) --filter @northline/consumer dev

run-consumer-dev: $(WEB_INSTALLED) ## Consumer web with dev auth as CONSUMER_DEV_USER (Amara Osei); needs only the api
	cd $(ROOT)/web && NL_DEV_USER=$(CONSUMER_DEV_USER) NL_BFF_URL=http://localhost:8080 pnpm --filter @northline/consumer dev
