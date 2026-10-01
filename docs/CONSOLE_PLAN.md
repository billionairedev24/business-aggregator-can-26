# Platform console plan (`web/apps/console`, design 03)

Scope: everything in `design/03 Platform Console.dc.html` — the screens and states listed in `docs/SCREENS.md` §
Platform console — built story by story (E-8: S-79 … S-85, S-90 … S-96). S-90 laid the foundation described here: the
app, its BFF, the staff role model, the shell (top bar, role-filtered sidebar, role switch, denied screen) and a
stand-in for every screen. Every later story replaces one or more stand-ins without touching another story's routes.
This file is the console counterpart of `docs/IMPLEMENTATION_PLAN.md` (the Studio's) and `docs/CONSUMER_WEB_PLAN.md`;
their frontend and backend conventions apply unless this file says otherwise. Source-of-truth order: `CLAUDE.md`.

## Decisions that shape everything

- **A TanStack Router SPA, like the Studio** (not Start/SSR like the consumer site): nothing here is indexed, every
  screen is behind a staff sign-in, and the Studio's structure (file routes, `features/<feature>/`, the shared
  `AppShell`) carries over unchanged. Image: `web/Dockerfile --target console` — the Studio's nginx template, port 8080,
  `NL_AUTH_ORIGIN` read at start (`/config.js`).
- **console-bff = the bff jar with the `console` Spring profile** (`server/bff/src/main/resources/application-console.yml`),
  deployed as `northline-console-bff` (port 8083) on `console.<zone>` (`/api`, `/bff`, `/oauth2`, `/login`). One codebase
  keeps CSRF, cookies, the S-19 revocation check and the S-20 hardening identical to the Studio's; the profile changes
  the OAuth client (`console-bff`, scopes `openid profile console`), the cookie (`__Host-NL_CONSOLE`) and lets **only
  staff with a second factor** keep a session (`StaffGate`). Runbook: `docs/runbooks/README.md` § Console BFF.
