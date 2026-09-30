# server/ — Java 25, Gradle (api, auth, bff, worker). docs/BACKEND_CONVENTIONS.md § 1.
# PROJECT=api|auth|bff|worker narrows build/test to one app; TASKS replaces the Gradle tasks entirely (CI passes its
# own, e.g. TASKS=':api:build -x test --rerun-tasks').

.PHONY: server-build server-assemble server-classes server-test server-lint server-format server-events server-clean \
	run-api run-auth run-bff run-bff-consumer run-worker auth-clients auth-keys

server_prefix = $(if $(PROJECT),:$(PROJECT):)

##@ Server (server/, Gradle)

server-build: ## Full build: compile, Error Prone/NullAway, Checkstyle, Spotless check, tests, boot jars (PROJECT=…)
	$(GRADLE) $(or $(TASKS),$(server_prefix)build)

server-assemble: ## Compile and package the boot jars, no tests
	$(GRADLE) $(server_prefix)assemble

server-classes:
	$(GRADLE) :api:classes :auth:classes :bff:classes :worker:classes -q

server-test: ## Run the tests (Docker for Testcontainers); PROJECT=api TESTS='*MerchantApiTest' for one class
	$(GRADLE) $(server_prefix)test $(if $(TESTS),--tests '$(TESTS)')

server-lint: ## Static checks only: Spotless check, Checkstyle, Error Prone + NullAway (compiles main and test)
	$(GRADLE) $(server_prefix)spotlessCheck $(server_prefix)checkstyleMain $(server_prefix)checkstyleTest $(server_prefix)compileTestJava

server-format: ## Format the Java sources (Spotless / palantir-java-format)
	$(GRADLE) spotlessApply

server-events: ## Event schema contract check against BASE (default origin/main; S-34, docs/runbooks/events.md § 7)
	$(GRADLE) :event-contracts:eventSchemas -PeventSchemas.base="$(or $(BASE),origin/main)" $(if $(REQUIRE_BASE),-PeventSchemas.requireBase=true)

server-clean:
	$(GRADLE) clean -q

run-api: ## Run the api on :8080 (SPRING_PROFILE, default local: Postgres only, dev auth via X-Dev-User)
	$(GRADLE) :api:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE)'

run-auth: ## Run northline-auth on :9000 (local: migrates + seeds, SMS codes in the log)
	$(GRADLE) :auth:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE)'

run-bff: ## Run the studio-bff on :8082 (local: in-memory sessions)
	$(GRADLE) :bff:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE)'

run-bff-consumer: ## Run the consumer-bff on :8081 (profiles SPRING_PROFILE + consumer; guests allowed)
	$(GRADLE) :bff:bootRun --args='--spring.profiles.active=$(SPRING_PROFILE),consumer'

run-worker: ## Run the worker on :8084 (needs Postgres, Kafka, Elasticsearch: make up PROFILES=db,events,search)
	$(GRADLE) :worker:bootRun $(if $(filter-out local,$(SPRING_PROFILE)),--args='--spring.profiles.active=$(SPRING_PROFILE)')

auth-clients: ## Compare the OAuth clients in configuration and database (ARGS=list|sync; S-122)
	$(GRADLE) :auth:oauthClients --args='$(or $(ARGS),list)'

auth-keys: ## Local token signing keys (ARGS=status|rotate|…; docs/runbooks/key-rotation.md)
	$(GRADLE) :auth:signingKeys --args='$(or $(ARGS),status)'
