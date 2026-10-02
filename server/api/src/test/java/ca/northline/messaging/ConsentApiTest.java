package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.email.CommercialConsent;
import ca.northline.email.MessageClasses;
import ca.northline.email.UnsubscribeTokens;
import ca.northline.messaging.api.ConsentRetention;
import ca.northline.messaging.domain.ConsentEvidence;
import ca.northline.messaging.domain.ConsentWordings;
import ca.northline.messaging.domain.CustomerNotificationPrefs;
import ca.northline.messaging.domain.NotificationMatrix;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-108 CASL: express consent stored with its time, source, wording, language and minimised evidence; the Account ›
 * Notifications {@code offers} row and marketing-email choice are those consents; one-click unsubscribe withdraws at
 * once without signing in; staff find the proof by contact and record withdrawals; the proof outlives a withdrawal for
 * the CASL period; every notification row is classified.
 */
class ConsentApiTest extends IntegrationTest {

    static final UnsubscribeTokens TOKENS = new UnsubscribeTokens(UnsubscribeTokens.DEV_KEY);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConsentRetention retention;

    @Autowired
    CommercialConsent commercialConsent;

    String amara;
    String email;

    @BeforeEach
    void person() {
        amara = data.user("Amara Osei");
        email = "amara+" + amara.toLowerCase(Locale.ROOT) + "@example.ca";
        jdbc.sql("update identity.users set email = ?, phone = ? where id = ?")
                .params(email, "+1403" + (1_000_000 + Math.floorMod(amara.hashCode(), 8_999_999)), amara)
                .update();
    }

    @Nested
    class Capture {

        @Test
        void nothingIsGrantedByDefault_theWordingNamesTheSender_inTheReadersLanguage() throws Exception {
            mvc.perform(get("/api/v1/me/consents").with(TestJwt.customer(amara)).header("Accept-Language", "fr-CA"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.categories[*].category")
                            .value(Matchers.contains("marketing_email", "marketing_sms", "marketing_push")))
                    .andExpect(jsonPath("$.categories[*].granted").value(Matchers.everyItem(Matchers.is(false))))
                    .andExpect(jsonPath("$.categories[0].wordingVersion").value("account.email.2026-10"))
                    .andExpect(jsonPath("$.categories[0].wording")
                            .value(Matchers.startsWith("Oui, Northline Marketplace Inc. peut m’envoyer par courriel")))
                    .andExpect(jsonPath("$.history").isEmpty())
                    .andExpect(jsonPath("$.requester").value(Matchers.containsString("Northline Marketplace Inc.")))
                    .andExpect(jsonPath("$.requester").value(Matchers.containsString("support@northline.ca")));
            mvc.perform(get("/api/v1/me/consents").param("surface", "studio").with(TestJwt.member(amara)))
                    .andExpect(jsonPath("$.categories[*].category").value(Matchers.contains("marketing_email")))
                    .andExpect(jsonPath("$.categories[0].wordingVersion").value("studio.email.2026-10"));
        }

        @Test
        void aGrant_isStoredWithTimeSourceWordingLanguage_andMinimisedEvidence() throws Exception {
            mvc.perform(put("/api/v1/me/consents/marketing_email")
                            .with(TestJwt.customer(amara))
                            .with(r -> {
                                r.setRemoteAddr("203.0.113.42");
                                return r;
                            })
                            .header("User-Agent", "Mozilla/5.0 (test)")
                            .header("Accept-Language", "fr-CA")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"granted": true, "source": "checkout", "wordingVersion": "account.email.2026-10"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.categories[0].granted").value(true))
                    .andExpect(jsonPath("$.categories[0].source").value("checkout"))
                    .andExpect(jsonPath("$.history[0].action").value("granted"))
                    .andExpect(jsonPath("$.history[0].wordingVersion").value("account.email.2026-10"))
                    .andExpect(jsonPath("$.history[0].language").value("fr"));

            var row = jdbc.sql("""
                            select source, wording_version, language, address_hash, ip_prefix, user_agent_hash, at
                              from messaging.consent_records where user_id = :u""").param("u", amara).query().singleRow();
            assertThat(row)
                    .containsEntry("source", "checkout")
                    .containsEntry("wording_version", "account.email.2026-10")
                    .containsEntry("language", "fr")
                    .containsEntry("address_hash", ConsentEvidence.addressHash(email))
                    .containsEntry("ip_prefix", "203.0.113.0/24");
            assertThat((String) row.get("user_agent_hash")).hasSize(64).isNotEqualTo("Mozilla/5.0 (test)");
            assertThat(((Timestamp) row.get("at")).toInstant())
                    .isBetween(
                            Instant.now().minus(Duration.ofMinutes(1)),
                            Instant.now().plus(Duration.ofMinutes(1)));
            assertThat(commercialConsent.allows(amara, MessageClasses.ConsentCategory.MARKETING_EMAIL))
                    .isTrue();

            // the same state again records nothing (a save without a change)
            mvc.perform(put("/api/v1/me/consents/marketing_email")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"granted\": true, \"source\": \"web_settings\"}"))
                    .andExpect(jsonPath("$.history.length()").value(1));
        }

        @Test
        void validation_staleWording_unknownCategory_andAuthentication() throws Exception {
            mvc.perform(put("/api/v1/me/consents/marketing_sms")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"granted\": true, \"source\": \"unsubscribe_link\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("source"))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose from the list."));
            mvc.perform(put("/api/v1/me/consents/marketing_sms")
                            .with(TestJwt.customer(amara))
                            .header("Accept-Language", "fr-CA")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"source\": \"web_settings\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("granted"))
                    .andExpect(jsonPath("$.errors[0].message").value("Choisissez dans la liste."));
            mvc.perform(put("/api/v1/me/consents/marketing_sms")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"granted": true, "source": "app_settings", "wordingVersion": "account.sms.2020-01"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("consent_wording_changed"));
            mvc.perform(put("/api/v1/me/consents/merchant_newsletter")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"granted\": true, \"source\": \"web_settings\"}"))
                    .andExpect(status().isNotFound());
            // Studio offers email only
            mvc.perform(put("/api/v1/me/consents/marketing_sms")
                            .with(TestJwt.member(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"granted\": true, \"source\": \"studio\"}"))
                    .andExpect(status().isUnprocessableContent());
            mvc.perform(get("/api/v1/me/consents")).andExpect(status().isUnauthorized());
            assertThat(jdbc.sql("select count(*) from messaging.consent_records where user_id = :u")
                            .param("u", amara)
                            .query(Integer.class)
                            .single())
                    .isZero();
        }
    }

