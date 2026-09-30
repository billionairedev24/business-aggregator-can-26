# S-20 — Security review: JSON sign-in flow and BFF hand-off

Reviewed 2026-09-30 against `main` at `cd6db12` (after S-9, S-17, S-18, S-19, S-28). Scope: `server/auth` (the JSON
login API: registration with phone code, sign-in, passkey / authenticator / backup-code factors, "Not you?",
Google/Apple federation, session management and step-up, rate limits), `server/bff` (OAuth2 client, `/bff/login?next=`,
session cookie, CSRF, token relay, introspection-based session check, logout), how the Studio calls them, and the
api's `acr=mfa` enforcement. Method: code reading of every endpoint, filter chain and configuration file, then a
regression test for each finding (and for the checks that were already right, so they stay right).

Result per check: **OK** (already right; test named when one now guards it), **fixed** (issue found and fixed in this
story, with its regression test), **accepted risk** (left as is, with the reason). Severity of fixed issues: H / M / L.

## Threat model summary

**Assets.** Sign-in sessions (`__Host-NL_AUTH` on `auth.<zone>`, `__Host-NL_STUDIO` on `studio.<zone>`), OAuth tokens
(only ever inside the BFF's server-side session), second factors (passkeys, TOTP secrets encrypted at rest, backup
code hashes), phone codes, the business accounts behind `acr=mfa` tokens (payouts, customer data), account existence
(emails/mobiles of businesses), and the audit trail.

**Actors.** An anonymous internet attacker (credential stuffing, code guessing, CSRF/clickjacking pages, phishing
origins); an attacker who controls content on a *sibling* subdomain of the same site (`pages.` storefronts with
merchant content, the consumer apex) — same site, so `SameSite=Lax` does not stop them; a network-adjacent workload
inside the cluster (another pod); a thief of a device or authenticator clone; an insider reading logs or a database
backup.

**Trust boundaries and entry points.**

```
browser ──(same site, cross-origin, credentials)──► auth.<zone>  /api/auth/**  (JSON, CORS allow-list, Origin check)
   │                                                   /oauth2/authorize (session → silent code, PKCE)
   │                                                   /login/oauth2/code/{google,apple} (state in DB, V022)
   └──(same origin)──► studio.<zone> ─ nginx: SPA (CSP, frame-ancestors 'none')
                                     └ Envoy routes /api /bff /oauth2 /login ─► bff ─(bearer)─► api
                                                                              └─(client secret)─► auth /oauth2/token|revoke|introspect
Envoy Gateway (edge, TLS, HSTS) ─► pods; auth/bff believe X-Forwarded-* only from private addresses
Valkey: sessions (auth, bff) + rate-limit counters (auth) · Postgres: accounts, factors, authorizations, audit
```

**Main threats and the controls that answer them.**

| threat | controls (after S-20) |
|---|---|
| Guessing phone / TOTP / backup codes | per-flow limits (5 per code / attempt), S-9 limits per account, IP and session with exponential lockout, **fail closed when Valkey is down (staging/prod)**, TOTP step replay protection |
| Account enumeration | identical sign-in steps and answers for unknown accounts, **identical passkey options**, **equal work for unknown accounts**, same 429s; registration's "already in use" is a design requirement (accepted, rate limited) |
| CSRF / login CSRF | auth: CORS allow-list + Origin check (+ **Fetch Metadata**) + JSON bodies; bff: double-submit token **from the header only**, **`__Host-` cookies**; OAuth `state` + PKCE + nonce |
| Session fixation / hijack | new session id at sign-in, registration, **step-up** and the BFF callback; HttpOnly, Secure, host-only **`__Host-`** cookies; tokens never reach the browser |
| Open redirect | `next` local paths only, **no control characters** (BFF and Studio); exact-match redirect URIs |
| Phishing / cloned authenticators | WebAuthn origin + RP id checks, **user verification required**, **signature counter must advance** |
| Weak tokens for business actions | `acr=mfa` only after a second factor; api enforces it on merchant endpoints and **now on console (staff) endpoints**; PKCE S256; 10-min access tokens; rotating refresh tokens |
| Sign-out that doesn't sign out | BFF session invalidated + refresh token revoked, which **now ends the sign-in at auth**; revoked sessions end everywhere within ~1 min (S-19) |
| Forged client IP / city | trusted-proxy CIDRs; **staging/prod NetworkPolicy only admits Envoy Gateway**; city header only from a CDN that overwrites it |
| Framing, content sniffing | Studio nginx CSP/XFO/COOP; auth and bff: XFO DENY, nosniff, **CSP `default-src 'none'`**, no-referrer; HSTS at the edge |
| Information leakage | no tokens/codes in logs (except the local SMS fake), masked phone numbers, generic ProblemDetails, no stack traces |

