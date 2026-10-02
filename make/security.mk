# Security scans (S-104; docs/security/README.md, findings in docs/security/findings.md). Every target runs what is
# installed and skips the rest, so it works offline; `make security-tools` fetches pinned copies once.

##@ Security

.PHONY: security-tools
security-tools: ## Download pinned gitleaks, osv-scanner, kube-score and pip-install semgrep + checkov into .cache/security-tools
	@sh -c '$(ROOT)/scripts/security/tools.sh'

.PHONY: security-scan
security-scan: ## Every offline-capable scan: secrets, dependencies (SBOM + lockfiles), SAST, IaC; reports in build/security
	@$(ROOT)/scripts/security/scan.sh all

.PHONY: security-secrets
security-secrets: ## gitleaks over the whole git history (.gitleaks.toml); fails on any finding
	@$(ROOT)/scripts/security/scan.sh secrets

.PHONY: security-deps
security-deps: ## CycloneDX SBOMs of the apps + osv-scanner over them and the pnpm lockfiles (OSV_OFFLINE=1: local DB)
	@$(ROOT)/scripts/security/scan.sh deps

.PHONY: security-sast
security-sast: ## semgrep with the java, typescript, react and secrets rules (SEMGREP_RULES=<semgrep-rules checkout> offline)
	@$(ROOT)/scripts/security/scan.sh sast

.PHONY: security-iac
security-iac: ## checkov over infra/terraform and the rendered chart (staging, prod), kube-score on the chart
	@$(ROOT)/scripts/security/scan.sh iac

.PHONY: security-sbom
security-sbom: ## CycloneDX SBOM of each app (server/<app>/build/reports/cyclonedx-direct/bom.json)
	$(GRADLE) :api:cyclonedxDirectBom :auth:cyclonedxDirectBom :bff:cyclonedxDirectBom :worker:cyclonedxDirectBom

# DAST against a running api: `make up` first (local profile, dev auth). ZAP's API scan over the committed OpenAPI specs
# when Docker can run zaproxy/zap-stable, and always the scripted negative tests. Never point it at a shared environment.
API ?= http://localhost:8080
ZAP_IMAGE ?= zaproxy/zap-stable
.PHONY: security-dast
security-dast: ## Negative tests + ZAP API scan (Docker) against API (default http://localhost:8080, local profile)
	@API=$(API) $(ROOT)/scripts/security/negative-tests.sh
	@mkdir -p $(ROOT)/build/security/zap && cp $(ROOT)/docs/api/openapi/api-public.yaml $(ROOT)/build/security/zap/ \
	  && chmod 777 $(ROOT)/build/security/zap
	@if docker image inspect $(ZAP_IMAGE) >/dev/null 2>&1 || docker pull $(ZAP_IMAGE) >/dev/null 2>&1; then \
	  docker run --rm --network host -v $(ROOT)/build/security/zap:/zap/wrk:rw $(ZAP_IMAGE) zap-api-scan.py \
	    -t api-public.yaml -f openapi -O $(API) -J zap-public.json -r zap-public.html -I; \
	else echo "  skipped: ZAP ($(ZAP_IMAGE) can't be pulled) — the negative tests above ran"; fi
