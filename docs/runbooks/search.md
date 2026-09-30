# Search (Elasticsearch read model)

Elasticsearch 9 holds the **search read model** only: PostgreSQL stays the source of truth, and everything in the
indices can be rebuilt from it. This runbook covers the indices and their synonyms (S-42).

| story | what | where |
|---|---|---|
| S-42 | indices `listings_en` / `listings_fr`, analyzers, synonyms, the bootstrap Job | `deploy/search`, `server/search-index`, `ca.northline.worker.search` |

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
| an analyzer, a filter, a setting, a field's type or analyzer | bump `schema` in `listings.json`; the Job reports `REINDEX REQUIRED`; run the reindex (S-71) |

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

## 6. Troubleshooting

| symptom | cause / fix |
|---|---|
| Job log `REINDEX REQUIRED … settings or analyzers changed` | the analysis in `deploy/search` differs from the live index's: run the reindex (S-71) |
| Job log `… the alias points at 2 indices` | someone edited aliases by hand; point the alias at one index (`POST _aliases`) |
| `strict_dynamic_mapping_exception` in a writer | the field isn't in the mapping yet: run the Job (`apply`) |
| Job fails with `security_exception … manage_search_synonyms` | the app user lacks the cluster privilege (§ 5) |
