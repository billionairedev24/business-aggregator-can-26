# S-104 — Internal security review: findings

Reviewed 2026-10-02 against `main` at `e96abf9` (after S-115), before the external penetration test (S-104; scope and
rules of engagement: [pentest-scope.md](pentest-scope.md)). Method: automated scans (§ Scanners), then a manual review
against OWASP ASVS 4.0.3 Level 2 and the OWASP API Security Top 10 (2023), in the order the story set. It builds on the
S-20 review of the sign-in flow and the BFF ([s-20-auth-review.md](s-20-auth-review.md)); what S-20 settled is not
repeated unless it changed. How to run the scans again: [README.md](README.md).

**Result: no open critical or high finding.** Two high findings and a high-rated set of dependency advisories were found
and fixed; every fix has a regression test. Severity is CVSS 3.1 where a score means something, otherwise qualitative.

This file is internal: the documentation site renders `docs/` only in its IP-restricted internal variant. Open items are
described by impact and location, without exploit detail.

## Summary

| id | severity | area | finding | status |
|---|---|---|---|---|
| S104-01 | High (7.5) | api, auth, bff, worker | Request bodies had no size limit: one anonymous request could exhaust a pod's memory | fixed |
| S104-02 | High (8.0, worst case) | email (api) | Text typed by a console reviewer reached a template's pre-processed expression | fixed |
| S104-03 | High (advisories up to 9.8) | server dependencies | Tomcat, Jackson 2/3 and lz4-java versions with published advisories | fixed |
| S104-04 | Medium (6.5) | uploads (catalogue, kitchen) | Image "decompression bomb": a small file could make the api allocate gigabytes | fixed |
| S104-05 | Medium (5.8) | auth (MCP client metadata) | The fetch of Client ID Metadata Documents had a weaker SSRF guard than the platform's | fixed |
| S104-06 | Medium (5.3) | privacy (S-105) | Verification texts were unlimited across reopened requests, and each code added guesses | fixed |
| S104-07 | Medium (5.3) | auth (SMS) | No platform-wide budget for texted codes (SMS pumping across many numbers and addresses) | fixed |
| S104-08 | Medium (5.4) | exports (api, Studio) | Spreadsheet formula injection in the sales CSV; the Studio's guard let `-<digit>…` formulas through | fixed |
| S104-09 | Medium (4.7) | consumer web | No Content-Security-Policy on the consumer site | fixed (follow-up: nonces) |
| S104-10 | Low | mobile (deep links) | A malformed escape in a notification's link threw out of the tap handler | fixed |
| S104-11 | Low | web/mobile tooling | Advisories in build-time-only packages (Docusaurus, Expo CLI, Vitest) | open, accepted |
| S104-12 | Low | infra (staging) | The staging cluster API accepts any source address unless `api_allowed_cidrs` is set | open, action before the test |
| S104-13 | Low | uploads (booking) | Job photos are checked by declared type only, not by content | open |
| S104-14 | Info | api | Object-level authorization harness over every documented operation: no finding | — |
| S104-15 | Info | scanners | False positives and hardening advice triaged (§ Scanners) | — |

## Fixed

### S104-01 — Unbounded request bodies (High, CVSS 7.5 `AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H`)

