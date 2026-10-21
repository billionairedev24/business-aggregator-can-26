package ca.northline.search;

import static ca.northline.search.SearchDocs.doc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.searchindex.SearchLanguage;
import ca.northline.support.IntegrationTest;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The public search API on the {@code meilisearch} provider: {@link SearchApiTest}'s searches against Meilisearch
 * (Testcontainers, a master key) holding the same documents ({@link SearchDocs#listings}), written as the worker writes
 * them. The same answers, except where Meilisearch ranks differently — each such assertion says so (docs/DECISIONS.md,
 * Meilisearch search provider). The engine-independent parts (rate limits, OpenAPI) stay in {@link SearchApiTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(MeilisearchSearchApiTest.FixedClock.class)
@DirtiesContext // its own context (Meilisearch, fixed clock): closed afterwards so the cached ones fit in the heap
class MeilisearchSearchApiTest extends IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock searchTestClock() {
            return Clock.fixed(SearchDocs.NOON, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void meilisearch(DynamicPropertyRegistry registry) {
        registry.add("northline.search.provider", () -> "meilisearch");
        registry.add("northline.search.meilisearch.url", MeilisearchDocs::start);
        registry.add("northline.search.meilisearch.api-key", () -> MeilisearchDocs.MASTER_KEY);
    }

    @BeforeAll
    void documents() {
        MeilisearchDocs.start();
        var listings = SearchDocs.listings();
        MeilisearchDocs.index(listings.en(), listings.fr());
    }

    // ── text, languages, market ─────────────────────────────────────────────────────────────────────────────────

    @Autowired
    ApplicationContext context;

    /** S-163: no Elasticsearch client (nor its connection pool or health check) with Meilisearch (SearchProviderImportFilter). */
    @Test
    void noElasticsearchClient() {
        assertThat(context.getBeanNamesForType(ElasticsearchClient.class)).isEmpty();
    }

    /**
     * Meilisearch ranks a word at the start of the name above the same word later in it (its attribute rule weighs
     * the position) before the trust tier; Elasticsearch's B1, B2, B3 is trust first. Same-position matches go by
     * tier (B2 trusted before B3 registered).
     */
    @Test
    void text_samePositionRankedByTrust_onlyTheMarket() throws Exception {
        var body = search("q=sourdough");
        assertThat(ids(body)).containsExactly("B2", "B3", "B1");
        assertThat((Integer) JsonPath.read(body, "$.total")).isEqualTo(3);
        assertThat(ids(search("q=sourdough&market=BC"))).containsExactly("X1");
    }

    @Test
    void theLanguagePicksTheIndex_andSynonymsCrossLanguages() throws Exception {
        var fr = search("q=pain au levain&lang=fr");
        assertThat(ids(fr)).contains("B1", "B2");
        assertThat((String) JsonPath.read(fr, "$.items[0].name")).isEqualTo("Pain au levain de campagne");
        var accept = mvc.perform(get("/api/v1/search")
                        .param("q", "mecanicien")
                        .header("Accept-Language", "fr-CA,fr;q=0.9")
                        .with(from(address())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(ids(accept)).startsWith("S1");
        assertThat((String) JsonPath.read(accept, "$.items[0].name")).contains("mécanicien");
    }

    @Test
    void anItemCarriesWhatTheCardShows() throws Exception {
        var body = search("q=country sourdough&lat=51.0447&lng=-114.0719");
        var item = "$.items[0]";
        assertThat((String) JsonPath.read(body, item + ".id")).isEqualTo("B1");
        assertThat((String) JsonPath.read(body, item + ".kind")).isEqualTo("product");
        assertThat((String) JsonPath.read(body, item + ".merchant.name")).isEqualTo("Glenmore Test Bakery");
        assertThat((String) JsonPath.read(body, item + ".merchant.tier")).isEqualTo("master");
        assertThat((Integer) JsonPath.read(body, item + ".priceCents")).isEqualTo(750);
        assertThat((Double) JsonPath.read(body, item + ".distanceKm")).isEqualTo(0.0);
        assertThat((Boolean) JsonPath.read(body, item + ".onTonightsRun")).isTrue();
        assertThat((String) JsonPath.read(body, item + ".category.name")).isEqualTo("Bakery");
    }

    // ── filters ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void openNow_excludesClosedPausedAndSoldOut() throws Exception {
        assertThat(ids(search("kind=food&openNow=true"))).containsExactly("F1");
        var all = search("kind=food&sort=price_asc");
        assertThat(ids(all)).containsExactly("F2", "F3", "F1");
        assertThat((List<Boolean>) JsonPath.read(all, "$.items[*].soldOut")).containsExactly(true, false, false);
        assertThat((List<Boolean>) JsonPath.read(all, "$.items[*].openNow")).containsExactly(false, false, true);
        assertThat(ids(search("kind=service&openNow=true"))).containsExactly("S1"); // Wednesday 12:00, 9–5
    }

    @Test
    void tonightsRun_dietary_allergens_price_rating_tier_instantBook_category() throws Exception {
        assertThat(ids(search("delivery=tonight"))).containsExactly("B1");
        assertThat(ids(search("dietary=halal"))).containsExactly("F1");
        assertThat(ids(search("kind=food&allergenFree=peanuts&sort=price_asc"))).containsExactly("F2", "F3");
        assertThat(ids(search("kind=product&minPrice=800&maxPrice=1500&sort=price_asc")))
                .containsExactly("B2", "B3");
        assertThat(ids(search("q=sourdough&minRating=4.6"))).containsExactly("B1");
        assertThat(ids(search("tier=master&kind=product,merchant&sort=price_asc")))
                .containsExactlyInAnyOrder("B1", "M-GTB");
        assertThat(ids(search("instantBook=true"))).containsExactly("S1");
        assertThat(ids(search("category=service.auto&sort=price_asc"))).containsExactly("S1", "S2", "S3");
        assertThat(ids(search("category=cat.bakery"))).containsExactlyInAnyOrder("B1", "M-GTB");
        // a value is quoted into the filter, never parsed as filter syntax
        var injected = mvc.perform(get("/api/v1/search")
                        .param("category", "cat.bakery\" OR market = \"BC")
                        .with(from(address())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(ids(injected)).isEmpty();
    }

    // ── location ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void distances_distanceSort_andRadius() throws Exception {
        var near = "lat=51.0447&lng=-114.0719&category=service.auto";
        var byDistance = search(near + "&sort=distance");
        assertThat(ids(byDistance)).containsExactly("S1", "S2"); // S3 has no location
        var distances = (List<Double>) JsonPath.read(byDistance, "$.items[*].distanceKm");
        assertThat(distances.get(0)).isBetween(1.0, 1.6);
        assertThat(distances.get(1)).isBetween(240.0, 300.0);
        assertThat(ids(search(near + "&radiusKm=50"))).containsExactly("S1");
        var relevance = search(near);
        assertThat(ids(relevance)).contains("S3");
        assertThat(ids(relevance).getLast()).isEqualTo("S3"); // no location: after every one that has one
        assertThat((List<Object>) JsonPath.read(relevance, "$.items[?(@.id=='S3')].distanceKm"))
                .containsExactly((Object) null);
    }

    @Test
    void nearnessAndTrustTier_orderRelevance() throws Exception {
        assertThat(ids(search("q=winter tire swap&lat=51.0447&lng=-114.0719"))).containsExactly("S4", "S5");
        assertThat(ids(search("q=winter tire swap&lat=51.20&lng=-114.40"))).containsExactly("S5", "S4");
        assertThat(ids(search("q=snow tire storage"))).containsExactly("S6", "S7");
    }

    // ── pages and facets ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void pages_coverEverythingOnce_inOrder() throws Exception {
        var first = search("sort=price_asc&size=4");
        var total = (Integer) JsonPath.read(first, "$.total");
        var seen = new ArrayList<String>(ids(first));
        var prices = new ArrayList<Integer>((List<Integer>) JsonPath.read(first, "$.items[*].priceCents"));
        String next = JsonPath.read(first, "$.next");
        while (next != null) {
            var page = search("sort=price_asc&size=4&after=" + next);
            seen.addAll(ids(page));
            prices.addAll((List<Integer>) JsonPath.read(page, "$.items[*].priceCents"));
            assertThat((List<?>) JsonPath.read(page, "$.facets.kinds")).isEmpty();
            next = JsonPath.read(page, "$.next");
        }
        assertThat(seen).hasSize(total).doesNotHaveDuplicates().doesNotContain("X1");
        var priced = prices.stream().filter(java.util.Objects::nonNull).toList();
        assertThat(priced).isSorted();
        assertThat(seen.subList(priced.size(), seen.size())).isNotEmpty(); // unpriced last
        // a page of another size continues from the same place (offset paging)
        var three = search("sort=price_asc&size=3");
        var rest = search("sort=price_asc&size=5&after=" + JsonPath.read(three, "$.next"));
        assertThat(ids(rest)).containsExactlyElementsOf(seen.subList(3, 8));
    }

    @Test
    void facetsOnTheFirstPage() throws Exception {
        var body = search("q=sourdough");
        assertThat((List<String>) JsonPath.read(body, "$.facets.kinds[*].value"))
                .containsExactly("product");
        assertThat((List<Integer>) JsonPath.read(body, "$.facets.kinds[*].count"))
                .containsExactly(3);
        assertThat((List<String>) JsonPath.read(body, "$.facets.merchants[*].label"))
                .containsExactlyInAnyOrder("Glenmore Test Bakery", "Sidewalk Test Citizen", "Prairie Test Pantry");
        assertThat((List<String>) JsonPath.read(body, "$.facets.merchants[*].value"))
                .containsExactlyInAnyOrder("M-GTB", "M-STC", "M-PTP");
        assertThat((List<Integer>) JsonPath.read(body, "$.facets.prices[?(@.value=='under_10')].count"))
                .containsExactly(2);
        assertThat((List<Integer>) JsonPath.read(body, "$.facets.prices[?(@.value=='10_25')].count"))
                .containsExactly(1);
        assertThat((List<String>) JsonPath.read(body, "$.facets.tiers[*].value"))
                .containsExactlyInAnyOrder("master", "trusted", "registered");
        var categories = search("category=service.auto");
        assertThat((List<String>) JsonPath.read(categories, "$.facets.categories[*].value"))
                .containsExactly("cat.mechanic");
        assertThat((List<String>) JsonPath.read(categories, "$.facets.categories[*].label"))
                .containsExactly("Mobile mechanic");
    }

    // ── suggestions ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void suggestions_withTheTypedPartToHighlight() throws Exception {
        var sour = suggest("q=sour");
        assertThat((List<String>) JsonPath.read(sour, "$.items[*].text"))
                .contains("Country sourdough", "Sourdough rye", "Sourdough starter kit")
                .doesNotContain("Sourdough BC loaf");
        assertThat((List<Map<String, Object>>)
                        JsonPath.read(sour, "$.items[?(@.text=='Country sourdough')].highlight[0]"))
                .containsExactly(Map.of("start", 8, "length", 4));
        assertThat((List<String>) JsonPath.read(sour, "$.items[?(@.text=='Country sourdough')].type"))
                .containsExactly("product");

        var mec = suggest("q=méc");
        assertThat((List<String>) JsonPath.read(mec, "$.items[*].text"))
                .contains("Mobile mechanic brake inspection", "Mobile mechanic");
        assertThat((List<String>) JsonPath.read(mec, "$.items[?(@.text=='Mobile mechanic')].type"))
                .containsExactly("category");
        assertThat((List<Map<String, Object>>) JsonPath.read(mec, "$.items[?(@.text=='Mobile mechanic')].highlight[0]"))
                .containsExactly(Map.of("start", 7, "length", 3));

        assertThat((List<String>) JsonPath.read(suggest("q=glen"), "$.items[?(@.type=='merchant')].text"))
                .containsExactly("Glenmore Test Bakery");
        assertThat((List<String>) JsonPath.read(suggest("q=pain&lang=fr"), "$.items[*].text"))
                .contains("Pain au levain de campagne");
        assertThat((List<String>) JsonPath.read(suggest("q=sour&kind=service"), "$.items[*].text"))
                .isEmpty();
        // the heaviest suggestion first (the completion weight: trust tier, rating, sales)
        assertThat((List<String>) JsonPath.read(suggest("q=snow"), "$.items[*].text"))
                .startsWith("Snow tire storage")
                .doesNotHaveDuplicates();
    }

    // ── rules, cache ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void validationMessages_andPageTokens() throws Exception {
        invalid("after=relevance.bm90LWpzb24", "after", "This page link no longer works. Start the search again.");
        invalid("after=relevance.WzBd", "after", "This page link no longer works. Start the search again."); // [0]
        var priceToken = (String) JsonPath.read(search("sort=price_asc&size=1"), "$.next");
        invalid("after=" + priceToken, "after", "This page link no longer works. Start the search again.");
        invalid("sort=distance", "sort", "Sorting by distance needs your location (lat and lng).");
        invalid("market=YT", "market", "Search isn't available in YT yet.");
    }

    @Test
    void hotQueriesAreAnsweredFromTheCache() throws Exception {
        MeilisearchDocs.index(
                List.of(doc("C1", "product", "Cache test loaf", "M-C", "Cache Test", "registered")
                        .build()),
                List.of());
        assertThat(ids(search("q=cache test loaf"))).containsExactly("C1");
        var meili = MeilisearchDocs.client();
        meili.await(meili.deleteDocuments(SearchLanguage.EN.alias(), List.of("C1")));
        assertThat(ids(search("q=cache test loaf"))).containsExactly("C1"); // within 30 s: the cached answer
        assertThat(ids(search("q=cache test loaf&size=5"))).isEmpty(); // another question: the index
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private String search(String query) throws Exception {
        return perform("/api/v1/search?" + query);
    }

    private String suggest(String query) throws Exception {
        return perform("/api/v1/search/suggest?" + query);
    }

    private String perform(String url) throws Exception {
        return mvc.perform(get(url).with(from(address())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void invalid(String query, String field, String message) throws Exception {
        mvc.perform(get("/api/v1/search?" + query).with(from(address())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "')].message")
                        .value(hasItem(message)));
    }

    private static List<String> ids(String body) {
        return JsonPath.read(body, "$.items[*].id");
    }

    /** A fresh client address per request (the rate limit counts per address). */
    private static String address() {
        var n = ADDRESSES.incrementAndGet();
        return "10.2." + (n / 250) + "." + (n % 250);
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static final AtomicInteger ADDRESSES = new AtomicInteger();
}
