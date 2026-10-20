# OVHcloud runbook — the dry run in Beauharnois (`ovh-vps` or `ovh-mks`)

The dry run ([dryrun-gcp.md](dryrun-gcp.md) is the Google Cloud one) on OVHcloud, with **everything in one Canadian
location** — Beauharnois, Québec (BHS) — so nothing pays egress between components. Two shapes, selectable:

- **`ovh-vps`** — the cheapest: one VPS (8 vCore / 24 GB) running k3s, every data service on it, ≈ $25–30 for the
  month.
- **`ovh-mks`** — production-shaped: OVHcloud Managed Kubernetes (free control plane) with two nodes, OVHcloud Managed
  PostgreSQL 17, ≈ $150–215 a month.

Fake data only, the `dev` Spring profile, the same two charts and `values-dryrun.yaml` as Google Cloud. Owner decision
and what was left open: [DECISIONS.md § 2026-10-19](../DECISIONS.md). Comparison with Google Cloud:
[README § Google vs OVH](README.md#google-vs-ovh).

> **Status (2026-10-19): never applied.** Nothing here has run against a real OVHcloud account. The Terraform roots
> (`infra/terraform/envs/ovh/vps`, `infra/terraform/envs/ovh/mks`) pass `terraform validate`, `tflint` and an offline
> plan against a mocked `ovh/ovh` provider (2.22, built from source here); both charts render and validate offline with
> the OVHcloud values (`deploy/helm/validate.sh`); the scripts parse with bash 3.2 and were run against stubs only.
> The `ovh` pipeline (S-155, [§ Today](#today-from-a-new-ovh-account-to-a-running-demo)) has never run either.
> **Every price below is unverified** (from search-engine excerpts; the vendor sites were unreachable) — check each in
> the OVHcloud console before ordering.

Other environments: [dryrun-gcp](dryrun-gcp.md), [dev](dev.md), [staging](staging.md), [prod](prod.md) ·
[overview](README.md)

## Today: from a new OVH account to a running demo

The shortest path, through the **`ovh` pipeline** (`.github/workflows/ovh.yml`, S-155): you click in the OVHcloud
console, run one set-up command on your Mac, then start the pipeline from GitHub. `ovh-vps` is the target for a first
demo (≈ $25–30 for the month); `ovh-mks` works the same way with the differences noted. About an hour end to end, most
of it waiting.

> **Never run against a real account yet.** The pipeline, `scripts/ovh/gh-setup.sh`, the state bucket bootstrap and
> the runner's firewall rule were checked offline only (actionlint, bash 3.2 parsing, shellcheck, stubbed runs of the
> scripts, `terraform test` with a mocked provider, `helm template | kubeconform`). Expect to fix small things on the
> first run; each step below says where it reports problems.

### 1. Account and project (console)

1. Open **ca.ovhcloud.com** (the Canadian site: prices in CA$, Canadian taxes, the `ovh-ca` API) › *Create an
   account* (or log in) › *Billing* › *Payment methods*: add a card.
2. **Public Cloud** › *Create a project* › name `northline-dryrun`, no voucher, accept the terms. Copy the project's
   id (32 hex characters, under the project's name, top left): this is `OVH_SERVICE_NAME`.
3. In the project: **Object Storage** › *S3 users* (or *Users & Roles*) › *Add user* › description `terraform-state`,
   role **ObjectStore operator**. Copy its **access key** and **secret key** when they are shown (the secret is shown
   once): `TF_STATE_ACCESS_KEY` / `TF_STATE_SECRET_KEY`. The pipeline creates the state bucket with them on its first
   run (versioning on); nothing else uses this user.

### 2. API token (browser)

Open this address while signed in (it pre-fills the rights; pick a validity covering the demo period, e.g. 30 days,
and no IP restriction — the pipeline's runners have new addresses every run):

```text
https://ca.api.ovh.com/createToken/?GET=/cloud/*&POST=/cloud/*&PUT=/cloud/*&DELETE=/cloud/*&GET=/vps/*&GET=/ip/*&POST=/ip/*&PUT=/ip/*&DELETE=/ip/*
```

`/cloud/*`: Object Storage, its users, and for `ovh-mks` the cluster, node pool, private network and database;
`GET /vps/*`: read the VPS; `/ip/*`: the Network Firewall in front of the VPS. It returns three values —
**application key**, **application secret**, **consumer key** (`OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`,
`OVH_CONSUMER_KEY`). Revoke them at teardown (§ 8).

### 3. The VPS (`ovh-vps` only; console)

1. On your Mac, a key if you have none: `ssh-keygen -t ed25519` (Enter three times), then `cat ~/.ssh/id_ed25519.pub`.
2. **Bare Metal Cloud** › *Virtual Private Servers* › *Order*. Choose by the specs, not the name (it changes between
   ranges): **8 vCores, 24 GB RAM, 200 GB NVMe**. Location **Beauharnois (BHS), Canada**. Image **Ubuntu 24.04**.
   Paste the public key from step 1 in *SSH key*. No options. Monthly, no commitment. Pay.
3. When it is delivered (minutes; an email): note its **service name** (`vps-xxxxxxxx.vps.ovh.ca`, the VPS's page) —
   `OVH_VPS_SERVICE_NAME` — and its **IPv4**. Check that `ssh ubuntu@IPv4` works from your Mac.

`ovh-mks` orders nothing by hand: the pipeline's `apply` creates the cluster, nodes and database.

### 4. GitHub set-up (your Mac, once per target)

Needs the GitHub CLI signed in with admin rights on the repository (`brew install gh`, `gh auth login`). Environments
on a private repository need a paid GitHub plan; if the command says GitHub refused the environment, use
[the local alternative](#the-local-alternative-no-github-actions).

```sh
make ovh-gh-setup TARGET=ovh-vps
```

It creates the **Environment** `ovh-vps` (Settings › Environments) with **you as required reviewer** and deployments
allowed from `develop` and `main` only, then sets every secret and variable the pipeline reads. It asks for each
required one (secrets are typed hidden) — or takes them from `.ovh/ovh-vps.env` if you put them there (`KEY=value`
lines; the file is git-ignored). For `ovh-vps` it also makes a **deploy key** for the pipeline
(`.ovh/ovh-vps-deploy-key`), installs it on the VPS over your own SSH access, and records the VPS's host key
(compare the fingerprint it prints with the console's KVM if you want to be thorough). Re-run it to change a value.

What ends up where ([§ Pipeline secrets and variables](#pipeline-secrets-and-variables) has the full list):

| you have | goes into (environment `ovh-vps` / `ovh-mks`) |
|---|---|
| project id | variable `OVH_SERVICE_NAME` (and `OVH_ENDPOINT` = `ovh-ca`) |
| the three API values | secrets `OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`, `OVH_CONSUMER_KEY` |
| the state user's keys | secrets `TF_STATE_ACCESS_KEY`, `TF_STATE_SECRET_KEY`; variable `TF_STATE_BUCKET` (proposed: `northline-tfstate-` + the project id's first 8 characters) |
| the VPS's service name, your IP | variables `OVH_VPS_SERVICE_NAME`, `OVH_ADMIN_CIDRS` (proposed: your current address/32) |
| the domain of the demo | variable `OVH_DOMAIN` (`demo.northline.ca` by default; any zone you control — every host moves under it) |
| a GitHub classic token with **read:packages** only (Settings › Developer settings › Tokens (classic)) | secret `REGISTRY_PULL_TOKEN` — the cluster pulls the images from ghcr.io with it. Without it the pipeline uses the run's own token, which expires when the run ends (a restarted pod on a node without the image then can't pull) |
| Stripe test keys, Twilio (optional, § 6) | secrets `STRIPE_*`, `SMS_AUTH_TOKEN`; variables `SMS_ACCOUNT_ID`, `SMS_FROM` |

The app's own secrets (`TOTP_KEY`, `KMS_LOCAL_KEY`, the BFF secrets, the data-service passwords) need nothing from
you: the first deploy generates them **once** and keeps them in the cluster (`secret/northline-generated`); later runs
reuse them. Back them up after the first deploy:
`kubectl -n northline-dryrun get secret northline-generated -o yaml > .ovh/ovh-vps-generated.yaml`.

### 5. Run the pipeline (GitHub)

**Actions** › **ovh** › **Run workflow** › branch `develop`, then, in order:

| run | target | action | other inputs | what happens |
|---|---|---|---|---|
| 1 | `ovh-vps` | `plan` | — | approve the run (*Review deployments* › `ovh-vps` › Approve); the summary lists what would be created: 2 buckets, 2 S3 users, the Network Firewall and its rules |
| 2 | `ovh-vps` | `provision-and-deploy` | `build-images` on | approve; Terraform applies, the seven images are built and pushed to `ghcr.io/<owner>/northline` (≈ 20 min, in parallel), then the deploy job: the runner's address is let through the Network Firewall for this run only, k3s is installed over SSH (idempotent), the Secrets, cert-manager, Envoy Gateway, CloudNativePG, `northline-data` and `northline` are installed (≈ 15 min the first time), rollouts are waited for and the smoke test runs; the runner's firewall rule is removed at the end |

The deploy job's summary prints the **public address** (the VPS's IPv4; on `ovh-mks` the load balancer's), the DNS
records to create and the URLs. The HTTPS part of the smoke test shows *pending* until the records exist (§ 7);
after creating them, run **`deploy`** again with `build-images` off (≈ 5 min): it then checks auth's OpenID
configuration, the Studio's and the consumer's sign-in pages, the consumer home and the console over HTTPS. A
failing step names what failed; the logs of a pod: run the [local alternative](#the-local-alternative-no-github-actions)'s
`kubectl` commands from your Mac.

Later: `deploy` after each change (the images are tagged with the commit's short sha; `image-tag` + `build-images`
off redeploys an existing tag), `down` / `up` between demos (§ [Between demos](#between-demos)), `plan` / `apply`
after changing a variable. `ovh-mks` is the same with `target = ovh-mks` (its `apply` takes ≈ 15–25 minutes).

Every job that touches the account — `plan` included, because the API credentials live in the environment — waits
for your approval; a run of one target never overlaps another run of the same target. The repository's other
workflows are manual too, and **GitHub Actions minutes may be depleted**: the image build is the expensive part (≈ 20
runner-minutes). Without minutes, use the local alternative below.

### 6. Stripe and Twilio — optional for a first demo

Under the `dev` profile:

- **Without Stripe** (no `STRIPE_SECRET_KEY`): the api uses its fake payment gateway, and the deploy switches
  identity and age checks to the local fakes. Everything starts; but the fakes' outcome pages exist only under
  `local`, so **an owner can't finish identity verification** (onboarding stops there) and age-restricted items
  can't be bought. Add the test keys later ([§ Stripe and Twilio](#stripe-and-twilio)), re-run `make ovh-gh-setup`,
  then `deploy`.
- **Without Twilio**: **no one can sign up** — every registration verifies a phone, and under `dev` the stand-in
  withholds the code ([demo.md § 2](demo.md#2-before-the-first-run-on-the-dry-run)). The sites, the sign-in pages and
  the (empty) catalogue load; that is a deployment check, not a demo. A Twilio trial account (verified numbers only)
  is enough: `SMS_AUTH_TOKEN` (secret), `SMS_ACCOUNT_ID`, `SMS_FROM` (variables).
- Emails are written to the api's log without SendGrid (`EMAIL_API_KEY`).

Then the first console admin and the demo data: [§ Demo data](#demo-data).

### 7. DNS

At whoever hosts the zone (`OVH_DOMAIN`), the records from the deploy summary:

```text
demo.northline.ca.    300 IN A   IP
*.demo.northline.ca.  300 IN A   IP
demo.northline.ca.        IN CAA 0 issue "letsencrypt.org"
```

(with your domain and the printed address). Let's Encrypt issues each host's certificate a few minutes after the
records resolve; then `deploy` again for the HTTPS smoke test.

### 8. Teardown

1. **Pause** between demos: action `down` (`ovh-mks`: also the nodes → 0; `ovh-vps` saves nothing — monthly billing).
2. **Delete**: action `destroy` with `confirm-destroy` = the target's name. It removes the releases (on `ovh-mks` the
   load balancer and volumes with them), empties the buckets (fake data) and runs `terraform destroy`.
3. `ovh-vps`: console › Bare Metal Cloud › the VPS › **Terminate** (billing stops at the end of the paid month).
4. Remove the DNS records; revoke the API token (*ca.api.ovh.com* › the token, or the console's API keys page);
   delete the state user and bucket and the Public Cloud project if nothing else lives there; delete the GitHub
   Environment (Settings › Environments) and the `.ovh/` files; check the next invoice.

### The local alternative (no GitHub Actions)

The same steps from your Mac with `make` — the pipeline only wraps them. Tools: [§ What you need](#what-you-need).

```sh
make ovh-secrets-init TARGET=ovh-vps            # .ovh/ovh-vps.env: generated secrets; add Stripe/Twilio if you have them
export OVH_ENDPOINT=ovh-ca OVH_APPLICATION_KEY=… OVH_APPLICATION_SECRET=… OVH_CONSUMER_KEY=…
# Optional: the pipeline's shared state instead of a local one (MIGRATE=1 moves an existing local state there).
export TF_STATE_BUCKET=… TF_STATE_ACCESS_KEY=… TF_STATE_SECRET_KEY=…
export AWS_ACCESS_KEY_ID=$TF_STATE_ACCESS_KEY AWS_SECRET_ACCESS_KEY=$TF_STATE_SECRET_KEY
make ovh-tf-init TARGET=ovh-vps
cp infra/terraform/envs/ovh/vps/terraform.tfvars.example infra/terraform/envs/ovh/vps/terraform.tfvars   # fill it in
terraform -chdir=infra/terraform/envs/ovh/vps apply
make ovh-bootstrap                               # k3s over SSH, kubeconfig + tunnel
export KUBECONFIG=~/.kube/northline-ovh-vps
# images: § Images (docker login ghcr.io, Jib + buildx), then
export REGISTRY=ghcr.io/ORG/northline IMAGE_TAG=$(git rev-parse --short=12 HEAD)
make deploy TARGET=ovh-vps                       # OVH_DOMAIN=… for a domain other than demo.northline.ca
make ovh-smoke TARGET=ovh-vps
```

Use one or the other for a given target, not both at once (one state, no locking). A deploy from the Mac uses the
file's generated secrets: if the pipeline deployed first, copy them from `secret/northline-generated` into the file
(or set the same values in the environment) so the two don't fight over `TOTP_KEY` and `KMS_LOCAL_KEY`.

### Pipeline secrets and variables

Per environment (`ovh-vps`, `ovh-mks`); `make ovh-gh-setup` sets them. **R** = required, **O** = optional.

| name | kind | | targets | what |
|---|---|---|---|---|
| `OVH_ENDPOINT` | variable | O | both | `ovh-ca` (default) |
| `OVH_SERVICE_NAME` | variable | R | both | Public Cloud project id |
| `OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`, `OVH_CONSUMER_KEY` | secrets | R (or the next row) | both | the API token |
| `OVH_CLIENT_ID`, `OVH_CLIENT_SECRET` | secrets | instead of the token | both | an OAuth2 service account |
| `TF_STATE_BUCKET` | variable | R | both | the state bucket (created by the first run) |
| `TF_STATE_ACCESS_KEY`, `TF_STATE_SECRET_KEY` | secrets | R | both | its S3 user's keys |
| `OVH_DOMAIN` | variable | O | both | zone of the public hosts (default `demo.northline.ca`) |
| `OVH_BUCKET_SUFFIX` | variable | O | both | if the bucket names are taken |
| `OVH_TFVARS` | variable | O | both | extra Terraform variables as HCL (e.g. `data_services`, `managed_postgres`) |
| `OVH_VPS_SERVICE_NAME` | variable | R | ovh-vps | the VPS (`vps-….vps.ovh.ca`) |
| `OVH_ADMIN_CIDRS` | variable | R | ovh-vps | who may SSH besides the pipeline (≤ 3, space-separated) |
| `OVH_EDGE_FIREWALL` | variable | O | ovh-vps | `true` (default) / `false` |
| `OVH_VPS_SSH_PRIVATE_KEY` | secret | R | ovh-vps | the pipeline's deploy key (made by gh-setup) |
| `OVH_VPS_KNOWN_HOSTS` | secret | R | ovh-vps | the VPS's host key line (gh-setup; without it the run trusts the key on first sight, with a warning) |
| `OVH_API_ALLOWED_CIDRS` | variable | O | ovh-mks | who may reach the Kubernetes API (the runner is added for each run) |
| `OVH_NODE_COUNT`, `OVH_NODE_FLAVOR` | variables | O | ovh-mks | `2`, `b3-16` |
| `REGISTRY_PULL_TOKEN` | secret | O (recommended) | both | classic token, `read:packages` |
| `REGISTRY_PULL_USERNAME` | variable | O | both | that token's owner (default: the run's actor) |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET` | secrets | O | both | Stripe **test** mode |
| `SMS_AUTH_TOKEN` | secret | O | both | Twilio |
| `SMS_ACCOUNT_ID`, `SMS_FROM` | variables | O | both | Twilio |
| `EMAIL_API_KEY`, `KAFKA_SASL_JAAS_CONFIG` | secrets | O | both | SendGrid; an external Kafka |
| `TOTP_KEY`, `WEBHOOK_SECRET_KEY`, `EMAIL_UNSUBSCRIBE_KEY`, `KMS_LOCAL_KEY`, `STUDIO_/CONSUMER_/CONSOLE_BFF_SECRET` (+ `_HASH`), `DB_PASSWORD`, `REDIS_PASSWORD`, `MEILISEARCH_API_KEY` | secrets | O | both | only to impose values; otherwise generated once in the cluster |

Repository level (not per environment; the image build job has no environment): variable `REGISTRY` (a registry
other than ghcr.io, e.g. an OVHcloud Managed Private Registry) with secrets `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`
to push; `MAVEN_MIRROR_*` as for [deploy.yml](deploy.md).

## Contents

- [Today: from a new OVH account to a running demo](#today-from-a-new-ovh-account-to-a-running-demo) — the pipeline
  ([secrets and variables](#pipeline-secrets-and-variables), [local alternative](#the-local-alternative-no-github-actions))
- [The two shapes](#the-two-shapes)
- [Cost estimate](#cost-estimate)
- [What you need](#what-you-need)
- [Account and API credentials](#account-and-api-credentials)
- [ovh-vps step by step](#ovh-vps-step-by-step) (V1–V10)
- [ovh-mks step by step](#ovh-mks-step-by-step) (M1–M8)
- [Secrets](#secrets)
- [Images](#images)
- [DNS](#dns)
- [Stripe and Twilio](#stripe-and-twilio)
- [Demo data](#demo-data)
- [Between demos](#between-demos)
- [Backups and restore](#backups-and-restore)
- [Logs and debugging](#logs-and-debugging)
- [Teardown](#teardown)
- [Open items and never run](#open-items-and-never-run)

## The two shapes

| piece | `ovh-vps` | `ovh-mks` |
|---|---|---|
| Location | VPS in Beauharnois (BHS); Object Storage in BHS (Public Cloud region `BHS5`) | Public Cloud region `BHS5` (Terraform `region`, Canadian regions only — `BHS5` today) |
| Kubernetes | **k3s** on the VPS (one node; Traefik off), installed over SSH by `scripts/ovh/bootstrap-k3s.sh` | **Managed Kubernetes**, free plan (control plane included, single zone), node pool `general`: 2 × `b3-16` (4 vCPU / 16 GB), hourly |
| Network | the VPS's public IPv4; **OVHcloud Network Firewall** in front (Terraform) and `ufw` on the host: SSH only from `admin_cidrs`, 80 and 443 from anywhere; the k3s API only through an SSH tunnel | private network (vRack) `10.60.0.0/24` with a gateway (`s`); the Kubernetes API optionally limited to `api_allowed_cidrs` |
| Postgres 17 + PostGIS | CloudNativePG on the VPS (`local-path` volume), daily base backup + WAL to Object Storage | **OVHcloud Managed PostgreSQL 17**, Essential `db1-4`, on the private network (`data_services.postgres.mode`: `managed`, `in-cluster` or `external`) |
| Valkey, Meilisearch, Kafka | on the VPS (`deploy/helm/northline-data`) | in the cluster (Cinder volumes), each `in-cluster` or `external` |
| Object storage | OVHcloud Object Storage (S3 API, `https://s3.bhs.io.cloud.ovh.net`), bucket `northline-ovh-vps-uploads` (+ `-pg-backups`), one S3 user per bucket | the same, `northline-ovh-mks-uploads` |
| Edge | Envoy Gateway; k3s's ServiceLB binds the VPS's ports 80/443; Let's Encrypt per host (cert-manager, HTTP-01) | Envoy Gateway (2 proxies) behind a public OVHcloud load balancer; Let's Encrypt per host |
| DNS | A records by hand → the VPS's IP | A records by hand → the load balancer's IP |
| Secrets | plain Kubernetes Secrets from `.ovh/ovh-vps.env` ([§ Secrets](#secrets)) | from `.ovh/ovh-mks.env` |
| Keys | `KMS_PROVIDER=local`: auth's signing keys in a file on a volume, the api seals with `KMS_LOCAL_KEY` | same |
| Replicas | 1 of everything | 2 of every stateless app; auth and the worker 1 |
| Spring profile | `dev` (as the GCP dry run: local fakes where no account exists, Stripe test mode) | `dev` |

Hosts are the dry run's (`values-dryrun.yaml`): `https://demo.northline.ca`, `studio.`, `auth.`, `console.`, `pages.`,
`api.` under `demo.northline.ca`. Only one target should own them at a time (they are the GCP dry run's too).

**Why not Traefik or ingress-nginx (k3s's default)?** The chart's edge (HTTPS listeners, HSTS, HTTP → HTTPS
redirects, certificates per host) is built and validated on Envoy Gateway; reusing it keeps one edge for every target.

**What differs from Google Cloud** — no secrets manager behind External Secrets (a local file and a script instead), no
key service the apps support (local keys: allowed under `dev`, refused by staging/prod), no external-dns (records by
hand), no cloud logging (kubectl logs), static S3 keys instead of workload identities.

## Cost estimate

**Unverified list prices** (USD, before GST/QST; from the owner's hosting research of 2026-10-18, § 5, itself from
search-engine excerpts). Confirm each one in the OVHcloud console (Canadian site, Beauharnois selected) before ordering.

### `ovh-vps`

| item | per month | notes |
|---|---|---|
| VPS 8 vCore / 24 GB / 200 GB NVMe in Beauharnois ("VPS-4" in the 2025/2027 range; check the specs, the names move) | ≈ $23–27 | **billed by the month** (paying a month even for three weeks); unlimited traffic; check the CA$ price and commitment terms |
| Object Storage (Standard, BHS): uploads and Postgres backups, well under 50 GB | under $1 | ≈ €7/TB-month; internal traffic included |
| Network Firewall, IPv4 | $0 | included |
| **Total** | **≈ $25–30** | for the month, whatever runs |

Fallback if that VPS is out of stock in BHS: two 6 vCore / 12 GB VPSs would need a two-node k3s — not scripted here.

### `ovh-mks`

| item | per month (730 h) | notes |
|---|---|---|
| Managed Kubernetes control plane, free plan | $0 | 99.5 % SLO, single zone |
| 2 × `b3-16` nodes, hourly | ≈ $95–150 | `monthly_billed = true` ≈ halves it but commits each node for the month |
| Managed PostgreSQL Essential `db1-4` (1 node, PG 17) | ≈ $51–63 | Business `db1-4` (2 nodes, HA) ≈ $160 |
| Public load balancer (size S, created for Envoy's Service) | unverified | check Public Cloud › Load Balancer prices |
| Gateway `s` (the private network's router) | unverified | small hourly charge |
| Block storage (Cinder high-speed) ≈ 20 GB: Valkey, Meilisearch, Kafka, auth keys | ≈ $2 | |
| Object Storage | $1–5 | |
| **Total** | **≈ $150–215 + LB + gateway** | Business PG: ≈ $255–315 |

Between demos `make ovh-down TARGET=ovh-mks` scales the nodes to 0 (the rest keeps billing).

## What you need

- `terraform` 1.9+, `kubectl`, `helm` 3.14+, `jq`, `ssh`, `openssl`, `htpasswd` (or Docker for it), Docker with buildx
  and Java 25 (the images), Node 22 (`make demo-data`). For the pipeline only the GitHub CLI `gh` (`make ovh-gh-setup`);
  the shared state from a Mac also needs the `aws` CLI (`make ovh-tf-init` creates the bucket with it).
- An **OVHcloud Canada** account with a payment method ([§ Account](#account-and-api-credentials)).
- Control of DNS for `northline.ca` (records under `demo.northline.ca`).
- A Stripe account in **test mode** ([stripe.md](stripe.md)) and a Twilio account ([README § SMS](README.md#sms-and-voice-codes-s-8)):
  under `dev` no one can register without real SMS codes ([demo.md § 2](demo.md#2-before-the-first-run-on-the-dry-run)).
- An image registry the cluster can pull from ([§ Images](#images)).

Everything below runs from the repository root.

## Account and API credentials

1. **Account.** Create an account on OVHcloud's **Canadian** site (ca.ovhcloud.com; the subsidiary decides the
   currency, the taxes and the API endpoint `ovh-ca`). Add a payment method (Billing › Payment methods).
2. **Public Cloud project** (both targets: Object Storage lives in one). Public Cloud › Create a project, name
   `northline-dryrun`. Its id (32 hex characters, top left of the project) is Terraform's `service_name`. A project
   costs nothing by itself. Check that `BHS5` is offered (Object Storage, Managed Kubernetes and, for `ovh-mks`,
   Managed Databases for PostgreSQL 17 with PostGIS).
3. **API credentials for Terraform.** Either an application key set (simplest) — create it on the Canadian API's
   token page with the rights below, for the duration of the dry run:

   ```text
   https://ca.api.ovh.com/createToken/
   rights: GET/POST/PUT/DELETE on /cloud/*   (Public Cloud: storage, users, kube, databases, network)
           GET on /vps/*                     (ovh-vps: read the VPS)
           GET/POST/PUT/DELETE on /ip/*      (ovh-vps: the Network Firewall)
   ```

   ```sh
   export OVH_ENDPOINT=ovh-ca OVH_APPLICATION_KEY=… OVH_APPLICATION_SECRET=… OVH_CONSUMER_KEY=…
   ```

   or an OAuth2 service account (Identity and Access Management › Service accounts) with a policy on the project:
   `export OVH_ENDPOINT=ovh-ca OVH_CLIENT_ID=… OVH_CLIENT_SECRET=…`. Keep them out of shell history and files in the
   repository; revoke them at teardown.
4. **Terraform state.** Local state is fine for a demo (it holds the S3 secret keys and, for `ovh-mks`, the database
   passwords — keep it on one machine; `*.tfstate` is git-ignored). Shared state: an Object Storage bucket as an S3
   backend, `backend.hcl.example` in each root.

## ovh-vps step by step

### V1. Order the VPS (console)

Bare Metal Cloud › Virtual Private Servers › Order: **8 vCore / 24 GB / 200 GB NVMe** (check the specs, not the plan
name), datacentre **Beauharnois (Canada)**, image **Ubuntu 24.04**, your SSH public key, no options needed (the daily
automated backup is included in that range per the research — unverified). Pay; it is ready in minutes. Note its
service name (`vps-….vps.ovh.ca`) and IPv4.

Why the console and not Terraform: provider 2.22 has an `ovh_vps` resource that orders through the order workflow,
but it needs the catalogue's plan code for the current range in Beauharnois and a default payment method, and an apply
commits a month's payment — neither could be checked from here. Terraform reads the ordered VPS instead.

### V2. Terraform: Object Storage and the firewall

```sh
cd infra/terraform/envs/ovh/vps
cp terraform.tfvars.example terraform.tfvars   # service_name, vps_service_name, admin_cidrs (curl -s ifconfig.me)/32
terraform init && terraform apply
terraform output vps            # ipv4, ssh (ubuntu@IP), edge_firewall
terraform output dns_records    # the two A records to create
cd ../../../../..
```

It creates the uploads and Postgres-backup buckets (default encryption on), one S3 user per bucket limited to it by
an S3 policy, and the Network Firewall on the VPS's IPv4: TCP replies, SSH from `admin_cidrs` (at most 3), 80, 443,
DNS/NTP replies, ICMP, everything else dropped. `edge_firewall = false` turns it off (the host firewall of V4 stays).

### V3. Secrets file

```sh
make ovh-secrets-init TARGET=ovh-vps     # writes .ovh/ovh-vps.env (mode 600)
$EDITOR .ovh/ovh-vps.env                 # Stripe test keys, Twilio, registry — § Secrets
```

### V4. k3s on the VPS

```sh
make ovh-bootstrap                       # HOST=ubuntu@IP to override terraform output vps.ssh
export KUBECONFIG=~/.kube/northline-ovh-vps
kubectl get nodes                        # northline Ready
```

Over SSH, as root: `ufw` (SSH only from `admin_cidrs`, 80/443, the pod and service networks), swap off, k3s from its
install script on channel `v1.33` (Traefik disabled, secrets encrypted at rest, local-path volumes), Helm. Locally:
the kubeconfig (`server: https://127.0.0.1:16443`) and an SSH tunnel to the API (`make ovh-tunnel` reopens it after a
reboot or a network change). Re-running is safe.

### V5. Images

[§ Images](#images): build and push the seven images, then `export REGISTRY=… IMAGE_TAG=…`.

### V6. Deploy

```sh
make deploy TARGET=ovh-vps               # REGISTRY and IMAGE_TAG from V5
```

`scripts/ovh/deploy.sh` writes the Secrets (`make ovh-secrets`), installs cert-manager, Envoy Gateway (+ the
GatewayClass and `manifests/ovh-vps` EnvoyProxy) and CloudNativePG, then `northline-data` (Postgres + PostGIS, Valkey,
Meilisearch, Kafka, the auth signing-key volume) and `northline` with `values-dryrun.yaml` + `values-ovh-vps.yaml` +
Terraform's `helm_values`. Order inside the app chart as on Google Cloud: Kafka topics and migrations Jobs, the
Deployments, the search-indices and OAuth clients Jobs. About 15 minutes the first time. `ADDONS=0` skips the add-ons
on later runs; `RESTART=1` restarts the apps (after a secret changed).

### V7. DNS and TLS

[§ DNS](#dns), then:

```sh
kubectl -n northline-dryrun get gateway northline      # PROGRAMMED True
kubectl -n northline-dryrun get certificates            # all Ready a few minutes after DNS resolves
curl -sI https://studio.demo.northline.ca | head -1     # 200, with strict-transport-security
```

### V8. Stripe webhooks

[§ Stripe and Twilio](#stripe-and-twilio).

### V9. First admin, market, demo data

[§ Demo data](#demo-data).

### V10. Smoke test

```sh
curl -s https://auth.demo.northline.ca/.well-known/openid-configuration | jq -r .issuer
for h in studio console; do curl -s -o /dev/null -w "%{http_code} $h\n" "https://$h.demo.northline.ca"; done
curl -s -o /dev/null -w "%{http_code} consumer\n" https://demo.northline.ca
make ovh-smoke TARGET=ovh-vps                          # rollouts, every Spring app Ready (= readiness UP; the images are distroless, no wget), HTTPS once DNS resolves
kubectl -n northline-dryrun get jobs                   # migrate, kafka-topics, search-indices, oauth-clients Complete
kubectl -n northline-dryrun get backups.postgresql.cnpg.io   # the first base backup completed (Object Storage)
```

Then by hand as in [dryrun-gcp.md § 14](dryrun-gcp.md#14-smoke-test): sign in, each Studio section, a search, a
checkout with the test card, the console.

## ovh-mks step by step

### M1. Terraform: network, cluster, database, storage

```sh
cd infra/terraform/envs/ovh/mks
cp terraform.tfvars.example terraform.tfvars   # service_name, api_allowed_cidrs
terraform init && terraform apply              # ≈ 15–25 minutes (cluster, node pool, database)
cd ../../../../..
```

It creates the private network and subnet (`10.60.0.0/24`, VLAN 60) with a gateway, the cluster on it (free plan,
`BHS5`), the node pool (2 × `b3-16`, hourly), Managed PostgreSQL 17 Essential `db1-4` on the private network (only the
subnet may connect) with the database `northline` and the user `northline_app`, the uploads bucket and its S3 user.
`data_services = { postgres = { mode = "in-cluster" } }` puts Postgres in the cluster instead (CloudNativePG with S3
backups, as `ovh-vps`); `managed_postgres = { plan = "business" }` for two nodes (HA).

### M2. kubeconfig

```sh
terraform -chdir=infra/terraform/envs/ovh/mks output -raw kubeconfig > ~/.kube/northline-ovh-mks
chmod 600 ~/.kube/northline-ovh-mks && export KUBECONFIG=~/.kube/northline-ovh-mks
kubectl get nodes                                # two nodes Ready
```

### M3. Secrets file

`make ovh-secrets-init TARGET=ovh-mks`, then fill `.ovh/ovh-mks.env` ([§ Secrets](#secrets)). With Managed
PostgreSQL its `DB_PASSWORD` is ignored: the script takes the app user's password from Terraform.

### M4. Images

[§ Images](#images).

### M5. Deploy

```sh
make deploy TARGET=ovh-mks
```

As V6, plus, once, the database's extensions — `postgis`, `citext`, `pgcrypto` — and the app user's `CREATE` grant
on `northline`, as the service's admin user `avnadmin` from a short-lived `postgres:17-alpine` pod (there is no
Terraform resource for extensions; the migration V001 then finds them). Envoy Gateway runs two proxies behind a public
OVHcloud load balancer (`manifests/ovh-mks`), created for its LoadBalancer Service.

### M6. DNS

```sh
kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=northline   # EXTERNAL-IP
```

[§ DNS](#dns) with that address. Then the certificates as in V7.

### M7. Stripe, first admin, demo data

[§ Stripe and Twilio](#stripe-and-twilio), [§ Demo data](#demo-data).

### M8. Smoke test

As V10, without the CloudNativePG backups line (Managed PostgreSQL: Databases › the service › Backups in the console).

## Secrets

OVHcloud has no secrets manager External Secrets could read, so the secrets live in **one local file per target**,
`.ovh/ovh-vps.env` or `.ovh/ovh-mks.env` (git-ignored, mode 600, `KEY=value` lines taken literally), and
`scripts/ovh/secrets.sh` turns it into Kubernetes Secrets ([secrets.md § OVHcloud](secrets.md#ovhcloud-targets-plain-secrets-from-a-local-file)):

| command | does |
|---|---|
| `make ovh-secrets-init TARGET=…` | creates the file: `TOTP_KEY`, `WEBHOOK_SECRET_KEY`, `EMAIL_UNSUBSCRIBE_KEY`, `KMS_LOCAL_KEY`, the three BFF secrets and their bcrypt hashes, `DB_PASSWORD`, `REDIS_PASSWORD`, `MEILISEARCH_API_KEY` generated; Stripe, Twilio, SendGrid, registry empty |
| `scripts/ovh/secrets.sh check …` | names the required keys still empty (never prints a value); refuses a live Stripe key, and a secret key without its publishable key |
| `make ovh-secrets TARGET=…` | writes `northline-secrets` (every app key with a value + `STORAGE_ACCESS_KEY`/`STORAGE_SECRET_KEY` from Terraform, and `DB_PASSWORD` from Terraform with Managed PostgreSQL), `northline-pg-app`, `northline-valkey`, `northline-meilisearch`, `northline-pg-backup-s3` (in-cluster services) and `northline-registry`; `make deploy` runs it too |

Required before deploying: the generated ones. Stripe test values (`STRIPE_SECRET_KEY` `sk_test_…` with
`STRIPE_PUBLISHABLE_KEY` `pk_test_…`; `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET` after
[§ Stripe](#stripe-and-twilio) — an empty one is left out of the api's environment) are optional since S-155 (without
them: the fake payment gateway, identity and age checks on the local fakes). Optional too: `SMS_AUTH_TOKEN` + `SMS_ACCOUNT_ID` + `SMS_FROM` (all three switch
`SMS_PROVIDER=twilio` on for northline-auth), `EMAIL_API_KEY` (SendGrid; otherwise emails are logged),
`KAFKA_SASL_JAAS_CONFIG` (an external Kafka), `REGISTRY_SERVER`/`_USERNAME`/`_PASSWORD`.

**From the pipeline** the same script reads every key from environment variables instead
(`OVH_SECRETS_SOURCE=env`: the GitHub environment's secrets and variables, [§ Today](#pipeline-secrets-and-variables)).
A generated key without a value there is taken from the cluster's `secret/northline-generated`, or generated once and
kept there, so redeploys never change it; each generated value is masked in the log. Stripe is optional in both modes
(without `STRIPE_SECRET_KEY`: the fake payment gateway, identity and age checks on the local fakes — [§ 6](#6-stripe-and-twilio--optional-for-a-first-demo)).
Pipeline settings in the same file (`OVH_*`, `TF_STATE_*`, `REGISTRY*`) are read by `make ovh-gh-setup` and never
copied into `northline-secrets`.

Keep a copy of the file somewhere safe (a password manager): losing it loses `TOTP_KEY` (every enrolled authenticator
must re-enrol) and `KMS_LOCAL_KEY` (sealed calendar tokens can't be opened). Change one: edit, `make ovh-secrets`,
`kubectl -n northline-dryrun rollout restart deploy`. Everything else — Google/Apple sign-in, Google Maps, OpenRouter,
push — stays empty: the `dev` profile uses its fakes.

## Images

The cluster pulls `REGISTRY/<app>:IMAGE_TAG` for `api`, `auth`, `bff`, `worker`, `studio`, `consumer`, `console`.
Any registry works; two simple ones:

- **GitHub Container Registry** (default suggestion): `REGISTRY=ghcr.io/<org>/northline`, a token with
  `write:packages` to push and one with `read:packages` in `REGISTRY_SERVER=ghcr.io`, `REGISTRY_USERNAME`,
  `REGISTRY_PASSWORD` of the secrets file to pull (`northline-registry`, referenced by both values files). Images are
  code, not data; pulls are inbound traffic (free at OVHcloud).
- **OVHcloud Managed Private Registry** in the same project (Public Cloud › Managed Private Registry, plan S; Harbor;
  price unverified): the same three variables with its URL and a Harbor robot account.

```sh
export REGISTRY=ghcr.io/ORG/northline IMAGE_TAG=$(git rev-parse --short=12 HEAD)
docker login ghcr.io
(cd server && ./gradlew jib -Pimage.registry="$REGISTRY" -Pimage.tag="$IMAGE_TAG" -Pimage.platforms=linux/amd64)
for app in studio consumer console; do
  docker buildx build web --target "$app" --platform linux/amd64 --provenance=false \
    --build-arg GIT_SHA="$(git rev-parse HEAD)" --build-arg IMAGE_TAG="$IMAGE_TAG" -t "$REGISTRY/$app:$IMAGE_TAG" --push
done
```

amd64 only (the VPS and `b3` nodes are x86).

## DNS

No external-dns on OVHcloud: two records at whoever hosts `northline.ca` (or a delegated `demo.northline.ca` zone),
pointing every host at the target's address — the VPS's IPv4 (`terraform output dns_records`) or the load balancer's
EXTERNAL-IP (M6):

```text
demo.northline.ca.    300 IN A   IP
*.demo.northline.ca.  300 IN A   IP
demo.northline.ca.        IN CAA 0 issue "letsencrypt.org"
```

If the zone is at OVHcloud (Web Cloud › Domain names › DNS zone) the same records go there. Let's Encrypt validates
each host over HTTP-01 through the Gateway once the records resolve; `kubectl -n northline-dryrun get certificates`.
Switching a demo from Google Cloud to OVHcloud (or back) is changing these records — the hosts are the same.

## Stripe and Twilio

- **Stripe (test mode)** — Dashboard › Developers › Webhooks: `https://api.demo.northline.ca/api/v1/webhooks/stripe`
  (account events) and `https://api.demo.northline.ca/api/v1/webhooks/stripe/connect` (connected accounts), the events
  of [stripe.md](stripe.md), Identity activated in test mode ([demo.md § 2](demo.md#2-before-the-first-run-on-the-dry-run)).
  Their signing secrets into `STRIPE_WEBHOOK_SECRET` and `STRIPE_CONNECT_WEBHOOK_SECRET` of the secrets file, then
  `make ovh-secrets TARGET=…` and `kubectl -n northline-dryrun rollout restart deployment northline-api`.
- **Twilio** — [README § Twilio account setup](README.md#twilio-account-setup-once-per-environment): the auth token,
  account SID and sender into `SMS_AUTH_TOKEN`, `SMS_ACCOUNT_ID`, `SMS_FROM`; `make deploy TARGET=…` switches
  northline-auth to Twilio. A trial account texts only verified numbers: verify the demo phones.

## Demo data

As [dryrun-gcp.md § 13](dryrun-gcp.md#13-demo-data) and [demo.md](demo.md), with the target as `ENV`:

1. **First console admin** — register on `https://demo.northline.ca` with an authenticator app (keep its key), then:

   ```sh
   # ovh-vps / in-cluster Postgres
   kubectl -n northline-dryrun exec -it northline-pg-1 -- psql -d northline
   # ovh-mks / Managed PostgreSQL: a throw-away psql pod (the admin URI sits in its spec while it runs)
   kubectl -n northline-dryrun run psql --rm -it --restart=Never --image=postgres:17-alpine \
     --env="PGURI=$(terraform -chdir=infra/terraform/envs/ovh/mks output -json postgres_credentials | jq -r .admin_uri)" \
     -- sh -c 'psql "$PGURI"'
   ```

   `INSERT INTO identity.platform_roles (user_id, role) VALUES ('USER_ID', 'staff'), ('USER_ID', 'admin');` (the id
   from `select id from identity.users where email = '…'`).
2. **Market live** — console › Provinces, as [demo.md § 2](demo.md#2-before-the-first-run-on-the-dry-run).
3. **`make demo-data ENV=ovh-vps`** (or `ENV=ovh-mks`) with the variables of [demo.md § 3](demo.md#3-run-it); the
   state file is `.demo/ovh-vps.json` (or `ovh-mks`). `RESET=1` after each demo as on Google Cloud.

## Between demos

```sh
make ovh-status TARGET=ovh-vps       # nodes, Deployments, StatefulSets
make ovh-down   TARGET=ovh-vps       # apps → 0 (replicas remembered); data services keep running
make ovh-up     TARGET=ovh-vps       # apps back
```

- **`ovh-vps`: down saves no money** — the VPS is billed by the month. It only takes the sites off the internet (and
  the data services stay up). There is no "stop the VPS" saving either; the only saving is ending the service
  ([§ Teardown](#teardown)) once the demos are done.
- **`ovh-mks`:** `make ovh-down TARGET=ovh-mks` scales the apps to 0, then `terraform apply -var node_count=0` (it asks
  before applying; `AUTO_APPROVE=1` doesn't): the hourly nodes stop billing; the control plane (free), the volumes, the
  load balancer, the gateway and Managed PostgreSQL keep billing. In-cluster Valkey/Meilisearch/Kafka wait on their
  volumes. `make ovh-up TARGET=ovh-mks` applies `terraform.tfvars` again (2 nodes, ≈ 5–10 minutes) and restores the
  apps. A plain `terraform apply` while down also brings the nodes back.

## Backups and restore

| data | `ovh-vps` | `ovh-mks` |
|---|---|---|
| Postgres | CloudNativePG: daily base backup (03:00 UTC, one immediately) + continuous WAL to `northline-ovh-vps-pg-backups`, 7 days (`postgres.backup.retentionPolicy`); a lifecycle rule deletes leftovers after 28 days | Managed PostgreSQL: OVHcloud's daily backups and point-in-time restore (retention by plan — check the console) |
| Uploads | Object Storage: OVHcloud's durability; no versioning (fake data) | same |
| Valkey (sessions), Kafka (events in flight) | not backed up: a lost volume signs people out and drops undelivered events (fake data) | same |
| Meilisearch | not backed up: rebuilt from Postgres by the reindex (`--set searchReindex.runId=…`, [search.md § 11](search.md#11-meilisearch-provider-meilisearch)) | same |
| The VPS | OVHcloud's automated backup of the VPS (included in the range, unverified) — a whole-box restore in the console | — |
| Secrets | your copy of `.ovh/<target>.env` and the Terraform state | same |

**Restore the in-cluster Postgres** (`ovh-vps`, or `ovh-mks` with `postgres.mode = in-cluster`) — a new cluster
bootstrapped from the bucket, to the end of the WAL or a point in time; it then archives into a new folder:

```sh
kubectl -n northline-dryrun get backups.postgresql.cnpg.io        # what exists
make ovh-down TARGET=ovh-vps                                       # apps off while restoring
cat > .ovh/ovh-vps-data.yaml <<'EOF'                               # kept: every later deploy layers it
postgres:
  recovery: { enabled: true, targetTime: "" }                      # or "2026-10-20T14:00:00Z" (within the 7 days)
  backup: { serverName: northline-pg-2 }                           # a new folder; next restore: -3, and sourceServerName: northline-pg-2
EOF
kubectl -n northline-dryrun delete cluster.postgresql.cnpg.io northline-pg   # its volume goes with it
ADDONS=0 make deploy TARGET=ovh-vps                                # northline-data recreates it from the backups
kubectl -n northline-dryrun get cluster.postgresql.cnpg.io northline-pg -w   # "Cluster in healthy state"
make ovh-up TARGET=ovh-vps
```

After a restore, run the search reindex (Meilisearch then matches the restored rows). **Restore Managed PostgreSQL:**
console › Databases › the service › Backups › restore (or fork to a new service at a point in time); a fork has a new
host — `terraform import` is not needed for a same-service restore. Rehearse a restore once before the first client
demo.

## Logs and debugging

No cloud logging: `kubectl -n northline-dryrun logs deploy/northline-api` (`-f`, `--since=1h`), `kubectl get events`.
On the VPS: `journalctl -u k3s`, `sudo k3s kubectl …`. `--set observability.collector.enabled=true` on a deploy runs
one Collector that only logs a summary (nothing to export to). Node and pod usage: `kubectl top` (k3s ships
metrics-server; on MKS install it if missing).

## Teardown

```sh
# both: the releases (the load balancer and volumes go with them on ovh-mks)
helm uninstall northline northline-data -n northline-dryrun
kubectl delete namespace northline-dryrun envoy-gateway-system
# ovh-vps
terraform -chdir=infra/terraform/envs/ovh/vps destroy      # buckets must be empty: empty them in the console first
#   then console › the VPS › Terminate (billing stops at the end of the paid month)
# ovh-mks
terraform -chdir=infra/terraform/envs/ovh/mks destroy      # cluster, nodes, database, network, gateway, buckets, users
```

Then: remove the DNS records, revoke the API credentials (and the registry tokens), delete the Public Cloud project if
nothing else lives in it, delete `.ovh/<target>.env` and the Terraform state, check the next invoice. Buckets that
still hold objects make `destroy` fail — empty them in the console (Object Storage › the bucket › delete objects).

## Open items and never run

- **Never applied.** No OVHcloud account exists: Terraform, the scripts, k3s on the VPS, MKS, Managed PostgreSQL,
  Object Storage over S3 (the api's S3 adapter and CloudNativePG's barman against `s3.bhs.io.cloud.ovh.net`), the
  Network Firewall, the OVHcloud load balancer with `externalTrafficPolicy: Local`, Let's Encrypt — all unexercised.
- **Unverified facts to check in the console:** every price; VPS stock and range names in Beauharnois and its billing
  terms; BHS5 offering MKS free plan, Managed PostgreSQL 17 with PostGIS, Object Storage; the S3 endpoint
  `https://s3.bhs.io.cloud.ovh.net`; the database region name `BHS`; whether a node pool may sit at 0 nodes; the load
  balancer's need for the gateway; the Network Firewall on a VPS IP; whether the OVHcloud load balancer health-checks
  `Local` services (two Envoy proxies on two nodes avoid relying on it).
- **Keys:** `KMS_PROVIDER=local` only works under `dev`. Staging or prod on OVHcloud need an OVHcloud KMS (OKMS)
  adapter for signing and sealing — not built.
- **Toronto:** the region list holds `BHS5` only; Toronto is one entry in `stacks/ovh` once its services are confirmed.
- **The provider version:** the roots pin `ovh/ovh ~> 2.22`; it was built from source here (the registry was
  unreachable) — the first real `terraform init` downloads it from the registry.