**Where:** every servlet app. Spring reads a `@RequestBody` whole (the Stripe webhook's signed payload, a guest's cart
JSON, the commerce webhooks' raw bytes); Tomcat caps only form posts and the edge (Envoy Gateway) had no body limit.
Several endpoints are unauthenticated by design (webhooks, the guest cart, storefront visits).
**Impact:** memory exhaustion of api/auth/bff pods by an anonymous client.
**Fix:** `ca.northline.platform.web.RequestSizeLimitFilter`, auto-configured in every servlet app. A declared
`Content-Length` over the cap is answered `413 payload_too_large` before anything reads it; a chunked body is counted
while it is read and fails at the cap. Caps: `northline.http.max-request-body` (5 MB) and
`northline.http.max-multipart-body` for uploads — multipart or a raw file body (26 MB, above the api's 25 MB multipart limit, which still applies inside it).
**Tests:** `RequestSizeLimitFilterTest` (declared length, chunked, multipart and raw-file uploads), `RequestSizeLimitApiTest` (Stripe webhook
and guest cart answer 413). DoS testing stays out of the external test's scope.

### S104-02 — Expression injection into email templates (High, CVSS 8.0 worst case `AV:N/AC:H/PR:H/UI:N/S:C/C:H/I:H/A:H`)

**Where:** `server/email` templates turn codes into message keys with Thymeleaf pre-processing
(`#{__${'listing-rejected.why.' + r}__}`); pre-processing pastes the value into the expression that is then evaluated.
The rejection reasons of a **dish** in the console's vetting queue (S-92) were free text (a listing's were already
checked against the list), and they reach the business owner's "listing rejected" email.
**Impact:** a console account with the trust & safety role and a second factor could make the api evaluate an
expression. Rated on the worst case; Thymeleaf 3.1 restricts expression features and exploitation was not attempted,
but the guard must not depend on that.
**Fix:** two layers. The console refuses dish reasons outside `ListingVetting.REASONS` (422 "Pick reasons from the
list."). `EmailTemplates` refuses to render when a variable used as a key fragment (`decision`, `phase`, `change`,
`kind`, `outcome`, `role`, `action`, `rule`, the items of `reasons` and `checks`) is not a plain code.
**Tests:** `TemplateKeyInjectionTest` (hostile reason and kind refused; every pre-processed variable of every template is
one the guard checks, so a new template is covered), `ListingVettingApiTest.aDishRejectedWithAReasonNotOnTheList_isRefused`.

### S104-03 — Server dependencies with published advisories (High; advisories rated up to 9.8)

Found by osv-scanner over the apps' CycloneDX SBOMs (new `cyclonedxDirectBom` task, `make security-sbom`):

| package | was | now | advisories | reachable here? |
|---|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | 11.0.26 | CVE-2026-65905, CVE-2026-65182, CVE-2026-68525 | no: Tomcat's own DIGEST/FORM authenticators and access control, unused (Spring Security authenticates); upgraded anyway |
| `tools.jackson.core:jackson-core`, `-databind` (Jackson 3) | 3.1.5 | 3.1.7 | CVE-2026-89425, -89407, -91777, -91776, -68497, -83557, -19032 | partly: the denial-of-service ones apply to any JSON body |
| `com.fasterxml.jackson.core:*` (Jackson 2, through libraries) | 2.21.5 | 2.21.7 | the same set | as above |
| `at.yawk.lz4:lz4-java` (Kafka clients) | 1.10.1 | 1.11.4 | CVE-2026-59949 | no: needs invalid ranges from the caller |

**Fix:** Spring Boot BOM properties overridden in `server/build.gradle.kts` (versions in `libs.versions.toml`, to drop once
the Boot BOM catches up). **Check:** the rescan reports no known vulnerability; the full server build passes.

### S104-04 — Image decompression bomb (Medium, CVSS 6.5 `AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:H`)

**Where:** `ImageIoInspector` (catalogue media uploads, bulk-import image URLs, commerce sync) and the kitchen's photo
size check called `ImageIO.read`, which allocates the whole raster a PNG or JPEG header declares.
**Impact:** any business member, or any image URL in a bulk import, could crash an api pod with a small file.
**Fix:** `ca.northline.shared.storage.ImageDecoding`: dimensions from the header only; images over 100 megapixels are
refused unread; analysis (white border, perceptual hash) decodes a subsampled raster of about 2 megapixels at most. The
kitchen's check reads only the header now.
**Tests:** `ImageDecodingTest` (a sub-kilobyte 60 000 × 60 000 PNG refused; exact size and subsampled analysis of a real
image), `MenuApiTest` (the same file uploaded as a dish photo answers 422).

### S104-05 — SSRF guard of the MCP client metadata fetch (Medium, CVSS 5.8 `AV:N/AC:H/PR:N/UI:N/S:C/C:L/I:N/A:L`)

**Where:** northline-auth fetches an MCP client's metadata document when an authorization request names an HTTPS
`client_id` (S-127). Its own check missed carrier-grade NAT and IPv4 inside IPv6, resolved the name a second time to
connect (DNS rebinding), and read the whole response before applying the size cap.
**Impact:** anonymous, blind GET requests from auth towards internal addresses.
**Fix:** the fetch now goes through the platform's egress policy (`EgressPolicy` + `EgressDnsResolver`, as partner
webhooks and import images do): every resolved address checked, the connection pinned to the checked addresses, no
redirects, the size cap applied while reading.
**Tests:** `ClientIdMetadataDocumentsTest` (metadata, private, CGNAT, IPv4-mapped/NAT64/6to4 and IPv6 metadata addresses
refused after a single lookup; IP literals refused before any request; an oversized document refused; the fetch connects
to the checked address).

### S104-06 — Privacy verification texts unlimited across requests (Medium, CVSS 5.3)

