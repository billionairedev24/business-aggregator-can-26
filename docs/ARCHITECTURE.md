# Architecture (v2)

Interactive version: `design/05 Architecture.dc.html`.

## Deployables
| App | Tech | Role |
|---|---|---|
| server/auth | Spring Authorization Server (Spring Security 7) | OAuth 2.1 + OIDC issuer, passkeys, TOTP, Google/Apple federation |
| server/bff | Spring Cloud Gateway (MVC) + OAuth2 client | Web session (Redis), token relay to api |
| server/api | Spring Boot 4.1.1 + Spring Modulith 2 | All business modules, REST /api/v1 |
| server/worker | Spring Kafka | Search indexing, notifications, partner webhooks, payouts |
| web/apps/consumer | TanStack Start | Consumer site (SSR) |
| web/apps/studio, web/apps/console | TanStack Router SPA | Business studio, platform console |

## Modules (server/api, package ca.northline.<module>, schema <module>)
identity · region · merchants · catalogue · food · availability · booking · orders · fulfilment · payments · trust · messaging · search (projection only) · shared (kernel). Boundaries verified by `ApplicationModules.verify()`.

## Event management
1. Aggregate changes state and publishes a `DomainEvent` in the same transaction.
2. Modulith JDBC registry writes the publication row (outbox) — `events.event_publication`.
3. `@ApplicationModuleListener` handlers run async after commit, each in its own transaction; incomplete publications are resubmitted on restart.
4. `@Externalized` events go to Kafka topic `<module>.<aggregate>` keyed by aggregate id (per-aggregate ordering).
5. Consumers dedupe on event id (`events.processed_events`), retry with backoff, then `.dlq`.
6. Envelope: id (ULID), type, version, occurredAt, aggregate, tenant, actor, traceId, data. Schemas in `server/api/src/main/resources/events`. No PII beyond ids.

Key events: user.registered · merchant.submitted/verified · storefront.published · listing.published · menu.published · quote.requested/sent/accepted · booking.completed · order.placed/delivered · delivery.assigned/completed · payment.authorized · escrow.released · refund.issued · dispute.decided · review.posted.

## Identity
Clients: consumer-bff, studio-bff, console-bff (confidential, PKCE), mobile-consumer and courier-app (public, PKCE, rotating refresh, DPoP), partner:* (client credentials, private_key_jwt). Access tokens JWT ES256 10 min; claims sub, scope, roles, merchants, acr. Business + staff require acr=mfa (passkey or TOTP). Browsers only hold an HttpOnly session cookie.

## Data stores
- **PostgreSQL 17 + PostGIS** — system of record, outbox, auth tables, RLS by tenant.
- **Elasticsearch 9** — listings_en / listings_fr (service, product, dish, merchant), geo sort, completion suggester. Rebuildable from Kafka; api never writes to it directly.
- **Redis 8** — BFF sessions, caches (evicted by events), rate limits, idempotency keys, slot holds (10 min TTL), order tracking pub/sub.

## Front end
TanStack Start/Router/Query/Form/Table/Virtual; Storybook 9 with a11y + interaction tests; `@northline/tokens` generated from tokens.json. Theme = five base tokens; everything else derived.

## Code standards (mandatory — PRs that break these are rejected)
- **Java 25:** records for DTOs/commands/events/value objects; sealed interfaces + pattern-matching `switch`; record patterns; unnamed variables `_`; `var`; text blocks; `SequencedCollection`; `Stream.toList()` and gatherers; `ScopedValue` instead of `ThreadLocal`; virtual threads (no hand-rolled pools); flexible constructor bodies; module import declarations. No pre-Java-17 idioms.
- **Encapsulation:** private final fields by default; aggregates guard invariants and expose behaviour (`order.cancel(reason)`), not setters; no public mutable state; return unmodifiable views. Only a module's `api` package is public — internals are package-private (Modulith-verified).
- **Abstraction:** web adapter → application service → domain → port. Controllers never touch repositories; domain code imports no Spring/JPA/Kafka types. Model each concept once (`Money`, `Address`, `TimeSlot`).
- **Interfaces:** inbound use cases (`PlaceOrder`) and outbound ports (`PaymentGateway`, `Clock`, `Notifier`) are interfaces, implemented by adapters. Constructor injection only. Small single-purpose interfaces; no interface for a single implementation that never needs a test double.
- **Lombok:** `@RequiredArgsConstructor`, `@Getter`, `@Builder`/`@With`, `@Value`, `@Slf4j`; `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` on JPA entities. Never `@Data` on entities, no `@Setter` on aggregates. `lombok.config`: `lombok.addLombokGeneratedAnnotation = true`, `config.stopBubbling = true`.
- **No boilerplate:** never hand-write getters/setters/constructors/equals/hashCode/toString/builders. MapStruct for mapping, Bean Validation for validation, `@HttpExchange` interfaces for HTTP clients, `@ConfigurationProperties` records for config, Spring Data derived queries before hand-written SQL.
- **Go 1.26 (any Go app):** `go 1.26` in go.mod with pinned toolchain; generics, range-over-func iterators, `slices`/`maps`/`iter`, `log/slog`, `errors.Join` + `%w`, `context` everywhere, `ServeMux` method+path patterns. Small consumer-side interfaces (accept interfaces, return structs), unexported fields + constructors, no global state.
- **Enforced in CI:** Checkstyle, Error Prone + NullAway (JSpecify), ArchUnit layering/interface rules, Modulith verify, Spotless; Go: golangci-lint + gofumpt + staticcheck.

## Flows
Sign-in via BFF + PKCE; itemized quote → deposit escrow → booking; pooled shop order → dispatch → SSE tracking; search projection via Kafka → Elasticsearch. Step-by-step in design/05.
