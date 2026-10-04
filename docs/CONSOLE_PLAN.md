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
| `/?province=&market=` | `overview` | `overview` | all six | S-91 | built |
| `/orders?view=&q=&province=&market=` | `orders` | `orders` | admin, dispatch, support, support_lead | S-81 | built |
| `/disputes?province=&market=&case=kind:id` | `disputes` | `disputes` | admin, trust_safety, finance, support, support_lead | S-80 | built |
| `/delivery?market=` | `delivery` | `delivery` | admin, dispatch | S-81 | built |
| `/sellers?q=&province=&market=&risk=` | `sellers` | `sellers` | admin, trust_safety, support, support_lead | S-82 | built |
| `/sellers/$sellerId` | `seller_detail` | `sellers` | admin, trust_safety, support, support_lead | S-82 | built |
| `/verification?province=&market=&application=` | `verify` | `verify` | admin, trust_safety | S-79 | built |
| `/vetting?province=&market=` | `vetting` | `vetting` | admin, trust_safety | S-92 | built |
| `/trust?province=&market=` | `trust` | `trust` | admin, trust_safety | S-93 | built |
| `/catalogue` | `taxonomy` | `taxonomy` | admin | S-94 | built |
| `/support?province=&market=&filter=&ticket=` | `support` | `support` | admin, trust_safety, dispatch, support, support_lead | S-83 | built |
| `/provinces?province=` | `regions` | `regions` | admin | S-84 | built |
| `/finance` | `finance` | `finance` | admin, finance | S-85 | built |
| `/reports` | `reports` | `reports` | admin, finance, analyst | S-95 | stand-in |

| `/finance` | `finance` | `finance` | admin, finance | S-85 | stand-in |
| `/reports?province=` | `reports` | `reports` | admin, finance, analyst | S-95 | built |
| `/integrations` | `api` | `api` | admin | S-96 | stand-in |
| `/team` | `team` | `team` | admin, trust_safety, finance | S-96 | stand-in |
| `/profile?tab=security\|sessions\|audit\|prefs` | `profile` | `profile` | every staff member | S-96 | stand-in |
| `/on-call` | `oncall` | `oncall` | every staff member | S-96 | stand-in |

| `/privacy?state=open\|closed&request=` | `privacy` | `privacy` | admin, privacy, support_lead | S-105 | built |
| `/uat?view=feedback\|participants\|report&state=&blocking=&persona=&item=` | `uat` (no design: S-121) | `uat` | admin, support, support_lead | S-121 | built |
| `/go-live?market=` | `go_live` (no design: S-118) | `go_live` (+ `attest`, `province`) | admin; finance, merchant_success (record gates); trust_safety (read) | S-118 | built |
| `/integrations` | `api` | `api` | admin | S-96 | built |
| `/team` | `team` | `team` | admin, trust_safety, finance | S-96 | built |
| `/profile?tab=security\|sessions\|audit\|prefs` | `profile` | `profile` | every staff member | S-96 | built |
| `/on-call` | `oncall` | `oncall` | every staff member | S-96 | built |
| any of the above, role can't open it | `denied` | — | — | S-90 | built (banner + overview) |

## Roles

| role (`StaffRole`, token code) | design name | screens | actions |
|---|---|---|---|
| `admin` | Admin | all | all (`suspend`, `decide`, `refund`, `province`, `payouts`, `keys`, `verify`, `vet`, `dispatch`, `support`, `macros`) |
| `trust_safety` | Trust & safety | overview, disputes, sellers, verify, vetting, trust, support, team | suspend, decide, verify, vet, support |
| `dispatch` | Ops dispatcher | overview, orders, delivery, support | dispatch |
| `finance` | Finance | overview, disputes, finance, reports, team | refund, payouts |
| `support` | Support | overview, orders, disputes, sellers, support | support |
| `support_lead` | Support lead (S-83; design: "macros … editable by support leads") | as support | support, macros |
| `analyst` | Read-only analyst | overview, reports | — |

