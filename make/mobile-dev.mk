# The native apps against the stack on this machine (redesign phase 4): docs/runbooks/mobile-local.md.
# scripts/mobile-dev.sh does the work; nothing is defaulted here (TARGET is also loadtest.mk's, with its own default).

##@ Mobile apps on your phone, simulator or emulator (against make up-all)

# Only what was set on the command line or in the environment reaches the script.
MOBILE_DEV_ENV = APP="$(or $(APP),consumer)" TARGET="$(or $(TARGET),device)" CLIENT="$(or $(CLIENT),dev)" BUILD="$(or $(BUILD),0)" \
  FIXTURES="$(or $(FIXTURES),0)" CHECK="$(or $(CHECK),1)" $(if $(MOBILE_HOST),MOBILE_HOST="$(MOBILE_HOST)") $(if $(PLATFORM),PLATFORM="$(PLATFORM)")

.PHONY: mobile-dev
mobile-dev: $(MOBILE_INSTALLED) ## Metro for APP=consumer|courier, API URLs on this machine's LAN IP (TARGET=device|ios|android, CLIENT=dev|go, BUILD=1, MOBILE_HOST=…)
	@$(MOBILE_DEV_ENV) $(ROOT)/scripts/mobile-dev.sh

.PHONY: mobile-dev-build
mobile-dev-build: $(MOBILE_INSTALLED) ## Build + install the development build (expo run:ios / run:android), then Metro: APP=…, TARGET=ios|android|device (PLATFORM=android for a USB phone)
	@$(MOBILE_DEV_ENV) BUILD=1 $(ROOT)/scripts/mobile-dev.sh

.PHONY: mobile-dev-check
mobile-dev-check: ## Only check that the api, auth and the consumer site answer on the address the phone will use (APP, TARGET, MOBILE_HOST)
	@$(MOBILE_DEV_ENV) MOBILE_DEV_CHECK_ONLY=1 $(ROOT)/scripts/mobile-dev.sh
