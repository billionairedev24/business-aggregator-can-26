# Runbook — CI (GitHub Actions and GitLab CI)

Stories S-4 (server), S-5 (web) and the infrastructure checks of S-2/S-3 (Terraform). The same checks are defined for **GitHub Actions** and **GitLab CI**; use
whichever has credits. Nothing is tied to one cloud: the jobs need only a Linux runner with Docker (GitHub) or a
Docker executor that allows `docker:dind` (GitLab), plus public images and package registries.

> **Manual only.** CI credits are depleted, so no pipeline starts on push, pull/merge request or schedule.
> Someone has to start it by hand (below). [Enabling automatic runs](#enabling-automatic-runs) is a one-line change.

## What runs

| Part | GitHub (`.github/workflows/`) | GitLab (`.gitlab-ci.yml` + `ci/gitlab/`) | What it does |
|---|---|---|---|
| server | `server.yml` › `gradle build` | `server:build` | JDK 25 (Temurin). `./gradlew build` in `server/`: Spotless check, Checkstyle, Error Prone + NullAway, every test (Testcontainers PostGIS and Kafka 4, `ModularityTests`, ArchUnit). Gradle cache, ≤ 2 workers. JUnit XML → GitHub check "server tests" / GitLab test report; HTML reports as artifacts. |
| web | `web.yml` › `checks` | `web:checks` | Node 22 + pnpm 10 (corepack). No hex colours in components (`pnpm lint:colors`), `pnpm -r typecheck`, `pnpm -r test` (vitest), `pnpm --filter @northline/studio build` (artifact `studio-dist`). |
| web | `web.yml` › `storybook` | `web:storybook` | `build-storybook` (artifact `storybook-static`) and `test-storybook`: every story in headless Chromium with its play function (interaction tests) and the a11y addon (`a11y.test: 'error'` — any violation fails). |
| infra | `infra.yml` › `validate` | `infra:validate` | Terraform 1.16.4. `infra/terraform/scripts/validate.sh`: `terraform fmt -check`, the module contract check (the AWS, Google Cloud and Azure implementations of each capability share variables and outputs), `init -backend=false` + `validate` for every module, stack, bootstrap and env root, and `terraform test` in each env root (a plan against mocked providers, plus a check that non-Canadian regions are rejected). No cloud credentials, no state. |
| infra | `infra.yml` › `tflint` (on by default) | `infra:tflint` (on by default) | tflint 0.64 with the terraform, aws, google and azurerm rulesets (`infra/terraform/.tflint.hcl`). |
| deploy | `deploy.yml` › `java`, `web` (images) | `images:java`, `images:web` (`PIPELINE_PART=images` only, never in `all`) | S-14: build and push `api`, `auth`, `bff`, `worker` with Jib and `studio`, `consumer` with `web/Dockerfile` (buildx, amd64 + arm64) to the registry given as input; digests in the run summary / `image-digests.txt` ([deploy.md](deploy.md#build-and-push)) |
| deploy | `deploy.yml` › `chart` | `chart:validate` (`PIPELINE_PART=chart` or `all`) | S-14: `deploy/helm/validate.sh` — `helm lint --strict` + `helm template \| kubeconform -strict` for dev/staging/prod × aws/gcp/azure, kind and Gateway API |
| gitops | `gitops.yml` › `validate` (`action=validate`) | `gitops:validate` (`PIPELINE_PART=gitops` or `all`) | S-15: `deploy/argocd/validate.sh` — the app of apps for dev/staging/prod × aws/gcp/azure \| `kubeconform -strict` against the Argo CD CRDs, the sync policy (only dev automated), the chart as each Application renders it, the Argo CD install kustomization ([gitops.md](gitops.md#checks-no-cluster)) |
| gitops | `gitops.yml` › `promote` (`action=promote`) | `gitops:promote` (`PIPELINE_PART=promote` only) | S-15: `deploy/argocd/promote.sh` writes `deploy/argocd/envs/<env>/images.yaml` (digests looked up in the registry, or copied from another environment) and opens the promotion PR / merge request ([gitops.md § Promotion](gitops.md#promotion-build--digest--pr--sync)) |
| events | `event-schemas.yml` › `event schemas` | `events:schemas` (`PIPELINE_PART=events` or `all`) | S-34: `./gradlew :event-contracts:eventSchemas` with the base branch (input `base` / `EVENT_SCHEMAS_BASE`, default `main`, fetched in full): schemas valid for the worker's validator, every `@Externalized` event ↔ a schema, sample payloads validate, no breaking change against the merge base without a version bump — required base (exit 2 without it). `server build` runs the first three and check 4 when the base is there ([events.md § Schema checks](events.md#7-schema-checks-s-34)). |
| openapi | `openapi.yml` › `lint`, `check` | `openapi:lint` (`PIPELINE_PART=openapi` or `all`) | S-125: `make openapi-lint` (Redocly CLI over `docs/api/openapi`, `redocly.yaml`) and `make openapi-check` (the specs the code serves equal the committed ones; the server build runs the same tests) ([api-docs.md](api-docs.md)). |
| courier | `courier.yml` › `checks` | `courier:checks` (`PIPELINE_PART=courier` only, never in `all`) | S-87: `make courier-check` in `mobile/` — ESLint (warnings fail), `tsc`, Jest + React Native Testing Library, `expo export` of the iOS and Android bundles. No native build ([courier-app.md](courier-app.md#checks)). |
| courier | `courier.yml` › `web-smoke` (on by default) | `courier:web-smoke` | S-87: `make courier-web-smoke` — the web build on the in-app fixture backend in headless Chromium (Playwright image); screenshots as the `courier-smoke` artifact. |
| courier | `courier.yml` › `eas` (input `eas`, default none) | `courier:eas-build` (manual, needs `EXPO_TOKEN`) | S-87: `make courier-eas-build` — queues an EAS Build of the chosen profile; skipped without `EXPO_TOKEN` / `EAS_PROJECT_ID` ([courier-app.md § EAS](courier-app.md#eas-builds)). |
| mobile-consumer | `mobile-consumer.yml` › `checks` | `mobile-consumer:checks` (`PIPELINE_PART=mobile-consumer` only, never in `all`) | S-97: `make mobile-consumer-check` in `mobile/` — ESLint (warnings fail), `tsc`, Jest + React Native Testing Library, `expo export` of the iOS and Android bundles ([mobile.md](mobile.md#checks)). |
| mobile-consumer | `mobile-consumer.yml` › `native-check` (on by default) | `mobile-consumer:native-check` | S-97: `make mobile-consumer-native-check` — `expo prebuild` of both platforms for the development and production variants and checks of the generated manifests, entitlements and permissions; no Xcode or Android SDK. |
| mobile-consumer | `mobile-consumer.yml` › `web-smoke` (on by default) | `mobile-consumer:web-smoke` | S-97/S-98: `make mobile-consumer-web-smoke` — the web build on the in-app fixture backend in headless Chromium at 402 × 874; screenshots as the `mobile-consumer-smoke` artifact. |
| mobile-consumer | `mobile-consumer.yml` › `eas` (input `eas`, default none) | `mobile-consumer:eas-build` (manual, needs `EXPO_TOKEN`) | S-97: `make mobile-consumer-eas-build` — queues an EAS Build of the chosen profile; skipped without `EXPO_TOKEN` / `EAS_CONSUMER_PROJECT_ID` ([mobile.md § EAS](mobile.md#eas-builds)). |
| mobile-release | `mobile-release.yml` › `check` (every action) | `mobile-release:check` (`PIPELINE_PART=mobile-release` only, never in `all`) | S-103: the offline release checks of the chosen app (`--strict` before going public); production builds tag `<app>-vX.Y.Z` ([mobile-release.md § CI](mobile-release.md#ci)). The same checks run in every mobile `checks` job through `pnpm lint`. |
| mobile-release | `mobile-release.yml` › `eas` (actions `build`, `update`, `update-republish`, `update-rollback-embedded`, `listing`) | `mobile-release:eas` (manual, needs `EXPO_TOKEN`) | S-103: EAS Build (production → TestFlight + Play internal), EAS Update and its rollbacks, `eas metadata:push`; skipped without `EXPO_TOKEN` and the app's EAS project id. |
| mobile-release | `mobile-release.yml` › `store` (actions `listing`, `submit-review`, `promote`, `rollout`, `halt`) | `mobile-release:store` (manual, needs the store key) | S-103: fastlane (`mobile/fastlane/Fastfile`): Play listing text, promotion to a staged rollout, rollout, halt; iOS App Review; skipped without `PLAY_SERVICE_ACCOUNT_JSON` / `ASC_API_KEY_P8`. |
| security | `security.yml` › `scan`, `dast` (input `dast`, default off) | `security:scan` (`PIPELINE_PART=security` only, never in `all`) | S-104: `make security-tools` + `make security-scan` — gitleaks over the history (`.gitleaks.toml`), CycloneDX SBOMs + osv-scanner (Gradle and pnpm; fails on a known vulnerability), semgrep, checkov (Terraform, rendered chart), kube-score; reports in the `security-reports` artifact. `dast`: the api under `local` + `make security-dast` (negative tests, ZAP API scan). Triage: [docs/security/findings.md](../security/findings.md). |
| e2e | `e2e.yml` › `local` / `target` (input `environment`: local, dev, staging; `grep`) | `e2e:local` / `e2e:target` (`PIPELINE_PART=e2e` only, never in `all`; `E2E_TARGET`, `E2E_GREP`) | S-117: `make e2e` — PostGIS service, `ci/e2e.sh`: migrate + dev seed, api, auth and the three BFFs under `local`, the three web apps, then the Playwright suite (sign-in, onboarding, quote → booking → escrow, order → pack → deliver, payout, the Studio smoke sweep that S-5's `studio-smoke` job ran). Against dev/staging: `make e2e-target ENV=…` with the environment's URLs and personas. Report, traces, screenshots and videos in the `e2e-<environment>` artifact ([e2e.md](e2e.md)). |

| loadtest | `loadtest.yml` › `local` (inputs `profile`, `scale`, `scenarios`, `duration`) | `loadtest:local` (`PIPELINE_PART=loadtest` only, never in `all`; a shell runner tagged `loadtest`) | S-119: the api and worker boot jars, `make load-k6` (k6 with xk6-sse), `make load-stack-up` (Postgres, Elasticsearch, the api, seeded data), the chosen profile (`make load-smoke` / `load` / `load-stress` / `soak`); `report.md` in the run summary, every result in the `loadtest-results` artifact. Staging is load-tested from a pod in the cluster, never from CI ([load-testing.md](load-testing.md)). |
| web | `web.yml` › `studio-smoke` (optional) | `web:studio-smoke` (optional) | `ci/studio-smoke.sh`: PostGIS service → `:api:flywayMigrate -Pdb.devSeed=true` + `:api:seedCategories` → api and auth with the `local` profile → studio dev server (dev auth as Ravi Sandhu) → `scripts/studio-smoke.mjs` (135 screen/width/locale checks). Screenshots and logs in the `studio-smoke` artifact. |
| web | `web.yml` › `a11y` (optional) | `web:a11y` (optional) | `make a11y` (S-109): builds the Studio, console and consumer, then Playwright + axe on 32 journey screens at 1280 px (en) and 320 px (fr-CA) against a mock api replaying the apps' test fixtures — no backend. Critical/serious WCAG 2.2 A/AA violations, a wrong `<html lang>` or horizontal scroll at 320 px fail. Page reports in the `a11y-results` artifact; findings in [docs/a11y/audit.md](../a11y/audit.md). |

Expected durations (hosted runners; first run in brackets, before caches are warm):

| Job | Duration |
|---|---|
| server build | 6–9 min (10–12 min) — the tests take about 4 min on 2 workers |
| web checks | 2–3 min (3–4 min) |
| web storybook | 3–4 min (4–5 min, incl. the Chromium download) |
| e2e local | 15–20 min (20–25 min) — boot jars, five Spring Boot apps, three dev servers; the suite itself is about 6–7 min (the Studio smoke sweep 4 of them) |
| infra validate | 3–4 min (5–6 min, provider downloads: aws, google, google-beta, azurerm, random) |
| infra tflint | about 1 min |

## Running a pipeline by hand

### GitHub Actions
- **UI:** repository › *Actions* › pick **server** or **web** › *Run workflow* › choose the branch and inputs › *Run workflow*.
- **CLI:** `gh workflow run server.yml --ref <branch> [-f project=api] [-f skip-tests=true] [-f rerun-tasks=true]`
  or `gh workflow run web.yml --ref <branch> [-f storybook=false] [-f a11y=true]`,
  or `gh workflow run e2e.yml --ref <branch> [-f environment=staging] [-f grep=@smoke]`,
  or `gh workflow run infra.yml --ref <branch> [-f cloud=aws] [-f tflint=false]`,
  or `gh workflow run deploy.yml --ref <branch> -f registry=<registry/path> [-f image-tag=…] [-f images=java] [-f push=false] [-f login=ghcr]`,
  or `gh workflow run gitops.yml -f action=validate` / `-f action=promote -f environment=staging -f from=dev`; follow with `gh run watch`.

| Workflow | Input | Default | Meaning |
|---|---|---|---|
| server | `project` | `all` | `all`, `api`, `auth`, `bff` or `worker` (`./gradlew :<project>:build`) |
| server | `skip-tests` | `false` | static checks and compilation only (`-x test`) |
| server | `rerun-tasks` | `false` | ignore the Gradle build cache (`--rerun-tasks`) |
| web | `storybook` | `true` | run the Storybook job |
| web | `a11y` | `false` | run the accessibility page sweep job (S-109) |
| infra | `cloud` | `all` | `all`, `aws`, `gcp` or `azure`: which modules and env roots to validate |
| infra | `tflint` | `true` | run the tflint job |
| deploy | `registry` | — | registry including its path (required to push) |
| deploy | `image-tag` | short sha | image tag |
| deploy | `images` | `all` | `all`, `java`, `web` or `none` |
| deploy | `push` | `true` | `false` builds only (Java: one platform, as a tar) |
| deploy | `login` | `password` | `password` (secrets `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`) or `ghcr` (the workflow token) |
| deploy | `platforms` | `linux/amd64,linux/arm64` | image platforms |
| deploy | `chart` | `true` | run `deploy/helm/validate.sh` |
| gitops | `action` | `validate` | `validate` or `promote` |
| gitops | `environment` | `dev` | promote: target environment |
| gitops | `from` | — | promote: copy the digests `dev` or `staging` runs |
| gitops | `registry`, `image-tag` | — | promote without `from`: look up `<registry>/<app>:<image-tag>` |
| gitops | `login` | `password` | promote: registry login for the lookup (`password`, `ghcr`, `none`) |
| event-schemas | `base` | `main` | the branch the schemas are compared with |

A new run on the same branch cancels the previous one of the same workflow (concurrency group per workflow and ref).

### GitLab CI
- **UI:** project › *Build › Pipelines* › *Run pipeline* › choose the branch, adjust the variables › *Run pipeline*.
- **API:** `curl --request POST --header "PRIVATE-TOKEN: <token>" "https://gitlab.com/api/v4/projects/<id>/pipeline?ref=<branch>&variables[][key]=PIPELINE_PART&variables[][value]=server"`,
  or `glab ci run --branch <branch> --variables PIPELINE_PART:web,RUN_STUDIO_SMOKE:true`.

| Variable | Default | Meaning |
|---|---|---|
| `PIPELINE_PART` | `all` | `all`, `server`, `web`, `infra`, `chart`, `gitops` (S-15), `events` (S-34), `images` or `promote` (S-15; the last two never in `all`), `loadtest` (S-119, never in `all`) |
| `EVENT_SCHEMAS_BASE` | `main` | events: the branch the schemas are compared with |
| `SERVER_GRADLE_ARGS` | `build` | Gradle arguments for server/, e.g. `:api:build`, `build -x test`, `build --rerun-tasks` |
| `RUN_STUDIO_SMOKE` | `false` | `true` adds the studio smoke sweep |
| `RUN_A11Y` | `false` | `true` adds the accessibility page sweep (S-109) |
| `INFRA_CLOUD` | `all` | infra: `all`, `aws`, `gcp` or `azure` |
| `RUN_TFLINT` | `true` | infra: `false` skips tflint (it downloads its rulesets from GitHub) |
| `IMAGE_REGISTRY` | the project's registry | images: registry including its path |
| `IMAGE_TAG` | short sha | images: tag |
| `IMAGE_APPS` | `all` | images: `all`, `java` or `web` |
| `IMAGE_PLATFORMS` | `linux/amd64,linux/arm64` | images: platforms |
| `PROMOTE_ENV` | `dev` | promote: `dev`, `staging` or `prod` (S-15) |
| `PROMOTE_FROM` | — | promote: copy the digests `dev` or `staging` runs; empty = look up `IMAGE_REGISTRY`/`<app>`:`IMAGE_TAG` |

Runner requirements: `server:build` needs a runner with `privileged = true` for the `docker:dind` service that
Testcontainers talks to (`DOCKER_HOST=tcp://docker:2375`, `TESTCONTAINERS_HOST_OVERRIDE=docker`, Ryuk disabled).
GitLab.com hosted Linux runners qualify; on a self-managed runner set `privileged = true` in its `config.toml`.
Every job is `interruptible`, so a newer pipeline on the same branch cancels the older one.

## Variables and secrets
**None are required** for the checks — every job builds from public images and registries. Pushing images needs registry credentials (below).

| Name | Where | Purpose |
|---|---|---|
| `MAVEN_MIRROR_URL` | GitHub: *Settings › Secrets and variables › Actions › Variables*; GitLab: *Settings › CI/CD › Variables* | Optional. Maven repository that Gradle tries before Maven Central and the Plugin Portal (Maven Central answers bursts with HTTP 429). E.g. `https://maven-central.storage-download.googleapis.com/maven2/`, or an Artifactory/Nexus/GitLab package proxy. Applied by `ci/gradle/maven-mirror.init.gradle.kts`, which does nothing when the variable is empty. |
| `MAVEN_MIRROR_USERNAME`, `MAVEN_MIRROR_PASSWORD` | GitHub: *Secrets*; GitLab: masked variables | Optional, only for a mirror that needs credentials. |
| `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` (GitHub) / `REGISTRY_USER`, `REGISTRY_PASSWORD` (GitLab) | Secrets / masked variables | Only for pushing images (S-14) to a registry other than GHCR / the GitLab project registry; per-cloud values in [deploy.md](deploy.md#from-ci-manual). |
| `GITOPS_PUSH_TOKEN` (GitLab) | masked variable | Only for `PIPELINE_PART=promote` (S-15): project access token, role Developer, scope `write_repository`, to push the promotion branch and open the merge request. GitHub uses the workflow token (enable *Allow GitHub Actions to create and approve pull requests*). |

## Enabling automatic runs
**GitHub** — in `.github/workflows/server.yml`, `web.yml` and `infra.yml`, add triggers next to `workflow_dispatch` (inputs then
use their defaults, e.g. no smoke sweep):
```yaml
on:
  push: { branches: [main] }
  pull_request:
  workflow_dispatch:
    …
```
Add `paths: [server/**, ci/**, .github/workflows/server.yml]` (or `web/**, scripts/**, …`, or `infra/**`) to each trigger to build only what changed.

**GitLab** — in `.gitlab-ci.yml`, add one line under `workflow: rules:` above `- when: never`:
```yaml
    - if: $CI_PIPELINE_SOURCE == "push" || $CI_PIPELINE_SOURCE == "merge_request_event"
```
To limit jobs to what changed, add `changes: [server/**/*, ci/**/*]` (or the web paths) to the job rules in `ci/gitlab/*.yml`.

**The end-to-end suite nightly on staging (S-117)** is its own one-line switch (a schedule, not a push trigger):
[e2e.md § CI](e2e.md#ci-manual-only).

## Running the same checks locally
The jobs call make targets (S-124), so the same targets run them on a laptop:

| Job | Target |
|---|---|
| server build | `make server-build` (`PROJECT=api`, `TASKS=':api:build -x test'`) |
| web checks | `make web-check` (= `web-lint` + `web-test` + `web-build-studio`) |
| web storybook | `make web-storybook-test` |
| web a11y (S-109) | `make a11y` |
| e2e (S-117) | `make e2e` (starts its own disposable Postgres in Docker) · `make e2e-target ENV=staging` |
| infra validate / tflint | `make tf-validate CLOUD=…` / `make tf-lint` |
| chart / gitops validate | `make helm-validate` / `make argocd-validate` |
| event schemas | `make server-events BASE=origin/main REQUIRE_BASE=1` |
| openapi | `make openapi-lint` / `make openapi-check` |
| java images | `make images-java` (`PUSH=1 REGISTRY=… IMAGE_TAG=…`) |

GitLab jobs whose image lacks make install it first (`apt-get install make` / `apk add make`). The web image build
(buildx with the GitHub Actions cache) and the promotion job keep their own steps. The underlying commands:
```
# server (Docker running; Testcontainers starts its own PostGIS)
cd server && ./gradlew build --init-script ../ci/gradle/maven-mirror.init.gradle.kts --max-workers=2

# web
cd web && pnpm install --frozen-lockfile
pnpm lint:colors && pnpm -r typecheck && pnpm -r test
pnpm --filter @northline/ui build-storybook
pnpm --filter @northline/ui exec playwright install chromium   # once
pnpm --filter @northline/ui test-storybook
pnpm --filter @northline/studio build

# infra (Terraform ≥ 1.9, tflint; no cloud credentials needed)
mkdir -p ~/.terraform.d/plugin-cache && cd infra/terraform && TF_PLUGIN_CACHE_DIR=~/.terraform.d/plugin-cache scripts/validate.sh
tflint --init --config "$PWD/.tflint.hcl" && tflint --recursive --config "$PWD/.tflint.hcl"

# end-to-end suite: its own disposable Postgres (Docker), the whole stack under `local`, Playwright (docs/runbooks/e2e.md)
ci/e2e.sh run                      # results in e2e-out/ (report/index.html, traces, videos, logs)
```

## The colour lint
`web/scripts/check-hex-colors.mjs` fails on hex colour literals (`#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`) in
`web/packages/ui/src` and `web/apps/*/src` (`.tsx`, `.ts`, `.css`; tests and test fixtures are skipped). Colours come
from `packages/tokens/tokens.json` as `var(--color-*)`. Genuine exceptions — third-party brand artwork such as the
Google logo, or merchant data such as brand-colour swatches — go in `web/scripts/hex-colors.allowlist` with a reason.
`packages/tokens`, `public/` assets (favicon) and `.storybook/` are not scanned.

## When a job fails
- **infra `terraform fmt`:** run `terraform fmt -recursive infra/terraform` and commit.
- **infra contract:** an implementation of a capability gained or lost a variable/output; keep `aws`, `gcp` and `azure` in step (`infra/terraform/README.md` § Module contract).
- **infra `terraform test`:** the mocked plan names the resource and expression; a failure there would also fail a real `plan`.
- **Maven Central 429 / timeouts:** set `MAVEN_MIRROR_URL` and re-run.
- **Testcontainers cannot find Docker (GitLab):** the runner is not privileged, or the `docker:dind` service did not start; check the service log in the job.
- **Storybook a11y failure:** the message names the story, the element and the axe rule; fix the component (tokens, roles), not the test. "Click to debug" links point at a local Storybook (`pnpm storybook`).
- **Smoke sweep — known findings (29 Sep 2026), so the job currently ends red with 6 problems out of 135:**
  `kitchen/menu` ×3 — the dev seed (`db/seed-dev/V108__kitchen.sql`) points menu items at `seed/*.jpg` photos that
  exist in no media store, so `GET …/menu-items/{id}/photo` answers 404 on a fresh database; `settings?tab=security`
  ×3 — the tab calls northline-auth (`/api/auth/security`), which answers 401 because dev auth (`X-Dev-User`) gives
  no auth-server session. Both are app/seed gaps, not CI faults.
- **Smoke sweep:** each problem line is JSON with the screen, width, locale and the console/HTTP errors; `smoke-out/api.log`, `auth.log` and `studio.log` hold the server logs and `smoke-out/shots/` the desktop-English screenshots.
