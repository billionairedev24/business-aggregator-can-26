# Northline — one entry point for every developer and operator workflow (S-124).
#
#   make setup          check the toolchain, create the .env files, pnpm install (once)
#   make up             the compose stand-ins (Postgres by default) + migrations + seed, then SERVICES in the background
#   make run            the same in the foreground with merged logs; Ctrl-C stops what it started (alias: make dev)
#   make status / logs  what runs and where / follow the app logs;  make down stops the apps and the stand-ins
#   make all            everything CI checks (server build with tests, web checks)
#
# SERVICES picks the apps (default "api studio": the Studio with dev auth, Postgres only):
#   make up SERVICES="auth api bff studio"          real sign-in through northline-auth and the studio-bff
#   make up SERVICES="auth api bff-consumer consumer"   the consumer web through its BFF
#   make up SERVICES=all PROFILES=all               every app and every stand-in (Kafka, Elasticsearch, …)
#   make up-all                                     everything incl. observability, with your own Postgres / Valkey /
#                                                   Grafana from BYO_SERVICES in .env (e.g. db,cache,grafana)
# `make help` lists every target; docs/LOCAL_DEVELOPMENT.md explains each workflow, docs/runbooks/local.md the stand-ins.
#
# Portable: GNU make 3.81 (macOS's /usr/bin/make) or later, bash 3.2+, BSD or GNU userland — no .ONESHELL,
# .SHELLFLAGS, ::= or != assignments, no GNU-only sed/find flags. Every recipe line is its own shell. The app runner is
# scripts/stack.sh; CI (.github/workflows, ci/gitlab) calls the same targets.

.DEFAULT_GOAL := help
SHELL := /bin/bash
ROOT := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
MAKEFLAGS += --no-print-directory

STACK := $(ROOT)/scripts/stack.sh
SERVICES ?= api studio
# Compose profiles for up/down; empty = COMPOSE_PROFILES in .env (db). PROFILES=none starts no stand-in (your own Postgres).
PROFILES ?=
# Spring profile the apps run with (`local` = Postgres only, dev auth, dev seed).
SPRING_PROFILE ?= local
# Seeded personas (db/seed-dev): Ravi Sandhu (owner of the three businesses), Amara Osei (consumer) and Priya Natarajan
# (Northline staff with every console role, S-90).
DEV_USER ?= 01J9ZD3V00000000000000RAV1
CONSUMER_DEV_USER ?= 01J9ZD3V0000000000000C0001
CONSOLE_DEV_USER ?= 01J9ZD3V00000000000000PNA1
# Gradle: at most two workers (the tests start Testcontainers), plus CI's optional Maven mirror (a no-op when
# MAVEN_MIRROR_URL is empty). CI adds its own, e.g. GRADLE_FLAGS='--max-workers=2 --continue --console=plain'.
GRADLE_FLAGS ?= --max-workers=2
export SPRING_PROFILE DEV_USER CONSUMER_DEV_USER CONSOLE_DEV_USER GRADLE_FLAGS DEV_AUTH
# S-111: OBS=1 also starts the OpenTelemetry Collector + Grafana LGTM (compose profile observability) and makes every app
# export traces, metrics and logs to it (docs/runbooks/observability.md). Grafana: http://localhost:3300.
OBS ?=
ifeq ($(OBS),1)
export OTEL_EXPORT_ENABLED := true
export OTEL_EXPORTER_OTLP_ENDPOINT := http://localhost:4318
export OTEL_RESOURCE_ATTRIBUTES := deployment.environment.name=local
endif

