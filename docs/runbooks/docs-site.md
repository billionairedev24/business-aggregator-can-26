# Documentation site — Docusaurus on docs.<zone> (S-126)

`web/apps/docs` is a Docusaurus 3 site in the pnpm workspace. It renders the repository's `docs/` where they are
committed, and the API reference from the committed OpenAPI documents (`docs/api/openapi`, S-125). Each reference is
shown in both **Redoc** (via redocusaurus) and **Scalar** (via `@scalar/docusaurus`). The site has local search and
English and French UI, and is themed with the Northline tokens.

## Two variants

| | `public` | `internal` |
|---|---|---|
| Contents | guides (getting started, authentication, webhooks; en + fr) and the references `api-public`, `api-partner`, `api-webhooks`, `auth-public` | the public contents, plus `docs/`: architecture, data model, screens, local development, plans and conventions, backlog README, every runbook, decisions, security review, the AI section; plus every OpenAPI document, internal ones included |
| Image | `docs` | `docs-internal` |
| Host | `urls.docs` = `docs.<zone>`, every environment including prod | `urls.docsInternal` = `internal-docs.<zone>`, only with `apps.docs-internal.enabled` |
| Who can open it | anyone | only `edge.docsInternal.allowedCIDRs`: an Envoy Gateway `SecurityPolicy` answers 403 to everyone else at the edge |

The variant is chosen at build time with `NORTHLINE_DOCS_VARIANT=public|internal` (default `internal` for local
builds; `src/content.ts` decides what each variant gets). The public variant never contains an internal page or
spec, not even as a downloadable file. `scripts/check-build.mjs` fails the build if it does. Search covers only what
is in the build.

The chart refuses `docs-internal` outside `local` without allowed CIDRs or without the Envoy edge
(`deploy/helm/validate.sh` checks the refusal). Search engines are told not to index the internal site.

## Build and run locally

| Command | What it does |
|---|---|
| `make docs` | internal variant, en + fr, then the page check (every runbook and spec page in both languages) |
| `make docs DOCS_VARIANT=public` | what `docs.<zone>` serves |
| `make docs-serve` | serves the last build on http://localhost:3300 (search works here) |
| `make docs-dev` / `make up SERVICES=docs` | live dev server on :3300 (English only, no search index) |
| `make docs-pages` | the public variant for GitHub/GitLab Pages (`DOCS_URL`, `DOCS_BASE_URL=/<project>/`) |
| `pnpm --filter @northline/docs test` | unit tests (content per variant, runtime config) — part of `pnpm -r test` |

How a build works:

1. `scripts/sync.mjs` copies the variant's specs to `static/openapi/`, where Scalar and the download links fetch
   them, and Scalar's standalone bundle (the pinned `@scalar/api-reference` 1.72.3) to `static/scalar/`.
2. Docusaurus reads `docs/` through the docs plugin's `path`. `docs/*.md` is CommonMark (`markdown.format: detect`),
   so existing pages need no MDX escaping.

Links from runbooks into the code (`../../infra/…`) are reported as warnings in the internal variant. They work on
GitHub, not on the site. The public variant fails on any broken link.

**Adding pages:**

- **Repository pages.** A new `.md` under `docs/runbooks/`, `docs/security/` or `docs/ai/` appears automatically.
  Top-level pages need a line in `web/apps/docs/sidebars.ts`.
- **Public guides.** These go in `web/apps/docs/guides/`, with the French page under
  `i18n/fr/docusaurus-plugin-content-docs/current/` (same file name).
- **UI strings.** They go in `i18n/fr/code.json`, `navbar.json` and `docusaurus-plugin-content-docs-repo/current.json`.
- **AI.** `docs/ai/` is the AI section (Spring AI with OpenRouter, built by its own story). Its pages show up under
  **AI** in the internal site.

## The container

`web/Dockerfile` targets `docs` and `docs-internal` build the site from the named build context `repo-docs`, the
repository's `docs/`:

```sh
docker buildx build web --target docs --build-context repo-docs=docs -t "$REGISTRY/docs:$IMAGE_TAG"
make images-web WEB_IMAGES="docs docs-internal"          # or all web images
```

- **Build check.** Both variants are built once and checked by `scripts/check-build.mjs`; no image is produced
  without every page.
- **Runtime.** The image is nginx-unprivileged: uid 101, port 8080, read-only root, `/healthz`, gzip,
  immutable `/assets`.
- **CSP.** It allows only the site itself (`'unsafe-inline'` scripts for Docusaurus' and Scalar's init snippets) and
  `connect-src` to `NL_DOCS_CONNECT`. Redoc's badge from `cdn.redoc.ly` is blocked, and Redoc hides it.

**Runtime settings.** nginx writes these into `/config.js` at start; the chart derives them:

| Variable | Set by the chart to | Meaning |
|---|---|---|
| `NL_DOCS_SWAGGER` | `api\|<urls.api>/docs,auth\|<urls.auth>/docs,studio-bff\|<urls.studio>/bff/docs` where `apps.api.docsRoutes` (dev, staging); empty in prod | links on the API reference page to the services' own Swagger UI / Scalar / Redoc (S-125) |
| `NL_DOCS_CONNECT` | `<urls.api> <urls.auth>` | origins Scalar's "Try it" may call |

## Deploy (Helm, Argo CD)

- **Apps.** The chart's `apps.docs` (enabled) and `apps.docs-internal` (disabled) are static apps like the Studio:
  routes on their hosts, a certificate and DNS record per host through the S-17 edge, the same hardening and
  NetworkPolicy.
- **Hosts.** `urls.docs` / `urls.docsInternal` are set in `values-dev|staging|prod.yaml`; kind leaves both empty and
  disables `docs`.
- **Argo CD.** It deploys `docs` to dev by itself once its digest is promoted. `deploy/argocd/promote.sh` writes the
  `docs` and `docs-internal` digests into `envs/<env>/images.yaml`, and the images are pushed by the manual image
  jobs. The app project whitelists `SecurityPolicy`.
- **Turning on the internal site** in an environment:

  ```yaml
  # deploy/argocd/envs/<env>/values.yaml
  apps:
    docs-internal: { enabled: true }
  edge:
    docsInternal:
      allowedCIDRs: ["203.0.113.0/24"]   # office / VPN egress; behind a CDN also set edge.trustedProxyHops
  ```

- **Registries.** Terraform's registry modules (AWS, Google Cloud, Azure) create the `docs` and `docs-internal`
  repositories with the others.

## Static export (manual CI)

The public variant only:

- **GitHub Pages:** `.github/workflows/docs-pages.yml`. Set Settings › Pages › Source = GitHub Actions first, then
  run it with `base-url` = `/<repository>/`.
- **GitLab Pages:** `PIPELINE_PART=pages` (the `pages` job in `ci/gitlab/docs.yml`; never part of `all`).

The docs build check runs in GitHub `web.yml` (`docs` job) and GitLab `web:docs`.

## Versions (checked 2026-09-30)

| Package | Version |
|---|---|
| `@docusaurus/core`, `preset-classic` | 3.10.2 |
| `redocusaurus` | 2.5.2 (bundles Redoc 2.4) |
| `@scalar/docusaurus` | 0.8.46 |
| `@scalar/api-reference` (standalone) | 1.72.3 |
| `@easyops-cn/docusaurus-search-local` | 0.55.3 |
