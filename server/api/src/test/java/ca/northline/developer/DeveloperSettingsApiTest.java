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
import ca.northline.support.TestData;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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

    /** S-33: rotation overlap, the delivery log, resend, test events and turning an endpoint back on. */
    @Nested
    class Delivery {

        @Test
        void rotationKeepsTheOldSecretSigningFor24Hours_orStopsItAtOnce() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var created = addEndpoint(biz);
            String id = JsonPath.read(created, "$.endpoint.id");
            String first = JsonPath.read(created, "$.secret");

            mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}/secret", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.endpoint.previousSecretUntil").isNotEmpty());
            assertThat(cipher.decrypt(column(id, "secret_prev_enc"))).isEqualTo(first);

            mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}/secret", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"overlapHours\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.endpoint.previousSecretUntil").doesNotExist());
            assertThat(jdbc.sql("select secret_prev_enc is null from developer.webhook_endpoints where id = ?")
                            .params(id)
                            .query(Boolean.class)
                            .single())
                    .isTrue();

            mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}/secret", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"overlapHours\":200}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("overlapHours"))
                    .andExpect(jsonPath("$.errors[0].rule").value("range"))
                    .andExpect(jsonPath("$.errors[0].message").value("Keep the old secret for 0 to 168 hours."));
        }

        @Test
        void theDeliveryLogShowsAttempts_andTheOwnerResendsOrSendsATest() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            String id = JsonPath.read(addEndpoint(biz), "$.endpoint.id");
            var delivered = delivery(id, biz.merchantId(), "failed", 2);
            jdbc.sql("""
                            insert into developer.webhook_attempts (id, delivery_id, attempt, at, status_code, error)
                            values (?, ?, 1, now() - interval '2 minutes', null, 'timed out after 15 s'),
                                   (?, ?, 2, now() - interval '1 minute', 503, null)""")
                    .params(delivered + "A", delivered, delivered + "B", delivered)
                    .update();

            mvc.perform(get("/api/v1/merchants/{id}/settings/webhooks/{w}/deliveries", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].state").value("failed"))
                    .andExpect(jsonPath("$.items[0].eventType").value("booking.completed"))
                    .andExpect(jsonPath("$.items[0].attempts").value(2))
                    .andExpect(jsonPath("$.items[0].statusCode").value(503))
                    .andExpect(jsonPath("$.items[0].responseSnippet").value("busy"))
                    .andExpect(jsonPath("$.items[0].history[0].attempt").value(2))
                    .andExpect(jsonPath("$.items[0].history[1].error").value("timed out after 15 s"));

            mvc.perform(post(
                                    "/api/v1/merchants/{id}/settings/webhooks/{w}/deliveries/{d}/resend",
                                    biz.merchantId(),
                                    id,
                                    delivered)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.state").value("pending"))
                    .andExpect(jsonPath("$.resendOf").value(delivered))
                    .andExpect(jsonPath("$.eventId").value("EV" + delivered));
            assertThat(jdbc.sql("""
                            select count(*) from developer.webhook_deliveries
                             where resend_of = ? and event_id = ? and payload = '{"id":"x"}'""")
                            .params(delivered, "EV" + delivered)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);

            var pending = delivery(id, biz.merchantId(), "pending", 1);
            mvc.perform(post(
                                    "/api/v1/merchants/{id}/settings/webhooks/{w}/deliveries/{d}/resend",
                                    biz.merchantId(),
                                    id,
                                    pending)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("delivery_pending"));

            mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}/test", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.eventType").value("webhook.test"))
                    .andExpect(jsonPath("$.test").value(true))
                    .andExpect(jsonPath("$.state").value("pending"));
            assertThat(jdbc.sql("""
                            select action from developer.audit_log where merchant_id = ?
                               and action in ('webhook.delivery_resent', 'webhook.test_sent') order by action""")
                            .params(biz.merchantId())
                            .query(String.class)
                            .list())
                    .containsExactly("webhook.delivery_resent", "webhook.test_sent");
        }

        @Test
        void aTurnedOffEndpointIsShown_refusesResendAndTest_andTheOwnerTurnsItBackOn() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            String id = JsonPath.read(addEndpoint(biz), "$.endpoint.id");
            var failed = delivery(id, biz.merchantId(), "failed", 13);
            jdbc.sql("""
                            update developer.webhook_endpoints
                               set active = false, disabled_at = now(), disabled_reason = 'failing',
                                   failing_since = now() - interval '3 days', consecutive_failures = 13
                             where id = ?""").params(id).update();

            mvc.perform(get("/api/v1/merchants/{id}/settings/webhooks", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[0].active").value(false))
                    .andExpect(jsonPath("$.items[0].disabledAt").isNotEmpty())
                    .andExpect(jsonPath("$.items[0].failingSince").isNotEmpty());
            mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}/test", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("webhook_disabled"));
            mvc.perform(post(
                                    "/api/v1/merchants/{id}/settings/webhooks/{w}/deliveries/{d}/resend",
                                    biz.merchantId(),
                                    id,
                                    failed)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("webhook_disabled"));

            mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}/enable", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.disabledAt").doesNotExist())
                    .andExpect(jsonPath("$.failingSince").doesNotExist());
            assertThat(jdbc.sql("select consecutive_failures from developer.webhook_endpoints where id = ?")
                            .params(id)
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(events.stream(WebhookEndpointChanged.class)
                            .filter(e -> e.aggregateId().equals(id))
                            .map(WebhookEndpointChanged::change))
                    .containsExactly("created", "enabled");
        }

        @Test
        void onlyTheOwnerActs_theTeamReadsTheLog_otherBusinessesSeeNothing() throws Exception {
            var owner = data.business(MerchantRole.OWNER);
            String id = JsonPath.read(addEndpoint(owner), "$.endpoint.id");
            var technician = data.user("Jas");
            data.member(owner.merchantId(), technician, MerchantRole.TECHNICIAN);
            var other = data.business(MerchantRole.OWNER);

            mvc.perform(get("/api/v1/merchants/{id}/settings/webhooks/{w}/deliveries", owner.merchantId(), id)
                            .with(TestJwt.member(technician)))
                    .andExpect(status().isOk());
            for (var path : List.of("/test", "/enable")) {
                mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}" + path, owner.merchantId(), id)
                                .with(TestJwt.member(technician)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("insufficient_role"));
                mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}" + path, owner.merchantId(), id)
                                .with(TestJwt.memberWithoutMfa(owner.userId())))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("mfa_required"));
                mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}" + path, owner.merchantId(), id)
                                .with(TestJwt.member(other.userId())))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("not_a_member"));
            }
            mvc.perform(get("/api/v1/merchants/{id}/settings/webhooks/{w}/deliveries", other.merchantId(), id)
                            .with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks/{w}/test", other.merchantId(), id)
                            .with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
        }

        private String addEndpoint(TestData.Business biz) throws Exception {
            return mvc.perform(post("/api/v1/merchants/{id}/settings/webhooks", biz.merchantId())
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://example.com/hooks\",\"events\":[\"booking.completed\"]}"))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
        }

        /** A delivery as the worker writes it; its event id is "EV" + its id. */
        private String delivery(String endpointId, String merchantId, String state, int attempts) {
            var id = ca.northline.shared.Ids.next();
            jdbc.sql("""
                            insert into developer.webhook_deliveries
                                   (id, endpoint_id, merchant_id, event_id, event_type, payload, state, attempt,
                                    status_code, at, next_attempt_at, response_snippet, created_at)
                            values (?, ?, ?, ?, 'booking.completed', '{"id":"x"}', ?, ?, 503, now(),
                                    case when ? = 'pending' then now() + interval '1 hour' end, 'busy', now())""")
                    .params(id, endpointId, merchantId, "EV" + id, state, attempts, state)
                    .update();
            return id;
        }

        private byte[] column(String id, String column) {
            return jdbc.sql("select " + column + " from developer.webhook_endpoints where id = ?")
                    .params(id)
                    .query(byte[].class)
                    .single();
        }
    }
}
