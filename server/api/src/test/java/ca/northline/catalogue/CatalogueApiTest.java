package ca.northline.catalogue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ca.northline.shared.DomainEvent;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestData.Business;
import ca.northline.tools.CategorySeeder;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared set-up for the catalogue API tests: categories seeded, fixtures for seller/provider businesses and approved
 * comparables, and a capture of every domain event (including those published by the async vetting listener, which
 * {@code @RecordApplicationEvents} does not see).
 */
@Import(CatalogueApiTest.EventCapture.class)
abstract class CatalogueApiTest extends IntegrationTest {

    static final String AUTO_PARTS = "shop.hardware-and-auto.auto-parts";
    static final String MECHANIC = "service.automotive.mobile-mechanic";
    static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EventCapture captured;

    @BeforeEach
    void seedCategoriesOnce() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
    }

    /** Collects every {@link DomainEvent} published in the context, from any thread. */
    @TestConfiguration(proxyBeanMethods = false)
    static class EventCapture {
        final List<DomainEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(DomainEvent event) {
            events.add(event);
        }

        <E extends DomainEvent> List<E> of(Class<E> type, String aggregateId) {
            return events.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .filter(e -> e.aggregateId().equals(aggregateId))
                    .toList();
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────────────────────────

    Business seller(MerchantRole role) {
        return business("seller", role);
    }

    Business provider(MerchantRole role) {
        return business("provider", role);
    }

    Business business(String type, MerchantRole role) {
        var merchantId = data.merchant(type, "Prairie Wrench " + type);
        var userId = data.user("Test " + role.code());
        data.member(merchantId, userId, role);
        return new Business(merchantId, userId);
    }

    /** Adds a member with {@code role} to an existing business. */
    String member(String merchantId, MerchantRole role) {
        var userId = data.user("Test " + role.code());
        data.member(merchantId, userId, role);
        return userId;
    }

    void verifiedLicence(String merchantId, String registry) {
        jdbc.sql("""
                        insert into merchants.verifications (id, merchant_id, check_type, registry, status)
                        values (?, ?, 'licence', ?, 'verified')
                        """).params(Ids.next(), merchantId, registry).update();
    }

    /** An approved, live offer of another merchant in {@code categoryId} (a price comparable for vetting). */
    void approvedComparable(String categoryId, long priceCents) {
        var productId = Ids.next();
        jdbc.sql("""
                        insert into catalogue.catalog_products (id, ref, identifier_type, title, category_id, attributes, locked)
                        values (?, ?, 'none', 'Comparable', ?, '{}', false)
                        """).params(productId, "T-" + productId, categoryId).update();
        jdbc.sql("""
                        insert into catalogue.offers (id, product_id, merchant_id, title, sku, price_cents, stock, vetting, status)
                        values (?, ?, ?, 'Comparable', ?, ?, 1, 'approved', 'live')
                        """)
                .params(Ids.next(), productId, Ids.next(), "CMP-" + productId, priceCents)
                .update();
    }

    // ── requests ───────────────────────────────────────────────────────────────────────────────────────────────────

    static String completeProduct(String title, String sku, long priceCents) {
        return """
                {"identifierType":"none","title":"%s","brand":"Prairie Wrench","categoryId":"%s",
                 "attributes":{"partType":"Wiper blades","length":"22 in","position":"Front"},
                 "sku":"%s","priceCents":%d,"stock":10,"fulfilment":["pooled","pickup"],"imageSource":"shared",
                 "countryOfOrigin":"CA","restrictedOk":true,"bilingualOk":true}
                """.formatted(title, AUTO_PARTS, sku, priceCents);
    }

    static MockHttpServletRequestBuilder postJson(String path, String body, Object... vars) {
        return post(path, vars).contentType(JSON).content(body);
    }

    static MockHttpServletRequestBuilder putJson(String path, String body, Object... vars) {
        return put(path, vars).contentType(JSON).content(body);
    }

    static JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static void await(ThrowingCheck check) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(check::run);
    }

    @FunctionalInterface
    interface ThrowingCheck {
        void run() throws Exception;
    }

    /**
     * A PNG of the given size on {@code background}, with a random pattern of dark cells inside a 10 % margin — so
     * every call has a different perceptual hash (the duplicate check compares uploads across the shared test DB).
     */
    static byte[] png(int width, int height, Color background) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(40, 60, 50));
        var random = java.util.concurrent.ThreadLocalRandom.current();
        int cellW = width * 8 / 10 / 8, cellH = height * 8 / 10 / 8;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (random.nextBoolean()) {
                    g.fillRect(width / 10 + x * cellW, height / 10 + y * cellH, cellW, cellH);
                }
            }
        }
        g.dispose();
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
