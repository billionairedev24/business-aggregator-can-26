# Load and soak test results (S-119)

What the load tests of [load-testing.md](../runbooks/load-testing.md) measured, what they found and what was fixed.
Every number below comes from **one small, shared machine**; none from staging, which doesn't exist yet. Read them as
a lower bound for one api replica and as the shape of the curves, not as the platform's capacity — that is the
staging run's job ([§ Still to run on staging](#still-to-run-on-staging)).

## How to read these numbers

| | |
|---|---|
| machine | 4 vCPUs (Intel Xeon, 2.8 GHz), 15 GiB RAM, **shared**: the k6 generator, the api, Postgres, Elasticsearch and other agents' builds ran on the same cores |
| stack | `make load-stack-up`: one api replica (`local` profile with dev auth, INFO logging, 1 GiB heap, Hikari pool 10 unless said otherwise, the live bus in memory), Postgres 17 + PostGIS, Elasticsearch 9.1 (512 MiB heap), the fake payment gateway; Docker's port proxy between them (~0.1 core under load) |
| data | `loadtest/seed/seed.sh` defaults: 150 kitchens, 150 providers, 230 shops, 900 business people, 3,000 customers (≈ 0.5 GB in Postgres, 2,776 search documents per language) |
| commit | branch `qa/s-119-load` (from main `d231474a`), each run on the code of the fixes listed before it |
| scale | `LOAD_SCALE=0.25` for load and soak = a quarter of the target, i.e. about 0.75 × the launch peak; the stress run ramps 0.125 → 1.25 × the target |
| per core | at ~60 req/s plus 112 open KDS streams the api process used ≈ 0.85 core, Postgres ≈ 0.15, Elasticsearch ≈ 0.12, k6 ≈ 0.13: **≈ 70 req/s of this mix per api core** (≈ 1.2 ms of api CPU per request on average, 0.2 ms of Postgres) |

## Results

### Load — `LOAD_SCALE=0.25`, 5 min at the rate after a 1 min ramp (`make load LOAD_SCALE=0.25 LOAD_DURATION=5m`)

All thresholds held. ≈ 63 req/s (≈ 15.6 req/s per vCPU of the whole box) plus 112 open SSE streams, 0 errors.

| scenario | req/s | p95 | p99 | errors (5xx / none) | SLO (threshold) | pass |
|---|---:|---:|---:|---:|---|:---:|
| search + landing pages | 36.9 | 38 ms | 49 ms | 0 % | p95 < 2 s, p99 < 2.5 s, no 429 | ✓ |
| checkout — food (start + confirm) | 1.0 | 34 ms | 48 ms | 0 % | 99 % < 2.5 s, 99.9 % not 5xx | ✓ |
| checkout — goods (cart → place) | 0.4 | 44 ms | 65 ms | 0 % | 99 % < 2.5 s, 99.9 % not 5xx | ✓ |
| checkout — booking (slots → hold → pay → confirm) | 0.1 | 71 ms | 78 ms | 0 % | 99 % < 2.5 s, 99.9 % not 5xx | ✓ |
| checkout calls of the SLO (`slo_checkout_latency`) | — | 49 ms | 69 ms | 0 % | 99 % < 2.5 s | ✓ |
| KDS — board and badge refreshes, ticket steps | 7.7 | 30 ms | 41 ms | 0 % | — | ✓ |
| KDS — ticket delivery, placed → on the screen | 199 tickets | 29 ms | 36 ms | — | 99.5 % < 5 s | ✓ |
| KDS — stream open → `ready` | 339 streams | 8 ms | 14 ms | 0 % | 99.5 % < 2 s | ✓ |
| nav badges — Studio | 4.0 | 25 ms | 34 ms | 0 % | p95 < 2 s | ✓ |
| nav badges — consumer cart count | 13.9 | 6 ms | 10 ms | 0 % | p95 < 2 s | ✓ |
| nav badges — console | 0.03 | 6 ms | 7 ms | 0 % | p95 < 2 s | ✓ |

The KDS ticket-delivery table above is from the run before its measurement was corrected (it also counted later
steps of tickets placed while a screen reconnected, which produced a 78 s outlier); the soak below measures it
correctly.

