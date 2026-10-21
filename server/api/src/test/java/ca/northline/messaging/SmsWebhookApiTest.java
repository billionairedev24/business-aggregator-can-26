package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-157: texts to Northline's number through the SMS provider's webhook (the {@code local} fake under the test profile:
 * unsigned) — STOP withdraws the text consent of every account with the number and is answered once; START gives it
 * back only after a STOP; HELP names the sender; delivery statuses are recorded, a final one never replaced.
 */
class SmsWebhookApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    String amara;
    String phone;

    @BeforeEach
    void person() {
        amara = data.user("Amara Osei");
        phone = "+1587" + (1_000_000 + Math.floorMod(amara.hashCode(), 8_999_999));
        jdbc.sql("update identity.users set email = ?, phone = ?, phone_verified_at = now() where id = ?")
                .params("amara+" + amara.toLowerCase(java.util.Locale.ROOT) + "@example.ca", phone, amara)
                .update();
    }

    ResultActions text(String sid, String from, String body) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/sms/inbound")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("MessageSid", sid)
                .param("From", from)
                .param("To", "+15875550100")
                .param("Body", body));
    }

    void grantTexts() throws Exception {
        mvc.perform(put("/api/v1/me/consents/marketing_sms")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"granted": true, "source": "app_settings", "wordingVersion": "account.sms.2026-10"}"""))
                .andExpect(status().isOk());
    }

    List<Map<String, Object>> smsRecords() {
        return jdbc.sql("""
                        select action, source, wording_version from messaging.consent_records
                         where user_id = ? and category = 'marketing_sms' order by at, id""").params(amara).query().listOfRows();
    }

    @Test
    void stop_withdrawsTheTextConsentAtOnce_andIsAnsweredOnce() throws Exception {
        grantTexts();
        var sid = "SM" + Ids.next();
        text(sid, phone, " Stop ")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(Matchers.containsString("you’re unsubscribed — no more offers by text")))
                .andExpect(
                        content()
                                .string(
                                        Matchers.startsWith(
                                                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Message>Northline Marketplace Inc.")));
        assertThat(smsRecords())
                .extracting(r -> r.get("action") + "/" + r.get("source"))
                .containsExactly("granted/app_settings", "withdrawn/sms_keyword");
        // the provider retries the same text: nothing changes, no second reply
        text(sid, phone, "STOP")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.endsWith("<Response/>")));
        assertThat(smsRecords()).hasSize(2);
        assertThat(jdbc.sql("select keyword, accounts, from_hash from messaging.sms_inbound where message_id = ?")
                        .params(sid)
                        .query()
                        .singleRow())
                .containsEntry("keyword", "stop")
                .containsEntry("accounts", 1)
                .doesNotContainValue(phone);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/consents")
                        .with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.categories[1].granted").value(false))
                .andExpect(jsonPath("$.categories[1].source").value("sms_keyword"));
    }

    @Test
    void arret_isFrench_forAFrenchAccount() throws Exception {
        jdbc.sql("update identity.users set locale = 'fr-CA' where id = ?")
                .params(amara)
                .update();
        grantTexts();
        text("SM" + Ids.next(), phone, "Arrêt")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("vous êtes désabonné")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("unsubscribed"))));
        assertThat(smsRecords()).last().satisfies(r -> assertThat(r).containsEntry("action", "withdrawn"));
    }

    @Test
    void start_givesOffersBack_onlyAfterAStop() throws Exception {
        // never consented: START turns texts on, offers stay off
        text("SM" + Ids.next(), phone, "START")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Offers by text stay off")));
        assertThat(smsRecords()).isEmpty();
        // consented, STOP, START: the consent comes back with the keyword wording
        grantTexts();
        text("SM" + Ids.next(), phone, "STOP").andExpect(status().isOk());
        text("SM" + Ids.next(), phone, "start")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("texts and offers are back on")));
        assertThat(smsRecords())
                .extracting(r -> r.get("action") + "/" + r.get("source") + "/" + r.get("wording_version"))
                .containsExactly(
                        "granted/app_settings/account.sms.2026-10",
                        "withdrawn/sms_keyword/null",
                        "granted/sms_keyword/keyword.sms.2026-10");
    }

    @Test
    void help_namesTheSender_anUnknownNumberGetsBothLanguages_otherTextsGetNoReply() throws Exception {
        text("SM" + Ids.next(), "+15875559999", "HELP")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("support@northline.ca")))
                .andExpect(content().string(Matchers.containsString(" / Northline Marketplace Inc. : ")));
        text("SM" + Ids.next(), phone, "Is my order coming?")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.endsWith("<Response/>")));
        text("bad sid!", phone, "STOP").andExpect(status().isBadRequest());
    }

    @Test
    void deliveryStatuses_areRecorded_aFinalOneIsNeverReplaced() throws Exception {
        var sid = "SM" + Ids.next();
        for (var s : List.of("queued", "sent", "undelivered", "sent")) {
            mvc.perform(post("/api/v1/webhooks/sms/status")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("MessageSid", sid)
                            .param("MessageStatus", s)
                            .param("ErrorCode", "undelivered".equals(s) ? "30003" : ""))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbc.sql("select status, error_code from messaging.sms_delivery_status where message_id = ?")
                        .params(sid)
                        .query()
                        .singleRow())
                .containsEntry("status", "undelivered")
                .containsEntry("error_code", "30003");
        mvc.perform(post("/api/v1/webhooks/sms/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("MessageSid", sid)
                        .param("MessageStatus", "exploded"))
                .andExpect(status().isBadRequest());
    }
}