- **Roles are server-side.** `identity.platform_roles` holds `staff` (opens the console at all) and the console roles of
  design 03 (`admin`, `trust_safety`, `dispatch`, `finance`, `support`, `analyst`, V190). They reach the api in the
  access token's `roles` claim. `shared.security.StaffRole` maps each role to the **screens** it opens and the
  **actions** it allows (design `ROLES` + the Data Table's `CAN`). Every handler under `/api/v1/console/` declares
  `@RequiresConsole(screen, actions)` — secure by default (a handler without it is denied and fails
  `ConsoleEndpointsTest`). **The console only hides; the api refuses.**
- **One active role at a time ("Switch role view").** The console acts with one held role — the remembered one, else
  the first held in design order — and sends it as `X-Console-Role` on every api call; the api checks the person holds
  it and authorizes with that role alone. Switching posts `POST /api/v1/console/me/role-view` (audit-logged:
  `console.role_view_switched`). Without the header the api uses every held role (agents, scripts).
- **Region-neutral.** No province, city or zone in code: the top bar's "Ops · Alberta + BC pilot" is built from
  `GET /api/v1/geo/regions` (live provinces by name, pilot ones by code); screens that filter by place take province /
  market codes from the region model (S-134).

## Layout

```
web/apps/console/
  public/                       config.js (runtime NL_AUTH_ORIGIN in the image), favicon
  src/routes/                   file routes — thin: validateSearch, beforeLoad/loader, component from a feature
    __root.tsx                  providers are in main.tsx; not-found and error components
    sign-in.tsx                 the signed-out page (design 03 lines 31–62)
    _console.tsx                pathless layout: session (→ /sign-in?next=), roles (GET /api/v1/console/me), the shell
    _console/<screen>.tsx       one per screen (table below)
  src/features/<feature>/       api.ts (zod schemas, queryOptions, mutations) · messages.ts (en + fr) · *.tsx · *.css · *.test.tsx
    shell/                      ConsoleLayout (AppShell + top bar + account menu + denied banner), screens.ts, navMenu.ts,
                                api.ts (me, role switch, regions), roleView.ts, ScreenPending, NotFound, Brand
    auth/                       SignInPage (northline-auth JSON API via @northline/auth-kit, then the console-bff hand-off)
    overview/                   S-91
  src/lib/                      http.ts (@northline/client + X-Console-Role), session.ts, auth-server.ts
  src/test/                     setup.ts, render.tsx (renderConsole(path): the real route tree in a memory history;
                                staffApi(roles): session + /me + regions answered like the api)
```

A feature folder per screen, named after the design `view`: `orders`, `disputes`, `delivery`, `sellers`, `verify`,
`vetting`, `trust`, `taxonomy`, `support`, `regions`, `finance`, `reports`, `api`, `team`, `profile`, `oncall`. Anything
reusable (the Data Table, a chart, a stat tile) goes to `packages/ui` with a story.

## Running it

`docs/runbooks/local.md` § 5c. In short: `make up SERVICES="auth api bff-console console"` (sign in at
http://localhost:3200 as `priya.natarajan@example.com`, backup code `priya-n-00001` … `priya-n-00010`), or without auth
and bff `make up SERVICES="api console"` — dev auth as Priya Natarajan (`01J9ZD3V00000000000000PNA1`, every console role,
`db/seed-dev/V191`). Checks: `pnpm --filter @northline/console typecheck | test | build`.

## Frontend conventions (in addition to IMPLEMENTATION_PLAN.md § Frontend conventions)

- Call the api with `http()` from `src/lib/http.ts` (never `@northline/client` directly): it adds `X-Console-Role`.
- Gate controls, never data, in the UI: `useActiveGrant(me)` (from `features/shell/ConsoleLayout`) gives the active
  role's `screens` and `actions`; hide or disable a mutating control the role lacks (design: "View only · {role}" tag,
  `can = { create, update, delete, export }`). The api answers 403 `insufficient_role` anyway — show it as an inline
  error, not a crash.
- Route gating is done once, by the layout: a screen the active role doesn't open renders the denied banner above the
  overview (design `v.denied`), at the same URL, so switching role view lets the person in. Don't gate in screens.
- Copy: exactly the design's (`design/03`), en + fr-CA (`design/i18n-fr.js` wording where it exists). Numbers and
  dates through `useFormatters` (the platform zone from the region model). No place names in copy: take them from the
  data or the region model and pass them as parameters.
- Every record list is the shared Data Table (`packages/ui` `DataTable`, design `Data Table.dc.html`) with
  `can`/`roleName` from the active grant.
- Tests: vitest + Testing Library with `renderConsole(path)` and `staffApi(roles, extra)`; test every screen as at
  least one role that opens it and one that doesn't (the denied banner).

## Backend conventions

- A screen's endpoints live in the module that owns the data, under `/api/v1/console/<module>/…` (as
  `trust/flags`, `registry-reviews`, `payments/tax-reconciliations`). Screens that combine modules (overview, nav
  badges, search) live in the `console` module and read other modules only through their `api` packages (S-37: no
  cross-module SQL; add a query interface to the owning module's `api` when one is missing).
- Every handler: `@RequiresConsole(value = ConsoleScreen.X, actions = ConsoleAction.Y)` (reads: no actions) and a
  `CurrentStaff staff` parameter when it needs the actor. Privileged actions write the audit trail in their
  transaction: `AuditTrail.record(new Entry(merchantIdOrNull, staff.userId(), staff.roleCodes(), "<area>.<verb>", …))`.
- 403 codes: `mfa_required`, `not_staff`, `role_not_held`, `insufficient_role`, `unguarded_endpoint`
  (`StaffAccessDenied`). Validation: 422 as everywhere, messages added to `docs/spec/validation-messages.fr-CA.tsv`.
- Tests: `TestJwt.staff(userId, StaffRole...)` / `staffWithoutMfa(...)`; per endpoint a happy path for a role that
  opens it, 403 for one that doesn't, 403 without MFA, and the 422 messages. The OpenAPI document is
  `docs/api/openapi/api-console.yaml` (regenerate with `make openapi`).

## Routes

Paths avoid the prefixes the console-bff owns on this host (`/api`, `/bff`, `/oauth2`, `/login`): API & webhooks is
`/integrations`. Roles = who opens the screen (`StaffRole.screens()`); profile and on-call are open to every staff
member.

| path | design `view` | screen key | roles | story | status |
|---|---|---|---|---|---|
| `/sign-in` | signed out | — | anyone | S-90 | built (`?next=`, `?error=staff_only\|mfa_required\|signin`) |
| `/` | `overview` | `overview` | all six | S-91 | stand-in |
| `/orders` | `orders` | `orders` | admin, dispatch, support | S-81 | stand-in |
| `/disputes` | `disputes` | `disputes` | admin, trust_safety, finance, support | S-80 | stand-in |
| `/delivery` | `delivery` | `delivery` | admin, dispatch | S-81 | stand-in |
| `/sellers` | `sellers` | `sellers` | admin, trust_safety, support | S-82 | stand-in |
| `/sellers/$sellerId` | `seller_detail` | `sellers` | admin, trust_safety, support | S-82 | stand-in |
| `/verification` | `verify` | `verify` | admin, trust_safety | S-79 | stand-in |
| `/vetting` | `vetting` | `vetting` | admin, trust_safety | S-92 | stand-in |
| `/trust` | `trust` | `trust` | admin, trust_safety | S-93 | stand-in |
| `/catalogue` | `taxonomy` | `taxonomy` | admin | S-94 | stand-in |
| `/support` | `support` | `support` | admin, trust_safety, dispatch, support | S-83 | stand-in |
| `/provinces` | `regions` | `regions` | admin | S-84 | stand-in |
| `/finance` | `finance` | `finance` | admin, finance | S-85 | stand-in |
| `/reports` | `reports` | `reports` | admin, finance, analyst | S-95 | stand-in |
| `/integrations` | `api` | `api` | admin | S-96 | stand-in |
| `/team` | `team` | `team` | admin, trust_safety, finance | S-96 | stand-in |
| `/profile?tab=security\|sessions\|audit\|prefs` | `profile` | `profile` | every staff member | S-96 | stand-in |
| `/on-call` | `oncall` | `oncall` | every staff member | S-96 | stand-in |
| any of the above, role can't open it | `denied` | — | — | S-90 | built (banner + overview) |

## Roles

| role (`StaffRole`, token code) | design name | screens | actions |
|---|---|---|---|
| `admin` | Admin | all | all (`suspend`, `decide`, `refund`, `province`, `payouts`, `keys`, `verify`, `vet`, `dispatch`, `support`) |
| `trust_safety` | Trust & safety | overview, disputes, sellers, verify, vetting, trust, support, team | suspend, decide, verify, vet, support |
| `dispatch` | Ops dispatcher | overview, orders, delivery, support | dispatch |
| `finance` | Finance | overview, disputes, finance, reports, team | refund, payouts |
| `support` | Support | overview, orders, disputes, sellers, support | support |
| `analyst` | Read-only analyst | overview, reports | — |

Not modelled yet (later stories): the design's co-signatures ("province Off↔Live needs 2 admins", "Suspend requires a
T&S lead co-sign", "refunds > $500 need a 2nd approver"), the per-role second factor ("Passkey" / "App 2FA" / "SSO" —
today every role needs `acr=mfa`), and granting roles from the Team screen (SQL until S-96 —
`docs/runbooks/README.md` § Console BFF).

