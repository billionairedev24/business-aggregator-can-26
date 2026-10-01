# Backend conventions (server/)

Written by the backend foundation. The **reference slice is `ca.northline.merchants`** (`GET /api/v1/me/businesses`,
`GET/PATCH /api/v1/merchants/{merchantId}`) — when in doubt, copy it. Code standards in `ARCHITECTURE.md` are mandatory.

## 1. Build, test, run

Always `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` (or any JDK 25). From `server/`:

| what | command |
|---|---|
| full build (compile, Error Prone/NullAway, Checkstyle, Spotless check, tests, boot jars) | `./gradlew build` |
| format | `./gradlew spotlessApply` (run before committing; `build` fails on unformatted code) |
| api tests only | `./gradlew :api:test` (needs Docker — one shared PostGIS Testcontainer per JVM) |
| one test | `./gradlew :api:test --tests '*MerchantApiTest'` |
| run locally | `./gradlew :api:bootRun --args='--spring.profiles.active=local'` |
| migrate a DB | `./gradlew :api:flywayMigrate [-Pdb.url=jdbc:postgresql://localhost:5432/northline -Pdb.user=northline -Pdb.password=northline] [-Pdb.devSeed=true]` |
| migration status | `./gradlew :api:flywayInfo [-Pdb.url=…]` |
| seed categories | `./gradlew :api:seedCategories [-Pdb.url=…]` (idempotent upsert of `db/seed/categories.json`) |

`local` profile = Postgres only. No auth server, Kafka, Elasticsearch or Redis needed. It applies `db/seed-dev`
(V100–V109) on top of the migrations, turns Kafka externalization off and enables **dev auth** (below). Point it at
another database with `DB_URL=jdbc:postgresql://localhost:5432/<db> DB_USER=… DB_PASSWORD=…` (environment or
`server/.env`, see `server/.env.example`), and change the port with `SERVER_PORT`. Profiles `dev`/`staging`/`prod`
and every variable: `docs/runbooks/README.md`. Categories are not seeded automatically: run `seedCategories` against the same DB once.

### Dev auth (profile `local` only)
Send `X-Dev-User: <identity.users id>`. The api mints an in-memory token with `scope=openid profile merchant`,
`acr=mfa` and `merchants` taken from `merchant_members`. Add `X-Dev-Acr: pwd` to simulate a single-factor sign-in.
A real `Authorization: Bearer` header always wins. It logs a banner at startup and can never be active without the
`local` profile.

| persona (design 02) | id | memberships |
|---|---|---|
| Ravi Sandhu | `01J9ZD3V00000000000000RAV1` | owner of all three businesses |
| Jas Gill | `01J9ZD3V000000000000000JAS` | technician, Prairie Wrench |
| Priya Sandhu | `01J9ZD3V00000000000000PR1Y` | bookkeeper, Prairie Wrench + Parts |

| business | id | type · tier |
|---|---|---|
| Prairie Wrench | `01J9ZD3V00000000000000PWM1` | provider · master |
| Prairie Wrench Parts | `01J9ZD3V00000000000000PWP1` | seller · trusted |
| Pho Dau Bo | `01J9ZD3V00000000000000PDB1` | kitchen · trusted |

```sh
curl -H 'X-Dev-User: 01J9ZD3V00000000000000RAV1' localhost:8080/api/v1/me/businesses
```

## 2. Package layout per module

```
ca.northline.<module>
  package-info.java   @ApplicationModule(displayName = "<module>") + @NullMarked
  api/                PUBLIC (@NamedInterface("api")): events + types other modules may use. Nothing else is public.
  web/                @RestController adapters, request/response records, MapStruct web mappers
  application/        use-case interfaces (inbound ports, e.g. RenameMerchant), @Service implementations
                      (package-private), outbound port interfaces (MerchantRepository), read models
  domain/             aggregates, value objects, enums, rules — plain Java + Lombok, NO Spring/Jackson/JPA
  persistence/        Spring Data JDBC @Table rows + repositories, JdbcClient queries, adapters implementing ports
```

- Other modules may only use `<module>.api` (and `shared`, `shared.security`). Modulith verifies it
  (`ModularityTests`). Cross-module reactions go through events (`@ApplicationModuleListener`), not calls into internals.
- ArchUnit (`ArchitectureTests`) enforces: domain has no Spring/adapter deps; application doesn't see web/persistence;
  controllers never touch repositories; `@RestController` only in `web`, `@Table` only in `persistence`; no `@Autowired` fields.
