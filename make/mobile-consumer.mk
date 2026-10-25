# mobile/apps/consumer — the Northline consumer app (S-97, Expo + React Native), on @northline/mobile-kit.
# docs/runbooks/mobile.md. Shares mobile/'s install with the courier app (MOBILE_PNPM, MOBILE_INSTALLED: courier.mk).
# Nothing here builds a native binary: that needs Xcode / the Android SDK or EAS Build (mobile-consumer-eas-build).

CONSUMER_APP_PNPM := $(MOBILE_PNPM) --filter @northline/consumer-app

##@ Consumer app (mobile/apps/consumer, Expo)

.PHONY: mobile-consumer-install
mobile-consumer-install: $(MOBILE_INSTALLED) ## pnpm install of mobile/ (frozen lockfile); a no-op while node_modules is up to date

.PHONY: mobile-consumer-check
mobile-consumer-check: mobile-consumer-lint mobile-consumer-typecheck mobile-consumer-test mobile-consumer-export ## What the manual CI job runs: lint, typecheck, Jest, iOS + Android bundles

.PHONY: mobile-consumer-lint
mobile-consumer-lint: $(MOBILE_INSTALLED) ## ESLint over the apps and the kit (warnings fail)
	$(MOBILE_PNPM) lint

.PHONY: mobile-consumer-typecheck
mobile-consumer-typecheck: $(MOBILE_INSTALLED) ## tsc --noEmit for the consumer app and the kit
	$(CONSUMER_APP_PNPM) typecheck
	$(MOBILE_PNPM) --filter @northline/mobile-kit typecheck

.PHONY: mobile-consumer-test
mobile-consumer-test: $(MOBILE_INSTALLED) ## Jest + React Native Testing Library (screens on the fixture backend) and the kit's tests
	$(CONSUMER_APP_PNPM) test
	$(MOBILE_PNPM) --filter @northline/mobile-kit test

.PHONY: mobile-consumer-export
mobile-consumer-export: $(MOBILE_INSTALLED) ## expo export: the Hermes bundles for iOS and Android (no native build)
	$(CONSUMER_APP_PNPM) export:ios
	$(CONSUMER_APP_PNPM) export:android

.PHONY: mobile-consumer-native-check
mobile-consumer-native-check: $(MOBILE_INSTALLED) ## expo prebuild of both platforms (dev + prod variants) and checks of the generated manifests, then deleted
	$(CONSUMER_APP_PNPM) native:check

.PHONY: mobile-consumer-web-smoke
mobile-consumer-web-smoke: $(MOBILE_INSTALLED) ## Web build on the fixture backend + headless Chromium (402 × 874): Journey A, tabs, French
	$(CONSUMER_APP_PNPM) export:web
	$(CONSUMER_APP_PNPM) smoke:web

# Look at the native apps' screens without a phone (light direction): the web build of each app on its fixture
# backend, every screen photographed at 390 × 844 by headless Chromium (mobile/scripts/shots.mjs).
# MOBILE_SHOTS_APPS=consumer|courier (default both) · MOBILE_SHOTS_SKIP_BUILD=1 · MOBILE_SHOTS_ONLY=home,product
# S-178: the stock photos come from the variants `make photos` builds into the consumer site's /photos/ (built first
# when missing); shots.mjs answers the app's photo requests from that folder.
MOBILE_SHOTS_APPS ?= consumer courier
.PHONY: mobile-shots
mobile-shots: $(MOBILE_INSTALLED) ## Screenshots of every consumer and courier screen (web build, fixture backend, 390×844) into mobile/shots-out/<app>/
	@[ -d $(ROOT)/web/apps/consumer/public/photos ] || $(MAKE) photos
	@for app in $(MOBILE_SHOTS_APPS); do \
	  pkg=$$( [ "$$app" = consumer ] && echo @northline/consumer-app || echo @northline/courier ); \
	  if [ -z "$(MOBILE_SHOTS_SKIP_BUILD)" ]; then (cd $(ROOT)/mobile && pnpm --filter $$pkg export:web) || exit 1; fi; \
	  node $(ROOT)/mobile/scripts/shots.mjs $$app $(if $(MOBILE_SHOTS_ONLY),--only $(MOBILE_SHOTS_ONLY),) || exit 1; \
	done
	@echo "mobile-shots: $(ROOT)/mobile/shots-out/<app>/<nn>-<screen>.png (390×844; -full = the whole scroll, -fr = French); sideways scroll in _overflow.txt"

.PHONY: mobile-consumer-start
mobile-consumer-start: $(MOBILE_INSTALLED) ## Expo dev server for a dev build on a phone/simulator (EXPO_PUBLIC_* must reach your machine)
	$(CONSUMER_APP_PNPM) start

.PHONY: mobile-consumer-web
mobile-consumer-web: $(MOBILE_INSTALLED) ## The app in a browser on the fixture backend (no server needed)
	$(CONSUMER_APP_PNPM) web:fixtures

.PHONY: mobile-consumer-eas-build
mobile-consumer-eas-build: $(MOBILE_INSTALLED) ## EAS Build (EAS_PROFILE=preview, EAS_PLATFORM=all); needs EXPO_TOKEN or `eas login`
	cd $(ROOT)/mobile/apps/consumer && npx --yes eas-cli@latest build --profile $(EAS_PROFILE) --platform $(EAS_PLATFORM) --non-interactive $(EAS_FLAGS)

.PHONY: mobile-consumer-clean
mobile-consumer-clean: ## Delete the consumer app's export outputs, smoke screenshots and any prebuild output
	rm -rf $(ROOT)/mobile/apps/consumer/dist-ios $(ROOT)/mobile/apps/consumer/dist-android $(ROOT)/mobile/apps/consumer/dist-web \
	  $(ROOT)/mobile/apps/consumer/smoke-out $(ROOT)/mobile/apps/consumer/ios $(ROOT)/mobile/apps/consumer/android $(ROOT)/mobile/shots-out
