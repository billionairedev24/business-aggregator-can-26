# The store release of both native apps (S-103): EAS Build / Submit / Update / Metadata, fastlane for the Play
# rollout. docs/runbooks/mobile-release.md. Targets marked (offline) need no Expo, Apple or Google account; the others
# need `eas login` or EXPO_TOKEN (and, for fastlane, the store keys — see the runbook).
# Uses MOBILE_PNPM, MOBILE_INSTALLED, EAS_PROFILE, EAS_PLATFORM and EAS_FLAGS from courier.mk.

# consumer | courier
MOBILE_APP ?= consumer
MOBILE_APP_DIR = $(ROOT)/mobile/apps/$(MOBILE_APP)
# X.Y.Z for mobile-version-set
MOBILE_VERSION ?=
# eas submit profile: internal (TestFlight + Play internal) | production (Play production draft)
SUBMIT_PROFILE ?= internal
# EAS Update: the channel (= the build profile and EAS environment: development | preview | production), a message,
# an optional staged percentage, and the update group to republish for a rollback
UPDATE_CHANNEL ?= preview
UPDATE_MESSAGE ?=
UPDATE_ROLLOUT ?=
UPDATE_GROUP ?=
RELEASE_CLI = cd $(ROOT)/mobile && node packages/release/src/cli.mjs
EAS_CLI = npx --yes eas-cli@latest
# $(call profile_env,<profile>): that build profile's env as VAR=value words, for commands that must see what EAS Build sees
profile_env = $$(cd $(ROOT)/mobile && node packages/release/src/cli.mjs env $(MOBILE_APP) $(1))

##@ Mobile releases (MOBILE_APP=consumer|courier; docs/runbooks/mobile-release.md)

.PHONY: mobile-release-check
mobile-release-check: ## (offline) Both apps' release set-up: eas.json, listings en/fr-CA and limits, privacy answers, screenshots, versions (also in pnpm lint)
	$(RELEASE_CLI) check

.PHONY: mobile-release-check-strict
mobile-release-check-strict: ## (offline) The same, also failing on what is pending before a store release (store ids, screenshots, account deletion)
	$(RELEASE_CLI) check --strict

.PHONY: mobile-release-test
mobile-release-test: $(MOBILE_INSTALLED) ## (offline) Unit tests of the release checks
	$(MOBILE_PNPM) --filter @northline/mobile-release test

.PHONY: mobile-version
mobile-version: ## (offline) Both apps' marketing versions (package.json — the one source) and their release tags
	@for a in consumer courier; do v=$$(cd $(ROOT)/mobile && node packages/release/src/cli.mjs version $$a); echo "$$a $$v (release tag $$a-v$$v)"; done

.PHONY: mobile-version-set
mobile-version-set: ## (offline) Set MOBILE_APP's version: make mobile-version-set MOBILE_APP=courier MOBILE_VERSION=1.0.0
	@test -n "$(MOBILE_VERSION)" || { echo "MOBILE_VERSION=X.Y.Z is required"; exit 1; }
	$(RELEASE_CLI) set-version $(MOBILE_APP) $(MOBILE_VERSION)

.PHONY: mobile-release-config
mobile-release-config: $(MOBILE_INSTALLED) ## (offline) The app config MOBILE_APP's EAS_PROFILE resolves to (expo config --type public)
	cd $(MOBILE_APP_DIR) && env $(call profile_env,$(EAS_PROFILE)) npx expo config --type public

.PHONY: mobile-fingerprint
mobile-fingerprint: $(MOBILE_INSTALLED) ## (offline) MOBILE_APP's runtime version for EAS_PROFILE: updates reach only binaries with the same
	@cd $(MOBILE_APP_DIR) && env $(call profile_env,$(EAS_PROFILE)) node node_modules/expo/bin/fingerprint fingerprint:generate \
	  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).hash))'

.PHONY: mobile-store-placeholders
mobile-store-placeholders: ## (offline) Rewrite the placeholder screenshots and Play feature graphics from store/screenshots.json
	$(RELEASE_CLI) placeholders

.PHONY: mobile-eas-build
mobile-eas-build: $(MOBILE_INSTALLED) ## EAS Build of MOBILE_APP (EAS_PROFILE, EAS_PLATFORM); production also submits to TestFlight + Play internal
	cd $(MOBILE_APP_DIR) && $(EAS_CLI) build --profile $(EAS_PROFILE) --platform $(EAS_PLATFORM) --non-interactive \
	  $(if $(filter production,$(EAS_PROFILE)),--auto-submit-with-profile internal,) $(EAS_FLAGS)

.PHONY: mobile-eas-submit
mobile-eas-submit: $(MOBILE_INSTALLED) ## EAS Submit of MOBILE_APP's latest build (SUBMIT_PROFILE=internal|production, EAS_PLATFORM)
	cd $(MOBILE_APP_DIR) && $(EAS_CLI) submit --profile $(SUBMIT_PROFILE) --platform $(EAS_PLATFORM) --latest --non-interactive

.PHONY: mobile-store-metadata
mobile-store-metadata: $(MOBILE_INSTALLED) ## EAS Metadata: push MOBILE_APP's App Store listing (store/store.config.json, en-CA + fr-CA)
	$(RELEASE_CLI) check --app $(MOBILE_APP)
	cd $(MOBILE_APP_DIR) && $(EAS_CLI) metadata:push --profile production --non-interactive

.PHONY: mobile-update
mobile-update: $(MOBILE_INSTALLED) ## EAS Update of MOBILE_APP to UPDATE_CHANNEL with that profile's env (UPDATE_MESSAGE; UPDATE_ROLLOUT=10 = staged)
	@test -n "$(UPDATE_MESSAGE)" || { echo "UPDATE_MESSAGE is required"; exit 1; }
	cd $(MOBILE_APP_DIR) && env $(call profile_env,$(UPDATE_CHANNEL)) $(EAS_CLI) update --channel $(UPDATE_CHANNEL) \
	  --environment $(UPDATE_CHANNEL) --message "$(UPDATE_MESSAGE)" --non-interactive $(if $(UPDATE_ROLLOUT),--rollout-percentage $(UPDATE_ROLLOUT),)

.PHONY: mobile-update-republish
mobile-update-republish: $(MOBILE_INSTALLED) ## Roll an update back: republish a known good UPDATE_GROUP on its branch (eas update:list finds it)
	@test -n "$(UPDATE_GROUP)" || { echo "UPDATE_GROUP is required (eas update:list --branch $(UPDATE_CHANNEL))"; exit 1; }
	cd $(MOBILE_APP_DIR) && $(EAS_CLI) update:republish --group $(UPDATE_GROUP) --message "Rollback to $(UPDATE_GROUP)" --non-interactive

.PHONY: mobile-update-rollback-embedded
mobile-update-rollback-embedded: $(MOBILE_INSTALLED) ## Roll UPDATE_CHANNEL back to the JS inside the binaries (no update at all)
	cd $(MOBILE_APP_DIR) && $(EAS_CLI) update:roll-back-to-embedded --channel $(UPDATE_CHANNEL) --message "Back to the embedded bundle" --non-interactive