# JDK 25 for the Gradle wrapper: JAVA_HOME when it is one, else macOS's java_home, Homebrew, SDKMAN or /usr/lib/jvm.
JDK25 := $(firstword $(shell [ -n "$$JAVA_HOME" ] && "$$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "25' && echo "$$JAVA_HOME") $(shell [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 25 2>/dev/null) $(wildcard /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home /Library/Java/JavaVirtualMachines/*25*/Contents/Home $(HOME)/.sdkman/candidates/java/25* /usr/lib/jvm/java-25-openjdk-amd64 /usr/lib/jvm/java-25-openjdk-arm64 /usr/lib/jvm/java-25-openjdk /usr/lib/jvm/temurin-25-jdk-amd64 /usr/lib/jvm/temurin-25-jdk-arm64))
ifneq ($(JDK25),)
export JAVA_HOME := $(JDK25)
endif

GRADLE := cd $(ROOT)/server && ./gradlew --init-script $(ROOT)/ci/gradle/maven-mirror.init.gradle.kts $(GRADLE_FLAGS)
PNPM := cd $(ROOT)/web && pnpm
COMPOSE := cd $(ROOT) && docker compose
comma := ,
compose_profiles = $(if $(filter-out none,$(PROFILES)),--profile $(subst $(comma), --profile ,$(PROFILES)))

##@ Setup

.PHONY: setup
setup: ## Check the toolchain (JDK 25, Node 22 + pnpm; Docker, helm, terraform optional), create .env files, pnpm install
	@sh $(ROOT)/make/toolchain.sh || { printf '\nInstall the missing required tools, then run make setup again.\n'; exit 1; }
	@echo
	@$(MAKE) env web-install
	@printf '\nReady. Next: make up run   (Postgres in Docker, migrations + seed, then the api + Studio as Ravi Sandhu)\n'

.PHONY: doctor
doctor: ## Every tool and version, required and optional, without changing anything
	@sh $(ROOT)/make/toolchain.sh

.PHONY: env
env: ## Create .env, server/.env and the web apps' .env from their .env.example (never overwrites)
	@for f in .env server/.env web/apps/studio/.env web/apps/consumer/.env; do \
		if [ -f "$(ROOT)/$$f" ]; then echo "  kept    $$f"; \
		else cp "$(ROOT)/$$f.example" "$(ROOT)/$$f" && echo "  created $$f (from $$f.example)"; fi; \
	done

##@ Run (apps: api auth bff bff-consumer worker studio consumer storybook docs)

.PHONY: up
up: standins-up ## Stand-ins (PROFILES) + migrate + seed, then SERVICES in the background, waiting until each answers
	@if [ -z "$(SKIP_DB)" ]; then $(MAKE) db-migrate db-seed; fi
	@if [ "$(OBS)" = 1 ]; then $(byo_arg) $(ROOT)/scripts/observability.sh up; fi
	@$(STACK) up $(SERVICES)

# Everything, with the services you run yourself (docs/runbooks/local.md § 6a): BYO_SERVICES in .env, or BYO=… once.
byo_arg = $(if $(filter command line environment,$(origin BYO)),BYO="$(BYO)")
services_arg = $(if $(filter command line environment,$(origin SERVICES)),SERVICES="$(SERVICES)")

.PHONY: up-all
up-all: ## Every app + every stand-in you don't bring (BYO=db,cache,grafana) + observability and alerting; checks yours, migrates, prints every URL
	@$(byo_arg) $(services_arg) $(if $(filter file,$(origin SPRING_PROFILE)),SPRING_PROFILE=) MAKE="$(MAKE)" $(ROOT)/scripts/local-all.sh up

.PHONY: up-all-check
up-all-check: ## Only check your own Postgres (PostGIS, version), Valkey and Grafana, and the ports the stand-ins need
	@$(byo_arg) $(ROOT)/scripts/local-all.sh check

.PHONY: urls
urls: ## The status table of make up-all: every app, API docs, stand-in and observability URL, and whether it answers
	@$(byo_arg) $(ROOT)/scripts/local-all.sh status

.PHONY: run
run: ## SERVICES in the foreground with merged logs (stand-ins as make up leaves them); Ctrl-C stops what it started
	@if [ "$(OBS)" = 1 ]; then $(byo_arg) $(ROOT)/scripts/observability.sh up; fi
	@$(STACK) dev $(SERVICES)

.PHONY: dev
dev: run ## Alias of make run

.PHONY: down
down: ## Stop every app make started and the stand-ins (SERVICES=… stops only those apps; VOLUMES=1 deletes stand-in data)
	@$(STACK) down $(if $(filter command line,$(origin SERVICES)),$(SERVICES))
	@$(if $(filter command line,$(origin SERVICES)),,$(MAKE) standins-down)

.PHONY: restart
restart: ## Restart SERVICES
	@$(STACK) down $(SERVICES) && $(STACK) up $(SERVICES)

.PHONY: status
status: ## What runs, on which port, whether it answers, the useful URLs; then the stand-ins
	@$(STACK) status
	@$(COMPOSE) --profile all --profile tools --profile observability ps 2>/dev/null || true

.PHONY: logs
logs: ## Follow the app logs (.run/logs; SERVICES=api for one); make standins-logs for the containers
	@$(STACK) logs $(if $(filter command line,$(origin SERVICES)),$(SERVICES))

.PHONY: standins-up
standins-up: ## Only the compose stand-ins (PROFILES=db,cache,events,search,mail,storage,payments or all), healthy
	@if [ "$(PROFILES)" = none ]; then echo "PROFILES=none: no stand-ins (your own Postgres per server/.env)"; \
	else $(COMPOSE) $(compose_profiles) up -d --wait; fi

.PHONY: standins-down
standins-down: ## Stop the compose stand-ins, every profile (VOLUMES=1 also deletes their data)
	$(COMPOSE) --profile all --profile tools --profile observability down $(if $(VOLUMES),-v)

.PHONY: standins-logs
standins-logs: ## Follow the stand-ins' logs (SERVICE=postgres|kafka|… for one)
	$(COMPOSE) --profile all --profile tools --profile observability logs -f --tail=100 $(SERVICE)

.PHONY: smoke
smoke: ## Health of every app port, whoever started it (api, auth, bffs, worker, studio, consumer)
	@for s in "api http://localhost:8080/actuator/health" "auth http://localhost:9000/actuator/health" \
		"studio-bff http://localhost:8082/actuator/health" "consumer-bff http://localhost:8081/actuator/health" \
		"worker http://localhost:8084/actuator/health" "studio http://localhost:3100/" "consumer http://localhost:3000/"; do \
		set -- $$s; code=$$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$$2" || true); \
		case "$$code" in 2*|3*) printf '  \033[32mup\033[0m    %-13s %s\n' "$$1" "$$2";; \
		*) printf '  \033[2mdown\033[0m  %-13s %s (%s)\n' "$$1" "$$2" "$${code:-no answer}";; esac; \
	done

##@ All stacks

.PHONY: all
all: server-build web-check ## Everything CI checks: server build (tests included) and web checks

.PHONY: build
build: server-assemble web-build ## Build every app without running tests (boot jars, web bundles)

.PHONY: test
test: server-test web-test ## Server tests (Testcontainers) and every web package's vitest

.PHONY: lint
lint: server-lint web-lint ## Static checks: Spotless, Checkstyle, Error Prone/NullAway, hex colours, typecheck

.PHONY: format
format: server-format web-format ## Format the code (Spotless; Prettier on the web files you changed)

.PHONY: clean
clean: server-clean web-clean docs-clean courier-clean mobile-consumer-clean ## Delete build outputs and the runner's logs (keeps node_modules, ~/.gradle, Docker volumes)
	rm -rf $(ROOT)/smoke-out $(ROOT)/e2e-out $(ROOT)/.run/logs

.PHONY: clean-all
clean-all: clean ## clean + node_modules and the Gradle project cache
	rm -rf $(ROOT)/web/node_modules $(ROOT)/web/apps/*/node_modules $(ROOT)/web/packages/*/node_modules $(ROOT)/server/.gradle

##@ Help

.PHONY: help
help: ## This list, and the common variables
	@awk 'BEGIN { FS = ":[^#]*## " } \
		/^##@ / { printf "\n\033[1m%s\033[0m\n", substr($$0, 5); next } \
		/^[a-zA-Z0-9_.-]+:[^=]*## / { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\n\033[1mVariables\033[0m\n'
	@awk '/^##> / { l = substr($$0, 5); i = index(l, "  "); printf "  \033[33m%-22s\033[0m %s\n", substr(l, 1, i - 1), substr(l, i + 2) }' $(MAKEFILE_LIST)
	@echo
	@echo 'Apps: api auth bff bff-consumer worker studio consumer storybook docs — e.g. make up SERVICES="auth api bff studio"'

##> SERVICES  apps for up/run/down/restart/logs (default "api studio"; all = every app)
##> PROFILES  compose stand-ins for up, e.g. db,cache,events or all; none = your own Postgres (default: .env)
##> DEV_AUTH  studio/consumer: auto (dev auth unless their BFF runs), 1 or 0
##> SPRING_PROFILE  Spring profile(s) of the apps (default local)
##> SKIP_DB  1 = make up doesn't migrate or seed
##> OBS  1 = make up/run also start the observability stack and the apps export to it (S-111)
##> BYO  up-all / obs-*: the stand-ins you run yourself, e.g. db,cache,grafana (default: BYO_SERVICES in .env)
##> GRAFANA_URL  obs-grafana-provision / up-all: your Grafana; GRAFANA_TOKEN (service account) or GRAFANA_USER + _PASSWORD
##> PROJECT TESTS  server-build/server-test: one Gradle project (api, auth, bff, worker), a test filter
##> DB_URL DB_USER DB_PASSWORD  database for db-* and the apps (default: server/.env, then localhost:5432/northline)
##> GRADLE_FLAGS  Gradle flags (default --max-workers=2)
##> CLOUD  tf-validate: all, aws, gcp or azure; dr-restore-*: the cloud of the environment
##> DR_ENV TIME DRY_RUN  dr-restore-*: environment (default prod), restore time, DRY_RUN=1 prints the commands (S-114)
##> REGISTRY IMAGE_TAG PUSH  images-*: registry path, tag, PUSH=1 pushes
##> E2E_ARGS ENV  e2e: playwright arguments (e.g. --grep @smoke); e2e-target: the environment (dev, staging)
##> ENV MARKET RECORD  go-live-check: environment, region market id, 1 = record the repository's results on the checklist (S-118)
##> STRICT  i18n-check: 1 = known gaps (legal texts, French awaiting a translator) fail too — before a French-first launch

include $(ROOT)/make/server.mk
include $(ROOT)/make/web.mk
include $(ROOT)/make/e2e.mk
include $(ROOT)/make/compliance.mk
include $(ROOT)/make/courier.mk
include $(ROOT)/make/mobile-consumer.mk
include $(ROOT)/make/mobile-release.mk
include $(ROOT)/make/db.mk
include $(ROOT)/make/kafka.mk
include $(ROOT)/make/search.mk
include $(ROOT)/make/docs.mk
include $(ROOT)/make/deploy.mk
include $(ROOT)/make/infra.mk
include $(ROOT)/make/dr.mk
include $(ROOT)/make/observability.mk
include $(ROOT)/make/security.mk
include $(ROOT)/make/i18n.mk
include $(ROOT)/make/loadtest.mk
include $(ROOT)/make/golive.mk
