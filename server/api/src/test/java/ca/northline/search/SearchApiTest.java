package ca.northline.search;

import static ca.northline.search.SearchDocs.at;
import static ca.northline.search.SearchDocs.doc;
import static ca.northline.search.SearchDocs.everyDay;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.ListingDocument.Completion;
import ca.northline.searchindex.ListingDocument.MinuteRange;
import ca.northline.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * S-44: the public search API against Elasticsearch 9 (Testcontainers) holding documents shaped as the worker writes
 * them. The clock is Wednesday 30 September 2026, 12:00 in Edmonton. Every request is anonymous.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SearchApiTest.FixedClock.class)
@DirtiesContext // its own context (Elasticsearch, fixed clock): closed afterwards so the cached ones fit in the heap
class SearchApiTest extends IntegrationTest {

    static final Instant NOON = Instant.parse("2026-09-30T18:00:00Z");
    static final LocalDate TODAY = LocalDate.parse("2026-09-30");
    static final int CUTOFF = 17 * 60 + 45;
    static final List<MinuteRange> WEEKDAYS_9_TO_5 = IntStream.range(0, 5)
            .mapToObj(d -> new MinuteRange(d * 1440 + 540, d * 1440 + 1020))
            .toList();

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock searchTestClock() {
            return Clock.fixed(NOON, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void elasticsearch(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", SearchDocs::start);
        registry.add("northline.search.provider", () -> "elasticsearch");
        registry.add("northline.search.rate-limit", () -> "60");
    }

    @BeforeAll
    void documents() throws Exception {
        SearchDocs.start();
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
                .suggest(new Completion(SearchDocs.inputs("Pain au levain de campagne"), 340))
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
        try (var es = SearchDocs.client()) {
            SearchDocs.index(es, en, fr);
        }
    }

    private static void both(List<ListingDocument> en, List<ListingDocument> fr, ListingDocument doc) {
        en.add(doc);
        fr.add(doc);
    }

    // ── text, languages, market ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void text_rankedByTrustAndRating_onlyTheMarket_anonymous() throws Exception {
        var body = search("q=sourdough");
        assertThat(ids(body)).containsExactly("B1", "B2", "B3");
        assertThat((Integer) JsonPath.read(body, "$.total")).isEqualTo(3);
        mvc.perform(get("/api/v1/search")
                        .param("q", "sourdough")
                        .param("market", "BC")
                        .with(from("10.0.0.2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("X1"));
    }

    @Test
    void theLanguagePicksTheIndex_andSynonymsCrossLanguages() throws Exception {
        var fr = search("q=pain au levain&lang=fr");
        assertThat(ids(fr)).contains("B1", "B2");
        assertThat((String) JsonPath.read(fr, "$.items[0].name")).isEqualTo("Pain au levain de campagne");
        var accept = mvc.perform(get("/api/v1/search")
                        .param("q", "mecanicien")
                        .header("Accept-Language", "fr-CA,fr;q=0.9")
                        .with(from("10.0.0.3")))
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
        assertThat((List<Object>) JsonPath.read(relevance, "$.items[?(@.id=='S3')].distanceKm"))
                .containsExactly((Object) null);
    }

    @Test
    void nearnessAndTrustTier_boostRelevance() throws Exception {
        assertThat(ids(search("q=winter tire swap&lat=51.0447&lng=-114.0719"))).containsExactly("S4", "S5");
        assertThat(ids(search("q=winter tire swap&lat=51.20&lng=-114.40"))).containsExactly("S5", "S4");
        assertThat(ids(search("q=snow tire storage"))).containsExactly("S6", "S7");
    }

    // ── pages and facets ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void searchAfterPages_coverEverythingOnce_inOrder() throws Exception {
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
        assertThat((List<Integer>) JsonPath.read(body, "$.facets.prices[?(@.value=='under_10')].count"))
                .containsExactly(2);
        assertThat((List<Integer>) JsonPath.read(body, "$.facets.prices[?(@.value=='10_25')].count"))
                .containsExactly(1);
        assertThat((List<String>) JsonPath.read(body, "$.facets.tiers[*].value"))
                .containsExactlyInAnyOrder("master", "trusted", "registered");
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
    }

    // ── rules, limits, cache, contract ──────────────────────────────────────────────────────────────────────────

    @Test
    void validationMessages() throws Exception {
        invalid("q=" + "x".repeat(101), "q", "Search for 100 characters or fewer.");
        invalid("market=YT", "market", "Search isn't available in YT yet."); // well-formed, not in SEARCH_MARKETS
        invalid("market=A1", "market", "Use a two-letter province or territory code.");
        invalid("kind=boat", "kind", "Choose service, product, food or merchant.");
        invalid("tier=gold", "tier", "Choose registered, trusted or master.");
        invalid("sort=cheapest", "sort", "Sort by relevance, distance, price_asc, price_desc or rating.");
        invalid("sort=distance", "sort", "Sorting by distance needs your location (lat and lng).");
        invalid("lat=51", "lng", "Send both lat and lng, or neither.");
        invalid("lat=91&lng=0", "lat", "Latitude must be between -90 and 90.");
        invalid("radiusKm=10", "radiusKm", "A distance filter needs your location (lat and lng).");
        invalid("radiusKm=500&lat=51&lng=-114", "radiusKm", "Choose a distance between 1 and 100 km.");
        invalid("minPrice=2000&maxPrice=1000", "minPrice", "The lowest price is above the highest.");
        invalid("minPrice=-1", "minPrice", "Prices can't be negative.");
        invalid("minRating=6", "minRating", "Choose a rating between 1 and 5.");
        invalid("size=51", "size", "Ask for 1 to 50 results.");
        invalid("after=relevance.bm90LWpzb24", "after", "This page link no longer works. Start the search again.");
        var priceToken = (String) JsonPath.read(search("sort=price_asc&size=1"), "$.next");
        invalid("after=" + priceToken, "after", "This page link no longer works. Start the search again.");
        mvc.perform(get("/api/v1/search/suggest").param("q", " ").with(from("10.0.0.9")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("q"))
                .andExpect(jsonPath("$.errors[0].message").value("Type at least one letter."));
    }

    @Test
    void anonymousCallersAreRateLimitedPerAddress() throws Exception {
        for (var i = 0; i < 60; i++) {
            mvc.perform(get("/api/v1/search/suggest").param("q", "sour").with(from("10.66.0.1")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/search").param("q", "sour").with(from("10.66.0.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
        mvc.perform(get("/api/v1/search").param("q", "sour").with(from("10.66.0.2")))
                .andExpect(status().isOk());
        // behind the ingress/BFF: the right-most public X-Forwarded-For hop, not the proxy's own address
        for (var i = 0; i < 60; i++) {
            mvc.perform(get("/api/v1/search/suggest")
                            .param("q", "sour")
                            .header("X-Forwarded-For", "203.0.113.7, 10.9.0.4")
                            .with(from("10.9.0.5")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/search/suggest")
                        .param("q", "sour")
                        .header("X-Forwarded-For", "1.2.3.4, 203.0.113.7")
                        .with(from("10.9.0.5")))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/search/suggest")
                        .param("q", "sour")
                        .header("X-Forwarded-For", "203.0.113.8")
                        .with(from("10.9.0.5")))
                .andExpect(status().isOk());
    }

    @Test
    void hotQueriesAreAnsweredFromTheCache() throws Exception {
        try (var es = SearchDocs.client()) {
            SearchDocs.index(
                    es,
                    List.of(doc("C1", "product", "Cache test loaf", "M-C", "Cache Test", "registered")
                            .build()),
                    List.of());
            assertThat(ids(search("q=cache test loaf"))).containsExactly("C1");
            es.delete(
                    d -> d.index("listings_en").id("C1").refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
        }
        assertThat(ids(search("q=cache test loaf"))).containsExactly("C1"); // within 30 s: the cached answer
        assertThat(ids(search("q=cache test loaf&size=5"))).isEmpty(); // another question: the index
    }

    @Test
    void theOpenApiDescriptionHasBothEndpoints() throws Exception {
        var docs = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((List<String>) JsonPath.read(docs, "$.paths['/api/v1/search'].get.parameters[*].name"))
                .contains(
                        "q",
                        "market",
                        "lang",
                        "kind",
                        "category",
                        "openNow",
                        "delivery",
                        "lat",
                        "lng",
                        "radiusKm",
                        "sort",
                        "size",
                        "after");
        assertThat((List<String>) JsonPath.read(docs, "$.paths['/api/v1/search/suggest'].get.parameters[*].name"))
                .contains("q");
        assertThat(docs).contains("SearchResponse", "SuggestResponse");
    }

    @Test
    void p95UnderOneHundredFiftyMilliseconds_onTheSeededIndex() throws Exception {
        for (var i = 0; i < 10; i++) {
            search("q=sourdough&minPrice=" + i); // warm-up, distinct keys (no cache)
        }
        var queries = List.of(
                "q=sourdough",
                "q=pho&openNow=true",
                "kind=service&lat=51.04&lng=-114.07&sort=distance",
                "q=mechanic&tier=master",
                "delivery=tonight",
                "q=tire&lat=51.2&lng=-114.4");
        // the build runs other test JVMs beside this one: the best of three rounds of 60 uncached queries counts
        var rounds = new ArrayList<List<Long>>();
        for (var round = 0; round < 3; round++) {
            var timings = new ArrayList<Long>();
            for (var i = 0; i < 60; i++) {
                var q = queries.get(i % queries.size()) + "&minPrice=" + (1000 * round + 100 + i);
                var start = System.nanoTime();
                search(q);
                timings.add((System.nanoTime() - start) / 1_000_000);
            }
            timings.sort(Long::compare);
            rounds.add(timings);
            if (timings.get(56) < 150) {
                break;
            }
        }
        var best = rounds.stream().mapToLong(t -> t.get(56)).min().orElseThrow(); // 57th of 60 = p95
        assertThat(best).as("p95 per round: %s", rounds).isLessThan(150);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private String search(String query) throws Exception {
        return perform("/api/v1/search?" + query);
    }

    private String suggest(String query) throws Exception {
        return perform("/api/v1/search/suggest?" + query);
    }

    private String perform(String url) throws Exception {
        var n = ADDRESSES.incrementAndGet();
        MockHttpServletRequestBuilder request = get(url).with(from("10.1." + (n / 250) + "." + (n % 250)));
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void invalid(String query, String field, String message) throws Exception {
        mvc.perform(get("/api/v1/search?" + query).with(from("10.0.0.8")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "')].message")
                        .value(hasItem(message)));
    }

    private static List<String> ids(String body) {
        return JsonPath.read(body, "$.items[*].id");
    }

    /** Requests from one client address (the rate limit counts per address; tests use their own). */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static final java.util.concurrent.atomic.AtomicInteger ADDRESSES =
            new java.util.concurrent.atomic.AtomicInteger();
}
