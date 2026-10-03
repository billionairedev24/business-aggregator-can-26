# Launch capacity plan (S-119)

How big each piece of the platform must be for the launch market's peak hour with 3× headroom, from the load-test
target ([load-testing.md](../runbooks/load-testing.md)) and what one api replica did on the local stack
([results.md](results.md)). The Helm values and Terraform sizes it refers to are the repository's. **The numbers are
estimates until the staging load test**: they come from a 4-vCPU shared machine, normalised per core, with the
uncertainty that brings ([results.md § How to read these numbers](results.md#how-to-read-these-numbers)).

## Assumptions

One market (the launch province's first city), three months after go-live, the dinner peak (17:30–19:30 in the
market's time zone) — the busiest hour for kitchens and for browsing:

| | launch | why |
|---|---|---|
| businesses | 300: 100 kitchens, 120 service providers, 80 shops | S-120's pilot (≥ 10) grown over the first quarter |
| business people signed in at the peak | 250 | 1.5 per kitchen (the line's screen, the owner's phone), 0.5 per other business |
| kitchen screens open | 150 | 100 kitchens × 1.5 |
| registered customers | 20,000 | |
| customer sessions at the peak | 1,200 | 6 % of the registered customers in the peak hour |
| searches, suggestions and landing pages | 50 /s | a session asks one every 24 s while browsing; a fifth are landing pages |
| food orders | 1,000 /h (0.28 /s) | 10 per kitchen in the dinner hour |
| shop orders | 240 /h (0.07 /s) | the evening pooled run |
| bookings | 90 /h (0.025 /s) | services are booked in the evening for later days |
| Studio nav badges | 5.6 /s | one refetch per member every ~45 s (page changes, live events, the 60 s safety refresh) |
| consumer cart count | 20 /s | one page view a minute per session |

**The target is 3× that** (`LOAD_SCALE=1`): ≈ 242 requests/s of the mixed traffic plus 450 open kitchen-display
streams — a good week, a promotion, a second city's soft launch, or a deploy that doubles the calls, without running
out of anything. Beyond 3× is the stress profile's job.

## Sizing

| component | the target needs | prod today (values-prod, Terraform `envs/*/prod`) | verdict |
|---|---|---|---|
| **api** pods | ≈ 3.5 vCPU of api at ≈ 70 req/s per vCPU (≈ 1.2 ms CPU per request of this mix) → **5 pods** of 1 vCPU at a 70 % CPU target, 6 at 60 %; the launch peak alone ≈ 1.2 vCPU (the 3-pod minimum carries it at ~40 %) | HPA 3–10 pods, request 1 vCPU, 2 GiB (1.5 GiB heap) | **enough**; target CPU lowered to 60 % and a faster scale-up (below) |
| **kitchen-display streams** | 450 SSE streams ≈ 45–150 per pod; each is an async request (no thread), a keep-alive every 25 s, a 10-minute life | — | enough; staging must confirm the Valkey live bus at this fan-out |
| **database connections** | busy connections stayed ≤ 4 per replica at ~0.75 × peak; pool saturation is the stress test's second limit after CPU | `DB_POOL_SIZE` 10 per pod: api ≤ 100, auth ≤ 60, worker 10 → ≤ 170 | **keep 10 per pod**: more connections don't add database CPU; requests that wait over 5 s are shed with 503 (`DB_CONNECTION_TIMEOUT_MS`) |
| **Postgres** | ≈ 0.15 vCPU per 60 req/s locally → ≈ 0.6–1 vCPU at the target, most of it the landing pages' city-wide reads (F5), which grow with businesses | AWS `db.m7g.large` (2 vCPU, 8 GiB, multi-AZ), 100 GB; GCP / Azure equivalents | **enough at launch**; plan `xlarge` when peak CPU passes 60 % or F5 isn't fixed before ~600 businesses; storage is far from a limit (the load-test data for 541 businesses is 0.5 GB) |
| **Elasticsearch** | ~6,000 documents per language at launch (2,776 for the test's 541 businesses), 50 searches/s at the peak, p95 36 ms end to end locally | 1 primary shard, `auto_expand_replicas: 0-1`; 4 GB × 2 zones | **enough**: one shard is right below ~10–50 GB per index; the replica on the second zone carries reads and failover. Re-shard (a reindex, S-71) only when an index passes ~20 GB |
| **Kafka** | ≈ 1–5 events/s at the peak (orders, kitchen steps, listings, messages) | 6 partitions per topic (DLQ 1), 3 brokers (MSK `kafka.m7g.large`) | **enough**: 6 partitions allow 6 consumers per group, the worker runs 2 replicas; partitions are never lowered and raising one re-maps keys — raise only for a topic whose consumer lags (`NorthlineConsumerLag`) |
| **Valkey** | sessions, the live bus (450 streams' signals), search's hot-query cache, idempotency keys: well under 1 GB and a few thousand ops/s | `cache.m7g.large`, 2 replicas | **enough** |
| **worker** | search indexing, notifications, webhooks at the event rate above | 2 replicas, 250m / 768 MiB | enough |
| **load generator** (staging) | 450 streams + ~250 req/s: k6 used ~0.13 vCPU and 0.2 GiB at a quarter of that, ~4 GiB of memory for ~1,000 VUs at the stress test's top | — | one k6 pod, 2 vCPU / 4 GiB |

### What breaks first, and how it fails

From the stress run ([results.md § Results](results.md#results)):
CPU (shared between the api, Postgres and Elasticsearch on one box), then the connection pool. Past the knee the api
now answers 503 `overloaded` after 5 s instead of queueing until the heap is gone, and recovers by itself — the HPA has
time to add pods. In the cloud the api and Postgres don't share cores, so the per-pod knee should be higher than the
per-core figure here; staging's stress run gives the real one.

## HPA settings

`apps.<app>.autoscaling.behavior` (new, S-119) passes an HPA `behavior` block through; without it the chart keeps
its default (scale down after 5 minutes of low CPU). The api in prod and staging now has:

```yaml
api:
  autoscaling:
    enabled: true
    minReplicas: 3                       # prod (staging 2)
    maxReplicas: 10                      # prod (staging 4)
    targetCPUUtilizationPercentage: 60   # headroom for latency, the JIT of new pods and the KDS streams' fan-out
    behavior:
      scaleUp:                           # a dinner rush: double, or +2 pods, every minute — whichever is more
        stabilizationWindowSeconds: 0
        selectPolicy: Max
        policies:
          - { type: Percent, value: 100, periodSeconds: 60 }
          - { type: Pods, value: 2, periodSeconds: 60 }
      scaleDown:                         # one pod every 2 minutes after 5 quiet minutes: each removal reconnects its
        stabilizationWindowSeconds: 300  # kitchen screens' streams
        selectPolicy: Min
        policies:
          - { type: Pods, value: 1, periodSeconds: 120 }
```

The BFFs and the consumer web keep their CPU HPAs (70 %, the chart's default behaviour): they do little but relay,
and scale with the same traffic.

## Revisit when

- staging's load test gives the per-pod knee and the CPU per request in the cloud (replace the estimates above);
- the market passes ~600 businesses or a second market opens (F5's city-wide reads, Postgres CPU);
- a second province's traffic arrives in another time zone (the peak hours stop coinciding — less, not more, at once).
