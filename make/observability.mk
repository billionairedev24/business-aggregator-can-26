# S-111 observability (docs/runbooks/observability.md): the local Collector + Grafana LGTM, dashboards, alert rules.
# Everything goes through scripts/observability.sh, which also runs without make.

##@ Observability

.PHONY: obs-up obs-down obs-status obs-open obs-env obs-dashboards obs-check
obs-up: ## OpenTelemetry Collector (:4317/:4318) + Grafana LGTM (:3300); then make up OBS=1 (or eval "$$(make -s obs-env)")
	@$(ROOT)/scripts/observability.sh up

obs-down: ## Stop the observability stack (no data is kept)
	@$(ROOT)/scripts/observability.sh down

obs-status: ## Is the observability stack running, does Grafana answer
	@$(ROOT)/scripts/observability.sh status

obs-open: ## The Northline overview dashboard in Grafana
	@$(ROOT)/scripts/observability.sh open

obs-env: ## The OTEL_* variables that make an app you start yourself export to the local stack
	@$(ROOT)/scripts/observability.sh env

obs-dashboards: ## Regenerate the Grafana dashboards (deploy/observability/grafana/dashboards.py)
	@$(ROOT)/scripts/observability.sh dashboards

obs-check: ## Dashboards up to date, Collector config valid, alert rules' unit tests (Docker)
	@$(ROOT)/scripts/observability.sh check
