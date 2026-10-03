# Go-live of a market (S-118)

How a market goes from `pilot` to `live` — the first one is Calgary (Alberta), and every later market follows the same
steps; nothing in the code names a place. The stage itself is region data (S-134, [regions.md](regions.md)); the pilot
cohort is S-120's ([pilot-onboarding.md](pilot-onboarding.md)); the UAT go/no-go is S-121's ([../uat/README.md](../uat/README.md)).

> **Plainly:** production does not exist yet. What exists is the switch (console › Go-live), the checks
> (`make go-live-check`), the process below and a rehearsal on a local stack ([§ Rehearsal](#rehearsal)). "Production
> live" happens when the people named in [§ The real-world checklist](#the-real-world-checklist) have done their part.

## Contents

- [The gates](#the-gates)
- [The readiness check: `make go-live-check`](#the-readiness-check-make-go-live-check)
- [Console › Go-live](#console--go-live)
- [Timeline: T-14 → T+14](#timeline-t-14--t14)
- [The go/no-go meeting](#the-gono-go-meeting)
- [Launch-day run sheet](#launch-day-run-sheet)
- [Rollback](#rollback)
- [Hypercare](#hypercare)
- [Contacts](#contacts)
- [The real-world checklist](#the-real-world-checklist)
- [Configuration](#configuration)
- [Rehearsal](#rehearsal)

## The gates

Every gate has a status — **pass**, **fail**, **pending** (not known yet) or **not applicable** —, its evidence and
an owner. A **required** gate that doesn't pass blocks the launch (unless an admin asks with an emergency override,
[§ Console › Go-live](#console--go-live)). *Automatic* gates are evaluated by the api on every read; *manual* ones are
recorded with who, when, a result and the evidence (text, optional `https://` link) by their owner in the console — or
by `make go-live-check RECORD=1` for what the repository can prove. A record counts for 14 days
(`GO_LIVE_RECORD_MAX_AGE`), then the gate is pending again: the checklist is re-confirmed for each launch.

| gate | kind | owner | passes when | evidence / procedure |
|---|---|---|---|---|
| `market_zones` | auto | operations | the market has a delivery zone with a boundary | Console › Provinces ([regions.md](regions.md)) |
| `province_live` | auto | operations | the market's province is live (a market is never more open than its province) | Console › Provinces |
| `uat_go_no_go` | auto | product | S-121's report says **go**: no blocking item open or unverified, every persona signed off | Console › UAT ([../uat/README.md](../uat/README.md)) |
| `pilot_businesses` | auto | merchant success | at least `GO_LIVE_MIN_PILOT_BUSINESSES` (10) pilot businesses are live or wait only for the launch (approved, page published, listings customers see — visible by direct link) | Console › Pilot onboarding ([pilot-onboarding.md](pilot-onboarding.md)) |
| `security_findings` | manual (script) | security | no open critical or high finding | [../security/findings.md](../security/findings.md) |
| `pentest` | manual | security | the external test's report has no open critical or high finding | [../security/pentest-scope.md](../security/pentest-scope.md) |
| `backup_drill` | manual (script) | SRE | a drill **in the cloud** passed within 92 days (locally: any drill) | [backups-dr.md § Drill log](backups-dr.md#drill-log) |
| `alert_rules` | auto with a metrics store, else manual | SRE | the store evaluates Northline's alerting rules (`northline-*` groups) | [alerting.md](alerting.md) |
| `oncall_coverage` | auto | SRE | the on-call rota covers every minute of the next 14 days | Console › On-call; [alerting.md § The on-call rota](alerting.md#the-on-call-rota) |
| `slo_alerts` | auto with a metrics store, else manual | SRE | no paging alert (`severity=page`) is firing; SLO burn normal | dashboards `northline-slo-*` |
| `legal_signoff` | manual (script) | legal | every legal text's current version carries counsel's sign-off | `make legal-status`; [../compliance/legal/review-packet.md](../compliance/legal/review-packet.md) |
| `pci_saq_a` | manual | payments | SAQ A and the AOC signed | [../compliance/pci/saq-a.md](../compliance/pci/saq-a.md), [../compliance/pci/aoc.md](../compliance/pci/aoc.md) |
| `stripe_live` | auto | payments | live secret and publishable keys, both webhook signing secrets set (configuration only, Stripe isn't called) | [stripe.md § 2](stripe.md#2-platform-account-setup-once-per-mode) |
| `stripe_webhooks` | manual | payments | both endpoints registered in live mode, a test event delivered | [stripe.md § 5](stripe.md#5-webhooks-s-12) |
| `dns_certs` | manual | SRE | every host resolves and serves a valid certificate, HSTS on | [edge.md § DNS delegation](edge.md#dns-delegation), [edge.md § Certificates](edge.md#certificates) |
| `app_stores` | manual, **optional** | mobile | the apps are live in both stores (a web-first launch may go without them) | [mobile-release.md](mobile-release.md#going-public-app-review-and-the-staged-rollout) |
| `french_coverage` | manual (script) | localization | `make i18n-check STRICT=1` passes — **only in a French-first market** (S-116), not applicable elsewhere | [i18n.md § 3](i18n.md#3-before-opening-a-french-first-place) |
| `a11y_criticals` | manual (script) | QA | no open critical or serious accessibility issue | [../a11y/audit.md](../a11y/audit.md) |
| `e2e_results` | manual (script) | QA | the last end-to-end run (within 7 days) has no failure | `docs/runbooks/e2e.md` (S-117), `e2e-out/results.json` |
| `load_test` | manual (script) | SRE | the last load test against the environment (within 7 days) held every SLO threshold | [load-testing.md](load-testing.md), `loadtest/results/…/summary.json` |

The UAT gate is platform-wide (S-121's pilot group), the others are per market or per deployment. A source that
can't be read (the UAT report, the metrics store) makes its gate **pending**, never the whole checklist.

## The readiness check: `make go-live-check`

```sh
make go-live-check ENV=local MARKET=mkt-calgary            # against a local api (dev auth as the seeded admin)
GO_LIVE_API=https://api.northline.ca GO_LIVE_TOKEN=… \
  make go-live-check ENV=prod MARKET=mkt-calgary RECORD=1   # a staff access token with a second factor
```

It reads the market's checklist from the api, checks what lives in the repository and on this machine (security
findings, accessibility audit, DR drill log, legal registry, French coverage where French-first, the last e2e and load
results, the alert rules of `PROMETHEUS_URL` when it answers), prints one line per gate with its status, kind, owner and
evidence, and exits **0** when nothing required blocks, **1** when something does, **2** on an error. With `RECORD=1` it
records the repository's results on the gates that take a record (source "script", recorded by the token's staff
member) and reads the checklist again. Script: `scripts/go-live/check.mjs` (parsers tested by
`node --test scripts/go-live/check.test.mjs`).

## Console › Go-live

`/go-live` in the console (screen `go_live`): admins, finance, merchant success and trust & safety open it.

- **The checklist**: every gate, its owner, status and evidence in words (en / fr-CA), who recorded it and when, the
  runbook. **Record** (action `attest`: admins, finance, merchant success) takes pass / fail / not applicable, the
  evidence and an optional `https://` link.
- **Switch to live — two people** (action `province`: admins). One admin asks (an optional note). A second admin
  approves by typing the market's name. The requester can't approve (409 `same_person`) and may withdraw; another
  admin may reject. A request lapses after `GO_LIVE_REQUEST_TTL` (24 h). Both steps are in the audit log
  (`golive.launch_requested`, `golive.launch_approved`, `region.stage_changed`).
- **Blocked while a required gate fails.** Asking or approving is refused (409 `not_ready`) unless the request carries
  an **emergency override**: a written reason of 20–500 characters, shown to the approver with the gates that failed
  when it was asked, and kept in the audit log (`after.override`, `after.blocking`). Use it only when the go/no-go
  meeting decided to launch with a known, accepted gap — and write the gap and its owner in the meeting's notes.
  Two rules no override lifts: the province is live, the market has a delivery zone.
- **Approving** sets the market `live` (region model re-read at once on this replica, within `REGION_CACHE_TTL` on the
  others) and shows the market's businesses hidden before launch (S-120 `pilot.market_launched`). The Provinces screen
  no longer raises a market to Live (409 `use_go_live`); it links here.
- **Rollback** ([§ Rollback](#rollback)) and **Hypercare** ([§ Hypercare](#hypercare)) are on the same screen once
  the market is live; the history lists launches, rollbacks and requests.

## Timeline: T-14 → T+14

T = launch day. Owners are roles ([§ Contacts](#contacts)).

| when | what | owner |
|---|---|---|
| T-14 | Launch date proposed. `make go-live-check ENV=prod MARKET=…` run once: the list of what's missing goes to each owner. Hypercare people asked for their availability | launch lead |
| T-14 | Real-world items started ([§ The real-world checklist](#the-real-world-checklist)): Stripe live mode, store submissions (review takes days), counsel's sign-off, pentest retest | each owner |
| T-10 | Load test against staging with the release candidate ([load-testing.md](load-testing.md)); e2e suite green on staging | SRE, QA |
| T-7 | Release candidate on staging; UAT sign-offs complete (S-121); pilot businesses all "waiting for the launch" | product, merchant success |
| T-7 | Merchant launch email prepared ([go-live/email-merchant-launch.md](go-live/email-merchant-launch.md)); support macros for launch questions | merchant success, support lead |
| T-5 | Production deploy of the release (market still `pilot`); smoke test by staff with direct links | SRE |
| T-3 | Every manual gate recorded in the console; on-call rota covers T-1 → T+14 | gate owners |
| T-2 | **Go/no-go meeting** ([§ The go/no-go meeting](#the-gono-go-meeting)) | launch lead |
| T-1 | Customer launch email scheduled ([go-live/email-customer-launch.md](go-live/email-customer-launch.md)), waitlist export ready; freeze: no deploy until T+2 except fixes | marketing, SRE |
| T | Launch-day run sheet ([§ Launch-day run sheet](#launch-day-run-sheet)) | launch lead |
| T → T+14 | Hypercare: daily stand-up, dashboard, rollback criteria watched ([§ Hypercare](#hypercare)) | hypercare rota |
| T+7 | Mid-point review: open issues, exit criteria trend | launch lead |
| T+14 | Exit review: hypercare ends when the exit criteria hold; retrospective written | launch lead |

## The go/no-go meeting

T-2, 30 minutes, the launch lead chairs; the owner of every gate attends or sends a written status.

1. Screen-share Console › Go-live for the market; go through the gates top to bottom. Every gate is **pass** or
   **not applicable**, or its owner states the gap, the risk and the date it closes.
2. Rollback readiness: the rollback criteria below are agreed; two admins are reachable on launch day; the hypercare
   rota is filled.
3. Decision: **go**, **go with an override** (the gaps written down, each with an owner and a date, and the override
   reason to type in the request), or **no-go** (a new date).
4. Notes: the decision, attendees, gaps; filed with the launch record (the console's history keeps the request).

## Launch-day run sheet

Times are the market's local time; the times are a template.

| time | step | who |
|---|---|---|
| 08:30 | Stand-up: rota on call, dashboards open (`northline-hypercare` with the market selected, `northline-overview`, `northline-slo-*`) | hypercare primary |
| 08:45 | `make go-live-check ENV=prod MARKET=… ` — exit 0, or only the gaps the go/no-go accepted | launch lead |
| 09:00 | Admin 1: Console › Go-live › **Request go-live** (with the override reason when the go/no-go decided so) | admin 1 |
| 09:05 | Admin 2: types the market's name, **Approve and go live**. Check: stage `live`; the Location screen at an address in the market delivers (no waitlist); the pilot board shows the businesses **Live** within a minute | admin 2 |
| 09:15 | Smoke: one real order by staff in each vertical (shop, booking, food) with a real card, refunded after; search finds the pilot businesses | QA, staff |
| 09:30 | Merchant launch email sent by merchant success; status post in the team channel | merchant success |
| 10:00 | Customer launch email / waitlist notification sent | marketing |
| 10:00 | Console › Go-live › **Plan hypercare** (if not done the day before) | launch lead |
| hourly | Rollback criteria checked on the dashboards; findings in the launch channel | hypercare primary |
| 17:00 | End-of-day review: numbers, issues, tomorrow's rota | launch lead |

## Rollback

**Criteria** — roll back when one holds and no fix is minutes away:

- checkout failing for more than 5 % of attempts over 15 minutes, or a paging SLO burn alert (checkout, sign-in,
  payouts) that the on-call can't stop within 30 minutes;
- money moving wrongly (a charge without an order, a payout to the wrong account, escrow not held);
- a security incident or personal data exposed;
- a legal or regulatory instruction to stop.

**Steps:**

1. The on-call primary declares it in the launch channel; an admin opens Console › Go-live › **Roll back to pilot**,
   writes the reason (10–500 characters, which criterion) and types the market's name. One admin is enough: speed
   matters more than a second pair of eyes here, and the audit log keeps it (`golive.rolled_back`,
   `region.stage_changed`).
2. What happens: the market is `pilot` again. **New public discovery stops** — an address in the market resolves to
   the waitlist again, the market is no longer the shop's fallback, and every active business of the market is hidden
   from search (cause `pilot`, no "you were hidden" email). **Nothing is cancelled**: orders, bookings and escrow in
   progress carry on and are fulfilled; customers with a direct link still reach the pages (as during the pilot);
   payouts run as scheduled.
3. Tell the businesses (merchant success) and, if customers were told of the launch, them (support's macro).
4. Fix, re-run `make go-live-check`, and launch again with a new two-person request. Launching shows the hidden
   businesses again.

Lowering a live market from the Provinces screen (S-84) has the same effect.

## Hypercare

**The rota.** Console › Go-live › **Plan hypercare** (admins, once the market is live): the first day (today by
default, up to 14 days ahead) and, in turn, the primary on-call people, the secondary ones and the business contacts.
Each of the 14 days gets a primary, a secondary (never the same person) and a business contact; the primary and the
secondary also get a shift on the on-call rota for that local day (`Hypercare · <market> · primary|secondary`), so the
paging tool reads them through the on-call export (S-113). Swaps happen on Console › On-call.

**The dashboard.** Grafana `northline-hypercare` (pick the market): the market's orders, bookings, payouts sent and
failed and support tickets (`northline_market_activity_total{market,kind}`), platform checkout errors, sign-in
failures, payouts, paging alerts and SLO budgets, and the pilot group's UAT feedback and blocking items
(`northline_uat_feedback_reported`, `northline_uat_blocking_open`).

**The daily stand-up** — 15 minutes, every morning of the 14 days: [go-live/daily-standup.md](go-live/daily-standup.md)
(en / fr template).

**Exit criteria** — hypercare ends at T+14 when all hold (else it is extended a week at a time):

- no paging alert in the last 72 hours and every SLO's 30-day budget above 25 %;
- no open blocking issue (UAT or support) older than 48 hours;
- checkout failures under 2 % per day for 5 days; no money incident open;
- payouts ran on schedule for two cycles;
- support tickets from the market at or under the pre-agreed weekly level;
- the business contacts report no unanswered business question.

**Comms templates:** [go-live/email-merchant-launch.md](go-live/email-merchant-launch.md) and
[go-live/email-customer-launch.md](go-live/email-customer-launch.md) (en and fr). Sent by people from the email
provider's console, not by the platform (CASL: the customer email goes only to people who joined the waitlist or
consented to news — S-108).

## Contacts

Placeholders until the people are named; keep this table and the console's on-call rota in sync.

| role | person | reach |
|---|---|---|
| launch lead | _TBD_ | _phone_, _email_ |
| admin 1 / admin 2 (the two-person switch) | _TBD_ / _TBD_ | _phone_ |
| platform / SRE on-call | the on-call rota | paging tool |
| payments (Stripe) | _TBD_ | _email_; Stripe support: the dashboard's support form |
| security | _TBD_ | _phone_ |
| legal counsel | _firm, TBD_ | _email_ |
| merchant success | _TBD_ | _phone_ |
| support lead | _TBD_ | _email_ |
| mobile (store releases) | _TBD_ | _email_ |
| localization (French-first markets) | _TBD_ | _email_ |

## The real-world checklist

What only people can do, before the first launch. Each item's gate closes when its owner records it.

| item | gate | runbook |
|---|---|---|
| Production built: cloud account, `terraform apply`, data stores, the chart deployed with every required variable | — (prerequisite) | [prod.md § Readiness checklist](prod.md#readiness-checklist), [infrastructure.md](infrastructure.md), [deploy.md](deploy.md) |
| Stripe platform account activated for live mode (Connect, Tax, Identity), live keys and restricted key in the secrets manager | `stripe_live` | [stripe.md § 2](stripe.md#2-platform-account-setup-once-per-mode) |
| Both Stripe webhook endpoints registered in live mode, signing secrets set, a test event delivered | `stripe_webhooks` | [stripe.md § 5](stripe.md#5-webhooks-s-12) |
| SAQ A answered against the official PDF and the AOC signed | `pci_saq_a` | [../compliance/pci/saq-a.md](../compliance/pci/saq-a.md) |
| Domain delegated, certificates issued, WAF in front | `dns_certs` | [edge.md](edge.md#dns-delegation) |
| Counsel's review of every legal text and the 51 questions; sign-offs recorded in the registry | `legal_signoff` | [../compliance/legal/review-packet.md](../compliance/legal/review-packet.md), [../compliance/legal/counsel-questions.md](../compliance/legal/counsel-questions.md) |
| External penetration test and retest of its findings | `pentest` | [../security/pentest-scope.md](../security/pentest-scope.md) |
| First cloud DR drill (point-in-time restore) recorded in the drill log | `backup_drill` | [backups-dr.md](backups-dr.md#roles-and-the-quarterly-drill) |
| Metrics store and paging tool connected; alert rules loaded; the on-call rota filled | `alert_rules`, `slo_alerts`, `oncall_coverage` | [alerting.md](alerting.md), [observability.md](observability.md) |
| Load test against staging at the target, e2e suite green on staging | `load_test`, `e2e_results` | [load-testing.md](load-testing.md); `docs/runbooks/e2e.md` (S-117) |
| Pilot businesses recruited and onboarded in production (real ID checks, kitchen visits, live Connect accounts) | `pilot_businesses` | [pilot-onboarding.md](pilot-onboarding.md) |
| UAT with the pilot group, sign-offs collected, blockers fixed | `uat_go_no_go` | [../uat/README.md](../uat/README.md) |
| App Store / Play submissions approved (optional for a web-first launch) | `app_stores` | [mobile-release.md](mobile-release.md#the-first-release-step-by-step) |
| French reviewed by a translator (French-first markets only) | `french_coverage` | [i18n.md](i18n.md#3-before-opening-a-french-first-place) |
| Email provider out of its sandbox, SPF/DKIM/DMARC; SMS provider live | — (prerequisite) | [email.md](email.md), [prod.md § Third-party accounts](prod.md#third-party-accounts) |
| Contacts above named; two admins with console access and a second factor | — | this page |

## Configuration

All optional, api (`northline.go-live.*`):

| variable | default | what |
|---|---|---|
| `GO_LIVE_MIN_PILOT_BUSINESSES` | `10` | pilot businesses ready before a market can launch (S-120's acceptance criterion) |
| `GO_LIVE_RECORD_MAX_AGE` | `P14D` | how long a manual record counts |
| `GO_LIVE_REQUEST_TTL` | `PT24H` | how long a launch request waits for its second admin |
| `GO_LIVE_PROMETHEUS_URL` / `_TOKEN` (secret) | the console's `CONSOLE_HEALTH_PROMETHEUS_URL` / `_TOKEN` | the Prometheus HTTP API whose `/api/v1/rules` the alert gates read; empty = recorded by hand |

## Rehearsal

`make go-live-rehearsal` (wrapped in `STACK_LOCK=` flock on a shared machine; `scripts/go-live/rehearsal.sh` and
`rehearsal.mjs`): S-120's pilot dry run on a throwaway database (12 fake businesses live in the region model's first
live market), then the api again on that database and, as production would: the market back to `pilot`, the go-live
check (manual gates pending, launch refused), the repository's results recorded by the script and the other manual
gates recorded by a second admin as **rehearsal stand-ins**, the on-call rota filled, the two-person switch, public
discovery checked, rollback, a second launch requested by the other admin, hypercare planned. Outputs in
`go-live-rehearsal/` (git-ignored).

REHEARSAL-RESULT