Not modelled yet (later stories): the design's co-signatures ("province Off↔Live needs 2 admins", "Suspend requires a
T&S lead co-sign"; "refunds > $500 need a 2nd approver" is built for disputes by S-80), the per-role second factor ("Passkey" / "App 2FA" / "SSO" —
today every role needs `acr=mfa`). Granting roles is the Team screen's since S-96 (the runbook's SQL only bootstraps the first admin —
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

### Verification queue (S-79)

```
GET  /api/v1/console/verification/applications[?province=&market=]      → { items: [Application], pending, medianDecisionHours? }
GET  /api/v1/console/verification/applications/{businessId}             → { application, owners, registryReviews, decisions }
POST /api/v1/console/verification/applications/{businessId}/decision    {decision: approve|request_info, checkKeys?, note?}  (verify)
POST /api/v1/console/verification/applications/{businessId}/identity-reviews/{checkId}/decision {decision: approve|reject, note?}
409 not_pending · reviews_open · review_closed
```

Every console screen that filters by place resolves `?province=&market=` through `shared.PlaceFilter` (same rules and
422s as the overview) and shows the shell's `PlaceFilters`.

### Listing vetting (S-92)

```
GET  /api/v1/console/vetting[?province=&market=]          → { autoApproved, flagged, items: [Item] }   (screen vetting)
POST /api/v1/console/vetting/listings/{id}/decision        {decision: approve|reject, reasons?, note?}  (vet)
POST /api/v1/console/vetting/dishes/{id}/decision          {decision: approve|reject, reasons?, note?}  (vet)
Item: { id, kind: product|service|dish, merchantId, businessName, province?, name, priceCents?, category?, medianCents?,
        deviationPct?, regulator?, flags[], revetReasons[], trustFlags[{flagId, rule, source, explanation, categories}],
        state: pending|approved|rejected, submittedAt?, decidedAt?, reasons[], note? }
409 not_in_review
```

### Disputes & refunds (S-80)

```
GET  /api/v1/console/disputes[?province=&market=]          → { summary: {forAgent, inSellerWindow, closedThisWeek}, items: [{row, businessName, province}] }
GET  /api/v1/console/disputes/{dispute|refund}/{id}        → { item, detail: {evidence, customerPriorDisputes, sellerPriorDisputes, sellerPriorWon}, sellerQuality? }
GET  /api/v1/console/disputes/dispute/{id}/evidence/{evidenceId}   the stored file
POST /api/v1/console/disputes/{dispute|refund}/{id}/decision {outcome: full_refund|partial|release|goodwill_credit, refundCents?, note?}  (decide)
POST /api/v1/console/disputes/decisions/{decisionId}/cosign  {decision: approve|decline, note?}   (refund; not the decider)
409 not_with_agent · awaiting_cosign · cosign_self · cosign_closed
```

### Trust & safety (S-93)

```
GET /api/v1/console/trust/rules                                  → {items: [{key, value, defaults, fields, updatedBy?, edited?}]}
PUT /api/v1/console/trust/rules/{key} {value}                    (decide) 422 value.<field>
GET /api/v1/console/trust/rules/rating_floor/impact?rating=4.4[&province=&market=]  → {rating, days, affected, total}
GET /api/v1/console/trust/flags/queue[?province=&market=]        → {items: [FlagView + businessName, province, action]}
POST /api/v1/console/trust/flags/{id}/action {action: warn|coach|confirm|suspend_listings|escalate, note?}  (decide; suspend)
```

### Support desk (S-83)

