# OVHcloud runbook — the dry run in Vint Hill for now, Beauharnois later (`ovh-vps` or `ovh-mks`)

The dry run ([dryrun-gcp.md](dryrun-gcp.md) is the Google Cloud one) on OVHcloud, with **everything in one location**
so nothing pays egress between components. The location is one setting, `OVH_LOCATION` (S-160,
[§ Locations](#locations)): **`us-east-va`** — Vint Hill, Virginia, on an OVHcloud **US** account — is the default for
now (the owner is in the US; demo data only, [§ Data residency](#data-residency)); `us-west-or` (Hillsboro, Oregon) is
the other US one; **`ca-bhs`** — Beauharnois, Québec, on an OVHcloud **Canada** account — is where real Canadian data
must live ([§ Canada later](#canada-later)). Two shapes, selectable:

- **`ovh-vps`** — the cheapest: one VPS (8 vCore / 24 GB) running k3s, every data service on it, ≈ $25–30 for the
  month.
- **`ovh-mks`** — production-shaped: OVHcloud Managed Kubernetes (free control plane) with two nodes, OVHcloud Managed
  PostgreSQL 17, ≈ $150–215 a month.

Fake data only, the `dev` Spring profile, the same two charts and `values-dryrun.yaml` as Google Cloud. Owner decision
and what was left open: [DECISIONS.md § 2026-10-19](../DECISIONS.md). Comparison with Google Cloud:
[README § Google vs OVH](README.md#google-vs-ovh).

> **Status (2026-10-20, S-161): never applied** — neither in the US nor in Canada. Nothing here has run against a real OVHcloud account. The Terraform roots
> (`infra/terraform/envs/ovh/vps`, `infra/terraform/envs/ovh/mks`) pass `terraform validate`, `tflint` and an offline
> plan against a mocked `ovh/ovh` provider (2.22, built from source here); both charts render and validate offline with
> the OVHcloud values (`deploy/helm/validate.sh`); the scripts parse with bash 3.2 and were run against stubs only.
> The `ovh` pipeline (S-155, [§ Today](#today-from-a-new-ovh-account-to-a-running-demo)) has never run either.
> **Every price below is unverified** (from search-engine excerpts; the vendor sites were unreachable) — check each in
> the OVHcloud console before ordering. The US region codes and endpoints come from OVHcloud US documentation excerpts
> ([§ Locations](#locations) says which and what is still unverified).

Other environments: [dryrun-gcp](dryrun-gcp.md), [dev](dev.md), [staging](staging.md), [prod](prod.md) ·
[overview](README.md)

## Today: from a new OVH account to a running demo

The shortest path, through the **`ovh` pipeline** (`.github/workflows/ovh.yml`, S-155): **three steps in a browser,
one set-up command on your Mac, then the pipeline** (S-161: nothing else is made by hand). Written for the **US
location `us-east-va`** (the default for now); Canada is the same with the Canadian site and token page —
[§ Canada later](#canada-later). `ovh-vps` is the target for a first demo; `ovh-mks` works the same way with the
differences noted. About an hour end to end, most of it waiting.

> **Never run against a real account yet.** The pipeline, `scripts/ovh/gh-setup.sh`, the API calls that make the
> state user (`scripts/ovh/api.sh`), the state bucket bootstrap and the runner's firewall rule were checked offline only
> (actionlint, bash 3.2 parsing, shellcheck, the scripts against a stub of the OVHcloud API and the `aws` CLI,
> `terraform test` with a mocked provider, `helm template | kubeconform`). Expect to fix small things on the first run;
> each step below says where it reports problems.

**What you do by hand — and nothing else:**

| | where | what |
|---|---|---|
| 1 | us.ovhcloud.com | the account |
| 2 | us.ovhcloud.com › Billing | a payment method |
| 3 | api.us.ovhcloud.com/createToken | the API token (three values) |

You already have a Public Cloud project? Keep its id (32 hex characters) for § 4. You don't? `make ovh-gh-setup`
can order one (optional, [below](#no-project-yet)).

**What code makes** (`make ovh-gh-setup`, then the pipeline's `apply`, all in **US-EAST-VA** / Vint Hill):

- the **Public Cloud user `terraform-state`** (role ObjectStore operator) and its **S3 key**, through the OVHcloud API
  — stored straight into the GitHub Environment as `TF_STATE_ACCESS_KEY` / `TF_STATE_SECRET_KEY`, never printed;
- the **state bucket** (`northline-tfstate-` + the project id's first 8 characters, versioned) — by the pipeline's
  first run, in the location's Object Storage (`https://s3.us-east-va.io.cloud.ovh.us`);
- the **app buckets** (uploads, Postgres backups) and their own S3 users — by Terraform in the same location;
- for `ovh-vps`, the **server**: a **Public Cloud instance** (Ubuntu 24.04, `b3-32`: 8 vCPU / 32 GB, billed by the
  hour) with a **public IPv4**, and the **SSH key pair** the pipeline uses (gh-setup makes it; Terraform registers the
  public half with the instance; the private half is an Environment secret);
- for `ovh-mks`, the cluster, nodes, private network and database (as before).

**No Object Storage, no bucket, no S3 user, no VPS is created in the console.** Did you already make some by hand?
They are adopted, not duplicated: a user described `terraform-state` is reused with its existing S3 key (the API hands
its secret back), and a bucket named `northline-tfstate-…` already in the project is proposed as `TF_STATE_BUCKET`.

### 1. Account (browser, OVHcloud US)

OVHcloud US (**us.ovhcloud.com**, the US company: prices in US$, the `ovh-us` API) and OVHcloud Canada
(ca.ovhcloud.com) are **separate companies with separate accounts**: a login, a project or an API token from one does
not work on the other. For the US location (`us-east-va`, the default for now) everything is on the US site.

Open **us.ovhcloud.com** › *Log in* › *Create an account* (or sign in).

### 2. Payment method (browser, OVHcloud US)

In the Control Panel, your name (top right) › *Billing* › *Payment methods* › add a card, and make it the **default**.
The instance is billed by the hour against it (and, if you let gh-setup order the project, that free order is paid
with it).

### 3. API token (browser, OVHcloud US)

Open this address while signed in to us.ovhcloud.com. It pre-fills the rights; pick a validity covering the demo
period (e.g. 30 days) and **no IP restriction** (the pipeline's runners have new addresses every run):

```text
https://api.us.ovhcloud.com/createToken/?GET=/cloud/*&POST=/cloud/*&PUT=/cloud/*&DELETE=/cloud/*
```

`/cloud/*` is everything this design touches: the state user and its S3 key, Object Storage, the instance and its SSH
key, and for `ovh-mks` the cluster, node pool, private network and database. It returns three values —
**application key**, **application secret**, **consumer key** (`OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`,
`OVH_CONSUMER_KEY`). Keep the page open for § 4. Revoke the token at teardown (§ 8). The Canadian page
(`https://ca.api.ovh.com/createToken/`) takes the same rights.

Two options need more rights, appended to the same address — only if you choose them:

| option | append |
|---|---|
| a VPS you order in the console instead of the instance (`OVH_SERVER=vps`, [below](#a-vps-instead-of-the-instance)) | `&GET=/vps/*&GET=/ip/*&POST=/ip/*&PUT=/ip/*&DELETE=/ip/*` |
| gh-setup orders the Public Cloud project ([below](#no-project-yet)) | `&GET=/me&GET=/me/*&POST=/me/order/*&GET=/order/*&POST=/order/*&DELETE=/order/*` |

### 4. GitHub set-up (your Mac, once per target)

Needs the GitHub CLI signed in with admin rights on the repository (`brew install gh`, `gh auth login`), `jq` (macOS
15 has it; else `brew install jq`), `curl` and `openssl` (macOS has them). Environments on a private repository need a
paid GitHub plan; if the command says GitHub refused the environment, use
[the local alternative](#the-local-alternative-no-github-actions).

```sh
make ovh-gh-setup TARGET=ovh-vps
```

**The first question is the location** — answer **`us-east-va`** (Enter takes it, it is the default):

```text
   Where Northline runs (OVH_LOCATION):
     us-east-va  Vint Hill, Virginia   — OVHcloud US (us.ovhcloud.com), the default for now
     us-west-or  Hillsboro, Oregon     — OVHcloud US
     ca-bhs      Beauharnois, Québec   — OVHcloud Canada (ca.ovhcloud.com)
OVH_LOCATION [us-east-va]:
```

Then it prints the token page of that location (§ 3) and asks for the three token values (typed hidden), then:

| question | answer |
|---|---|
| `OVH_SERVICE_NAME` | your project's id (32 hex characters, top left of the project page). Empty: [no project yet](#no-project-yet) |
| `TF_STATE_BUCKET` | Enter (the proposal: your existing `northline-tfstate-…` bucket if there is one, else `northline-tfstate-` + 8 characters of the project id — the pipeline creates it) |
| `OVH_DOMAIN` | Enter for `demo.northline.ca`, or a zone you control |
| `OVH_SERVER` | Enter for **`instance`** |
| `OVH_ADMIN_CIDRS` | Enter (your current address/32: who may SSH besides the pipeline) |
| the optional ones | `y` to the ones you want (§ 6: an email way is needed for sign-ups) |

Along the way it shows, without any secret:

```text
== Terraform state user (terraform-state)
   Public Cloud user terraform-state created (id …, role ObjectStore operator)   (or: exists: reused)
   its S3 key …WXYZ: created                                                       (or: reused)
   set secret TF_STATE_ACCESS_KEY
   set secret TF_STATE_SECRET_KEY
== SSH for the pipeline
   new deploy key …/.ovh/ovh-vps-deploy-key (+ .pub)
   set secret OVH_VPS_SSH_PRIVATE_KEY
   set var OVH_SSH_PUBLIC_KEY
```

It creates the **Environment** `ovh-vps` (Settings › Environments) with **you as required reviewer** and deployments
allowed from `develop` and `main` only, and sets every secret and variable the pipeline reads. Values can also come from
`.ovh/ovh-vps.env` (`KEY=value` lines; git-ignored; `OVH_LOCATION=us-east-va` there skips the question; on a re-run the
current value is the default). **Re-run it any time**: it reuses the same state user, S3 key and deploy key — nothing
piles up. It removes the old S-155 variable `OVH_ENDPOINT` if the environment still has one.

`ovh-mks`: `make ovh-gh-setup TARGET=ovh-mks` — the same questions without the server and SSH ones; the same state
user and bucket serve both targets (each target's state is its own key in the bucket).

The app's own secrets (`TOTP_KEY`, `KMS_LOCAL_KEY`, the BFF secrets, the data-service passwords) need nothing from
you: the first deploy generates them **once** and keeps them in the cluster (`secret/northline-generated`); later runs
reuse them. Back them up after the first deploy:
`kubectl -n northline-dryrun get secret northline-generated -o yaml > .ovh/ovh-vps-generated.yaml`.

#### No project yet

Leave `OVH_SERVICE_NAME` empty and answer `y`: `scripts/ovh/project.sh` first looks for a project named
`northline-dryrun` on the account and adopts it; only if there is none does it order one with
`infra/terraform/envs/ovh/project` (`ovh_cloud_project`, plan `project.2018`, paid — it is free — with the default
payment method; local state in `.ovh/project-us-east-va.tfstate`). The token needs the order rights of § 3 for that.

#### A VPS instead of the instance

The instance is the default because code makes it — and deletes it — completely. A VPS is cheaper by the month
([§ Cost estimate](#cost-estimate)) but is **ordered in the console** (Bare Metal Cloud › Virtual Private Servers ›
Order: 8 vCores / 24 GB / 200 GB, Vint Hill, Ubuntu 24.04, monthly): provider 2.22's `ovh_vps` can order, but it needs
the US catalogue's plan code and configuration labels for the current range — unverified from here — and an apply
commits a month's payment that a destroy doesn't refund. With a VPS: answer `vps` to `OVH_SERVER`, then its service
name (`vps-….vps.ovh.us`) and IPv4; gh-setup installs the deploy key over your own SSH access and records the host
key. The token then needs the VPS rights of § 3 (the Network Firewall in front of the VPS).

#### Manual fallback: the state user in the console

Only if the API path fails (gh-setup or the State keys step says why). The console's labels may differ slightly.
**OVHcloud US**: Public Cloud › your project › *Storage* › *Object Storage* › tab *Users* › *Create user* —
description `terraform-state` — then the user's *…* menu › *View the secret key*; or *Project Management* › *Users &
Roles* › *Add user*, description `terraform-state`, role **ObjectStore operator**. Put its keys in `.ovh/ovh-vps.env`
as `TF_STATE_ACCESS_KEY=…` and `TF_STATE_SECRET_KEY=…` and re-run `make ovh-gh-setup` (keys in the file win). Use the
description `terraform-state` and later runs adopt that user. Still no bucket to create: the pipeline makes it.

### 5. Run the pipeline (GitHub)

**Actions** › **ovh** › **Run workflow** › branch `develop`, then, in order:

| run | target | action | other inputs | what happens |
|---|---|---|---|---|
| 1 | `ovh-vps` | `plan` | — | approve the run (*Review deployments* › `ovh-vps` › Approve); the state bucket is created (versioned) if missing; the summary lists what would be created: the instance, 2 buckets, 2 S3 users |
| 2 | `ovh-vps` | `provision-and-deploy` | `build-images` on | approve; Terraform applies (the instance comes up in a few minutes with its public IPv4), the seven images are built and pushed to `ghcr.io/OWNER/northline` (≈ 20 min, in parallel), then the deploy job: k3s is installed over SSH (idempotent; `ufw` on the host: SSH only from `OVH_ADMIN_CIDRS` and the runner, 80/443), the Secrets, cert-manager, Envoy Gateway, CloudNativePG, `northline-data` and `northline` are installed (≈ 15 min the first time), rollouts are waited for and the smoke test runs |

Each job that reads the state first runs **State keys**: the stored `TF_STATE_*` keys, or — when they are missing or
refused — the `terraform-state` user's keys from the API (found or created, masked in the log). So the pipeline works
even if gh-setup could not store them.

The deploy job's summary prints the **public address** (the instance's IPv4; on `ovh-mks` the load balancer's), the
DNS records to create and the URLs. The HTTPS part of the smoke test shows *pending* until the records exist (§ 7);
after creating them, run **`deploy`** again with `build-images` off (≈ 5 min): it then checks auth's OpenID
configuration, the Studio's and the consumer's sign-in pages, the consumer home and the console over HTTPS. A failing
step names what failed; the logs of a pod: run the [local alternative](#the-local-alternative-no-github-actions)'s
`kubectl` commands from your Mac (`ssh -i .ovh/ovh-vps-deploy-key ubuntu@IPv4` reaches the instance).

Later: `deploy` after each change (the images are tagged with the commit's short sha; `image-tag` + `build-images`
off redeploys an existing tag), `down` / `up` between demos (§ [Between demos](#between-demos)), `plan` / `apply`
after changing a variable. `ovh-mks` is the same with `target = ovh-mks` (its `apply` takes ≈ 15–25 minutes).

Every job that touches the account — `plan` included, because the API credentials live in the environment — waits
for your approval; a run of one target never overlaps another run of the same target. The repository's other
workflows are manual too, and **GitHub Actions minutes may be depleted**: the image build is the expensive part (≈ 20
runner-minutes). Without minutes, use the local alternative below.

### 6. Email (needed), Stripe and Twilio (optional)

Since S-154 an account is **email + password**, confirmed with a **6-digit code sent by email**; the mobile number is
optional and the Studio's and console's second factor is an authenticator app or a passkey. So **sign-ups need
email, not Twilio** — pick one of these before inviting anyone ([§ Email](#email) has the exact settings):

| choice | set (environment variables / secrets, or `.ovh/<target>.env`) | who can sign up |
|---|---|---|
| an SMTP relay — Brevo, Gmail with an app password, or OVHcloud's own email | `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USERNAME`, secret `SMTP_PASSWORD`, `SMTP_STARTTLS` (true), `EMAIL_FROM` | anyone: the codes reach their inbox |
| the **demo inbox** (Mailpit in the cluster) for a private demo | `OVH_DEMO_MAILPIT=true` (gh-setup then makes and shows the inbox password, secret `MAILPIT_PASSWORD`) | the people you show `https://mail.<domain>` to: every email lands there, nothing is delivered |
| nothing | — | only you, reading the code in the log: under `dev`, `EMAIL_PROVIDER=local` finds no mail server in the cluster and writes each email's text — the code and the link included — to the app's log at WARN (`kubectl -n northline-dryrun logs deploy/northline-auth \| grep -A12 'No SMTP server took the email'`) |

Unlike SMS codes (withheld under `dev`, S-112), email codes are not withheld when no provider is configured — they
are in the auth pod's log, which on OVHcloud goes nowhere else. `api` and `worker` (invitations, notifications) use
the same settings as `auth`.

- **Without Stripe** (no `STRIPE_SECRET_KEY`): the api uses its fake payment gateway, and the deploy switches
  identity and age checks to the local fakes. Everything starts; but the fakes' outcome pages exist only under
  `local`, so **an owner can't finish identity verification** (onboarding stops there) and age-restricted items
  can't be bought. Add the test keys later ([§ Stripe and Twilio](#stripe-and-twilio)), re-run `make ovh-gh-setup`,
  then `deploy`.
- **Without Twilio**: sign-ups by email work. What needs it: signing in to the consumer site **with a phone code**,
  confirming a mobile number, and `make demo-data`, which still registers its accounts through the phone path (six
  phones that receive SMS, [demo.md § 2](demo.md#2-before-the-first-run-on-the-dry-run)). A Twilio trial account
  (verified numbers only) is enough: `SMS_AUTH_TOKEN` (secret), `SMS_ACCOUNT_ID`, `SMS_FROM` (variables).

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

1. **Pause** between demos: action `down` (apps to 0; `ovh-mks`: also the nodes → 0). On `ovh-vps` the instance keeps
   billing by the hour while it exists — for a pause of days, `destroy` and later `provision-and-deploy` again is
   cheaper (≈ 40 minutes to rebuild; fake data: `make demo-data` again, or back up first, [§ Backups](#backups-and-restore)).
2. **Delete**: action `destroy` with `confirm-destroy` = the target's name. It removes the releases (on `ovh-mks` the
   load balancer and volumes with them), empties the buckets (fake data), runs `terraform destroy` (on `ovh-vps` that
   deletes the instance and its SSH key: billing stops), then — **last** — deletes the target's state from the state
   bucket and, when no other target's state is left there, the bucket and the `terraform-state` user. The
   Environment's `TF_STATE_*` secrets are then stale; a later run makes a new user and uses its keys by itself.
3. `OVH_SERVER=vps` only: console › Bare Metal Cloud › the VPS › **Terminate** (billing stops at the end of the paid
   month).
4. Remove the DNS records; revoke the API token (the console's API keys page of the account — us.ovhcloud.com for the
   US locations, ca.ovhcloud.com for `ca-bhs`); delete the Public Cloud project if nothing else lives there (it costs
   nothing empty); delete the GitHub Environment (Settings › Environments) and the `.ovh/` files; check the next
   invoice.

### Data residency

**The rule:** Northline's production data stays in Canada (the platform's Canadian data residency; Stripe and the
other clouds' roots are all Canadian). **The owner's decision (2026-10-20, S-160): demo in the US for now** — the
owner is in the US and deploys the dry run to OVHcloud US (`us-east-va`). That is allowed because the dry run holds
**fake demo data only** (the demo merchants, `make demo-data`, test cards) under the `dev` profile. **Real Canadian
customer data must move to `ca-bhs` before launch** — and nothing real (no real merchant, customer, address, phone or
payment) may be entered in a US deployment in the meantime.

How it is enforced: Terraform passes the location's country to the chart (`global.hostingCountry`: `US` or `CA`, in
`helm_values`); the `northline` chart **refuses to render** when the country is not `CA` and the environment is
`staging` or `prod`, or the Spring profile is `staging` or `prod` (`templates/validate.yaml`). `scripts/ovh/deploy.sh`
renders the chart with the same values before touching the cluster, so the refusal stops a deploy before anything is
installed. On the US locations email relays, Twilio and Stripe test mode are unchanged (they were already outside
Canada for the demo, [§ Email](#email)).

### Locations

One setting decides every place-dependent value (`infra/terraform/stacks/ovh/locations.json`, read by Terraform and
the scripts): the GitHub Environment variable **`OVH_LOCATION`** (pipeline), Terraform's **`ovh_location`** (both roots,
default `us-east-va`), `OVH_LOCATION=` in `.ovh/<target>.env` (Mac).

| `OVH_LOCATION` | account | API (`endpoint`) | token page | Public Cloud region | Object Storage (S3 region · endpoint) | Managed Databases region | VPS datacentre |
|---|---|---|---|---|---|---|---|
| `us-east-va` (default) | us.ovhcloud.com (OVHcloud US) | `ovh-us` (`https://api.us.ovhcloud.com/1.0`) | `https://api.us.ovhcloud.com/createToken/` | `US-EAST-VA-1` | `us-east-va` · `https://s3.us-east-va.io.cloud.ovh.us` | `US-EAST-VA` | Vint Hill, Virginia |
| `us-west-or` | us.ovhcloud.com (OVHcloud US) | `ovh-us` | the same | `US-WEST-OR-1` | `us-west-or` · `https://s3.us-west-or.io.cloud.ovh.us` | `US-WEST-OR` | Hillsboro, Oregon |
| `ca-bhs` | ca.ovhcloud.com (OVHcloud Canada) | `ovh-ca` (`https://ca.api.ovh.com/1.0`) | `https://ca.api.ovh.com/createToken/` | `BHS5` | `bhs` · `https://s3.bhs.io.cloud.ovh.net` | `BHS` | Beauharnois, Québec |

Where these come from (2026-10-20; the vendor sites themselves were unreachable from here, so from search-engine
excerpts of them):

- **API endpoint `ovh-us`** → `https://api.us.ovhcloud.com/1.0`: in the `ovh/ovh` provider 2.22 binary (its endpoint
  table) — verified.
- **S3 `us-east-va`, endpoint `https://s3.<region>.io.cloud.ovh.us`**: OVHcloud US support, *Object Storage — Endpoints
  and geoavailability* (support.us.ovhcloud.com article 10667991081107) and *Getting started with Object Storage*
  (example `https://s3.us-east-va.io.cloud.ovh.us/`); Hillsboro listed as `us-west-or` there. The `us-west-or`
  endpoint is that pattern, **not seen written out**. Path-style addressing (the api and CloudNativePG use it) avoids
  the virtual-host form.
- **Public Cloud `US-EAST-VA-1`, `US-WEST-OR-1`; Managed Kubernetes free plan, 1-AZ, in both**: OVHcloud docs,
  *Managed Kubernetes — Available datacenters, worker nodes and persistent storage flavors*; the US support article of
  the same name.
- **Managed Databases (PostgreSQL) in `US-EAST-VA`**: OVHcloud US support, *Databases & Analytics — Getting started
  with Terraform* (uses region `US-EAST-VA`); *available in all Public Cloud 1-AZ and 3-AZ regions* (OVHcloud docs,
  PostgreSQL capabilities). `US-WEST-OR` follows that statement — **unverified by example**. PostgreSQL 17 is listed;
  **PostGIS on 17 in the US regions is unverified** (it is on OVHcloud's extension list generally).
- **Object Storage's `region_name` `US-EAST-VA` / `US-WEST-OR`** (what `ovh_cloud_project_storage` takes, `BHS` for
  `BHS5`): by analogy — **unverified**.
- **VPS in Vint Hill and Hillsboro; VPS-4 = 8 vCores / 24 GB / 200 GB**: OVHcloud US press release (2021) and
  locations page; VPS specs from OVHcloud US pages (VPS-3: 6 vCores / 12 GB / 100 GB) and third-party listings of the
  US VPS-4 (Sept 2026) — **check in the order form**.
- **Edge Network Firewall**: OVHcloud US support lists *Enabling and configuring the Edge Network Firewall* (Network
  section); that the `/ip/{ip}/firewall` API behaves on `ovh-us` as on `ovh-ca` is **unverified**.

**When a region lacks a piece** (`locations.json` `managed_postgres`, `edge_firewall`, `managed_kubernetes` — all
`true` today on the evidence above): `ovh-mks` with `postgres.mode = managed` falls back to Postgres in the cluster
(CloudNativePG with backups to Object Storage; output `postgres.fallback = true`); `ovh-vps` without the Network
Firewall keeps only `ufw` on the host (as `edge_firewall = false`; output `location.edge_firewall_used`); without
Managed Kubernetes, `ovh-mks` refuses to plan — use `ovh-vps`. If the first `apply` in the US shows one of them is
missing, set that flag to `false` in `locations.json` (or, without a code change, `OVH_TFVARS` =
`data_services = { postgres = { mode = "in-cluster" } }`, `OVH_EDGE_FIREWALL=false`).

### Canada later

Moving the demo — or launching — in Canada is a **new account and a fresh deployment**, not a setting flip on the US
one (the US account's project, token, state bucket and server don't exist on ca.ovhcloud.com):

1. **Account**: ca.ovhcloud.com › create an account, a default payment method, a Public Cloud project (or let
   gh-setup order one) — § 1–2 on the Canadian site. The state user and bucket are made by code, as in the US.
2. **Token**: `https://ca.api.ovh.com/createToken/` with the same rights as § 3.
3. **Server** (`ovh-vps`): nothing to do with the instance (Terraform makes it in `BHS5`); with `OVH_SERVER=vps`,
   order 8 vCores / 24 GB / 200 GB in **Beauharnois (BHS)**.
4. **Keep what you want from the US demo** (it is fake data — the simplest is to keep nothing and re-run
   `make demo-data` in Canada, [§ Demo data](#demo-data)). To carry it over: a `pg_dump` of the US database
   ([§ Backups and restore](#backups-and-restore); `kubectl exec` on the `northline-pg` pod, or the managed database's
   URI), the uploads bucket (`aws s3 sync` to your Mac with the uploads user's keys from `terraform output -json
   s3_credentials`), and `secret/northline-generated` (`TOTP_KEY`, `KMS_LOCAL_KEY`… — without them enrolled
   authenticators and sealed values stop working with that database).
5. **Delete the US deployment** (§ 8: `destroy` — it removes the US state, bucket and state user last —, revoke the
   US token) — the pipeline's state for the
   target is in the US bucket, so destroy it **before** the environment points at Canada; afterwards no copy stays
   outside Canada.
6. **GitHub**: put the Canadian values in `.ovh/ovh-vps.env` (project id `OVH_SERVICE_NAME`, the three token values, a
   **new** `TF_STATE_BUCKET`, `OVH_ADMIN_CIDRS`; the state user's keys are made again from the Canadian token) — the
   script keeps a value that is already set unless the file holds a new one — then `make ovh-gh-setup TARGET=ovh-vps`
   and answer **`ca-bhs`** to the location question (it shows the current one as the default and asks you to confirm
   the move).
7. **Pipeline**: `plan` (the summary shows `BHS5` / `s3.bhs.io.cloud.ovh.net`), then `provision-and-deploy` (§ 5); point
   the DNS records at the new address (§ 7). Then restore what step 4 kept (database restore, `aws s3 sync` up to the
   new bucket, the generated secrets as environment secrets) and `deploy` again.

For staging or prod (real data) more than the location is needed: a key service adapter (`KMS_PROVIDER=local` is
`dev`-only — [§ Open items](#open-items-and-never-run)).

### The local alternative (no GitHub Actions)

The same steps from your Mac with `make` — the pipeline only wraps them. Tools: [§ What you need](#what-you-need).

```sh
make ovh-secrets-init TARGET=ovh-vps            # .ovh/ovh-vps.env: generated secrets; add Stripe/Twilio if you have them
export OVH_LOCATION=us-east-va                   # also ovh_location in terraform.tfvars (same value); ca-bhs in Canada
export OVH_APPLICATION_KEY=… OVH_APPLICATION_SECRET=… OVH_CONSUMER_KEY=…   # from that location's account
# Optional: the pipeline's shared state instead of a local one (MIGRATE=1 moves an existing local state there). The
# state user's keys come from the API (found or created, never shown); the bucket is created if missing.
export OVH_SERVICE_NAME=… TF_STATE_BUCKET=…
eval "$(scripts/ovh/tf-state.sh keys ovh-vps)"    # exports AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY for this shell
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
| `OVH_LOCATION` | variable | O | both | `us-east-va` (default; Vint Hill, OVHcloud US), `us-west-or` (Hillsboro, OVHcloud US) or `ca-bhs` (Beauharnois, OVHcloud Canada) — [§ Locations](#locations). Replaces S-155's `OVH_ENDPOINT` (a leftover that contradicts the location stops the run) |
| `OVH_SERVICE_NAME` | variable | R | both | Public Cloud project id (gh-setup can find or order it, [§ 4](#no-project-yet)) |
| `OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`, `OVH_CONSUMER_KEY` | secrets | R (or the next row) | both | the API token |
| `OVH_CLIENT_ID`, `OVH_CLIENT_SECRET` | secrets | instead of the token | both | an OAuth2 service account |
| `TF_STATE_BUCKET` | variable | R | both | the state bucket (created, versioned, by the first run; one made by hand is adopted) |
| `TF_STATE_ACCESS_KEY`, `TF_STATE_SECRET_KEY` | secrets | O | both | the S3 key of the Public Cloud user `terraform-state` — gh-setup makes and stores them through the API; missing or refused, each job takes them from the API (found or created) |
| `OVH_DOMAIN` | variable | O | both | zone of the public hosts (default `demo.northline.ca`) |
| `OVH_BUCKET_SUFFIX` | variable | O | both | if the bucket names are taken |
| `OVH_TFVARS` | variable | O | both | extra Terraform variables as HCL (e.g. `data_services`, `managed_postgres`) |
| `OVH_SERVER` | variable | O | ovh-vps | `instance` (default: a Public Cloud instance Terraform makes, hourly) or `vps` (ordered in the console) |
| `OVH_INSTANCE_FLAVOR` | variable | O | ovh-vps | the instance's flavor (default `b3-32`: 8 vCPU / 32 GB) |
| `OVH_SSH_PUBLIC_KEY` | variable | R with `instance` | ovh-vps | the deploy key's public half (gh-setup), registered with the instance by Terraform |
| `OVH_VPS_SERVICE_NAME` | variable | R with `vps` | ovh-vps | the VPS (its service name, e.g. `vps-….vps.ovh.us`; `.vps.ovh.ca` in Canada) |
| `OVH_ADMIN_CIDRS` | variable | R | ovh-vps | who may SSH besides the pipeline (≤ 3, space-separated) |
| `OVH_EDGE_FIREWALL` | variable | O | ovh-vps | `vps` only: `true` (default) / `false` (the instance has `ufw` on the host only) |
| `OVH_VPS_SSH_PRIVATE_KEY` | secret | R | ovh-vps | the pipeline's deploy key (made by gh-setup) |
| `OVH_VPS_KNOWN_HOSTS` | secret | R with `vps` | ovh-vps | the VPS's host key line (gh-setup; without it — and always for the instance, which doesn't exist before the run — the run trusts the key on first sight) |
| `OVH_API_ALLOWED_CIDRS` | variable | O | ovh-mks | who may reach the Kubernetes API (the runner is added for each run) |
| `OVH_NODE_COUNT`, `OVH_NODE_FLAVOR` | variables | O | ovh-mks | `2`, `b3-16` |
| `REGISTRY_PULL_TOKEN` | secret | O (recommended) | both | classic token, `read:packages` |
| `REGISTRY_PULL_USERNAME` | variable | O | both | that token's owner (default: the run's actor) |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET` | secrets | O | both | Stripe **test** mode |
| `SMS_AUTH_TOKEN` | secret | O | both | Twilio |
| `SMS_ACCOUNT_ID`, `SMS_FROM` | variables | O | both | Twilio |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_STARTTLS`, `EMAIL_FROM` | variables | O (an email way is needed for sign-ups) | both | an SMTP relay ([§ Email](#email)) |
| `SMTP_PASSWORD` | secret | with `SMTP_USERNAME` | both | the relay's password / key |
| `OVH_DEMO_MAILPIT` | variable | O | both | `true` = the demo inbox at `mail.<domain>` instead of delivering |
| `MAILPIT_PASSWORD` | secret | O | both | the inbox password (user `demo`; gh-setup makes it) |
| `EMAIL_API_KEY`, `KAFKA_SASL_JAAS_CONFIG` | secrets | O | both | SendGrid's API; an external Kafka |
| `TOTP_KEY`, `WEBHOOK_SECRET_KEY`, `EMAIL_UNSUBSCRIBE_KEY`, `KMS_LOCAL_KEY`, `STUDIO_/CONSUMER_/CONSOLE_BFF_SECRET` (+ `_HASH`), `DB_PASSWORD`, `REDIS_PASSWORD`, `MEILISEARCH_API_KEY` | secrets | O | both | only to impose values; otherwise generated once in the cluster |

Repository level (not per environment; the image build job has no environment): variable `REGISTRY` (a registry
other than ghcr.io, e.g. an OVHcloud Managed Private Registry) with secrets `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`
to push; `MAVEN_MIRROR_*` as for [deploy.yml](deploy.md).

## Contents

- [Today: from a new OVH account to a running demo](#today-from-a-new-ovh-account-to-a-running-demo) — the pipeline, US
  first ([data residency](#data-residency), [locations](#locations), [Canada later](#canada-later),
  [secrets and variables](#pipeline-secrets-and-variables), [local alternative](#the-local-alternative-no-github-actions))
- [The two shapes](#the-two-shapes)
- [Cost estimate](#cost-estimate)
- [What you need](#what-you-need)
- [Account and API credentials](#account-and-api-credentials)
- [ovh-vps step by step](#ovh-vps-step-by-step) (V1–V10)
- [ovh-mks step by step](#ovh-mks-step-by-step) (M1–M8)
- [Secrets](#secrets)
- [Images](#images)
- [DNS](#dns)
- [Email](#email) — SMTP relays (Brevo, Gmail, OVHcloud email), the demo inbox, and what happens with none
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
| Location (`OVH_LOCATION`) | a Public Cloud instance (default) in the location's region, or a VPS in its datacentre (Vint Hill / Hillsboro / Beauharnois); Object Storage there | Public Cloud region `US-EAST-VA-1` / `US-WEST-OR-1` / `BHS5` ([§ Locations](#locations)) |
| Kubernetes | **k3s** on the VPS (one node; Traefik off), installed over SSH by `scripts/ovh/bootstrap-k3s.sh` | **Managed Kubernetes**, free plan (control plane included, single zone), node pool `general`: 2 × `b3-16` (4 vCPU / 16 GB), hourly |
| Network | the VPS's public IPv4; **OVHcloud Network Firewall** in front (Terraform) and `ufw` on the host: SSH only from `admin_cidrs`, 80 and 443 from anywhere; the k3s API only through an SSH tunnel | private network (vRack) `10.60.0.0/24` with a gateway (`s`); the Kubernetes API optionally limited to `api_allowed_cidrs` |
| Postgres 17 + PostGIS | CloudNativePG on the VPS (`local-path` volume), daily base backup + WAL to Object Storage | **OVHcloud Managed PostgreSQL 17**, Essential `db1-4`, on the private network (`data_services.postgres.mode`: `managed`, `in-cluster` or `external`) |
| Valkey, Meilisearch, Kafka | on the VPS (`deploy/helm/northline-data`) | in the cluster (Cinder volumes), each `in-cluster` or `external` |
| Object storage | OVHcloud Object Storage (S3 API, the location's endpoint, e.g. `https://s3.us-east-va.io.cloud.ovh.us`), bucket `northline-ovh-vps-uploads` (+ `-pg-backups`), one S3 user per bucket | the same, `northline-ovh-mks-uploads` |
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
search-engine excerpts). Confirm each one in the OVHcloud console of the location's site (us.ovhcloud.com with Vint
Hill selected, or ca.ovhcloud.com with Beauharnois) before ordering. US prices are in US$ before sales tax.

### `ovh-vps`

| item | per month | notes |
|---|---|---|
| **the instance (default, `OVH_SERVER=instance`)**: Public Cloud `b3-32`, 8 vCPU / 32 GB / 200 GB, hourly | ≈ $145–165 for a full month (≈ $0.20–0.23 an hour) | **unverified** (EU list price ≈ €0.18/h, scaled); billed only while it exists — a week of demos ≈ $35–40, `destroy` stops it. `b3-16` (16 GB) is half that but below the 24 GB the stack was sized for |
| or a VPS 8 vCore / 24 GB / 200 GB NVMe (`OVH_SERVER=vps`, ordered in the console; "VPS-4" in the 2025/2027 range; check the specs, the names move) | ≈ $23–28 | **billed by the month** (paying a month even for three weeks); unlimited traffic. US: ≈ $27.50 without commitment, ≈ $23.37 with 12 months (third-party listings); Canada: check the CA$ price. **≈ $120–135 a month less than the instance** for a full month |
| Object Storage (Standard, the location): uploads and Postgres backups, well under 50 GB | under $1 | ≈ €7/TB-month; internal traffic included |
| Network Firewall (VPS), IPv4 | $0 | included |
| **Total** | **instance ≈ $150–170 for a full month (by the hour); VPS ≈ $25–30** | the VPS for the month, whatever runs |

Fallback if that VPS is out of stock in the datacentre: two 6 vCore / 12 GB VPSs would need a two-node k3s — not scripted here.

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
  the shared state from a Mac also needs the `aws` CLI (`make ovh-tf-init` creates the bucket with it), `curl` and
  `openssl` (the API calls that make the state user).
- An OVHcloud account of the location's company with a payment method: **OVHcloud US** (us.ovhcloud.com) for
  `us-east-va` / `us-west-or`, **OVHcloud Canada** (ca.ovhcloud.com) for `ca-bhs`
  ([§ Account](#account-and-api-credentials)).
- Control of DNS for `northline.ca` (records under `demo.northline.ca`).
- A way to deliver email ([§ Email](#email): an SMTP relay, or the demo inbox): accounts are confirmed with an email
  code (S-154). A Stripe account in **test mode** ([stripe.md](stripe.md)) and a Twilio account
  ([README § SMS](README.md#sms-and-voice-codes-s-8)) are optional for a first demo
  ([§ 6](#6-email-needed-stripe-and-twilio-optional)); `make demo-data` needs Twilio (it registers by phone).
- An image registry the cluster can pull from ([§ Images](#images)).

Everything below runs from the repository root.

## Account and API credentials

1. **Account.** Create an account on the site of the location's company — **us.ovhcloud.com** for the US locations
   (API `ovh-us`), **ca.ovhcloud.com** for `ca-bhs` (API `ovh-ca`); the subsidiary decides the currency, the taxes and
   the API endpoint, and the accounts are separate. Add a payment method (Billing › Payment methods).
2. **Public Cloud project** (both targets: Object Storage lives in one). Public Cloud › Create a project, name
   `northline-dryrun` (or `scripts/ovh/project.sh ensure` orders it — [§ No project yet](#no-project-yet)). Its id (32 hex characters, top left of the project) is Terraform's `service_name`. A project
   costs nothing by itself. Check that the location's region (`US-EAST-VA-1`, `US-WEST-OR-1` or `BHS5`) is offered (Object Storage, Managed Kubernetes and, for `ovh-mks`,
   Managed Databases for PostgreSQL 17 with PostGIS).
3. **API credentials for Terraform.** Either an application key set (simplest) — create it on the token page of the
   location's API ([§ Locations](#locations)) with the rights below, for the duration of the dry run:

   ```text
   https://api.us.ovhcloud.com/createToken/       (US; Canada: https://ca.api.ovh.com/createToken/)
   rights: GET/POST/PUT/DELETE on /cloud/*   (Public Cloud: storage, users, instance, kube, databases, network)
           only with server = "vps":  GET on /vps/* (read the VPS), GET/POST/PUT/DELETE on /ip/* (the Network Firewall)
   ```

   ```sh
   export OVH_LOCATION=us-east-va OVH_APPLICATION_KEY=… OVH_APPLICATION_SECRET=… OVH_CONSUMER_KEY=…
   ```

   or an OAuth2 service account (Identity and Access Management › Service accounts) with a policy on the project:
   `export OVH_CLIENT_ID=… OVH_CLIENT_SECRET=…` (and `ovh_location` in `terraform.tfvars` — the provider's endpoint
   follows it). Keep them out of shell history and files in the
   repository; revoke them at teardown.
4. **Terraform state.** Local state is fine for a demo (it holds the S3 secret keys and, for `ovh-mks`, the database
   passwords — keep it on one machine; `*.tfstate` is git-ignored). Shared state: an Object Storage bucket as an S3
   backend, `backend.hcl.example` in each root.

## ovh-vps step by step

### V1. The server

Default (`server = "instance"` in `terraform.tfvars`, with `ssh_public_key`): nothing to order — V2's `terraform apply`
makes the Public Cloud instance (Ubuntu 24.04, `instance_flavor`, hourly) and prints its IPv4. The rest of this
section is for `server = "vps"`.

**Order the VPS (console).**

Bare Metal Cloud › Virtual Private Servers › Order: **8 vCore / 24 GB / 200 GB NVMe** (check the specs, not the plan
name), datacentre **the location's** (Vint Hill for `us-east-va`, Hillsboro for `us-west-or`, Beauharnois for
`ca-bhs`), image **Ubuntu 24.04**, your SSH public key, no options needed (the daily
automated backup is included in that range per the research — unverified). Pay; it is ready in minutes. Note its
service name (`vps-….vps.ovh.us` / `….vps.ovh.ca`) and IPv4.

Why the console and not Terraform: provider 2.22 has an `ovh_vps` resource that orders through the order workflow,
but it needs the catalogue's plan code for the current range in the datacentre and a default payment method, and an apply
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
the location's region: `US-EAST-VA-1` by default), the node pool (2 × `b3-16`, hourly), Managed PostgreSQL 17 Essential `db1-4` on the private network (only the
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
`SMS_PROVIDER=twilio` on for northline-auth), the email settings of [§ Email](#email) (`SMTP_*` + `EMAIL_FROM`, `OVH_DEMO_MAILPIT` + `MAILPIT_PASSWORD`, or
`EMAIL_API_KEY` for SendGrid; with none, emails — codes included — are only in the apps' logs),
`KAFKA_SASL_JAAS_CONFIG` (an external Kafka), `REGISTRY_SERVER`/`_USERNAME`/`_PASSWORD`.

**From the pipeline** the same script reads every key from environment variables instead
(`OVH_SECRETS_SOURCE=env`: the GitHub environment's secrets and variables, [§ Today](#pipeline-secrets-and-variables)).
A generated key without a value there is taken from the cluster's `secret/northline-generated`, or generated once and
kept there, so redeploys never change it; each generated value is masked in the log. Stripe is optional in both modes
(without `STRIPE_SECRET_KEY`: the fake payment gateway, identity and age checks on the local fakes — [§ 6](#6-email-needed-stripe-and-twilio-optional)).
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

## Email

Every email of the platform — the **sign-up and password-reset codes** (northline-auth, S-154), invitations and
notifications (api, worker) — goes out the same way, chosen by `scripts/ovh/deploy.sh` from the settings (pipeline:
the environment's variables and secrets; Mac: `.ovh/<target>.env`), first match wins:

1. `OVH_DEMO_MAILPIT=true` — the demo inbox (below); nothing is delivered.
2. `SMTP_HOST` set — `EMAIL_PROVIDER=smtp` for api, auth and worker with `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`,
   `SMTP_PASSWORD` (secret), `SMTP_STARTTLS`.
3. `EMAIL_API_KEY` set — SendGrid's API ([email.md](email.md)).
4. none — `EMAIL_PROVIDER=local`: the text of each email, codes included, in the sending app's log (§ 6).

`EMAIL_FROM` (e.g. `Northline demo <you@example.com>`, no commas) must be an address the relay lets you send from;
without it the sender is `no-reply@<OVH_DOMAIN>`, which a relay will usually refuse or spam-folder. Only **port 587
with STARTTLS** is supported (no implicit TLS on 465); with a login, STARTTLS is required. Settings change: update
them, then `deploy` (pipeline) or `make deploy` + `RESTART=1`.

The three easiest relays (exact settings as their documentation gave them; menu names and limits **unverified** —
check them when you sign up). Each one stores and sends mail outside Canada: fine for fake demo data, not a decision
for production ([email.md](email.md) covers that).

| | **Brevo** (free: 300 emails a day) | **Gmail** with an app password | **OVHcloud email** (MX Plan with a domain at OVHcloud, or Email Pro) |
|---|---|---|---|
| sign-up | brevo.com account; *Senders, domains* › add and confirm the sender address (or authenticate your domain) | a Google account with **2-Step Verification on**; *Google Account › Security › App passwords* › create one (16 characters) | Web Cloud › Emails › the offer › create the mailbox |
| `SMTP_HOST` | `smtp-relay.brevo.com` | `smtp.gmail.com` | MX Plan `ssl0.ovh.net`; Email Pro `pro1.mail.ovh.net` (the offer's *General information* shows yours) |
| `SMTP_PORT` / `SMTP_STARTTLS` | `587` / `true` | `587` / `true` | `587` / `true` |
| `SMTP_USERNAME` | the SMTP **login** on *SMTP & API › SMTP* (looks like `…@smtp-brevo.com`, not your account email) | your full Gmail address | the full mailbox address |
| `SMTP_PASSWORD` | an **SMTP key** made on the same page | the app password (no spaces) | the mailbox password |
| `EMAIL_FROM` | `Northline demo <the confirmed sender>` | `Northline demo <your Gmail address>` (Gmail rewrites any other From) | `Northline demo <the mailbox>` |
| good for | a demo with real sign-ups | a quick test (≈ 500 a day; looks personal) | when the domain is already at OVHcloud |

Others work the same way: SendGrid SMTP (`smtp.sendgrid.net`, user `apikey`, password = an API key, a verified
sender), Mailgun (`smtp.mailgun.org`, `postmaster@<your domain>`). For mail that lands in inboxes rather than spam, send
from your own domain and add the SPF/DKIM records the relay shows.

### The demo inbox (private demos)

`OVH_DEMO_MAILPIT=true` installs **Mailpit** in the namespace (chart value `demoMail`, image `axllent/mailpit`): api,
auth and worker send every email to it over SMTP, and **nothing leaves the cluster**. Its inbox is at
`https://mail.<OVH_DOMAIN>` (the wildcard record already covers it; Let's Encrypt issues its certificate like the
others) behind HTTP basic auth — user `demo`, password `MAILPIT_PASSWORD` (`make ovh-gh-setup` generates it and
shows it once; from a Mac it is in `.ovh/<target>.env`). Only the edge reaches the inbox and only this release's apps
reach its SMTP port (NetworkPolicy). Messages live in memory (the latest 1000; a restart empties it).

For a demo where you sit with the people signing up: they register with any address (real or not), you open the
inbox and read out — or show — the 6-digit code. Anyone with the inbox password reads **every** email, so share it
only with the demo's audience, and switch to a relay (unset `OVH_DEMO_MAILPIT`, set `SMTP_*`, `deploy`) before real
people use their own addresses. The chart refuses it in staging and prod.

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
  Object Storage over S3 (the api's S3 adapter and CloudNativePG's barman against `s3.us-east-va.io.cloud.ovh.us` or
  `s3.bhs.io.cloud.ovh.net`), the OVHcloud US API (`ovh-us`), the
  Network Firewall, the OVHcloud load balancer with `externalTrafficPolicy: Local`, Let's Encrypt — all unexercised.
- **Unverified facts to check in the console:** every price; VPS stock, range names and the service name's suffix in
  Vint Hill / Beauharnois and their billing terms; each location offering the MKS free plan, Managed PostgreSQL 17 with
  PostGIS and Object Storage ([§ Locations](#locations) has what was found); the `us-west-or` S3 endpoint; the Object
  Storage region names `US-EAST-VA` / `US-WEST-OR`; the database region names; whether a node pool may sit at 0 nodes; the load
  balancer's need for the gateway; the Network Firewall on a VPS IP; whether the OVHcloud load balancer health-checks
  `Local` services (two Envoy proxies on two nodes avoid relying on it).
- **Keys:** `KMS_PROVIDER=local` only works under `dev`. Staging or prod on OVHcloud need an OVHcloud KMS (OKMS)
  adapter for signing and sealing — not built.
- **Toronto, more US regions:** `locations.json` holds `us-east-va`, `us-west-or` and `ca-bhs`; Toronto (or a US
  Local Zone) is one entry there once its services are confirmed, plus the allowed list in both roots' `ovh_location`.
- **The provider version:** the roots pin `ovh/ovh ~> 2.22`; it was built from source here (the registry was
  unreachable) — the first real `terraform init` downloads it from the registry.
