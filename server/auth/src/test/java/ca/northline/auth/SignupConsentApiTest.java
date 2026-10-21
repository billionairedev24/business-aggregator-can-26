package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.email.ConsentProof;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-157: the sign-up forms' unticked marketing checkboxes (CASL express consent). The form shows the wording from
 * {@code /api/auth/accounts/consent-wordings}; a ticked box becomes a {@code messaging.consent_records} grant with the
 * wording version, the time, the place and the minimised evidence — only once the account exists.
 */
class SignupConsentApiTest extends AuthIntegrationTest {

    static final String PASSWORD = "correct horse battery staple";
    static final String EMAIL_WORDING = "account.email.2026-10";
    static final String SMS_WORDING = "account.sms.2026-10";

    private ResultActions postJson(String path, MockHttpSession session, String body, String language)
            throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .header("Accept-Language", language)
                .header("User-Agent", "Mozilla/5.0 (test)")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String email() {
        return "efua." + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@example.ca";
    }

    private static String form(String email, String app, String phone, String marketing) {
        return """
                {"firstName":"Efua","lastName":"Owusu","email":"%s","password":"%s","passwordConfirm":"%s",
                 "terms":true,"app":"%s","phone":%s,"marketing":%s}""".formatted(email, PASSWORD, PASSWORD, app, phone, marketing);
    }

    private static String box(String category, String version) {
        return "{\"category\":\"%s\",\"wordingVersion\":\"%s\"}".formatted(category, version);
    }

    private List<Map<String, Object>> consents(String email) {
        return jdbc.sql("""
                        SELECT c.category, c.action, c.source, c.wording_version, c.language, c.address_hash,
                               c.ip_prefix, c.user_agent_hash
                          FROM messaging.consent_records c JOIN identity.users u ON u.id = c.user_id
                         WHERE u.email = CAST(:e AS citext) ORDER BY c.category""").param("e", email).query().listOfRows();
    }

    @Test
    void consumerForm_showsBothWordings_inTheRequestsLanguage() throws Exception {
        mvc.perform(get("/api/auth/accounts/consent-wordings").param("app", "consumer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].category").value("marketing_email"))
                .andExpect(jsonPath("$.items[0].wordingVersion").value(EMAIL_WORDING))
                .andExpect(jsonPath("$.items[0].wording").value(containsString("may email me")))
                .andExpect(jsonPath("$.items[1].category").value("marketing_sms"))
                .andExpect(jsonPath("$.requester").value(containsString("1200 – 8th Avenue SW")));
        mvc.perform(get("/api/auth/accounts/consent-wordings")
                        .param("app", "studio")
                        .header("Accept-Language", "fr-CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].wordingVersion").value("studio.email.2026-10"))
                .andExpect(jsonPath("$.items[0].wording").value(containsString("peut m’envoyer par courriel")));
        mvc.perform(get("/api/auth/accounts/consent-wordings").param("app", "console"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void tickedBoxes_areRecordedWhenTheAccountIsCreated_withWordingTimePlaceAndEvidence() throws Exception {
        var email = email();
        var session = new MockHttpSession();
        var marketing = "[" + box("marketing_email", EMAIL_WORDING) + "," + box("marketing_sms", SMS_WORDING) + "]";
        postJson("/api/auth/accounts", session, form(email, "consumer", "\"+1 587 555 0177\"", marketing), "fr-CA")
                .andExpect(status().isAccepted());
        // pending: nothing recorded before the address is confirmed
        assertThat(jdbc.sql("SELECT jsonb_array_length(marketing_consents) FROM auth.pending_accounts"
                                + " WHERE email = CAST(:e AS citext)")
                        .param("e", email)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))), "fr-CA")
                .andExpect(status().isCreated());

        var rows = consents(email);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst())
                .containsEntry("category", "marketing_email")
                .containsEntry("action", "granted")
                .containsEntry("source", "web_signup")
                .containsEntry("wording_version", EMAIL_WORDING)
                .containsEntry("language", "fr")
                .containsEntry("address_hash", ConsentProof.addressHash(email))
                .containsEntry("ip_prefix", "127.0.0.0/24");
        assertThat((String) rows.getFirst().get("user_agent_hash")).hasSize(64);
        assertThat(rows.get(1))
                .containsEntry("category", "marketing_sms")
                .containsEntry("wording_version", SMS_WORDING)
                .containsEntry("address_hash", ConsentProof.addressHash("+15875550177"));
    }

    @Test
    void untickedBoxes_recordNothing() throws Exception {
        var email = email();
        var session = new MockHttpSession();
        postJson("/api/auth/accounts", session, form(email, "studio", "null", "[]"), "en")
                .andExpect(status().isAccepted());
        postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))), "en")
                .andExpect(status().isCreated());
        assertThat(consents(email)).isEmpty();
    }

    @Test
    void theStudioForm_recordsItsOwnWording() throws Exception {
        var email = email();
        var session = new MockHttpSession();
        postJson(
                        "/api/auth/accounts",
                        session,
                        form(email, "studio", "null", "[" + box("marketing_email", "studio.email.2026-10") + "]"),
                        "en")
                .andExpect(status().isAccepted());
        postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))), "en")
                .andExpect(status().isCreated());
        assertThat(consents(email))
                .singleElement()
                .satisfies(r -> assertThat(r)
                        .containsEntry("wording_version", "studio.email.2026-10")
                        .containsEntry("language", "en"));
    }

    @Test
    void aStaleWording_aCategoryTheFormDoesntOffer_orTextsWithoutAMobile_are422() throws Exception {
        postJson(
                        "/api/auth/accounts",
                        new MockHttpSession(),
                        form(email(), "consumer", "null", "[" + box("marketing_email", "account.email.2025-01") + "]"),
                        "en")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("marketing"))
                .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.CONSENT_WORDING_CHANGED));
        postJson(
                        "/api/auth/accounts",
                        new MockHttpSession(),
                        form(email(), "studio", "null", "[" + box("marketing_sms", SMS_WORDING) + "]"),
                        "en")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.CONSENT_CHOOSE));
        postJson(
                        "/api/auth/accounts",
                        new MockHttpSession(),
                        form(email(), "consumer", "null", "[" + box("marketing_sms", SMS_WORDING) + "]"),
                        "en")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("phone"))
                .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.CONSENT_SMS_NEEDS_PHONE));
        postJson(
                        "/api/auth/accounts",
                        new MockHttpSession(),
                        form(
                                email(),
                                "consumer",
                                "null",
                                "[{\"category\":\"marketing_fax\",\"wordingVersion\":\"x\"}]"),
                        "en")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theAppsPhoneRegistration_recordsAppSignup() throws Exception {
        var person = newPerson();
        var session = new MockHttpSession();
        var body = person.json()
                .replace(
                        "\"terms\":true}",
                        "\"terms\":true,\"consentSource\":\"app_signup\",\"marketing\":["
                                + box("marketing_sms", SMS_WORDING) + "]}");
        postJson("/api/auth/register", session, body, "en").andExpect(status().isOk());
        postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))), "en")
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/register/complete").session(session)).andExpect(status().isCreated());
        assertThat(consents(person.email()))
                .singleElement()
                .satisfies(r -> assertThat(r)
                        .containsEntry("category", "marketing_sms")
                        .containsEntry("source", "app_signup")
                        .containsEntry("address_hash", ConsentProof.addressHash(person.e164())));
    }
}
