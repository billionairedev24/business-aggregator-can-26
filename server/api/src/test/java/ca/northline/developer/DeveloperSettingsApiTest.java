package ca.northline.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.developer.api.ApiKeyRevoked;
import ca.northline.developer.api.WebhookEndpointChanged;
import ca.northline.developer.application.WebhookSecretCipher;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Settings › API &amp; integrations (keys, webhooks) and the audit log. */
@RecordApplicationEvents
class DeveloperSettingsApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    WebhookSecretCipher cipher;

    @Nested
    class ApiKeys {

        @Test
        void ownerIssuesAKey_theSecretIsShownOnce_andOnlyItsHashIsKept() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var body = mvc.perform(
                            post("/api/v1/merchants/{id}/settings/api-keys", biz.merchantId())
                                    .with(TestJwt.member(biz.userId()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"name\":\" Website embed \",\"scopes\":[\"booking:write\",\"storefront:read\"]}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.key.name").value("Website embed"))
                    .andExpect(jsonPath("$.key.scopes", contains("storefront:read", "booking:write")))
                    .andExpect(jsonPath("$.secret", startsWith("nl_live_")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String secret = JsonPath.read(body, "$.secret");
            String id = JsonPath.read(body, "$.key.id");

            var stored = jdbc.sql("select key_hash from developer.api_keys where id = ?")
                    .params(id)
                    .query(byte[].class)
                    .single();
            assertThat(stored)
                    .isEqualTo(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));

            mvc.perform(get("/api/v1/merchants/{id}/settings/api-keys", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].prefix").value(secret.substring(0, 12)))
                    .andExpect(jsonPath("$.items[0].secret").doesNotExist());

            mvc.perform(delete("/api/v1/merchants/{id}/settings/api-keys/{k}", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/merchants/{id}/settings/api-keys", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(0)));
            assertThat(events.stream(ApiKeyRevoked.class)
                            .filter(e -> e.aggregateId().equals(id)))
                    .hasSize(1);
            mvc.perform(get("/api/v1/merchants/{id}/settings/audit-log", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[*].action", contains("api_key.revoked", "api_key.issued")))
                    .andExpect(jsonPath("$.items[0].actorName").value("Test owner"));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                no name      | {"name":" ","scopes":["orders:read"]}          | name   | required | Name the key so you can tell it apart.
                long name    | {"name":"LONG","scopes":["orders:read"]}       | name   | length   | At most 60 characters.
                no scopes    | {"name":"Zap","scopes":[]}                     | scopes | required | Pick at least one scope.
                bad scope    | {"name":"Zap","scopes":["admin:all"]}          | scopes | allowed  | Pick scopes from the list.
                """)
        void validationMessages(String name, String json, String field, String rule, String message) throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(post("/api/v1/merchants/{id}/settings/api-keys", biz.merchantId())
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.replace("LONG", "k".repeat(61))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].rule").value(rule))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void teamSeesKeys_onlyTheOwnerChangesThem() throws Exception {
            var biz = data.business(MerchantRole.TECHNICIAN);
            mvc.perform(get("/api/v1/merchants/{id}/settings/api-keys", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/merchants/{id}/settings/api-keys", biz.merchantId())
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Zap\",\"scopes\":[\"orders:read\"]}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(get("/api/v1/merchants/{id}/settings/audit-log", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/merchants/{id}/settings/api-keys", biz.merchantId())
                            .with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            mvc.perform(get("/api/v1/merchants/{id}/settings/api-keys", biz.merchantId())
                            .with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }
    }

    @Nested
    class Webhooks {

        @Test
        void ownerAddsAnEndpoint_signingSecretIsEncrypted_rotatesAndDeletes() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var body = mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks", biz.merchantId())
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"url":"https://prairiewrench.ca/hooks/northline",
                                     "events":["review.created","booking.confirmed"]}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.endpoint.events", contains("booking.confirmed", "review.created")))
                    .andExpect(jsonPath("$.endpoint.signature").value("HMAC-SHA256"))
                    .andExpect(jsonPath("$.secret", startsWith("whsec_")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String id = JsonPath.read(body, "$.endpoint.id");
            String secret = JsonPath.read(body, "$.secret");
            assertThat(cipher.decrypt(stored(id))).isEqualTo(secret);

            var rotated = mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}/secret", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String next = JsonPath.read(rotated, "$.secret");
            assertThat(next).isNotEqualTo(secret);
            assertThat(cipher.decrypt(stored(id))).isEqualTo(next);

            mvc.perform(delete("/api/v1/merchants/{id}/settings/webhooks/{w}", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNoContent());
            assertThat(events.stream(WebhookEndpointChanged.class)
                            .filter(e -> e.aggregateId().equals(id))
                            .map(WebhookEndpointChanged::change))
                    .containsExactly("created", "secret_rotated", "deleted");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                no url     | {"url":"","events":["review.created"]}                    | url    | required | Enter the URL that receives events.
                plain http | {"url":"http://example.com/h","events":["review.created"]} | url    | format   | Enter an https:// URL.
                no events  | {"url":"https://example.com/h","events":[]}               | events | required | Pick at least one event.
                bad event  | {"url":"https://example.com/h","events":["user.deleted"]} | events | allowed  | Pick events from the list.
                """)
        void validationMessages(String name, String json, String field, String rule, String message) throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks", biz.merchantId())
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].rule").value(rule))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        private byte[] stored(String id) {
            return jdbc.sql("select secret_enc from developer.webhook_endpoints where id = ?")
                    .params(id)
                    .query(byte[].class)
                    .single();
        }
    }
}