## Checks

### 1. Open redirect via `next` / `redirect_uri`; exact-match redirect URIs

| item | result |
|---|---|
| BFF `/bff/login?next=` (`NextRedirect.safe`) | **OK** — local paths only (`/…`, not `//…` or `/\…`), control characters refused. Hardened: DEL too; tests for tab/newline variants (`BffSessionTest.login_rejectsOffSiteNext`). |
| Studio `safeNext` (`/sign-in?next=` when already signed in → `redirect({ href })`) | **fixed (M)** — it refused `//host` and `/\host` but accepted `/<TAB>/evil.example`; the URL parser drops tabs and newlines, which turns it into `//evil.example`, an off-site redirect from our origin. Control characters are now refused anywhere, same rule as the BFF (`routeSupport.test.ts`). |
| OAuth `redirect_uri` | **OK** — Spring Authorization Server compares the registered URI exactly; the client catalogue refuses wildcards, fragments and non-https URIs in staging/prod (S-122). Trailing slash, extra path, query, other port/scheme/host and `localhost:3100.evil.example` all get no code (`SecurityReviewApiTest.OAuth.redirectUris_mustMatchExactly`). |
| Federation and "unavailable provider" redirects | **OK** — built from `northline.auth.login-page` (configuration) with encoded query parameters; nothing from the request picks the host. |
| BFF `redirect_uri` built from `{baseUrl}` (forwarded host) | **accepted risk** — the BFF (Tomcat `RemoteIpValve`) believes `X-Forwarded-Host` from private addresses; a client can make *its own* authorization request carry another host, which auth then refuses (exact match). No other user is affected (a browser can't be made to send the header cross-site) and Envoy preserves `Host`. Local development relies on the Vite proxy's forwarded host. |

### 2. CSRF on every state-changing JSON endpoint

| item | result |
|---|---|
| auth `/api/auth/**` (register, resend, verify, passkey/TOTP steps, sign-in steps, sign-out, backup codes, step-up, Security › sessions/passkeys) | **OK** — every POST/DELETE goes through CORS (exact origins, credentials) and `OriginCheck` (allow-list or own origin); bodies are JSON (`@RequestBody`), so a cross-site form gets 415 or 403. |
| auth: request without `Origin` | **fixed (L)** — let through unconditionally. Now refused when Fetch Metadata says a browser sent it cross-site (`Sec-Fetch-Site: cross-site`); tools without either header still work (`SecurityReviewApiTest.CrossSiteRequests`). |
| auth authorization-server endpoints | **OK** — `/oauth2/token`, `/revoke`, `/introspect` need client authentication; `/oauth2/authorize` is a GET bound to `state` + PKCE; Apple's cross-site `form_post` is bound to `state` kept server-side (V022). |
| bff `/bff/logout`, `/api/**` relay | **fixed (M)** — `csrf.spa()` also accepted the token as an XOR-masked `_csrf` form field. A page on a sibling subdomain is *same site* (`SameSite=Lax` sends the session cookie) and can plant an `XSRF-TOKEN` cookie for the parent domain, then post a form with the matching masked field — e.g. a multipart upload through the relay. The token now counts only in `X-XSRF-TOKEN` (a custom header needs CORS, which the BFF doesn't grant), and the cookie is `__Host-` in the cloud (`BffHardeningTest.theCsrfToken_countsOnlyInTheHeader_notAsAFormField`, `aForeignCookieValue_isNoToken`). |
| bff `/bff/login` (login CSRF) | **OK** — it only starts an authorization request for the browser's *own* auth session; the callback needs the `state` stored in that browser's BFF session (`BffHardeningTest.theCallback_withAnotherBrowsersState_signsNobodyIn`). |

### 3. Session fixation

| item | result |
|---|---|
| auth sign-in, registration | **OK** — `changeSessionId()` when the session is established (`SecurityReviewApiTest.SessionFixation`). |
| auth step-up ("Confirm it's you") | **fixed (M)** — the session gained a fresh factor time (it unlocks revoking sessions and removing passkeys) without a new id. Now rotated. |
| BFF OAuth callback | **OK** — Spring Security's change-session-id strategy; proven end to end against a WireMock auth server (`BffHardeningTest.signingIn_givesTheSessionANewId_andLandsOnNext`). |

### 4. Cookie flags

| item | result |
|---|---|
| HttpOnly, Secure (cloud), SameSite | **OK** — session cookies HttpOnly + Secure (not under `local`, http) + `SameSite=Lax`. |
| `__Host-` prefix | **fixed (M)** — plain names, and `COOKIE_DOMAIN` could widen them to the whole zone. The cloud profiles now use `__Host-NL_AUTH`, `__Host-NL_STUDIO`, `__Host-XSRF-TOKEN` (Secure, path `/`, no Domain), so sibling subdomains can't plant or overwrite them; `COOKIE_DOMAIN` removed; the CSRF cookie is `SameSite=Strict` (`SessionCookieSettingsTest` ×2, `BffHardeningTest`). Local keeps the plain names (http). |
| `SameSite=Lax` rather than `Strict` for the session cookies | **accepted risk** — Strict would drop the auth cookie on the federation callbacks (a cross-site navigation from Google) and make links from emails to the Studio open signed out. CSRF is handled by the controls in § 2, which don't rely on SameSite. |

### 5. CORS origins and credentials

**OK** — only `/api/auth/**` has CORS; allowed origins are exactly `STUDIO_ORIGIN` and `CONSUMER_ORIGIN` (no patterns),
credentials allowed, methods GET/POST/DELETE; a preflight from another origin is refused without
`Access-Control-Allow-Origin` (`SecurityReviewApiTest.CrossSiteRequests.cors_answersOnlyTheAllowedOrigins_withCredentials`).
The BFF has no CORS (same origin as the Studio).

### 6. Account enumeration (responses and timing)

| item | result |
|---|---|
| sign-in identifier step and factor answers | **OK** — the same factors are offered to everyone; a wrong code for an unknown account gets the same 422 body as for a known one (`SecurityReviewApiTest.ErrorBodies.unknownAccount_andWrongCode_lookTheSame`); 429s are identical (S-9). |
| passkey sign-in options | **fixed (M)** — after typing an email, `allowCredentials` listed that account's credential ids (empty for unknown accounts): existence and "has a passkey" in one call, with no factor. Sign-in options are now the same for everyone (all passkeys are discoverable); the owner is checked after the assertion (`PasskeyApiTest.signInOptions_areTheSameForAnAccountWithAPasskey_andForNobody`). |
| timing of TOTP / backup-code checks | **fixed (L)** — unknown accounts skipped the database and the HMAC. They now run the same lookup (with an id no account has) and a code check against a decoy secret (`SignInServiceTest`). |
| registration: "An account already uses this email / mobile. Sign in instead." | **accepted risk** — the design and validation rules call for it. Mitigations: checked before any SMS is sent, and every "Send code" counts against `otp-send` (5/h per mobile, 20/h per IP, 5/h per session). A per-email limit would not stop a distributed enumeration either. |
| federation with a verified email of an existing account | **accepted risk** — only the owner of that verified Google/Apple address learns that it has an account. |

### 7. OTP and backup-code brute force; do the limits cover every path?

| item | result |
|---|---|
| phone code | **OK** — 6 digits, 10 min, 5 wrong tries per code, `otp-verify` 10/h per mobile across sessions and codes, 50/h per IP. |
| authenticator / backup code / passkey / step-up | **OK** — 5 failures per attempt or step-up session, then 10 / 15 min per account, 30 per IP, 10 per session, doubling lockouts (S-9 tests). Backup codes: ~50 bits each, single use. |
| paths without a limit | **OK** — registration's TOTP confirmation and passkey (the registrant's own secret/key), `/oauth2/token` client authentication (bcrypt, high-entropy secret, confidential BFF only), signed-in Security changes (`security-change` counts every call). |
| Valkey down → limits fail open | **fixed (M)** — new policy `RATE_LIMIT_WHEN_UNAVAILABLE`: `closed` (staging/prod default) answers `503 sign_in_unavailable` with `Retry-After: 30` on the code/OTP and factor paths; `open` (local/dev default, and a break-glass elsewhere, warned at start-up) lets them through with an error log. Identifier lookup and Security changes stay open (`RateLimitStoreDownApiTest` against an unreachable Valkey, `AttemptLimitsTest`, `RateLimitConfigTest.whenUnavailable_perProfile`, Studio `auth.test.tsx`). |
| backup codes stored as unsalted SHA-256 | **accepted risk** — offline guessing of ~50-bit codes is feasible after a database breach. They are single use and a second factor only (the attacker also needs the account). Follow-up: HMAC with a server-side key (compatible check of old hashes needed). |

### 8. TOTP replay within its window

**OK** — ±1 step (30 s) for drift; the used step is stored with a conditional `UPDATE … WHERE last_used_step < :step`
(atomic, shared by sign-in and step-up; registration marks its step too), so a code works once even across two
concurrent requests (`SignInApiTest.totpCode_cannotBeReplayed`, `StepUpApiTest.usedCodeStep_cannotBeReplayed`).

### 9. WebAuthn: origin, RP id, user verification, counter

| item | result |
|---|---|
| origin | **OK** — only `STUDIO_ORIGIN` / `CONSUMER_ORIGIN`; an assertion made for another origin fails (`PasskeyApiTest.anAssertionMadeForAnotherOrigin_isRejected`). |
| RP id | **OK** — `WEBAUTHN_RP_ID` (the zone); the RP id hash in authenticator data is verified by webauthn4j. |
| user verification | **fixed (M)** — `preferred`: a security key without PIN (presence only) signed in with `acr=mfa`, although the passkey is the whole sign-in. Now `required` at registration and at every assertion (`PasskeyApiTest.anAssertionWithoutUserVerification_isRejected`, `aPasskeyWithoutUserVerification_cantBeRegistered`). |
| signature counter | **fixed (M)** — Spring's relying party builds the credential from the stored *attestation object*, so webauthn4j compared each assertion's counter with the registration's (0), not the last one seen: a cloned authenticator was never noticed. The counter must now exceed the stored one unless both are 0 (authenticators that don't count, e.g. synced passkeys) (`aClonedAuthenticatorBehindOnItsCounter_isRejected`, `anAuthenticatorThatDoesntCount_keepsWorking`). |
| attestation | **accepted risk** — `none`; no authenticator model policy for small businesses. |