## Contracts

### Session (console-bff)

`GET /bff/session` → `200 { user: { id, firstName, lastName, email, phone, initials, locale, memberSince }, acr, sid }`
or `401`; `POST /bff/logout` → `204`; `GET /bff/login?next=/path` (sign-in hand-off after the JSON sign-in). Only staff
with `acr=mfa` ever get a 200. CSRF: `__Host-XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header only (the shared `http()`).
Document: `docs/api/openapi/bff-console-internal.yaml`.

### The staff member (S-90)

```
GET  /api/v1/console/me                    → { userId, roles: [{ role, screens: [...], actions: [...] }] }   (held roles, design order)
POST /api/v1/console/me/role-view {role}   → { role, screens, actions }   403 role_not_held · 422 "Choose a role."
```

`GET /api/v1/console/me` is sent without `X-Console-Role` (the stored view may name a role taken away since).

## API: what exists, what's missing

| screen | exists | missing (the screen's story adds it) |
|---|---|---|
| shell | `GET /api/v1/console/me`, `POST …/me/role-view` (S-90); `GET /api/v1/geo/regions` (S-134) | nav badge counts (`GET /api/v1/console/nav-badges`, design: "14", "1 stuck", "23 open"…); global search (`GET /api/v1/console/search?q=` across merchants, orders, cases — the design shows the pill only, no results; no story owns it yet) |
| overview | — | S-91 |
| orders, delivery | — (`orders.api`, `fulfilment` read models for merchants only) | console orders monitor, runs, couriers, zone economics (S-81) |
| disputes | `payments.api.DisputeDecisions` (decide, decideRefund) | the agents' queue and evidence endpoints (S-80) |
| sellers | `merchants.api.MerchantDirectory`, `trust.api.QualityQuery` | directory with filters, seller detail, oversight actions (coach, instant book off, hide, demote, suspend) (S-82) |
| verify | `GET/POST /api/v1/console/registry-reviews` (S-23) | the application queue with KYC / licence / insurance checks, approve / request info (S-79; replaces the local "Simulate approval") |
| vetting | — | flagged listings queue, approve / reject (S-92) |
| trust | `GET /api/v1/console/trust/flags`, `POST …/{id}/decision` (S-133) | tier rules, automatic consequences, rating floor tuning (S-93) |
| taxonomy | `db/seed/categories.json` (seed only) | categories CRUD with regulators, limits, per-province rules (S-94) |
| support | customer cases (`account`, `messaging.api`) for their owners | tickets queue, macros en/fr, case actions (S-83) |
| regions | `region.api.Regions` reads; `GET /api/v1/geo/regions` | province / market / zone stage changes with co-sign (S-84) |
| finance | `POST /api/v1/console/payments/tax-reconciliations` (S-21) | escrow / payouts / reconciliation / take rate by tier / revenue mix (S-85) |
| reports | — | funnels, cohorts, top categories, supply gaps (S-95) |
| api | `developer` module (merchants' keys and webhooks) | platform-wide API clients and rate limits (S-96) |
| team, profile | `developer.api.AuditTrail` (write); auth `GET /api/auth/security` (sessions, passkeys) | roles and people, audit log views ("My audit trail"), sessions (S-96) |
| oncall | — | rota, incidents, escalation paths (S-96) |

## Migration and seed ranges

**V190–V199** (IMPLEMENTATION_PLAN.md, the next free range above V183). S-90: V190 (`identity.platform_roles` console
roles, `granted_by`; `ix_audit_log_platform`), dev seed V191 (Priya Natarajan, staff with every role). Later console
stories take the next numbers in the range; a seed stays in `db/seed-dev/`.

## Deploy

Chart apps `console` (static) and `console-bff` (the bff image + `console` profile); the console host routes `/api`,
`/bff`, `/oauth2`, `/login` to the console-bff (`templates/ingress.yaml`). Secrets `CONSOLE_BFF_SECRET` (console-bff) and
`CONSOLE_BFF_SECRET_HASH` (auth, now required). Argo CD: `console` and `console-bff` in `envs/*/images.yaml`
(`promote.sh` pins console-bff to the bff's digest). Runbooks: README § Console BFF, local.md § 5c, dev/staging/prod.md,
secrets.md, edge.md, deploy.md.
