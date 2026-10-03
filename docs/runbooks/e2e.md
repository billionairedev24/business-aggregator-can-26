# End-to-end suite (S-117)

Playwright against the real stack: the three web apps, their BFFs, northline-auth and the api, signed in for real (no
dev auth, no token shortcuts). The suite lives in `web/e2e` (package `@northline/e2e`). It grew out of
`scripts/studio-smoke.mjs` (S-5), whose sweep of every Studio screen is now one of its tests.

| # | journey | actors (one browser context each) | file |
|---|---|---|---|
| 1 | **sign-in** — the owner in the Studio (authenticator app), staff in the console (second factor), a new customer signs up on the consumer site (phone code + passkey) | owner, staff, customer | `tests/sign-in.setup.ts` |
| 2 | **onboarding** — a new business: account, business details, every verification check (fake Stripe Identity), submit → a console reviewer approves it in the verification queue → the owner's "approved" email → its first product is published (vetting; the console reviewer approves it if the checks flag it) and its page is public | owner, staff, customer | `tests/1-onboarding.spec.ts` |
| 3 | **quote → booking → escrow** — the customer asks a provider for a quote, the provider writes an itemized quote in the Studio, the customer accepts it (address, passkey step-up, the whole amount held in escrow), the provider travels, checks in and completes the job, the customer signs off, the escrow is released into the provider's earnings | owner, customer | `tests/2-quote-booking-escrow.spec.ts` |
| 4 | **order → pack → deliver** — the shop lists a product, the customer buys it for direct courier delivery (card stand-in + bank approval), the shop marks it packed in the Studio, dispatch puts a courier on shift and gives them the run in the console, the courier (courier app's DPoP-bound tokens) picks up and drops off with the customer's PIN, the order page says "Delivered" | owner, staff, customer, courier | `tests/3-order-delivery.spec.ts` |
| 5 | **payout** — the payout run pays what is available; the payout is in Studio › Payouts, on its way to the bank; the "payout sent" email | owner | `tests/4-payout.spec.ts` |
| 6 | **Studio smoke** (`@smoke`) — every Studio screen of the three seeded businesses at 1280 px and 375 px in English and 1280 px in French: no console error, no failed `/api` call, no error state, a heading, no horizontal scroll | owner | `tests/5-studio-smoke.spec.ts` |

Steps that have no screen in the app that does them go through the same app's BFF with the person's session (the
customer's sign-off lives in the mobile app; dispatch's courier and shift calls) or through the courier API with the
courier app's tokens. Everything else is clicked.

## Run it locally

```sh
make e2e                     # ≈ 15–20 min: build, start, run (≈ 6–7 min), stop
make e2e-smoke               # only the Studio sweep (E2E_ARGS="--grep @smoke")
make e2e E2E_ARGS=tests/4-payout.spec.ts
```

`make e2e` runs `ci/e2e.sh run` inside `flock` on `E2E_LOCK` (default `.run/stack.lock`): **one full stack per
machine** — a second `make e2e`, or a load test using the same lock, waits. It needs Docker (for its Postgres), JDK 25,
Node 22 + pnpm, and Playwright's Chromium (`pnpm --filter @northline/ui exec playwright install chromium` once, or
`CHROMIUM=/path/to/chrome`; where `/opt/pw-browsers` exists it is used and nothing is installed). What `ci/e2e.sh`
starts:

