# Workstream brief (read before starting a Studio feature)

You own one slice of the Studio, full stack: backend module(s) in `server/api` + screens in `web/apps/studio`. Several workstreams run in parallel in separate git worktrees and are merged afterwards, so stay inside your slice and follow the shared conventions exactly.

## Read first
1. `CLAUDE.md` (source-of-truth order, stack, non-negotiables) · `docs/IMPLEMENTATION_PLAN.md` (layout, frontend conventions, contracts, migration + seed ranges) · `docs/BACKEND_CONVENTIONS.md` (how to add an endpoint, guard it, return 422s, test it) · `docs/SCREENS.md` · `docs/spec/validation-rules.md` · `docs/ARCHITECTURE.md` § Code standards.
2. Your design sections in `design/02 Provider Studio.dc.html` — read the template lines AND the matching logic/data in the `<script type="text/x-dc">` block at the bottom (grep for the variables your template uses). It is the behavioural and visual spec: copy text exactly, reproduce every state, every portal variant (provider / seller / kitchen / both) and every interaction. Recreate it in React with the UI kit; don't port the prototype's structure.
3. Your tables in `db/migrations/*.sql` and `docs/DATA_MODEL.md`; relevant rules in `docs/spec/*`.
4. Existing code: `web/packages/ui/src` (UI kit + `DataTable`), `web/apps/studio/src` (shell, `lib/http.ts`, `lib/forms.ts`, `features/shell/api.ts`), `server/api/src/main/java/ca/northline/**` (reference slice from the backend foundation).

## Definition of done
- **Backend:** endpoints under `/api/v1/merchants/{merchantId}/…` guarded by merchant membership + role permissions + `acr=mfa` (MerchantAccess); layering per ARCHITECTURE.md code standards (web adapter → use-case interface → domain → port → adapter; Lombok; records; no boilerplate); Bean Validation with the exact messages; 422 format; domain events published in the transaction, `@Externalized` where other systems care; external systems (Stripe, Google, SMS, email, storage) behind ports with a local/fake adapter under the `local` and `test` profiles; a `NavBadgeContributor` for your screens' sidebar badges if the design shows one. Integration tests (Testcontainers Postgres via the foundation's base class) for happy path, authorization (non-member 403, wrong role 403), every validation message, and every DB trigger you touch. `ModularityTests` passes. Migrations only in your version range, additive only; dev seed in your `db/seed-dev/V1xx` file so the screens show the design's data under the `local` profile.
- **Frontend:** replace the `ScreenPending` component of your routes with real screens in `src/features/<feature>/` (api.ts with zod schemas + queryOptions + mutations; messages.ts with en + fr-CA for every string; components). Every screen: design-faithful layout and copy, loading skeleton, empty state, error state with retry, optimistic or pending feedback on mutations, server 422s mapped to fields, validation shown after touch or submit + "N things need attention." summary where the design has forms, role-gated controls (owner / staff / bookkeeper as the design says), no horizontal scroll ≥ 320 px, keyboard + screen-reader accessible. Record lists use `DataTable` from `@northline/ui`. New generic components go into `packages/ui` with a story; screen-specific ones stay in your feature folder. vitest + Testing Library tests for the important interactions and validation.
- **Checks green before you finish:** `cd server && ./gradlew build` (JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64) · `cd web && pnpm -r typecheck && pnpm -r test && pnpm --filter @northline/studio build`.
- **Record** every decision the spec didn't make, and every schema addition, in `docs/DECISIONS.md` (append a section headed with your workstream name).
- **Commit** on your worktree branch (small logical commits) with messages ending in:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01RAZmx8zqf6CFFkbqr6aa2w
  ```
  Do not push. Do not edit other workstreams' routes/features/modules; if you need something from another slice, code against the contract in IMPLEMENTATION_PLAN.md and note it in your final report.

## Environment
- JDK 25: `/usr/lib/jvm/java-25-openjdk-amd64`. Use `./gradlew` in `server/`. Keep Gradle workers ≤ 2 (4 CPUs are shared by 4 parallel workstreams).
- Shared Postgres (PostGIS 17) at `localhost:5432`, `northline`/`northline`. Create your own databases (e.g. `nl_<workstream>`); never drop databases you didn't create. Docker is available for Testcontainers.
- `cd web && pnpm install` first (store is warm). Run the studio with `NL_DEV_USER=<seeded owner id> pnpm --filter @northline/studio dev` against `./gradlew :api:bootRun --args='--spring.profiles.active=local'` if you want to click through.

## Final report (your last message)
What you built (endpoints, screens, components), test results, schema additions, decisions, anything unfinished or stubbed, and any contract another workstream must honour.
