# Database — Flyway migrations (db/migrations), dev personas (db/seed-dev, local only) and categories (db/seed).
# The Gradle tasks read DB_URL / DB_USER / DB_PASSWORD from the environment, then server/.env, then
# localhost:5432/northline (docs/runbooks/local.md § 3). db-reset and db-psql talk to the compose `postgres` service when
# it runs, else to your own Postgres with psql (PGHOST/PGPORT/PGUSER, the database from DB_NAME).

DEV_SEED ?= true
DB_NAME ?= northline
DB_OWNER ?= northline
# 1 when the compose postgres service is running (evaluated per use, not at parse time)
db_in_compose = cd $(ROOT) && [ -n "$$(docker compose ps -q postgres 2>/dev/null)" ]

##@ Database

.PHONY: db-migrate
db-migrate: ## Apply db/migrations (+ db/seed-dev personas unless DEV_SEED=false) to DB_URL
	$(GRADLE) :api:flywayMigrate -Pdb.devSeed=$(DEV_SEED)

.PHONY: db-seed
db-seed: ## Upsert db/seed/categories.json into catalogue.categories (idempotent; required once)
	$(GRADLE) :api:seedCategories

.PHONY: db-info
db-info: ## Flyway migration status of DB_URL
	$(GRADLE) :api:flywayInfo

# Local only: refuses anything but a compose container or a local host, and asks first (YES=1 skips the question).
.PHONY: db-reset
db-reset: ## DROP and recreate the local database DB_NAME, then migrate + seed (asks first; YES=1 to skip)
	@case "$${PGHOST:-localhost}" in localhost|127.0.0.1|::1|/*) ;; *) echo "db-reset: PGHOST=$$PGHOST is not local — refused"; exit 1;; esac
	@if [ -z "$(YES)" ]; then read -r -p "Drop database '$(DB_NAME)' and every row in it? [y/N] " a; case "$$a" in y|Y|yes) ;; *) echo "Aborted."; exit 1;; esac; fi
	@if $(db_in_compose); then \
		echo "Resetting '$(DB_NAME)' in the compose postgres container"; \
		docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$${PG_USER:-northline}" -d postgres \
			-c 'drop database if exists "$(DB_NAME)" with (force)' -c 'create database "$(DB_NAME)" owner "$(DB_OWNER)"'; \
	else \
		echo "Resetting '$(DB_NAME)' with psql (PGHOST=$${PGHOST:-localhost}, PGUSER=$${PGUSER:-postgres}; a superuser: postgis is not a trusted extension)"; \
		PGUSER=$${PGUSER:-postgres} psql -v ON_ERROR_STOP=1 -d postgres \
			-c 'drop database if exists "$(DB_NAME)" with (force)' -c 'create database "$(DB_NAME)" owner "$(DB_OWNER)"' && \
		PGUSER=$${PGUSER:-postgres} psql -v ON_ERROR_STOP=1 -d "$(DB_NAME)" \
			-c 'create extension if not exists postgis' -c 'create extension if not exists citext' -c 'create extension if not exists pgcrypto'; \
	fi
	@$(MAKE) --no-print-directory db-migrate db-seed

.PHONY: db-psql
db-psql: ## psql shell on DB_NAME (compose container, or your own Postgres)
	@if $(db_in_compose); then docker compose exec postgres psql -U "$${PG_USER:-northline}" -d "$(DB_NAME)"; \
	else psql -d "$(DB_NAME)"; fi
