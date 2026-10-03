# Launch capacity plan (S-119)

How big each piece of the platform must be for the launch market's peak hour with 3× headroom, from the load-test
target ([load-testing.md](../runbooks/load-testing.md)) and what one api replica did on the local stack
([results.md](results.md)). The Helm values and Terraform sizes it refers to are the ones in the repository; the
numbers are **estimates until the staging load test** — they come from a 4-core shared machine, per core, with the
uncertainty that brings (see [results.md § How to read these numbers](results.md#how-to-read-these-numbers)).

## Assumptions

One market (the launch province's first city), three months after go-live, the dinner peak (17:30–19:30 in the
market's time zone) — the busiest hour for kitchens and the busiest for browsing:

| | launch | why |
|---|---|---|
| businesses | 300: 100 kitchens, 120 service providers, 80 shops | S-120's pilot (≥ 10) grown by the go-to-market plan's first quarter |
| business people signed in at the peak | 250 | 1.5 per kitchen (the line's screen, the owner's phone), 0.5 per other business |
| kitchen screens open | 150 | 100 kitchens × 1.5 |
| registered customers | 20,000 | |
| customer sessions at the peak | 1,200 | 6 % of the registered customers in the peak hour |
| searches and suggestions | 50 /s | a session asks one every 24 s while browsing |
| landing pages (home, kitchens, a category's providers) | ~10 /s | a fifth of the browsing |
| food orders | 1,000 /h (0.28 /s) | 10 per kitchen in the dinner hour |
| shop orders | 240 /h (0.07 /s) | pooled evening run |
| bookings | 90 /h (0.025 /s) | services book evenings for later days |
| Studio nav badges | 5.6 /s | one refetch per member every ~45 s (page changes, live events, the 60 s safety refresh) |
| consumer cart count | 20 /s | one page view a minute per session |

**The target is 3× that** (`LOAD_SCALE=1`): a good week, a promotion, a second city's soft launch, or a bad deploy
that doubles the calls — without running out of anything. Beyond 3× is the stress profile's job: where it breaks.

## What the target means for each component

| component | at the target (3×) | today's prod values | enough? | change |
|---|---|---|---|---|
| … filled from the measured results below | | | | |

## Kafka partitions

## Elasticsearch shards

## Postgres

## Valkey

## HPA settings
