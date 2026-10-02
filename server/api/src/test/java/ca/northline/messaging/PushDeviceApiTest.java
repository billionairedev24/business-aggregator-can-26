package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.email.UnsubscribeTokens;
import ca.northline.messaging.domain.PushDeviceRules;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-102: the push device registry ({@code PUT|DELETE /api/v1/me/devices/{installationId}}). App tokens only (the real
 * DPoP proof check is in DpopResourceServerTest); the app comes from the token; one person can't touch another's
 * installation; a token moves to whoever registered it last.
 */
class PushDeviceApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    static String installation() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String token() {
        return "apns-" + UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef";
    }

    static MockHttpServletRequestBuilder register(String installation, String json, JwtRequestPostProcessor as) {
        return put("/api/v1/me/devices/{id}", installation)
                .with(as)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    static String body(String platform, String token, String locale, String permission) {
        return """
                {"platform":"%s","token":"%s","locale":"%s","appVersion":"1.0.0 (42)","permission":"%s"}""".formatted(platform, token, locale, permission);
    }

    Map<String, Object> row(String userId, String installation) {
        return jdbc.sql("""
                        select app, platform, token, locale, app_version, permission from messaging.push_devices
                         where user_id = :u and installation_id = :i""").param("u", userId).param("i", installation).query().singleRow();
    }

    int rows(String installation) {
        return jdbc.sql("select count(*) from messaging.push_devices where installation_id = :i")
                .param("i", installation)
                .query(Integer.class)
                .single();
    }

    @Test
    void theConsumerAppRegistersAndRefreshes_oneRowPerInstallation() throws Exception {
        var user = data.user("Amara Osei");
        var installation = installation();
        var first = token();
        mvc.perform(register(installation, body("ios", first, "fr", "granted"), TestJwt.consumerApp(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value("consumer"))
                .andExpect(jsonPath("$.platform").value("ios"))
                .andExpect(jsonPath("$.locale").value("fr-CA"))
                .andExpect(jsonPath("$.token").doesNotExist());
        var second = token();
        mvc.perform(register(installation, body("ios", second, "en-CA", "provisional"), TestJwt.consumerApp(user)))
                .andExpect(status().isOk());

        assertThat(rows(installation)).isEqualTo(1);
        assertThat(row(user, installation))
                .containsEntry("app", "consumer")
                .containsEntry("token", second)
                .containsEntry("locale", "en-CA")
                .containsEntry("permission", "provisional")
                .containsEntry("app_version", "1.0.0 (42)");
    }

    @Test
    void theCourierAppsTokenRegistersACourierDevice() throws Exception {
        var user = data.user("Kai Courier");
        var installation = installation();
        mvc.perform(register(installation, body("android", token(), "en", "granted"), TestJwt.courier(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value("courier"));
        assertThat(row(user, installation)).containsEntry("app", "courier").containsEntry("platform", "android");
    }

    @Test
    void aDeniedPermission_isKeptWithoutAToken() throws Exception {
        var user = data.user("Amara Osei");
        var installation = installation();
        mvc.perform(register(installation, """
                        {"platform":"ios","locale":"en","appVersion":"1.0.0","permission":"denied"}""", TestJwt.consumerApp(user))).andExpect(status().isOk());
        assertThat(row(user, installation))
                .containsEntry("permission", "denied")
                .containsEntry("token", null);
    }

    @Test
    void onlyKeyBoundAppTokensReachTheRegistry() throws Exception {
        var user = data.user("Amara Osei");
        var installation = installation();
        // a browser session's token (through the BFF) or a Studio token: a bearer token, no cnf
        mvc.perform(register(installation, body("ios", token(), "en", "granted"), TestJwt.customer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(register(installation, body("ios", token(), "en", "granted"), TestJwt.member(user)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/me/devices/{id}", installation).with(TestJwt.customer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/me/devices/{id}", installation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ios", token(), "en", "granted")))
                .andExpect(status().isUnauthorized());
        assertThat(rows(installation)).isZero();
    }

    @Test
    void signOutRemovesTheInstallation_butNobodyCanRemoveSomeoneElses() throws Exception {
        var owner = data.user("Amara Osei");
        var stranger = data.user("Kofi Mensah");
        var installation = installation();
        mvc.perform(register(installation, body("android", token(), "en", "granted"), TestJwt.consumerApp(owner)))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/me/devices/{id}", installation).with(TestJwt.consumerApp(stranger)))
                .andExpect(status().isNotFound());
        // the courier app's token is another app: it can't remove the consumer app's installation either
        mvc.perform(delete("/api/v1/me/devices/{id}", installation).with(TestJwt.courier(owner)))
                .andExpect(status().isNotFound());
        assertThat(rows(installation)).isEqualTo(1);

        mvc.perform(delete("/api/v1/me/devices/{id}", installation).with(TestJwt.consumerApp(owner)))
                .andExpect(status().isNoContent());
        assertThat(rows(installation)).isZero();
        mvc.perform(delete("/api/v1/me/devices/{id}", installation).with(TestJwt.consumerApp(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSharedPhone_theTokenFollowsWhoeverSignedInLast() throws Exception {
        var first = data.user("Amara Osei");
        var second = data.user("Kofi Mensah");
        var installation = installation();
        var token = token();
        mvc.perform(register(installation, body("ios", token, "en", "granted"), TestJwt.consumerApp(first)))
                .andExpect(status().isOk());
        mvc.perform(register(installation, body("ios", token, "en", "granted"), TestJwt.consumerApp(second)))
                .andExpect(status().isOk());

        assertThat(rows(installation)).isEqualTo(1);
        assertThat(row(second, installation)).containsEntry("token", token);
    }

    @Test
    void validationMessages() throws Exception {
        var user = data.user("Amara Osei");
        mvc.perform(register(installation(), body("windows", token(), "en", "granted"), TestJwt.consumerApp(user)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("platform"))
                .andExpect(jsonPath("$.errors[0].message").value(PushDeviceRules.PLATFORM));
        mvc.perform(register(installation(), body("ios", token(), "de", "granted"), TestJwt.consumerApp(user)))
                .andExpect(jsonPath("$.errors[0].message").value(PushDeviceRules.LANGUAGE));
        mvc.perform(register(installation(), body("ios", token(), "en", "maybe"), TestJwt.consumerApp(user)))
                .andExpect(jsonPath("$.errors[0].message").value(PushDeviceRules.PERMISSION));
        mvc.perform(register(installation(), body("ios", "short", "en", "granted"), TestJwt.consumerApp(user)))
                .andExpect(jsonPath("$.errors[0].field").value("token"))
                .andExpect(jsonPath("$.errors[0].message").value(PushDeviceRules.TOKEN));
        mvc.perform(register(installation(), """
                        {"platform":"ios","locale":"en","appVersion":"1.0.0","permission":"granted"}""", TestJwt.consumerApp(user)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value(PushDeviceRules.TOKEN_NEEDED));
        mvc.perform(register("not an id", body("ios", token(), "en", "granted"), TestJwt.consumerApp(user)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value(PushDeviceRules.INSTALLATION));
        mvc.perform(register(installation(), body("ios", token(), "en", "granted"), TestJwt.consumerApp(user))
                        .header("Accept-Language", "fr-CA")
                        .content(body("ios", token(), "en", "granted").replace("1.0.0 (42)", "")))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Envoyez la version de l’appli (32 caractères au plus)."));
    }

    /** The worker's customer emails carry a {@code customer.<row>} unsubscribe link (S-102): it turns that email off. */
    @Test
    void aCustomerUnsubscribeLink_turnsOffThatRowsEmail() throws Exception {
        var user = data.user("Amara Osei");
        var link =
                new UnsubscribeTokens(UnsubscribeTokens.DEV_KEY).issue(user, "customer.order_updates", Locale.CANADA);
        mvc.perform(get("/api/v1/email/unsubscribe").param("t", link))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Order updates &amp; delivery")));
        mvc.perform(post("/api/v1/email/unsubscribe").param("t", link)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/notifications").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.matrix.order_updates.email").value(false))
                .andExpect(jsonPath("$.matrix.order_updates.push").value(true))
                .andExpect(jsonPath("$.matrix.refunds_cases.email").value(true));
        // security alerts can't be turned off by a link
        var security = new UnsubscribeTokens(UnsubscribeTokens.DEV_KEY).issue(user, "customer.security", Locale.CANADA);
        mvc.perform(post("/api/v1/email/unsubscribe").param("t", security)).andExpect(status().isBadRequest());
    }
}
