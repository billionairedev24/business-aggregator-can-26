# Northline — one entry point for every developer and operator workflow (S-124). `make` or `make help` lists the
# targets; the per-area targets live in make/*.mk. From a fresh clone:
#
#   make setup up run        # toolchain check + .env files + pnpm install · Postgres + migrations + seed · api + Studio
#
# Portable: GNU make 3.81 (macOS's /usr/bin/make) or later, bash 3.2+, BSD or GNU userland. No .ONESHELL, no GNU-only
# sed/find flags. Every recipe line is its own shell, so each one starts with `cd …` where it needs a directory.
# Variables are set on the command line (`make server-test PROJECT=api TESTS='*MerchantApiTest'`) or in the
# environment; `make help` lists the common ones. CI (.github/workflows, ci/gitlab) calls the same targets.

.DEFAULT_GOAL := help
SHELL := /bin/bash
ROOT := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

# ---- Shared settings ----------------------------------------------------------------------------------------------
# JDK 25 for the Gradle wrapper: JAVA_HOME if set, else macOS's java_home, else a JDK 25 under /usr/lib/jvm.
ifeq ($(strip $(JAVA_HOME)),)
JAVA_HOME := $(shell /usr/libexec/java_home -v 25 2>/dev/null || ls -d /usr/lib/jvm/java-25-* /usr/lib/jvm/temurin-25* /usr/lib/jvm/jdk-25* 2>/dev/null | head -1)
endif
ifneq ($(strip $(JAVA_HOME)),)
export JAVA_HOME
endif

# Gradle: at most two workers (the tests start Testcontainers), plus CI's optional Maven mirror (a no-op when
# MAVEN_MIRROR_URL is empty). GRADLE_FLAGS adds flags, e.g. GRADLE_FLAGS='--max-workers=2 --continue --console=plain'.
GRADLE_FLAGS ?= --max-workers=2
GRADLE := cd $(ROOT)/server && ./gradlew --init-script $(ROOT)/ci/gradle/maven-mirror.init.gradle.kts $(GRADLE_FLAGS)
PNPM := cd $(ROOT)/web && pnpm
COMPOSE := cd $(ROOT) && docker compose

# Spring profile the apps run with (`local` = Postgres only, dev auth, dev seed).
SPRING_PROFILE ?= local
# Seeded personas (db/seed-dev): Ravi Sandhu (owner of the three businesses) and Amara Osei (consumer).
DEV_USER ?= 01J9ZD3V00000000000000RAV1
CONSUMER_DEV_USER ?= 01J9ZD3V0000000000000C0001

include $(ROOT)/make/server.mk
include $(ROOT)/make/web.mk
include $(ROOT)/make/db.mk
include $(ROOT)/make/kafka.mk
include $(ROOT)/make/search.mk
include $(ROOT)/make/docs.mk
include $(ROOT)/make/deploy.mk
include $(ROOT)/make/infra.mk

.PHONY: help setup doctor env up down ps logs run run-signin run-consumer-stack all build test lint format e2e smoke clean clean-all

##@ Getting started

help: ## List every target and the common variables
	@printf 'Usage: make <target> [VAR=value …]   (docs/runbooks/local.md)\n'
	@awk 'BEGIN { FS = ":[^#]*## " } \
		/^##@ / { printf "\n\033[1m%s\033[0m\n", substr($$0, 5); next } \
		/^[a-zA-Z0-9_.-]+:[^=]*## / { printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\n\033[1mVariables\033[0m\n'
	@awk '/^##> / { l = substr($$0, 5); i = index(l, "  "); printf "  \033[33m%-24s\033[0m %s\n", substr(l, 1, i - 1), substr(l, i + 2) }' $(MAKEFILE_LIST)

##> SPRING_PROFILE  Spring profile(s) for run-* (default local)
##> PROFILES  compose profiles for up/down, e.g. db,cache,events or all (default: COMPOSE_PROFILES in .env)
##> PROJECT  Gradle project for server-build/server-test: api, auth, bff, worker (default: all)
##> TESTS  test filter for server-test, e.g. '*MerchantApiTest'
##> DEV_SEED  db-migrate applies db/seed-dev personas (default true)
##> DB_URL DB_USER DB_PASSWORD  database for db-* and the apps (default: server/.env, then localhost:5432/northline)
##> GRADLE_FLAGS  extra Gradle flags (default --max-workers=2)
##> CLOUD  tf-validate: all, aws, gcp or azure
##> REGISTRY IMAGE_TAG PUSH  images-*: registry path, tag, PUSH=1 pushes

