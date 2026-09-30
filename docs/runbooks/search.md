# Search (Elasticsearch read model)

Elasticsearch 9 holds the **search read model** only: PostgreSQL stays the source of truth, and everything in the
indices can be rebuilt from it. This runbook covers the indices and their synonyms (S-42), the indexer that fills them (S-43) and
the search API that reads them (S-44), and how to rebuild everything (S-71).

| story | what | where |
|---|---|---|
| S-42 | indices `listings_en` / `listings_fr`, analyzers, synonyms, the bootstrap Job | `deploy/search`, `server/search-index`, `ca.northline.worker.search` |
| S-43 | the `search-indexer` consumer, the reconcile sweep, `merchants.locations` | `ca.northline.worker.search`, `db/migrations/V120` |
| S-71 | the full reindex from Postgres with an alias swap (§ 9) | `SearchReindex`, chart `searchReindex` |
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
`deliveryCutoffMinute` (same-day pooled run cut-off, minutes after midnight in Edmonton), `inStock`, `soldOutOn`,
`openHours` (`integer_range`s of minutes in the week, Monday 00:00 Edmonton = 0), `pausedUntil`, `prepMinutes`,
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
- **Latency:** the API adds the cache and one Elasticsearch request; `SearchApiTest` checks p95 < 150 ms on the
  seeded index (Testcontainers, security off).

## 8. Contract for the consumer web and app

Both endpoints are `GET`, public, JSON, camelCase; money in cents; errors as everywhere in the api (422
`{"errors":[{"field","rule","message"}]}`, 429 ProblemDetail). OpenAPI: `/v3/api-docs` (tag *Search*).

### `GET /api/v1/search`

