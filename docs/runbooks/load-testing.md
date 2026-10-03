# Load and soak testing (S-119)

How to load-test search, checkout, the kitchen display (KDS) and the nav badges, locally and on staging, and how to
read the results. The numbers measured so far are in [docs/perf/results.md](../perf/results.md); the launch sizing
they feed is [docs/perf/capacity.md](../perf/capacity.md).

> **Only the local stack has been load-tested.** No staging environment exists yet, so the staging procedure below has
> never run; its numbers are the ones that decide the launch (the acceptance criterion: the target load sustained with
> p95 within the SLOs).

## Contents

- [What is tested](#what-is-tested) — the scenarios and what each measures
- [Profiles and the target](#profiles-and-the-target) — smoke, load, stress, soak; LOAD_SCALE
- [Pass or fail](#pass-or-fail) — the thresholds are the S-113 SLOs
- [Tooling](#tooling) — k6 with the SSE extension, the files in `loadtest/`
- [Local](#local) — the dedicated stack, the seed data, the runs
- [Staging](#staging) — the real load and soak
- [Reading the results](#reading-the-results)
- [Rate limits](#rate-limits) — why a load test does not measure 429s
- [Variables](#variables)

## What is tested

| scenario | what the virtual users do | measured |
|---|---|---|
| `search` | anonymous searches as the consumer web and app send them: half browse a kind with no text, a third type a query, the rest ask for suggestions; 35 % in French (`lang=fr`, `listings_fr`); a third near the person (`lat`/`lng`, half sorted by distance), a quarter with a filter, a tenth go on to page 2 | `GET /api/v1/search`, `/search/suggest` |
| `checkout_food` | a customer with a saved card orders pickup from a kitchen whose screens are open: quote → start (card authorized) → confirm (placed) | `POST /api/v1/me/food-orders`, `…/{id}/confirm` |
| `checkout_goods` | 1–3 products from different shops into the cart → checkout page → quote → start → place, saved card | `POST /api/v1/me/checkouts`, `…/{id}/place` (the checkout SLO's calls) |
| `checkout_booking` | the provider's calendar (7 days) → hold a free slot (retrying when another customer took it) → pay the deposit into escrow → confirm | `POST /api/v1/me/bookings/checkout` (SLO), `…/holds/{id}/confirm` |
| `kds` | one VU = one kitchen screen holding the Studio's live stream (`GET /api/v1/merchants/{id}/live`, server-sent events) for 2 min at a time, as `EventSource` does; on each `kitchen` event it refetches the board and the nav badges, as the Studio does, and works the tickets (accept → ready after 20 s → hand off after 40 s; whoever taps first, the other screens get a 409) | ticket delivery: placed → its event on the screen; stream open → `ready` |
| `badges_studio` | a business member's sidebar badges, in their language (30 % French) | `GET /api/v1/merchants/{id}/nav-badges` |
| `badges_consumer` | a signed-in customer's header cart count | `GET /api/v1/cart` |
| `badges_console` | the staff console's shell | `GET /api/v1/console/me` |

Running them together is the **mixed traffic** (`SCENARIOS=all`, the default). Every saved card, payment and booking
goes through the payment port: the fake gateway locally, stripe-mock or Stripe test mode on staging — Stripe confirms
the card in the browser, so the api receives the same calls either way. The courier app, sign-in (northline-auth) and
the BFFs are not in these scenarios (see [Staging](#staging) for the BFFs).

## Profiles and the target

The target is the **launch peak hour with 3× headroom** — assumptions and arithmetic in
[capacity.md § Assumptions](../perf/capacity.md#assumptions):

| scenario | at the peak | target (`LOAD_SCALE=1`) |
|---|---|---|
| search | 50 req/s (1,200 customer sessions × one search or suggestion per 24 s) | 150 iterations/s (≈ 165 req/s) |
| checkout_food | 0.28/s (100 kitchens × 10 orders an hour at dinner) | 0.84/s |
| checkout_goods | 0.07/s (240 shop orders an hour) | 0.2/s |
| checkout_booking | 0.025/s (90 bookings an hour) | 0.075/s |
| kds | 150 open screens (100 kitchens × 1.5) | 450 open streams (150 kitchens × 3 screens) |
| badges_studio | 5.6/s (250 Studio users, one refetch every ~45 s) | 17/s |
| badges_consumer | 20/s (1,200 sessions, one page view a minute) | 60/s |
| badges_console | a dozen staff every 5 minutes | 0.1/s |

| profile | shape | use |
|---|---|---|
| `smoke` | 30 s, a trickle of every journey (fixed, not scaled) | does every journey work against this target? Before any other profile, after every deploy of the stack |
| `load` | 1 min ramp, then `LOAD_DURATION` (10 min) at the target × `LOAD_SCALE` | the acceptance run: does the target hold within the SLOs? |
| `stress` | ramps 0.5× → 1× → 2× → 3× → 4× → 5× the target over `STRESS_DURATION` (12 min) | where is the knee (p95 climbs, errors start)? What saturates first? Expected to fail its thresholds at the top |
| `soak` | the load profile for `SOAK_DURATION` (2 h on staging; 15–20 min locally) | memory that only grows, connections or threads that leak, a queue that never drains |

`LOAD_SCALE` multiplies every rate and every screen count; staging runs the target (`1`), a laptop or a CI runner a
fraction of it (results normalised per core in [results.md](../perf/results.md)).

## Pass or fail

The thresholds are the [S-113 SLOs](alerting.md#slos) — a run fails (k6 exit 99) when its numbers would burn the
error budget faster than the SLO allows:

| SLO (deploy/observability/slo) | threshold (`loadtest/lib/slo.js`) |
|---|---|
| checkout availability 99.9 % | `slo_checkout_errors` (5xx or no answer on the checkout calls) < 0.1 % |
| checkout latency 99 % within 2.5 s | `slo_checkout_latency` p95 and p99 < 2.5 s |
| KDS ticket delivery 99.5 % within 5 s | `kds_ticket_delivery` p95 and p99.5 < 5 s — placed (the board's `placedAt`) → the event on the screen, transport included |
| KDS freshness 99.5 % within 2 s | `kds_stream_ready` p95 and p99.5 < 2 s — opening the stream → its `ready` event |
| (search, badges: no SLO) | the `NorthlineSlowRequests` alert's p95 < 2 s, p99 < 2.5 s; any 429 from search fails the run |
| every scenario | 5xx < 0.1 %, failed journeys < 1 %, checks > 99 % |

Sign-in and payouts, the other two SLOs, are not load-tested here (northline-auth and the payout job are not in these
journeys).

## Tooling

**k6** (Grafana, one Go binary, JavaScript scenarios) with **xk6-sse** for the KDS streams — stock k6 has no
server-sent events client. `make load-k6` (`loadtest/k6.sh`) builds k6 v1.3.0 + xk6-sse v0.1.12 once into
`loadtest/.bin` with Go (1.24+), or with the `grafana/xk6` image when there is only Docker, or uses `K6=<path>`.

```
loadtest/
  main.js              entry: SCENARIOS + PROFILE → k6 scenarios and thresholds
  lib/                 config (environment), profiles (rates, shapes), slo (thresholds), api (auth, requests), kitchens
  scenarios/           search.js, checkout.js, kds.js, badges.js
  seed/                clone.sql + seed.sh: the local load-test data
  stack.sh             the local load-test stack (up, api, statements, status, down)
  run.sh               a profile against TARGET → loadtest/results/<run>/ ; report.py turns it into report.md
  sample.sh            the local api's heap, old generation, threads, RSS and busy DB connections every 15 s
  targets/*.env        local, staging (never prod: run.sh refuses it)
```

CI: the manual `loadtest` workflow (GitHub Actions) / `PIPELINE_PART=loadtest` (GitLab) runs a profile against the
local stack on the runner ([ci.md](ci.md)). Never triggered automatically.

## Local

The load-test stack is separate from `make up`: its own compose project (`northline-load`), ports and volumes, so it
never touches your development data.

```sh
cd server && ./gradlew :api:bootJar :worker:bootJar && cd ..   # the jars the stack runs
make load-stack-up             # ~5 min the first time; LOAD_LOCK=<file> waits for a shared machine's lock
make load-smoke                # 30 s: every journey works?
make load LOAD_SCALE=0.2       # 11 min at a fifth of the target
make load-stress LOAD_SCALE=0.2
make soak LOAD_SCALE=0.2       # 15 min by default locally (SOAK_DURATION=20m …)
make load-stack-down           # stops the api, removes the containers and their data
```

`make load-stack-up` (`loadtest/stack.sh up`):

1. Postgres (with `pg_stat_statements`), Elasticsearch and Kafka in the `northline-load` project on :55432, :59200,
   :59092;
2. the api jar under `local` (dev auth) on **:18080** with `SEARCH_PROVIDER=elasticsearch`,
   `SEARCH_RATE_LIMIT_EXEMPT=127.0.0.1,::1`, INFO logging (the `local` profile's DEBUG would distort the numbers), a
   1 GB heap and `DB_POOL_SIZE=10` (`LOAD_API_HEAP`, `LOAD_DB_POOL_SIZE`); it migrates and applies the dev seed;
3. `seedCategories`, then **`loadtest/seed/seed.sh`**: clones the dev seed's businesses (Ravi Sandhu's provider,
   seller and kitchen with their team, listings, menus, hours and finance history), design 06's eight shops and the
   customer Amara Osei — `SEED_BUSINESSES=150`, `SEED_SHOP_UNITS=10`, `SEED_CUSTOMERS=3000` by default: 150 kitchens,
   150 providers, 230 sellers, 450 business people, 3,000 customers, about 1.8 M rows. Every id becomes a valid ULID
   starting with `7` (`seed.sh --clean` removes them all), e-mails, phones and slugs are made unique, locations are
   spread over ~8 km, kitchens open around the clock. It writes `loadtest/.data/manifest.json` (the ids the scenarios
   use). It refuses a database that isn't on localhost or has no dev seed;
4. the search indices (`SearchIndicesCommand`) and a full reindex from Postgres (`SearchReindexCommand`, S-71), from
   the worker jar; then Kafka is stopped (the api under `local` needs none) and the api restarted (a clean fake
   gateway).

To load-test your own `make up` api instead: `API_URL=http://localhost:8080 make load-smoke` after
`PGPORT=5432 loadtest/seed/seed.sh` — and run that api with `SEARCH_RATE_LIMIT_EXEMPT=127.0.0.1` and
`SEARCH_PROVIDER=elasticsearch`, or search will measure 429s and empty results.

**A small machine.** The load generator, the api, Postgres and Elasticsearch share its cores; the numbers are a lower
bound for one api replica and are normalised per core in [results.md](../perf/results.md). Keep local soaks short
(15–20 min is enough to see a leak in the old generation) and stop the stack afterwards.

## Staging

The decisive runs: the target (`LOAD_SCALE=1`) for 10 minutes, then the soak for 2 hours, against the staging
release that is going to prod, sized as prod will be ([capacity.md](../perf/capacity.md)).

1. **Data.** staging must hold the launch's shape: at least 150 kitchens, 150 providers, 230 shops and 3,000
   customers, all with the dev seed's kind of content — created through the api by an operator script or restored
   from a masked prod-shaped dump; `seed.sh` refuses anything but a local database. Then a full reindex
   ([search.md § 9](search.md#9-reindex-s-71)). Write the manifest (same format as the local one: `kitchens` with
   `ownerId` and dish ids, `providers` with `slug` and instant-book service ids, `sellers` with offer ids, `members`,
   `customers`, `staff`, `market`, `province`, `center`) to `loadtest/.data/staging-manifest.json`.
2. **Tokens.** dev auth does not exist outside `local`: each user in the manifest needs an access token from
   northline-auth (`AUTH_MODE=bearer`, `TOKENS_FILE` = `{"<userId>": "<token>"}`, `loadtest/.data/staging-tokens.json`).
   Tokens live 10 minutes, so a soak needs them renewed; **the token harvester is not built** (follow-up below).
3. **Rate limits.** Set `SEARCH_RATE_LIMIT_EXEMPT` on the staging api to the k6 pod's range (the cluster's pod CIDR)
   for the duration of the test, in `deploy/argocd/envs/staging/values.yaml` (`apps.api.configEnv`), and remove it
   afterwards. prod refuses the variable.
4. **Run k6 inside the cluster** — the api is not public (only webhooks are, [staging.md](staging.md)), and from
   inside the network the generator measures the api, not the internet:

   ```sh
   kubectl -n northline-staging create configmap loadtest --from-file=loadtest/ --dry-run=client -o yaml | kubectl apply -f -
   # a pod with the k6+xk6-sse binary (make load-k6 builds it for linux/amd64) mounting the config map and the data
   kubectl -n northline-staging run k6 --rm -it --image=<registry>/northline-k6:<tag> --overrides='…' -- \
     env TARGET=staging PROFILE=load LOAD_SCALE=1 k6 run --summary-export /out/summary.json main.js
   ```

   Size the k6 pod at 2 CPUs and 2 GiB for the target (450 streams plus ~250 req/s); one generator is enough.
5. **Watch** Grafana's `Northline · SLO · checkout` and `· kds` dashboards and the flow dashboards (S-111) while it
   runs: p95 per route, `hikaricp_connections_pending`, `jvm_gc_live_data_size_bytes`, the HPA's replicas, Postgres CPU
   and connections, Elasticsearch search latency, Valkey (the live bus and the search cache). The soak's verdict is
   the live set after GC and the connection counts: flat after the first 20 minutes, or a leak.
6. **Through the BFFs** (optional second pass): the same scenarios with session cookies against the studio-bff and the
   consumer-bff measure the token relay and the Valkey sessions too; that needs signed-in sessions per user (the same
   harvester, through `/bff/login`).

**Follow-ups (not built):** the staging data and token harvester (sign the load-test accounts in with their
authenticator secrets and keep their tokens fresh during the soak), and the k6 image. Until they exist the staging run
is a manual exercise, and the acceptance criterion is met only by the local evidence in
[results.md](../perf/results.md).

## Reading the results

Each run writes `loadtest/results/<UTC time>-<target>-<profile>/` (git-ignored):

| file | what |
|---|---|
| `report.md` | per scenario: requests, req/s, p95, p99, 5xx, failed journeys, the SLO thresholds and whether they held; the SLIs (ticket delivery, stream ready, checkout latency); a per-minute timeline (a stress run's knee); locally the api process over time |
| `summary.json` | k6's summary (every metric, every threshold) |
| `timeline.csv.gz` | every sample, for your own analysis |
| `server.csv` | local: heap, old generation, GC count, threads, RSS, busy DB connections every 15 s |
| `statements.txt` | local: the 30 statements that took the most database time during the run (`pg_stat_statements`) — where N+1 queries and missing indexes show up first |
| `k6.log` | k6's console, with a sample of the failed journeys' answers |

Copy what matters into [results.md](../perf/results.md) with the machine, the commit and the scale.

## Rate limits

A load test that hits rate limits measures 429s. The ones on these paths:

| limit | where | in a load test |
|---|---|---|
| search: 120 requests/min per client address (S-44) | api, per replica | the generator is exempt: `SEARCH_RATE_LIMIT_EXEMPT` (local stack: loopback; staging: the k6 pod range); refused under prod. A single 429 fails the run. |
| webhooks, AI budgets, courier pings | api | not on these paths |
| sign-in lookups and codes (S-9) | northline-auth | not on these paths (tokens are harvested before the run) |

Nothing else rate-limits checkout, KDS or badges: the Idempotency-Key stores (Valkey in the cloud, memory locally)
take one key per payment attempt.

## Variables

| variable | default | what |
|---|---|---|
| `TARGET` | `local` | `loadtest/targets/<TARGET>.env`: `local` or `staging` (`prod` is refused) |
| `API_URL` | local `http://localhost:18080`; staging the api Service | where the requests go |
| `SCENARIOS` | `all` | `search`, `checkout_food`, `checkout_goods`, `checkout_booking`, `kds`, `badges_studio`, `badges_consumer`, `badges_console`; `checkout`, `badges` |
| `LOAD_SCALE` | `1` | multiplies the target |
| `LOAD_DURATION`, `STRESS_DURATION`, `SOAK_DURATION` | `10m`, `12m`, `2h` (local soak `15m`) | how long |
| `AUTH_MODE`, `TOKENS_FILE` | `dev`; staging `bearer` + `.data/staging-tokens.json` | who the requests are |
| `MANIFEST` | `.data/manifest.json`; staging `.data/staging-manifest.json` | the seeded ids |
| `KDS_STREAM_S`, `KDS_COOK_S` | `120`, `20` | one stream's life before reconnecting; seconds from placed to ready (handed off at twice that) |
| `LOAD_LOCK` | — | local runs wait for this `flock` lock (a machine shared by several people or agents) |
| `SEED_BUSINESSES`, `SEED_SHOP_UNITS`, `SEED_CUSTOMERS` | `150`, `10`, `3000` | the local seed's size |
| `LOAD_API_PORT`, `LOAD_PG_PORT`, `LOAD_ES_PORT`, `LOAD_KAFKA_PORT` | `18080`, `55432`, `59200`, `59092` | the local stack's ports |
| `LOAD_API_HEAP`, `LOAD_DB_POOL_SIZE`, `LOAD_ES_HEAP`, `LOAD_LIVE_BUS` | `1g`, `10`, `512m`, `memory` | the local api's heap and pool, Elasticsearch's heap, `redis` to carry the live bus over Valkey as in the cloud |
| `SEARCH_RATE_LIMIT_EXEMPT` (api) | empty | the generators' addresses or CIDR ranges; refused under prod |