**Where:** S-105 self-service requests text a 6-digit code: one open request per type, a 1-minute resend cool-down and 5
wrong tries per code — but withdrawing and reopening sent a new text every time, and each new code reset the tries.
**Impact:** SMS cost at an attacker's pace, and many more guesses for someone holding a stolen session (an access request
ends in a data export).
**Fix:** texts per person across all of their requests: 3 an hour, 6 a day (`privacy.verification_texts`, **V305**).
Over the budget a request still opens and waits for a step-up proof; "send a new code" answers `409 too_many_codes`
(en, fr-CA).
**Test:** `PrivacyRequestsApiTest.Erasure.reopeningAgainAndAgain_stopsTextingAfterTheHourlyBudget`.

### S104-07 — No platform-wide budget for texted sign-in codes (Medium, CVSS 5.3)

**Where:** auth's `otp-send` limits are per account (5/h), IP (20/h) and session (5/h). SMS pumping spreads requests over
many numbers and addresses and stays under each; numbers are validated as NANP, which includes non-Canadian, costlier
destinations.
**Fix:** a new limit scope `PLATFORM` (everyone together) for `otp-send`: `OTP_SEND_PLATFORM_PER_HOUR` (default 1000),
15-minute lockout logged as an error to alert on. Residual: destination restrictions belong in the SMS provider's
geo-permissions (Canada only) — go-live checklist below.
**Test:** `PlatformSmsBudgetTest`.

### S104-08 — Spreadsheet formula injection in CSV exports (Medium, CVSS 5.4 `AV:N/AC:L/PR:L/UI:R/S:C/C:L/I:L/A:N`)

**Where:** the Studio's sales export (`/reports/export.csv`) wrote customer names and listing titles as typed; the
Studio's own client-side exports (`DataTable/report.ts`) guarded formulas but let a minus followed by a digit through.
**Fix:** both prefix a quote mark to a cell starting with `=`, `+`, `@`, tab, CR, or `-` unless the whole cell is a
negative amount. **Tests:** `SalesCsvTest`, `report.test.ts`.

### S104-09 — No Content-Security-Policy on the consumer site (Medium, CVSS 4.7)

