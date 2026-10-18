package ca.northline.searchindex;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.searchindex.ListingDocument.Completion;
import ca.northline.searchindex.ListingDocument.GeoPoint;
import ca.northline.searchindex.ListingDocument.MinuteRange;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ListingDocument ↔ Meilisearch document: everything survives the trip, and the derived fields are right. */
class MeilisearchDocumentsTest {

    static ListingDocument dish() {
        return ListingDocument.builder()
                .id("01J9ZD3V00000000000000DSH1")
                .kind("food")
                .market("AB")
                .merchantId("01J9ZD3V00000000000000PDB1")
                .merchantName("Pho \"Test\" Kitchen")
                .merchantType("kitchen")
                .merchantStatus("active")
                .name("Phở spécial")
                .categoryId("food.vietnamese")
                .categoryPath(List.of("food.vietnamese"))
                .categoryNames(List.of("Vietnamese"))
                .priceCents(1700L)
                .rating(4.8)
                .reviewCount(12)
                .trustTier("trusted")
                .trustRank(2)
                .vetting("approved")
                .status("live")
                .fulfilment(List.of("courier", "pickup"))
                .soldOutOn(LocalDate.parse("2026-09-30"))
                .openHours(List.of(new MinuteRange(660, 1260), new MinuteRange(1440 + 670, 1440 + 1265)))
                .pausedUntil(Instant.parse("2026-09-30T20:00:00Z"))
                .prepMinutes(30)
                .allergens(List.of("peanuts"))
                .dietary(List.of("halal"))
                .location(new GeoPoint(51.0376, -113.987))
                .updatedAt(Instant.parse("2026-09-30T18:00:00.123456Z"))
                .suggest(new Completion(List.of("Phở spécial", "spécial"), 240))
                .suggestCategory(new Completion(List.of("Vietnamese"), 240))
                .build();
    }

    @Test
    void roundTrip_keepsEveryField() {
        var doc = dish();
        var meili = MeilisearchDocuments.toMeilisearch(doc);
        assertThat(MeilisearchDocuments.fromMeilisearch(meili)).isEqualTo(doc);
        var bare = doc.toBuilder()
                .location(null)
                .pausedUntil(null)
                .soldOutOn(null)
                .priceCents(null)
                .categoryId(null)
                .suggestCategory(null)
                .build();
        var node = MeilisearchDocuments.toMeilisearch(bare);
        assertThat(node.has("priceCents")).isFalse(); // missing, not null: sorts last, NOT-filters include it
        assertThat(MeilisearchDocuments.fromMeilisearch(node)).isEqualTo(bare);
    }

    @Test
    void derivedFields() {
        var node = MeilisearchDocuments.toMeilisearch(dish());
        assertThat(node.path("_geo").path("lat").asDouble()).isEqualTo(51.0376);
        assertThat(node.path("_geo").path("lng").asDouble()).isEqualTo(-113.987);
        assertThat(node.path("hasLocation").asBoolean()).isTrue();
        assertThat(node.path("pausedUntilEpoch").asLong())
                .isEqualTo(Instant.parse("2026-09-30T20:00:00Z").getEpochSecond());
        assertThat(node.path("priceBucket").asString()).isEqualTo("10_25");
        assertThat(node.path("merchantFacet").asString())
                .isEqualTo("01J9ZD3V00000000000000PDB1\u001fPho \"Test\" Kitchen");
        assertThat(node.path("categoryFacet").asString()).isEqualTo("food.vietnamese\u001fVietnamese");
        assertThat(node.path("suggestWeight").asInt()).isEqualTo(240);
        assertThat(node.path("categorySuggest").asBoolean()).isTrue();
        assertThat(node.path("soldOutOn").asString()).isEqualTo("2026-09-30");
    }

    @Test
    void openSlots_areTheQuarterHoursCoveredEntirely() {
        var slots = MeilisearchDocuments.openSlots(dish());
        // Monday 11:00–21:00: slots 44 … 83; Tuesday 11:10–21:05: from 11:15 (slot 96 + 45) to 21:00 (96 + 83)
        assertThat(slots).contains(44, 83, 96 + 45, 96 + 83).doesNotContain(43, 84, 96 + 44, 96 + 84);
        assertThat(slots).hasSize(40 + 39);
        assertThat(MeilisearchDocuments.slot(11 * 60 + 14)).isEqualTo(44);
    }

    @Test
    void priceBuckets_andQuoting() {
        assertThat(MeilisearchDocuments.priceBucket(999)).isEqualTo("under_10");
        assertThat(MeilisearchDocuments.priceBucket(1000)).isEqualTo("10_25");
        assertThat(MeilisearchDocuments.priceBucket(9999)).isEqualTo("50_100");
        assertThat(MeilisearchDocuments.priceBucket(10_000)).isEqualTo("100_plus");
        assertThat(MeilisearchDocuments.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }
}
