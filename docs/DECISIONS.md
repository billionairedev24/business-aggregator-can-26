# Decisions log

Record anything the spec did not decide. Format: date · decision · why · spec reference.

- 2026-09-08 · Enums stored as `text + CHECK` in V0xx; promote to native enums after schema stabilises · keeps early migrations cheap · DATA_MODEL.md
- 2026-09-08 · Cross-module references are logical (no FK) to preserve module extractability · ARCHITECTURE.md (Spring Modulith)


## 2026-09-27 — v2 redesign + stack change
- Visual system replaced: Broadsheet → Northline "Spruce & Honey" (design/theme/northline.css, web/packages/tokens).
- Java 25 + Spring Boot 4.1.1 replaces Java 21 / Boot 3. Go services dropped — tracking runs in Java (SSE + Redis pub/sub) on virtual threads.
- Keycloak → Spring Authorization Server (we own the login UX, passkeys, "Not you?").
- OpenSearch → Elasticsearch 9. Debezium removed — Spring Modulith event publication registry is the outbox and externalizes to Kafka.
- Starter artifact names follow Boot 4's modular starters. Verify exact names/versions on start.spring.io at implementation time and record changes here.
- Orders moved from top nav to account menu; location auto-detected (browser geolocation → /api/v1/geo/reverse) with saved-address fallback; Shop/Services/Food are landing pages.

## 2026-09-29 — Data Table (web/packages/ui/src/DataTable)
- Money columns (`type: 'money'`) hold CAD cents and render with `formatMoney`; range filters take dollars. `num` columns also parse legacy strings like "$1,912.40". · platform money convention · SCREENS.md § Data Table
- Excel export is a real .xlsx (Office Open XML in a stored ZIP written in-house, no dependency); PDF is a print-styled HTML tab that calls `print()`. · avoids a heavy dependency · Data Table.dc.html
- Mutations are caller-owned async callbacks (`onCreate/onUpdate/onDelete/onAction`). A CRUD control shows only when the role permits it AND a handler (or `onCreateClick`/`onEditClick` navigation) is supplied. The design's local `set`/`call` action fields are replaced by `onAction(action, rows)`. · no fake persistence in shared components
- Hit targets: controls keep the design's visual size (32–40px) with an invisible ≥ 44px hit area (pseudo-element), so the layout stays pixel-faithful. Table cells use `overflow-wrap: break-word` (design: `anywhere`) so words are not split mid-word; column auto-hide still prevents horizontal scroll.
- French create/edit titles use "Ajouter · {entity}" / "Modifier · {entity}" to avoid grammatical gender on caller-supplied nouns; callers can pass `createLabel`.
