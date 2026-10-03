---
title: "UAT dry run"
---

# UAT dry run (S-121, 2026-10-03)

The pilot group and its sign-off are people who don't exist yet, so the tooling and the process were rehearsed with a
fake pilot group before the real one starts. Nothing here involved a real participant, a real device or a deployed
environment. French summary at the end.

## What was run

| part | how | result |
|---|---|---|
| 1. Seed a fake pilot group | dev seed `db/seed-dev/V326__uat_pilot.sql` (local profile only): Prairie Wrench (provider), Prairie Wrench Parts (seller), Pho Dau Bo (kitchen), two customers (one French-speaking), a courier and Priya Natarajan (staff); six feedback items at every triage stage (new, triaged, accepted-blocking, fixed-blocking, closed, a duplicate), five sign-offs (one blocked) | `UatDevSeedTest` (api, profiles `test,local`): the console lists the seeded items with their history and merged duplicate; the go/no-go is **no go** with `blocking_open` and `blocking_unverified`; seeded people see the control, Ravi outside a pilot business doesn't — **pass** |
| 2. Walk the triage flow through the api | `UatApiTest.DryRun` on the real schema (Testcontainers PostGIS, every migration): a pilot customer is added by email, attaches a screenshot and reports a blocker; a second participant reports the same thing; support triages, accepts it as blocking with a note, assigns the support lead, links the tracker issue, merges the duplicate; the participant signs off **blocked** naming the item; fixed → verified → closed; the participant signs off; both CSVs, French included | every step answered as expected; the go/no-go listed the item as `new`, then `accepted` with 2 reports, then `fixed`, then not at all; 7 audit rows for the item — **pass** |
| 3. Walk the triage flow in the console | `web/apps/console/src/features/uat/uat.test.tsx` (Vitest + Testing Library, the real route tree, a fake of the api's flow): open the queue, open the item, move it new → triaged → (the 422 when blocking isn't chosen) → accepted (blocking) → owner → tracker link → fixed → verified → closed; merge a duplicate; record a sign-off; read the go/no-go in English and French; axe on each view; the console's own "Send feedback" control for a staff participant | **pass** (6 tests) |
| 4. The feedback control | `@northline/ui` `PilotFeedback.test.tsx` (category, severity, text, screenshot type and size checks, the server's refusal shown, the route without its query string, en/fr, axe), the Studio's, the consumer site's and the consumer app's controls (`pilot.test.tsx` in each) | **pass** |

The Playwright suite (S-117, `web/e2e`) is not on main yet (PR #154), so the browser-level walk is the Testing Library
test above, as the story allows. When #154 merges, a `6-uat-feedback.spec.ts` journey can reuse its personas: the
seeded customer sends feedback from the consumer site and Priya N. triages it in the console.

Repeat it:

```sh
cd server && ./gradlew :api:test --tests '*UatApiTest' --tests '*UatDevSeedTest' --tests '*UatRulesTest'
cd web && pnpm --filter @northline/console exec vitest run src/features/uat
cd web && pnpm --filter @northline/ui exec vitest run --project dom src/PilotFeedback.test.tsx
cd mobile/apps/consumer && pnpm exec jest __tests__/pilot.test.tsx
```

By hand, with the local stack (`make up SERVICES="api console"`, dev auth as Priya N.): open **Pilot UAT** — the seeded
queue, participants and the go/no-go are there; move UAT-1001 to fixed and verified and watch the report.

## Findings

No blocking issue in the tooling. Observations taken into the process (README):

1. **A participant's "blocker" holds the launch until someone triages it.** Intended: nobody should be able to reach
   "go" by not reading the queue. Triage daily.
2. **The staff persona needs its own participants.** Coverage is per persona; with no staff participant the report
   says so. Add the console staff who will work the launch.
3. **Couriers have no in-app control yet** (the courier app is out of S-121's scope). Their script says to tell the
   pilot contact; staff log it in the queue on their behalf — the item then carries the staff member as sender. A
   courier-app control is a follow-up.
4. **The consumer app sends no screenshot** (it would need a native screen-capture module and a new store build);
   participants describe the screen, and the app sends the screen's path.
5. **Script versions:** the sign-off records the version run; after a script change the report still counts older
   sign-offs. The support lead decides whether to ask again (README § Scripts).

## Résumé en français

L’essai à blanc a été fait avec un faux groupe pilote (semence de développement V326), un parcours complet par l’api
(signalement avec capture d’écran, doublon fusionné, tri jusqu’à la fermeture, approbations, CSV en anglais et en
français) et le même parcours dans la console (tests Testing Library). Tout a réussi. Aucun problème bloquant dans
l’outillage; les observations ci-dessus (tri quotidien, participants du personnel, pas de bouton dans l’application
des livreurs ni de capture dans l’application client) sont reprises dans le déroulement.
