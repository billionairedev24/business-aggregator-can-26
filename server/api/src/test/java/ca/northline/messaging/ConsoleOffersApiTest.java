package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.RecordingEmailSender;
import ca.northline.support.RecordingSmsTransport;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.util.Locale;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-157: the console's offers — preview, a test to oneself, the consenting customers of a market counted, and a send
 * queued through the worker's deferred queue for exactly them; 403 for roles without the screen or the action, 422s
 * with their messages, 409 with nobody to send to; audit-logged.
 */
class ConsoleOffersApiTest extends IntegrationTest {

    static final String MARKET = "BC"; // served by the test profile's region configuration

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RecordingEmailSender sentEmails;

    @Autowired
    RecordingSmsTransport sentTexts;

    String staff;
    String consenting;
    String notConsenting;
    String elsewhere;

    @BeforeEach
    void people() throws Exception {
        staff = data.user("Mei Staff");
        contact(staff, "mei+" + staff.toLowerCase(Locale.ROOT) + "@northline.test", "+1604555" + digits(staff));
        consenting = customer(MARKET, true);
        notConsenting = customer(MARKET, false);
        elsewhere = customer("NU", true);
    }

    static String digits(String id) {
        return String.format("%04d", Math.floorMod(id.hashCode(), 10_000));
    }

    void contact(String user, String email, String phone) {
        jdbc.sql("update identity.users set email = ?, phone = ? where id = ?")
                .params(email, phone, user)
                .update();
    }