```
GET    /api/v1/console/support/tickets[?filter=all|urgent|unassigned|mine|sla_risk|providers|sellers|kitchens|customers][&province=&market=]
       → {kpis: {open, urgent, medianFirstReplyMinutes?, slaAtRisk, resolvedWithoutEscalation?, csat?, frenchShare?}, counts: {<filter>: n}, items: [Ticket]}
GET    /api/v1/console/support/tickets/{id}                → {ticket, context, refLabel?, notes: [{by, name?, body, at}], refundRequests}
POST   /api/v1/console/support/tickets/{id}/reply {body, resolve?, macroKey?}   (support) → waiting | resolved
POST   /api/v1/console/support/tickets/{id}/take                                (support)
POST   /api/v1/console/support/tickets/{id}/escalate {note?}                     (support) 409 already_escalated
POST   /api/v1/console/support/tickets/{id}/refund-requests {amountCents, note?} (support)
GET    /api/v1/console/support/refund-requests[?province=&market=]   (screen finance) → {items: [{request, ticket}]}
POST   /api/v1/console/support/refund-requests/{id}/decision {decision: approve|decline, note?}  (screen finance, refund) 409 request_self · request_closed
GET    /api/v1/console/support/macros                                    → {items: [{id, key, title: {en, fr}, body: {en, fr}}]}
POST   /api/v1/console/support/macros {key, title, body}  · PUT …/macros/{id} · DELETE …/macros/{id}   (macros)
409 case_resolved on any action on a resolved case
```

### Overview (S-91)

```
GET /api/v1/console/overview[?province=AB][&market=<region market id>]   (screen overview: every role)
→ { asOf, timeZone, scope: { province, market, city }, from,
    headline: { gmvCents, sellers, verifications, disputes },
    kpis: { gmvCents, previousGmvCents, revenueCents, orders, bookings, onTimeRatio?, disputeRate?, averageDeliveryFeeCents? },
    weeks: [12 × { start, goodsCents, servicesCents }],                       oldest first
    health: [{ key: api_p95|search_p95|kafka_lag|stripe|tracking_streams|courier_app, value?, status: ok|degraded|unknown }],
    workQueue: { verifications, flaggedListings, disputes: {count, oldest?}, stuckRuns: {count, oldestOverdue?},
                 trustFlags: {count, oldest?, offPlatformPayment}, sellersBelowFloor },
    live: { couriersOnRuns, couriersActive, providersOnJobs, pools: [{ market, city, label?, orders, closesAt, startsAt }], escrowHeldCents } }
422 province | market: not in the region model, or the market outside the province
```

- **Periods:** rolling — the KPIs cover the last 7 days (`from` … `asOf`) against the 7 days before; the chart is 12
  consecutive 7-day periods ending now. **GMV** = goods (order lines, quantity × unit price; shop and food orders by
  placed time; cancelled orders and refunded lines out) + services (booking price by booking time; cancelled out).
  **Net revenue** = the ledger's `revenue` account (fees credited at escrow release, minus dispute give-backs).
  **On time** = pooled-run orders delivered before their window's end. **Dispute rate** = disputes opened ÷ orders +
  bookings. **Goods share** = orders ÷ orders + bookings.
- **Scope:** a province and/or market (region model) resolves to the businesses there (`merchants.api.MarketplaceMerchants`);
  every module filters its own rows by those ids (`shared.MerchantScope`). The fleet (couriers, stuck runs) is
  platform-wide until zones carry their market. Dates show in the market's, else the province's, else the platform
  zone (`timeZone`).
- **Sources (no cross-module SQL, S-37):** `orders.api.MarketplaceOrders`, `booking.api.MarketplaceBookings`,
  `payments.api.MarketplaceMoney`, `merchants.api.MarketplaceMerchants`, `catalogue.api.VettingQueue`,
  `trust.api.TrustQueues`, `fulfilment.api.FleetStatus`, `orders.api.DeliveryRuns` (pools), and the console's
  `HealthSignals` port (`none` | `prometheus`, docs/runbooks/observability.md § Console health).
- The console refreshes it every minute while open.

### Delivery and dispatch (S-86; S-81 builds the screens)