### 10. `acr=mfa` enforced for business tokens

| item | result |
|---|---|
| issuance | **OK** — the auth session exists only after a second factor (sign-in: passkey/TOTP/backup code; registration: phone + passkey/TOTP); federation never becomes the session; `acr=mfa` is set from the `FACTOR_*` authorities only. |
| api, merchant endpoints | **OK** — `@RequiresMerchant` → `MerchantAccess` requires `FACTOR_MFA` (`mfa_required`), checked per handler; `MerchantScopedEndpointsTest` fails an unguarded `{merchantId}` handler. |
| api, console (staff) endpoints | **fixed (M)** — `/api/v1/console/**` checked the staff role only, although CLAUDE.md requires `acr=mfa` for staff tokens too. Now role + MFA (`ConsoleAccessTest`). No console endpoint exists yet, so nothing was exposed. |

### 11. PKCE, token lifetimes, refresh rotation

| item | result |
|---|---|
| PKCE | **OK** — required for every client (the catalogue refuses `require-pkce: false` for public clients and under staging/prod); only S256 (a missing challenge or `plain` gets no code — `SecurityReviewApiTest.OAuth.pkceIsRequired_andOnlyS256`); the BFF sends `code_verifier` (`BffHardeningTest`). |
| lifetimes | **OK** — authorization code 5 min, access token 10 min (asserted), ID token 30 min, refresh token 12 h (BFFs) = session idle; mobile 30 days (local only today). |
| refresh rotation | **OK** — a new refresh token on every refresh; the previous one answers `invalid_grant` (`tokensLiveTenMinutes_andRefreshTokensRotate`). |
| refresh token reuse detection (revoke the family on replay) | **accepted risk** — Spring Authorization Server doesn't do it; the only cloud clients are confidential BFFs whose tokens never leave the server. To revisit with the mobile apps (S-28/S-87, with DPoP). |