### Stress — `LOAD_SCALE=0.5`, 0.25 → 2.5 × that over 8 min, pool 20 (`make load-stress …`)

| minute | offered | req/s answered | p95 | 5xx / no answer |
|---:|---|---:|---:|---:|
| 0–1 | 0.125–0.25 × target | 47–93 | 40 ms | 0 |
| 2 | 0.5 × | 153 | 51 ms | 0 |
| 3 | 0.75 × | 281 | 421 ms | 0 |
| 4 | 1 × | 309 | 5.1 s | 445 |
| 5 | 1.25 × | 393 | 8.2 s | 918 |
| 6–7 | 1.25 × (hold) | 436–449 | 5.7–11.9 s | 557–886 |

**The knee on this box is ≈ 250–280 req/s**, about 1.1 × the target's request rate (the target's mix is ≈ 242 req/s
plus 450 streams) — with the generator, the api, Postgres and Elasticsearch sharing 4 vCPUs, i.e. **≈ 65–70 req/s per
vCPU of the whole box**. Past it every connection of the pool is busy, requests wait the full
`DB_CONNECTION_TIMEOUT_MS` (5 s) and are shed with 503 `overloaded`; the api recovered by itself when the load went
away (no OOM, no deadlock — see the findings for the two earlier stress runs that did neither). What saturates first:
the shared CPU (Postgres for the landing pages' city-wide reads, the api for everything else), then the connection
pool.

### Soak — `LOAD_SCALE=0.25`, 15 min (`make soak LOAD_SCALE=0.25`)

All thresholds held for the whole run, with nothing creeping: ≈ 69 req/s and 112 open KDS streams (reconnecting
every 2 minutes), p95 flat at 32–38 ms minute after minute, 0 errors.

| scenario | req/s | p95 | p99 | errors | SLO (threshold) | pass |
|---|---:|---:|---:|---:|---|:---:|
| search + landing pages | 38.9 | 36 ms | 48 ms | 0 % | p95 < 2 s, p99 < 2.5 s, no 429 | ✓ |
| checkout — food | 0.9 | 35 ms | 52 ms | 0 % | 99 % < 2.5 s, 99.9 % not 5xx | ✓ |
| checkout — goods | 0.4 | 58 ms | 87 ms | 0 % | 99 % < 2.5 s, 99.9 % not 5xx | ✓ |
| checkout — booking | 0.1 | 80 ms | 136 ms | 0 % | 99 % < 2.5 s, 99.9 % not 5xx | ✓ |
| checkout calls of the SLO | — | 59 ms | 99 ms | 0 % | 99 % < 2.5 s | ✓ |
| KDS — refreshes and ticket steps | 8.6 | 35 ms | 54 ms | 0 % | — | ✓ |
| KDS — ticket delivery, placed → on the screen | 573 tickets | 33 ms | 49 ms (p99.5 52 ms, max 62 ms) | — | 99.5 % < 5 s | ✓ |
| KDS — stream open → `ready` | 904 streams | 10 ms | 18 ms (p99.5 27 ms) | 0 % | 99.5 % < 2 s | ✓ |
| nav badges — Studio | 4.1 | 27 ms | 40 ms | 0 % | p95 < 2 s | ✓ |
| nav badges — consumer cart count | 14.6 | 7 ms | 12 ms | 0 % | p95 < 2 s | ✓ |
| nav badges — console | 0.03 | 12 ms | 13 ms | 0 % | p95 < 2 s | ✓ |

**No leak in 15 minutes:** the api's live set after a forced full GC at the end was 142 MB, the same as the lowest old
generation of the first minutes (147 MB); threads stayed at 44–46, RSS levelled at ~1.6 GB (1 GiB heap), never more
than 4 busy database connections. The old generation rose ~8 MB/min between G1's mixed collections, which is
promotion, not retention (the full GC took it back). A 2-hour soak on staging is still needed for slower leaks
(Valkey pub/sub subscriptions, the hot-query cache) — the in-memory live bus of one replica was used here.

## Findings

| # | finding | severity | status |
|---|---|---|---|
| F1 | **Pool deadlock.** `RegionCatalogue` reloaded its cache (every 60 s) under a lock while the reloading request waited for a database connection — and the requests holding all the connections were waiting on the lock. A minute into the stress run the api stopped answering for good (20 connections "idle in transaction", health check timing out). Any instance whose pool is saturated when the cache expires would do the same in production. | critical | **fixed** — one caller reloads while the others keep the old rows; first read at start-up (`RegionCatalogueTest`) |
| F2 | **Overload ends in OutOfMemoryError.** Past the knee, requests queued up to Hikari's 30 s default for a connection; the queue (thousands of virtual threads) filled the 1 GiB heap and the api died. | high | **fixed** — `DB_CONNECTION_TIMEOUT_MS` = 5 s, then 503 `overloaded` + `Retry-After: 2` (`OverloadedTest`); the stress run now degrades and recovers |
| F3 | **N+1 on the provider list.** `GET /api/v1/public/services/{slug}/providers` read a rating summary (3 aggregates), a quality score and the next free slot (~20 calendar queries) per provider: 1,160 queries and 0.7 s for 41 providers, 2.5–2.8 s for 150. Home, the kitchens list and quote requests read a rating summary per business too. | high | **fixed** — ratings and quality scores in one query per page (`RatingQuery.summaries`, `QualityQuery.latestOf`); next free starts kept a minute per instance and refreshed in the background (`NextFreeSlotsTest`, `ReviewsApiTest`) |
| F4 | The nav badges' rating computed the Reviews screen's whole summary (star distribution and praise tags, three aggregates) on every Studio page. | low | **fixed** — one grouped query |
| F5 | **City-wide reads per landing page.** Home and the kitchens list read every business of the city (`PublicDirectory.active`: 541 rows, ~10 ms) and every kitchen's calendar row (`KitchenCalendarJdbc`: late tickets, menu-live and average-price subqueries per kitchen, ~6–8 ms) on each view: the two largest consumers of database time in every run (≈ 60 % of it). They grow with the number of businesses. The responses already say `Cache-Control: public, max-age=60/30`, but nothing between the consumer web and the api caches them. | medium | **fixed** (engineering follow-ups) — the home summary and the kitchens list's city-level part kept 30 s per instance (`PUBLIC_PAGES_CACHE_TTL`), dropped on visibility events: the city reads went from 69 % to 9 % of database time ([below](#landing-page-cache--engineering-follow-ups-f5)). An edge cache can still go in front of `/api/v1/public/**` |
| F6 | **The payout run is an N+1 every minute**: `PayoutService.runScheduled` reads each merchant's zone, schedule and today's payouts one by one (≈ 3 queries × merchants with a schedule, every minute, ≈ 1,600 queries/min with 541 merchants). Not user-facing; grows linearly with businesses. | low | **fixed** (engineering follow-ups) — two queries per run whatever the number of businesses (`ScheduledPayoutRunTest`) |
| F7 | A servlet filter that needs the database (here: dev auth) answers a pool timeout with **403** (error dispatch → access denied) instead of 503, with a stack trace per request. | low | **fixed** (engineering follow-ups) — `OverloadedFilter` answers 503 `overloaded` + `Retry-After` ahead of Spring Security (`OverloadedTest`) |
| F8 | Saving a first card twice at the same moment for one customer can answer 404/409 (two Stripe customers created; one loses). Only seen when the test reused customers across VUs. | low | open — the load test gives each VU its own customers |
| F9 | The fake payment gateway and fake saved cards keep every intent and card in memory (local and test only). Over a long local soak they grow (~1 KB per payment). | info | not a production path |
| F10 | `food-orders` calls (`POST /api/v1/me/food-orders`, `…/confirm`) are not in the checkout SLO's `uri` pattern (`/api/v1/me/(checkouts.*|bookings/checkout)`), though food is the busiest checkout. | medium | **fixed 2026-10-04** (owner decision): both are in `deploy/observability/slo/checkout.yaml` (availability and latency); quotes stay out; promtool tests in `northline-slo_test.yml` |
| F11 | Under the stress test's top load the api's old generation held ~0.8 GiB of 1 GiB (queued requests); prod's 2 GiB pods (1.5 GiB heap) have room, staging's must too. | info | capacity plan |

### Before and after the fixes — one page view, warm, 150 providers / kitchens in the market

| page | before | after | rating, quality and calendar queries per view |
|---|---:|---:|---|
| `GET /api/v1/public/services/{slug}/providers` | 2.1 s (3.8 s cold, 4,241 queries) | 23 ms (41 ms cold) | 1,057 → 2 |
| `GET /api/v1/public/home` | 254 ms | 31 ms | 302 → 1 |
| `GET /api/v1/public/kitchens` | 221 ms | 31 ms | 302 → 1 |

(The `main` jar and the branch's side by side on the same database and data; "before" on a JVM warmed by five
requests, "after" on the soak's.)

Fixes were verified by re-running the profiles; the earlier stress runs (pool 10 with the 30 s timeout: OOM at
~180 req/s; pool 20 with the 5 s timeout before F1: deadlock after 7 min) are kept in the findings, not in the tables.

### Landing page cache — engineering follow-ups (F5)

`make load-smoke`, then the search scenario (search + landing pages) at `LOAD_SCALE=0.25` for 1 minute, once with the
cache (`PUBLIC_PAGES_CACHE_TTL=30s`, the default) and once without (`0s`, i.e. before), on the same api jar and data.
A smaller seed than the tables above (`SEED_BUSINESSES=60 SEED_SHOP_UNITS=4 SEED_CUSTOMERS=1000`: the machine had
about 2 GB of disk), same shared 4-vCPU box, 2026-10-04.

| | without the cache | with the cache (30 s) |
|---|---:|---:|
| database time in the minute (pg_stat_statements, all statements) | 5.9 s | 1.7 s (−71 %) |
| kitchens' calendar read (`KitchenCalendarJdbc`): calls · time | 654 · 2.86 s | 16 · 0.10 s |
| city's businesses (`PublicDirectory.active`): calls · time | 450 · 1.22 s | 8 · 0.04 s |
| share of database time of those city reads | 69 % | 9 % |
| search + landing requests, p95 / p99 | 30 ms / 54 ms | 40 ms / 82 ms |
| errors | 0 | 0 |

The latency difference is within this box's noise (the first minute includes the api's warm-up; p95 of the second
minute: 24 ms without, 28 ms with) — at a quarter of the target the database was never the bottleneck. What the cache
removes is the work that grew with the number of businesses: the database time of the landing pages is now flat in
their traffic (one read per market and language every 30 s per replica). `make load-smoke`: every journey passed in
both runs (k6 exit 0). Results: `loadtest/results/20261004T1421*` and `…T1424*` (not committed).

## Still to run on staging

The acceptance criterion — the target load sustained with p95 within the SLOs — is shown here only at a quarter of
the target on one replica. Before the launch, on the staging release sized as prod
([capacity.md](capacity.md)), with the procedure of [load-testing.md § Staging](../runbooks/load-testing.md#staging):

1. `make load-smoke TARGET=staging`, then `make load TARGET=staging LOAD_SCALE=1` (10 min at the target): every
   threshold green, the HPA's replica count and CPU per pod recorded.
2. `make load-stress TARGET=staging LOAD_SCALE=1`: where the knee is with the HPA at its maximum; the per-pod rate at
   the knee replaces the per-core estimate above.
3. `make soak TARGET=staging LOAD_SCALE=1` (2 h): `jvm_gc_live_data_size_bytes`, connections, threads and Valkey
   memory flat after warm-up.
4. With `LIVE_BUS=redis` (staging's default): the KDS streams across replicas through Valkey pub/sub — the local runs
   used the in-memory bus of one replica.
5. Through the BFFs: the same journeys with session cookies (studio-bff, consumer-bff), adding token relay and Valkey
   sessions to the path.
6. Stripe in test mode instead of the fake gateway: the checkout SLO's 2.5 s includes Stripe's and the tax
   provider's latency, which no local run has.

Prerequisites not built yet: staging load-test data, the token harvester, the k6 image (runbook § Staging).