Full contract, payloads and the planning rules: [runbooks/fulfilment.md](runbooks/fulfilment.md). Every handler carries
`@RequiresConsole`; changes go to the platform audit log (`developer.audit_log`, `merchant_id` null, actions
`fulfilment.courier_added` · `fulfilment.shift_scheduled` · `fulfilment.run_assigned` · `fulfilment.runs_planned`).

| call | screen · action | answer |
|---|---|---|
| `GET /api/v1/console/fulfilment/runs?market=&from=&to=` | delivery | `{items: [RunSummary]}` (default: yesterday → 2 days ahead) |
| `GET /api/v1/console/fulfilment/runs/{runId}` | delivery | `{run: RunSummary, stops: [Stop]}` |
| `POST /api/v1/console/fulfilment/runs/{runId}/assign` `{courierId}` | delivery · dispatch | RunSummary; 409 `courier_busy` / `run_started` |
| `GET /api/v1/console/fulfilment/couriers?market=` | delivery | `{items: [{id, userId, name, market, vehicle, status, active, shift, runId, position}]}` (`position` from S-88: the latest only) |
| `POST /api/v1/console/fulfilment/couriers` `{userId, market, vehicle}` | delivery · dispatch | 201 CourierSummary; 409 `already_a_courier` |
| `POST /api/v1/console/fulfilment/couriers/{courierId}/shifts` `{startsAt, endsAt}` | delivery · dispatch | 201 shift; ≤ 12 h |
| `POST /api/v1/console/fulfilment/plan` `{market?}` | delivery · dispatch | `{runs, assigned}` |
| `GET /api/v1/console/fulfilment/orders/{orderId}` | orders | `{orderId, orderRef, orderType, kind, market, state, orderBy, packBy, run, pickups: [{merchantId, name, packedAt, pickedUpAt}], dropoffEta, deliveredAt, proofKind}` |

`RunSummary` = `{id, label, part, market, kind, state, startsAt, endsAt, packBy, courier: {id, userId, name}, orders,
stopsDone, stopsTotal, nextEta, late, heuristic}` (`late`: a pending stop more than 15 min past its ETA).

### Orders monitor and delivery ops (S-81)

```
GET  /api/v1/console/orders?view=attention|live|escrow|late|all&q=&province=&market=      (screen orders)
→ { asOf, week, counts: { attention, live, escrow, late, all }, truncated,
    items: [{ id, ref, kind: order|booking, type: goods|food|service, customer, sellers: [name], amountCents, state,
              status: new|live|escrow|escrow_48h|late|stuck|issue|delivered|done|cancelled, attention, at, since }] }
GET  /api/v1/console/delivery/map?market=<region market id>                              (screen delivery)
→ { market: { id, city, province, lat, lng }, zones: [{ id, marketId, name, ring: [{lat, lng}], runsPerDay, feeStdCents,
    feePlusCents, minBasketCents }], basemap: { tiles, attribution } | null }
POST /api/v1/console/fulfilment/couriers/{courierId}/pause {reason}                        (delivery · dispatch)
POST /api/v1/console/fulfilment/couriers/{courierId}/resume                                (delivery · dispatch)
```

Rules (attention, late, stuck, escrow > 48 h) and the map provider: DECISIONS "S-81". Pause / resume are audited
(`fulfilment.courier_paused` with the reason, `fulfilment.courier_resumed`); a paused courier gets no run.

### Sellers directory and seller detail (S-82)

