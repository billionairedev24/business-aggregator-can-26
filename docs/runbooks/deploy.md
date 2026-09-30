# Deploy runbook — container images and the Helm chart (S-14)

How Northline gets from a commit to pods on any Kubernetes cluster: build and push the images, install or upgrade the
Helm chart per environment and cloud, roll back, and rehearse the whole thing locally on kind. Nothing here is tied to
one cloud — the same images and the same chart run on EKS, GKE, AKS or kind; only values change.

> **Status (2026-09-30):** images build and run; the chart installs on kind with every pod Ready (below). No cloud
> cluster exists yet (Terraform is unapplied — [infrastructure.md](infrastructure.md)), so nothing has been deployed to
> EKS/GKE/AKS. Secrets are plain Kubernetes Secrets until External Secrets (S-6); the api still migrates at start-up
> until the migration Job (S-16); GitOps is S-15; TLS, DNS and the ingress controller are S-17.

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
| secrets | each app reads **only its own keys** (`apps.<app>.secretEnv`: key → required) from the Secret `secrets.existingSecret` (`northline-secrets`). A missing required key keeps the pod from starting (`CreateContainerConfigError`); optional ones fall back to the app default. S-6 replaces the hand-made Secret with External Secrets |
| workload identity | `workloadIdentities.<app>.service_account_annotations` on the ServiceAccount and `.pod_labels` on the pods — exactly Terraform's `kubernetes.workload_identities` output (IRSA role ARN, GKE service account, Azure client id + `azure.workload.identity/use`). No cloud logic in the templates |
| probes | Spring: startup (up to 5 min) + liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness` (the S-1 probe groups: they don't include Kafka/Elasticsearch, so a data-store blip doesn't restart pods); web: `/healthz` |
| security | non-root (65532 / 101), `runAsNonRoot`, `seccompProfile: RuntimeDefault`, no privilege escalation, all capabilities dropped, **read-only root file system** (an `emptyDir` on `/tmp`; nginx also on `/etc/nginx/conf.d`), no ServiceAccount token mounted |
| rollouts | `maxUnavailable: 0`, `maxSurge: 1`, `preStop` sleep 5 s (Kubernetes ≥ 1.30), 45 s grace period, pods roll when their configuration changes (`checksum/config`), zone and node spread (soft) |
| network | NetworkPolicies (ingress only): studio, consumer, bff and auth accept the ingress controller (`networkPolicy.ingressFrom`, default any namespace — narrow it per cluster); api accepts the bff (and the ingress for its public paths); auth accepts bff and api; worker accepts nothing. Egress stays open (managed data stores and cloud APIs sit at provider addresses) |
| routes | `ingress.enabled` (+ `className`, `annotations`, `tls` secret names) or `gateway.enabled` (+ `parentRefs`). Studio host: `/api`, `/bff`, `/oauth2`, `/login` → bff, `/` → studio; auth host → auth; consumer (and console) hosts; api host → only `/api/v1/webhooks/stripe` (S-12) and `/api/v1/email/unsubscribe` (S-13). No certificates or DNS here (S-17) |
| OAuth clients (S-122) | post-install/post-upgrade hook Job `northline-oauth-clients`: the auth image with the auth environment runs `OAuthClientsCommand sync` (`oauthClientsJob.command: list` to only report). Argo CD runs it as PostSync |

Values files (later `-f` wins): `values.yaml` (defaults) → `values-<env>.yaml` (`dev`, `staging`, `prod`: profile,
hosts, replicas/HPA, sizes, what is required there) → `values-<cloud>.yaml` (`aws`, `gcp`, `azure`: region for the AWS
SDKs, the Memorystore CA on Google Cloud, the recommended email provider, ingress class hints) → the values generated
from Terraform for that environment. `values-local-kind.yaml` is the local rehearsal.

Refusals at render time: `secrets.create` (Secret from values) outside `global.environment=local`; `http://` URLs in
staging/prod; an unknown environment.

### Values from Terraform

```sh
cd infra/terraform/envs/<cloud>/<env>
terraform output -json config_env | jq '{configEnv: .}' > /tmp/infra-values.json
terraform output -json kubernetes | jq '{workloadIdentities: .workload_identities}' > /tmp/identities.json
terraform output -json registry | jq -r .registry_url      # REGISTRY (append /northline on AWS and Azure)
```

Neither file holds a secret (`config_env` is non-secret by construction; `secret_env` only names the secrets), but they
are environment-specific: keep them next to the environment's GitOps values (S-15), not in this chart.

## Install, upgrade, roll back

