package ca.northline.hire;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-156: checkout reads the standing consequences — a provider whose instant book Northline paused takes quote
 * requests only (its page says so), and a customer with confirmed no-shows can't book instantly.
 */
class StandingCheckoutApiTest extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    HireFixtures.Provider provider;
    String service;
    String customer;

    @BeforeEach
    void setUp() {
        new CategorySeeder(dataSource).seed();
        var fx = new HireFixtures(jdbc);
        provider = fx.provider("Standing Wrench", "master", List.of("Beltline"));
        service = fx.service(
                provider.merchantId(), BookingCheckoutApiTest.MECHANIC, "Brake inspection", "fixed", 8900L, 60);
        customer = data.user("Amara Osei");
    }

    String holdBody() {
        return JSON.writeValueAsString(Map.of(
                "slug",
                provider.slug(),
                "serviceId",
                service,
                "startsAt",
                BookingCheckoutApiTest.tomorrowAt(10).toString()));
    }

    @Test
    void aPausedProvider_takesQuoteRequestsOnly() throws Exception {
        jdbc.sql("""
                        insert into catalogue.instant_book_settings (merchant_id, enabled, paused_at, paused_cause,
                          paused_reason, updated_at) values (?, true, now(), 'provider_no_shows', '3 no-shows', now())""").params(provider.merchantId()).update();
        mvc.perform(post("/api/v1/me/bookings/holds")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("This service needs the provider's approval — ask for a quote instead."));
        mvc.perform(get("/api/v1/public/providers/{slug}", provider.slug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].instantBook").value(false));
    }

    @Test
    void aCustomerWithConfirmedNoShows_asksForAQuote() throws Exception {
        jdbc.sql("update identity.users set no_show_count = 2, no_show_restricted_at = now() where id = ?")
                .params(customer)
                .update();
        mvc.perform(post("/api/v1/me/bookings/holds")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("serviceId"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("After missed appointments, providers confirm your bookings first — ask for a quote"
                                + " instead."));
        var someoneElse = data.user("Bea Punctual");
        mvc.perform(post("/api/v1/me/bookings/holds")
                        .with(TestJwt.customer(someoneElse))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody()))
                .andExpect(status().isCreated());
    }
}