```
GET  /api/v1/console/sellers?q=&province=&market=            (screen sellers)
→ { asOf, active, atRisk, truncated, items: [Row] }
Row: { id, name, category: {id, names}, type, province, city, tier, status, quality, gmv90Cents, disputeRate,
       flags: [{ kind: quality_below|disputes_above|trust_flag|check_expiring|check_due|check_pending, rule?, checkType?,
                 registry?, status?, value?, floor?, days? }] }
GET  /api/v1/console/sellers/{sellerId}
→ { asOf, seller: Row, joinedAt, approvedAt, stripeAccount, ratingAverage, ratingCount, quality, onTime, disputes
    ({value, floor}), signals: [{key, value, bar, barFloor, inverted}], checks: [Check], trail: [{id, action, reason,
    detail, actorName, actorRole, at}] }
POST /api/v1/console/merchants/{businessId}/suspend {reason}                      (sellers · suspend) 409 not_active
POST /api/v1/console/merchants/{businessId}/reinstate {reason}                    (sellers · suspend) 409 not_suspended
POST /api/v1/console/merchants/{businessId}/reverification {verificationId, reason} (sellers · verify) 409 not_verifiable
POST /api/v1/console/merchants/{businessId}/tier {tier, reason}                   (sellers · suspend) 409 same_tier · not_approved
POST /api/v1/console/merchants/{businessId}/search {hidden, reason}               (sellers · suspend) 409 already_hidden · not_hidden
```

Row also carries `searchHidden` (`staff` | `rating_floor` | null). A nightly job enforces the trust rules'
consequences: below the rating floor → hidden from search until it recovers; off-platform payment again after a
warning → suspended (actor `system`).

Audit `merchant.<action>`; events `merchant.suspended|reinstated|tier_changed|reverification_required`; the owners are
emailed with the reason (DECISIONS "S-82").

### Province switchboard (S-84)

```
GET    /api/v1/console/regions                                   (screen regions) → { provinces: [Province] }
Province: { id, code, names, stage, languages, courierModel, tax: {gst|pst|hst|qst: bps}, timeZones, holidays, privacyLaw,
            registries, waitlist, markets: [{id, city, stage, lat, lng, radiusKm, zones, waitlist}],
            zones: [{id, marketId, name, runsPerDay, feeStdCents, feePlusCents, minBasketCents, areaKm2}],
            checklist: {taxProfile, holidays, registries, marketWithZones} }
POST   /api/v1/console/regions/provinces/{code}/stage {stage, confirm: <code>}       (province) 409 not_ready
PUT    /api/v1/console/regions/provinces/{code}/courier-model {courierModel: own|contracted|hybrid}
POST   /api/v1/console/regions/markets {province, city, lat, lng, radiusKm}           409 market_exists
POST   /api/v1/console/regions/markets/{marketId}/stage {stage, confirm: <city>}      422 stage (above the province) · 409 not_ready
POST   /api/v1/console/regions/zones {marketId, name, runsPerDay?, feeStdCents?, feePlusCents?, minBasketCents?, boundary?: GeoJSON}
PUT    /api/v1/console/regions/zones/{zoneId}   (same body; boundary omitted = kept)
DELETE /api/v1/console/regions/zones/{zoneId}                                         409 last_zone
→ each change answers the province (Province)
```

Every change is audited (`region.*`) and re-reads the region model after commit (DECISIONS "S-84").

### Finance and reconciliation (S-85)

```
GET  /api/v1/console/finance                                         (screen finance)
→ { asOf, timeZone, escrowHeldCents, escrowItems, payoutsInFlightCents, payoutsInFlightSellers, nextPayoutArrival,
    revenueWeekCents, mix: {takeCents, deliveryCents, adjustmentsCents, plusCents: null, rewardsCents: null},
    tiers: [{tier, sellers, rateBps, gmvShare}], tax: {period, platformFeeCents, facilitatorCents, nextFiling} }
GET  /api/v1/console/payments/reconciliation?from=&to=               → {items: [Day]} (default: 14 days)
GET  /api/v1/console/payments/reconciliation/{day}                   → {day: Day, items: [Item]}
GET  /api/v1/console/payments/reconciliation/export?from=&to=        text/csv (audited)
GET  /api/v1/console/payments/reconciliation/ledger-export?from=&to= text/csv (audited)
POST /api/v1/console/payments/reconciliation/run {day}               (finance · payouts) Day
POST /api/v1/console/payments/reconciliation/{day}/resolve {note}    (finance · payouts) Day   409 not_mismatched
Day: {day, stripeCents, ledgerCents, varianceCents, feeCents, items, mismatches, status: matched|mismatch|resolved, …}
GET  /api/v1/console/support/refund-requests                         (screen finance; S-83's) {items: [{request, ticket}]}
POST /api/v1/console/support/refund-requests/{id}/decision {decision: approve|decline, note?}  (finance · refund; S-83's)
```

