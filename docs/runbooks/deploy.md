# Deploy runbook — container images and the Helm chart (S-14)

How Northline gets from a commit to pods on any Kubernetes cluster: build and push the images, install or upgrade the
Helm chart per environment and cloud, roll back, and rehearse the whole thing locally on kind. Nothing here is tied to
one cloud — the same images and the same chart run on EKS, GKE, AKS or kind; only values change.

> **Status (2026-09-30):** images build and run; the chart installs on kind with every pod Ready (below). No cloud
> cluster exists yet (Terraform is unapplied — [infrastructure.md](infrastructure.md)), so nothing has been deployed to
> EKS/GKE/AKS. Secrets come from the cloud secrets manager through External Secrets (S-6, [secrets.md](secrets.md));
> database migrations run as a Job before every rollout (S-16); Argo CD delivers the chart per environment (S-15,
> [gitops.md](gitops.md)); TLS, DNS and the Gateway are S-17 ([edge.md](edge.md)).

Other runbooks: [dev](dev.md) · [staging](staging.md) · [prod](prod.md) · [infrastructure](infrastructure.md) ·
[CI](ci.md) · [overview and variables](README.md)

## What is built

| image | from | base | user | port | health |
|---|---|---|---|---|---|
| `api` | `server/api` — Jib | `gcr.io/distroless/java25-debian13:nonroot` (pinned digest) | 65532 | 8080 | `/actuator/health/{liveness,readiness}` |
| `auth` | `server/auth` — Jib | same | 65532 | 9000 | same |
| `bff` | `server/bff` — Jib | same | 65532 | 8082 | same |
| `worker` | `server/worker` — Jib | same | 65532 | 8084 (health only) | same |
| `studio` | `web/Dockerfile` target `studio` | `nginxinc/nginx-unprivileged:1.29-alpine` | 101 | 8080 | `/healthz` |
| `consumer` | `web/Dockerfile` target `consumer` | `gcr.io/distroless/nodejs22-debian13:nonroot` | 65532 | 3000 | `/healthz` |
| `console` | — | the console app (E-8) doesn't exist yet: `apps.console` in the chart is a disabled placeholder | | | |

- **Names are registry-agnostic:** `<REGISTRY>/<app>:<IMAGE_TAG>`, where `REGISTRY` includes any path and
  `IMAGE_TAG` is the git sha. Terraform's `registry.repository_urls` are exactly these repositories (`api`, `auth`,
  `bff`, `worker`, `studio`, `consumer`):

  | cloud | `REGISTRY` |
  |---|---|
  | AWS (ECR, tags immutable) | `<account>.dkr.ecr.ca-central-1.amazonaws.com/northline` |
  | Google Cloud (Artifact Registry) | `northamerica-northeast1-docker.pkg.dev/<project>/<repository>` (`registry.registry_url`) |
  | Azure (ACR) | `<name>.azurecr.io/northline` |
  | GitHub / GitLab | `ghcr.io/<owner>/northline` · `registry.gitlab.com/<group>/<project>` |