| parameter | meaning |
|---|---|
| `q` | what was typed (≤ 100 characters); blank = browse by the filters |
| `market` | province of the location pill: `AB` (default, `SEARCH_DEFAULT_MARKET`), `BC`, `ON`, `QC` |
| `lang` | `en` \| `fr` — picks the index; default `Accept-Language` (fr* → French), else English |
| `kind` | `service`, `product`, `food`, `merchant` — repeat or comma-separate (the web's `scope`: services → `service`, shop → `product`, food → `food`) |
| `category` | a category id at any level (group or leaf) |
| `minPrice`, `maxPrice` | cents, inclusive ("Under $10" = `maxPrice=999`) |
| `minRating` | 1–5 |
| `tier` | `registered`, `trusted`, `master` ("Master sellers", "Master tier") |
| `instantBook` | `true` = services bookable at once |
| `openNow` | `true` = inside its weekly hours now (Edmonton), not paused, not sold out today ("Open now", "Available today") |
| `delivery` | `tonight` = on tonight's pooled run: pooled delivery, before the seller's cut-off, in stock ("On tonight's run") |
| `dietary` | tags every result has: `halal`, `vegan`, `gluten_free` … ("Halal", "Vegan", "Gluten-free") |
| `allergenFree` | Health Canada allergen codes no result contains: `peanuts`, `tree_nuts` … ("Nut-free" = `peanuts,tree_nuts`) |
| `lat`, `lng` | the person's location (both or neither): `distanceKm` on results, nearness boost, distance sort and filter |
| `radiusKm` | 1–100, needs `lat`/`lng` ("Under 3 km") |
| `sort` | `relevance` (default), `distance` (needs `lat`/`lng`; results without a location are left out), `price_asc`, `price_desc` (no price last), `rating` |
| `size` | 1–50, default 24 |
| `after` | the previous page's `next` (keep the other parameters the same) |

```json
{
  "items": [{
    "id": "01J9…", "kind": "product", "name": "Country sourdough", "description": "…",
    "merchant": { "id": "01J9…", "name": "Glenmore Bakery", "type": "seller", "slug": "glenmore-bakery", "tier": "master" },
    "category": { "id": "shop.groceries.bakery", "name": "Bakery" },
    "priceCents": 750, "pricingMode": "fixed", "rating": 4.8, "reviewCount": 120, "trustTier": "master",
    "distanceKm": 1.2, "instantBook": false, "fulfilment": ["pooled"],
    "openNow": false, "soldOut": false, "onTonightsRun": true, "prepMinutes": null,
    "dietary": [], "allergens": [], "imageKey": "media:01J9…"
  }],
  "total": 3,
  "facets": {
    "kinds": [{ "value": "product", "label": null, "count": 3 }],
    "categories": [{ "value": "shop.groceries.bakery", "label": "Bakery", "count": 2 }],
    "merchants": [{ "value": "01J9…", "label": "Glenmore Bakery", "count": 3 }],
    "tiers": [{ "value": "master", "label": null, "count": 1 }],
    "prices": [{ "value": "under_10", "label": null, "count": 2 }],
    "dietary": []
  },
  "next": "relevance.WzEuNDMsMywiMDFKOS4uLiJd"
}
```

- `kind = merchant` items are the businesses themselves (shop / provider / kitchen pages: `merchant.slug`); the
  others link to the listing. `pricingMode = quote` or `priceCents = null` → "Quote"; a merchant's `priceCents` is
  its cheapest listing ("from $").
- `facets` only on the first page (empty lists after); `prices` buckets are `under_10`, `10_25`, `25_50`, `50_100`,
  `100_plus` (dollars). `total` is exact up to 10 000.
- `next` is null on the last page. A `next` from another sort, or a damaged one, is a 422 on `after`
  ("This page link no longer works. Start the search again.").
- `imageKey` is opaque (`media:<id>` catalogue image, `object:<key>` dish photo); no public image URL exists yet.

### `GET /api/v1/search/suggest`

`q` (required, what has been typed), `market`, `lang` / `Accept-Language`, `kind`, `size` (1–10, default 6).

```json
{ "items": [
  { "text": "Country sourdough", "type": "product", "id": "01J9…", "merchantId": "01J9…",
    "merchantName": "Glenmore Bakery", "merchantType": "seller", "merchantSlug": "glenmore-bakery",
    "priceCents": 750, "trustTier": "master", "rating": 4.8, "highlight": [{ "start": 8, "length": 4 }] },
  { "text": "Mobile mechanic", "type": "category", "id": "service.automotive.mobile-mechanic",
    "merchantId": null, "merchantName": null, "merchantType": null, "merchantSlug": null,
    "priceCents": null, "trustTier": null, "rating": null, "highlight": [{ "start": 7, "length": 3 }] }
] }
```

- `type`: `service` | `product` | `food` (a listing: open it), `merchant` (a business page: `merchantSlug`),
  `category` (a category: search with `category=<id>`).
- Suggestions start a word with `q` ("sour" → "Country **sour**dough"), ignoring case and accents; heavier ones
  (trust tier, rating, recent sales) first; categories take at most two places. `highlight` = UTF-16 offsets into
  `text` to set in bold (empty when the match came from another word form).
- The design's "Your recent" searches are the client's (not stored by the api); "fr → sourdough" synonym rows are not
  returned (synonyms apply to `/search`).

## 9. Reindex (S-71)

Rebuilds both indices from Postgres into new versioned indices and swaps the aliases — searches keep being answered
by the old indices until the new ones are complete, and by the new ones from the swap on. Run it:

- after a layout change that needs it (the search-indices Job logs `REINDEX REQUIRED`: a new analyzer, a changed field,
  a `schema` bump in `deploy/search/listings.json`);
- when the index has drifted from Postgres (lost events, a restore of the database, a new environment filled from a
  dump), or to repair documents after a bug fix in the indexer.

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
cd server && ./gradlew :worker:searchReindex                     # or --args='--keep-old' / --args='--batch=500'
java -cp @/app/jib-classpath-file ca.northline.worker.search.SearchReindexCommand   # inside the worker image
```

**Check:** `curl -s "$ES_URIS/_cat/aliases/listings_*?v"` shows each alias on the new index;
`curl -s "$ES_URIS/_cat/indices/listings_*?v"` the document counts (the two languages hold the same number).
**Go back** (with `--keep-old`): `POST _aliases` with a `remove` of the new index and an `add` of the old one per
language, then delete the new ones. **Duration:** about one merchant per few milliseconds plus Elasticsearch's bulk
time; the catch-up is seconds. Nothing needs to be stopped: the indexer, the sweep and the API keep running.

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
