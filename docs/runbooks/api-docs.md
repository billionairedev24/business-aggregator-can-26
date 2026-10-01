# API documentation — OpenAPI 3.1, Swagger UI, Scalar, Redoc (S-125)

Every HTTP service describes itself as OpenAPI 3.1, generated from the code by springdoc, one document per audience.
Each document opens in three viewers: **Swagger UI**, **Scalar** and **Redoc**. The generated documents are committed
under [`docs/api/openapi/`](../api/openapi), and a test fails the build when the code and the committed file differ.
In production no service serves a spec or a viewer. The public, partner and webhook references are published on the
documentation site (S-126).

## Documents

| File | Service · group | Audience | Authentication |
|---|---|---|---|
| `api-public.yaml` | api · `public` | consumer web and mobile apps | none for public reads; consumer-bff session + CSRF, OAuth token, or DPoP (mobile) |
| `api-studio.yaml` | api · `studio` | the business Studio | studio-bff session + CSRF, or OAuth `merchant` scope; `acr=mfa` |
| `api-partner.yaml` | api · `partner` | partner integrations (S-30): exactly the `@PartnerAccess` handlers | client credentials with `private_key_jwt`, scope from the annotation |
| `api-console.yaml` | api · `console` | platform staff | session or token, role STAFF, `acr=mfa` |
| `api-webhooks.yaml` | api · `webhooks` | partners receiving S-33 deliveries | `Northline-Signature` (HMAC), checked by the receiver |
| `api-internal.yaml` | api · `internal` | nobody outside: provider callbacks, email links, local dev tools | provider signatures / single-use state |
| `auth-public.yaml` | auth · `public` | every OAuth client: discovery, JWKS, authorize, token, revoke, userinfo | PKCE, DPoP, `private_key_jwt` |
| `auth-internal.yaml` | auth · `internal` | the first-party sign-in pages (`/api/auth/**`) | auth session cookie, Origin allow-list |
| `bff-internal.yaml` | studio-bff · `internal` | the Studio (`/bff/**`) | studio-bff session + CSRF |
| `bff-consumer-internal.yaml` | consumer-bff · `internal` | the consumer web (`/bff/**`) | consumer-bff session (guests too) + CSRF |

The worker has no HTTP API. It serves only `/actuator/health` and `/actuator/prometheus` on 8084, so it has no
document.

`OpenApiSpecsTest` (api) fails when an `/api/**` path is in none of the api's groups, so every endpoint is
documented somewhere.

**Conventions every document follows** (`server/openapi`, `ApiDocs` / `ApiConventions`, no annotations needed):

- **Errors.** `422` uses `ValidationErrors` (`{"errors":[{"field","rule","message"}]}`, with the exact messages of
  `docs/spec/validation-rules.md`) on every write with a body. `401`/`403` use RFC 9457 `Problem` with a `code` on
  secured operations. `404` applies wherever a path has parameters.
- **Ids and money.** `*Id` path parameters are `Ulid` (pattern, example). `*Cents` properties are int64 cents of
  CAD (`MoneyCents`).
- **Operation ids** are `<controller><Method>`, e.g. `listingList`. They stay stable when other controllers change.
- **Servers** are fixed: `northline.docs.servers`, from `API_PUBLIC_URL` / `AUTH_ISSUER`, or `/` for the BFFs. They
  never come from the request, so a spec is reproducible.
- **Security schemes:**
  - `bffSession` + `csrf`: an HttpOnly cookie plus `X-XSRF-TOKEN`.
  - `oauth2`: authorization code + PKCE at northline-auth.
  - `dpop` + `dpopProof`: RFC 9449, S-29.
  - `partnerClientCredentials`: client credentials, `x-token-endpoint-auth-methods: [private_key_jwt]`, S-30.
  - `authSession`: northline-auth's own cookie.
  - `webhookSignature`: S-33.

  Each document keeps only the schemes and shared schemas it uses.

## Viewers (local, dev, staging)

| Service | Landing page | Swagger UI | Scalar | Redoc | Spec |
|---|---|---|---|---|---|
| api (:8080, `api.<zone>`) | `/docs` | `/swagger-ui.html` | `/docs/scalar` | `/docs/redoc?group=…` | `/v3/api-docs/<group>`, `/v3/api-docs.yaml/<group>` |
| auth (:9000, `auth.<zone>`) | `/docs` | `/swagger-ui.html` | `/docs/scalar` | `/docs/redoc` | `/v3/api-docs/<group>` |
| studio-bff (:8082, `studio.<zone>`) / consumer-bff (:8081, apex) | `/bff/docs` | `/bff/swagger-ui.html` | `/bff/docs/scalar` | `/bff/docs/redoc` | `/bff/v3/api-docs/internal` |