- **Java (Jib):** no Dockerfile and no Docker daemon needed to push. Exploded classpath in three layers (libraries,
  resources, classes), `java -cp @/app/jib-classpath-file <main class>`, `-XX:MaxRAMPercentage=75`
  (the pod's memory limit sizes the heap), reproducible (fixed timestamps — the same commit gives the same digest), OCI
  labels (`org.opencontainers.image.revision` = the commit, `.version` = the tag, `.source`, `.title`). Multi-platform
  (`linux/amd64,linux/arm64`) when pushing. The worker gained a health-only HTTP port (8084) for its probes.
- **Studio:** the Vite build behind nginx, SPA fallback to `index.html`, hashed `/assets/*` cached for a year,
  pre-compressed `.gz` (`gzip_static`), security headers (CSP, `X-Frame-Options DENY`, `nosniff`, `Referrer-Policy`,
  `Permissions-Policy`, COOP). **One image for every environment:** the auth origin is read at container start from
  `NL_AUTH_ORIGIN` and served as `/config.js` (`window.__NL_CONFIG__.authOrigin`), which the Studio reads before
  `VITE_NL_AUTH_ORIGIN`. Brotli is left to the edge/CDN (S-17): stock nginx has no brotli module.
- **Consumer:** TanStack Start's build is a fetch handler; `web/apps/consumer/server/node-server.mjs` serves it with
  Node's own http module (static assets from `dist/client`, SSR for the rest, `/healthz`, graceful SIGTERM), no extra
  dependency.

## Build and push

### From CI (manual)

- **GitHub:** Actions › **deploy** › Run workflow — `registry`, `image-tag` (empty = short sha), `images`
  (`all`/`java`/`web`/`none`), `push`, `login` (`password` → secrets `REGISTRY_USERNAME`/`REGISTRY_PASSWORD`; `ghcr`
  → the workflow token), `platforms`, `chart` (Helm checks). `gh workflow run deploy.yml -f registry=… -f image-tag=…`.
  The run summary lists each Java image's digest.
- **GitLab:** Run pipeline with `PIPELINE_PART=images` and `IMAGE_REGISTRY` (empty = the project's registry),
  `IMAGE_TAG`, `IMAGE_APPS`, `IMAGE_PLATFORMS`; credentials `REGISTRY_USER` / `REGISTRY_PASSWORD` (default: the job
  token for the GitLab registry). `PIPELINE_PART=chart` (also part of `all`) runs the Helm checks. Digests are in the
  `image-digests.txt` artifact.

Registry passwords per cloud (until CI federates with each cloud's OIDC): ECR — user `AWS`, password
`aws ecr get-login-password --region ca-central-1` (valid 12 h); Artifact Registry — user `_json_key_base64`, password
a base64 service-account key with `roles/artifactregistry.writer`; ACR — a repository-scoped token (`az acr token
create … --scope-map _repositories_push`) or a service principal with `AcrPush`.

### From a workstation

```sh
export REGISTRY=<registry/path> IMAGE_TAG=$(git rev-parse --short=12 HEAD)
docker login "${REGISTRY%%/*}"                       # or: aws ecr get-login-password | docker login …; az acr login; gcloud auth configure-docker
cd server && ./gradlew jib -Pimage.registry="$REGISTRY" -Pimage.tag="$IMAGE_TAG"      # api auth bff worker, amd64 + arm64
cd ../web
for app in studio consumer; do
  docker buildx build . --target "$app" --platform linux/amd64,linux/arm64 --provenance=false \
    --build-arg GIT_SHA="$(git rev-parse HEAD)" --build-arg IMAGE_TAG="$IMAGE_TAG" -t "$REGISTRY/$app:$IMAGE_TAG" --push
done
```

Local images only (no registry): `./gradlew jibDockerBuild -Pimage.platforms=linux/amd64` → `northline/<app>:dev`;
`docker build web --target studio -t northline/studio:dev` (and `consumer`). Behind a TLS-intercepting proxy pass
`--build-arg HTTPS_PROXY --secret id=extra_ca,src=<ca.pem>` (and `--network=host` if the proxy listens on localhost);
if Docker Hub rate-limits, `--build-arg NODE_IMAGE=mirror.gcr.io/library/node:22-bookworm-slim`. Maven Central 429s:
`MAVEN_MIRROR_URL=… ./gradlew … --init-script ../ci/gradle/maven-mirror.init.gradle.kts` ([ci.md](ci.md)).

## The chart: `deploy/helm/northline`

One chart, one release per namespace (`northline-<env>`); every resource is `northline-<app>` (fixed, so the
ServiceAccounts match the names Terraform binds to cloud identities). Per enabled app: Deployment, Service (not the
worker), ServiceAccount, ConfigMap, NetworkPolicy, optional HPA and PodDisruptionBudget (only with more than one
replica); plus the Ingress or Gateway API HTTPRoutes and the OAuth client Job.

| concern | how |
|---|---|
| configuration | `envFrom`: ConfigMap `northline-infra` (Terraform `config_env`, rendered from `configEnv` values or an existing ConfigMap) + ConfigMap `northline-<app>` (derived URLs + `env` + `apps.<app>.env`). Derived: `SPRING_PROFILES_ACTIVE` (= `global.environment`), `AUTH_ISSUER`, `STUDIO_ORIGIN`, `CONSUMER_ORIGIN`, `CONSOLE_ORIGIN`, `API_PUBLIC_URL`, `WEBAUTHN_RP_ID` from `urls.*`; `API_URL`, `AUTH_INTERNAL_URL` = the in-cluster Services |
| secrets | each app reads **only its own keys** (`apps.<app>.secretEnv`: key → required). With `externalSecrets.enabled` (the cloud overlays, S-6) an ExternalSecret per app writes Secret `northline-<app>-secrets` from AWS Secrets Manager / Secret Manager / Key Vault ([secrets.md](secrets.md)); without it, one hand-made Secret `secrets.existingSecret` (`northline-secrets`). A missing required key keeps the pod from starting (`CreateContainerConfigError`); optional ones fall back to the app default |
| workload identity | `workloadIdentities.<app>.service_account_annotations` on the ServiceAccount and `.pod_labels` on the pods — exactly Terraform's `kubernetes.workload_identities` output (IRSA role ARN, GKE service account, Azure client id + `azure.workload.identity/use`). No cloud logic in the templates |
| probes | Spring: startup (up to 5 min) + liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness` (the S-1 probe groups: they don't include Kafka/Elasticsearch, so a data-store blip doesn't restart pods); web: `/healthz` |
| security | non-root (65532 / 101), `runAsNonRoot`, `seccompProfile: RuntimeDefault`, no privilege escalation, all capabilities dropped, **read-only root file system** (an `emptyDir` on `/tmp`; nginx also on `/etc/nginx/conf.d`), no ServiceAccount token mounted |
| rollouts | `maxUnavailable: 0`, `maxSurge: 1`, `preStop` sleep 5 s (Kubernetes ≥ 1.30), 45 s grace period, pods roll when their configuration changes (`checksum/config`), zone and node spread (soft) |
| network | NetworkPolicies (ingress only): studio, consumer, bff and auth accept the ingress controller (`networkPolicy.ingressFrom`, default any namespace; staging/prod narrow it to `envoy-gateway-system` — S-20, [edge.md § Trusted proxies](edge.md#trusted-proxies-s-20)); api accepts the bff (and the ingress for its public paths); auth accepts bff and api; worker accepts nothing. Egress stays open (managed data stores and cloud APIs sit at provider addresses) |
| routes and edge (S-17) | default (dev/staging/prod): `gateway.enabled` + `edge.enabled` — the chart's Gateway `northline` (Envoy Gateway) with an HTTPS listener per host, HTTP → 301, HSTS and headers on every route, TLS 1.2+, a cert-manager Issuer and a Certificate per host ([edge.md](edge.md)); or `ingress.enabled` (+ `className`) with the same certificates; or HTTPRoutes on an existing Gateway (`gateway.parentRefs`). Studio host: `/api`, `/bff`, `/oauth2`, `/login` → bff, `/` → studio; auth host → auth; consumer, pages (+ `edge.customDomains`) → consumer; console host once enabled; api host → only `/api/v1/webhooks/stripe` (S-12) and `/api/v1/email/unsubscribe` (S-13) |
| migrations (S-16) | pre-install/pre-upgrade hook Job `northline-migrate-<hash>` (Argo CD PreSync; the name carries a hash of its inputs, so each change runs a new Job — S-15) from the api image: Flyway `db/migration`, then the category seed — before any Deployment changes; a failure fails the release ([§ Migrations](#migrations-s-16)) |
| OAuth clients (S-122) | post-install/post-upgrade hook Job `northline-oauth-clients-<hash>`: the auth image with the auth environment runs `OAuthClientsCommand sync` (`oauthClientsJob.command: list` to only report). Argo CD runs it as PostSync |

Values files (later `-f` wins): `values.yaml` (defaults) → `values-<env>.yaml` (`dev`, `staging`, `prod`: profile,
hosts, replicas/HPA, sizes, what is required there) → `values-<cloud>.yaml` (`aws`, `gcp`, `azure`: region for the AWS
SDKs, the Memorystore CA on Google Cloud, the recommended email provider, ingress class hints) → the values generated
from Terraform for that environment. `values-local-kind.yaml` is the local rehearsal.

Refusals at render time: `secrets.create` (Secret from values) outside `global.environment=local`; `http://` URLs in
staging/prod; an unknown environment.

### Values from Terraform

```sh
cd infra/terraform/envs/<cloud>/<env>
terraform output -json helm_values > /tmp/northline-values.json   # configEnv + workloadIdentities + externalSecrets (S-6)
terraform output -json registry | jq -r .registry_url             # REGISTRY (append /northline on AWS and Azure)
```

`helm_values` holds no secret (`configEnv` is non-secret by construction; `externalSecrets.remoteKeys` only names the
secrets), but it is environment-specific: it goes to `deploy/argocd/envs/<env>/infra.yaml` (S-15, [gitops.md](gitops.md)), not into this chart.
(The individual outputs `config_env`, `kubernetes.workload_identities`, `secret_env` still exist.)

## Install, upgrade, roll back

> In dev, staging and prod the chart is installed by **Argo CD** ([gitops.md](gitops.md)): same chart, same order
> (migrations → Deployments → OAuth clients), but no `helm upgrade` by hand and rollback through Git or `argocd app
> rollback`. The Helm commands below are for a cluster without Argo CD and for kind.

Prerequisites per environment: the cluster and data stores from Terraform, the Postgres role and extensions
([infrastructure.md § 5.1](infrastructure.md#51-postgresql-app-role-and-extensions-once-per-environment)), Kafka topics
([infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials)), and the secret values
([dev.md § Environment variables](dev.md#environment-variables)).

```sh
# once per cluster: External Secrets Operator with its workload identity — secrets.md § Setting it up
kubectl create namespace northline-<env>
# Google Cloud only: the Memorystore CA (values-gcp.yaml)
kubectl -n northline-<env> create configmap northline-redis-ca --from-file=ca.pem=redis-ca.pem

helm upgrade --install northline deploy/helm/northline -n northline-<env> \
  -f deploy/helm/northline/values-<env>.yaml -f deploy/helm/northline/values-<cloud>.yaml \
  -f /tmp/northline-values.json \
  --set global.image.registry="$REGISTRY" --set global.image.tag="$IMAGE_TAG" \
  --wait --timeout 15m
```

The secret values must already be in the secrets manager ([secrets.md § Secret inventory](secrets.md#secret-inventory)).
A cluster without External Secrets: add `--set externalSecrets.enabled=false` and create one Secret with every secret
variable first (`kubectl -n northline-<env> create secret generic northline-secrets --from-env-file=secrets.env`,
never committed).

- Order of one `helm upgrade --install … --wait`: the migration Job (pre-install/pre-upgrade hook) → the Deployments
  roll → the OAuth client Job (post-install/post-upgrade, after every Deployment is Ready with `--wait`).
- **Edge** (S-17): the environment files turn on the chart's Gateway, certificates and headers; the cluster needs
  Envoy Gateway, cert-manager and external-dns first ([edge.md § Setting it up](edge.md#setting-it-up-per-environment)).
  For an Ingress controller instead: `--set gateway.enabled=false --set ingress.enabled=true --set ingress.className=<class>`.
- **Pin digests**: `--set apps.api.image.digest=sha256:…` per app; the tag is then informational. Argo CD environments
  pin every image in `deploy/argocd/envs/<env>/images.yaml` and refuse to render without (`global.image.requireDigest`,
  [gitops.md § Promotion](gitops.md#promotion-build--digest--pr--sync)).
- **Check:** `kubectl -n northline-<env> get pods` (all Ready, `northline-oauth-clients-<hash>` Completed),
  `kubectl -n northline-<env> logs -l app.kubernetes.io/component=oauth-clients --tail=20` (`studio-bff: up to date` or `create`), then the
  environment runbook's *Verify* step.
- **Upgrade** = the same command with the new `IMAGE_TAG`. Migrations run first; if they fail, the upgrade stops there
  and nothing else changes. Pods then roll one at a time behind readiness; a pod that never becomes ready stops the
  rollout and the old pods keep serving (`helm upgrade --wait` fails after the timeout).
- **Roll back:** `helm history northline -n northline-<env>` → `helm rollback northline <revision> -n northline-<env>
  --wait`. This restores the previous images and ConfigMaps; it does not undo migrations (Flyway is forward-only, so
  every migration must keep the previous release working — [dev.md § Deploy](dev.md#deploy-migrate-roll-back)) and does
  not restore Secret values (those live in the secrets manager — restore the previous version there).
- **Uninstall:** `helm uninstall northline -n northline-<env>` (the Secret, the infra ConfigMap you created by hand
  and the data stores stay; the per-app Secrets go with their ExternalSecrets).

Per cloud, nothing differs but the overlay and the registry:

| | AWS (EKS) | Google Cloud (GKE) | Azure (AKS) |
|---|---|---|---|
| credentials for `kubectl`/`helm` | `terraform output -json kubernetes \| jq -r .kubeconfig_command \| sh` | same | same (+ `kubelogin`) |
| overlay | `values-aws.yaml` | `values-gcp.yaml` (+ ConfigMap `northline-redis-ca`) | `values-azure.yaml` (+ `EMAIL_ENDPOINT`) |
| image pulls | node role reads ECR | node service account reads Artifact Registry | kubelet identity has AcrPull |
| NetworkPolicy enforced by | VPC CNI network policy agent (enable it) | Dataplane V2 | Cilium |

## Migrations (S-16)

`db/migrations` (V001…) are applied by the Job `northline-migrate-<hash>` **before** every install and upgrade (Helm
`pre-install,pre-upgrade` hook; Argo CD `PreSync` with sync waves, S-15), never by the pods: the api runs with
`SPRING_FLYWAY_ENABLED=false` while `migrations.enabled` (default) and northline-auth never migrates outside `local`.

| step | container | what |
|---|---|---|
| 1 | `migrate` (init container) | `DbTool migrate` from the api image (`/app/tools`, outside the app's classpath): logs the schema version and the pending migrations, applies them (Flyway, advisory lock, one transaction per migration), logs the new version |
| 2 | `seed-categories` | `DbTool seed-categories`: upserts `db/seed/categories.json` (idempotent; `migrations.seedCategories: false` skips it) |

- **A failed migration blocks the rollout.** The Job fails (`backoffLimit: 1` → two attempts, `activeDeadlineSeconds:
  900`), `helm upgrade` fails with `pre-upgrade hooks failed`, and no Deployment, ConfigMap or Secret is changed: the
  running pods keep serving the old schema (PostgreSQL rolls the failed migration back; nothing is recorded in
  `flyway_schema_history`). Fix forward with a new image, then upgrade again.
- **Logs:** `kubectl -n northline-<env> logs $(kubectl -n northline-<env> get jobs -l app.kubernetes.io/component=migrate -o name --sort-by=.metadata.creationTimestamp | tail -1) -c migrate` (and `-c seed-categories`). The Job is named `northline-migrate-<hash of image, profile, configEnv, secret source>`: a change runs a new Job, a sync without change doesn't re-run it (S-15). Each Job is
  kept a week (`ttlSecondsAfterFinished`); a redeploy of the same content replaces it.
- **Before a first install** the Job can't use the chart's regular ConfigMaps and Secrets (they don't exist yet), so it
  gets hook-scoped ones: ConfigMap `northline-migrate-<hash>` (`SPRING_PROFILES_ACTIVE`, `NORTHLINE_ENVIRONMENT`, Terraform's
  `configEnv`) and Secret `northline-migrate-<hash>` with `DB_PASSWORD` only (a hook ExternalSecret with External
  Secrets; a hook Secret on kind; or the existing `secrets.existingSecret`). It runs as the namespace's `default`
  ServiceAccount without a token: it needs no cloud identity.
- **The dev seed can't get there.** `db/seed-dev` (V1xx personas) is not a main resource any more: it isn't in the boot
  jars or the images (only `bootRun`, the tests and the Gradle DB tasks see it). DbTool refuses `db.devSeed` under a
  deployed profile (`dev`, `staging`, `prod`, `cloud`) or for a non-local database host, and the api refuses a Flyway
  location naming `seed-dev` outside `local`/`test` (`DevSeedGuard`). Prod therefore has no V1xx rows:
  `select count(*) from flyway_schema_history where version::int >= 100` is 0.
- **Rules for migrations** (unchanged): forward-only; every migration keeps the previous release working (expand →
  migrate → contract across releases), because old pods serve against the new schema during the rollout and after a
  `helm rollback`.
- Manual run without Helm (from a machine that can reach the database):
  `DB_URL=… DB_USER=… DB_PASSWORD=… ./gradlew :api:flywayMigrate :api:seedCategories` — never `-Pdb.devSeed=true`
  (refused anyway outside local).
- `migrations.enabled: false` returns to migrate-on-start (the api's Flyway on), e.g. for a cluster without hook support.

## Kafka topics (S-25)

The Job `northline-kafka-topics-<hash>` (hash of its inputs, as the migrations Job — S-15) runs **before** every install and upgrade (same hook mechanism as the migrations,
hook weight -10, Argo CD `PreSync`), from the **worker** image: `TopicsCommand <kafkaTopics.command>` reads
`deploy/kafka/topics.yaml` (packaged in the image) and talks to Kafka through the admin API with the worker's
`KAFKA_*` settings. A release whose api publishes to a new topic or whose worker gains a consumer therefore finds
the topics (and their `.dlq` / retry topics) before any pod starts.

- `kafkaTopics.command`: `apply` (default; MSK, Managed Kafka) creates missing topics and corrects retention /
  cleanup / `min.insync.replicas` drift; `plan` (values-azure.yaml — Terraform creates event hubs) reports only;
  `verify` reports and exits 3 on drift, which fails the release. Partition drift and unmanaged topics are reported,
  never changed; nothing is ever deleted.
- Inputs: hook ConfigMap `northline-kafka-topics-<hash>` (the `KAFKA_*` keys of `configEnv`, plus
  `KAFKA_REPLICATION_FACTOR` / `KAFKA_MIN_INSYNC_REPLICAS` from `kafkaTopics.replicationFactor` /
  `.minInsyncReplicas`; prod sets min ISR 2) and hook Secret `northline-kafka-topics-<hash>` with
  `KAFKA_SASL_JAAS_CONFIG` (hook ExternalSecret, or `secrets.values` on kind). No Spring profile: no database,
  Valkey or Elasticsearch needed.
- Logs: `kubectl -n northline-<env> logs $(kubectl -n northline-<env> get jobs -l app.kubernetes.io/component=kafka-topics -o name --sort-by=.metadata.creationTimestamp | tail -1)` (`CREATED`, `CORRECTED`, `DRIFT`, `UNMANAGED`
  lines and a summary). `kafkaTopics.enabled: false` turns it off (the kind rehearsal has no Kafka).
- Details, per-cloud table and how to change the catalogue: [infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials).

## Local: kind

A full rehearsal on a laptop: kind + the local images + Postgres and Valkey, with the Spring apps in the `dev`
profile (the cloud shape) and every probe, policy and security setting of the real chart.

```sh
cd server && ./gradlew jibDockerBuild -Pimage.platforms=linux/amd64 && cd ..      # northline/{api,auth,bff,worker}:dev
docker build web --target studio -t northline/studio:dev
docker build web --target consumer -t northline/consumer:dev
deploy/kind/up.sh          # kind cluster "northline", namespace northline-local, helm install --wait (≈ 3 min)
kubectl -n northline-local port-forward svc/northline-auth 19000:9000 &
curl localhost:19000/.well-known/openid-configuration
kubectl -n northline-local port-forward svc/northline-studio 18080:8080 &   # http://localhost:18080
deploy/kind/down.sh        # deletes the cluster and the Postgres container
```

- Tools: Docker, `kind` ≥ 0.27, `kubectl`, `helm` 3.14+. Variables: `KIND_CLUSTER`, `NAMESPACE`, `IMAGE_TAG`, `APPS`
  (images to load; `APPS=""` to skip), `HELM_EXTRA_ARGS`.
- Postgres runs **next to** the cluster (a PostGIS container on the `kind` network behind a selector-less Service —
  the shape of a managed database, and the image isn't copied into the node). Valkey runs in the cluster. Kafka and
  Elasticsearch are not started: `kafka` and `elasticsearch` are Services without endpoints, so the worker and the
  api's event externalization retry in the background and the overall `/actuator/health` is `DOWN` while
  liveness/readiness are `UP`.
- Secrets come from `values-local-kind.yaml` (`secrets.create`, allowed only with `global.environment=local`), with the
  apps' local development values — or, with External Secrets Operator installed in the cluster, add
  `-f deploy/helm/northline/values-local-kind-eso.yaml` (ESO's `fake` provider; [secrets.md](secrets.md#local-development-and-kind)). Token signing uses `KMS_PROVIDER=local` on an `emptyDir` (one auth replica).
- The migration Job migrates the empty database and seeds the categories before any pod starts (no dev seed: this is
  the cloud shape). The OAuth client Job runs after everything is Ready.
- kindnet enforces NetworkPolicies only on kernels with nftables queue support; elsewhere the policies are accepted but
  not enforced.

## Checks (no cluster)

```sh
deploy/helm/validate.sh     # helm lint --strict + helm template | kubeconform -strict: dev/staging/prod × aws/gcp/azure,
                            # kind, Gateway API, defaults; plus the render-time refusals and the topics Job's command per cloud
```

CI runs it manually (GitHub **deploy** workflow `chart` input; GitLab `PIPELINE_PART=chart` or `all`). It needs
`helm` and `kubeconform`; schemas come from the Kubernetes JSON schema mirror and the Datree CRD catalog
(`KUBECONFORM_SCHEMAS` adds a mirror for air-gapped runners), checked against `KUBE_VERSION` (default 1.33.0).

## Troubleshooting

| symptom | cause / fix |
|---|---|
| pod `CreateContainerConfigError`, "couldn't find key X in Secret" | a required secret is missing: with External Secrets see the ExternalSecret's status ([secrets.md § Troubleshooting](secrets.md#troubleshooting)); without, add the key to `northline-secrets` |
| pod `CrashLoopBackOff`, log `APPLICATION FAILED TO START … need environment variables that are not set` | the S-1 check: the listed variables are missing from the ConfigMaps/Secret |
| startup probe fails after 5 min | the app can't reach a data store at start (Postgres, Valkey): check `DB_URL`, `REDIS_*` and egress from the namespace |
| studio `/config.js` shows the wrong auth origin | `urls.auth` (→ `NL_AUTH_ORIGIN`); the page caches nothing, reload |
| `ImagePullBackOff` | `global.image.registry` / tag wrong, or the nodes' identity can't read the registry (ECR/AR/ACR grants from Terraform `registry` readers) |
| `northline-oauth-clients-<hash>` failed | `kubectl logs -l app.kubernetes.io/component=oauth-clients`: redirect URIs must be `https` outside dev, secret hashes `{bcrypt}` in staging/prod ([README § OAuth clients](README.md#oauth-clients-s-122)) |
