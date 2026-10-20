# S-150: sign-in codes for the seeded personas (scripts/dev/personas.json; local only).

##@ Sign-in (local personas)

.PHONY: creds
creds: ## Every seeded persona: app, email, mobile, the authenticator code for right now, backup codes, latest phone code (WHO=ravi for one)
	@node $(ROOT)/scripts/dev/creds.mjs $(WHO)

.PHONY: logins
logins: creds ## Alias of make creds

.PHONY: backup-codes-reset
backup-codes-reset: ## Local DB only: every persona's backup codes work again, and their seeded authenticator keys are back
	@bash $(ROOT)/scripts/dev/backup-codes-reset.sh

.PHONY: creds-test
creds-test: ## personas.json against the dev seed SQL and the README table (node --test)
	cd $(ROOT) && node --test scripts/dev/personas.test.mjs
