package ca.northline.account;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/**
 * S-58: Orders &amp; bookings, Your week, the wallet, the account menu's values and favourites — the caller's own rows
 * only, composed from orders, booking, payments, trust and identity.
 */
class AccountActivityApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    AccountFixtures f;
    String amara;
    String bakery;
    String greens;
    String wrench;
    String ravi;
    String sable;

    @BeforeEach
    void people() {
        f = new AccountFixtures(jdbc);
        amara = data.user("Amara Osei");
        bakery = shopFixtures.shop("Calgary", "Glenmore Bakery", "master");
        greens = shopFixtures.shop("Calgary", "Sunnyside Greens", "trusted");
        wrench = data.merchant("provider", "Prairie Wrench");
        ravi = data.user("Ravi Sandhu");
        sable = data.merchant("provider", "Sable & Soda");
        f.storefront(wrench, "prairie-wrench-" + wrench.substring(20).toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void ordersBookingsAndQuotes_activeFirst_onlyTheCallers() throws Exception {
        var now = Instant.now();
        var tonight =
                f.goodsOrder(amara, "packing", now.minus(Duration.ofHours(2)), null, List.of(bakery, greens), 1000);
        var past = f.goodsOrder(
                amara,
                "confirmed",
                now.minus(Duration.ofDays(9)),
                now.minus(Duration.ofDays(8)),
                List.of(greens),
                2000);
        var brakes = f.booking(
                amara, wrench, ravi, "Brake inspection", "confirmed", now.plus(Duration.ofDays(2)), 8900, null, 0);
        var tires = f.booking(
                amara, wrench, ravi, "Winter tire swap", "signed_off", now.minus(Duration.ofDays(20)), 12000, null, 0);
        var request = f.quoteRequest(amara, "Mocktail bar · 40 guests", List.of(sable));
        var quote = f.quote(request, sable, 64000, "sent");
        var stranger = data.user("Someone Else");
        f.goodsOrder(stranger, "packing", now, null, List.of(bakery), 500);

        mvc.perform(get("/api/v1/me/activity").with(TestJwt.customer(amara)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                // active, soonest first: tonight's order, then the quote (preferred date unknown → asked now), then Thu
                .andExpect(jsonPath("$.items[?(@.id == '%s')].status".formatted(tonight.id()))
                        .value("packing"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].action".formatted(tonight.id()))
                        .value("track"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].href".formatted(tonight.id()))
                        .value("/orders/" + tonight.id()))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].with[*]".formatted(tonight.id()))
                        .value(contains("Glenmore Bakery", "Sunnyside Greens")))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].delivery".formatted(tonight.id()))
                        .value("pooled"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].amountCents".formatted(tonight.id()))
                        .value(2000 + 499 + 100))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].status".formatted(brakes))
                        .value("escrow"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].with[0]".formatted(brakes))
                        .value("Prairie Wrench · Ravi"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].href".formatted(brakes))
                        .value(contains(startsWith("/providers/prairie-wrench-"))))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].status".formatted(request))
                        .value("quote_ready"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].href".formatted(request))
                        .value("/quotes/" + quote))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].amountCents".formatted(request))
                        .value(64000))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].tone".formatted(request))
                        .value("accent-2"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].active".formatted(past.id()))
                        .value(false))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].status".formatted(past.id()))
                        .value("done"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].action".formatted(tires))
                        .value("rebook"))
                .andExpect(jsonPath("$.items[3].active").value(false))
                .andExpect(jsonPath("$.items[0].active").value(true));
    }

    @Test
    void aRefundCaseShowsOnTheOrderAndCountsAsOpen() throws Exception {
        var now = Instant.now();
        var order = f.goodsOrder(
                amara, "delivered", now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)), List.of(greens), 446);
        var escrow = f.escrow(
                amara,
                greens,
                "goods",
                "order_line",
                order.lineIds().getFirst(),
                446,
                "disputed",
                now.minus(Duration.ofDays(2)),
                now.plus(Duration.ofDays(5)));
        var refund = f.refundCase(greens, escrow, 446, "seller_review");

        mvc.perform(get("/api/v1/me/activity").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.items[0].status").value("case"))
                .andExpect(jsonPath("$.items[0].caseRef.id").value(refund))
                .andExpect(jsonPath("$.items[0].caseRef.number").value(startsWith("RF-")))
                .andExpect(jsonPath("$.items[0].caseRef.open").value(true))
                .andExpect(jsonPath("$.items[0].action").value("view_case"))
                .andExpect(jsonPath("$.items[0].href").value("/account?tab=help&case=" + refund));
        mvc.perform(get("/api/v1/me/account-summary").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.openCases").value(1))
                .andExpect(jsonPath("$.activeOrders").value(0));
    }

    @Nested
    class YourWeek {
        @Test
        void nextSevenDaysInTheCallersLanguage() throws Exception {
            var now = Instant.now();
            f.booking(
                    amara, wrench, ravi, "Brake inspection", "confirmed", now.plus(Duration.ofDays(2)), 8900, null, 0);
            f.booking(amara, wrench, ravi, "Furnace check", "confirmed", now.plus(Duration.ofDays(12)), 8900, null, 0);
            f.goodsOrder(
                    amara,
                    "confirmed",
                    now.minus(Duration.ofDays(9)),
                    now.minus(Duration.ofDays(8)),
                    List.of(greens),
                    2000);

            mvc.perform(get("/api/v1/me/upcoming").with(TestJwt.customer(amara)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].title").value("Brake inspection · Prairie Wrench · Ravi"))
                    .andExpect(jsonPath("$.items[0].subtitle")
                            .value(org.hamcrest.Matchers.endsWith(" · $93.45 in escrow")))
                    .andExpect(jsonPath("$.items[0].state").value("Booked"))
                    .andExpect(jsonPath("$.items[0].tone").value("neutral"))
                    .andExpect(jsonPath("$.items[0].href").value(startsWith("/providers/prairie-wrench-")));
            mvc.perform(get("/api/v1/me/upcoming")
                            .header("Accept-Language", "fr-CA")
                            .with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items[0].subtitle")
                            .value(org.hamcrest.Matchers.matchesRegex(".* · 93,45[\\s\\u00a0\\u202f]\\$ en séquestre")))
                    .andExpect(jsonPath("$.items[0].state").value("Réservé"));
        }
    }

    @Test
    void walletPointsAndPlus() throws Exception {
        var now = Instant.now();
        f.points(amara, 300, now.minus(Duration.ofDays(3)));
        f.points(amara, 520, now.minus(Duration.ofDays(10)));
        f.points(amara, -100, now.minus(Duration.ofDays(1)));
        f.plus(amara, "monthly");

        mvc.perform(get("/api/v1/me/wallet").with(TestJwt.customer(amara)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.balance").value(720))
                .andExpect(jsonPath("$.points.valueCents").value(720))
                .andExpect(jsonPath("$.points.weekly", hasSize(8)))
                .andExpect(jsonPath("$.points.weekly[7]").value(300))
                .andExpect(jsonPath("$.points.weekly[6]").value(520))
                .andExpect(jsonPath("$.plus.plan").value("monthly"))
                .andExpect(jsonPath("$.plus.members").value(1));
        mvc.perform(get("/api/v1/me/account-summary").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.points.balance").value(720))
                .andExpect(jsonPath("$.plus").value(true));
    }

    @Test
    void anEmptyWallet() throws Exception {
        mvc.perform(get("/api/v1/me/wallet").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.points.balance").value(0))
                .andExpect(jsonPath("$.points.weekly", hasSize(8)))
                .andExpect(jsonPath("$.plus").doesNotExist());
    }

    @Nested
    class Favourites {
        @Test
        void addListRemove() throws Exception {
            f.booking(
                    amara,
                    wrench,
                    ravi,
                    "Winter tire swap",
                    "signed_off",
                    Instant.now().minus(Duration.ofDays(20)),
                    12000,
                    null,
                    0);
            mvc.perform(put("/api/v1/me/favourites/{id}", wrench).with(TestJwt.customer(amara)))
                    .andExpect(status().isNoContent());
            mvc.perform(put("/api/v1/me/favourites/{id}", wrench).with(TestJwt.customer(amara)))
                    .andExpect(status().isNoContent());
            mvc.perform(put("/api/v1/me/favourites/{id}", bakery).with(TestJwt.customer(amara)))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/me/favourites").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].name".formatted(wrench))
                            .value("Prairie Wrench"))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].visits".formatted(wrench))
                            .value(1))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].type".formatted(bakery))
                            .value("seller"))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].tier".formatted(bakery))
                            .value("master"));
            mvc.perform(get("/api/v1/me/account-summary").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.favourites").value(2));

            mvc.perform(delete("/api/v1/me/favourites/{id}", wrench).with(TestJwt.customer(amara)))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/me/favourites").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.items", hasSize(1)));
        }

        @Test
        void aBusinessThatIsNotActive_404() throws Exception {
            var closed = shopFixtures.merchant("Calgary", "Closed Shop", "registered", "seller", "suspended");
            mvc.perform(put("/api/v1/me/favourites/{id}", closed).with(TestJwt.customer(amara)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void signedOut_401() throws Exception {
        for (var path : List.of(
                "/api/v1/me/activity",
                "/api/v1/me/upcoming",
                "/api/v1/me/wallet",
                "/api/v1/me/account-summary",
                "/api/v1/me/favourites")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(put("/api/v1/me/favourites/{id}", bakery).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnauthorized());
    }
}
