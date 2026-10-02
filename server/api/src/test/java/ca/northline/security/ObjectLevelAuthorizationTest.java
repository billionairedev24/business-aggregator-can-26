package ca.northline.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.ShopFixtures;
import ca.northline.support.TestJwt;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * S-104: broken object-level and function-level authorization (OWASP API1/API5), for <b>every operation in the
 * committed OpenAPI documents</b> ({@code docs/api/openapi}) — a new endpoint is covered the day its spec is
 * regenerated, without writing a test for it.
 *
 * <ul>
 *   <li><b>Another business</b>: every Studio and partner operation with {@code {merchantId}}, called by the owner of
 *       another business (or a partner bound to another business), answers 403 — before the body is even read.
 *   <li><b>Another business's object</b>: every Studio operation that names an object ({@code {orderId}},
 *       {@code {listingId}} …) under the caller's <i>own</i> business, with the id of an object that belongs to someone
 *       else (the caller's business is brand new and owns nothing, so every existing row is someone else's; fixtures
 *       make sure the main kinds exist), never succeeds (no 2xx) and never fails with a 5xx.
 *   <li><b>Another person's object</b>: every signed-in consumer operation that names an object ({@code /me/…},
 *       quotes, cart items …), called by a brand-new customer with another person's id: no 2xx, no 5xx.
 *   <li><b>Console</b>: every {@code /api/v1/console/**} operation refuses a customer, a business owner and staff
 *       without a second factor (403), and staff without a console role unless the screen is open to all staff.
 * </ul>
 *
 * Runs in the shared {@link IntegrationTest} context (no new Spring context: the api test JVM caches at most 12).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectLevelAuthorizationTest extends IntegrationTest {

    private static final Path SPECS = Path.of(System.getProperty("northline.repo", "../.."), "docs/api/openapi");
    private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    /** Paths open to everyone by design (SecurityConfig): no caller, so no "someone else's" either. */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/v1/public/", "/api/v1/search", "/api/v1/storefronts/", "/api/v1/geo/", "/api/v1/team-invitations/");

    /**
     * Where to find an existing object for a path parameter: tables tried in order (several when the name is used for
     * more than one kind, each is probed). A parameter without a row anywhere gets a fresh ULID (a plain 404 check).
     */
    private static final Map<String, List<String>> OBJECTS = Map.ofEntries(
            Map.entry("listingId", List.of("catalogue.offers", "catalogue.services")),
            Map.entry("productId", List.of("catalogue.catalog_products")),
            Map.entry("menuId", List.of("food.menus")),
            Map.entry("sectionId", List.of("food.menu_sections")),
            Map.entry("itemId", List.of("food.menu_items", "orders.cart_items")),
            Map.entry("groupId", List.of("food.modifier_groups")),
            Map.entry("comboId", List.of("food.combos")),
            Map.entry("holidayId", List.of("food.holiday_hours", "availability.holiday_openings")),
            Map.entry("endpointId", List.of("developer.webhook_endpoints")),
            Map.entry("keyId", List.of("developer.api_keys", "developer.publishable_keys")),
            Map.entry("deliveryId", List.of("developer.webhook_deliveries", "fulfilment.deliveries")),
            Map.entry("disputeId", List.of("payments.disputes")),
            Map.entry("refundId", List.of("payments.refunds")),
            Map.entry("accountId", List.of("payments.payout_accounts")),
            Map.entry("orderId", List.of("orders.orders")),
            Map.entry("checkoutId", List.of("orders.checkouts", "orders.food_checkouts")),
            Map.entry("jobId", List.of("booking.bookings")),
            Map.entry("bookingId", List.of("booking.bookings")),
            Map.entry("quoteId", List.of("booking.quotes")),
            Map.entry("requestId", List.of("booking.quote_requests", "privacy.requests")),
            Map.entry("threadId", List.of("messaging.threads")),
            Map.entry("attachmentId", List.of("messaging.attachments")),
            Map.entry("uploadId", List.of("messaging.customer_uploads")),
            Map.entry("caseId", List.of("messaging.tickets", "payments.disputes")),
            Map.entry("reviewId", List.of("trust.reviews")),
            Map.entry("importId", List.of("catalogue.imports", "food.pos_imports")),
            Map.entry("mediaId", List.of("catalogue.media", "booking.media")),
            Map.entry("documentId", List.of("merchants.documents", "catalogue.listing_documents")),
            Map.entry("verificationId", List.of("merchants.verifications")),
            Map.entry("principalId", List.of("merchants.merchant_principals")),
            Map.entry("invitationId", List.of("merchants.member_invitations")),
            Map.entry("timeOffId", List.of("availability.time_off")),
            Map.entry("addressId", List.of("identity.addresses")),
            Map.entry("installationId", List.of("messaging.push_devices")),
            Map.entry("userId", List.of("identity.users")),
            Map.entry("id", List.of("privacy.requests")));

    /**
     * Operations that serve another business's object by design, because the same object is public: their public
     * counterpart. A 2xx passes only when that public path serves the same id too (S-123: approved catalogue images are
     * shared records; drafts stay the owner's).
     */
    private static final Map<String, String> SHARED_WITH_EVERYONE =
            Map.of("GET /api/v1/merchants/{merchantId}/media/{mediaId}", "/api/v1/public/catalogue/media/{mediaId}");

    private boolean publiclyServed(Operation op, Map<String, String> probe) throws Exception {
        var publicPath = SHARED_WITH_EVERYONE.get(op.toString());
        if (publicPath == null) {
            return false;
        }
        var uri = publicPath;
        for (var e : probe.entrySet()) {
            uri = uri.replace("{" + e.getKey() + "}", URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return mvc.perform(request(HttpMethod.GET, uri))
                        .andReturn()
                        .getResponse()
                        .getStatus()
                == 200;
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    /** The callers' own (empty) businesses, one per portal kind, and the other business. */
    private final Map<String, String> owners = new LinkedHashMap<>();

    private String otherMerchant = "";
    private String otherOwner = "";
    private String customer = "";

    @BeforeAll
    void world() {
        for (var type : List.of("both", "kitchen")) {
            var merchant = data.merchant(type, "Caller " + type);
            var owner = data.user("Caller owner " + type);
            data.member(merchant, owner, MerchantRole.OWNER);
            owners.put(merchant, owner);
        }
        var other = data.business(MerchantRole.OWNER);
        otherMerchant = other.merchantId();
        otherOwner = other.userId();
        customer = data.user("Caller customer");
        // the other business and another person own one of each of the main kinds (other tests add the rest)
        var victim = data.user("Victim customer");
        var operations = new OperationsFixtures(jdbc);
        operations.job(otherMerchant, otherOwner, victim, Instant.now().plus(Duration.ofDays(2)), "confirmed");
        operations.quoteRequest(otherMerchant, victim);
        operations.order(
                operations.window(Duration.ofHours(6), "S-104"),
                victim,
                "placed",
                new OperationsFixtures.Line(otherMerchant, "Brake pads", 1, 4500, "pending"));
        new SettingsFixtures(jdbc).verification(otherMerchant, "bank", null, null, "submitted", null, "bank");
        shopFixtures.categories();
        shopFixtures.listing(
                shopFixtures.shop("Productville", "Victim Bakery", "trusted"), ShopFixtures.BAKERY, "Loaf", 650, 3);
    }

    @TestFactory
    Stream<DynamicTest> anotherBusiness_isRefusedOnEveryMerchantOperation() throws IOException {
        var caller = owners.entrySet().iterator().next();
        return Stream.concat(operations("api-studio.yaml"), operations("api-partner.yaml"))
                .filter(op -> op.path().contains("{merchantId}"))
                .map(op -> DynamicTest.dynamicTest(op.toString(), () -> {
                    var params = fill(op, Map.of("merchantId", otherMerchant));
                    var status = status(op, params, TestJwt.member(caller.getValue()));
                    assertThat(status)
                            .as("%s as the owner of another business", op)
                            .isEqualTo(403);
                    var partner =
                            status(op, params, TestJwt.partner("partner:s104", "api.read api.write", caller.getKey()));
                    assertThat(partner)
                            .as("%s as a partner bound to another business", op)
                            .isEqualTo(403);
                }));
    }

    @TestFactory
    Stream<DynamicTest> anotherBusinessesObject_isNeverServedUnderTheCallersOwn() throws IOException {
        return operations("api-studio.yaml")
                .filter(op ->
                        op.path().contains("{merchantId}") && !objectParams(op).isEmpty())
                .flatMap(op -> probes(op).stream()
                        .map(probe -> DynamicTest.dynamicTest(op + " " + probe, () -> {
                            for (var caller : owners.entrySet()) {
                                var params = new LinkedHashMap<>(probe);
                                params.put("merchantId", caller.getKey());
                                var status = status(op, fill(op, params), TestJwt.member(caller.getValue()));
                                if (status / 100 == 2 && publiclyServed(op, probe)) {
                                    continue; // the same object is public (SHARED_WITH_EVERYONE)
                                }
                                assertThat(status)
                                        .as("%s with someone else's %s under the caller's own business", op, probe)
                                        .satisfies(ObjectLevelAuthorizationTest::refusedOrInvalid);
                            }
                        })));
    }

    @TestFactory
    Stream<DynamicTest> anotherPersonsObject_isNeverServedToACustomer() throws IOException {
        return Stream.concat(operations("api-public.yaml"), operations("api-studio.yaml"))
                .filter(op -> !op.path().contains("{merchantId}")
                        && !objectParams(op).isEmpty()
                        && PUBLIC_PREFIXES.stream().noneMatch(op.path()::startsWith))
                .flatMap(op -> probes(op).stream()
                        .map(probe -> DynamicTest.dynamicTest(op + " " + probe, () -> {
                            var status = status(op, fill(op, probe), TestJwt.customerWithMfa(customer));
                            assertThat(status)
                                    .as("%s with another person's %s", op, probe)
                                    .satisfies(ObjectLevelAuthorizationTest::refusedOrInvalid);
                        })));
    }

    @TestFactory
    Stream<DynamicTest> console_refusesEveryoneButStaffWithASecondFactorAndTheRole() throws IOException {
        var owner = owners.values().iterator().next();
        return operations("api-console.yaml")
                .map(op -> DynamicTest.dynamicTest(op.toString(), () -> {
                    var params = fill(op, Map.of());
                    assertThat(status(op, params, TestJwt.customerWithMfa(customer)))
                            .as("%s as a customer", op)
                            .isEqualTo(403);
                    assertThat(status(op, params, TestJwt.member(owner)))
                            .as("%s as a business owner", op)
                            .isEqualTo(403);
                    assertThat(status(
                                    op,
                                    params,
                                    TestJwt.staffWithoutMfa(customer, ca.northline.shared.security.StaffRole.values())))
                            .as("%s as staff with every role but no second factor", op)
                            .isEqualTo(403);
                    if (!openToAllStaff(op, params)) {
                        assertThat(status(op, params, TestJwt.staff(customer)))
                                .as("%s as staff without a console role", op)
                                .isEqualTo(403);
                    }
                }));
    }

    // --- the documents

    record Operation(
            String spec, HttpMethod method, String path, Map<String, Object> operation, Map<String, Object> schemas) {

        List<Map<String, Object>> parameters() {
            return maps(operation.get("parameters"));
        }

        Set<String> requestTypes() {
            return map(map(operation.get("requestBody")).get("content")).keySet();
        }

        /**
         * A request body that passes the documented shape — every required property with a value of its type (the
         * first enum value, a date, one array item …) — so a write reaches the service's own checks instead of
         * stopping at a 422 for an empty body.
         */
        String sampleBody() {
            var content = map(map(operation.get("requestBody")).get("content"));
            var schema = map(map(content.getOrDefault(MediaType.APPLICATION_JSON_VALUE, Map.of()))
                    .get("schema"));
            return JSON.writeValueAsString(sample(schema, 0));
        }

        private @Nullable Object sample(Map<String, Object> schema, int depth) {
            if (schema.get("$ref") instanceof String ref) {
                return depth > 6 ? null : sample(map(schemas.get(ref.substring(ref.lastIndexOf('/') + 1))), depth + 1);
            }
            if (schema.get("enum") instanceof List<?> values && !values.isEmpty()) {
                return values.getFirst();
            }
            var type = schema.get("type") instanceof List<?> types
                    ? types.stream()
                            .map(String::valueOf)
                            .filter(t -> !"null".equals(t))
                            .findFirst()
                            .orElse("")
                    : String.valueOf(schema.get("type"));
            return switch (type) {
                case "object" -> {
                    var out = new LinkedHashMap<String, Object>();
                    var properties = map(schema.get("properties"));
                    for (var name : schema.get("required") instanceof List<?> required ? required : List.of()) {
                        var value = sample(map(properties.get(String.valueOf(name))), depth + 1);
                        if (value != null) {
                            out.put(String.valueOf(name), value);
                        }
                    }
                    yield out;
                }
                case "array" -> {
                    var item = sample(map(schema.get("items")), depth + 1);
                    yield item == null ? List.of() : List.of(item);
                }
                case "integer", "number" -> schema.get("minimum") instanceof Number min ? min : 1;
                case "boolean" -> true;
                case "string" ->
                    switch (String.valueOf(schema.get("format"))) {
                        case "date" -> LocalDate.now().plusDays(3).toString();
                        case "date-time" ->
                            Instant.now().plus(Duration.ofDays(3)).toString();
                        case "email" -> "s104-probe@example.ca";
                        case "uri" -> "https://example.ca/s104";
                        default -> "S-104 probe";
                    };
                default -> null;
            };
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static Stream<Operation> operations(String spec) throws IOException {
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> document = yaml.load(Files.readString(SPECS.resolve(spec)));
        var out = new ArrayList<Operation>();
        map(document.get("paths"))
                .forEach((path, item) -> map(item).forEach((method, op) -> {
                    if (METHODS.contains(method)) {
                        out.add(new Operation(
                                spec,
                                HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)),
                                path,
                                map(op),
                                map(map(document.get("components")).get("schemas"))));
                    }
                }));
        return out.stream();
    }

    private static List<String> pathParams(Operation op) {
        return PARAM.matcher(op.path()).results().map(m -> m.group(1)).toList();
    }

    /** Path parameters that name a stored object (not the business, a provider name, a date …). */
    private static List<String> objectParams(Operation op) {
        return pathParams(op).stream().filter(OBJECTS::containsKey).toList();
    }

    /**
     * One set of object ids per probe: every object parameter gets someone else's id; the last one is varied over each
     * kind it may name (an {@code {itemId}} is probed as a menu item and as a cart item).
     */
    private List<Map<String, String>> probes(Operation op) {
        var params = objectParams(op);
        var first = new LinkedHashMap<String, String>();
        params.forEach(p -> first.put(p, foreign(OBJECTS.get(p).getFirst()).orElseGet(Ids::next)));
        var probes = new ArrayList<Map<String, String>>();
        var last = params.getLast();
        for (var table : OBJECTS.get(last)) {
            var probe = new LinkedHashMap<>(first);
            probe.put(last, foreign(table).orElseGet(Ids::next));
            if (!probes.contains(probe)) {
                probes.add(probe);
            }
        }
        return probes;
    }

    /** Any existing object of a table: never the callers' (their businesses and the customer own nothing). */
    private Optional<String> foreign(String table) {
        try {
            return jdbc.sql("select id::text from " + table + " order by id desc limit 1")
                    .query(String.class)
                    .optional();
        } catch (RuntimeException noIdColumn) {
            return Optional.empty();
        }
    }

    /** Values for every path parameter: the given ones, else a plausible value of the documented type. */
    private static Map<String, String> fill(Operation op, Map<String, String> given) {
        var values = new LinkedHashMap<String, String>();
        for (var name : pathParams(op)) {
            values.put(name, given.containsKey(name) ? given.get(name) : sample(name, schemaOf(op, name, "path")));
        }
        return values;
    }

    private static Map<String, Object> schemaOf(Operation op, String name, String in) {
        return op.parameters().stream()
                .filter(p -> name.equals(p.get("name")) && in.equals(p.get("in")))
                .findFirst()
                .map(p -> map(p.get("schema")))
                .orElse(Map.of());
    }

    private static String sample(String name, Map<String, Object> schema) {
        if (schema.get("enum") instanceof List<?> values && !values.isEmpty()) {
            return String.valueOf(values.getFirst());
        }
        var format = String.valueOf(schema.get("format"));
        if ("date".equals(format) || "day".equals(name) || "date".equals(name)) {
            return LocalDate.now().toString();
        }
        if ("date-time".equals(format)) {
            return Instant.now().toString();
        }
        if ("integer".equals(schema.get("type")) || "number".equals(schema.get("type"))) {
            return "1";
        }
        if ("boolean".equals(schema.get("type"))) {
            return "true";
        }
        return switch (name) {
            case "provider" -> "google";
            case "platform" -> "shopify";
            case "token" -> "s104-not-a-real-token-0000000000000000000000";
            case "slug" -> "s104-no-such-slug";
            default -> Ids.next();
        };
    }

    /** The answer's status for {@code op} with these path values, required query values and a request body. */
    private int status(Operation op, Map<String, String> params, RequestPostProcessor caller) throws Exception {
        return mvc.perform(build(op, params).with(caller))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private static AbstractMockHttpServletRequestBuilder<?> build(Operation op, Map<String, String> params) {
        var uri = op.path();
        for (var e : params.entrySet()) {
            uri = uri.replace("{" + e.getKey() + "}", URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        var query = new ArrayList<String>();
        for (var p : op.parameters()) {
            if ("query".equals(p.get("in")) && Boolean.TRUE.equals(p.get("required"))) {
                var name = String.valueOf(p.get("name"));
                query.add(name + "=" + URLEncoder.encode(sample(name, map(p.get("schema"))), StandardCharsets.UTF_8));
            }
        }
        if (!query.isEmpty()) {
            uri += "?" + String.join("&", query);
        }
        var types = op.requestTypes();
        AbstractMockHttpServletRequestBuilder<?> builder;
        if (types.contains(MediaType.MULTIPART_FORM_DATA_VALUE)) {
            builder = multipart(op.method(), uri).file(new MockMultipartFile("file", "s104.png", "image/png", PNG));
        } else {
            builder = request(op.method(), uri);
            if (!types.isEmpty()) {
                builder.contentType(MediaType.APPLICATION_JSON).content(op.sampleBody());
            }
        }
        return builder.header("Idempotency-Key", Ids.next())
                .header("Accept-Language", "en-CA")
                .accept(MediaType.APPLICATION_JSON, MediaType.ALL);
    }

    /** Whether the console handler behind this operation opens a screen every staff member has. */
    private boolean openToAllStaff(Operation op, Map<String, String> params) throws Exception {
        var request = build(op, params).buildRequest(new org.springframework.mock.web.MockServletContext());
        var chain = mappings.getHandler(request);
        if (chain == null || !(chain.getHandler() instanceof HandlerMethod handler)) {
            return false;
        }
        var guard = Optional.ofNullable(handler.getMethodAnnotation(RequiresConsole.class))
                .orElse(handler.getBeanType().getAnnotation(RequiresConsole.class));
        return guard != null && guard.value().openToAllStaff() && guard.actions().length == 0;
    }

    /** Refused (401/403/404), or rejected as a request (400/405/409/415/422) — never served, never a server error. */
    private static void refusedOrInvalid(Integer status) {
        assertThat(status).isIn(400, 401, 403, 404, 405, 409, 415, 422);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(@Nullable Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<Map<String, Object>> maps(@Nullable Object value) {
        return value instanceof List<?> list
                ? list.stream().map(ObjectLevelAuthorizationTest::map).toList()
                : List.of();
    }

    /** A 1×1 PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
}