setup: ## Check the toolchain (JDK 25, Node 22 + pnpm; Docker, helm, terraform optional), create .env files, pnpm install
	@sh $(ROOT)/make/toolchain.sh || { printf '\nInstall the missing required tools, then run make setup again.\n'; exit 1; }
	@echo
	@$(MAKE) --no-print-directory env web-install
	@printf '\nNext: make up run   (Postgres in Docker, migrations + seed, then api + Studio as Ravi Sandhu)\n'

doctor: ## Report every tool and version (required and optional) without changing anything
	@sh $(ROOT)/make/toolchain.sh

env: ## Create .env, server/.env and the web apps' .env from their .env.example (never overwrites)
	@for f in .env server/.env web/apps/studio/.env web/apps/consumer/.env; do \
		if [ -f "$(ROOT)/$$f" ]; then echo "  kept    $$f"; \
		else cp "$(ROOT)/$$f.example" "$(ROOT)/$$f" && echo "  created $$f (from $$f.example)"; fi; \
	done

up: ## Start the compose stand-ins (PROFILES=…, default .env), wait until healthy, then migrate + seed the database
	@$(COMPOSE) $(if $(PROFILES),--profile $(subst $(comma), --profile ,$(PROFILES))) up -d --wait
	@if [ -z "$(SKIP_DB)" ]; then $(MAKE) --no-print-directory db-migrate db-seed; fi
	@printf '\nStand-ins are up (make ps). Next: make run\n'

down: ## Stop the compose stand-ins (all profiles); VOLUMES=1 also deletes their data
	$(COMPOSE) --profile all --profile tools down $(if $(VOLUMES),-v)

ps: ## Show the compose stand-ins and their health
	$(COMPOSE) --profile all --profile tools ps

logs: ## Follow the compose logs (SERVICE=postgres|kafka|… for one)
	$(COMPOSE) --profile all --profile tools logs -f --tail=100 $(SERVICE)

run: server-classes ## Run the api + the Studio with dev auth as Ravi Sandhu (http://localhost:3100); Ctrl-C stops both
	@$(MAKE) --no-print-directory -j2 run-api run-studio-dev

run-signin: server-classes ## Run auth + api + studio-bff + Studio with real sign-in (docs/runbooks/local.md § 5)
	@$(MAKE) --no-print-directory -j4 run-auth run-api run-bff run-studio

run-consumer-stack: server-classes ## Run auth + api + consumer-bff + consumer web (http://localhost:3000)
	@$(MAKE) --no-print-directory -j4 run-auth run-api run-bff-consumer run-consumer

##@ All stacks

all: server-build web-check ## Everything CI checks: server build (tests included) and web checks

build: server-assemble web-build ## Build every app without running tests (boot jars, web bundles)

test: server-test web-test ## Run the server and web unit/integration tests

lint: server-lint web-lint ## Static checks: Spotless, Checkstyle, Error Prone/NullAway, hex colours, typecheck

format: server-format web-format ## Format the code (Spotless; Prettier on the web files you changed)

e2e: ## Studio smoke sweep: migrate + seed a DISPOSABLE database, start api/auth/studio, check 135 screens (ci/studio-smoke.sh)
	cd $(ROOT) && ci/studio-smoke.sh

smoke: ## Quick health check of whatever runs locally (api, auth, bffs, worker, web apps)
	@for s in "api http://localhost:8080/actuator/health" "auth http://localhost:9000/actuator/health" \
		"studio-bff http://localhost:8082/actuator/health" "consumer-bff http://localhost:8081/actuator/health" \
		"worker http://localhost:8084/actuator/health" "studio http://localhost:3100/" "consumer http://localhost:3000/"; do \
		set -- $$s; code=$$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$$2" || true); \
		case "$$code" in 2*|3*) printf '  \033[32mup\033[0m    %-13s %s\n' "$$1" "$$2";; \
		*) printf '  \033[2mdown\033[0m  %-13s %s (%s)\n' "$$1" "$$2" "$${code:-no answer}";; esac; \
	done

clean: server-clean web-clean docs-clean ## Delete build outputs (Gradle build/, web dist/, smoke-out/)
	rm -rf $(ROOT)/smoke-out

clean-all: clean ## clean + node_modules and the Gradle project cache (not your ~/.gradle or Docker volumes)
	rm -rf $(ROOT)/web/node_modules $(ROOT)/web/apps/*/node_modules $(ROOT)/web/packages/*/node_modules $(ROOT)/server/.gradle

comma := ,
