# Backups and disaster recovery (S-114) — scripts/dr, db/dr. docs/runbooks/backups-dr.md. The cloud targets drive the
# aws / gcloud / az CLIs and have never run against a real account: DRY_RUN=1 prints the commands.
#   make dr-restore-pitr CLOUD=aws DR_ENV=prod TIME=2026-10-02T13:05:00Z
#   make dr-prod-to-staging SOURCE_URL=postgresql://… TARGET_URL=postgresql://…

DR_ENV ?= prod
DR_ROWS ?= 200000
DR_FLAGS = --cloud $(CLOUD) --env $(DR_ENV) $(if $(INSTANCE),--instance $(INSTANCE)) $(if $(NAME),--name $(NAME)) $(if $(DRY_RUN),--dry-run)

##@ Backups and disaster recovery

.PHONY: dr-drill
dr-drill: ## Local DR drill in throwaway containers: base backup + WAL, PITR, dump restore, prod→staging masking, timings (DR_FROM_URL=copy a database instead of Flyway)
	$(ROOT)/scripts/dr/drill-local.sh --rows $(DR_ROWS) $(if $(DR_FROM_URL),--from-url $(DR_FROM_URL)) $(if $(KEEP),--keep)

.PHONY: dr-restore-pitr
dr-restore-pitr: ## Restore CLOUD's DR_ENV database to TIME (RFC 3339 or latest) into a NEW instance (DRY_RUN=1 prints)
	@[ -n "$(TIME)" ] || { echo "TIME=2026-10-02T13:05:00Z (UTC) or TIME=latest"; exit 2; }
	$(ROOT)/scripts/dr/restore.sh pitr $(DR_FLAGS) --time $(TIME)

.PHONY: dr-restore-snapshot
dr-restore-snapshot: ## Restore CLOUD's DR_ENV database from a backup (BACKUP=id, default latest) into a NEW instance
	$(ROOT)/scripts/dr/restore.sh snapshot $(DR_FLAGS) --backup $(or $(BACKUP),latest)

.PHONY: dr-restore-regional
dr-restore-regional: ## Regional disaster: recover prod's database in the other Canadian region (runbook first)
	$(ROOT)/scripts/dr/restore.sh regional $(DR_FLAGS) $(if $(TIME),--time $(TIME))

.PHONY: dr-point
dr-point: ## Point DR_ENV at a restored database: configEnv.DB_URL in deploy/argocd/envs/DR_ENV/values.yaml (URL=jdbc:…)
	$(ROOT)/scripts/dr/restore.sh point --env $(DR_ENV) --url '$(URL)'

.PHONY: dr-prod-to-staging
dr-prod-to-staging: ## Mask a restored prod copy (SOURCE_URL) and replace staging (TARGET_URL) with it, verified
	$(ROOT)/scripts/dr/prod-to-staging.sh --source-url '$(SOURCE_URL)' --target-url '$(TARGET_URL)' $(if $(OWNER_ROLE),--owner-role $(OWNER_ROLE))

.PHONY: dr-mask-check
dr-mask-check: ## Fail if URL (postgresql://…) holds personal data or prod credentials (db/dr/mask-check.sql)
	$(ROOT)/scripts/dr/mask.sh --url '$(URL)' --check-only

.PHONY: dr-verify
dr-verify: ## Compare two databases: Flyway version, row counts, checksums (SOURCE_URL, TARGET_URL; AS_OF=time, ONLY=flyway,asof)
	$(ROOT)/scripts/dr/verify.sh check --source-url '$(SOURCE_URL)' --target-url '$(TARGET_URL)' $(if $(AS_OF),--as-of $(AS_OF)) $(if $(ONLY),--only $(ONLY))
