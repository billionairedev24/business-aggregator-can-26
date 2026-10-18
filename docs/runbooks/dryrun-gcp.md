# Dry-run runbook — a demo environment on Google Cloud (GKE)

A short-lived, production-shaped environment for client demos: the whole platform on one zonal GKE cluster in a
Canadian region, paid from a Google Cloud free-trial credit ($300), **fake data only**, torn down after the demos.
Owner decision and alternatives considered: [DECISIONS.md § 2026-10-18](../DECISIONS.md).

> **Status (2026-10-18): never applied.** The Terraform root (`infra/terraform/envs/gcp/dryrun`) passes `terraform
> validate` and an offline plan against mocked providers; both Helm charts render and validate offline
> (`deploy/helm/validate.sh`). Nothing here has run against a real Google Cloud project, CloudNativePG, Layerbase or
> Let's Encrypt yet. Search runs on Meilisearch (`SEARCH_PROVIDER=meilisearch`, [search.md § 11](search.md#11-meilisearch-provider-meilisearch)):
> the search-indices Job creates its indexes, the worker fills them as businesses are onboarded.

Other environments: [dev](dev.md), [staging](staging.md), [prod](prod.md) · [overview](README.md)

## Contents

- [The shape](#the-shape)
- [Cost estimate](#cost-estimate)
- [Pay only while demoing](#pay-only-while-demoing)
- [What you need](#what-you-need)
- [Steps from an empty project](#steps-from-an-empty-project) (1–15)
- [Running it day to day](#running-it-day-to-day)
- [Teardown (stop billing)](#teardown-stop-billing)
- [Cheaper still: one VM (being researched)](#cheaper-still-one-vm-being-researched)
- [Open items](#open-items)

## The shape

Lean on purpose (target: at most ≈ $100 of credit for three weeks):

| piece | dry run | prod (for comparison) |
|---|---|---|
| Region / zone | `northamerica-northeast1` (Montréal), zone `northamerica-northeast1-a` — Terraform variables `region`, `zone` (Canadian regions only); everything in that one zone | regional, multi-zone |
| Kubernetes | GKE Standard, **one zonal cluster** (its management fee is covered by GKE's free tier), **one node pool** `general`: 2 × `e2-standard-4` **Spot** (`node_count`, `spot_nodes`), 30 GB pd-balanced boot disks, Workload Identity, Dataplane V2 | regional, HA pools |
| Network | **no Cloud NAT**: the nodes are public (an ephemeral external IP each) and reach the internet (images, Let's Encrypt, Stripe) directly; no Private Service Access or PSC policy (no managed data stores) | private nodes behind Cloud NAT |
| Spring profile | `dev` (`global.springProfiles`, chart environment `dryrun`): required variables checked, Kafka externalization on, no dev seed; local fakes where no third-party account exists (registries, calendar, POS, places, AI, SMS codes in the auth log, emails logged) | `prod` |
| Replicas and JVMs | 1 of everything; memory limits sized so two nodes hold it all with room (the images give the heap 75 % of the limit, `-XX:MaxRAMPercentage=75`; `JAVA_TOOL_OPTIONS` adds serial GC, a smaller code cache and thread stacks, C1 only) | 2+ with HPAs |
| PostgreSQL 17 + PostGIS | **in the cluster**: CloudNativePG, 1 instance, 10 GB pd-balanced, daily base backup + WAL to the GCS bucket `northline-dryrun-pg-backups` (7 days) | Cloud SQL |
| Valkey | **in the cluster**: 1 pod, append-only file on 1 GB, password | Memorystore |
| Search | **Meilisearch in the cluster**: 1 pod, 5 GB, master key (`SEARCH_PROVIDER=meilisearch`) | Elastic Cloud |
| Kafka | **in the cluster**: one KRaft node (broker + controller), PLAINTEXT, 10 GB, replication factor 1; topics by the chart's Job | Managed Kafka |
| Each data service, alternatively | **external** (e.g. Layerbase): endpoint in `data_services`, credential in Secret Manager — [§ 5](#5-optional-external-data-services) | — |
| Object storage, KMS, secrets, registry, DNS | as every environment: GCS `northline-dryrun-uploads` (CMEK), Cloud KMS keys `data`/`signing`/`tokens` (software), Secret Manager `northline-dryrun-*`, Artifact Registry `northline-dryrun`, Cloud DNS zone `demo.northline.ca` | same (HSM keys) |
| Edge | **one** load balancer: Envoy Gateway (one proxy) behind a single regional network load balancer IP; Let's Encrypt certificates per host (cert-manager, HTTP-01); records by external-dns | same, 2 proxies |
| Delivery | plain `helm upgrade --install` (no Argo CD: one fewer component for three weeks) | Argo CD |
| Observability | **off by default**: pod logs reach Cloud Logging anyway (GKE, inside the free 50 GiB/month) and GKE's system metrics are free. Flag for a debugging session: `--set observability.collector.enabled=true` — one Collector, traces to Cloud Trace and logs to Cloud Logging (free tiers), metrics not exported. No Managed Service for Prometheus samples, no Prometheus/Loki/Tempo/Grafana pods | Collector → the cloud's backends |

Every pod with a volume is pinned to the cluster's zone (`zone` in `deploy/helm/northline-data`); the disks are zonal,
so a node replaced by Spot preemption comes back in the same zone and the pod re-attaches its disk.

**Spot risk.** Google may reclaim a Spot node at any time (30 s notice), most often when the zone is short of
capacity. Its pods restart on the other node, or wait for the replacement (≈ 2–5 minutes; Postgres, Kafka and
Meilisearch wait for their disk). For a demo that must not blink: **demo day = on demand** — `spot_nodes = false` and
`terraform apply` the evening before (the pool is re-created, ≈ 10 minutes), back to `true` afterwards.

Hosts (`values-dryrun.yaml`): `https://demo.northline.ca` (consumer), `studio.`, `auth.`, `console.`, `pages.`,
`api.` under `demo.northline.ca`. No documentation site, no merchant custom domains.

### Memory budget

| | requests |
|---|---|
| Spring apps (api 900 Mi, auth 640 Mi, bff ×3 400 Mi, worker 448 Mi) | ≈ 3.2 GiB |
| web (studio, consumer, console) | ≈ 0.1 GiB |
| Postgres 1 GiB, Kafka 1 GiB, Meilisearch 512 Mi, Valkey 128 Mi | ≈ 2.6 GiB |
| add-ons (External Secrets, cert-manager, Envoy Gateway + 1 proxy, external-dns, CloudNativePG) | ≈ 1 GiB |
| GKE system pods | ≈ 1 GiB |
| **total** | **≈ 8 GiB** of the ≈ 26 GiB two `e2-standard-4` (16 GB each) offer — one node can carry everything while the other is replaced |

## Cost estimate

Approximate list prices for `northamerica-northeast1` (USD; Montréal is about 10 % above `us-central1`). They were
**not re-checked online** for this estimate: confirm them in the
[pricing calculator](https://cloud.google.com/products/calculator) before applying. Spot prices move (up to once a
month); the figure below is typical. Month = 730 h, 3 weeks = 504 h.

| item | per hour | per month | 3 weeks |
|---|---|---|---|
| GKE management fee, one zonal cluster ($0.10/h) | $0 (free tier: $74.40/month per billing account) | $0 | $0 |
| **Always on** (also while the nodes are at 0): load balancer forwarding rule $0.025 + its IP $0.005 | $0.030 | $22 | $15 |
| Data volumes, pd-balanced 26 GB (≈ $0.11/GB-month) | $0.004 | $3 | $2 |
| Cloud DNS zone, KMS keys, Secret Manager versions, Artifact Registry (≈ 3 GB), GCS (uploads, backups) | $0.003 | $2 | $1.50 |
| **Nodes, Spot**: 2 × (`e2-standard-4` ≈ $0.045 + 30 GB boot disk $0.0045 + Spot VM IP $0.0025) | $0.104 | $76 | $52 |
| *Nodes, on demand: 2 × (≈ $0.147 + $0.0045 + IP $0.005)* | *$0.313* | *$228* | *$158* |
| Internet egress (≈ $0.12/GB, ≈ 15 GB of demo traffic), Cloud Logging and Trace (free tiers) | — | $2 | $2 |
| **Total, Spot, running all the time** | **$0.141** | **≈ $105** | **≈ $73** |
| **Total, on demand, running all the time** | **$0.350** | **≈ $257** | **≈ $179** |
| **Spot, with 3 demo days on demand** (72 h) | | | **≈ $88** |
| **Spot, nodes up only while demoing/preparing** (≈ 60 h over 3 weeks, [below](#pay-only-while-demoing)), of which 3 demo days of 8 h on demand | | | **≈ $32** |

Within the ≈ $100 target in every Spot case. Not in the table: an external provider's own plan (Layerbase bills flat
monthly plans; Google's credit doesn't pay it) and the egress to it (below). Budget alert:
[§ 1](#1-project-billing-budget).

**External data services cost egress.** Layerbase runs on Hetzner in Ashburn, Virginia: everything the cluster sends
to it (queries, writes, cache traffic) is internet egress from Montréal (≈ $0.12/GB) and every round trip adds ≈ 20–25
ms. A demo moves little data (≈ $1–5), but a page that makes 30 Valkey/Postgres calls gets ≈ 0.7 s slower. Keep
Postgres in the cluster in any case (Layerbase's Postgres has no PostGIS). Without Cloud NAT the cluster has no fixed
egress address to allow-list at a provider.

## Pay only while demoing

The nodes are ≈ 75 % of the bill. Between demos, scale the pool to 0; volumes, data, the load balancer and DNS stay
(≈ $0.04/h ≈ $0.90/day while down):

```sh
make dryrun-down     # autoscaling off, node pool general → 0 nodes
make dryrun-up       # → 2 nodes (NODES=2), autoscaling back to Terraform's bounds; pods and data back in ≈ 5 minutes
make dryrun-status   # machine type, Spot or not, autoscaling, running nodes
```

(`scripts/dryrun-nodes.sh` reads the cluster, zone and project from `terraform output kubernetes`.) Bring the nodes
up an hour before a demo and run the smoke test ([§ 14](#14-smoke-test)); Let's Encrypt certificates and the
Postgres data survive. A `terraform apply` while the pool is down puts it back to `node_count` (2).

## What you need

- `gcloud` (Google Cloud CLI), `terraform` 1.9+, `kubectl`, `helm` 3.14+, `jq`, `yq`, Docker with buildx, Java 25 (the
  Jib builds), `openssl`, `htpasswd` (or Docker for it).
- A Google account with a billing account (the free trial's $300 credit). **Free-trial limit:** at most 8 vCPUs in the
  project — two `e2-standard-4` nodes exactly, so the pool can neither grow to 3 nor surge-upgrade. Upgrading the
  billing account to a paid one keeps the remaining credit and lifts the limit (then billing does not stop by itself
  when the credit runs out — keep the budget alert).
- Control of DNS for `northline.ca` (to delegate `demo.northline.ca`).
- A Stripe account in **test mode** (keys `sk_test_…`, `pk_test_…`) — [stripe.md](stripe.md).
- Optional: a SendGrid key for real emails ([email.md](email.md)); without it emails are only logged.

Everything below runs from the repository root; `PROJECT` is the new project's id.

## Steps from an empty project

### 1. Project, billing, budget

```sh
export PROJECT=northline-dryrun-$RANDOM REGION=northamerica-northeast1 ZONE=northamerica-northeast1-a
gcloud auth login && gcloud auth application-default login
gcloud projects create "$PROJECT" --name "Northline dry run"
gcloud billing accounts list                                   # the free-trial billing account id
gcloud billing projects link "$PROJECT" --billing-account "$BILLING_ACCOUNT"
gcloud config set project "$PROJECT"
# Budget: e-mails the billing admins at 50 %, 90 % and 100 % of $100.
gcloud services enable billingbudgets.googleapis.com
gcloud billing budgets create --billing-account "$BILLING_ACCOUNT" --display-name "northline-dryrun" \
  --budget-amount 100USD --filter-projects "projects/$PROJECT" \
  --threshold-rule percent=0.5 --threshold-rule percent=0.9 --threshold-rule percent=1.0
```

### 2. APIs

Terraform enables what the stack uses (`stacks/gcp` `local.services`); these two must be on before it can:

```sh
gcloud services enable serviceusage.googleapis.com cloudresourcemanager.googleapis.com
```

### 3. Terraform state

For a two-to-three-week environment local state is acceptable (keep `terraform.tfstate` on one machine; it holds the
generated data-service passwords). A shared state bucket instead: `infra/terraform/bootstrap/gcp`, then
`backend.hcl` from `backend.hcl.example` ([infrastructure.md § 2](infrastructure.md)).

### 4. `terraform apply`

```sh
cd infra/terraform/envs/gcp/dryrun
cp terraform.tfvars.example terraform.tfvars   # project_id, region, zone, admin_principals, api_allowed_cidrs
terraform init && terraform apply
```

It creates the VPC (no Cloud NAT: public nodes), the zonal cluster, the KMS keys, Artifact Registry, the uploads and Postgres backup
buckets, the Cloud DNS zone, every Secret Manager secret (the in-cluster data services' credentials **generated**,
the app secrets **empty**), and the workload identities (apps, External Secrets, cert-manager, external-dns, the
Collector, the Postgres backups). About 15 minutes (the cluster). No Cloud SQL, Memorystore, Managed Kafka or Elastic
Cloud: `data_services` replaces them.

```sh
terraform output -json helm_values > ../../../../../northline-dryrun-values.json   # northline chart
terraform output -json data_values > ../../../../../northline-data-values.json     # northline-data chart
terraform output -json gitops_addon_values > ../../../../../addon-values.json      # add-ons' identities
terraform output -json kubernetes | jq -r .kubeconfig_command | sh                 # kubectl credentials
cd ../../../../..
```

### 5. Optional: external data services

Per service, `data_services` in `terraform.tfvars` chooses `in-cluster` (default) or `external`:

```hcl
data_services = {
  cache  = { mode = "external", host = "VALKEY_HOST", port = 6380, ssl = true, username = "default" }
  search = { mode = "external", url = "https://MEILISEARCH_HOST" }
  # postgres: only a provider with PostGIS, citext and pgcrypto — Layerbase has no PostGIS
  # kafka:    { mode = "external", bootstrap = "HOST:9093", security_protocol = "SASL_SSL", sasl_mechanism = "PLAIN" }
}
```

1. Create the database(s) at the provider (Layerbase: Valkey, Meilisearch), TLS on and a strong password. There is
   no fixed egress address to allow-list (no Cloud NAT; the nodes' ephemeral IPs change with every node), so the
   provider must accept connections from anywhere — acceptable for fake data only.
2. `terraform apply` again: the endpoints go into `configEnv`, the external service is left out of
   `northline-data`, and its credential secret is created **empty**.
3. Put the credentials in (names: `terraform output -json data_stores | jq '.data_services.secrets_to_fill'`):
   ```sh
   printf %s 'VALKEY_PASSWORD' | gcloud secrets versions add northline-dryrun-redis-password --data-file=-
   printf %s 'MEILISEARCH_KEY' | gcloud secrets versions add northline-dryrun-meilisearch-api-key --data-file=-
   # external Kafka: the whole JAAS line, then add KAFKA_SASL_JAAS_CONFIG to externalSecrets.optionalKeys
   ```
4. Regenerate the two values files ([§ 4](#4-terraform-apply)).

### 6. App secrets

Terraform created them empty. The ones the dry run's pods require (each ExternalSecret fails while one is empty):

```sh
put() { printf %s "$2" | gcloud secrets versions add "northline-dryrun-$1" --data-file=- --project "$PROJECT"; }
bcrypt() { docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 "" "$1" | tr -d ':\n' | sed 's/^/{bcrypt}/'; }
put totp-key "$(openssl rand -base64 32)"
put webhook-secret-key "$(openssl rand -base64 32)"
for c in studio consumer console; do
  s=$(openssl rand -base64 32); put "$c-bff-secret" "$s"; put "$c-bff-secret-hash" "$(bcrypt "$s")"
done
put email-unsubscribe-key "$(openssl rand -base64 32)"
# Stripe TEST mode (docs/runbooks/stripe.md): keys from Developers › API keys; the webhook secrets after step 12
put stripe-secret-key 'sk_test_REPLACE'
put stripe-publishable-key 'pk_test_REPLACE'
put stripe-webhook-secret 'whsec_REPLACE'            # replaced in step 12
put stripe-connect-webhook-secret 'whsec_REPLACE'    # replaced in step 12
```

`db-app-password`, `redis-password` and `meilisearch-api-key` already hold generated values (in-cluster services).
Everything else (Google/Apple sign-in, Twilio, Google Maps, OpenRouter, push) stays empty: the dev profile uses its
fakes. Full inventory: [secrets.md](secrets.md).

### 7. Cluster add-ons

The same charts and values Argo CD uses elsewhere (`deploy/argocd/addons`), installed with Helm, one replica each:

```sh
A=deploy/argocd/addons
# External Secrets Operator (secrets.md § Setting it up)
helm repo add external-secrets https://charts.external-secrets.io
helm upgrade --install external-secrets external-secrets/external-secrets -n external-secrets --create-namespace \
  --version 0.20.4 -f $A/external-secrets/values.yaml -f <(jq '.["external-secrets"]' addon-values.json) --wait
# cert-manager (Let's Encrypt)
helm repo add jetstack https://charts.jetstack.io
helm upgrade --install cert-manager jetstack/cert-manager -n cert-manager --create-namespace --version v1.18.2 \
  -f $A/cert-manager/values.yaml -f <(jq '.["cert-manager"]' addon-values.json) --set webhook.replicaCount=1 --wait
# Envoy Gateway (Gateway API) + the GatewayClass and the GKE EnvoyProxy
helm upgrade --install envoy-gateway oci://registry-1.docker.io/envoyproxy/gateway-helm -n envoy-gateway-system \
  --create-namespace --version v1.5.1 -f $A/envoy-gateway/values.yaml --set deployment.replicas=1 --wait
kubectl apply -f $A/envoy-gateway/manifests/common -f $A/envoy-gateway/manifests/gcp
kubectl -n envoy-gateway-system patch envoyproxy northline --type merge \
  -p '{"spec":{"provider":{"kubernetes":{"envoyDeployment":{"replicas":1}}}}}'
# external-dns (records in demo.northline.ca)
helm repo add external-dns https://kubernetes-sigs.github.io/external-dns/
helm upgrade --install external-dns external-dns/external-dns -n external-dns --create-namespace --version 1.18.0 \
  -f $A/external-dns/values.yaml -f $A/external-dns/values-gcp.yaml -f <(jq '.["external-dns"]' addon-values.json) --wait
# CloudNativePG operator (the in-cluster Postgres)
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm upgrade --install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace --version 0.26.0 --wait
```

CloudNativePG 0.26 (operator 1.27) still has the in-tree `barmanObjectStore` backups this chart uses; a later
operator moves them to the Barman Cloud plugin — pin the version until the chart follows.

### 8. Build and push the images

```sh
export REGISTRY=$(terraform -chdir=infra/terraform/envs/gcp/dryrun output -json registry | jq -r .registry_url)
export IMAGE_TAG=$(git rev-parse --short=12 HEAD)
gcloud auth configure-docker "${REGISTRY%%/*}"
(cd server && ./gradlew jib -Pimage.registry="$REGISTRY" -Pimage.tag="$IMAGE_TAG" -Pimage.platforms=linux/amd64)
for app in studio consumer console; do
  docker buildx build web --target "$app" --platform linux/amd64 --provenance=false \
    --build-arg GIT_SHA="$(git rev-parse HEAD)" --build-arg IMAGE_TAG="$IMAGE_TAG" -t "$REGISTRY/$app:$IMAGE_TAG" --push
done
```

amd64 only (the nodes are `e2`); the tags are immutable in Artifact Registry, so a rebuild needs a new commit.
CI alternative: [deploy.md § Build and push](deploy.md#build-and-push).

### 9. In-cluster data services

```sh
kubectl create namespace northline-dryrun
helm upgrade --install northline-data deploy/helm/northline-data -n northline-dryrun -f northline-data-values.json --wait --timeout 15m
kubectl -n northline-dryrun get cluster.postgresql.cnpg.io,statefulsets,pods
kubectl -n northline-dryrun get backups.postgresql.cnpg.io     # the immediate first backup: completed
```

Postgres comes up with the database `northline`, its owner `northline_app` (password = the generated
`db-app-password`) and the extensions `postgis`, `citext`, `pgcrypto`. Kafka has no topics yet: the next step's Job
creates them.

### 10. Install Northline

```sh
helm upgrade --install northline deploy/helm/northline -n northline-dryrun \
  -f deploy/helm/northline/values-gcp.yaml -f deploy/helm/northline/values-dryrun.yaml -f northline-dryrun-values.json \
  --set global.image.registry="$REGISTRY" --set global.image.tag="$IMAGE_TAG" --wait --timeout 20m
```

Order: Kafka topics Job and migrations Job (pre-install hooks: Flyway `db/migrations`, then the categories) → the
Deployments → the OAuth clients Job. Check: `kubectl -n northline-dryrun get pods,jobs,externalsecrets`
(every ExternalSecret `SecretSynced`; a `SecretSyncedError` names the empty secret from step 6).

### 11. DNS and TLS

```sh
terraform -chdir=infra/terraform/envs/gcp/dryrun output -json dns | jq -r '.name_servers[]'
```

Delegate `demo.northline.ca` from wherever `northline.ca` is hosted: one NS record set `demo` with those four
servers ([edge.md § DNS delegation](edge.md#dns-delegation)), plus `demo.northline.ca. CAA 0 issue "letsencrypt.org"`.
Then external-dns writes the host records and cert-manager gets one certificate per host:

```sh
kubectl -n northline-dryrun get gateway northline        # PROGRAMMED True, an address
kubectl -n northline-dryrun get certificates             # all Ready (a few minutes after DNS resolves)
dig +short studio.demo.northline.ca                      # the Gateway address
curl -sI https://studio.demo.northline.ca | head -1      # 200, with strict-transport-security
```

### 12. Stripe webhooks (test mode)

Stripe Dashboard (test mode) › Developers › Webhooks: an endpoint `https://api.demo.northline.ca/api/v1/webhooks/stripe`
(account events) and one `https://api.demo.northline.ca/api/v1/webhooks/stripe/connect` (connected accounts), the
events of [stripe.md](stripe.md); put their signing secrets into `stripe-webhook-secret` and
`stripe-connect-webhook-secret`, then `kubectl -n northline-dryrun annotate externalsecret --all force-sync=$(date +%s) --overwrite`
and `kubectl -n northline-dryrun rollout restart deployment northline-api`.

### 13. Demo data

The dev seed (`db/seed-dev`, the personas with published passwords and authenticator keys) **never** goes to a
deployed database — DbTool and the api refuse it (`DevSeedGuard`, [deploy.md § Migrations](deploy.md#migrations-s-16)),
and on a public URL its known credentials would be anyone's. Demo data is made through the product, with obviously
fake people and businesses:

1. **First console admin.** Register on `https://demo.northline.ca`, set up a second factor, then once, from a pod in
   the cluster (`kubectl -n northline-dryrun exec -it northline-pg-1 -- psql -d northline`):
   `INSERT INTO identity.platform_roles (user_id, role) VALUES ('USER_ID', 'staff'), ('USER_ID', 'admin');`
   (the user id from `select id from identity.users where email = '…'`). More staff from the console's Team screen.
2. **Market.** In the console, open the market the demo uses for onboarding (pilot), as in
   [pilot-onboarding.md](pilot-onboarding.md).
3. **Businesses.** In the Studio, onboard two or three fake businesses (a provider, a shop, a kitchen) with the demo
   storyline's listings, menus and photos; business registries use their fixtures, Stripe Connect onboarding uses
   Stripe's test data (identity and bank: Stripe's documented test values).
4. **Customers.** Register one or two fake customers; book a service, buy an item, order food with the test card
   `4242 4242 4242 4242`.

Phone codes appear in the auth log (`kubectl -n northline-dryrun logs deploy/northline-auth | grep -i code`) and
emails in the api/worker logs, until Twilio / SendGrid are configured. Keep the demo storyline (who, what, which
order) in the demo script, not in this repository.

### 14. Smoke test

```sh
curl -s https://auth.demo.northline.ca/.well-known/openid-configuration | jq -r .issuer   # https://auth.demo.northline.ca
for h in studio console; do curl -s -o /dev/null -w "%{http_code} $h\n" "https://$h.demo.northline.ca"; done
curl -s -o /dev/null -w "%{http_code} consumer\n" https://demo.northline.ca
kubectl -n northline-dryrun exec deploy/northline-api -- wget -qO- localhost:8080/actuator/health/readiness
kubectl -n northline-dryrun get jobs                                      # migrate, kafka-topics, search-indices, oauth-clients Complete
```

Then by hand: sign in to the Studio (passkey or password + code), open each Studio section, search on the consumer
site (Meilisearch), a checkout with the test card, the console's overview. The e2e suite can also run against it
(`make e2e-target`, [e2e.md](e2e.md)) once its personas exist there.

### 15. Observability

Logs: Google Cloud console › Logging (`resource.labels.namespace_name="northline-dryrun"`) — the pods' stdout, always
on. Node and pod CPU/memory: Monitoring › Kubernetes (GKE system metrics, free). Traces only with the Collector flag
(Cloud Trace). No application metrics or Grafana dashboards in the dry run; the full stack is
[observability.md](observability.md).

## Running it day to day

- **Between demos:** `make dryrun-down`; before the next one `make dryrun-up` ([Pay only while demoing](#pay-only-while-demoing)).
- **Demo day:** `spot_nodes = false` in `terraform.tfvars`, `terraform apply` the evening before (the pool is
  re-created: about 10 minutes of restarts), smoke test; back to `true` afterwards.
- **Upgrade:** new `IMAGE_TAG`, step 8, then the step 10 command.
- **Postgres backups:** `kubectl -n northline-dryrun get backups.postgresql.cnpg.io`; restore = a new CloudNativePG
  Cluster bootstrapped `recovery` from the bucket. Not needed for fake data; it is there to rehearse prod's shape.
- **Debugging session:** `--set observability.collector.enabled=true` on the step 10 command (traces in Cloud Trace).

## Teardown (stop billing)

```sh
helm uninstall northline northline-data -n northline-dryrun     # releases the load balancer and the volumes' claims
kubectl delete namespace northline-dryrun envoy-gateway-system  # the remaining LB Service and PVCs
terraform -chdir=infra/terraform/envs/gcp/dryrun destroy        # cluster, buckets (force_destroy), DNS zone, secrets
gcloud projects delete "$PROJECT"                               # everything left: disks, KMS key rings (they cannot be deleted)
```

Remove the `demo` NS delegation from `northline.ca`. Check the billing report the next day: costs stop within hours;
a deleted project is recoverable for 30 days. Cloud KMS key versions are scheduled for destruction (no charge once
destroyed).

## Cheaper still: one VM (being researched)

A single Compute Engine VM running k3s (all of the above on one `e2-standard-4`, a static IP, no load balancer)
would cost roughly half of the Spot cluster. It is **being researched and out of scope here**: no Terraform or charts
for it yet, and it drops the GKE pieces this dry run rehearses (Workload Identity, the GKE load balancer, node pools).

## Open items

- **Data-service placement is the owner's call** (DECISIONS 2026-10-18): in the cluster (default here) or external.
  Layerbase was considered: no PostGIS (so never for Postgres), and it runs on Hetzner in Ashburn — every query
  crosses providers (egress, ≈ 20–25 ms per round trip). It is supported per service as `external`.
- **Meilisearch adapter:** in place ([search.md § 11](search.md#11-meilisearch-provider-meilisearch)) with the variables
  above; the `searchIndices` Job creates `listings_en` / `listings_fr` on Meilisearch. Tested against the in-cluster
  image (`getmeili/meilisearch:v1.15.2`) and v1.28.0, never against the cluster itself or Layerbase. The apps use the
  in-cluster master key as `MEILISEARCH_API_KEY`; with an external provider, give the worker a key limited to
  `listings_*` (search.md § 11.2). If the database is ever restored or the provider changed, run the full reindex
  (`searchReindex.runId`).
- **Images:** the PostGIS image must carry barman-cloud for the backups (CloudNativePG's PostGIS images; check the tag
  `ghcr.io/cloudnative-pg/postgis:17-3.5` before the first apply). Versions pinned in `deploy/helm/northline-data/values.yaml`.
- Never applied to a real project; prices in the table are list prices to re-check.
