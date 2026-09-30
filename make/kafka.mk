# Kafka — the topic catalogue deploy/kafka/topics.yaml (S-25), DLQ replay (S-26). KAFKA_* from the environment or
# server/.env (default localhost:9092). docs/runbooks/events.md, infrastructure.md § 5.3.

dlq_args = --topic=$(TOPIC) --group=$(GROUP) $(if $(EVENT),--event=$(EVENT)) $(if $(FORCE),--force)
require_dlq = @if [ -z "$(TOPIC)" ] || [ -z "$(GROUP)" ]; then echo "usage: make $@ TOPIC=<topic>.dlq GROUP=<consumer group> [EVENT=<id>]"; exit 2; fi

##@ Kafka

.PHONY: kafka-topics
kafka-topics: ## Create/align every topic, .dlq and .retry-n of the catalogue (the deploy Job's provisioner; never deletes)
	$(GRADLE) :worker:kafkaTopics --args='apply'

.PHONY: kafka-topics-plan
kafka-topics-plan: ## Show what kafka-topics would change, change nothing
	$(GRADLE) :worker:kafkaTopics --args='plan'

.PHONY: kafka-topics-verify
kafka-topics-verify: ## Exit non-zero when a topic is missing or drifted
	$(GRADLE) :worker:kafkaTopics --args='verify'

.PHONY: kafka-topics-list
kafka-topics-list: ## Print every derived topic (name partitions retention.ms cleanup.policy); no Kafka needed
	cd $(ROOT) && sh scripts/topics.sh --list

.PHONY: kafka-dlq
kafka-dlq: ## Dry run: what a DLQ holds for a group (TOPIC=<topic>.dlq GROUP=<group>)
	$(require_dlq)
	$(GRADLE) :worker:dlqReplay --args='list $(dlq_args)'

.PHONY: kafka-dlq-replay
kafka-dlq-replay: ## Replay a group's DLQ records to the original topic (TOPIC=… GROUP=… [EVENT=<id>] [FORCE=1])
	$(require_dlq)
	$(GRADLE) :worker:dlqReplay --args='replay $(dlq_args)'

.PHONY: kafka-ui
kafka-ui: ## Kafka UI on http://localhost:8190 (compose profile tools; needs make up PROFILES=events)
	$(COMPOSE) --profile tools up -d kafka-ui
