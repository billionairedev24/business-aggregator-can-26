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
