package ca.northline.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.hire.HireFixtures;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-74: {@code GET …/calendar-cells} — open slots are the starts of the free runs a customer could book (a job splits
 * the day), at most 3 a day; quote holds are the times open sent quotes propose, with the customer's short name; any
 * member sees them, others get 403; the range is validated. The fixture provider works 00:00–23:30 every day in the
 * launch market's zone.
 */
class CalendarCellsApiTest extends IntegrationTest {

    static final String CELLS = "/api/v1/merchants/{m}/calendar-cells";
    static final ZoneId ZONE = ZoneId.of("America/Edmonton"); // the fixture's market (test data, not app code)
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    HireFixtures.Provider provider;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        new CategorySeeder(dataSource).seed();
        provider = new HireFixtures(jdbc).provider("Cells Wrench", "master", List.of("Beltline"));
        tomorrow = LocalDate.now(ZONE).plusDays(1);
    }

    Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    @Test
    void openSlotsStartEachFreeRun_andQuoteHoldsShowTheProposedTimes() throws Exception {
        jdbc.sql("""
                        insert into booking.bookings (id, merchant_id, member_user_id, customer_id, type, state, starts_at,
                                                      ends_at, title, source)
                        values (?, ?, ?, ?, 'visit', 'confirmed', ?, ?, 'Brake inspection', 'studio')
                        """)
                .params(
                        Ids.next(),
                        provider.merchantId(),
                        provider.owner(),
                        data.user("Amara Osei"),
                        at(tomorrow, 10).atOffset(ZoneOffset.UTC),
                        at(tomorrow, 11).atOffset(ZoneOffset.UTC))
                .update();
        var customer = data.user("Minh Tran");
        var request = Ids.next();
        jdbc.sql("""
                        insert into booking.quote_requests (id, customer_id, details, merchant_ids, expires_at, respond_by)
                        values (?, ?, '{"title":"Alternator"}'::jsonb, array[?], now() + interval '3 days', now() + interval '2 hours')
                        """).params(request, customer, provider.merchantId()).update();
        var open = Ids.next();
        var expired = Ids.next();
        for (var q : List.of(open, expired)) {
            jdbc.sql("""
                            insert into booking.quotes (id, request_id, merchant_id, ref, version, scope, warranty, deposit_kind,
                                   subtotal_cents, tax_cents, total_cents, valid_hours, valid_until, state, sent_at, proposed_at,
                                   duration_min)
                            values (?, ?, ?, 'QT-74', ?, 'scope', 'none', 'none', 0, 0, 0, 72, ?, 'sent', now(), ?, 120)
                            """)
                    .params(
                            q,
                            request,
                            provider.merchantId(),
                            q.equals(open) ? 1 : 2,
                            (q.equals(open)
                                            ? Instant.now().plusSeconds(86_400)
                                            : Instant.now().minusSeconds(60))
                                    .atOffset(ZoneOffset.UTC),
                            at(tomorrow.plusDays(1), 10).atOffset(ZoneOffset.UTC))
                    .update();
        }

        var body = JSON.readTree(mvc.perform(get(CELLS, provider.merchantId())
                        .param("from", tomorrow.toString())
                        .param("days", "2")
                        .with(TestJwt.member(provider.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteHolds.length()").value(1))
                .andExpect(jsonPath("$.quoteHolds[0].quoteId").value(open))
                .andExpect(jsonPath("$.quoteHolds[0].customerName").value("M. Tran"))
                .andExpect(jsonPath("$.quoteHolds[0].ref").value("QT-74"))
                .andExpect(jsonPath("$.quoteHolds[0].durationMin").value(120))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var tomorrowSlots = new ArrayList<Instant>();
        for (var slot : body.get("openSlots")) {
            var startsAt = Instant.parse(slot.get("startsAt").asString());
            if (startsAt.atZone(ZONE).toLocalDate().equals(tomorrow)) {
                tomorrowSlots.add(startsAt);
            }
        }
        // the job splits tomorrow into two free runs: one before it, one after
        assertThat(tomorrowSlots).hasSize(2);
        assertThat(tomorrowSlots.get(0)).isBefore(at(tomorrow, 10));
        assertThat(tomorrowSlots.get(1)).isAfterOrEqualTo(at(tomorrow, 11));
    }

    @Test
    void membersOnly_andTheRangeIsValidated() throws Exception {
        mvc.perform(get(CELLS, provider.merchantId())
                        .param("from", tomorrow.toString())
                        .with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(CELLS, provider.merchantId())
                        .param("from", tomorrow.toString())
                        .with(TestJwt.memberWithoutMfa(provider.owner())))
                .andExpect(status().isForbidden());
        mvc.perform(get(CELLS, provider.merchantId())
                        .param("from", tomorrow.toString())
                        .param("days", "40")
                        .with(TestJwt.member(provider.owner())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick 1 to 31 days."));
        mvc.perform(get(CELLS, provider.merchantId())
                        .param("from", tomorrow.toString())
                        .param("durationMin", "5")
                        .with(TestJwt.member(provider.owner())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a duration between 15 minutes and 12 hours."));
    }
}