The screen lists support's pending refund requests under "Refund requests from support" and decides them there.

Rules: DECISIONS "S-85"; operations: runbooks/stripe.md § 10.

### Catalogue taxonomy (S-94)

```
GET  /api/v1/console/taxonomy                                          (screen taxonomy)
→ { asOf, serviceCategories, shopDepartments, categories: [Row], regulators: [{code, name, province, website, categories}],
    limits: [{merchantType, max, businessesAbove, updatedAt, updatedBy}], suggestions: [{id, name, businesses: [{id, name, type, province, status}]}] }
Row: { id, parentId, root, group, nameEn, nameFr, bookingType, regulatedRegistry, requiresVsCheck,
       regulators: [{province, regulator|null}], sellers, liveIn: [province], liveListings, medianPriceCents, priceMode: fixed|hourly|quote }
POST /api/v1/console/taxonomy/categories {root?, parentId?, nameEn, nameFr?, bookingType?, regulatedRegistry?, requiresVsCheck}
                                                                       (taxonomy · vet) 201 Row · 409 category_exists
PUT  /api/v1/console/taxonomy/categories/{id} {nameEn, nameFr?, bookingType?, regulatedRegistry?, requiresVsCheck}   Row
PUT  /api/v1/console/taxonomy/categories/{id}/regulators/{province} {regulator: code|none|null}                      Row
POST /api/v1/console/taxonomy/regulators {code, name, province, website?}            201 · 409 regulator_exists
PUT  /api/v1/console/taxonomy/regulators/{code} {name, province, website?}           409 regulator_in_use
PUT  /api/v1/console/taxonomy/limits/{provider|seller|both|kitchen} {max}            Limit
POST /api/v1/console/taxonomy/suggestions/{suggestionId}/approve {CategoryInput}     {category: Row, moved, alreadyHeld}
POST /api/v1/console/taxonomy/suggestions/{suggestionId}/merge {categoryId}          {category: Row, moved, alreadyHeld}
```

Audit `catalogue.category_created | category_updated | category_regulated | regulator_created | regulator_updated |
suggestion_approved | suggestion_merged`, `merchants.category_limit_changed`, and per moved business
`merchant.category_assigned`; event `merchant.categories_changed` (search re-reads the business). DECISIONS "S-94".

### Reports & analytics (S-95)

```
GET /api/v1/console/reports[?province=AB]                     (screen reports; admin, finance, analyst)
→ { asOf, province, from, weeks: [{week, customers, previous}] (13),
    funnel: [{step: app_opens|browsed|cart|checkout|paid, count, recorded}],
    cohorts: [{month, customers, m1, m2, m3}] (4), topCategories: [{categoryId, names, salesCents}] (≤ 6),
    waitlist: [{province, people}] }
```

Counts only; any count from 1 to 4 is null (withheld). DECISIONS "S-95".

### Team, audit, API keys, on-call (S-96)