| what | where | notes |
|---|---|---|
| Postgres 17 + PostGIS | Docker container `northline-e2e-pg-55117`, port `DB_PORT` (55117), on tmpfs | **disposable**: migrated with the dev seed (`db/seed-dev`) and the categories; removed at the end. `DB_HOST=…` uses a database you give instead (CI's service) — it must be disposable too |
| api, northline-auth | 8080, 9000 | boot jars, profile `local`; payments jobs every 5 s (`NORTHLINE_PAYMENTS_JOBS_INTERVAL=PT5S`) |
| studio-bff, consumer-bff, console-bff | 8082, 8081, 8083 | the bff jar with `local`, `local,consumer`, `local,console` |
| Studio, consumer site, console | 3100, 3000, 3200 | Vite dev servers through their BFFs (`NL_DEV_USER` forced empty) |

The ports are the ones the local OAuth clients are registered with, so they are fixed: `ci/e2e.sh` refuses to start
when one is taken (`make down`, or stop what holds it). Everything it started is stopped by process group (never by
name) and the container is removed, also when the run fails or is interrupted.

**Writing tests:** `make e2e-up` once (≈ 5 min), then `make e2e-test E2E_ARGS=tests/3-order-delivery.spec.ts` as
often as needed (or `cd web && pnpm --filter @northline/e2e exec playwright test --ui`), finally `make e2e-down`. The
database lives as long as the stack: reruns see the earlier runs' data, which the tests are written to tolerate.

### Output

Everything goes to `e2e-out/` (`E2E_OUT`; the CI artifact):

| path | what |
|---|---|
| `report/index.html` | the HTML report — `pnpm --filter @northline/e2e report` opens it |
| `artifacts/<test>/` | on failure: `trace.zip` (every actor's context — `pnpm --filter @northline/e2e exec playwright show-trace …`), each actor's last screen and video, the page snapshot (`error-context.md`) |
| `junit.xml`, `results.json` | for CI and for counting flakes |
| `logs/` | api, auth, the BFFs, the dev servers, Gradle — the first place to look when a step waits for something that never comes |
| `state/` | the setup's signed-in states, the customer's exported passkey, the TOTP steps used |

## Test data and personas

- **Seeded** (dev seed, local only): Ravi Sandhu owns the provider and the shop the journeys use (authenticator key from
  `db/seed-dev/V101__auth.sql`); Priya Natarajan is the console reviewer and dispatcher (backup codes, `V191`; the
  suite remembers which ones it used).
- **New every run**: the customer (and, for the delivery, a courier) sign up with a phone code read from the local
  outbox and a passkey from Chrome's virtual authenticator; businesses, products and quote descriptions carry the run id
  (`E2E_RUN_ID`, the start time), the product photo is drawn from its title (vetting flags a reused image), the visit
  is the provider's first free hour from its public calendar. Reruns against the same database never collide.
- **Places and businesses** come from `web/e2e/data/local.json` (the pilot market's time zone, address and position,
  the seeded businesses) — data, not code (region-neutral rule).

### Local-only routes the suite uses

| route | what | |
|---|---|---|
| `GET /api/auth/dev/outbox?to=…` (auth) | the codes the local SMS fake "sent" | sign-up codes |
| `GET /api/v1/dev/outbox?to=…` (api) | the emails and texts the api sent (recorded around the email and SMS adapters) | the approval and payout emails |
| `POST /api/v1/dev/merchants/{id}/payouts/run` (api, owners only, through the studio-bff) | the scheduled payout run for one business now | the payout journey |
| `GET /api/v1/dev/identity-sessions/…` (api, S-22) | the fake Stripe Identity page | onboarding |

They exist only under the `local` profile: `DevOutboxTest` (auth) and `DevOnlyRoutesTest` (api: every `/api/v1/dev`
route, the outbox's proxies) check that neither the beans nor the routes are there under `prod`, `staging` or `dev`.

## Target an environment

```sh
make e2e-target ENV=staging        # dev or staging; prod is refused (the suite creates businesses, orders, payouts)
```

No stack is started; the suite runs with `E2E_MODE=target` against the environment's URLs. It needs:

| variable | what | GitHub (`e2e.yml`) / GitLab (`e2e:target`) |
|---|---|---|
| `E2E_STUDIO_URL`, `E2E_CONSUMER_URL`, `E2E_CONSOLE_URL`, `E2E_AUTH_URL`, `E2E_COURIER_API_URL` | the Studio, consumer site, console, northline-auth and the api the courier app calls | Environment variables / CI/CD variables scoped to the environment |
| `E2E_DATA` (a file) or `E2E_DATA_JSON` (CI) | the environment's test data: copy `web/e2e/data/target.example.json` — its market, the test provider and shop of the owner persona | Environment variable `E2E_DATA_JSON` / CI/CD variable |
| `E2E_OWNER_IDENTIFIER`, `E2E_OWNER_TOTP_SECRET` | a business owner with an authenticator app who owns the test provider and shop | secrets / masked variables |
| `E2E_STAFF_IDENTIFIER`, `E2E_STAFF_TOTP_SECRET` | staff with the trust & safety and dispatch console roles | secrets |
| `E2E_COURIER_IDENTIFIER`, `E2E_COURIER_TOTP_SECRET`, `E2E_COURIER_USER_ID` | a courier of the market with an authenticator app (must not hold a run) | secrets / variable |
| `E2E_CONSUMER_IDENTIFIER`, `E2E_CONSUMER_PASSKEY` | a customer and their passkey (below) | secrets |

On a laptop put them in `web/e2e/env/<env>.env` (`KEY=value` lines; git-ignored) — `make e2e-target` reads it.

**The customer's passkey:** consumers sign in with a phone code or a passkey, and a deployed environment texts real
phones, so the suite signs the customer in with a passkey imported into Chrome's virtual authenticator. Create it once:
run the setup against the environment's sign-up in a headed browser, or register the account by hand in Chrome with
DevTools › WebAuthn › "Enable virtual authenticator environment", then copy the credential (`credentialId`,
`privateKey`, `rpId`, `userHandle`, `signCount`, `isResidentCredential`) as JSON into `E2E_CONSUMER_PASSKEY`. It is a
test account's key — rotate it like any test secret.

**What target mode skips** (with the reason in the report): onboarding (identity verification goes through Stripe
Identity; there is no fake page), the emails (no outbox), the payout *run* (deployed environments pay on schedule — the
test checks the latest payout in Studio › Payouts instead) and the Studio smoke sweep (it names the dev seed's
businesses). Sign-in, quote → booking → escrow and order → pack → deliver run unchanged.

## CI (manual only)

GitHub: Actions › **e2e** › Run workflow (`environment`: local, dev, staging; `grep`), or
`gh workflow run e2e.yml -f environment=staging`. GitLab: Run pipeline with `PIPELINE_PART=e2e`, `E2E_TARGET`
(`local`, `dev`, `staging`) and optionally `E2E_GREP`. `local` runs `make e2e` in the job with a PostGIS service;
`dev`/`staging` run `make e2e-target`. Report, traces and videos are the `e2e-<environment>` artifact (14 days).

**Nightly on staging later** — one line. GitHub: under `on:` in `.github/workflows/e2e.yml`,
`schedule: [{ cron: "17 9 * * *" }]` (a scheduled run has no inputs; every job then reads the environment as
`staging`). GitLab: a pipeline schedule with `PIPELINE_PART=e2e` and `E2E_TARGET=staging`, plus this line under
`workflow: rules:` in `.gitlab-ci.yml`: `- if: $CI_PIPELINE_SOURCE == "schedule" && $PIPELINE_PART == "e2e"`.
The acceptance criterion "green on staging nightly" starts counting from that switch.

## Flake triage

A red run is a bug until shown otherwise — the suite has no retries on purpose (`retries: 0`), so a flake shows up.

1. **Open the trace** of the failed test (`artifacts/<test>/trace.zip`): the step, the locator, what the page showed,
   the network. Each actor's video is attached; `error-context.md` is the page as text.
2. **Check `e2e-out/logs/`** for the time of the failure: a 4xx/5xx from the api, `BFF session of … ended` (the
   studio/consumer/console bff), a code that never reached the outbox (auth).
3. **Rerun only that test** a few times on the same stack: `make e2e-up`, then
   `make e2e-test E2E_ARGS="tests/3-order-delivery.spec.ts --repeat-each=5"`. Fails every time → a regression
   (bisect against main); fails sometimes → a flake: keep going.
4. **Classify and fix the cause**, never with a sleep or a retry:

| symptom | usual cause | fix |
|---|---|---|
| a click on the consumer site does nothing | clicked before React hydrated the server-rendered page | `hydrated(locator)` before the first interaction on a server-rendered page |
| a value is read too early (a total, a status) | asserting before the screen settled | a web-first assertion (`expect(locator).toHaveText(…)`) on the value, not `textContent()` right away |
| waits on something a background job does (escrow release, payout state) | job interval | `expect(async () => { reload; expect… }).toPass()`; locally the payments jobs run every 5 s |
| `Your session has ended` / sign-in page mid-test | the BFF ended the session | the log says why: `refresh refused` was S-117's BFF race (fixed: `SerializedRefresh`); anything else is a bug |
| works on a fresh stack, fails on a rerun | data left by an earlier run (a run left on a courier, a slot taken) | make the test pick fresh data (unique names, the provider's free hour, a new courier) instead of fixed ids |
| times out only under load (CI, other builds) | a timeout too tight for a dev server's first compile | raise that one wait, with the reason in a comment |

5. **Record it**: a flake fixed in the suite → a line in the PR; a product bug → a backlog item, and keep the test
   red until it is fixed (or `test.fixme` with the item's id, never a silent skip).

Rules for new tests: one browser context per actor (`owner`, `staff`, `consumer` fixtures); names unique per run
(`unique(…)`); no fixed sleeps (`waitForTimeout` is not used anywhere); local-only steps behind `isLocal` with the
reason; selectors by role and visible text, as a person reads the screen.