### 12. Tokens or PII in logs

**OK** — every log statement in auth and bff reviewed: no tokens, secrets or codes, phone numbers masked
(`+1 403 *** **48`), user ids (ULIDs) only. **Accepted risk:** the local SMS fake writes codes to the log — refused
under staging/prod, warned under dev (no real person registers in dev). **Accepted risk:** edge access logs contain
the callback URL's `code` and `state` — single use, 5 minutes, PKCE-bound.

### 13. Error bodies that reveal internals

**OK** — RFC 9457 ProblemDetails with fixed texts; Boot's error attributes exclude message and stack trace; a
malformed body answers a generic 400 without class or package names (`SecurityReviewApiTest.ErrorBodies`). **Fixed
(L):** the identifier and backup code had no length limit (a megabyte "email" went into the session): now 320 / 64
characters, 422 `length`.

### 14. Trusted-proxy header spoofing (`X-Forwarded-*`, city header)

| item | result |
|---|---|
| auth `TrustedProxyFilter` | **OK** — forwarded headers believed only from `TRUSTED_PROXIES` CIDRs, right-most untrusted hop, IP literals only, stripped otherwise (S-9 tests). |
| who can be a "trusted proxy" in the cloud | **fixed (M)** — the default CIDRs are all private ranges (Envoy's pods have pod addresses) and the chart's NetworkPolicy admitted any namespace, so any pod in the cluster could reach auth and choose its client IP (rate limits per IP, sessions list). staging/prod now admit only the `envoy-gateway-system` namespace to the public apps (`values-staging.yaml`, `values-prod.yaml`; edge.md § Trusted proxies). |
| city header | **accepted risk** — display only; documented: set `CLIENT_CITY_HEADER` only when a CDN overwrites it on every request (Envoy alone passes the client's header through). |
| bff forwarded headers | **accepted risk** — see § 1 (redirect URI host); scheme/host only affect the sender's own requests. |

### 15. Clickjacking and security headers

| item | result |
|---|---|
| Studio (nginx) | **OK** — CSP with `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, COOP, Permissions-Policy, Referrer-Policy; HSTS at the edge. |
| auth and bff | **OK** (XFO DENY, nosniff, no-store from Spring Security) and **hardened**: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` and `Referrer-Policy: no-referrer` on every answer — they serve no pages (`SecurityReviewApiTest.Headers`, `BffHardeningTest.everyAnswer_forbidsFraming`). |

### 16. Logout invalidates both the BFF and the auth session

| item | result |
|---|---|
| BFF | **OK** — `POST /bff/logout` (CSRF) invalidates the session, revokes the refresh token, clears the cookie (now the configured `__Host-` name). |
| auth | **fixed (M)** — the auth session ended only if the browser's second call (`POST /api/auth/sign-out`) arrived; if it failed ("either failing still signs out locally"), the next `/bff/login` silently signed the same person back in — the "Not you?" case. A refresh token revoked at `/oauth2/revoke` now ends its whole sign-in (`revoke_reason = signed_out`): the auth session is dropped on its next request and gets no silent code (`SecurityReviewApiTest.OAuth.theBffRevokingItsRefreshToken_endsTheAuthSessionToo`). |
| access tokens after sign-out | **accepted risk** (S-19) — stateless JWTs stay valid at the api for ≤ 10 min, but only ever existed inside the BFF session that is gone. |
| BFF session check when auth is unreachable | **accepted risk** (S-19) — introspection fails open; the next refresh ends a revoked session within 10 min. |

## Findings fixed (summary)

| # | finding | sev. | fix | test |
|---|---|---|---|---|
| 1 | Studio `safeNext` accepted `/\t/host` (open redirect) | M | refuse control characters (also DEL in the BFF) | `routeSupport.test.ts`, `BffSessionTest` |
| 2 | BFF accepted the CSRF token as a `_csrf` form field (sibling-subdomain cookie tossing) | M | header-only token handler | `BffHardeningTest` |
| 3 | Cookies without `__Host-`, `COOKIE_DOMAIN` could widen them | M | `__Host-` names in the cloud, `COOKIE_DOMAIN` removed, CSRF cookie Strict | `SessionCookieSettingsTest` ×2, `BffHardeningTest` |
| 4 | Session id not rotated at step-up | M | `changeSessionId()` in `SessionSignIn.refreshFactor` | `SecurityReviewApiTest.SessionFixation` |
| 5 | Passkey sign-in options listed the typed account's credentials | M | anonymous options for sign-in | `PasskeyApiTest` |
| 6 | Passkey user verification only preferred | M | required at creation and assertion | `PasskeyApiTest` |
| 7 | Passkey counter compared with the registration's | M | own check against the stored counter | `PasskeyApiTest` |
| 8 | Rate limits failed open when Valkey was down | M | `RATE_LIMIT_WHEN_UNAVAILABLE`, closed in staging/prod | `RateLimitStoreDownApiTest`, `AttemptLimitsTest`, `RateLimitConfigTest`, Studio |
| 9 | Studio sign-out left the auth session alive if the browser's call failed | M | revoked refresh token ends the sign-in | `SecurityReviewApiTest.OAuth` |
| 10 | Staff (console) tokens didn't need `acr=mfa` | M | role + MFA on `/api/v1/console/**` | `ConsoleAccessTest` |
| 11 | Any pod could reach auth/bff with forged forwarded headers | M | staging/prod NetworkPolicy: Envoy Gateway only | `helm template` of staging/prod (rendered policies checked) |
| 12 | Timing difference for unknown accounts | L | same lookups + decoy TOTP check | `SignInServiceTest` |
| 13 | Missing-Origin requests always allowed | L | Fetch Metadata `cross-site` refused | `SecurityReviewApiTest.CrossSiteRequests` |
| 14 | Unbounded identifier / backup code | L | 320 / 64 characters | `SecurityReviewApiTest.ErrorBodies` |
| 15 | No CSP on auth/bff answers | L | `default-src 'none'`, `frame-ancestors 'none'`, no-referrer | `SecurityReviewApiTest.Headers`, `BffHardeningTest` |

## Not covered

Penetration testing against a deployed environment (none exists); Google/Apple and real authenticators (tests use
WireMock providers and a software authenticator); load/DoS of the auth endpoints beyond the rate limits; the
consumer and console apps (not built); the NetworkPolicy change in § 14 was rendered with `helm template` but not
applied to a cluster.