    String customer(String province, boolean consents) throws Exception {
        var id = data.user("Customer");
        contact(id, "c+" + id.toLowerCase(Locale.ROOT) + "@example.ca", "+1778555" + digits(id));
        jdbc.sql("insert into identity.addresses (id, user_id, street, city, province, postal, is_default)"
                        + " values (?, ?, '1 Test St', 'Testville', ?, 'V5K 0A1', true)")
                .params(Ids.next(), id, province)
                .update();
        if (consents) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                    "/api/v1/me/consents/marketing_email")
                            .with(TestJwt.customer(id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"granted": true, "source": "checkout", "wordingVersion": "account.email.2026-10"}"""))
                    .andExpect(status().isOk());
        }
        return id;
    }

    static String draft(String market, String channel, String path) {
        return """
                {"market": "%s", "channel": "%s", "titleEn": "Free delivery this weekend",
                 "bodyEn": "Order before Sunday night and delivery is on us.",
                 "titleFr": "Livraison gratuite ce week-end",
                 "bodyFr": "Commandez avant dimanche soir et la livraison est offerte.", "path": "%s"}""".formatted(market, channel, path);
    }

    ResultActions postAs(String path, String body, StaffRole role) throws Exception {
        return mvc.perform(post(path)
                .with(TestJwt.staff(staff, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    int queuedFor(String user, String campaign) {
        return jdbc.sql("""
                        select count(*) from messaging.deferred_notifications
                         where user_id = ? and event_id = ? and event_type = 'messaging.offer' and channel = 'email'
                           and payload ->> 'campaignId' = ?""")
                .params(user, campaign, campaign)
                .query(Integer.class)
                .single();
    }

    @Test
    void preview_showsBothLanguages_withTheCaslFooter_andCountsTheConsentingCustomersOfTheMarket() throws Exception {
        postAs("/api/v1/console/offers/preview", draft(MARKET, "email", "/shop"), StaffRole.FINANCE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.en.subject").value(Matchers.containsString("Free delivery this weekend")))
                .andExpect(jsonPath("$.en.text").value(Matchers.containsString("Northline Marketplace Inc.")))
                .andExpect(jsonPath("$.en.text").value(Matchers.containsString("1200 – 8th Avenue SW")))
                .andExpect(jsonPath("$.en.html").value(Matchers.containsString("http://localhost:3000/shop")))
                .andExpect(jsonPath("$.fr.subject").value(Matchers.containsString("Livraison gratuite")))
                .andExpect(jsonPath("$.en.sms").value(Matchers.endsWith(" or reply STOP")))
                .andExpect(jsonPath("$.fr.sms").value(Matchers.endsWith(" ou répondez ARRET")))
                .andExpect(jsonPath("$.en.smsSegments").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.audience.market").value(MARKET))
                .andExpect(jsonPath("$.audience.email").value(Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void send_queuesExactlyTheConsentingCustomersOfTheMarket_throttled_andAudited() throws Exception {
        var answer = postAs("/api/v1/console/offers", draft(MARKET, "email", "/shop"), StaffRole.FINANCE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.market").value(MARKET))
                .andExpect(jsonPath("$.channel").value("email"))
                .andExpect(jsonPath("$.recipients").value(Matchers.greaterThanOrEqualTo(1)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String campaign = JsonPath.read(answer, "$.id");
        assertThat(queuedFor(consenting, campaign)).isEqualTo(1);
        assertThat(queuedFor(notConsenting, campaign)).isZero();
        assertThat(queuedFor(elsewhere, campaign)).isZero();
        assertThat(jdbc.sql("select count(*) from developer.audit_log where action = 'offer.sent' and target_id = ?")
                        .params(campaign)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        // the payload carries no personal data: ids and the offer's own words
        assertThat(jdbc.sql(
                                "select payload::text from messaging.deferred_notifications where event_id = ? and user_id = ?")
                        .params(campaign, consenting)
                        .query(String.class)
                        .single())
                .doesNotContain("@example.ca", "+1778");
        mvc.perform(get("/api/v1/console/offers").with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markets").value(Matchers.hasItem(MARKET)))
                .andExpect(jsonPath("$.perMinute").value(30))
                .andExpect(jsonPath("$.campaigns[?(@.id == '" + campaign + "')].pending")
                        .value(Matchers.contains(JsonPath.<Integer>read(answer, "$.recipients"))));
    }

    @Test
    void test_goesToTheStaffMembersOwnAddressOnly() throws Exception {
        postAs("/api/v1/console/offers/test", draft(MARKET, "email", "/shop"), StaffRole.ADMIN)
                .andExpect(status().isNoContent());
        assertThat(sentEmails.to("mei+" + staff.toLowerCase(Locale.ROOT) + "@northline.test"))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.subject()).startsWith("[Test] Free delivery this weekend");
                    assertThat(m.text()).contains("/api/v1/email/unsubscribe?t=");
                });
        postAs("/api/v1/console/offers/test", draft(MARKET, "sms", "/shop"), StaffRole.ADMIN)
                .andExpect(status().isNoContent());
        assertThat(sentTexts.to("+1604555" + digits(staff)))
                .singleElement()
                .satisfies(t -> assertThat(t.body()).startsWith("[Test] Northline: Free delivery this weekend."));
        assertThat(jdbc.sql(
                                "select count(*) from developer.audit_log where action = 'offer.test_sent' and actor_id = ?")
                        .params(staff)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void rolesWithoutTheScreenOrTheAction_get403() throws Exception {
        mvc.perform(get("/api/v1/console/offers").with(TestJwt.staff(staff, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        postAs("/api/v1/console/offers/preview", draft(MARKET, "email", "/shop"), StaffRole.ANALYST)
                .andExpect(status().isForbidden());
        postAs("/api/v1/console/offers", draft(MARKET, "email", "/shop"), StaffRole.PRIVACY)
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/offers").with(TestJwt.customer(staff))).andExpect(status().isForbidden());
    }

    @Test
    void validationMessages_andNobodyToSendTo() throws Exception {
        postAs("/api/v1/console/offers/preview", draft("NU", "email", "/shop"), StaffRole.FINANCE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("market"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a market Northline serves."));
        postAs("/api/v1/console/offers/preview", draft(MARKET, "fax", "https://evil.example"), StaffRole.FINANCE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].message")
                        .value(Matchers.containsInAnyOrder(
                                "Choose email or text.", "Link to a page of the site, starting with /.")));
        postAs("/api/v1/console/offers/preview", draft(MARKET, "email", "//evil.example"), StaffRole.FINANCE)
                .andExpect(status().isUnprocessableContent());
        postAs(
                        "/api/v1/console/offers/preview",
                        draft(MARKET, "email", "/shop").replace("Free delivery this weekend", ""),
                        StaffRole.FINANCE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Write the English title (up to 80 characters)."));
        mvc.perform(post("/api/v1/console/offers/preview")
                        .with(TestJwt.staff(staff, StaffRole.FINANCE))
                        .header("Accept-Language", "fr-CA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("NU", "email", "/shop")))
                .andExpect(jsonPath("$.errors[0].message").value("Choisissez un marché que Northline dessert."));
        // nobody in Québec agreed to texts in this suite
        postAs("/api/v1/console/offers", draft("QC", "sms", "/shop"), StaffRole.FINANCE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_recipients"));
    }
}