`make api-docs` prints these. The landing page links every group in all three viewers. Swagger UI opens a group with
`?urls.primaryName=<group>`. Scalar shows all of a service's groups on one page and has a document switcher.

- **Swagger UI** comes from springdoc's UI starter (`springdoc-openapi-starter-webmvc-ui` 3.1.1).
- **Scalar** comes from springdoc's own Scalar starter (`springdoc-openapi-starter-webmvc-scalar` 3.1.1, which pulls
  `com.scalar.maven:scalar-webmvc` 0.5.55). Its bundle is served by the app, so no CDN is used. Configure it under
  `scalar:` in each `application.yml`.
- **Redoc** 2.5.4 is a page of `server/openapi`. Its standalone bundle is downloaded from the npm registry at build
  time and checked against the registry's sha512 `integrity`. It is served by the app as
  `/docs/redoc/redoc.standalone.js`, initialised by `/docs/assets/redoc-init.js` (no inline script), and themed with
  the Northline tokens. A changed tarball fails the build; to upgrade, change `redocVersion` and `redocIntegrity` in
  `server/openapi/build.gradle.kts` (`npm view redoc@<v> dist.integrity`).

**Where they are served and the CSP (S-17 / S-20):**

- **api host.** The edge routes only `/api/v1` on `api.<zone>`. `apps.api.docsRoutes: true` (set in
  `values-dev.yaml` and `values-staging.yaml`) adds `/docs`, `/swagger-ui`, `/swagger-ui.html` and `/v3/api-docs`.
  The chart refuses it in prod (`deploy/helm/validate.sh` checks the refusal).
- **auth host.** `auth.<zone>` routes everything to northline-auth, so its viewers need no route change.
- **BFFs.** The Studio and consumer hosts belong to the web apps except `/api`, `/bff`, `/oauth2` and `/login`, so
  the BFFs serve their documentation under `/bff` and need no route change.
- **CSP.** northline-auth and the BFFs answer with S-20's `default-src 'none'`. Rather than relaxing that, the viewer
  paths get their own filter chain (`DocsSecurityConfiguration`, order 0), with:

  ```
  default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;
  font-src 'self' data:; connect-src 'self' <issuer>; form-action 'self'; frame-ancestors 'none'; base-uri 'self'
  ```

  Swagger UI's and Scalar's own initialisers need `'unsafe-inline'`. `connect-src` lets their "Authorize" / "Try it"
  exchange a code at northline-auth. Every other path keeps `default-src 'none'`; the auth and BFF tests check both.

**Production:** every `application-prod.yml` sets `springdoc.api-docs.enabled=false`,
`springdoc.swagger-ui.enabled=false`, `scalar.enabled=false` and `northline.docs.enabled=false`, so the documents,
viewers, pages and their filter chain don't exist. Each app's test asserts it.

## Regenerate, check, lint

| Command | What it does |
|---|---|
| `make openapi` | boots the api, auth and both BFF profiles in their tests (Docker for Testcontainers), writes `docs/api/openapi/*.yaml`, shows `git status`, then lints. Commit the diff with the code change. |
| `make openapi-check` | the drift check alone; `./gradlew build` runs the same tests, so a forgotten `make openapi` fails the build with "Regenerate them with `make openapi`" |
| `make openapi-lint` | Redocly CLI 2.57.0 (`pnpm dlx`, pinned in `make/docs.mk`) with [`redocly.yaml`](../../redocly.yaml) over every committed spec: `recommended`, `security-defined` as an error, missing 4xx and unused components as warnings |

Manual CI: GitHub `openapi.yml` (lint, check) and GitLab `openapi:lint` (`PIPELINE_PART=openapi` or `all`); the check
also runs in the server build ([ci.md](ci.md)).

## Adding an endpoint

1. Write the controller as usual. The conventions document ids, errors and money. Add `@Operation(summary = …)` and
   `@Parameter` / `@Schema` descriptions where the generated text isn't enough.
2. If its path is outside the patterns of `OpenApiConfig` (api), `OpenApiSpecsTest.everyApiPathIsInAnAudienceDocument`
   fails: add the path to the right audience.
3. `make openapi`, review the YAML diff, commit it.