Prerequisites per environment: the cluster and data stores from Terraform, the Postgres role and extensions
([infrastructure.md § 5.1](infrastructure.md#51-postgresql-app-role-and-extensions-once-per-environment)), Kafka topics
([infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials)), and the secret values
([dev.md § Environment variables](dev.md#environment-variables)).

```sh
kubectl create namespace northline-<env>
# Until S-6: one Secret with every secret variable (names = variable names, see the environment runbook).
kubectl -n northline-<env> create secret generic northline-secrets --from-env-file=secrets.env   # never commit secrets.env
# Google Cloud only: the Memorystore CA (values-gcp.yaml)
kubectl -n northline-<env> create configmap northline-redis-ca --from-file=ca.pem=redis-ca.pem

helm upgrade --install northline deploy/helm/northline -n northline-<env> \
  -f deploy/helm/northline/values-<env>.yaml -f deploy/helm/northline/values-<cloud>.yaml \
  -f /tmp/infra-values.json -f /tmp/identities.json \
  --set global.image.registry="$REGISTRY" --set global.image.tag="$IMAGE_TAG" \
  --wait --timeout 15m
```

- `--wait` matters: Helm then runs the OAuth client Job only after every Deployment is Ready (the api has migrated the
  schema by then). Without it, the Job may start before the tables exist and retry (`backoffLimit: 2`).
- **Ingress class / TLS** until S-17: add `--set ingress.className=nginx` (or your controller) and, when a
  certificate secret exists, `--set ingress.tls[0].secretName=… --set ingress.tls[0].hosts={…}`.
- **Pin digests** (what S-15 will do): `--set apps.api.image.digest=sha256:…` per app; the tag is then informational.
- **Check:** `kubectl -n northline-<env> get pods` (all Ready, `northline-oauth-clients` Completed),
  `kubectl -n northline-<env> logs job/northline-oauth-clients` (`studio-bff: up to date` or `create`), then the
  environment runbook's *Verify* step.
- **Upgrade** = the same command with the new `IMAGE_TAG`. Pods roll one at a time behind readiness; a pod that never
  becomes ready stops the rollout and the old pods keep serving (`helm upgrade --wait` fails after the timeout).
- **Roll back:** `helm history northline -n northline-<env>` → `helm rollback northline <revision> -n northline-<env>
  --wait`. This restores the previous images and ConfigMaps; it does not undo migrations (Flyway is forward-only, so
  every migration must keep the previous release working — [dev.md § Deploy](dev.md#deploy-migrate-roll-back)) and does
  not restore Secret values (those live in the secrets manager — restore the previous version there).
- **Uninstall:** `helm uninstall northline -n northline-<env>` (the Secret, the infra ConfigMap you created by hand
  and the data stores stay).

Per cloud, nothing differs but the overlay and the registry:

| | AWS (EKS) | Google Cloud (GKE) | Azure (AKS) |
|---|---|---|---|
| credentials for `kubectl`/`helm` | `terraform output -json kubernetes \| jq -r .kubeconfig_command \| sh` | same | same (+ `kubelogin`) |
| overlay | `values-aws.yaml` | `values-gcp.yaml` (+ ConfigMap `northline-redis-ca`) | `values-azure.yaml` (+ `EMAIL_ENDPOINT`) |
| image pulls | node role reads ECR | node service account reads Artifact Registry | kubelet identity has AcrPull |
| NetworkPolicy enforced by | VPC CNI network policy agent (enable it) | Dataplane V2 | Cilium |

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
  apps' local development values. Token signing uses `KMS_PROVIDER=local` on an `emptyDir` (one auth replica).
- The first start of northline-auth may restart once: until S-16 the api creates the schema, and auth can come up
  first. The OAuth client Job runs after everything is Ready.
- kindnet enforces NetworkPolicies only on kernels with nftables queue support; elsewhere the policies are accepted but
  not enforced.

## Checks (no cluster)

```sh
deploy/helm/validate.sh     # helm lint --strict + helm template | kubeconform -strict: dev/staging/prod × aws/gcp/azure,
                            # kind, Gateway API, defaults; plus the render-time refusals
```

CI runs it manually (GitHub **deploy** workflow `chart` input; GitLab `PIPELINE_PART=chart` or `all`). It needs
`helm` and `kubeconform`; schemas come from the Kubernetes JSON schema mirror and the Datree CRD catalog
(`KUBECONFORM_SCHEMAS` adds a mirror for air-gapped runners), checked against `KUBE_VERSION` (default 1.33.0).

## Troubleshooting

| symptom | cause / fix |
|---|---|
| pod `CreateContainerConfigError`, "couldn't find key X in Secret" | a required secret key is missing from `northline-secrets`: add it (or mark it optional in `apps.<app>.secretEnv` if the app really doesn't need it) |
| pod `CrashLoopBackOff`, log `APPLICATION FAILED TO START … need environment variables that are not set` | the S-1 check: the listed variables are missing from the ConfigMaps/Secret |
| startup probe fails after 5 min | the app can't reach a data store at start (Postgres, Valkey): check `DB_URL`, `REDIS_*` and egress from the namespace |
| studio `/config.js` shows the wrong auth origin | `urls.auth` (→ `NL_AUTH_ORIGIN`); the page caches nothing, reload |
| `ImagePullBackOff` | `global.image.registry` / tag wrong, or the nodes' identity can't read the registry (ECR/AR/ACR grants from Terraform `registry` readers) |
| `northline-oauth-clients` failed | `kubectl logs job/northline-oauth-clients`: redirect URIs must be `https` outside dev, secret hashes `{bcrypt}` in staging/prod ([README § OAuth clients](README.md#oauth-clients-s-122)) |
