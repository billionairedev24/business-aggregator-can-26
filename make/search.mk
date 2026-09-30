# Elasticsearch — listings_en / listings_fr indices and synonym sets from deploy/search (S-42), the full reindex
# (S-71). ES_* (and DB_*/KAFKA_* for the reindex) from the environment or server/.env. docs/runbooks/search.md.

##@ Search

.PHONY: search-indices
search-indices: ## Create/update the synonym sets and indices (the deploy Job's bootstrap; never reindexes)
	$(GRADLE) :worker:searchIndices --args='apply'

.PHONY: search-indices-plan
search-indices-plan: ## Show what search-indices would change, change nothing
	$(GRADLE) :worker:searchIndices --args='plan'

.PHONY: search-indices-verify
search-indices-verify: ## Exit 3 when an index is missing, changed or needs a reindex
	$(GRADLE) :worker:searchIndices --args='verify'

.PHONY: search-reindex
search-reindex: ## Rebuild both indices from Postgres and swap the aliases (S-71; KEEP_OLD=1 keeps the old indices)
	$(GRADLE) :worker:searchReindex $(if $(KEEP_OLD),--args='--keep-old')

.PHONY: kibana
kibana: ## Kibana on http://localhost:5601 (compose profile tools; needs make up PROFILES=search)
	$(COMPOSE) --profile tools up -d kibana
