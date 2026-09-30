package ca.northline.hire;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import ca.northline.tools.CategorySeeder;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** S-54: the public provider page's facts (the page itself is the storefront API) and its reviews. */
class ProviderPageApiTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    HireFixtures fx;

    @BeforeEach
    void seed() {
        new CategorySeeder(dataSource).seed();
        fx = new HireFixtures(jdbc);
    }

    @Test
    void composesTrustServicesAreaSlotAndFirstReviews() throws Exception {
        var p = fx.provider("Page Wrench", "master", List.of("Beltline", "Airdrie"));
        fx.service(p.merchantId(), "service.automotive.mobile-mechanic", "Diagnostic scan", "fixed", 12000L, 60);
        fx.service(p.merchantId(), "service.automotive.mobile-mechanic", "Alternator", "quote", null, 90);
        fx.service(p.merchantId(), "service.automotive.brakes-and-suspension", "Brake inspection", "fixed", 8900L, 60);
        fx.quality(p.merchantId(), 98, 0.3, 71);
        fx.verified(p.merchantId(), "licence:AMVIC");
        fx.verified(p.merchantId(), "insurance");
        for (int i = 1; i <= 4; i++) {
            fx.review(p.merchantId(), 5, "Great job " + i, "Customer " + i, 10 - i);
        }

        mvc.perform(get("/api/v1/public/providers/{slug}", p.slug()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.merchantId").value(p.merchantId()))
                .andExpect(jsonPath("$.name").value("Page Wrench"))
                .andExpect(jsonPath("$.tier").value("master"))
                .andExpect(jsonPath("$.kind").value("visit"))
                .andExpect(jsonPath("$.vehicle").value(true))
                .andExpect(jsonPath("$.quoteable").value(true))
                .andExpect(jsonPath("$.taxBps").value(500))
                .andExpect(jsonPath("$.category.slug").value("mobile-mechanic"))
                .andExpect(jsonPath("$.rating").value(5.0))
                .andExpect(jsonPath("$.reviewCount").value(4))
                .andExpect(jsonPath("$.onTimePct").value(98.0))
                .andExpect(jsonPath("$.disputePct").value(0.3))
                .andExpect(jsonPath("$.rebookPct").value(71.0))
                .andExpect(jsonPath("$.verifiedFacts", containsInAnyOrder("licence:AMVIC", "insurance")))
                .andExpect(jsonPath("$.zones", contains("Airdrie", "Beltline")))
                .andExpect(jsonPath("$.services.length()").value(3))
                .andExpect(jsonPath("$.services[?(@.name == 'Alternator')].pricingMode")
                        .value("quote"))
                .andExpect(jsonPath("$.services[?(@.name == 'Brake inspection')].categorySlug")
                        .value("brakes-and-suspension"))
                .andExpect(jsonPath("$.nextAvailable").isNotEmpty())
                .andExpect(jsonPath("$.reviews.items.length()").value(3))
                .andExpect(jsonPath("$.reviews.items[0].text").value("Great job 4"))
                .andExpect(jsonPath("$.reviews.items[0].author").value("Customer 4"))
                .andExpect(jsonPath("$.reviews.items[0].refType").value("booking"))
                .andExpect(jsonPath("$.reviews.nextOffset").value(3));

        mvc.perform(get("/api/v1/public/providers/{slug}/reviews", p.slug()).param("offset", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].text").value("Great job 1"))
                .andExpect(jsonPath("$.nextOffset").doesNotExist());
        // the page itself: the storefront API, public
        mvc.perform(get("/api/v1/storefronts/{slug}", p.slug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagline").value("Mobile mechanic · Calgary"));
    }

    @Test
    void aProviderWithoutServicesStillHasAPage() throws Exception {
        var p = fx.provider("Empty Wrench", "registered", List.of());
        mvc.perform(get("/api/v1/public/providers/{slug}", p.slug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(0))
                .andExpect(jsonPath("$.nextAvailable").doesNotExist())
                .andExpect(jsonPath("$.reviews.items.length()").value(0))
                .andExpect(jsonPath("$.onTimePct").doesNotExist());
    }

    @Test
    void unpublishedPausedUnknownOrNonProviderPagesAre404() throws Exception {
        var hidden = fx.provider("Hidden Page", "master", List.of());
        fx.unpublish(hidden.merchantId());
        var paused = fx.provider("Paused Page", "master", List.of());
        jdbc.sql("update merchants.merchants set status = 'paused' where id = ?")
                .params(paused.merchantId())
                .update();
        var seller = fx.provider("Seller Page", "master", List.of());
        jdbc.sql("update merchants.merchants set type = 'seller' where id = ?")
                .params(seller.merchantId())
                .update();

        for (var slug : List.of(hidden.slug(), paused.slug(), seller.slug(), "no-such-page")) {
            mvc.perform(get("/api/v1/public/providers/{slug}", slug)).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/public/providers/{slug}/reviews", slug)).andExpect(status().isNotFound());
        }
    }
}