- `SchemaOwnershipTests` (S-37) fails any class whose SQL strings name another module's schema (`merchants.…` outside
  `ca.northline.merchants`). Read other modules through their `api` package. For merchants that means `MerchantDirectory`,
  `MerchantVerifications` and `TeamRoster`. When the module you need already depends on yours, declare the query in your
  `api` package and let that module implement it (`payments.api.MerchantBillingFacts`, `merchants.api.CategorySource`).
  No exceptions remain since S-64 (`food.api.KitchenOrderFeed`, implemented by orders); a new one needs its reason in
  the test's `ALLOWED` map and a DECISIONS line.
- Every package has a `package-info.java` with `@NullMarked`. NullAway treats `ca.northline` as non-null by default —
  mark nullable things `@org.jspecify.annotations.Nullable`.
- Shared kernel `ca.northline.shared`: `Ids` (ULID), `Money`, `CodedEnum`/`CodedEnums`, `DomainEvent`, `ListResponse`,
  error types `RuleViolation`, `NotFound`, `Conflict`. `shared.security`: `CurrentUser`, `CurrentMember`,
  `@RequiresMerchant`, `MerchantPermission`, `MerchantRole`, `MerchantAccess`.

## 3. Naming

| thing | convention | example |
|---|---|---|
| use case (inbound port) | verb phrase interface, one method | `RenameMerchant.rename(Command)`, `ListMyBusinesses.of(userId)` |
| command | nested `record Command(...)` in the use case | `RenameMerchant.Command` |
| service | `<Noun>Service`, package-private, implements 1..n use cases | `MerchantProfileService` |
| outbound port | noun interface in `application` | `MerchantRepository`, `BusinessDirectory`, `PaymentGateway` |
| adapter | `<Tech/Noun>Adapter` or `<Noun>Queries` in `persistence` | `MerchantPersistenceAdapter` |
| row / Spring Data repo | `<Aggregate>Row` record, `<Aggregate>RowRepository` | `MerchantRow` |
| web DTOs | `<Thing>Request` / `<Thing>Response` records in `web` | `UpdateMerchantRequest` |
| event | past tense record in `api`, implements `DomainEvent` | `MerchantRenamed` → `merchant.renamed` |
| enum mapped to a DB `text + CHECK` | UPPER_CASE constants, `implements CodedEnum` | `MerchantType.PROVIDER` ↔ `"provider"` |

JSON: camelCase, ids = ULID strings, money `…Cents` longs, instants ISO-8601, enums lower-case codes
(`CodedEnum` does it via `@JsonValue`). Collections are always `{"items": [...]}` (`ListResponse`).
Time: inject `java.time.Clock` (the only source of "now"); never `Instant.now()`.
Ids: `Ids.next()`.

## 4. Add an endpoint (merchant-scoped)

```java
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/quotes")
@RequiredArgsConstructor
class QuoteController {
    private final SendQuote sendQuote;          // use-case interface, never a repository
    private final QuoteWebMapper mapper;         // MapStruct, @Mapper (Spring component model by default)

    @PostMapping("/{quoteId}/send")
    @RequiresMerchant(MerchantPermission.EDIT)   // REQUIRED on every {merchantId} handler
    QuoteResponse send(@PathVariable String merchantId, @PathVariable String quoteId,
                       @Valid @RequestBody SendQuoteRequest body, CurrentMember member) {
        return mapper.toResponse(sendQuote.send(new SendQuote.Command(merchantId, quoteId, member.userId())));
    }
}
```

- The path variable **must be named `merchantId`**. `MerchantAccessInterceptor` reads it, checks that the caller is a
  member whose role grants the permission **and** that the token has `acr=mfa`. Otherwise it returns 403 ProblemDetail
  with `code` = `mfa_required` | `not_a_member` | `insufficient_role`. A `{merchantId}` handler without
  `@RequiresMerchant` is denied at runtime and fails `MerchantScopedEndpointsTest`.
- Membership comes from `merchants.merchant_members` on every request (not from the token), so a removed team
  member loses access at once.
- Permissions (`MerchantPermission`): `VIEW` (all roles) · `OPERATE` and `EDIT` (owner, technician, cook) · `DELETE`,
  `MANAGE` (owner) · `FINANCE_READ` (owner, bookkeeper). Pick the narrowest. Need finer rules (for example, a
  technician sees only their own jobs)? Take a `CurrentMember member` parameter and pass `member.role()` or
  `member.userId()` into the command.
- Non-merchant endpoints: take a `CurrentUser user` parameter (`userId`, `scopes`, `roles`, `mfa`). `/api/v1/merchants/**`
  also needs the `merchant` scope at filter level; `/api/v1/console/**` needs role `STAFF`.
- Programmatic check (for example, in a listener): inject `MerchantAccess` and call `require(merchantId, permission)`.
  In SpEL use `@merchantAccess.has(#merchantId, 'EDIT')`.

## 5. Validation and errors

