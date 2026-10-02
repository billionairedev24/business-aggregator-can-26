# Search (Elasticsearch read model)

Elasticsearch 9 holds the **search read model** only: PostgreSQL stays the source of truth, and everything in the
indices can be rebuilt from it. This runbook covers the indices and their synonyms (S-42), the indexer that fills them (S-43) and
the search API that reads them (S-44), and how to rebuild everything (S-71).

| story | what | where |
|---|---|---|
| S-42 | indices `listings_en` / `listings_fr`, analyzers, synonyms, the bootstrap Job | `deploy/search`, `server/search-index`, `ca.northline.worker.search` |
| S-43 | the `search-indexer` consumer, the reconcile sweep, `merchants.locations` | `ca.northline.worker.search`, `db/migrations/V120` |
| S-71 | the full reindex from Postgres with an alias swap (§ 9) | `SearchReindex`, chart `searchReindex` |
| S-115 | the reindex runbook: partial reindex, rollback, decision tree (§ 9) | `SearchReindexCommand --partial` / `--rollback` |
| S-44 | the public search API `GET /api/v1/search` and `/api/v1/search/suggest` (contract § 8) | api module `ca.northline.search` |

Environments: local uses the compose `search` profile (Elasticsearch 9.1, security off, [local.md](local.md)); dev,
staging and prod use Elastic Cloud from the S-3 Terraform ([infrastructure.md § 5.4](infrastructure.md#54-elasticsearch-elastic-cloud)).
**No Elastic Cloud deployment or credentials exist yet** (open item): everything below has only run against the local
and Testcontainers Elasticsearch 9.

## 1. Layout (versioned code)

| file | what |
|---|---|
| `deploy/search/listings.json` | the index: `schema` version, settings, mappings (`dynamic: strict`) |
| `deploy/search/analysis-en.json`, `analysis-fr.json` | the analyzers of each language, under the same names |
| `deploy/search/synonyms-en.txt`, `synonyms-fr.txt` | the synonym rules (Solr format) of each language |

They are packaged into the worker (and the api) as `classpath:search/` by the `server/search-index` library
(`IndexLayout`); the bootstrap Job and the tests read the same files.

**Names.** Readers and writers only ever use the aliases `listings_en` and `listings_fr`. Each alias points at one
concrete index `listings_<lang>_v<schema>_<yyyyMMddHHmmss>` (UTC creation time), so a reindex builds the next index
beside the live one and swaps the alias atomically. Every index records `_meta.northline` = `schema`, `analysisHash`,
`mappingsHash` (SHA-256 of the rendered analysis and mappings); the bootstrap compares them with the files.

**Analyzers** (per language, same names):

| analyzer | English | French | used by |
|---|---|---|---|
| `nl_text` | standard, possessive, lowercase, ASCII folding, English stop words, English (Porter) stemmer | standard, **elision** (`l'`, `d'`, `qu'` …), lowercase, **ASCII folding**, French stop words, light French stemmer | indexing `name`, `description`, `keywords`, `merchantName`, `categoryNames` |
| `nl_text_search` | `nl_text` + the synonym set after folding | same | queries on those fields |
| `nl_prefix` | standard, lowercase, ASCII folding | + elision | `name.prefix` (search-as-you-type), `suggest`, `suggestCategory` (completion) |
| `nl_keyword` (normalizer) | lowercase, ASCII folding | same | `name.sort` |

So `L'Épicerie du marché` indexes as `epic`, `march`, and `cafe` finds `café`.

**Fields** (the document every listing kind shares; `kind` = `service` | `product` | `food` | `merchant`):
`id`, `kind`, `market` (province), `merchantId`, `merchantName`, `merchantType`, `merchantStatus`, `merchantSlug`,
`name` (+ `name.prefix`, `name.sort`), `description`, `keywords`, `categoryId`, `categoryPath` (root → leaf ids),
`categoryRoot`, `categoryNames`, `priceCents`, `pricingMode`, `rating`, `reviewCount`, `trustTier`, `trustRank`,
`qualityScore`, `vetting` (always `approved`), `status` (always `live`), `instantBook`, `fulfilment`,
`deliveryCutoffMinute` (same-day pooled run cut-off, minutes after midnight, local time), `inStock`, `soldOutOn`,
`openHours` (`integer_range`s of minutes in the week, Monday 00:00 local time = 0), `pausedUntil`, `prepMinutes`,
`allergens`, `dietary`, `location` (`geo_point`), `serviceRadiusKm`, `imageKey`, `sales30d`, `updatedAt`,
`suggest` (completion, contexts `market` and `kind`), `suggestCategory` (completion, context `market`).

## 2. The bootstrap Job (every deploy)

The chart's `northline-search-indices-<hash>` Job (Helm pre-install/pre-upgrade hook weight −9, Argo CD PreSync wave
−9, right after the Kafka topics Job) runs `SearchIndicesCommand <searchIndices.command>` from the **worker** image
with the worker's Elasticsearch settings (`ES_URIS`, `ES_USERNAME` from `configEnv`, `ES_PASSWORD` from the secrets
manager through a hook-scoped ExternalSecret, or the plain Secret):

