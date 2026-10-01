# server/ — Java 25, Gradle (api, auth, bff, worker). docs/BACKEND_CONVENTIONS.md § 1.
# PROJECT=api|auth|bff|worker narrows build/test to one app; TASKS replaces the Gradle tasks entirely (CI passes its
# own, e.g. TASKS=':api:build -x test --rerun-tasks').

server_prefix = $(if $(PROJECT),:$(PROJECT):)

##@ Server (server/, Gradle)

.PHONY: server-build
server-build: ## Full build: compile, Error Prone/NullAway, Checkstyle, Spotless check, tests, boot jars (PROJECT=…)
	$(GRADLE) $(or $(TASKS),$(server_prefix)build)

.PHONY: server-assemble
server-assemble: ## Compile and package the boot jars, no tests
	$(GRADLE) $(server_prefix)assemble

.PHONY: server-test
server-test: ## Run the tests (Docker for Testcontainers); PROJECT=api TESTS='*MerchantApiTest' for one class
	$(GRADLE) $(server_prefix)test $(if $(TESTS),--tests '$(TESTS)')

.PHONY: server-lint
server-lint: ## Static checks only: Spotless check, Checkstyle, Error Prone + NullAway (compiles main and test)
	$(GRADLE) $(server_prefix)spotlessCheck $(server_prefix)checkstyleMain $(server_prefix)checkstyleTest $(server_prefix)compileTestJava

.PHONY: server-format
server-format: ## Format the Java sources (Spotless / palantir-java-format)
	$(GRADLE) spotlessApply

.PHONY: server-events
server-events: ## Event schema contract check against BASE (default origin/main; S-34, docs/runbooks/events.md § 7)
	$(GRADLE) :event-contracts:eventSchemas -PeventSchemas.base="$(or $(BASE),origin/main)" $(if $(REQUIRE_BASE),-PeventSchemas.requireBase=true)

.PHONY: server-clean
server-clean:
	$(GRADLE) clean -q

.PHONY: run-api
run-api: ## Run the api on :8080 (SPRING_PROFILE, default local: Postgres only, dev auth via X-Dev-User)
	$(GRADLE) :api:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE)'

.PHONY: run-auth
run-auth: ## Run northline-auth on :9000 (local: migrates + seeds, SMS codes in the log)
	$(GRADLE) :auth:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE)'

.PHONY: run-bff
run-bff: ## Run the studio-bff on :8082 (local: in-memory sessions)
	$(GRADLE) :bff:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE)'

.PHONY: run-bff-consumer
run-bff-consumer: ## Run the consumer-bff on :8081 (profiles SPRING_PROFILE + consumer; guests allowed)
	$(GRADLE) :bff:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE),consumer'

.PHONY: run-bff-console
run-bff-console: ## Run the console-bff on :8083 (profiles SPRING_PROFILE + console; staff with a second factor only)
	$(GRADLE) :bff:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE),console'

.PHONY: run-worker
run-worker: ## Run the worker on :8084 (needs Postgres, Kafka, Elasticsearch: make up PROFILES=db,events,search)
	$(GRADLE) :worker:bootRun $(if $(filter-out local,$(SPRING_PROFILE)),--args='--spring.profiles.active=$(SPRING_PROFILE)')

.PHONY: auth-clients
auth-clients: ## Compare the OAuth clients in configuration and database (ARGS=list|sync; S-122)
	$(GRADLE) :auth:oauthClients --args='$(or $(ARGS),list)'

.PHONY: auth-keys
auth-keys: ## Local token signing keys (ARGS=status|rotate|…; docs/runbooks/key-rotation.md)
	$(GRADLE) :auth:signingKeys --args='$(or $(ARGS),status)'
