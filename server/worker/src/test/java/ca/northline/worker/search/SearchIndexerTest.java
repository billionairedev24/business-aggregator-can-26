package ca.northline.worker.search;

import static ca.northline.worker.support.WorkerContainers.KAFKA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.ListingDocument.MinuteRange;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.Listeners;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.support.TransactionOperations;

/**
 * S-43 end to end: domain events on Kafka 4 → the search-indexer consumer → documents built from the rows in PostGIS
 * (as the api writes them) → listings_en / listings_fr on Elasticsearch 9 (Testcontainers).
 */
class SearchIndexerTest extends WorkerIntegrationTest {

    /**
     * How long a test waits for a document. The target is five seconds from publish to searchable (S-43); an await
     * returns as soon as the document is there, and a loaded CI machine (several Testcontainers builds at once) needs
     * the headroom — the listener's partitions are assigned before each test and retries take 100 ms here.
     */
    static final Duration INDEXED = Duration.ofSeconds(30);

    static KafkaProducer<String, byte[]> producer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ElasticsearchClient es;

    @Autowired
    SearchProjection projection;

    @Autowired
    SearchReconciler reconciler;

    @Autowired
    TransactionOperations transactions;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @BeforeAll
    static void producer() {
        WorkerContainers.start();
        producer = new KafkaProducer<>(Events.producerConfig(KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void close() {
        producer.close();
    }

    @BeforeEach
    void listening() {
        Listeners.awaitAssigned(listeners, SearchIndexer.GROUP);
    }

    SearchFixtures fx() {
        return new SearchFixtures(jdbc);
    }

    @Test
    void publishedService_isSearchableInBothLanguages_andHiddenIsRemoved() {
        var fx = fx();
        var m = fx.merchant("provider", "master", "Prairie Test Mechanics");
        fx.review(m, 5);
        fx.review(m, 4);
        fx.weeklyHours(m, 1, "[[\"09:00\",\"17:00\"]]");
        var service = fx.service(m, "Brake inspection", "Inspection des freins", 8900, "live");

        send("catalogue.listing", "catalogue.listing_published", listing(service, m.id(), "service"));

        var en = await().atMost(INDEXED)
                .until(() -> doc("listings_en", service), Optional::isPresent)
                .get();
        assertThat(en.kind()).isEqualTo("service");
        assertThat(en.name()).isEqualTo("Brake inspection");
        assertThat(en.market()).isEqualTo("AB");
        assertThat(en.merchantName()).isEqualTo("Prairie Test Mechanics");
        assertThat(en.priceCents()).isEqualTo(8900L);
        assertThat(en.pricingMode()).isEqualTo("fixed");
        assertThat(en.instantBook()).isTrue();
        assertThat(en.trustTier()).isEqualTo("master");
        assertThat(en.trustRank()).isEqualTo(3);
        assertThat(en.rating()).isEqualTo(4.5);
        assertThat(en.reviewCount()).isEqualTo(2);
        assertThat(en.vetting()).isEqualTo("approved");
        assertThat(en.merchantStatus()).isEqualTo("active");
        assertThat(en.categoryPath()).containsExactly(fx.group(), fx.leaf());
        assertThat(en.categoryNames()).containsExactly("Test automotive", "Test mobile mechanic");
        assertThat(en.location()).isEqualTo(new ListingDocument.GeoPoint(51.0379, -114.088));
        assertThat(en.serviceRadiusKm()).isEqualTo(40.0);
        assertThat(en.openHours()).containsExactly(new MinuteRange(540, 1020));
        assertThat(en.suggest().input()).contains("Brake inspection", "inspection");
        var fr = doc("listings_fr", service).orElseThrow();
        assertThat(fr.name()).isEqualTo("Inspection des freins");
        assertThat(fr.categoryNames()).containsExactly("Automobile (test)", "Mécanicien mobile (test)");

        jdbc.sql("update catalogue.services set status = 'hidden' where id = :id")
                .param("id", service)
                .update();
        send("catalogue.listing", "catalogue.listing_hidden", listing(service, m.id(), "service"));
        await().atMost(INDEXED)
                .until(() -> doc("listings_en", service).isEmpty()
                        && doc("listings_fr", service).isEmpty());
    }

    @Test
    void eventsOnlySayWhereToLook_theRowsDecide_evenOutOfOrder() {
        var fx = fx();
        var m = fx.merchant("provider", "trusted", "Order Test Co");
        var service = fx.service(m, "Tire swap", null, 6000, "live");
        // a late "hidden" event for a listing that is live again: the document follows the row
        send("catalogue.listing", "catalogue.listing_hidden", listing(service, m.id(), "service"));
        await().atMost(INDEXED).until(() -> doc("listings_en", service), Optional::isPresent);
        assertThat(doc("listings_fr", service).orElseThrow().name()).isEqualTo("Tire swap");
    }

    @Test
    void anOlderSnapshotNeverOverwritesANewerDocument() throws IOException {
        var fx = fx();
        var m = fx.merchant("provider", "registered", "Version Test Co");
        var service = fx.service(m, "Detailing", null, 12000, "live");
        var future = Instant.now().plus(Duration.ofHours(1)).toEpochMilli() * 1000;
        var newer = ListingDocument.builder()
                .id(service)
                .kind("service")
                .market("AB")
                .merchantId(m.id())
                .merchantName("Version Test Co")
                .merchantType("provider")
                .merchantStatus("active")
                .name("Newer detailing")
                .categoryPath(java.util.List.of())
                .categoryNames(java.util.List.of())
                .vetting("approved")
                .status("live")
                .fulfilment(java.util.List.of())
                .openHours(java.util.List.of())
                .allergens(java.util.List.of())
                .dietary(java.util.List.of())
                .updatedAt(Instant.now())
                .suggest(new ListingDocument.Completion(java.util.List.of("Newer detailing"), 1))
                .build();
        es.index(i -> i.index("listings_en")
                .id(service)
                .version(future)
                .versionType(VersionType.External)
                .document(newer));

        var outcome = transactions.execute(
                _ -> projection.refresh(new Scope.Listing("service", service, m.id()), SearchProjection.Targets.LIVE));

        assertThat(outcome.stale()).isEqualTo(1); // the English write lost; the French one had nothing newer
        assertThat(doc("listings_en", service).orElseThrow().name()).isEqualTo("Newer detailing");
        assertThat(doc("listings_fr", service).orElseThrow().name()).isEqualTo("Detailing");
        // the same refresh again: same result, no error (idempotent)
        var again = transactions.execute(
                _ -> projection.refresh(new Scope.Listing("service", service, m.id()), SearchProjection.Targets.LIVE));
        assertThat(again.version()).isGreaterThan(outcome.version());
        assertThat(doc("listings_fr", service).orElseThrow().name()).isEqualTo("Detailing");
    }

    @Test
    void merchantPausedOrSuspended_removesAllItsDocuments_andTheyComeBack() {
        var fx = fx();
        var m = fx.merchant("seller", "trusted", "Parts Test Shop");
        jdbc.sql("update merchants.merchants set profile = '{\"sameDayCutoff\":\"17:45\"}' where id = :id")
                .param("id", m.id())
                .update();
        var offer = fx.offer(m, "Wiper blades 22\"", 1900, 12, "{pooled,pickup}");

        send("merchants.merchant", "merchants.merchant_renamed", renamed(m.id()));
        var product = await().atMost(INDEXED)
                .until(() -> doc("listings_en", offer), Optional::isPresent)
                .get();
        assertThat(product.kind()).isEqualTo("product");
        assertThat(product.inStock()).isTrue();
        assertThat(product.fulfilment()).containsExactly("pooled", "pickup");
        assertThat(product.deliveryCutoffMinute()).isEqualTo(17 * 60 + 45);
        var merchantDoc = doc("listings_en", m.id()).orElseThrow();
        assertThat(merchantDoc.kind()).isEqualTo("merchant");
        assertThat(merchantDoc.priceCents()).isEqualTo(1900L);
        assertThat(merchantDoc.merchantSlug()).isEqualTo(m.slug());

        jdbc.sql("update merchants.merchants set status = 'paused' where id = :id")
                .param("id", m.id())
                .update();
        send("merchants.merchant", "merchants.merchant_renamed", renamed(m.id()));
        await().atMost(INDEXED)
                .until(() -> doc("listings_en", offer).isEmpty()
                        && doc("listings_fr", offer).isEmpty()
                        && doc("listings_en", m.id()).isEmpty());

        jdbc.sql("update merchants.merchants set status = 'active' where id = :id")
                .param("id", m.id())
                .update();
        send("merchants.merchant", "merchants.merchant_renamed", renamed(m.id()));
        await().atMost(INDEXED).until(() -> doc("listings_fr", offer), Optional::isPresent);
    }

    @Test
    void vettingRejection_removesTheListing() {
        var fx = fx();
        var m = fx.merchant("seller", "registered", "Vetting Test Shop");
        var offer = fx.offer(m, "Cabin air filter", 2400, 3, "{pooled}");
        send("catalogue.listing", "catalogue.listing_published", listing(offer, m.id(), "product"));
        await().atMost(INDEXED).until(() -> doc("listings_en", offer), Optional::isPresent);

        jdbc.sql("update catalogue.offers set vetting = 'rejected' where id = :id")
                .param("id", offer)
                .update();
        send("catalogue.listing", "catalogue.listing_flagged", """
                {"eventId":"%s","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"%s","merchantId":"%s",\
                "kind":"product","flags":["restricted"]}""".formatted(Events.id(), offer, m.id()));
        await().atMost(INDEXED)
                .until(() -> doc("listings_en", offer).isEmpty()
                        && doc("listings_fr", offer).isEmpty());
    }

    @Test
    void kitchenDishes_bothLanguages_soldOutStays_pauseAndDeletionFollow() {
        var fx = fx();
        var k = fx.merchant("kitchen", "trusted", "Pho Test Kitchen");
        fx.kitchen(k);
        var menu = fx.menu(k, "live");
        var dish = fx.dish(k, menu, "Pho dac biet", "Phở spécial", 1700, "published", "approved");

        send("food.menu", "food.item_availability", availability(dish, k.id(), menu.menuId(), true, null));
        var en = await().atMost(INDEXED)
                .until(() -> doc("listings_en", dish), Optional::isPresent)
                .get();
        assertThat(en.kind()).isEqualTo("food");
        assertThat(en.prepMinutes()).isEqualTo(25 + 5);
        assertThat(en.fulfilment()).containsExactly("courier", "pickup");
        assertThat(en.dietary()).containsExactly("halal");
        assertThat(en.allergens()).containsExactly("peanuts");
        assertThat(en.serviceRadiusKm()).isEqualTo(6.0); // the kitchen's radius (no location radius)
        assertThat(en.openHours()).containsExactly(new MinuteRange(4 * 1440 + 11 * 60, 4 * 1440 + 21 * 60));
        assertThat(en.soldOutOn()).isNull();
        assertThat(doc("listings_fr", dish).orElseThrow().name()).isEqualTo("Phở spécial");

        var today = LocalDate.parse("2026-09-30");
        jdbc.sql("update food.menu_items set sold_out_on = :d where id = :id")
                .param("d", today)
                .param("id", dish)
                .update();
        send("food.menu", "food.item_availability", availability(dish, k.id(), menu.menuId(), false, today.toString()));
        await().atMost(INDEXED)
                .until(
                        () -> doc("listings_en", dish)
                                .map(ListingDocument::soldOutOn)
                                .orElse(null),
                        today::equals);

        var until = Instant.parse("2026-09-30T20:00:00Z");
        jdbc.sql("update food.kitchen_settings set paused_until = :u where merchant_id = :m")
                .param("u", java.sql.Timestamp.from(until))
                .param("m", k.id())
                .update();
        send("food.kitchen", "food.kitchen_paused", """
                {"eventId":"%s","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"%s","actorId":"%s",\
                "pausedUntil":"%s"}""".formatted(Events.id(), k.id(), Events.id(), until));
        await().atMost(INDEXED)
                .until(() -> doc("listings_en", dish).map(ListingDocument::pausedUntil), Optional.of(until)::equals);

        // a row deleted from Postgres: found in the index by a whole-merchant refresh
        jdbc.sql("delete from food.menu_items where id = :id").param("id", dish).update();
        send("food.menu", "food.menu_published", """
                {"eventId":"%s","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"%s","merchantId":"%s",\
                "actorId":"%s"}""".formatted(Events.id(), menu.menuId(), k.id(), Events.id()));
        await().atMost(INDEXED)
                .until(() -> doc("listings_en", dish).isEmpty()
                        && doc("listings_fr", dish).isEmpty());
    }

    @Test
    void aNewReview_andEditsWithoutAnEvent_arrive_throughTheReconcileSweep() {
        var fx = fx();
        var m = fx.merchant("provider", "trusted", "Sweep Test Co");
        var service = fx.service(m, "Oil change", null, 7900, "live");
        send("catalogue.listing", "catalogue.listing_published", listing(service, m.id(), "service"));
        await().atMost(INDEXED).until(() -> doc("listings_en", service), Optional::isPresent);

        jdbc.sql("update catalogue.services set price_cents = 8400, updated_at = now() where id = :id")
                .param("id", service)
                .update();
        fx.review(m, 3);

        assertThat(reconciler.sweep()).isPositive();
        var en = doc("listings_en", service).orElseThrow();
        assertThat(en.priceCents()).isEqualTo(8400L);
        assertThat(en.rating()).isEqualTo(3.0);
        assertThat(en.reviewCount()).isEqualTo(1);
        assertThat(doc("listings_fr", service).orElseThrow().priceCents()).isEqualTo(8400L);
    }

    private void send(String topic, String type, String json) {
        var id = json.substring(json.indexOf("\"eventId\":\"") + 11, json.indexOf("\"eventId\":\"") + 37);
        assertThat(producer.send(Events.record(topic, id, type, 1, json))).succeedsWithin(Duration.ofSeconds(30));
    }

    private Optional<ListingDocument> doc(String index, String id) {
        try {
            var response = es.get(g -> g.index(index).id(id), ListingDocument.class);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String listing(String id, String merchantId, String kind) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"%s","merchantId":"%s","kind":"%s"}""".formatted(Events.id(), id, merchantId, kind);
    }

    private static String renamed(String merchantId) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"%s","actorId":"%s",\
                "displayName":"Renamed"}""".formatted(Events.id(), merchantId, Events.id());
    }

    private static String availability(String id, String merchantId, String menuId, boolean visible, String soldOutOn) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"%s","merchantId":"%s","menuId":"%s",\
                "visible":%s,"soldOutOn":%s}""".formatted(
                Events.id(), id, merchantId, menuId, visible, soldOutOn == null ? "null" : "\"" + soldOutOn + "\"");
    }
}
