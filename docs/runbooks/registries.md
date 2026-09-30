# Business registry lookups (S-23)

Onboarding's **Business registration** row and the **licence** rows are checked against public registries. The
checks use the legal name, the numbers and the status the owner entered. Each lookup is stored as evidence in
`merchants.registry_checks`: the source, the number, what the source answered (name, number, status, expiry), when,
and the provider's reference. Lookups that don't match go to the **console verification queue**, where a Northline
agent decides them. Verified rows are re-checked on a schedule, and a registry's expiry date or a lapsed record feeds
`ComplianceStatus`.

| source | what it answers | how Northline asks | `northline.registries.<source>.provider` |
|---|---|---|---|
| **Corporations Canada** | federal corporations (CBCA, NFP Act, Coop Act, Boards of Trade) | ISED **Federal Corporation API** on the GC API Store, with a subscription key | `corporations-canada`: `fixtures` · `api` · `manual` |
| **Alberta Corporate Registry** | Alberta corporations, extra-provincial registrations, partnerships, trade names, co-operatives, societies | **no public API**. Either a search service (`opencorporates`) or a registry-agent search that an agent runs (`manual`) | `alberta`: `fixtures` · `opencorporates` · `manual` |
| **City of Calgary business licences** | municipal business licences (trade name, licence type, status, expiry) | Open Calgary **Socrata** dataset `vdjc-pybd` (SODA API), with an optional app token | `calgary`: `fixtures` · `socrata` · `manual` |
| other regulators (AMVIC, AHS permits, AGLC, RECA, Safety Codes, …) | licences | **no API**: every lookup goes to an agent | always manual |

`fixtures` is the default under `local` and `test`. It answers from `server/api/src/main/resources/registries/fixtures.json`,
where unknown numbers are "not found" and numbers containing `offline` are "unavailable". It is **refused under
staging/prod** and logs a warning under dev.

## What each source really offers (research, 2026-09-30)

The official documentation hosts (`api.ised-isde.canada.ca`, `ised-isde.canada.ca`, `dev.socrata.com`) could not be
reached from the build environment. The findings below come from search results that quote those pages. Everything
marked *to confirm* must be checked when the account exists.

### Corporations Canada: Federal Corporation API

