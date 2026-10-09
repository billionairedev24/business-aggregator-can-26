# Deploy — container images (S-14), Helm chart and Argo CD checks (S-14/S-15/S-17), the kind rehearsal, promotion.
# docs/runbooks/deploy.md, gitops.md. The checks need helm 3.14+ and kubeconform (argocd-validate also kubectl).

IMAGE_TAG ?= dev
WEB_IMAGES ?= studio consumer console docs docs-internal
# jibDockerBuild (local Docker, default) | jib (PUSH=1: push to REGISTRY, every IMAGE_PLATFORMS) | jibBuildTar (CI build-only)
JIB_TASK ?= $(if $(filter 1 true yes,$(PUSH)),jib,jibDockerBuild)
image_props = $(if $(REGISTRY),-Pimage.registry=$(REGISTRY)) -Pimage.tag=$(IMAGE_TAG) $(if $(IMAGE_PLATFORMS),-Pimage.platforms=$(IMAGE_PLATFORMS))

##@ Deploy (images, Helm, Argo CD)

.PHONY: deploy-validate
deploy-validate: helm-validate argocd-validate ## Every offline Helm and Argo CD check

.PHONY: helm-validate
.PHONY: helm-crd-schemas
helm-crd-schemas: ## Regenerate deploy/helm/schemas (External Secrets CRD schemas) for the operator version Argo CD installs
	python3 $(ROOT)/deploy/helm/crd-schemas.py

helm-validate: ## helm lint --strict + helm template | kubeconform for every env × cloud, kind, Gateway API
	cd $(ROOT) && bash deploy/helm/validate.sh

.PHONY: argocd-validate
argocd-validate: ## App of apps × env × cloud | kubeconform, sync policy, chart as each Application renders it
	cd $(ROOT) && bash deploy/argocd/validate.sh

.PHONY: images
images: images-java images-web ## Build every image (local Docker by default; PUSH=1 REGISTRY=… IMAGE_TAG=… to push)

.PHONY: images-java
images-java: ## api, auth, bff, worker with Jib (JIB_TASK=jibBuildTar for a build-only tar)
	$(GRADLE) $(JIB_TASK) -x test $(image_props)

.PHONY: images-web
images-web: ## studio, consumer, console, docs, docs-internal from web/Dockerfile (PUSH=1 uses buildx for IMAGE_PLATFORMS and pushes)
	@for app in $(WEB_IMAGES); do \
		ref="$(or $(REGISTRY),northline)/$$app:$(IMAGE_TAG)"; echo "--- $$ref"; \
		if [ -n "$(filter 1 true yes,$(PUSH))" ]; then \
			docker buildx build $(ROOT)/web --file $(ROOT)/web/Dockerfile --target $$app --build-context repo-docs=$(ROOT)/docs --platform "$(or $(IMAGE_PLATFORMS),linux/amd64,linux/arm64)" \
				--provenance=false --build-arg IMAGE_TAG=$(IMAGE_TAG) --tag "$$ref" --push || exit 1; \
		else \
			docker buildx build --load $(ROOT)/web --file $(ROOT)/web/Dockerfile --target $$app --build-context repo-docs=$(ROOT)/docs --build-arg IMAGE_TAG=$(IMAGE_TAG) --tag "$$ref" || exit 1; \
		fi; \

.PHONY: kind-up
kind-up: ## Local Helm rehearsal on kind: cluster, Postgres, Valkey, the chart (build the images first: make images)
	cd $(ROOT) && IMAGE_TAG=$(IMAGE_TAG) bash deploy/kind/up.sh

.PHONY: kind-down
kind-down: ## Remove the kind cluster and its Postgres
	cd $(ROOT) && bash deploy/kind/down.sh

.PHONY: gitops-promote
gitops-promote: ## Write deploy/argocd/envs/ENV/images.yaml (ENV=dev|staging|prod FROM=<env> | REGISTRY=… TAG=<sha>)
	@if [ -z "$(ENV)" ]; then echo "usage: make gitops-promote ENV=dev|staging|prod [FROM=dev|staging] [REGISTRY=…] [TAG=…]"; exit 2; fi
	cd $(ROOT) && bash deploy/argocd/promote.sh $(ENV) $(if $(FROM),--from $(FROM)) $(if $(REGISTRY),--registry $(REGISTRY)) $(if $(TAG),--tag $(TAG))
