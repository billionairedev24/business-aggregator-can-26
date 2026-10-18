package ca.northline.search;

import ca.northline.searchindex.IndexBootstrap;
import ca.northline.searchindex.IndexLayout;
import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.ListingDocument.Completion;
import ca.northline.searchindex.ListingDocument.GeoPoint;
import ca.northline.searchindex.ListingDocument.MinuteRange;
import ca.northline.searchindex.ListingIndices;
import ca.northline.searchindex.SearchLanguage;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Elasticsearch 9 for the search API tests, with the listings indices of deploy/search, and documents shaped exactly
 * as the worker writes them ({@link ListingDocument}).
 */
final class SearchDocs {

    static final ElasticsearchContainer ELASTIC = new ElasticsearchContainer(
                    DockerImageName.parse("elasticsearch:9.1.3")
                            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(java.time.Duration.ofMinutes(4));

    private static boolean ready;

    private SearchDocs() {}

    static synchronized String start() {
        if (!ready) {
            ELASTIC.start();
            try (var es = client()) {
                new IndexBootstrap(new ListingIndices(es), IndexLayout.fromClasspath(), Clock.systemUTC())
                        .reconcile(IndexBootstrap.Mode.APPLY);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ready = true;
        }
        return "http://" + ELASTIC.getHttpHostAddress();
    }

    static ElasticsearchClient client() {
        return ElasticsearchClient.of(
                b -> b.host("http://" + ELASTIC.getHttpHostAddress()).jsonMapper(new Jackson3JsonpMapper()));
    }

    /** Indexes documents into both languages (French ones may differ) and makes them searchable at once. */
    static void index(ElasticsearchClient es, List<ListingDocument> en, List<ListingDocument> fr) {
        var ops = new ArrayList<BulkOperation>();
        en.forEach(d -> ops.add(BulkOperation.of(
                b -> b.index(i -> i.index(SearchLanguage.EN.alias()).id(d.id()).document(d)))));
        fr.forEach(d -> ops.add(BulkOperation.of(
                b -> b.index(i -> i.index(SearchLanguage.FR.alias()).id(d.id()).document(d)))));
        try {
            var response = es.bulk(b -> b.operations(ops).refresh(Refresh.WaitFor));
            if (response.errors()) {
                throw new IllegalStateException(response.items().stream()
                        .filter(i -> i.error() != null)
                        .map(i -> i.id() + ": " + i.error().reason())
                        .toList()
                        .toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final Instant NOON = Instant.parse("2026-09-30T18:00:00Z");
    static final LocalDate TODAY = LocalDate.parse("2026-09-30");
    static final int CUTOFF = 17 * 60 + 45;
    static final List<MinuteRange> WEEKDAYS_9_TO_5 = IntStream.range(0, 5)
            .mapToObj(d -> new MinuteRange(d * 1440 + 540, d * 1440 + 1020))
            .toList();

    /** The documents of both languages, as the worker would write them. */
    record Listings(List<ListingDocument> en, List<ListingDocument> fr) {}

    /**
     * The listings every search API test searches (S-44): a bakery and its loaves (B*), mechanics and tire shops (S*),
     * kitchens (F*: open, sold out, paused), one listing of another market (X1). The French index has translated names
     * where a listing has one.
     */
    static Listings listings() {
        var en = new ArrayList<ListingDocument>();
        var fr = new ArrayList<ListingDocument>();
        var b1 = doc("B1", "product", "Country sourdough", "M-GTB", "Glenmore Test Bakery", "master")
                .rating(4.8)
                .reviewCount(120)
                .priceCents(750L)
                .pricingMode("fixed")
                .fulfilment(List.of("pooled"))
                .deliveryCutoffMinute(CUTOFF)
                .inStock(true)
                .location(at(51.0447, -114.0719))
                .categoryId("cat.bakery")
                .categoryPath(List.of("shop.groceries", "cat.bakery"))
                .categoryNames(List.of("Groceries", "Bakery"))
                .suggestCategory(new Completion(List.of("Bakery"), 340))
                .build();
        en.add(b1);
        fr.add(b1.toBuilder()
                .name("Pain au levain de campagne")
                .categoryNames(List.of("Épicerie", "Boulangerie"))
                .suggest(new Completion(inputs("Pain au levain de campagne"), 340))
                .suggestCategory(new Completion(List.of("Boulangerie"), 340))
                .build());
        both(
                en,
                fr,
                doc("B2", "product", "Sourdough rye", "M-STC", "Sidewalk Test Citizen", "trusted")
                        .rating(4.5)
                        .priceCents(850L)
                        .fulfilment(List.of("pickup"))
                        .inStock(true)
                        .location(at(51.0331, -114.0592))
                        .build());
        both(
                en,
                fr,
                doc("B3", "product", "Sourdough starter kit", "M-PTP", "Prairie Test Pantry", "registered")
                        .rating(4.2)
                        .priceCents(1400L)
                        .fulfilment(List.of("pooled"))
                        .deliveryCutoffMinute(CUTOFF)
                        .inStock(false)
                        .location(at(51.10, -114.20))
                        .build());
        both(
                en,
                fr,
                doc("M-GTB", "merchant", "Glenmore Test Bakery", "M-GTB", "Glenmore Test Bakery", "master")
                        .rating(4.8)
                        .priceCents(750L)
                        .location(at(51.0447, -114.0719))
                        .categoryId("cat.bakery")
                        .categoryPath(List.of("shop.groceries", "cat.bakery"))
                        .categoryNames(List.of("Groceries", "Bakery"))
                        .build());
        var s1 = doc("S1", "service", "Mobile mechanic brake inspection", "M-PTW", "Prairie Test Wrench", "master")
                .rating(4.9)
                .priceCents(8900L)
                .pricingMode("fixed")
                .instantBook(true)
                .openHours(WEEKDAYS_9_TO_5)
                .location(at(51.0379, -114.088))
                .serviceRadiusKm(40.0)
                .categoryId("cat.mechanic")
                .categoryPath(List.of("service.auto", "cat.mechanic"))
                .categoryNames(List.of("Automotive", "Mobile mechanic"))
                .suggestCategory(new Completion(List.of("Mobile mechanic", "mechanic"), 390))
                .build();
        en.add(s1);
        fr.add(s1.toBuilder()
                .name("Inspection des freins par mécanicien mobile")
                .build());
        both(
                en,
                fr,
                doc("S2", "service", "Diagnostic scan", "M-FTG", "Far Test Garage", "registered")
                        .priceCents(12000L)
                        .pricingMode("fixed")
                        .location(at(53.5461, -113.4938))
                        .categoryId("cat.mechanic")
                        .categoryPath(List.of("service.auto", "cat.mechanic"))
                        .categoryNames(List.of("Automotive", "Mobile mechanic"))
                        .build());
        both(
                en,
                fr,
                doc("S3", "service", "Pre-purchase inspection", "M-QTE", "Quote Test Motors", "trusted")
                        .pricingMode("quote")
                        .categoryId("cat.mechanic")
                        .categoryPath(List.of("service.auto", "cat.mechanic"))
                        .categoryNames(List.of("Automotive", "Mobile mechanic"))
                        .build());
        both(
                en,
                fr,
                doc("S4", "service", "Winter tire swap", "M-TS1", "Near Test Tires", "registered")
                        .location(at(51.0447, -114.0719))
                        .build());
        both(
                en,
                fr,
                doc("S5", "service", "Winter tire swap", "M-TS2", "Far Test Tires", "registered")
                        .location(at(51.20, -114.40))
                        .build());
        both(
                en,
                fr,
                doc("S6", "service", "Snow tire storage", "M-ST1", "Master Test Storage", "master")
                        .location(at(51.05, -114.07))
                        .build());
        both(
                en,
                fr,
                doc("S7", "service", "Snow tire storage", "M-ST2", "Plain Test Storage", "registered")
                        .location(at(51.05, -114.07))
                        .build());
        var f1 = doc("F1", "food", "Pho dac biet", "M-PTK", "Pho Test Kitchen", "trusted")
                .rating(4.8)
                .priceCents(1700L)
                .dietary(List.of("halal"))
                .allergens(List.of("peanuts"))
                .openHours(everyDay(660, 1260))
                .prepMinutes(30)
                .fulfilment(List.of("courier", "pickup"))
                .location(at(51.0376, -113.987))
                .build();
        en.add(f1);
        fr.add(f1.toBuilder().name("Phở spécial").build());
        both(
                en,
                fr,
                doc("F2", "food", "Pho chay", "M-PTK", "Pho Test Kitchen", "trusted")
                        .priceCents(1500L)
                        .dietary(List.of("vegan"))
                        .openHours(everyDay(660, 1260))
                        .soldOutOn(TODAY)
                        .location(at(51.0376, -113.987))
                        .build());
        both(
                en,
                fr,
                doc("F3", "food", "Pho late night", "M-PAU", "Paused Test Kitchen", "trusted")
                        .priceCents(1600L)
                        .openHours(everyDay(660, 1260))
                        .pausedUntil(Instant.parse("2026-09-30T20:00:00Z"))
                        .build());
        both(
                en,
                fr,
                doc("X1", "product", "Sourdough BC loaf", "M-BC1", "Island Test Bakery", "master")
                        .market("BC")
                        .priceCents(900L)
                        .build());
        return new Listings(List.copyOf(en), List.copyOf(fr));
    }

    static void both(List<ListingDocument> en, List<ListingDocument> fr, ListingDocument doc) {
        en.add(doc);
        fr.add(doc);
    }

    /** A document with everything a search needs; tests change what they look at. */
    static ListingDocument.ListingDocumentBuilder doc(
            String id, String kind, String name, String merchantId, String merchantName, String tier) {
        var rank = switch (tier) {
            case "master" -> 3;
            case "trusted" -> 2;
            default -> 1;
        };
        return ListingDocument.builder()
                .id(id)
                .kind(kind)
                .market("AB")
                .merchantId(merchantId)
                .merchantName(merchantName)
                .merchantType(kind.equals("food") ? "kitchen" : kind.equals("service") ? "provider" : "seller")
                .merchantStatus("active")
                .merchantSlug(merchantName.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'))
                .name(name)
                .categoryPath(List.of())
                .categoryNames(List.of())
                .trustTier(tier)
                .trustRank(rank)
                .rating(4.0)
                .reviewCount(10)
                .vetting("approved")
                .status("live")
                .fulfilment(List.of())
                .openHours(List.of())
                .allergens(List.of())
                .dietary(List.of())
                .updatedAt(Instant.parse("2026-09-30T12:00:00Z"))
                .suggest(new Completion(inputs(name), rank * 100 + 40));
    }

    static GeoPoint at(double lat, double lng) {
        return new GeoPoint(lat, lng);
    }

    /** Monday–Sunday {@code from}–{@code to} (minutes of the day). */
    static List<MinuteRange> everyDay(int from, int to) {
        var ranges = new ArrayList<MinuteRange>();
        for (var day = 0; day < 7; day++) {
            ranges.add(new MinuteRange(day * 1440 + from, day * 1440 + to));
        }
        return ranges;
    }

    static List<String> inputs(String name) {
        var words = Arrays.asList(name.split(" "));
        var out = new ArrayList<String>();
        for (var i = 0; i < words.size(); i++) {
            out.add(String.join(" ", words.subList(i, words.size())));
        }
        return out;
    }

    static @Nullable LocalDate date(@Nullable String iso) {
        return iso == null ? null : LocalDate.parse(iso);
    }
}