    @Nested
    class Settings {

        @Test
        void theOffersRowAndMarketingEmail_areTheConsents_neverOnByDefault() throws Exception {
            mvc.perform(get("/api/v1/me/notifications").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.matrix.offers.push").value(false))
                    .andExpect(jsonPath("$.matrix.offers.sms").value(false))
                    .andExpect(jsonPath("$.matrix.offers.email").value(false))
                    .andExpect(jsonPath("$.marketing").value("none"));

            mvc.perform(put("/api/v1/me/notifications")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"marketing": "rewards", "matrix": {"offers": {"push": true}},
                                     "consentSource": "app_settings",
                                     "consentWordings": {"email": "account.email.2026-10", "push": "account.push.2026-10"}}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.marketing").value("rewards"))
                    .andExpect(jsonPath("$.matrix.offers.email").value(true))
                    .andExpect(jsonPath("$.matrix.offers.push").value(true))
                    .andExpect(jsonPath("$.matrix.offers.sms").value(false));
            mvc.perform(get("/api/v1/me/consents").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.history.length()").value(2))
                    .andExpect(jsonPath("$.history[*].source").value(Matchers.everyItem(Matchers.is("app_settings"))));

            // a save of other settings records nothing; "none" withdraws, and the frequency is kept for next time
            mvc.perform(put("/api/v1/me/notifications")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quietOn\": false}"))
                    .andExpect(jsonPath("$.marketing").value("rewards"));
            mvc.perform(put("/api/v1/me/notifications")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"marketing\": \"none\"}"))
                    .andExpect(jsonPath("$.marketing").value("none"))
                    .andExpect(jsonPath("$.matrix.offers.email").value(false))
                    .andExpect(jsonPath("$.matrix.offers.push").value(true));
            mvc.perform(get("/api/v1/me/consents").with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.history.length()").value(3))
                    .andExpect(jsonPath("$.history[0].action").value("withdrawn"))
                    .andExpect(jsonPath("$.history[0].category").value("marketing_email"))
                    .andExpect(jsonPath("$.history[0].source").value("web_settings"));
            mvc.perform(put("/api/v1/me/notifications")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"consentSource\": \"console\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("consentSource"));
        }
    }

    @Nested
    class Unsubscribe {

        @Test
        void oneClick_withdrawsAtOnce_withoutSigningIn() throws Exception {
            grant(amara, "marketing_email");
            var token = TOKENS.issue(amara, "consent.marketing_email", Locale.CANADA_FRENCH);

            // GET only asks (mail scanners prefetch links), in the token's language, naming the sender
            mvc.perform(get("/api/v1/email/unsubscribe").param("t", token))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString("Offres et nouvelles de Northline")))
                    .andExpect(content().string(Matchers.containsString("Northline Marketplace Inc.")));
            assertThat(granted(amara, "marketing_email")).isTrue();

            // RFC 8058: the mailbox provider's POST
            mvc.perform(post("/api/v1/email/unsubscribe")
                            .param("t", token)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("List-Unsubscribe=One-Click"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString("Votre désabonnement est fait")))
                    .andExpect(content().string(Matchers.containsString("Compte › Notifications")));
            assertThat(granted(amara, "marketing_email")).isFalse();
            assertThat(latestSource(amara, "marketing_email")).isEqualTo("list_unsubscribe");

            // again: idempotent, nothing recorded
            mvc.perform(post("/api/v1/email/unsubscribe").param("t", token)).andExpect(status().isOk());
            assertThat(records(amara)).isEqualTo(2);
        }