1. **Synonym sets** `listings-synonyms-en` / `-fr`: created when missing, replaced when the file changed.
2. **Aliases**: when `listings_<lang>` is missing, the first index `listings_<lang>_v<schema>_<now>` is created behind
   it. When it exists: same hashes → `ok`; only new fields → `PUT _mapping` in place (`UPDATED`); a different
   analysis, schema version or an incompatible field → **`REINDEX REQUIRED`** (logged; the Job never reindexes).

| `searchIndices.command` | does |
|---|---|
| `apply` (default) | 1 and 2; exit 0 even when a reindex is required (the log says so) |
| `plan` | reports what `apply` would do |
| `verify` | reports, and exits 3 (fails the release) on anything missing, changed or needing a reindex |

It never deletes an index or a synonym set. `searchIndices.enabled: false` turns it off (the kind rehearsal has no
Elasticsearch). Logs:

```sh
kubectl -n northline-<env> logs $(kubectl -n northline-<env> get jobs -l app.kubernetes.io/component=search-indices -o name --sort-by=.metadata.creationTimestamp | tail -1)
```

By hand (same command, the worker's `ES_*` from the environment or `server/.env`):

```sh
cd server && ./gradlew :worker:searchIndices --args='plan'     # or verify (exit 3 on drift) / apply
java -cp @/app/jib-classpath-file ca.northline.worker.search.SearchIndicesCommand plan   # inside the worker image
```

## 3. Synonyms

Edit `deploy/search/synonyms-<lang>.txt` (one rule per line: `a, b, c` = equivalent, `a => b` = rewrite; `#`
comments), merge, deploy: the Job replaces the set and Elasticsearch **reloads the search analyzers at once** — no
reindex, no restart. The rules apply at query time only (`nl_text_search`), after lower-casing and ASCII folding, so
write them lower-case; accents are optional. French ↔ English pairs (`pain au levain, levain, sourdough`) belong in
**both** files: the French index serves English text where a listing has no French, and people type either language.
To try a change without deploying: `./gradlew :worker:searchIndices --args='apply'` against local Elasticsearch, then
query. `i18n.synonyms` (DATA_MODEL) is not read yet: the files are the source.

## 4. Changing the layout

| change | what happens |
|---|---|
| a new field or sub-field in `listings.json` | the Job adds it in place (`UPDATED`); no reindex |
| a synonym rule | the Job replaces the set; live at once |
| an analyzer, a filter, a setting, a field's type or analyzer | bump `schema` in `listings.json`; the Job reports `REINDEX REQUIRED`; run the reindex (§ 9) |

The indices are `dynamic: strict`: a document with a field the mapping doesn't have is refused, so a new field must
reach the mapping (the Job runs before the pods) before any writer sends it.

## 5. Elastic Cloud access (least privilege)

Until the deployment and its credentials exist, `ES_USERNAME` is the deployment's `elastic` superuser
([infrastructure.md § 5.4](infrastructure.md#54-elasticsearch-elastic-cloud)). The `northline_app` role the apps need:

```json
{
  "cluster": ["monitor", "manage_search_synonyms"],
  "indices": [
    { "names": ["listings_*"], "privileges": ["create_index", "manage", "read", "write", "view_index_metadata"] }
  ]
}
```

`manage_search_synonyms` is for the bootstrap Job (the synonym sets); `manage` covers aliases and mappings.

## 6. The indexer (S-43)

The worker's consumer group **`search-indexer`** reads `catalogue.listing`, `food.menu`, `food.kitchen`,
`merchants.merchant`, `merchants.storefront`, `trust.review` and `availability.availability` (retries 10 s / 60 s /
5 min, then `<topic>.dlq` — the S-26 framework, [events.md](events.md)). An event only says **what to look at**; the
document is always rebuilt from the rows as they are now, so duplicates, replays and events out of order do no harm:

| event | scope re-read |
|---|---|
| `listing.published` · `listing.hidden` · `listing.deleted` · `listing.flagged` · `listing.submitted` | that service or product offer |
| `food.item_availability` (sold out / back, visibility, deleted dish) | that dish |
| everything else: `menu.published`, `kitchen.paused/resumed`, `merchant.*`, `storefront.published`, `custom_domain_changed`, `review.replied/reported`, `availability.changed` | the whole merchant: every listing, dish and its own document |

**What is indexed** (everything else of the scope is deleted from both indices): merchants with `status = active` and
a province (`market`); services and offers `vetting = approved` and `status = live`; dishes `published`, `approved`,
on a `live` menu (sold out today stays, with `soldOutOn`); the merchant's own document (`kind = merchant`) once its
page is published. A paused or suspended merchant, a rejected or hidden listing, a deleted dish or a hidden menu
disappear with the next event or sweep.

**Where the fields come from** (read-only queries, `DocumentSource`): names and descriptions from `*_i18n->>'<lang>'`
with the listing's own text as fallback (French index) — categories from `catalogue.categories.name_i18n`;
`trustTier` = `merchants.tier`; `rating`/`reviewCount` = `trust.reviews` of the merchant; `location` +
`serviceRadiusKm` = `merchants.locations` (V120; a kitchen without a radius there uses `food.kitchen_settings.radius_km`);
`openHours` = `food.opening_hours` (dishes, kitchens) or the members' current `availability.availability_rules`
(services, providers); `deliveryCutoffMinute` = the seller's `profile.sameDayCutoff` for pooled offers; `pausedUntil`,
`prepMinutes` (default + busy bump + the dish's extra) = `food.kitchen_settings`.

**Versions.** Refreshes of one merchant take a Postgres advisory lock and version every write with Postgres'
`clock_timestamp()` (µs) taken under it (`version_type=external_gte`, deletes too): an older snapshot can never
overwrite a newer one, whichever replica, retry or reindex writes last. A `409` on an item in the logs' debug line is
that protection working.

**The reconcile sweep.** Edits no event announces — a price or a name changed on a live listing, new hours, a new
review, a location — are found every `SEARCH_RECONCILE_EVERY` (1 min) by `updated_at` (services, offers, catalogue
records, dishes, menus, kitchen settings and hours, merchants, pages, locations, weekly availability; reviews created,
replied or reported) and their merchants refreshed. The watermark is `search.sync_state` (`reconcile`); one replica
sweeps at a time (a 5-minute lease), looking 2 minutes back for late commits, at most 500 merchants per sweep.
`SEARCH_RECONCILE_ENABLED=false` turns it off (e.g. while a reindex runs, if you want the load gone).

**Locations.** Nothing geocodes addresses yet: onboarding keeps them as text. Until a geocoder exists, set a
merchant's point by hand (then the sweep picks it up within a minute):

```sql
insert into merchants.locations (merchant_id, geom, service_radius_km, source)
values ('<merchant id>', ST_GeogFromText('POINT(<lon> <lat>)'), 25, 'manual')
on conflict (merchant_id) do update set geom = excluded.geom, service_radius_km = excluded.service_radius_km,
  source = 'manual', updated_at = now();
```

Without a location a merchant's documents have no distance: they are left out of distance filters and sort last by
distance.

**Check a document:** `curl -s "$ES_URIS/listings_en/_doc/<id>?pretty"` (and `listings_fr`). **Replay** what the
group dead-lettered: `./gradlew :worker:dlqReplay --args='replay --topic=catalogue.listing.dlq --group=search-indexer'`
([events.md § DLQ](events.md)). **Metrics:** `northline_events_consumed_total{consumer="search-indexer"}` by outcome.

## 7. The search API (S-44)

The api reads the aliases (never writes them) through the port `SearchIndex`:

| `SEARCH_PROVIDER` | where | behaviour |
|---|---|---|
| `elasticsearch` | default; dev, staging, prod | `listings_en` / `listings_fr` on `ES_URIS` |
| `local` | the `local` and `test` profiles' default | no index: every search is empty (the rules still apply); refused under staging and prod |

To search locally: `docker compose --profile search up -d`, `./gradlew :worker:searchIndices --args='apply'`, fill the
indices (the worker with `--profile events`, or the reindex), and run the api with `SEARCH_PROVIDER=elasticsearch`.

- **Anonymous and rate limited:** `/api/v1/search/**` is open (no token needed; a token changes nothing). Each client
  address gets `SEARCH_RATE_LIMIT` (120) requests a minute per api instance, then `429` ProblemDetail
  `code: rate_limited` with `Retry-After: 60`. The address: when the peer is internal (loopback, RFC 1918, 100.64/10,
  IPv6 ULA — the ingress, a BFF, the consumer SSR server), the right-most public `X-Forwarded-For` hop; otherwise the
  peer. Entries a client writes itself sit further left and are ignored. The consumer web's SSR server should add the
  browser's address to `X-Forwarded-For` when it searches for a page view, or every server-rendered search counts
  against the SSR pod.
- **Hot-query cache:** an identical request is answered for `SEARCH_CACHE_TTL` (30 s) from Redis/Valkey (`nl:search:*`,
  shared by the replicas; memory under `local`/`test`). Best effort: when Redis is down the index answers. So an edit
  shows in search after the indexer (seconds) + at most the TTL.
- **Relevance:** text match (name ×4, merchant and category names ×2, keywords, description; every word must match;
  or as a prefix of the name) × (trust tier 1.5 / 1.2 / 1.0 + rating log10(2 + stars) + nearness up to 2 within
  1 km, half at 6 km, when `lat`/`lng` are sent). Ties: tier, then id.
- **Only what customers may see:** the market's documents with `vetting=approved`, `status=live`,
  `merchantStatus=active` (the indexer indexes nothing else; the filters are there too).
- **Markets are configuration** (the region model since S-134, [regions.md](regions.md); `SEARCH_MARKETS` is now a fallback of `REGION_PROVINCES`): a province opens
  to search by adding it there. Its time zone is the "now" for open-now, the same-day cut-off and "sold out today"
  (the index keeps local times). A code that isn't configured gets 422 `unsupported`; `SEARCH_DEFAULT_MARKET` must be
  one of them (blank = `market` required). The api refuses to start on a malformed entry.
- **Latency:** the API adds the cache and one Elasticsearch request; `SearchApiTest` checks p95 < 150 ms on the
  seeded index (Testcontainers, security off).

## 8. Contract for the consumer web and app

The request parameters, response shapes and how the design's filters map to them are in
[docs/CONSUMER_WEB_PLAN.md § Contracts › Search](../CONSUMER_WEB_PLAN.md#search-s-44) — the consumer web's single list of
contracts. OpenAPI: `/v3/api-docs` (tag *Search*).

## 9. Reindex (S-71)

Rebuilds both indices from Postgres into new versioned indices and swaps the aliases — searches keep being answered
by the old indices until the new ones are complete, and by the new ones from the swap on. Run it:

- after a layout change that needs it (the search-indices Job logs `REINDEX REQUIRED`: a new analyzer, a changed field,
  a `schema` bump in `deploy/search/listings.json`);
- when the index has drifted from Postgres (lost events, a restore of the database, a new environment filled from a
  dump), or to repair documents after a bug fix in the indexer. After **any** database restore and every masked
  prod → staging refresh this is mandatory: the index is rebuilt from the restored database, not restored from a
  snapshot (S-114, [backups-dr.md](backups-dr.md#elasticsearch-snapshots-but-rebuilt-rather-than-restored)).

**What it does** (`SearchReindex`, one run at a time — a Postgres advisory lock):

1. notes the end of every topic `search-indexer` consumes;
2. creates `listings_<lang>_v<schema>_<now>` for both languages (no refresh, no replica while loading);
3. **backfill:** every merchant, 200 at a time, through the indexer's own projection (same documents, same
   per-merchant lock and versions);
4. **catch-up:** applies the events published since step 1 to the new indices, pass after pass until nothing is new;
5. restores refresh and replicas, waits for the indices (yellow), then **swaps both aliases in one request**;
6. catches up once more (events the live indexer wrote to the old index during the swap) and re-reads the merchants
   whose rows changed since step 1 without an event (the reconcile sweep's work of that time);
7. deletes the old indices (`--keep-old` keeps them).

A failure before the swap deletes the half-built indices; searches never noticed. After the swap the new indices are
live even if step 6 fails — the live indexer and the next sweep keep them current; run the reindex again to be sure.

**Run it in a cluster** (the worker image, the worker's environment): set a run id in the environment's values and
sync —

```yaml
# deploy/argocd/envs/<env>/values.yaml (or helm upgrade … --set searchReindex.runId=…)
searchReindex:
  runId: "2026-10-01"            # any new DNS label; a finished Job with the same id won't run again
  keepOld: false
```

```sh
kubectl -n northline-<env> logs -f job/northline-search-reindex-2026-10-01
```

Remove `runId` afterwards (or leave it: the Job is deleted by its TTL after a week and is never re-run with the same id).
Exit codes: 0 done, 1 failed (see the log; before the swap nothing changed), 2 another reindex was running.

**By hand** (a machine that reaches the database, Kafka and Elasticsearch; the worker's `DB_*`, `KAFKA_*`, `ES_*`):

```sh
make search-reindex                                               # KEEP_OLD=1 keeps the old indices (S-124)
cd server && ./gradlew :worker:searchReindex                     # or --args='--keep-old' / --args='--batch=500'
java -cp @/app/jib-classpath-file ca.northline.worker.search.SearchReindexCommand   # inside the worker image
```

**Check:** `curl -s "$ES_URIS/_cat/aliases/listings_*?v"` shows each alias on the new index;
`curl -s "$ES_URIS/_cat/indices/listings_*?v"` the document counts (the two languages hold the same number).
**Go back** (with `--keep-old`): `POST _aliases` with a `remove` of the new index and an `add` of the old one per
language, then delete the new ones. **Duration:** about one merchant per few milliseconds plus Elasticsearch's bulk
time; the catch-up is seconds. Nothing needs to be stopped: the indexer, the sweep and the API keep running.

### Runbook: reindex, partial reindex, rollback (S-115)

**Symptoms.** The search-indices Job logs `REINDEX REQUIRED`; a listing that is live in the Studio isn't found (or a
hidden one still is); `_cat/indices` counts differ between `listings_en` and `listings_fr` or from Postgres;
`DEAD-LETTERED consumer=search-indexer` in the worker log; `NorthlineConsumerLag` for `search-indexer`
([alerts/consumer-lag.md](alerts/consumer-lag.md)); after a database restore (S-114).

**Impact.** Customers see stale or missing results; nothing else depends on the index (Postgres is the source of
truth, checkout re-checks everything). No data is lost by any step below.

**Decide:**

```
a layout change (REINDEX REQUIRED), a restore, a new environment, drift everywhere ─▶ full reindex (with --keep-old)
a few merchants wrong (an indexer bug fixed, a lost or dead-lettered event)       ─▶ partial reindex of those merchants
everything changed in a time window (the indexer was down, events dead-lettered)   ─▶ partial reindex --since=<start>
the new index after a reindex is worse (bad analyzer, missing documents)           ─▶ rollback (needs --keep-old)
```

**Compare one merchant** (its live services in Postgres against its documents; offers and dishes the same way from
the Studio's Listings filtered to Live):

```sql
select count(*) from catalogue.services where merchant_id = '<merchant id>' and vetting = 'approved' and status = 'live';
```

```sh
curl -s "$ES_URIS/listings_en/_count?q=merchantId:<merchant id>%20AND%20kind:service"
```

**Full reindex** — § 9 above; for anything but a trivial run use `--keep-old` (`searchReindex.keepOld: true` in the
chart) so a rollback is possible, and delete the old indices once the new ones are confirmed.

**Partial reindex** — re-reads merchants into the **live** aliases (no new index, no swap, nothing to roll back; the
same projection, per-merchant lock and versions as the live indexer, so it can run any time):

```sh
cd server && ./gradlew :worker:searchReindex --args='--partial --merchant=01J9ZD3V00000000000000PWM1,01J…'
./gradlew :worker:searchReindex --args='--partial --since=2026-10-02T08:00:00Z'     # every merchant whose rows changed since
java -cp @/app/jib-classpath-file ca.northline.worker.search.SearchReindexCommand --partial --merchant=…   # worker image
```

`--since` uses the reconcile sweep's change query (rows' `updated_at`): edits, new listings, hours, reviews. A
listing that disappeared without its row changing (a dead-lettered `listing.hidden`) is fixed by naming its merchant.

**Rollback** — after a full reindex run with `--keep-old`: points both aliases back at the newest older index of each
language in one request, then re-reads the merchants whose rows changed since the newer index was created (what the
old index missed while it was not live):

```sh
./gradlew :worker:searchReindex --args='--rollback'
curl -s "$ES_URIS/_cat/aliases/listings_*?v"                 # each alias on the old index again
curl -s -X DELETE "$ES_URIS/<newer listings_en_v…>,<newer listings_fr_v…>"   # once you're sure (or ListingIndices.delete)
```

Exit 1 with "No older … index to go back to" when the reindex didn't keep it — then the way back is a full reindex
(from the previous release's layout, if the layout was the problem: roll the worker back first). By hand, the same
swap is `POST _aliases` with a `remove` of the new index and an `add` of the old one per language.

**In a cluster** the partial and rollback run as a one-off Job like the DLQ replay's ([events.md § 3](events.md#3-dlq-investigate-and-replay)),
with `ca.northline.worker.search.SearchReindexCommand` and `--partial …` / `--rollback` as arguments, the worker's
ConfigMaps and `DB_PASSWORD`, `ES_PASSWORD`, `KAFKA_SASL_JAAS_CONFIG` from `northline-worker-secrets`. The chart's
`searchReindex` Job runs only the full reindex.

**Verify:** `_cat/aliases` (one index per alias), `_cat/indices/listings_*?v` (both languages the same count), the
merchant query above against `_count`, a search on the consumer site for one of the merchant's listings (the hot-query
cache answers up to 30 s from before).

**Comms.** None for a reindex (searches keep working). For drift customers noticed: support says results catch up
within minutes once the partial reindex has run.

### Exercised

| date | what was run | outcome |
|---|---|---|
| 2026-10-02 | `SearchReindexTest.runbookDrill_partialReindex_thenRollbackAfterKeepOld` — Kafka 4 + PostGIS + Elasticsearch 9.1 (Testcontainers) with the whole worker, through `SearchReindexCommand` (the operator's entry point): a document deleted from the live index; `--partial` without a merchant refused; `--partial --merchant=<id>`; full reindex `--keep-old`; a listing made live afterwards without an event; `--rollback`; the newer indices deleted; a second `--rollback` refused | **passed** (4.1 s): the lost document is back after the partial reindex (one merchant re-read); after the rollback both aliases point at the previous indices and the later listing is in them (the rollback's sweep); with nothing older left the rollback says to reindex instead |
| 2026-10-02 | `SearchReindexTest` (S-71's tests, unchanged) | **passed**: full reindex while live, one run at a time, failure before the swap changes nothing, `--keep-old` |
| — | **not exercised:** the chart's `searchReindex` Job and a cluster Job against Elastic Cloud (no deployment or credentials exist, § 5) | |

## 10. Troubleshooting

| symptom | cause / fix |
|---|---|
| Job log `REINDEX REQUIRED … settings or analyzers changed` | the analysis in `deploy/search` differs from the live index's: run the reindex (§ 9) |
| Job log `… the alias points at 2 indices` | someone edited aliases by hand; point the alias at one index (`POST _aliases`) |
| `strict_dynamic_mapping_exception` in a writer | the field isn't in the mapping yet: run the Job (`apply`) |
| Job fails with `security_exception … manage_search_synonyms` | the app user lacks the cluster privilege (§ 5) |
| a published listing isn't found | the merchant is not `active`, has no province, the listing isn't `approved` + `live`, or (merchant documents) the page isn't published; check `DEAD-LETTERED consumer=search-indexer` in the worker log |
| an edit shows up only after a minute | expected: edits without an event arrive with the reconcile sweep (§ 6) |
| no distance on a merchant's results | no row in `merchants.locations` (§ 6) |
| every search is empty | the api runs with `SEARCH_PROVIDER=local` (the `local` profile's default), or the indices are empty (run the indexer / reindex) |
| `429 rate_limited` from the consumer web | its requests arrive without the browser's address in `X-Forwarded-For` (the SSR server's own address counts them all): forward it, or raise `SEARCH_RATE_LIMIT` (§ 7) |
