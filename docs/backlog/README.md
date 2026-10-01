# Backlog

`northline-backlog.csv` is the full remaining-work backlog after the Studio build: 14 epics, 139 stories, 796 story points, laid out over 20 two-week sprints (5 Oct 2026 → 9 Jul 2027).

## Importing into Jira
Jira → *Settings › System › External system import › CSV* (Cloud: *Import issues from CSV*). Map the columns:

| CSV column | Jira field |
|---|---|
| Issue Id | Issue ID (used only to link stories to epics) |
| Parent Id | Parent ID (links each story to its epic) |
| Issue Type | Issue Type (Epic, Story) |
| Summary, Description, Priority | same |
| Epic Name | Epic Name (Jira Server/Data Center; ignore on Cloud) |
| Story Points | Story Points (or Story point estimate on team-managed projects) |
| Labels (×3) | Labels |
| Component/s | Component/s (create the components first, or let the importer create them) |
| Sprint | Sprint (optional; the sprints must exist, or leave unmapped and plan in the board) |
| Start date, Due date | Start date, Due date (date format `yyyy-MM-dd`) |

## How the timeline was built
- **Velocity:** assumed 40 points per two-week sprint (a small team of about 4 engineers). Change the velocity and the dates move. The generator script is kept outside the repo; ask to regenerate with a different velocity or start date.
- **Order:** by phase, then priority, then epic.
  1. Infrastructure, auth hardening, production adapters, events.
  2. Studio follow-ups, search, consumer web.
  3. Console, fulfilment.
  4. Mobile app.
  5. Security and observability.
  6. Pilot and Calgary launch.
- **Stable ids:** regenerating keeps every existing Issue Id; new stories get the next free number (e.g. S-139).
- **Holidays:** no allowance is made for holidays. Expect the late-December sprint to slip.
- **Sources:** stories come from the gaps recorded in `docs/DECISIONS.md`, the screen inventory in `docs/SCREENS.md` (consumer web, mobile app, console) and the architecture in `docs/ARCHITECTURE.md`.

## Platform decisions reflected in the backlog (2026-09-29)
- **Portable, multi-cloud:** deployable to AWS, Google Cloud and Azure (startup credits). Kubernetes + Helm everywhere; Terraform modules share one interface with a per-cloud implementation; storage, KMS, secrets and email sit behind ports selected by configuration. Canadian regions only.
- **Profiles:** `local` runs against your own Postgres/PostGIS, Valkey and local stand-ins (MinIO, Mailpit, Kafka, Elasticsearch, stripe-mock). Every environment has a runbook in `docs/runbooks/` listing what it needs.
- **CI on GitHub Actions and GitLab CI**, both **manual trigger only** until CI credits are available.

## Added 2026-09-30: developer experience, API docs, observability and AI (E-14)
- New stories: S-124 Makefiles, S-125 OpenAPI with Swagger UI/Scalar/Redoc, S-126 Docusaurus site, S-127 built-in MCP server (OAuth 2.1), S-128 docs MCP.
- Pulled forward from phase 5: S-111 OpenTelemetry tracing and metrics, S-112 centralised logging with PII redaction (now in E-14, phase 2).
- The epic is appended last so existing epic ids (E-1…E-13) stay stable.
- AI (added 2026-09-30, user decision): OpenRouter behind an `LlmClient` port on the Spring AI stack, following billionairedev24/samop-inv-ship-26. S-129 AI platform, S-130 Studio assistant, S-131 writing help, S-132 consumer natural-language search and triage, S-133 trust & safety assist. S-127 (MCP) uses springdoc's OpenAPI-to-MCP tools on Spring AI's MCP server, as samop does.
- Region-neutral (added 2026-09-30, user direction): Northline starts in Alberta but is built for every province. S-134 moves provinces and markets into configuration and removes hardcoded Alberta/Calgary/Edmonton from code and messages.
- Follow-ups found while merging phase 2 (2026-10-01): S-135 BFF relay race, S-136 indexer refresh before stale lookup, S-137 calendar disconnect deadlock, S-138 docs OAuth client, S-139 Error Prone -Werror.