        @Test
        void theSmsOptOutLink_andTheOffersRowsLink_withdraw_fromThePage() throws Exception {
            grant(amara, "marketing_sms");
            grant(amara, "marketing_email");
            var sms = TOKENS.issue(amara, "consent.marketing_sms", Locale.CANADA);
            mvc.perform(get("/api/v1/email/unsubscribe").param("t", sms))
                    .andExpect(content().string(Matchers.containsString("Stop text messages about")));
            mvc.perform(post("/api/v1/email/unsubscribe").param("t", sms))
                    .andExpect(content().string(Matchers.containsString("We won’t text you")));
            assertThat(latestSource(amara, "marketing_sms")).isEqualTo("unsubscribe_link");

            mvc.perform(post("/api/v1/email/unsubscribe")
                            .param("t", TOKENS.issue(amara, "customer.offers", Locale.CANADA)))
                    .andExpect(status().isOk());
            assertThat(granted(amara, "marketing_email")).isFalse();
            mvc.perform(post("/api/v1/email/unsubscribe")
                            .param("t", TOKENS.issue(amara, "consent.unknown", Locale.CANADA)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Console {

        @Test
        void staffFindTheProofByContact_andRecordAWithdrawal() throws Exception {
            // through the api: the grant hashes the account's email, which is what staff search by
            mvc.perform(put("/api/v1/me/consents/marketing_email")
                            .with(TestJwt.customer(amara))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"granted\": true, \"source\": \"web_signup\"}"))
                    .andExpect(status().isOk());
            var officer = data.user("Priya Officer");

            mvc.perform(get("/api/v1/console/consents")
                            .param("contact", email.toUpperCase(Locale.ROOT))
                            .with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].userId").value(amara))
                    .andExpect(jsonPath("$.items[0].category").value("marketing_email"))
                    .andExpect(jsonPath("$.items[0].addressKnown").value(true));
            mvc.perform(get("/api/v1/console/consents").with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Enter an account id, an email address or a phone number."));
            mvc.perform(get("/api/v1/console/consents")
                            .param("userId", amara)
                            .with(TestJwt.staff(officer, StaffRole.DISPATCH)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/consents")
                            .param("userId", amara)
                            .with(TestJwt.staffWithoutMfa(officer, StaffRole.PRIVACY)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/console/consents/withdrawals")
                            .with(TestJwt.staff(officer, StaffRole.ANALYST))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": \"" + amara + "\", \"category\": \"marketing_email\"}"))
                    .andExpect(status().isForbidden());

            mvc.perform(post("/api/v1/console/consents/withdrawals")
                            .with(TestJwt.staff(officer, StaffRole.PRIVACY))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": \"" + amara + "\", \"category\": \"marketing_email\"}"))
                    .andExpect(status().isNoContent());
            assertThat(granted(amara, "marketing_email")).isFalse();
            assertThat(latestSource(amara, "marketing_email")).isEqualTo("console");
            assertThat(jdbc.sql("""
                            select count(*) from developer.audit_log
                             where actor_id = :s and action = 'consent.withdrawn' and target_id = :u""")
                            .param("s", officer)
                            .param("u", amara)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
            mvc.perform(post("/api/v1/console/consents/withdrawals")
                            .with(TestJwt.staff(officer, StaffRole.PRIVACY))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": \"" + amara + "\", \"category\": \"newsletter\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("category"));
        }
    }

    @Nested
    class Retention {

        @Test
        void theProofIsKeptThreeYearsAfterAWithdrawal_anActiveConsentForever() {
            var old = data.user("Old Consent");
            var withdrawnAt = Instant.now().minus(Duration.ofDays(3 * 365 + 2));
            insert(old, "marketing_email", "granted", withdrawnAt.minus(Duration.ofDays(400)));
            insert(old, "marketing_email", "withdrawn", withdrawnAt);
            insert(old, "marketing_sms", "granted", withdrawnAt.minus(Duration.ofDays(400))); // still active
            var recent = data.user("Recent Withdrawal");
            insert(recent, "marketing_email", "granted", Instant.now().minus(Duration.ofDays(30)));
            insert(recent, "marketing_email", "withdrawn", Instant.now().minus(Duration.ofDays(1)));

            assertThat(retention.proofPeriod()).isEqualTo(java.time.Period.ofYears(3));
            retention.purgeExpiredProofs(Instant.now());

            assertThat(jdbc.sql("select category from messaging.consent_records where user_id = :u")
                            .param("u", old)
                            .query(String.class)
                            .list())
                    .containsExactly("marketing_sms");
            assertThat(records(recent)).isEqualTo(2);
        }
    }

    @Test
    void everyNotificationRow_andTheConsentCategories_areClassified() {
        NotificationMatrix.events()
                .forEach(row -> assertThat(MessageClasses.ofRow("team", row))
                        .as("team row " + row)
                        .isPresent());
        CustomerNotificationPrefs.events()
                .forEach(row -> assertThat(MessageClasses.ofRow("customer", row))
                        .as("customer row " + row)
                        .isPresent());
        assertThat(MessageClasses.ROWS.keySet())
                .hasSize(NotificationMatrix.events().size()
                        + CustomerNotificationPrefs.events().size());
        for (var category : ca.northline.messaging.domain.ConsentCategory.values()) {
            assertThat(MessageClasses.ConsentCategory.ofCode(category.code())).isPresent();
            assertThat(ConsentWordings.current(category, ConsentWordings.Surface.ACCOUNT))
                    .isPresent();
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    void grant(String user, String category) {
        insert(user, category, "granted", Instant.now().minusSeconds(60));
    }

    void insert(String user, String category, String action, Instant at) {
        jdbc.sql("""
                        insert into messaging.consent_records (id, user_id, category, action, at, source, wording_version)
                        values (:id, :u, :c, :a, :at, 'web_settings', :w)""")
                .param("id", Ids.next())
                .param("u", user)
                .param("c", category)
                .param("a", action)
                .param("at", Timestamp.from(at))
                .param("w", "granted".equals(action) ? "account.email.2026-10" : null)
                .update();
    }

    boolean granted(String user, String category) {
        return jdbc.sql("""
                        select action = 'granted' from messaging.consent_records where user_id = :u and category = :c
                         order by at desc, id desc limit 1""")
                .params(Map.of("u", user, "c", category))
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    String latestSource(String user, String category) {
        return jdbc.sql("""
                        select source from messaging.consent_records where user_id = :u and category = :c
                         order by at desc, id desc limit 1""")
                .params(Map.of("u", user, "c", category))
                .query(String.class)
                .single();
    }

    int records(String user) {
        return jdbc.sql("select count(*) from messaging.consent_records where user_id = :u")
                .param("u", user)
                .query(Integer.class)
                .single();
    }
}
