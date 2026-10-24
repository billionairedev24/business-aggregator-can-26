# Run it locally

From a fresh clone to Northline running on your machine with `make`: the stand-ins (Postgres, Valkey, Kafka,
Elasticsearch, …), the four Spring Boot apps, the Studio and the consumer web, plus every build, test and operator
check CI runs. `make help` lists every target; this page explains them by workflow.

Configuration details — your own Postgres instead of Docker, every stand-in, the `.env` files, the cloud-shape
rehearsal and troubleshooting of the apps themselves — live in [runbooks/local.md](runbooks/local.md). Cloud
environments: [runbooks/](runbooks/README.md).

Checked on Linux (Ubuntu 24.04, OpenJDK 25, Node 22.22, pnpm 10.17, Docker 28 + Compose 5.1, GNU make 4.3) on
30 Sep 2026. The Makefile and `scripts/stack.sh` are written for macOS's stock make 3.81 and bash 3.2 as well; they
were reviewed for it, not run on a Mac.

## Contents

1. [The short version](#the-short-version)
2. [Run everything locally with your own Postgres, Valkey and Grafana](#run-everything-locally-with-your-own-postgres-valkey-and-grafana)
3. [Prerequisites](#prerequisites)
4. [Start and stop](#start-and-stop)
5. [Ports and URLs](#ports-and-urls)
6. [Build, test, lint, format](#build-test-lint-format)
7. [Look at the UI: `make ui-shots`](#look-at-the-ui-make-ui-shots)
8. [Database, Kafka, search](#database-kafka-search)
9. [Deploy and infrastructure checks](#deploy-and-infrastructure-checks)
10. [CI runs the same targets](#ci-runs-the-same-targets)
11. [Troubleshooting](#troubleshooting)

## The short version

```sh
git clone https://github.com/billionairedev24/business-aggregator-can-26.git northline && cd northline
make setup up run
```

- `make setup` checks the toolchain (JDK 25, Node 22 + pnpm required; Docker, helm, terraform, … optional — it says
  what each is for), creates `.env`, `server/.env`, `web/apps/studio/.env` and `web/apps/consumer/.env` from their
  examples (never overwrites) and runs `pnpm install`.
- `make up` starts the compose stand-ins of `COMPOSE_PROFILES` in `.env` (`db` = Postgres 17 + PostGIS), waits until
  they are healthy, applies the migrations with the dev personas and seeds the categories, then starts the apps of
  `SERVICES` (default `api studio`) in the background and waits until each answers.
- `make run` follows their logs in the foreground (one prefix per app). Ctrl-C leaves apps that `make up` started
  running; `make down` stops them and the stand-ins.

Open http://localhost:3100 — the Studio, signed in as Ravi Sandhu (owner of Prairie Wrench, Prairie Wrench Parts
and Pho Dau Bo) through dev auth: without a BFF the Studio's dev server sends `X-Dev-User` to the api, which accepts
it under the `local` profile only.

| Make target | What it does |
| --- | --- |
| `make up SERVICES="auth api bff studio"` | real sign-in: northline-auth :9000 → studio-bff :8082 → Studio (`ravi.sandhu@example.com`, the code from `make creds WHO=ravi`) |
| `make up SERVICES="auth api bff-consumer consumer"` | the consumer web :3000 through the consumer-bff :8081 |
| `make up SERVICES="api consumer"` | the consumer web with dev auth as Amara Osei (no BFF, no auth server) |
| `make up SERVICES="auth api bff-console console"` | the platform console :3200 through the console-bff :8083 (S-90; staff sign-in, README § Local sign-in) |
| `make up SERVICES="api console"` | the console with dev auth as Priya Natarajan, staff with every console role |
| `make up SERVICES=all PROFILES=all` | every app (incl. the worker and Storybook) and every stand-in |
| `make up-all` | **everything, end to end**: every app, every stand-in you don't run yourself (`BYO_SERVICES` in `.env`), the observability stack with local alerting, migrations — and one table with every URL ([below](#run-everything-locally-with-your-own-postgres-valkey-and-grafana)) |
| `make up PROFILES=none` | no containers: your own Postgres from `server/.env` ([runbooks/local.md § 3](runbooks/local.md#3-postgres)) |
| `make run SERVICES=api` | only the api, in the foreground (also `make dev`) |
| `make status` · `make logs` · `make restart SERVICES=api` | what runs and the useful URLs · follow the logs · restart one app |
| `make down` · `make down SERVICES=studio` · `make down VOLUMES=1` | stop everything · one app · and delete the stand-ins' data |
| `make all` | everything CI checks: `server-build` (tests included) and `web-check` |
| `make grafana-move-port [PORT=3001]` | your own Grafana on :3000 (the consumer web app's port): moves a Homebrew or Docker Grafana to PORT and sets `GRAFANA_URL` in `.env` |
| `make db-create` | create the role + database from `server/.env` on your own Postgres if missing, add PostGIS, migrate, seed (never drops; `MIGRATE=0` only creates) |
| `make db-reset` | drop and recreate the local database, migrate, seed (asks first) |

## Run everything locally with your own Postgres, Valkey and Grafana

Root `.env`:

```sh
BYO_SERVICES=db,cache,grafana
GRAFANA_URL=http://localhost:3001       # your Grafana, not on 3000 (the consumer web app's port)
GRAFANA_TOKEN=glsa_your_token           # service account token, Admin role (or GRAFANA_USER + GRAFANA_PASSWORD)
```

`server/.env` — your Postgres (with PostGIS) and Valkey:

```sh
DB_URL=jdbc:postgresql://localhost:5432/northline
DB_USER=northline
DB_PASSWORD=northline
REDIS_HOST=localhost
REDIS_PORT=6379
```

Then:

```sh
make up-all                  # = make up-all BYO=db,cache,grafana without the .env line
make obs-fire-test-alert     # optional: watch an alert go pending → firing → Mailpit
make down                    # stops the apps and the stand-ins; never your own services
```

`make up-all` checks your Postgres (PostGIS available, version 17 recommended), Valkey and Grafana first and says what
to fix; starts every stand-in you didn't list (Kafka + Kafka UI, Elasticsearch, Mailpit, RustFS, stripe-mock) and the
observability stack (Collector, Prometheus with the S-113 rules, Alertmanager → Mailpit, Loki, Tempo — Prometheus,
Loki and Tempo on host ports for your Grafana); provisions your Grafana (data sources, folder Northline, every
dashboard); migrates and seeds your database; starts auth, api, the three BFFs, the worker, the Studio, the consumer
web and the console with telemetry on, `LIVE_BUS=redis` and sessions in your Valkey; and prints the status table
(`make urls` again later). `make up-all-check` runs only the checks. Without `grafana` in `BYO_SERVICES` you get the
bundled Grafana on http://localhost:3300 (admin / admin) instead. Details: [runbooks/local.md § 6a](runbooks/local.md#6a-run-everything-locally-with-your-own-postgres-valkey-and-grafana),
[runbooks/observability.md § Local](runbooks/observability.md#local).

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| JDK | **25** | the server apps and Gradle. `make` finds one through `JAVA_HOME`, `/usr/libexec/java_home -v 25`, Homebrew `openjdk@25`, SDKMAN or `/usr/lib/jvm`, even when `JAVA_HOME` points at another version |
| Node.js + pnpm | Node 22+, pnpm 10.17 | the web apps; `corepack enable` picks pnpm's version from `web/package.json` |
| Docker + Compose | Compose 2.20+ | the stand-ins you don't run yourself, the server tests (Testcontainers), images |
| GNU make | 3.81+ | everything here (macOS ships 3.81) |
| helm, kubeconform, kubectl, kind | helm 3.14+ (CI 3.19), kubeconform 0.7 | `helm-validate`, `argocd-validate`, `kind-up` |
| terraform, tflint | 1.9+ (CI 1.16.4), tflint 0.64 | `tf-validate`, `tf-lint` (bash 4+ and GNU find: `brew install bash findutils` on macOS) |
| psql | 17 | optional: `db-psql` / `db-reset` against your own Postgres |

`make doctor` prints what you have.

## Start and stop

The runner behind `up`, `run`, `down`, `status`, `logs` and `restart` is `scripts/stack.sh`. Each app runs in its own
process group (setsid; Perl's `POSIX::setsid` on macOS), with its pid and log under `.run/` (git-ignored), and is
stopped by that group only — never by name, so it can't stop anything it didn't start. It compiles the server apps
once before starting them (parallel Gradle builds would race on the same classes), refuses a port something else
holds (and names the holder), and restarts an app whose command line changed (another profile, dev auth on or off).

| App | Port | Command it runs |
| --- | --- | --- |
| `api` | 8080 | `./gradlew :api:bootRun --args='--spring.profiles.active=$SPRING_PROFILE'` |
| `auth` | 9000 | `./gradlew :auth:bootRun …` (under `local` it migrates and seeds too; SMS codes are in `.run/logs/auth.log`) |
| `bff` | 8082 | `./gradlew :bff:bootRun …` — the studio-bff |
| `bff-consumer` | 8081 | `./gradlew :bff:bootRun --args='--spring.profiles.active=$SPRING_PROFILE,consumer'` |
| `worker` | 8084 | `./gradlew :worker:bootRun` (needs `PROFILES=db,events,search` and `make search-indices`) |
| `studio` | 3100 | `pnpm --filter @northline/studio dev`; with `NL_DEV_USER=$DEV_USER` unless `bff` runs |
| `consumer` | 3000 | `pnpm --filter @northline/consumer dev`; with `NL_DEV_USER=$CONSUMER_DEV_USER` unless `bff-consumer` runs |
| `storybook` | 6006 | `pnpm --filter @northline/ui storybook` |
| `docs` | 3300 | `pnpm --filter @northline/docs start` — the documentation site's dev server (internal variant, English); the bundled Grafana uses 3300 too, so not both at once |

`DEV_AUTH=1` or `DEV_AUTH=0` forces dev auth on or off for the web apps; `SPRING_PROFILE=local,valkey` keeps sessions
in Valkey (`PROFILES=db,cache`). Single apps in the foreground without the runner: `make run-api`, `run-auth`,
`run-bff`, `run-bff-consumer`, `run-worker`, `run-studio`, `run-studio-dev`, `run-consumer`, `run-consumer-dev`.

Stand-ins only: `make standins-up PROFILES=db,cache,events`, `make standins-down`, `make standins-logs SERVICE=kafka`.
The profiles are docker-compose.yml's: `db`, `cache`, `events` (Kafka + topic creation), `search`, `mail`, `storage`,
`payments`, `all`, and `tools` (`make kafka-ui`, `make kibana`).

## Ports and URLs

| URL | What |
| --- | --- |
| http://localhost:3100 | Studio (dev server, proxies `/api` to the api or the studio-bff) |
| http://localhost:3000 | consumer web (SSR) |
| http://localhost:8080/api/v1/… | api (`curl -H 'X-Dev-User: 01J9ZD3V00000000000000RAV1' localhost:8080/api/v1/me/businesses`) |
| http://localhost:8080/docs | the api's API documentation: every audience in Swagger UI (`/swagger-ui.html`), Scalar (`/docs/scalar`) and Redoc (`/docs/redoc`); auth's on :9000/docs, the BFFs' on :8082/bff/docs and :8081/bff/docs ([runbooks/api-docs.md](runbooks/api-docs.md)) |
| http://localhost:9000/.well-known/openid-configuration | northline-auth |
| http://localhost:3200 | platform console (S-90; dev server, proxies `/api` to the api or the console-bff) |
| http://localhost:8082, :8081, :8083 | studio-bff, consumer-bff, console-bff (reached through the web dev servers) |
| http://localhost:8084/actuator/health | worker health |
| http://localhost:6006 | Storybook |
| http://localhost:3300 | documentation site (`make up SERVICES=docs`, or `make docs docs-serve` for the full build with search and French; [runbooks/docs-site.md](runbooks/docs-site.md)) |
| http://localhost:8025 | Mailpit inbox (`PROFILES=…,mail`) |
| http://localhost:8190, :5601 | Kafka UI, Kibana (`make kafka-ui`, `make kibana`; `make up-all` starts Kafka UI) |
| http://localhost:3300 | bundled Grafana, admin / admin (`OBS=1`, `make obs-up`, `make up-all` without your own Grafana) |
| http://localhost:9090, :9093 | Prometheus (alerts at `/alerts`), Alertmanager — alerts go to Mailpit only |
| http://localhost:3110, :3210 | Loki, Tempo (query APIs for Grafana; not 3100/3200, which the Studio and console use) |
| http://localhost:9101 | RustFS console (S3 storage, `northline` / `northline-dev-secret`) |

`make smoke` checks every app port, whoever started it.

## Build, test, lint, format

| Target | Runs |
| --- | --- |
| `make server-build [PROJECT=api]` | `./gradlew build`: compile, Error Prone + NullAway, Checkstyle, Spotless check, every test (Docker), boot jars |
| `make server-test PROJECT=api TESTS='*MerchantApiTest'` | one test class |
| `make server-lint` · `make server-format` | the static checks without tests · `spotlessApply` |
| `make server-events [BASE=origin/main]` | the event schema contract (S-34) |
| `make web-check` | CI's web job: `web-lint` (hex colours + typecheck), `web-test` (vitest), `web-build-studio` |
| `make web-build` · `web-build-consumer` | every web build · the consumer's |
| `make web-storybook-test` | Storybook build + interaction and a11y tests (Playwright Chromium: `pnpm --filter @northline/ui exec playwright install chromium`) |
| `make web-format` | Prettier on the web files you changed since `BASE` (default `origin/main`); `WEB_FORMAT_ALL=1` formats everything |
| `make openapi` · `openapi-check` · `openapi-lint` | regenerate the committed OpenAPI specs from the code · compare · Redocly lint ([runbooks/api-docs.md](runbooks/api-docs.md)) |
| `make docs [DOCS_VARIANT=public]` · `docs-serve` · `docs-dev` | build the documentation site and check every page · serve it · live dev server |
| `make e2e` · `e2e-smoke` · `e2e-target ENV=staging` | the end-to-end suite (S-117): its own disposable Postgres, the whole stack under `local`, Playwright journeys + the Studio smoke sweep · only the sweep · against a deployed environment ([runbooks/e2e.md](runbooks/e2e.md)) |
| `make a11y` | the accessibility page sweep (S-109): builds the three web apps, Playwright + axe against them with a mock api ([a11y/audit.md](a11y/audit.md)); `make a11y-record` refreshes its fixtures |
| `make ui-shots` | full-page screenshots of every web route, desktop and phone, English and French, without a backend ([below](#look-at-the-ui-make-ui-shots)) |
| `make ui-a11y` | S-180: the WCAG 2.2 AA check (axe, focus, targets, motion, text spacing) of every `ui-shots` route at 1440 and 390, English and French; one line per page in `ui-shots/<app>/_a11y.txt` ([below](#accessibility-of-every-route-make-ui-a11y)) |
| `make ui-hydration` | S-182: fails on any React hydration error on every consumer route (desktop and phone, en and fr), on the ui-shots servers and data |
| `make ui-visual` | S-168/S-180: the component gallery's screenshot baselines (`pnpm test:visual`, en + fr-CA, 1440 + 390); `UI_VISUAL_UPDATE=1` rewrites them after an intended change. With `ui-hydration`, the web CI's manual `visual` job |
| `make mobile-shots` | screenshots of every consumer and courier app screen at 390×844, without a phone or a backend ([below](#the-native-apps-make-mobile-shots)) |
| `make all` · `make build` · `make test` · `make lint` · `make format` | both stacks |
| `make clean` · `make clean-all` | build outputs and runner logs · also `node_modules` and `server/.gradle` |

Gradle runs with `--max-workers=2` (`GRADLE_FLAGS` to change) and CI's optional Maven mirror init script (a no-op
unless `MAVEN_MIRROR_URL` is set).

## Look at the UI: `make ui-shots`

Every UI change gets looked at, on a desktop and on a phone, before it is reviewed. `make ui-shots` builds the
consumer site, the Studio and the console and photographs **every route** of the three, full page, at 1440×900 and
390×844, **in English and in French** (S-180):

```
make ui-shots                                   # all three apps, en + fr (about 15 minutes)
make ui-shots UI_SHOTS_LANGS=en                 # one language (en or fr)
make ui-shots UI_SHOTS_APPS=consumer            # one app (or consumer,studio)
make ui-shots UI_SHOTS_SKIP_BUILD=1 UI_SHOTS_GREP=home   # reuse the last build, only the routes matching "home"
make ui-shots UI_SHOTS_WIDTHS=2560,1920,1440,390,360    # those widths instead (<route>-<width>.png)
```

The pictures land in `ui-shots/<app>/<route>-d.png` (desktop) and `<route>-m.png` (phone), and `<route>-fr-d.png` /
`<route>-fr-m.png` in French, at the repository root (git-ignored). The consumer is opened with `?lang=`, the Studio and
the console with their stored language (`nl.locale`). The route list is `web/packages/a11y/shots/routes.ts` — a new route goes there too. A route with `click`
(a Playwright selector) presses it once the page is ready and photographs the window, for a state behind a click: a
detail sheet, an editor step.

No backend is needed: the apps run as built (`vite preview` for the Studio and the console, the consumer's own Node
server) on ports 4641–4644, answered by the accessibility sweep's mock api (`web/packages/a11y/fixtures`, recorded from
the apps' own tests) plus demo-like data in `web/packages/a11y/shots/fixtures.mjs`: a catalogue with photos, shops,
providers, a fallback market, a Studio dashboard. The photos are the photo library's own files
(`web/packages/media`) — the very ones `make demo-data` uploads, so the pictures are what the demo shows (S-182):
listings name a library slot (`media('demo/listing/bakery.sourdough')`) and `shots/photos.ts` serves its source photo
from the apps' own origin under `/api/v1/public/catalogue/media/…`; a slot without a photo answers 404 and the page
shows the designed photo well, as the demo does. Nothing is fetched from the internet and no Content-Security-Policy
changes. A consumer route with a React hydration error fails the run; `make ui-hydration` checks every consumer route
for that alone (1440 and 390 wide, English and French, `UI_SHOTS_SKIP_BUILD=1` reuses the build). The Studio and the console are signed in (Ravi
Sandhu, Priya Natarajan), the consumer is a guest unless the route says `signedIn`. A page that still looks empty
usually lacks an answer: `ui-shots/<app>/_missing-api.txt` lists the requests nothing answered — add one to
`shots/fixtures.mjs`. A page wider than the screen (a sideways scroll on a phone) is named in
`ui-shots/<app>/_overflow.txt` with the outermost elements that stick out past the right edge (and `page Npx > Mpx`
when the document itself is wider than the window, measured with the html/body clip lifted). Two more lists help with
French, which runs about 20 % longer (S-180): `_truncated.txt` names text cut off by an ellipsis, a line clamp or its
box (a long shop name ellipsed in a card is by design; a cut button, tab or heading is a bug), and `_untranslated.txt`
the English-looking text left on a French page (the fixtures' own business, product and review texts are English, so it
lists candidates to read, not failures; `frenchCoverage.test.ts` is the gate for the strings).

### Accessibility of every route: `make ui-a11y`

`make ui-a11y` runs the accessibility sweep's checks (`web/packages/a11y/pages/support.ts`: axe for WCAG 2.0–2.2 A/AA,
the page language, text spacing, focus hidden by a sticky bar, motion under reduced motion, pointer target sizes) on
every route of `routes.ts` — the same builds, data, pinned clocks and clicks as `make ui-shots` — at 1440 and 390, in
English and French (`UI_SHOTS_LANGS`, `UI_SHOTS_APPS`, `UI_SHOTS_GREP` and `UI_SHOTS_SKIP_BUILD=1` work as for the
shots; about an hour for everything). `ui-shots/<app>/_a11y.txt` has one line per page and width (`ok`, or the
findings); the JSON of each check is in `web/packages/a11y/a11y-results/pages/`. It fails on a critical or serious
WCAG violation, a heading out of order, the wrong page language, a sideways scroll with the 1.4.12 text spacing, a
focused control hidden under a sticky bar, an animation running under reduced motion, or a target under 24 × 24 px;
targets between 24 and 44 px are listed against the project's 44 px rule (the documented exceptions are in
[a11y/audit.md](a11y/audit.md) § S-180). `make a11y` (S-109) stays the shorter sweep of 32 key screens with the ARIA
snapshots of the journeys.

### The visual regression gate: `make ui-visual` and `make ui-hydration`

`make ui-visual` compares the component gallery (`web/apps/gallery`, every `@northline/ui` section in en and fr-CA at
1440 and 390, the dialog and the sheet open) with its committed baselines (`apps/gallery/visual/__screenshots__`) and
fails past 0.1 % of changed pixels; `UI_VISUAL_UPDATE=1 make ui-visual` rewrites them after an intended change — commit
them with it, and only the ones your change meant to move. The run is deterministic: IBM Plex Sans is self-hosted and
must load, the clock is pinned (2026-10-23 18:00 UTC), the zone is UTC, motion is reduced, and the baselines are
renders of Playwright 1.56.1's Chromium (build 1194) with the fonts of Playwright's Ubuntu 24.04 image — which is why
CI runs it in `mcr.microsoft.com/playwright:v1.56.1-noble`. `make ui-hydration` is the other half of the gate (no React
hydration error on any consumer route). Both run in the web CI's `visual` job (GitHub Actions `web` workflow, input
`visual`; GitLab `web:visual`), which, like every workflow here, starts only by hand.

Sticky bars (a phone's "Add to cart", a wizard's "Continue") sit at the bottom of the 844 px screen in a full-page
picture, over the content that happens to be there: that is where a phone shows them, not an overlap.

Chromium comes from `CHROMIUM=/path`, else `/opt/pw-browsers/chromium`, else Playwright's own
(`pnpm --filter @northline/a11y exec playwright install chromium`).

### The native apps: `make mobile-shots`

There is no simulator here (no macOS, no Android SDK), so the native apps are looked at through their web build:
react-native-web renders the same screens, primitives and fonts, on the in-app fixture backend
(`EXPO_PUBLIC_FIXTURES=1`, the one the Jest tests and the web smoke tests use — no server).

```
make mobile-shots                                        # both apps (about 6 minutes)
make mobile-shots MOBILE_SHOTS_APPS=courier              # one app
make mobile-shots MOBILE_SHOTS_SKIP_BUILD=1 MOBILE_SHOTS_ONLY=home,product   # reuse the last build, only these screens
```

`mobile/scripts/shots.mjs` serves `apps/<app>/dist-web`, signs in through the fixture's browser sign-in, then opens
each screen in place (the fixture build's `__nlGo(href)`; a page load would start a new, signed-out session) and walks
the flows that need state (cart → checkout → payment, a booking's service → time → review → booked, a pickup before
its drop-off). Pictures land in `mobile/shots-out/<app>/<nn>-<screen>.png` at 390×844 (an iPhone's points, at 2×),
`-full.png` when the screen scrolls (the whole scroll content in one picture; sticky bars then sit at its bottom),
`-fr.png` for the French pass, and `_overflow.txt` names screens that scroll sideways. A screen is added to the lists in
`shots.mjs`. Native-only parts — the camera, the system browser, Keychain, Stripe's sheet, maps — show their web
fallbacks.

The stock photos are real (S-178): the fixture build asks the consumer site's `/photos/` for them, so `shots.mjs`
answers those requests from the variants `make photos` builds into `web/apps/consumer/public/photos` (`make
mobile-shots` runs `make photos` first when the folder is missing; `NL_PHOTOS_DIR` points elsewhere,
`MOBILE_SHOTS_NO_PHOTOS=1` goes on without them). Variants the folder lacks are listed in `_missing-photos.txt`.
`NL_SHOTS_DIST=<dist-web folder> NL_SHOTS_OUT=<folder>` photographs another build — an older branch's, for before
pictures.

## Database, Kafka, search

| Target | Runs |
| --- | --- |
| `make db-migrate [DEV_SEED=false]` · `db-info` | `:api:flywayMigrate -Pdb.devSeed=true` · `:api:flywayInfo` |
| `make db-seed` | `:api:seedCategories` (idempotent) |
| `make db-reset [YES=1]` | drop + recreate the database (compose container, or your local Postgres with psql as a superuser), migrate, seed; refuses a non-local `PGHOST` |
| `make db-psql` | psql on the database |
| `make kafka-topics` · `-plan` · `-verify` · `-list` | the topic catalogue through the deploy Job's provisioner (`:worker:kafkaTopics`); `-list` needs no Kafka |
| `make kafka-dlq TOPIC=… GROUP=…` · `kafka-dlq-replay …` | DLQ dry run and replay (S-26) |
| `make search-indices` · `-plan` · `-verify` | synonym sets and indices (S-42) |
| `make search-reindex [KEEP_OLD=1]` | full reindex with an alias swap (S-71) |

The database targets use `DB_URL` / `DB_USER` / `DB_PASSWORD` from the environment, then `server/.env`, then
`localhost:5432/northline`.

## Deploy and infrastructure checks

| Target | Runs |
| --- | --- |
| `make helm-validate` · `argocd-validate` · `deploy-validate` | `deploy/helm/validate.sh`, `deploy/argocd/validate.sh`, both |
| `make images` · `images-java` · `images-web` | Jib (`jibDockerBuild`) and `web/Dockerfile` into your Docker; `PUSH=1 REGISTRY=… IMAGE_TAG=…` pushes |
| `make kind-up` · `kind-down` | the local Helm rehearsal ([runbooks/deploy.md § Local: kind](runbooks/deploy.md#local-kind)) |
| `make gitops-promote ENV=staging FROM=dev` | writes `deploy/argocd/envs/<env>/images.yaml` for a promotion PR |
| `make tf-validate [CLOUD=aws]` · `tf-lint` · `tf-fmt` · `infra-validate` | offline Terraform checks |

## CI runs the same targets

The manual jobs in `.github/workflows` and `ci/gitlab` call these targets (`make server-build TASKS=…`, `web-lint`,
`web-test`, `web-build-studio`, `web-storybook-test`, `e2e`, `tf-validate`, `tf-lint`, `helm-validate`,
`argocd-validate`, `images-java`, `server-events`), so a green `make` locally is a green job. They stay manual-only
([runbooks/ci.md](runbooks/ci.md)). The web image build and the promotion job keep their own steps (buildx caching,
PR creation).

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `port 8080 (api) is taken by java (pid …)` | an app started outside make; stop it, or `make down` if make started it in another checkout |
| `Java 25 not found` | install Temurin 25; `make doctor` shows what `make` found |
| `make up` hangs at the stand-ins | `docker compose --wait` waits for health checks; `make standins-logs SERVICE=postgres` |
| An app exits during `make up` | the runner prints the last lines of `.run/logs/<app>.log`; see [runbooks/local.md § 8](runbooks/local.md#8-troubleshooting) |
| `db-reset: PGHOST=… is not local — refused` | on purpose: it only drops local databases |
| `make tf-validate` fails on macOS with `mapfile: command not found` | `brew install bash findutils` and put them first on `PATH` |
| `*** recipe commences before first target` or odd syntax errors | a make older than 3.81; macOS's `/usr/bin/make` is 3.81 and works |
