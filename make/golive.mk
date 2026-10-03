# Go-live (S-118) — docs/runbooks/go-live.md. The checklist lives in the api (console › Go-live); these targets read it
# from a terminal, add what only the repository knows, and rehearse the whole launch on a throwaway local stack.

##@ Go-live

.PHONY: go-live-check go-live-rehearsal
go-live-check: ## S-118: a market's go-live gates (api checklist + repo checks), exit 1 while one blocks (ENV=local|dev|staging|prod MARKET=mkt-… RECORD=1)
	@ENV=$(or $(ENV),local) MARKET=$(MARKET) RECORD=$(RECORD) node $(ROOT)/scripts/go-live/check.mjs

go-live-rehearsal: ## S-118: pilot dry run, then check → record → two-person launch → rollback → launch again on a local stack (STACK_LOCK=file wraps it in flock)
	$(if $(STACK_LOCK),flock $(STACK_LOCK)) $(ROOT)/scripts/go-live/rehearsal.sh
