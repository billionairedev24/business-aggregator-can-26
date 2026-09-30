package ca.northline.catalogue.adapters.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.catalogue.adapters.commerce.CommerceHttp.Backoff;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

/** S-35 adapter details that need no HTTP: throttling maths, back-off, signatures, mapping. */
class CommerceAdaptersTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void shopifyWaitsUntilTheBucketCanPayForTheQuery() {
        var cost = JSON.readTree("""
                {"requestedQueryCost":152,"throttleStatus":{"maximumAvailable":2000,"currentlyAvailable":2,"restoreRate":100}}""");
        assertThat(ShopifyCatalogSource.waitFor(cost)).isEqualTo(Duration.ofMillis(1500));
        var plenty = JSON.readTree("""
                {"requestedQueryCost":52,"throttleStatus":{"currentlyAvailable":1900,"restoreRate":100}}""");
        assertThat(ShopifyCatalogSource.waitFor(plenty)).isZero();
        assertThat(ShopifyCatalogSource.throttled(JSON.readTree("[{\"extensions\":{\"code\":\"THROTTLED\"}}]")))
                .isTrue();
        assertThat(ShopifyCatalogSource.throttled(JSON.readTree("[{\"message\":\"x\"}]")))
                .isFalse();
    }

    @Test
    void backoffHonoursRetryAfterAndGivesUpAfterTheLimit() {
        var waits = new ArrayList<Duration>();
        var backoff = new Backoff(2, Duration.ofSeconds(30), waits::add, CLOCK);
        var calls = new AtomicInteger();
        var result = backoff.call("t", () -> {
            if (calls.incrementAndGet() == 1) {
                throw tooMany("7");
            }
            if (calls.get() == 2) {
                throw tooMany("Wed, 30 Sep 2026 12:00:03 GMT");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(waits).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(3));

        waits.clear();
        assertThatThrownBy(() -> backoff.call("t", () -> {
                    throw tooMany(null);
                }))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        assertThat(waits).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1)); // exponential without a hint

        waits.clear();
        var capped = new Backoff(1, Duration.ofSeconds(2), waits::add, CLOCK);
        assertThat(capped.call("t", once(tooMany("2026-09-30T12:01:00Z")))).isEqualTo("ok");
        assertThat(waits).containsExactly(Duration.ofSeconds(2)); // ISO instant (Lightspeed), capped
    }

    private static java.util.function.Supplier<String> once(RuntimeException first) {
        var thrown = new AtomicInteger();
        return () -> {
            if (thrown.getAndIncrement() == 0) {
                throw first;
            }
            return "ok";
        };
    }

    private static HttpClientErrorException tooMany(String retryAfter) {
        var headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.add("Retry-After", retryAfter);
        }
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "429", headers, new byte[0], null);
    }

    @Test
    void shopifyCallbackSignatureCoversEveryParameterButHmacSorted() {
        var signed = ShopifyCatalogSource.signedQuery(
                Map.of("state", "s", "shop", "a.myshopify.com", "hmac", "x", "code", "c", "timestamp", "1"));
        assertThat(new String(signed, StandardCharsets.UTF_8))
                .isEqualTo("code=c&shop=a.myshopify.com&state=s&timestamp=1");
    }

    @Test
    void lightspeedSignatureHeaderAndFormBody() {
        assertThat(LightspeedCatalogSource.signature("signature=abc123, algorithm=HMAC-SHA256"))
                .isEqualTo("abc123");
        assertThat(LightspeedCatalogSource.signature("algorithm=HMAC-SHA256")).isNull();
        assertThat(LightspeedCatalogSource.parseForm("payload=%7B%22id%22%3A1%7D&type=product.update"))
                .containsEntry("payload", "{\"id\":1}")
                .containsEntry("type", "product.update");
        assertThat(LightspeedCatalogSource.DOMAIN_PREFIX.matcher("prairieparts").matches())
                .isTrue();
        assertThat(LightspeedCatalogSource.DOMAIN_PREFIX
                        .matcher("evil.example.com")
                        .matches())
                .isFalse();
    }

    @Test
    void imagesComeOnlyFromThePlatformsHostsOverHttps() {
        var fetcher = new HttpImageFetcher(new CommerceProperties.Images(List.of("cdn.shopify.com"), false));
        assertThat(fetcher.allowed(URI.create("https://cdn.shopify.com/s/files/a.png")))
                .isTrue();
        assertThat(fetcher.allowed(URI.create("https://x.cdn.shopify.com/a.png")))
                .isTrue();
        assertThat(fetcher.allowed(URI.create("http://cdn.shopify.com/a.png"))).isFalse();
        assertThat(fetcher.allowed(URI.create("https://cdn.shopify.com.evil.test/a.png")))
                .isFalse();
        assertThat(fetcher.allowed(URI.create("https://169.254.169.254/latest/meta-data")))
                .isFalse();
        assertThat(fetcher.allowed(URI.create("https://user@cdn.shopify.com/a.png")))
                .isFalse();
    }

    @Test
    void lightspeedGroupsVariantsIntoFamilies() {
        var rows = JSON.readTree("""
                [{"id":"p","name":"Chains","has_variants":true,"is_active":true},
                 {"id":"v1","variant_parent_id":"p","variant_name":"15 in","sku":"C15","price_excluding_tax":"19.99",
                  "variant_options":[{"name":"Size","value":"15 in"}],"is_active":true},
                 {"id":"v2","variant_parent_id":"p","sku":"C16","price_excluding_tax":21,"is_active":true,
                  "deleted_at":"2026-09-01T00:00:00+00:00"},
                 {"id":"s","name":"Single","sku":"S1","price_excluding_tax":5.5,"is_active":true,
                  "product_codes":[{"type":"EAN","code":"4006381333931"}]}]""");
        var list = new ArrayList<tools.jackson.databind.JsonNode>();
        rows.forEach(list::add);
        var families = LightspeedCatalogSource.families(list, Map.of("v1", 3, "s", 2));
        assertThat(families).hasSize(2);
        var chains = families.getFirst();
        assertThat(chains.id()).isEqualTo("p");
        assertThat(chains.variants()).singleElement().satisfies(v -> {
            assertThat(v.priceCents()).isEqualTo(1999);
            assertThat(v.stock()).isEqualTo(3);
            assertThat(v.options()).containsEntry("Size", "15 in");
        });
        assertThat(families.get(1).variants().getFirst().barcode()).isEqualTo("4006381333931");
    }
}