**Where:** the consumer SSR server set nosniff, frame, referrer and permissions headers but no CSP (the Studio and the
console have one from nginx). The site renders merchant-written content (storefronts, custom domains).
**Fix:** `web/apps/consumer/server/security-headers.mjs`: `default-src 'self'`; scripts from the site and Stripe; frames
from Stripe only; `connect-src` and `form-action` limited to the site and the auth origin; `frame-ancestors 'none'`,
`object-src 'none'`, `base-uri 'self'`; COOP `same-origin-allow-popups`. Merged with S-110 (PCI SAQ A): the
third-party script origins come from `SCRIPT_INVENTORY`, the payment-page script inventory (Stripe.js only;
docs/compliance/pci/payment-page-scripts.md), and the policy reports violations (`report-uri /csp-report`,
`report-to csp`, `Reporting-Endpoints`) to the Node server, which logs one `csp.violation` JSON line each (no query
strings, ≤ 300 a minute, bodies ≤ 16 KB). One enforced policy; no separate report-only one. **Residual:**
`script-src` keeps `'unsafe-inline'` because the SSR document's inline configuration and hydration scripts carry no
nonce yet (follow-up; a report-only copy without `'unsafe-inline'` should measure the nonce work first). The same
residual keeps SAQ A eligibility criterion E7 "not yet" (docs/compliance/pci/saq-a.md).
**Test:** `securityHeaders.test.ts` (policy, inventory vs the code's script loads, report parsing, rate cap).

### S104-10 — Malformed deep link threw in the tap handler (Low)

`parseDeepLink` decoded path segments with `decodeURIComponent`, which throws on a malformed escape, and the
notification tap handler had no catch. Such a link is now ignored like any foreign one (`push.test.ts`).

## Open

| id | severity | what | plan |
|---|---|---|---|
| S104-11 | Low | Advisories in packages used only to build or test: `serialize-javascript`, `undici`, `uuid` (Docusaurus, `web/apps/docs`); `node-forge`, `uuid`, `decode-uri-component` (Expo CLI, `mobile/`); `vitest`, `@vitest/mocker`, `@ai-sdk/provider-utils` (dev). None runs in a browser, an app or a server. | upgrade with the next Docusaurus, Expo SDK and Vitest bumps; `make security-deps` lists them |
| S104-12 | Low | Staging's Kubernetes API endpoint accepts any source address when `api_allowed_cidrs` is empty (prod refuses an empty list; authentication is still required). | set `api_allowed_cidrs` for staging before the external test ([pentest-scope.md § Before the test](pentest-scope.md#before-the-test)) |
| S104-13 | Low | Job photos are accepted by declared type; the other uploads also check magic bytes. Served back with the declared type and `nosniff`, so nothing executes. | reuse `MessageAttachmentService.signatureMatches` |

Accepted risks carried over from S-20, unchanged: BFF refresh tokens are not reuse-detected (they never leave the
server); session cookies are `SameSite=Lax`; the BFF trusts forwarded headers from private addresses; backup codes are
unsalted SHA-256; edge access logs hold single-use OAuth codes.

## Checked, no finding

### S104-14 — Object-level and function-level authorization (API1, API5)

`ObjectLevelAuthorizationTest` reads the committed OpenAPI documents and probes **every operation** (482 dynamic tests
when written), in the shared test context (no new Spring context):

- every Studio and partner operation with `{merchantId}`, called by the owner of another business and by a partner
  bound to another business: 403, before the body is read;
- every Studio operation that names an object (`{orderId}`, `{listingId}` … 36 kinds) under the caller's own, empty
  business with the id of an object someone else owns (fixtures create the main kinds; any existing row is someone
  else's): never 2xx, never 5xx — every probe answered 403 or 404, or 422 where the body was rejected first;
- every signed-in consumer operation that names an object (`/me/…`, cart items, quotes, privacy requests), called by a
  brand-new customer with another person's ids: never 2xx, never 5xx;
- every console operation: 403 for a customer, a business owner, staff without a second factor, and staff without a
  console role unless the screen is open to all staff.

One operation serves another business's object by design — `GET /merchants/{id}/media/{mediaId}` for an approved catalogue image, which is public anyway (S-123); the harness accepts a 2xx there only when the public media path serves the same id. Request bodies are synthesised from the documented schemas so writes reach the services' own checks. Eleven writes
still stop at validation (enums missing from the spec, multipart fields); their ownership is covered by their own tests.
A new operation is probed as soon as `make openapi` regenerates the specs.

### Other areas

| area | result |
|---|---|
| Mass assignment (API3) | Request DTOs are records with explicit fields; no endpoint binds a map or an entity; ids, roles, tiers, prices, vetting and states the server owns never come from the body. |
| SQL injection | No jOOQ; every `JdbcClient` query binds its parameters; concatenated fragments are constants (column lists, state sets) or fixed strings with bound values (`AuditLogQueryJdbc`). |
| Elasticsearch | Queries are JSON trees (no `query_string`, no scripts); sizes bounded. |
| Template injection | Only the email templates evaluate expressions — S104-02. User text is escaped in HTML and interpolated as data elsewhere. |
| Log injection, PII in logs | Structured JSON logs in the cloud (newlines escaped) with the S-112 redaction; no tokens or codes logged (S-20). |
| SSRF | Partner webhooks and import images: `EgressPolicy` after DNS resolution, pinned connections, no redirects (S-33, S-72); the Shopify shop must match `*.myshopify.com`; POS and commerce base URLs are configuration; auth's metadata fetch now the same — S104-05. |
| Inbound webhooks | Stripe: signature with a 5-minute tolerance, deduplicated on the event id (S-12); commerce: HMAC, deduplication, per-IP rate limit, 1 MB; calendar: per-channel secrets compared in constant time. |
| Uploads | Allow-listed types, magic bytes (messages, case uploads, listing documents), size limits, server-generated object keys (no client path), `nosniff` and `Content-Disposition` on downloads, presigned URL lifetime capped (`GuardedObjectStore`). Decompression — S104-04; job photos — S104-13. |
| Auth flows | S-20/S-29 controls re-checked: PKCE S256 only; exact redirect URIs; DPoP with server nonces and single-use `jti` at auth, `jti` + `ath` at the api; refresh rotation with reuse detection for public clients; session id rotated at sign-in and step-up; `__Host-` cookies; header-only CSRF token on the BFFs; CORS allow-list on auth only. Open redirects: `next` (BFFs, Studio, consumer) local paths without control characters; `continueTo` only to auth's `/oauth2/authorize`; the mobile `stripe-redirect` hands the URL to Stripe's SDK and navigates in-app only; deep links accept known hosts and id patterns only. |
| Abuse limits | Sign-in, OTP, TOTP, backup codes, passkeys, step-up and Security changes: S-9/S-20 limits, fail closed in staging/prod. Privacy codes — S104-06; SMS pumping — S104-07. Enumeration as S-20 (registration's "already in use" is a design requirement, rate limited). |
| AI and MCP | Assistant tools get the authorised `merchantId` from the request, never from the model; writes are proposed and confirmed by the person; MCP tools are an allow-list of Studio operations run as the caller, with audience, second-factor and scope checks and two-step confirmation of writes; MCP tokens are refused on `/api/**`; the docs MCP is staff-only in the cloud. Prompt injection can at most trigger a call the caller could make. |
| Containers, Kubernetes | Distroless Java images (`nonroot`), nginx as uid 101; `runAsNonRoot`, read-only root file system, every capability dropped, seccomp `RuntimeDefault`; ingress NetworkPolicy per app (only Envoy Gateway reaches the public apps in staging/prod); egress open by design (managed stores outside the cluster), SSRF handled in the apps. |
| Secrets in git history | gitleaks over 635 commits: test fixtures and documented local-only keys only (allow-listed with reasons in `.gitleaks.toml`); local keys are refused outside `local`/`test`. |
| Dependency confusion | Workspace packages are `@northline/*` with `workspace:` references and no private registry is configured, so no private name resolves from the public registry. Reserving the `@northline` npm scope is on the go-live checklist. |

## Scanners

| tool | target | result |
|---|---|---|
| gitleaks 8.21.2 | git history (635 commits), working tree | 33 raw hits, all test fixtures or documented local keys → allow-list; 0 after |
| osv-scanner 1.9.2, offline DB | CycloneDX SBOMs of api, auth, bff, worker (1 389 components) | 18 advisories in 5 packages → S104-03; 0 after the upgrades |
| osv-scanner 1.9.2, offline DB | `web/pnpm-lock.yaml` (1 794 packages), `mobile/pnpm-lock.yaml` (931) | 17 + 3 advisories, all in build-time or dev packages → S104-11 |
| semgrep 1.179.0 with rules from github.com/semgrep/semgrep-rules (the registry is unreachable here) | server, web, mobile, deploy: 614 rules, 3 310 files | 6 102 results: 5 746 i18n-key style, ~300 informational; the security-relevant ones were checked by hand — the CSV/XLSX/HTML builders escape (`report.ts`, `seo.mjs`), auth's request repository deserialises behind an allow-list filter, the Kubernetes API client trusts only the cluster CA, the `ProcessBuilder` in the build-time event-contracts tool runs a fixed `git` command. No finding. |
| checkov 3.3.20 | `infra/terraform` | 508 passed, 140 failed: hardening advice (key rotation periods, HSM keys, access logs, customer-managed keys for buckets, private endpoints, flow logs) for the infra backlog; false positives: Cloud SQL TLS (`ssl_mode = ENCRYPTED_ONLY`), GKE network policy (Dataplane V2 enforces it), GKE metadata server (set on the node pool); public cluster API endpoints → S104-12 |
| checkov 3.3.20 | rendered prod chart | 1 271 passed, 113 failed: default namespace in rendered templates, CPU limits, image digests (set by the GitOps promotion), secrets as environment variables (External Secrets design), Jobs without a NetworkPolicy (nothing listens) — accepted |
| kube-score 1.19.0 | rendered prod chart | 80 critical items of the same kinds, plus nginx's uid 101 and identical readiness/liveness probes — accepted |
| OWASP ZAP API scan (`zaproxy/zap-stable`) | api under `local`: `api-public.yaml` (144 URLs) and `api-studio.yaml` (238 URLs, dev auth as a business owner) | public: 112 rules passed, 1 warning — an SQL-injection heuristic on `/geo/autocomplete`, which answered the same 422 for every probe (false positive); Studio: 113 rules passed, no warning (active scan with SQL, template, command and path injection, XSS and SSRF rules) |
| `scripts/security/negative-tests.sh` | same api | 23 of 23 refused as expected |
| not run | CodeQL, OWASP dependency-check, trufflehog, tfsec, kubesec | no CodeQL CLI here and no NVD feed offline (osv-scanner covers dependencies); gitleaks, checkov and kube-score cover the rest; semgrep's registry (`p/java` …) is blocked here, the same rules ran from the rules repository |

## Go-live checklist

- Set `api_allowed_cidrs` for the staging and prod clusters (S104-12).
- SMS provider: geo-permissions limited to Canada; alert on "platform budget reached" (S104-07).
- Reserve the `@northline` scope on npm (dependency confusion).
- Run `make security-scan` (or the manual `security` CI job) before each release and triage new advisories here.
