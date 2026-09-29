# Runbook — CI (GitHub Actions and GitLab CI)

Stories S-4 (server) and S-5 (web). The same checks are defined for **GitHub Actions** and **GitLab CI**; use
whichever has credits. Nothing is tied to one cloud: the jobs need only a Linux runner with Docker (GitHub) or a
Docker executor that allows `docker:dind` (GitLab), plus public images and package registries.

> **Manual only.** CI credits are depleted, so no pipeline starts on push, pull/merge request or schedule.
> Someone has to start it by hand (below). [Enabling automatic runs](#enabling-automatic-runs) is a one-line change.

## What runs

| Part | GitHub (`.github/workflows/`) | GitLab (`.gitlab-ci.yml` + `ci/gitlab/`) | What it does |
|---|---|---|---|
| server | `server.yml` › `gradle build` | `server:build` | JDK 25 (Temurin). `./gradlew build` in `server/`: Spotless check, Checkstyle, Error Prone + NullAway, every test (Testcontainers PostGIS, `ModularityTests`, ArchUnit). Gradle cache, ≤ 2 workers. JUnit XML → GitHub check "server tests" / GitLab test report; HTML reports as artifacts. |
| web | `web.yml` › `checks` | `web:checks` | Node 22 + pnpm 10 (corepack). No hex colours in components (`pnpm lint:colors`), `pnpm -r typecheck`, `pnpm -r test` (vitest), `pnpm --filter @northline/studio build` (artifact `studio-dist`). |
| web | `web.yml` › `storybook` | `web:storybook` | `build-storybook` (artifact `storybook-static`) and `test-storybook`: every story in headless Chromium with its play function (interaction tests) and the a11y addon (`a11y.test: 'error'` — any violation fails). |
| web | `web.yml` › `studio-smoke` (optional) | `web:studio-smoke` (optional) | `ci/studio-smoke.sh`: PostGIS service → `:api:flywayMigrate -Pdb.devSeed=true` + `:api:seedCategories` → api and auth with the `local` profile → studio dev server (dev auth as Ravi Sandhu) → `scripts/studio-smoke.mjs` (135 screen/width/locale checks). Screenshots and logs in the `studio-smoke` artifact. |

Expected durations (hosted runners; first run in brackets, before caches are warm):

| Job | Duration |
|---|---|
| server build | 6–9 min (10–12 min) — the tests take about 4 min on 2 workers |
| web checks | 2–3 min (3–4 min) |
| web storybook | 3–4 min (4–5 min, incl. the Chromium download) |
| web studio smoke | 8–10 min (12–15 min) — two Spring Boot apps, the studio dev server and 135 page loads (the sweep alone is about 3 min) |

## Running a pipeline by hand

### GitHub Actions
- **UI:** repository › *Actions* › pick **server** or **web** › *Run workflow* › choose the branch and inputs › *Run workflow*.
- **CLI:** `gh workflow run server.yml --ref <branch> [-f project=api] [-f skip-tests=true] [-f rerun-tasks=true]`
  or `gh workflow run web.yml --ref <branch> [-f storybook=false] [-f studio-smoke=true]`; follow with `gh run watch`.

| Workflow | Input | Default | Meaning |
|---|---|---|---|
| server | `project` | `all` | `all`, `api`, `auth`, `bff` or `worker` (`./gradlew :<project>:build`) |
| server | `skip-tests` | `false` | static checks and compilation only (`-x test`) |
| server | `rerun-tasks` | `false` | ignore the Gradle build cache (`--rerun-tasks`) |
| web | `storybook` | `true` | run the Storybook job |
| web | `studio-smoke` | `false` | run the studio smoke sweep job |

A new run on the same branch cancels the previous one of the same workflow (concurrency group per workflow and ref).

### GitLab CI
- **UI:** project › *Build › Pipelines* › *Run pipeline* › choose the branch, adjust the variables › *Run pipeline*.
- **API:** `curl --request POST --header "PRIVATE-TOKEN: <token>" "https://gitlab.com/api/v4/projects/<id>/pipeline?ref=<branch>&variables[][key]=PIPELINE_PART&variables[][value]=server"`,
  or `glab ci run --branch <branch> --variables PIPELINE_PART:web,RUN_STUDIO_SMOKE:true`.

| Variable | Default | Meaning |
|---|---|---|
| `PIPELINE_PART` | `all` | `all`, `server` or `web` |
| `SERVER_GRADLE_ARGS` | `build` | Gradle arguments for server/, e.g. `:api:build`, `build -x test`, `build --rerun-tasks` |
| `RUN_STUDIO_SMOKE` | `false` | `true` adds the studio smoke sweep |

Runner requirements: `server:build` needs a runner with `privileged = true` for the `docker:dind` service that
Testcontainers talks to (`DOCKER_HOST=tcp://docker:2375`, `TESTCONTAINERS_HOST_OVERRIDE=docker`, Ryuk disabled).
GitLab.com hosted Linux runners qualify; on a self-managed runner set `privileged = true` in its `config.toml`.
Every job is `interruptible`, so a newer pipeline on the same branch cancels the older one.

## Variables and secrets
**None are required** — every job builds from public images and registries.

| Name | Where | Purpose |
|---|---|---|
| `MAVEN_MIRROR_URL` | GitHub: *Settings › Secrets and variables › Actions › Variables*; GitLab: *Settings › CI/CD › Variables* | Optional. Maven repository that Gradle tries before Maven Central and the Plugin Portal (Maven Central answers bursts with HTTP 429). E.g. `https://maven-central.storage-download.googleapis.com/maven2/`, or an Artifactory/Nexus/GitLab package proxy. Applied by `ci/gradle/maven-mirror.init.gradle.kts`, which does nothing when the variable is empty. |
| `MAVEN_MIRROR_USERNAME`, `MAVEN_MIRROR_PASSWORD` | GitHub: *Secrets*; GitLab: masked variables | Optional, only for a mirror that needs credentials. |

## Enabling automatic runs
**GitHub** — in `.github/workflows/server.yml` and `web.yml`, add triggers next to `workflow_dispatch` (inputs then
use their defaults, e.g. no smoke sweep):
```yaml
on:
  push: { branches: [main] }
  pull_request:
  workflow_dispatch:
    …
```
Add `paths: [server/**, ci/**, .github/workflows/server.yml]` (or `web/**, scripts/**, …`) to each trigger to build only what changed.

**GitLab** — in `.gitlab-ci.yml`, add one line under `workflow: rules:` above `- when: never`:
```yaml
    - if: $CI_PIPELINE_SOURCE == "push" || $CI_PIPELINE_SOURCE == "merge_request_event"
```
To limit jobs to what changed, add `changes: [server/**/*, ci/**/*]` (or the web paths) to the job rules in `ci/gitlab/*.yml`.

## Running the same checks locally
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

# studio smoke sweep against a DISPOSABLE database (it migrates and seeds it)
docker run -d --name smoke-pg -p 55432:5432 -e POSTGRES_DB=northline -e POSTGRES_USER=northline -e POSTGRES_PASSWORD=northline postgis/postgis:17-3.5
DB_PORT=55432 API_PORT=8190 AUTH_PORT=9190 STUDIO_PORT=3190 ci/studio-smoke.sh    # results in smoke-out/
```

## The colour lint
`web/scripts/check-hex-colors.mjs` fails on hex colour literals (`#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`) in
`web/packages/ui/src` and `web/apps/*/src` (`.tsx`, `.ts`, `.css`; tests and test fixtures are skipped). Colours come
from `packages/tokens/tokens.json` as `var(--color-*)`. Genuine exceptions — third-party brand artwork such as the
Google logo, or merchant data such as brand-colour swatches — go in `web/scripts/hex-colors.allowlist` with a reason.
`packages/tokens`, `public/` assets (favicon) and `.storybook/` are not scanned.

## When a job fails
- **Maven Central 429 / timeouts:** set `MAVEN_MIRROR_URL` and re-run.
- **Testcontainers cannot find Docker (GitLab):** the runner is not privileged, or the `docker:dind` service did not start; check the service log in the job.
- **Storybook a11y failure:** the message names the story, the element and the axe rule; fix the component (tokens, roles), not the test. "Click to debug" links point at a local Storybook (`pnpm storybook`).
- **Smoke sweep — known findings (29 Sep 2026), so the job currently ends red with 6 problems out of 135:**
  `kitchen/menu` ×3 — the dev seed (`db/seed-dev/V108__kitchen.sql`) points menu items at `seed/*.jpg` photos that
  exist in no media store, so `GET …/menu-items/{id}/photo` answers 404 on a fresh database; `settings?tab=security`
  ×3 — the tab calls northline-auth (`/api/auth/security`), which answers 401 because dev auth (`X-Dev-User`) gives
  no auth-server session. Both are app/seed gaps, not CI faults.
- **Smoke sweep:** each problem line is JSON with the screen, width, locale and the console/HTTP errors; `smoke-out/api.log`, `auth.log` and `studio.log` hold the server logs and `smoke-out/shots/` the desktop-English screenshots.
