# mobile/ — the courier app (S-87, Expo + React Native) and @northline/mobile-kit. docs/runbooks/courier-app.md.
# A pnpm workspace of its own (not web/): every target installs it first when mobile/pnpm-lock.yaml is newer.
# Nothing here builds a native binary: that needs Xcode / the Android SDK or EAS Build (courier-eas-build).

MOBILE_PNPM := cd $(ROOT)/mobile && pnpm
COURIER_PNPM := $(MOBILE_PNPM) --filter @northline/courier
MOBILE_INSTALLED := $(ROOT)/mobile/node_modules/.modules.yaml
# EAS profile and platform for courier-eas-build: development | development-device | preview | production; ios | android | all
EAS_PROFILE ?= preview
EAS_PLATFORM ?= all
# extra eas-cli flags, e.g. --no-wait (CI), --auto-submit
EAS_FLAGS ?=

$(MOBILE_INSTALLED): $(ROOT)/mobile/pnpm-lock.yaml $(ROOT)/mobile/package.json
	$(MOBILE_PNPM) install --frozen-lockfile
	@touch $@

##@ Courier app (mobile/, Expo)

.PHONY: courier-install
courier-install: $(MOBILE_INSTALLED) ## pnpm install of mobile/ (frozen lockfile); a no-op while node_modules is up to date

.PHONY: courier-check
courier-check: courier-lint courier-typecheck courier-test courier-export ## What the manual courier CI job runs: lint, typecheck, Jest, iOS + Android bundles

.PHONY: courier-lint
courier-lint: $(MOBILE_INSTALLED) ## ESLint over the app and the kit (warnings fail)
	$(MOBILE_PNPM) lint

.PHONY: courier-typecheck
courier-typecheck: $(MOBILE_INSTALLED) ## tsc --noEmit for the app and the kit
	$(MOBILE_PNPM) -r typecheck

.PHONY: courier-test
courier-test: $(MOBILE_INSTALLED) ## Jest + React Native Testing Library (app screens on the fixture backend, kit)
	$(MOBILE_PNPM) -r test

.PHONY: courier-export
courier-export: $(MOBILE_INSTALLED) ## expo export: the Hermes bundles for iOS and Android (no native build)
	$(COURIER_PNPM) export:ios
	$(COURIER_PNPM) export:android

.PHONY: courier-web-smoke
courier-web-smoke: $(MOBILE_INSTALLED) ## Web build on the fixture backend + headless Chromium: sign in, pickup, PIN drop-off, French
	$(COURIER_PNPM) export:web
	$(COURIER_PNPM) smoke:web

.PHONY: courier-start
courier-start: $(MOBILE_INSTALLED) ## Expo dev server for a dev build on a phone/simulator (EXPO_PUBLIC_API_URL / _AUTH_ISSUER must reach your machine)
	$(COURIER_PNPM) start

.PHONY: courier-web
courier-web: $(MOBILE_INSTALLED) ## The app in a browser on the fixture backend (no server needed)
	$(COURIER_PNPM) web:fixtures

.PHONY: courier-eas-build
courier-eas-build: $(MOBILE_INSTALLED) ## EAS Build (EAS_PROFILE=preview, EAS_PLATFORM=all); needs EXPO_TOKEN or `eas login`
	cd $(ROOT)/mobile/apps/courier && npx --yes eas-cli@latest build --profile $(EAS_PROFILE) --platform $(EAS_PLATFORM) --non-interactive $(EAS_FLAGS)

.PHONY: courier-clean
courier-clean: ## Delete the courier app's export outputs and smoke screenshots
	rm -rf $(ROOT)/mobile/apps/courier/dist-ios $(ROOT)/mobile/apps/courier/dist-android $(ROOT)/mobile/apps/courier/dist-web $(ROOT)/mobile/apps/courier/smoke-out
