# Load and soak tests (S-119) — k6 with xk6-sse, scenarios in loadtest/. docs/runbooks/load-testing.md, results in
# docs/perf/results.md. TARGET=local (default: the dedicated stack of make load-stack-up) or TARGET=staging (from a pod
# in the cluster); never prod. SCENARIOS=all|search,checkout,kds,badges,…  LOAD_SCALE=1 (= launch target × 3).
#   make load-stack-up && make load-smoke && make load LOAD_SCALE=0.2 && make soak && make load-stack-down
# LOAD_LOCK=<file>: local runs wait for this flock lock (machines shared by several people or agents).

LOADTEST := $(ROOT)/loadtest
LOAD_ENV = TARGET=$(or $(TARGET),local) $(if $(SCENARIOS),SCENARIOS=$(SCENARIOS)) $(if $(LOAD_SCALE),LOAD_SCALE=$(LOAD_SCALE)) \
	$(if $(LOAD_DURATION),LOAD_DURATION=$(LOAD_DURATION)) $(if $(SOAK_DURATION),SOAK_DURATION=$(SOAK_DURATION)) \
	$(if $(STRESS_DURATION),STRESS_DURATION=$(STRESS_DURATION)) $(if $(LOAD_LOCK),LOAD_LOCK=$(LOAD_LOCK))
lock = $(if $(LOAD_LOCK),$(if $(shell command -v flock),flock $(LOAD_LOCK)))

##@ Load tests

.PHONY: load-smoke
load-smoke: ## Seconds: every journey once in a while against TARGET — do they all work?
	$(LOAD_ENV) $(LOADTEST)/run.sh smoke

.PHONY: load
load: ## The launch target (× LOAD_SCALE) for LOAD_DURATION (10m); fails when a p95/p99/error SLO threshold breaks
	$(LOAD_ENV) $(LOADTEST)/run.sh load

.PHONY: load-stress
load-stress: ## Ramp 0.5× → 5× the target over STRESS_DURATION (12m) to find the knee
	$(LOAD_ENV) $(LOADTEST)/run.sh stress

.PHONY: soak
soak: ## The load for SOAK_DURATION (2h on staging, 15m locally by default): leaks, creep, pool exhaustion
	$(LOAD_ENV) $(LOADTEST)/run.sh soak

.PHONY: load-stack-up
load-stack-up: ## Local load-test stack (own compose project + ports): Postgres, Elasticsearch, the api jar, seeded data
	$(lock) $(LOADTEST)/stack.sh up

.PHONY: load-stack-down
load-stack-down: ## Stop the load-test stack and delete its data
	$(LOADTEST)/stack.sh down

.PHONY: load-seed
load-seed: ## Re-seed the local load-test data (SEED_BUSINESSES, SEED_SHOP_UNITS, SEED_CUSTOMERS; local databases only)
	PGPORT=$(or $(LOAD_PG_PORT),55432) $(LOADTEST)/seed/seed.sh

.PHONY: load-k6
load-k6: ## Build (once) and print the k6 binary with the SSE extension the load tests use
	@$(LOADTEST)/k6.sh
