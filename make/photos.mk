# Stock photos (web/packages/media, docs/runbooks/photos.md): the library, its slots, the variants the sites serve,
# and the Openverse candidates a person picks from.

MEDIA := $(ROOT)/web/packages/media

##@ Photos (web/packages/media; docs/runbooks/photos.md)

.PHONY: photos
photos: $(WEB_INSTALLED) ## Build the photo variants (AVIF/WebP/JPEG crops, 320–1440 px) into the consumer site's /photos/ and fill photos.json's size, hash and colour
	$(PNPM) --filter @northline/media slots
	$(PNPM) --filter @northline/media build

.PHONY: photos-slots
photos-slots: ## Regenerate the slot list (catalogue seed, cuisines, demo data) and print every slot without a photo
	node $(MEDIA)/scripts/slots.mjs --missing

.PHONY: photos-check
photos-check: ## Fail when the slot list or photos.json is out of date, or an entry lacks its licence, source or alt text
	node $(MEDIA)/scripts/slots.mjs --check
	node $(MEDIA)/scripts/build.mjs --check

.PHONY: photos-fetch
photos-fetch: ## Openverse candidates (CC0/PDM; COMMERCIAL=1 adds CC BY/BY-SA) for every slot without a photo — Node 22 only; resumable
	node $(ROOT)/scripts/photos/fetch.mjs $(if $(COMMERCIAL),--commercial) $(if $(GROUP),--group $(GROUP)) $(if $(LIMIT),--limit $(LIMIT))

.PHONY: photos-push
photos-push: ## Commit the candidates on a new branch photos/candidates-<date> from develop and push it (clean working tree only)
	node $(ROOT)/scripts/photos/push.mjs

.PHONY: photos-pick
photos-pick: $(WEB_INSTALLED) ## Promote candidates into the library: SLOT=… N=… ALT_EN="…" ALT_FR="…" [FOCAL=0.5,0.4], or PICKS=picks.json
	@if [ -n "$(PICKS)" ]; then node $(MEDIA)/scripts/pick.mjs --picks "$(abspath $(PICKS))"; \
	elif [ -n "$(SLOT)" ] && [ -n "$(N)" ]; then node $(MEDIA)/scripts/pick.mjs --slot "$(SLOT)" --n "$(N)" --alt-en "$(ALT_EN)" --alt-fr "$(ALT_FR)" --focal "$(or $(FOCAL),0.5$(comma)0.5)"; \
	else echo 'photos-pick: give SLOT=… N=… ALT_EN="…" ALT_FR="…" (FOCAL=x,y) or PICKS=picks.json' >&2; exit 2; fi
