# Compliance checks (S-110 PCI DSS SAQ A, S-106 legal registry) — docs/compliance/. Both are also part of the normal
# server build and `pnpm -r test`; these targets run just them, e.g. before an attestation or a release.

##@ Compliance

.PHONY: pci-scan
pci-scan: ## S-110: no card data in the schema, data, seeds, contracts, logs or clients; the request guard (Docker)
	$(GRADLE) :platform:test --tests '*CardDataTest' --tests '*RedactorTest' \
		:api:test --tests '*CardDataGuardTest' --tests '*CardDataScanTest' --tests '*CardDataRegressionTest'
	$(PNPM) --filter @northline/consumer exec vitest run src/lib/csp.test.ts

.PHONY: legal-check
legal-check: $(WEB_INSTALLED) ## S-106: every legal text matches its registered version; sign-offs cover the exact text
	$(PNPM) --filter @northline/legal test

.PHONY: legal-status
legal-status: ## S-106: every legal text's version, effective date and counsel sign-off
	cd $(ROOT)/web/packages/legal && node registry.mjs status