```
GET    /api/v1/console/team                                      (team) {roles: [{role, people, screens, actions}], members: [Member]}
POST   /api/v1/console/team/invite {email, role}                 (team · province = admin) Member
POST   /api/v1/console/team/{userId}/roles {role}                (team · province) Member
DELETE /api/v1/console/team/{userId}/roles/{role}                (team · province) Member · 409 own_admin | last_admin
GET    /api/v1/console/audit?actor=&action=&business=&target=&from=&to=&before=   (team) {items: [AuditRow], next}
GET    /api/v1/console/me/audit?before=                          (profile) my own entries
GET    /api/v1/console/api-keys                                  (api) {items: [KeyRow]}
POST   /api/v1/console/api-keys {merchantId, name, scopes}       (api · keys) 201 {key, secret}
POST   /api/v1/console/api-keys/{keyId}/revoke                   (api · keys) KeyRow
GET    /api/v1/console/oncall?from=&to=                          (oncall) {asOf, shifts, now, staff}
POST   /api/v1/console/oncall/shifts {userId, startsAt, endsAt, duty}   (oncall · province) 201
POST   /api/v1/console/oncall/shifts/{id}/hand-over {userId}     (oncall; the person on it, or an admin) · 409 not_your_shift
DELETE /api/v1/console/oncall/shifts/{id}                        (oncall · province) 204
```

Profile security and sessions call northline-auth's `/api/auth/security` (S-19) through `@northline/auth-kit`.
DECISIONS "S-96".

## API: what exists, what's missing