| situation | how | response |
|---|---|---|
| request shape and field rules | Bean Validation on the request record + `@Valid` | **422** `{"errors":[{"field","rule","message"}]}` |
| rules that need the domain (trimmed length, totals, state) | `throw RuleViolation.of("field", "rule", "message")` (domain or service) | **422**, same body |
| missing aggregate | `throw new NotFound("quote", id)` | 404 ProblemDetail |
| state conflict | `throw new Conflict("quote_already_sent", "…")` | 409 ProblemDetail with `code` |
| optimistic lock / unique key / DB CHECK or trigger | thrown by Spring | 409 (`stale` / `duplicate` / `constraint_violation`) |

- Use the **exact messages** from `docs/spec/validation-rules.md`. Keep them as constants next to the value object and
  reference them from the annotations, as `DisplayName` and `UpdateMerchantRequest` do:
  ```java
  record UpdateMerchantRequest(
      @NotBlank(message = DisplayName.REQUIRED)
      @Size(min = DisplayName.MIN, message = DisplayName.TOO_SHORT)
      @Size(max = DisplayName.MAX, message = DisplayName.TOO_LONG) String displayName) {}
  ```
- `field` is the JSON property path (`displayName`, `lines[2].amount`). The error list holds **one error per field**,
  the most basic failing rule first. Rule ids come from the constraint: `NotNull/NotBlank/NotEmpty/AssertTrue` → `required`,
  `Pattern/Email` → `format`, `Size/Length` → `length`, `Min/Max/Positive…` → `range`. A custom constraint `@GstNumber`
  gets the snake_cased name `gst_number`.
- The DB triggers in V016 stay as the last line of defence. Validate before writing so users see 422 messages, not a 409.
- **Nullness of request records.** A component that Bean Validation requires (`@NotNull`) is declared **non-null**
  for NullAway: no `@Nullable` next to it, because the two contradict each other (Error Prone
  `MultipleNullnessAnnotations`). A missing JSON field still deserializes to `null`, and `@Valid` turns it into the
  422 with the exact message before the handler runs, so the handler uses the value directly (no `requireNonNull`).
  The record must therefore never dereference such a component before validation: no compact-constructor checks and
  no methods called before `@Valid` (put those rules in the domain). Optional components are `@Nullable` with no
  `@NotNull`. `@NotBlank`/`@NotEmpty` are not nullness annotations and may sit next to `@Nullable`
  (`PayoutRequests`, `CaseRequests`).

## 6. Integration tests

```java
class QuoteApiTest extends IntegrationTest {           // ca.northline.support
    @Test void ownerSendsQuote() throws Exception {
        var biz = data.business(MerchantRole.OWNER);      // fresh ULIDs: merchant + member rows
        mvc.perform(post("/api/v1/merchants/{id}/quotes/{q}/send", biz.merchantId(), q)
                .with(TestJwt.member(biz.userId())))    // scope merchant + acr=mfa
           .andExpect(status().isOk());
    }
}
```

- `IntegrationTest` gives you the full context, MockMvc, profile `test` and the **single shared PostGIS container**
  (`SharedPostgres`, wired via `@ServiceConnection`). Flyway runs `db/migration` once per context.
- The database is shared by every test class and never wiped, so always create your own rows (`TestData`, or your
  own fixture). Don't assert on global counts.
- Tokens: `TestJwt.member(userId)`, `TestJwt.memberWithoutMfa(userId)`, `TestJwt.customer(userId)`. They produce the same
  authorities as real tokens (`NorthlineJwtConverter.authorities`).
- Every guarded endpoint needs at least: a happy path, 403 for a non-member, 403 without MFA, and a 422 **message**
  test for each validation rule (see `MerchantApiTest`).
- Events: add `@RecordApplicationEvents` and inject `ApplicationEvents` (see `MerchantApiTest.Rename`). To check
  Kafka routing, see `MerchantEventsExternalizationTest` (mocked `KafkaTemplate<?, ?>`, externalization on).
- Each new combination of `@MockitoBean`, `@TestPropertySource` or profiles starts another Spring context. Reuse
  combinations where you can.
- Catalogue data in a test: `new CategorySeeder(dataSource).seed()` (the `tools` source set is on the test classpath).

## 7. Migrations

- Files go in `db/migrations/V0NN__snake_case.sql`. Use **only your workstream's range** (see IMPLEMENTATION_PLAN.md:
  V020–V029 auth, V030–V039 merchants onboarding, V040–V049 operations, V050–V059 catalogue, V060–V069 finance,
  V070–V079 messaging, V080–V089 storefront/settings, V090–V099 kitchen). Dev seed rows go in
  `db/seed-dev/V10N__…sql` (V101–V109; V100 = personas).
