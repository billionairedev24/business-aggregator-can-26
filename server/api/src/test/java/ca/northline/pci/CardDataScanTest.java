package ca.northline.pci;

import static ca.northline.pci.CardDataScanner.REPO;
import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.platform.CardData;
import ca.northline.platform.logging.Redactor;
import ca.northline.support.SharedPostgres;
import java.sql.DriverManager;
import java.util.List;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * S-110 (PCI DSS SAQ A, "no card data in logs or the DB"): the scanner over everything that stores or describes data.
 * {@code make pci-scan} runs it; docs/compliance/pci/saq-a.md cites it as the evidence for requirement 3.
 *
 * <ul>
 *   <li>the database: a fresh database migrated with every migration <em>and</em> the dev seed — every column of every
 *       schema, its name and its contents (text, JSON, arrays, numbers);
 *   <li>the seed and migration files themselves;
 *   <li>the contracts: event JSON Schemas, partner webhook schemas, every OpenAPI document (no {@code cardNumber},
 *       {@code cvc} or {@code pan} in a request or response model);
 *   <li>the logs: the Redactor masks every brand's PAN, track data and verification codes, and the Collector's second
 *       line keeps a card rule;
 *   <li>the clients: no card input outside Stripe's fields and no script from anywhere but Stripe.
 * </ul>
 */
class CardDataScanTest {

    static JdbcClient jdbc;

    @BeforeAll
    static void migrateAFreshDatabase() throws Exception {
        var pg = SharedPostgres.INSTANCE;
        var name = "pci_scan_" + System.nanoTime();
        try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                var s = c.createStatement()) {
            s.execute("create database " + name);
        }
        var url = pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        Flyway.configure()
                .dataSource(url, pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/seed-dev")
                .outOfOrder(true)
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword()));
    }

    @Test
    void noColumnIsNamedLikeCardData() {
        var columns = CardDataScanner.columns(jdbc);
        assertThat(columns).as("the scan sees the schemas").hasSizeGreaterThan(500);
        assertThat(columns.stream().map(CardDataScanner.Column::schema).distinct())
                .contains("identity", "payments", "orders", "messaging", "events", "privacy");
        assertThat(CardDataScanner.columnNames(columns)).isEmpty();
    }

    @Test
    void savedCardsKeepOnlyBrandLastFourAndExpiry() {
        var cards = CardDataScanner.columns(jdbc).stream()
                .filter(c -> c.schema().equals("payments") && c.table().equals("customer_cards"))
                .map(CardDataScanner.Column::name)
                .toList();
        assertThat(cards).isNotEmpty().noneMatch(CardData::isCardDataName);
        assertThat(cards).noneMatch(n -> n.matches("(?i).*(number|cvc|cvv|track).*"));
    }

    @Test
    void noMigratedOrSeededValueHoldsCardData() {
        assertThat(CardDataScanner.contents(jdbc, CardDataScanner.columns(jdbc), _ -> true))
                .isEmpty();
    }

    @Test
    void seedAndMigrationFilesHoldNoCardData() {
        assertThat(CardDataScanner.fileContents(
                        List.of(REPO.resolve("db/migrations"), REPO.resolve("db/seed"), REPO.resolve("db/seed-dev")),
                        p -> p.toString().matches(".*\\.(sql|json|csv)$")))
                .isEmpty();
    }

    @Test
    void eventAndWebhookContractsHaveNoCardFields() {
        var dirs = List.of(
                REPO.resolve("server/api/src/main/resources/events"),
                REPO.resolve("docs/spec/webhooks"),
                REPO.resolve("server/event-contracts/src"));
        assertThat(CardDataScanner.jsonSchemaProperties(dirs)).isEmpty();
    }

    @Test
    void openApiModelsHaveNoCardFields() {
        assertThat(REPO.resolve("docs/api/openapi")).isDirectory();
        assertThat(CardDataScanner.openApiNames(REPO.resolve("docs/api/openapi")))
                .isEmpty();
    }

    @Test
    void logRedactionMasksCardData() {
        for (var pan : List.of(
                "4242 4242 4242 4242",
                "5555-5555-5555-4444",
                "378282246310005",
                "6011111111111117",
                "3566002020360505",
                "6200000000000005")) {
            var line = Redactor.redact("charge failed for card " + pan + " exp 12/29");
            assertThat(CardData.containsPan(line)).as(line).isFalse();
            assertThat(line).contains(CardData.mask(pan));
        }
        assertThat(Redactor.redact("{\"cvc\":\"123\"}")).doesNotContain("123");
        assertThat(Redactor.redact("swipe ;4242424242424242=29121010000000000000?"))
                .isEqualTo("swipe [TRACK]");
        assertThat(Redactor.isSensitiveName("cardNumber")).isTrue();
        assertThat(Redactor.isSensitiveName("cvc")).isTrue();
    }

    @Test
    void theCollectorKeepsItsCardRule() {
        var card = Pattern.quote("\\\\b\\\\d(?:[ -]?\\\\d){12,18}\\\\b");
        for (var file : List.of(
                "deploy/helm/northline/templates/_helpers.tpl",
                "deploy/observability/collector/collector-local.yaml")) {
            assertThat(CardDataScanner.read(REPO.resolve(file))).as(file).containsPattern(card);
        }
    }

    @Test
    void clientsTakeCardsOnlyInStripeFields() {
        var dirs = List.of(
                REPO.resolve("web/apps"),
                REPO.resolve("web/packages"),
                REPO.resolve("mobile/apps"),
                REPO.resolve("mobile/packages"));
        assertThat(CardDataScanner.clientSources(dirs)).isEmpty();
    }

    @Test
    void theStudioPolicyLetsOnlyStripeScriptAndFrameIn() {
        var headers = CardDataScanner.read(REPO.resolve("web/docker/security-headers.inc.template"));
        var csp = headers.lines()
                .filter(l -> l.contains("Content-Security-Policy"))
                .findFirst()
                .orElseThrow();
        for (var directive : List.of("script-src", "frame-src")) {
            var sources = Pattern.compile(directive + " ([^;]+);")
                    .matcher(csp)
                    .results()
                    .findFirst()
                    .orElseThrow()
                    .group(1)
                    .split(" ");
            assertThat(sources)
                    .as(directive)
                    .allMatch(s -> s.equals("'self'") || s.matches("https://[a-z*.-]*\\.?stripe\\.com"));
        }
        assertThat(csp).contains("object-src 'none'", "frame-ancestors 'none'", "base-uri 'self'");
    }
}
