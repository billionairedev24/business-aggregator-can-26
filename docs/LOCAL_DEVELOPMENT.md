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
2. [Prerequisites](#prerequisites)
3. [Start and stop](#start-and-stop)
4. [Ports and URLs](#ports-and-urls)
5. [Build, test, lint, format](#build-test-lint-format)
6. [Database, Kafka, search](#database-kafka-search)
7. [Deploy and infrastructure checks](#deploy-and-infrastructure-checks)
8. [CI runs the same targets](#ci-runs-the-same-targets)
9. [Troubleshooting](#troubleshooting)

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
| `make up SERVICES="auth api bff studio"` | real sign-in: northline-auth :9000 → studio-bff :8082 → Studio (`ravi.sandhu@example.com`, TOTP key `NORTHLINERAVIDEVTOTPSECRET234567`; README § Local sign-in) |
| `make up SERVICES="auth api bff-consumer consumer"` | the consumer web :3000 through the consumer-bff :8081 |
| `make up SERVICES="api consumer"` | the consumer web with dev auth as Amara Osei (no BFF, no auth server) |
| `make up SERVICES=all PROFILES=all` | every app (incl. the worker and Storybook) and every stand-in |
| `make up PROFILES=none` | no containers: your own Postgres from `server/.env` ([runbooks/local.md § 3](runbooks/local.md#3-postgres)) |
| `make run SERVICES=api` | only the api, in the foreground (also `make dev`) |
| `make status` · `make logs` · `make restart SERVICES=api` | what runs and the useful URLs · follow the logs · restart one app |
| `make down` · `make down SERVICES=studio` · `make down VOLUMES=1` | stop everything · one app · and delete the stand-ins' data |
| `make all` | everything CI checks: `server-build` (tests included) and `web-check` |
| `make db-reset` | drop and recreate the local database, migrate, seed (asks first) |

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
| http://localhost:8080/swagger-ui.html | api's Swagger UI (local, dev, staging; S-125 adds Scalar and Redoc) |
| http://localhost:9000/.well-known/openid-configuration | northline-auth |
| http://localhost:8082, :8081 | studio-bff, consumer-bff (reached through the web dev servers) |
| http://localhost:8084/actuator/health | worker health |
| http://localhost:6006 | Storybook |
| http://localhost:8025 | Mailpit inbox (`PROFILES=…,mail`) |
| http://localhost:8190, :5601 | Kafka UI, Kibana (`make kafka-ui`, `make kibana`) |

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
| `make e2e` | the Studio smoke sweep against a **disposable** database ([runbooks/ci.md](runbooks/ci.md)) |
| `make all` · `make build` · `make test` · `make lint` · `make format` | both stacks |
| `make clean` · `make clean-all` | build outputs and runner logs · also `node_modules` and `server/.gradle` |

Gradle runs with `--max-workers=2` (`GRADLE_FLAGS` to change) and CI's optional Maven mirror init script (a no-op
unless `MAVEN_MIRROR_URL` is set).

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
