# S-111 observability (docs/runbooks/observability.md): the local Collector → Prometheus, Loki, Tempo; Alertmanager →
# Mailpit; Grafana (yours or the bundled one), dashboards, alert rules. Everything goes through scripts/observability.sh,
# which also runs without make.

##@ Observability

.PHONY: obs-up obs-down obs-status obs-open obs-env obs-dashboards obs-check obs-grafana-provision obs-fire-test-alert
obs-up: ## Collector (:4318) → Prometheus :9090, Loki :3110, Tempo :3210; Alertmanager :9093 → Mailpit; Grafana :3300 (unless BYO grafana)
	@$(byo_arg) $(ROOT)/scripts/observability.sh up

obs-down: ## Stop the observability stack (no data is kept)
	@$(byo_arg) $(ROOT)/scripts/observability.sh down

obs-status: ## Is the observability stack running, does each backend and Grafana answer
	@$(byo_arg) $(ROOT)/scripts/observability.sh status

obs-open: ## The Northline overview dashboard in Grafana
	@$(byo_arg) $(ROOT)/scripts/observability.sh open

# Your Grafana on :3000 collides with the consumer web app: this moves it (scripts/grafana-move-port.sh).
.PHONY: grafana-move-port
grafana-move-port: ## Move YOUR Grafana (Homebrew or Docker) from :3000 to PORT (default 3001) and set GRAFANA_URL in .env
	@PORT="$(or $(PORT),3001)" bash $(ROOT)/scripts/grafana-move-port.sh

# Your own Grafana: GRAFANA_URL + GRAFANA_TOKEN (or GRAFANA_USER + GRAFANA_PASSWORD), GRAFANA_ALERT_RULES=1 to import
# the rules as Grafana-managed ones; GRAFANA_BACKEND_HOST=host.docker.internal when that Grafana runs in Docker.
obs-grafana-provision: ## Data sources (Prometheus, Loki, Tempo, Alertmanager), folder Northline, every dashboard into GRAFANA_URL (idempotent)
	@$(foreach v,GRAFANA_URL GRAFANA_TOKEN GRAFANA_USER GRAFANA_PASSWORD GRAFANA_ALERT_RULES GRAFANA_BACKEND_HOST,$(if $(filter command line,$(origin $(v))),$(v)="$($(v))")) \
		BYO=grafana $(ROOT)/scripts/observability.sh grafana-provision

obs-fire-test-alert: ## Failing sign-ins until a local alert fires and reaches Mailpit (SUSTAIN=12 also trips NorthlineSignInFailures)
	@$(if $(SUSTAIN),SUSTAIN=$(SUSTAIN)) $(ROOT)/scripts/observability.sh fire-test-alert

obs-env: ## The OTEL_* variables that make an app you start yourself export to the local stack
	@$(ROOT)/scripts/observability.sh env

obs-dashboards: ## Regenerate the Grafana dashboards (deploy/observability/grafana/dashboards.py)
	@$(ROOT)/scripts/observability.sh dashboards

obs-check: ## Dashboards up to date, Collector config valid (Docker), then obs-rules-check
	@$(ROOT)/scripts/observability.sh check

# S-113 alerting and on-call (docs/runbooks/alerting.md): SLOs as code (Sloth specs → burn-rate rules), rule unit tests.
.PHONY: obs-slo obs-rules-check
obs-slo: ## Regenerate the SLO burn-rate rules from deploy/observability/slo (Sloth) and the chart's copies
	@$(ROOT)/scripts/observability.sh slo

obs-rules-check: ## Offline: alerts' severity/runbooks, SLO drift, promtool check+test rules, amtool (tools or Docker)
	@$(ROOT)/scripts/observability.sh rules-check
