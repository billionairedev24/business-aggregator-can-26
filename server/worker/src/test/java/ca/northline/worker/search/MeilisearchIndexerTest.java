package ca.northline.worker.search;

import static ca.northline.worker.search.SearchIndexerTest.availability;
import static ca.northline.worker.search.SearchIndexerTest.listing;
import static ca.northline.worker.search.SearchIndexerTest.menuPublished;
import static ca.northline.worker.search.SearchIndexerTest.renamed;
import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.ListingDocument.MinuteRange;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.WorkerContainers;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * S-43 on the {@code meilisearch} provider: {@link SearchIndexerTest}'s events, handed to the search-indexer consumer,
 * → documents built from the rows in PostGIS → listings_en / listings_fr on Meilisearch. Writes are awaited, so a
 * document is there when the consumer returns.
 */
class MeilisearchIndexerTest extends MeilisearchWorkerTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SearchProjection projection;

    @Autowired
    SearchReconciler reconciler;

    @Autowired
    TransactionOperations transactions;

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

        consume("catalogue.listing", "catalogue.listing_published", listing(service, m.id(), "service"));

        var en = doc("listings_en", service).orElseThrow();
        assertThat(en.kind()).isEqualTo("service");
        assertThat(en.name()).isEqualTo("Brake inspection");
        assertThat(en.market()).isEqualTo("AB");
        assertThat(en.priceCents()).isEqualTo(8900L);
        assertThat(en.trustTier()).isEqualTo("master");
        assertThat(en.rating()).isEqualTo(4.5);
        assertThat(en.reviewCount()).isEqualTo(2);
        assertThat(en.categoryPath()).containsExactly(fx.group(), fx.leaf());
        assertThat(en.location()).isEqualTo(new ListingDocument.GeoPoint(51.0379, -114.088));
        assertThat(en.openHours()).containsExactly(new MinuteRange(540, 1020));
        assertThat(en.suggest().input()).contains("Brake inspection", "inspection");
        assertThat(doc("listings_fr", service).orElseThrow().name()).isEqualTo("Inspection des freins");
        // what Meilisearch filters and sorts on
        var raw = WorkerContainers.meilisearchClient()
                .document("listings_en", service)
                .orElseThrow();
        assertThat(raw.path("_geo").path("lng").asDouble()).isEqualTo(-114.088);
        assertThat(raw.path("openSlots").valueStream().map(n -> n.asInt()))
                .hasSize(32)
                .contains(36, 67); // Monday 09:00–17:00 in quarter hours
        assertThat(raw.path("merchantFacet").asString()).isEqualTo(m.id() + "\u001fPrairie Test Mechanics");

        jdbc.sql("update catalogue.services set status = 'hidden' where id = :id")
                .param("id", service)
                .update();
        consume("catalogue.listing", "catalogue.listing_hidden", listing(service, m.id(), "service"));
        assertThat(doc("listings_en", service)).isEmpty();
        assertThat(doc("listings_fr", service)).isEmpty();
    }

    @Test
    void eventsOnlySayWhereToLook_theRowsDecide_evenOutOfOrder() {
        var fx = fx();
        var m = fx.merchant("provider", "trusted", "Order Test Co");
        var service = fx.service(m, "Tire swap", null, 6000, "live");
        consume("catalogue.listing", "catalogue.listing_hidden", listing(service, m.id(), "service"));
        assertThat(doc("listings_fr", service).orElseThrow().name()).isEqualTo("Tire swap");
    }

    /**
     * Meilisearch has no external versions: refreshes of a merchant are applied in the order they were made (the
     * merchant lock + Meilisearch's task order), so the later one — the newer rows — is what stays; nothing is stale.
     */
    @Test
    void theLaterRefreshWins_withoutVersions() {
        var fx = fx();
        var m = fx.merchant("provider", "registered", "Version Test Co");
        var service = fx.service(m, "Detailing", null, 12000, "live");
        var scope = new Scope.Listing("service", service, m.id());
        var first = transactions.execute(_ -> projection.refresh(scope, SearchProjection.Targets.LIVE));
        jdbc.sql("update catalogue.services set price_cents = 13000 where id = :id")
                .param("id", service)
                .update();
        var second = transactions.execute(_ -> projection.refresh(scope, SearchProjection.Targets.LIVE));

        assertThat(second.version()).isGreaterThan(first.version());
        assertThat(first.indexed()).isEqualTo(2); // one document per language
        assertThat(second.stale()).isZero();
        assertThat(doc("listings_en", service).orElseThrow().priceCents()).isEqualTo(13000L);
        assertThat(doc("listings_fr", service).orElseThrow().priceCents()).isEqualTo(13000L);
    }

    @Test
    void merchantPausedOrSuspended_removesAllItsDocuments_andTheyComeBack() {
        var fx = fx();
        var m = fx.merchant("seller", "trusted", "Parts Test Shop");
        jdbc.sql("update merchants.merchants set profile = '{\"sameDayCutoff\":\"17:45\"}' where id = :id")
                .param("id", m.id())
                .update();
        var offer = fx.offer(m, "Wiper blades 22\"", 1900, 12, "{pooled,pickup}");

        consume("merchants.merchant", "merchants.merchant_renamed", renamed(m.id()));
        var product = doc("listings_en", offer).orElseThrow();
        assertThat(product.kind()).isEqualTo("product");
        assertThat(product.inStock()).isTrue();
        assertThat(product.deliveryCutoffMinute()).isEqualTo(17 * 60 + 45);
        assertThat(doc("listings_en", m.id()).orElseThrow().kind()).isEqualTo("merchant");

        jdbc.sql("update merchants.merchants set status = 'paused' where id = :id")
                .param("id", m.id())
                .update();
        consume("merchants.merchant", "merchants.merchant_renamed", renamed(m.id()));
        assertThat(doc("listings_en", offer)).isEmpty();
        assertThat(doc("listings_fr", offer)).isEmpty();
        assertThat(doc("listings_en", m.id())).isEmpty();

        jdbc.sql("update merchants.merchants set status = 'active' where id = :id")
                .param("id", m.id())
                .update();
        consume("merchants.merchant", "merchants.merchant_renamed", renamed(m.id()));
        assertThat(doc("listings_fr", offer)).isPresent();
    }

    @Test
    void kitchenDishes_soldOutStays_pauseFollows_aJustIndexedDishDeletedFromPostgresGoes() {
        var fx = fx();
        var k = fx.merchant("kitchen", "trusted", "Pho Test Kitchen");
        fx.kitchen(k);
        var menu = fx.menu(k, "live");
        var dish = fx.dish(k, menu, "Pho dac biet", "Phở spécial", 1700, "published", "approved");
        var keep = fx.dish(k, menu, "Banh mi thit", "Banh mi au porc", 1200, "published", "approved");

        consume("food.menu", "food.item_availability", availability(dish, k.id(), menu.menuId(), true, null));
        consume("food.menu", "food.item_availability", availability(keep, k.id(), menu.menuId(), true, null));
        var en = doc("listings_en", dish).orElseThrow();
        assertThat(en.kind()).isEqualTo("food");
        assertThat(en.dietary()).containsExactly("halal");
        assertThat(en.soldOutOn()).isNull();
        assertThat(doc("listings_fr", dish).orElseThrow().name()).isEqualTo("Phở spécial");

        var today = LocalDate.parse("2026-09-30");
        jdbc.sql("update food.menu_items set sold_out_on = :d where id = :id")
                .param("d", today)
                .param("id", dish)
                .update();
        consume("food.menu", "food.item_availability", availability(dish, k.id(), menu.menuId(), false, "2026-09-30"));
        assertThat(doc("listings_en", dish).orElseThrow().soldOutOn()).isEqualTo(today);

        var until = Instant.parse("2026-09-30T20:00:00Z");
        jdbc.sql("update food.kitchen_settings set paused_until = :u where merchant_id = :m")
                .param("u", java.sql.Timestamp.from(until))
                .param("m", k.id())
                .update();
        consume("food.kitchen", "food.kitchen_paused", """
                {"eventId":"%s","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"%s","actorId":"%s",\
                "pausedUntil":"%s"}""".formatted(Events.id(), k.id(), Events.id(), until));
        assertThat(doc("listings_en", dish).orElseThrow().pausedUntil()).isEqualTo(until);
        assertThat(WorkerContainers.meilisearchClient()
                        .document("listings_en", dish)
                        .orElseThrow()
                        .path("pausedUntilEpoch")
                        .asLong())
                .isEqualTo(until.getEpochSecond());

        // a row deleted from Postgres right after it was indexed: found by the whole-merchant refresh at once
        jdbc.sql("delete from food.menu_items where id = :id").param("id", dish).update();
        consume("food.menu", "food.menu_published", menuPublished(menu.menuId(), k.id()));
        assertThat(doc("listings_en", dish)).isEmpty();
        assertThat(doc("listings_fr", dish)).isEmpty();
        assertThat(doc("listings_en", keep)).isPresent();
    }

    @Test
    void editsWithoutAnEvent_arrive_throughTheReconcileSweep() {
        var fx = fx();
        var m = fx.merchant("provider", "trusted", "Sweep Test Co");
        var service = fx.service(m, "Oil change", null, 7900, "live");
        consume("catalogue.listing", "catalogue.listing_published", listing(service, m.id(), "service"));

        jdbc.sql("update catalogue.services set price_cents = 8400, updated_at = now() where id = :id")
                .param("id", service)
                .update();
        fx.review(m, 3);

        assertThat(reconciler.sweep()).isPositive();
        var en = doc("listings_en", service).orElseThrow();
        assertThat(en.priceCents()).isEqualTo(8400L);
        assertThat(en.rating()).isEqualTo(3.0);
        assertThat(doc("listings_fr", service).orElseThrow().priceCents()).isEqualTo(8400L);
    }
}