- ISED publishes a **Federal Corporation API** in the GC API Store, listed in the ISED API Catalogue
  ([catalogue](https://api.ised-isde.canada.ca/en/docs?api=corporations)). It gives real-time data on federal
  corporations: status, registered office address, directors (added in 2024,
  [notice](https://ised-isde.canada.ca/site/corporations-canada/en/director-information-now-available-api-store)).
  A lookup needs an existing **corporation number or 9-digit business number**. There is no name search.
- **Access:** you sign up in the API Store, subscribe to the API's plan and receive a key, which goes **in a header**
  on every call. The header name is *to confirm*: the adapter sends `user-key`, and
  `REGISTRY_CORPORATIONS_CANADA_KEY_HEADER` changes it.
- **Shape:** the older open JSON datasets used `…/CorporationsCanada/api/corporations/{corporation_id}.json?lang=…`
  and `…/{bn9}.json` ([JSON datasets](https://ised-isde.canada.ca/site/corporations-canada/en/accessing-federal-corporation-json-datasets)),
  with a `corporationNames` history. The adapter calls `GET {REGISTRY_CORPORATIONS_CANADA_URL}/corporations/{number}.json?lang=eng`
  and reads the record tolerantly:
  - the answer may be an array or an object
  - it reads the current `corporationNames[].CorporationName.name`, falling back to `corporationName` or `name`
  - `status` may be a string or an object with `text` or `desc`

  The exact base URL and path under the API Store are *to confirm*. Set the URL the subscription page shows.
- Coverage: federal corporations only. A federal corporation that operates in Alberta **also** needs an Alberta
  extra-provincial registration, and that second number is looked up in the Alberta registry.

### Alberta Corporate Registry: no public API

- Searches of the Corporate Registry (CORES) are sold through **registry agents**: a government fee plus the agent's
  fee, about $14–$18 a search ([Alberta.ca: find corporation details](https://www.alberta.ca/find-corporation-details),
  [AMA](https://ama.ab.ca/registries/business/corporate-search)).
- Direct system access ("Registries Online") is for accredited agents, law firms and high-volume subscribers after an
  application to Service Alberta ([Registries Online](https://www.alberta.ca/registries-online-subscribers)). It is
  not a public REST API.
- **Search services** resell registry data through APIs:
  - [OpenCorporates](https://api.opencorporates.com/documentation/API-Reference) covers `ca_ab` from Service Alberta's
    Corporate Registry, with `GET /v0.4/companies/ca_ab/{number}?api_token=…`
  - Kyckr and similar KYB services also sell Canadian provincial registry access
  - third-party mirrors such as albertacorporations.com are free, but they are not a source of record
- **Northline's model:**
  - `opencorporates` is the provider adapter. A match (name, active) verifies the row.
  - A not-found, a mismatch or an unavailable service opens a **manual review**. The agent then runs a registry-agent
    search and records its reference.
  - `manual` sends everything to that review. It is the default in staging/prod until a search-service account exists.
  - OpenCorporates can lag the registry by days and may not hold trade names or partnerships. A registry-agent search
    stays the legal proof.

### City of Calgary business licences: Open Calgary (Socrata)

- Dataset **"Calgary Business Licences"**, id `vdjc-pybd`, about 21,000 active licences
  ([dataset](https://data.calgary.ca/Business-and-Economic-Activity/Calgary-Business-Licenses/vdjc-pybd),
  [SODA foundry](https://dev.socrata.com/foundry/data.calgary.ca/vdjc-pybd)).
- Columns: `getbusid`, `tradename`, `homeoccind`, `address`, `comdistcd`, `comdistnm`, `licencetypes`,
  `first_iss_dt`, `exp_dt`, `jobstatusdesc` (e.g. `LICENSED`, `RENEWAL LICENSED`, `RENEWAL INVOICED`,
  `PENDING RENEWAL`, `RENEWAL NOTIFICATION SENT`, `EXPIRED`), `point`, `globalid`
  ([column list](https://www.splitgraph.com/calgary-ca/calgary-business-licences-vdjc-pybd)).
- API: `GET https://data.calgary.ca/resource/vdjc-pybd.json?getbusid=…`, free and without an account. Without an
  **app token** (`X-App-Token`), Socrata throttles by IP. A token raises the limit (Socrata quotes 1,000 requests per
  rolling hour); 429 means throttled ([app tokens](https://dev.socrata.com/docs/app-tokens.html)).
- Not every business needs a municipal licence, and the dataset notes that some need licences from other bodies.
  Only **kitchens that give a city licence number** and the **"Mobile permit"** licence row (food trucks) are looked
  up here, and only for businesses in Calgary.
- The exact format of `getbusid` is *to confirm*. The design shows "BL 22-118840". The adapter tries the number as
  typed, then without a leading `BL`. When a licence has several rows (renewals), the latest `exp_dt` wins.

### Regulators without an API

- **AMVIC** has a public licensee search on its website but no API
  ([AMVIC](https://www.amvic.org/business/business-licence/)).
- **AHS** publishes food inspection reports through a web portal (and Edmonton republishes some as open data), but
  there is no permit API ([AHS inspection reports](https://www.albertahealthservices.ca/eph/page3149.aspx)).
- **AGLC**, RECA, Safety Codes, the colleges and the rest have no API either.

These lookups always go to an agent (`source = manual`). The agent checks the regulator's website, then approves the
review with the expiry date they saw, which reaches `ComplianceStatus`, or rejects it.

## Which lookups run

| structure (legal-details.schema.json) | registry row looks up |
|---|---|
| `sole` | the trade name (`trade_name_registration`) in Alberta when there is one. Without one, nothing is registered and the row is verified with `not_required` |
| `partnership` | `partnership_registration` (Alberta) |
| `corp_ab` | `alberta_corporate_access_number` (Alberta) |
| `corp_fed` | `corporations_canada_number` (Corporations Canada) **and** `alberta_extra_provincial_registration` (Alberta) |
| `corp_ex` | `alberta_extra_provincial_registration` (Alberta) |
| `coop`, `nonprofit` | `cooperative_registration` / `society_registration` (Alberta) |
| kitchen with a city licence # (Business step) in Calgary | plus the City of Calgary licence |

**Matching:**
- The record's name must equal one of the names entered: the legal or corporate name, the operating or trade name,
  or the display name. Case, accents, punctuation, "&" and "and", a leading "The" and legal-form suffixes such as
  Ltd., Limited, Inc., Corp. and Ltée are ignored.
- The status must be active. Words such as struck, dissolved, expired, cancelled, revoked or amalgamated mean
  inactive.
- A licence expiry must not be in the past.

**Outcomes:**
- **Every lookup matched:** the row becomes `verified`, the reference is the primary number, and `expires_at` is the
  earliest registry expiry.
- **Anything else:** the row becomes `submitted` ("in review", still counted as complete for submitting), and each
  unmatched lookup opens a review.

## Console verification queue

- **Needs:** role `STAFF` and a second factor.
- `GET /api/v1/console/registry-reviews?limit=50`
  - open reviews, oldest first
  - each item shows the evidence: source, number, the name expected and the name found, status, expiry, reasons
    (`name`, `status`, `expired`), and the reference
- `POST /api/v1/console/registry-reviews/{id}/decision` with `{"decision":"approve|reject","reference":"…","expiresOn":"YYYY-MM-DD","note":"…"}`
  - **approve:** the row becomes `verified`, with the agent's reference (for example the registry-agent search
    number) and expiry, once no other review of that row is open
  - **reject:** the row becomes `rejected`. `ComplianceStatus` then reports it as due and the owner has to act.
  - a second decision on the same review: 409 `review_closed`

There is no console UI yet (console workstream). Agents use the API.

## Scheduled re-check

- **When:** `REGISTRY_RECHECK_CRON`, daily at 03:41 Calgary time by default.
- **What:** every `verified` row backed by an API source whose last check (`verifications.rechecked_at`) is older
  than `REGISTRY_RECHECK_AFTER` (30 days). The design says "Registry checks re-run monthly".
- **Concurrency:** rows are claimed with `FOR UPDATE SKIP LOCKED` in batches of 50, so replicas share the work.

**Results:**
- **Still matched:** the check time and the expiry are refreshed. A renewed Calgary licence moves `expires_at`.
- **No longer matches** (struck, expired, renamed): the row's `expires_at` is set to now, so `ComplianceStatus`
  reports it as expired, and instant book pauses after the 15-day grace period. A review opens.
- **Source unreachable:** the row is tried again the next day and no review opens.

Rows that only an agent checked (AMVIC and the other manual sources) are not re-checked automatically. Their expiry
comes from the agent's decision or the owner's renewal upload.

## Set-up per environment

| variable | secret | purpose |
|---|---|---|
| `REGISTRY_CORPORATIONS_CANADA_PROVIDER` | | `api` once subscribed, else `manual` (required under staging/prod) |
| `REGISTRY_CORPORATIONS_CANADA_URL` | | the API's base URL from the API Store subscription |
| `REGISTRY_CORPORATIONS_CANADA_KEY` | ✓ | the API Store key |
| `REGISTRY_CORPORATIONS_CANADA_KEY_HEADER` | | default `user-key`; set it if the store names the header differently |
| `REGISTRY_ALBERTA_PROVIDER` | | `opencorporates` with an account, else `manual` (required under staging/prod) |
| `REGISTRY_ALBERTA_URL` | | default `https://api.opencorporates.com` |
| `REGISTRY_ALBERTA_KEY` | ✓ | OpenCorporates API token |
| `REGISTRY_CALGARY_PROVIDER` | | `socrata` (required under staging/prod) |
| `REGISTRY_CALGARY_URL`, `REGISTRY_CALGARY_DATASET` | | defaults `https://data.calgary.ca`, `vdjc-pybd` |
| `REGISTRY_CALGARY_APP_TOKEN` | ✓ (optional) | Socrata app token: create one in a data.calgary.ca account → Developer settings |
| `REGISTRY_RECHECK_AFTER`, `REGISTRY_RECHECK_CRON` | | `P30D`, `0 41 3 * * *` |

Steps:
1. **Corporations Canada:** create an API Store account for Northline, subscribe to the Federal Corporation API,
   and copy the base URL and key. Test it with one known corporation number before switching to `api`.
2. **Alberta:**
   - for `opencorporates`, buy an API plan and set the token
   - either way, set up an account with a registry agent (for example AMA or a local agent) for the manual
     searches, and record the agent's search number as the review's reference
3. **Calgary:** optionally create an app token, then set `REGISTRY_CALGARY_PROVIDER=socrata`.
4. Secrets go into the secrets manager (Terraform creates `registry-corporations-canada-key`, `registry-alberta-key`
   and `registry-calgary-app-token` empty; [secrets.md](secrets.md)). Map the ones in use in the Helm values'
   `secretEnv`.

**Never run against the live services.** The adapters are tested against WireMock stand-ins built from the findings
above (`RegistryAdaptersWireMockTest`).
