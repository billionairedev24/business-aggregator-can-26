# French coverage (S-116, Loi 96 readiness; docs/runbooks/i18n.md). Node only — the TypeScript compiler comes from web/.

##@ Languages

.PHONY: i18n-check
i18n-check: $(WEB_INSTALLED) ## Every customer-facing string has fr-CA (web, mobile, server, catalogue, store, legal); STRICT=1 also fails known gaps
	cd $(ROOT) && node --test scripts/i18n/coverage.test.mjs
	cd $(ROOT) && node scripts/i18n/coverage.mjs $(if $(filter 1,$(STRICT)),--strict)