| screen | exists | missing (the screen's story adds it) |
|---|---|---|
| shell | `GET /api/v1/console/me`, `POST …/me/role-view` (S-90); `GET /api/v1/geo/regions` (S-134) | nav badge counts (`GET /api/v1/console/nav-badges`, design: "14", "1 stuck", "23 open"…); global search (`GET /api/v1/console/search?q=` across merchants, orders, cases — the design shows the pill only, no results; no story owns it yet) |
| overview | `GET /api/v1/console/overview` (S-91, below) | — |
| orders, delivery | `/api/v1/console/fulfilment/**` (S-86, § Delivery below): runs by market/time with `late`, run detail with stops, an order's delivery, couriers with shift and run, onboard a courier, schedule a shift, plan now, reassign a run; S-81: the orders monitor, the map's geometry, pause / resume a courier | zone economics' cost per stop (no courier cost model), paging a courier, bulk customer notices |
| disputes | `GET /api/v1/console/disputes`, `GET …/{kind}/{id}`, evidence download, `POST …/{kind}/{id}/decision`, `POST …/decisions/{id}/cosign` (S-80) | — |
| sellers | S-82: directory, detail, suspend / reinstate, re-verification, tier, hide from search | coaching, instant book off, bulk message, impersonation |
| verify | `GET/POST /api/v1/console/registry-reviews` (S-23); `GET /api/v1/console/verification/applications[/{id}]`, `POST …/{id}/decision`, `POST …/{id}/identity-reviews/{checkId}/decision` (S-79) | — |
| vetting | `GET /api/v1/console/vetting`, `POST …/listings/{id}/decision`, `POST …/dishes/{id}/decision` (S-92) | — |
| trust | `GET /api/v1/console/trust/flags`, `POST …/{id}/decision` (S-133); `GET …/flags/queue`, `POST …/flags/{id}/action`, `GET/PUT …/trust/rules[/{key}]`, `GET …/rules/rating_floor/impact` (S-93) | S-82 enforces the rating floor (hide from search) and warning-then-suspension; instant book off after no-shows and the photo delay have no state to act on |
| taxonomy | `db/seed/categories.json` (seed only) | categories CRUD with regulators, limits, per-province rules (S-94) |
| support | `GET /api/v1/console/support/tickets[/{id}]`, `POST …/tickets/{id}/reply\|take\|escalate\|refund-requests`, `GET …/refund-requests`, `POST …/refund-requests/{id}/decision`, `GET/POST/PUT/DELETE …/macros` (S-83) | CSAT collection (no survey sends it yet); the finance screen lists and decides refund requests (S-85) |
| regions | S-84: stages with a confirmation and the go-live checklist, markets, zones (GeoJSON), courier model | the co-sign of a second admin, dry-run as customer, categories per province, drawing zones on a map |
| finance | S-21 tax reconciliation; S-85: escrow, payouts in flight, revenue mix, take by tier, Stripe ↔ ledger reconciliation and exports, support's refund requests | Plus subscriptions and rewards (not recorded) |

| trust | `GET /api/v1/console/trust/flags`, `POST …/{id}/decision` (S-133); `GET …/flags/queue`, `POST …/flags/{id}/action`, `GET/PUT …/trust/rules[/{key}]`, `GET …/rules/rating_floor/impact` (S-93) | the consequences' jobs (S-82) |
| taxonomy | S-94: categories (add, edit), regulators by province, category limits, businesses' suggestions (approve / merge) | synonyms (fr/en) and search boosting rules (search reads neither yet); retiring a category |
| support | `GET /api/v1/console/support/tickets[/{id}]`, `POST …/tickets/{id}/reply\|take\|escalate\|refund-requests`, `GET …/refund-requests`, `POST …/refund-requests/{id}/decision`, `GET/POST/PUT/DELETE …/macros` (S-83) | the finance screen's list of refund requests (S-85 reads `GET …/support/refund-requests`); CSAT collection (no survey sends it yet) |
| regions | `region.api.Regions` reads; `GET /api/v1/geo/regions` | province / market / zone stage changes with co-sign (S-84) |
| finance | `POST /api/v1/console/payments/tax-reconciliations` (S-21) | escrow / payouts / reconciliation / take rate by tier / revenue mix (S-85) |
| reports | S-95: weekly active customers, shop funnel, signup-month cohorts, top categories, waitlist demand (counts only) | app opens and zero-result searches (nothing records them), scheduled email |
| api | `developer` module (merchants' keys and webhooks) | platform-wide API clients and rate limits (S-96) |
| team, profile | `developer.api.AuditTrail` (write); auth `GET /api/auth/security` (sessions, passkeys) | roles and people, audit log views ("My audit trail"), sessions (S-96) |
| oncall | — | rota, incidents, escalation paths (S-96) |

| reports | — | funnels, cohorts, top categories, supply gaps (S-95) |
| api | S-96: every business's API keys, issue (secret shown once) and revoke | request metrics (4.1M · p95 · 5xx · webhook success: no metrics source), per-key rate-limit edits, webhook catalogue page |
| team, profile | S-96: roles and people (grant / revoke / invite, admin only), audit log with filters and pages, my audit trail; profile security and sessions through `@northline/auth-kit` | Company SSO, backup-code regeneration from the console |
| oncall | S-96: rota (add / hand over / remove shifts), escalation paths | incidents ("Declare incident"), paging ("Page current on-call") |

## Migration and seed ranges

**V190–V199** (IMPLEMENTATION_PLAN.md, the next free range above V183); the console queues (S-79, S-80, S-83, S-92, S-93) **V210–V219**; the second batch (S-81, S-82, S-84, S-85, S-94–S-96) **V230–V239**. S-90: V190 (`identity.platform_roles` console
roles, `granted_by`; `ix_audit_log_platform`), dev seed V191 (Priya Natarajan, staff with every role). Later console
stories take the next numbers in the range; a seed stays in `db/seed-dev/`. The review queues (S-79, S-92, S-80, S-93,
S-83) use **V210–V219** (fulfilment took V200–V209 first; ordering rule). The second batch (S-81, S-82, S-84, S-85,
S-94–S-96) uses **V230–V239**: S-94 V232 (catalogue regulators, category rules, `edited_at`) and V233
(`merchants.category_limits`, the limit trigger's function reads it).

## Deploy

Chart apps `console` (static) and `console-bff` (the bff image + `console` profile); the console host routes `/api`,
`/bff`, `/oauth2`, `/login` to the console-bff (`templates/ingress.yaml`). Secrets `CONSOLE_BFF_SECRET` (console-bff) and
`CONSOLE_BFF_SECRET_HASH` (auth, now required). Argo CD: `console` and `console-bff` in `envs/*/images.yaml`
(`promote.sh` pins console-bff to the bff's digest). Runbooks: README § Console BFF, local.md § 5c, dev/staging/prod.md,
secrets.md, edge.md, deploy.md.