- V001–V017 are final: never rename columns or change enum values, and never edit an applied migration. Add a new one.
- Build packaging copies the folders onto the api classpath (`classpath:db/migration`, `classpath:db/seed-dev`,
  `classpath:db/seed`). The app, the tests and `:api:flywayMigrate` therefore always see the same files. Override
  with `SPRING_FLYWAY_LOCATIONS=filesystem:…` if you must.
- Enum columns: `text CHECK (col IN (...))`. The Java enum implements `CodedEnum`. Row records keep the column as `String`
  and the MapStruct row mapper uses `uses = CodedEnums.class`.
- Cross-module references are logical (no FK). Add indexes for the queries you write.

## 8. Persistence

- Aggregates: a Spring Data JDBC `@Table(schema = "<module>", name = "...")` record row + `ListCrudRepository`, mapped
  to the domain by a MapStruct mapper (`MerchantRowMapper`). The adapter implements the application port.
  `save()` of a row with an id issues UPDATE of the **mapped columns only**. For INSERT of assigned ULIDs use
  `@Version`/`Persistable` or `JdbcAggregateTemplate.insert`.
- Read models and joins: `JdbcClient` with named parameters in a `…Queries` class (`MembershipQueries`).
- Try Spring Data derived queries first, and write SQL only where they don't fit.

## 9. Events

```java
@Externalized("<module>.<aggregate>::#{aggregateId()}")   // Kafka topic + key (per-aggregate ordering)
public record QuoteSent(String eventId, Instant occurredAt, String aggregateId, String actorId, ...)
        implements DomainEvent {}
```

1. The aggregate method returns the event (`merchant.rename(...)` returns `Optional<MerchantRenamed>`). The
   `@Transactional` service saves it and then calls `ApplicationEventPublisher.publishEvent(event)` **in the same transaction**.
2. The Modulith JDBC registry (`events.event_publication`, completed rows archived to `…_archive`) is the outbox.
   After commit, the Kafka externalizer sends to the topic. The topic must be in `deploy/kafka/topics.yaml`
   (`ExternalizedTopicsCatalogueTest` fails otherwise); every environment creates it and its `.dlq` from there (S-25).
3. Other modules react with `@ApplicationModuleListener void on(QuoteSent e)`. It runs async in its own transaction
   and is retried from the registry, so make it idempotent on `eventId`.
4. Payloads carry ids, never PII. Add `server/api/src/main/resources/events/<module>.<event>.v1.schema.json`. A breaking
   change means a version bump (`@Override int version()` plus a new schema file).
   `:event-contracts` (S-34, part of `./gradlew build`) checks the schemas against the records and against `main`
   (docs/runbooks/events.md § 7).
5. Under `local` and `test` externalization is off (no Kafka). Module listeners still work.

## 10. Code style

- Lombok: `@RequiredArgsConstructor` for injection, `@Getter`, `@Builder`, `@Slf4j`; no `@Data`, no `@Setter`
  (Checkstyle blocks both). `lombok.config` sets `addLombokGeneratedAnnotation`, `stopBubbling`, and jspecify null annotations.
- Records for DTOs, commands, events and value objects. Put invariants in compact constructors (`DisplayName`).
- MapStruct for every mapping. Mappers are Spring beans with constructor injection, and an unmapped target is a
  compile error.
- Error Prone plus NullAway run on every compile, and **every warning is an error** (`-Werror`, S-138). That covers
  Error Prone's findings, NullAway and javac's own warnings in main, test and test-fixture code. A new warning fails
  `compileJava` and so `./gradlew build`, with `error: warnings found and -Werror specified` after the warning.
  - Fix the code. Suppress only a real false positive, with a narrow `@SuppressWarnings("<Check>") // reason` on the
    smallest element.
  - Generated sources (`build/generated/**`, MapStruct and Lombok output) are outside Error Prone
    (`disableWarningsInGeneratedCode`, `excludedPaths`).
  - Disabled checks (`StringSplitter`, `MissingSummary`, `JavaTimeDefaultTimeZone`) are listed in
    `server/build.gradle.kts`. Disabling another one is a decision for DECISIONS.md, not a local workaround.
  - To see every warning at once instead of stopping at the first failing module, run
    `./gradlew compileJava compileTestJava compileTestFixturesJava --rerun-tasks --continue`.
- File content in a record (an upload, a stored object) is `ca.northline.shared.Bytes`, never `byte[]`: a record
  compares and prints an array by reference, `Bytes` compares by content and prints only the size.
- Nested types that share a name across use cases (`SendQuote.Command`, `ReviseQuote.Command`) are written qualified
  wherever more than one is in scope, including in their own use-case file.
- No `ThreadLocal` (use `ScopedValue`), no hand-rolled executors (virtual threads are on), no `System.out`, no
  `java.util.Date`. Checkstyle enforces these.
