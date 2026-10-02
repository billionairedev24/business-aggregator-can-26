package ca.northline.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.identity.api.OncallRota;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * S-113: the on-call rota for paging tools — JSON and iCalendar, behind its own shared token (never a user's token),
 * who is on call now with their email.
 */
@TestPropertySource(properties = "northline.oncall.export.token=" + OncallExportApiTest.TOKEN)
class OncallExportApiTest extends IntegrationTest {

    static final String TOKEN = "fake-oncall-export-token-not-a-secret";

    @Autowired
    OncallRota rota;

    @Autowired
    JdbcClient jdbc;

    String nowShift;
    String laterShift;
    String email;

    @BeforeEach
    void rota() {
        var person = data.user("Noor Oncall");
        email = "noor." + Ids.next().substring(18).toLowerCase(java.util.Locale.ROOT) + "@example.test";
        jdbc.sql("update identity.users set email = :e where id = :id")
                .param("e", email)
                .param("id", person)
                .update();
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        nowShift = rota.add(person, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(7)), "Pager, Ops", person)
                .id();
        laterShift = rota.add(person, now.plus(Duration.ofDays(2)), now.plus(Duration.ofDays(3)), "Kitchens", person)
                .id();
    }

    @Test
    void listsWhoIsOnCallNowAndTheComingShifts() throws Exception {
        mvc.perform(get("/api/v1/ops/oncall").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.now[?(@.shiftId == '" + nowShift + "')].email")
                        .value(email))
                .andExpect(
                        jsonPath("$.now[?(@.shiftId == '" + laterShift + "')]").isEmpty())
                .andExpect(jsonPath("$.shifts[?(@.shiftId == '" + laterShift + "')].duty")
                        .value("Kitchens"));
    }

    @Test
    void servesTheRotaAsACalendar() throws Exception {
        var body = mvc.perform(get("/api/v1/ops/oncall.ics").param("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .startsWith("BEGIN:VCALENDAR\r\n")
                .contains("UID:" + nowShift + "@oncall.northline\r\n")
                .contains("SUMMARY:" + email + "\r\n")
                .contains("DESCRIPTION:Noor Oncall · Pager\\, Ops\r\n")
                .endsWith("END:VCALENDAR\r\n");
    }

    @Test
    void refusesAMissingOrWrongTokenAndUsersTokens() throws Exception {
        mvc.perform(get("/api/v1/ops/oncall")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ops/oncall").header("Authorization", "Bearer wrong-" + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Send the on-call export token."));
        mvc.perform(get("/api/v1/ops/oncall.ics").param("token", "nope")).andExpect(status().isUnauthorized());
        // a staff member's session is not the export's credential
        mvc.perform(get("/api/v1/ops/oncall").with(TestJwt.customer(Ids.next())))
                .andExpect(status().is(Matchers.oneOf(401, 403)));
    }
}
