package ca.northline.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ca.northline.shared.Ids;
import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixtures for the messaging, help and reviews API tests: SQL-level threads, messages and merchant tweaks. */
abstract class MessagingApiTest extends IntegrationTest {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected List<NavBadgeContributor> badgeContributors;

    protected String thread(
            String merchantId,
            String kind,
            @Nullable String assigneeId,
            String counterpart,
            String refType,
            String refCode) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into messaging.threads (id, merchant_id, kind, ref_type, ref_id, ref_code, counterpart_id,
                                                       counterpart_name, subject, assignee_id, participant_ids, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 'brake inspection Tue 9:00', ?, '{}', now() - interval '1 day')
                        """)
                .params(id, merchantId, kind, refType, Ids.next(), refCode, Ids.next(), counterpart, assigneeId)
                .update();
        return id;
    }

    protected void message(String threadId, String role, String body, Instant at) {
        jdbc.sql("""
                        insert into messaging.messages (id, thread_id, sender_id, sender_role, sender_name, body,
                                                        attachments, at, flagged)
                        values (?, ?, ?, ?, ?, ?, '{}', ?, false)
                        """)
                .params(
                        Ids.next(),
                        threadId,
                        Ids.next(),
                        role,
                        "agent".equals(role) ? "Dev K." : null,
                        body,
                        at.atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql(
                        "update messaging.threads set last_message_at = greatest(coalesce(last_message_at, ?), ?) where id = ?")
                .params(at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), threadId)
                .update();
    }

    protected void tier(String merchantId, String tier) {
        jdbc.sql("update merchants.merchants set tier = ? where id = ?")
                .params(tier, merchantId)
                .update();
    }

    protected String member(String merchantId, MerchantRole role) {
        var user = data.user("Team " + role.code());
        data.member(merchantId, user, role);
        return user;
    }

    protected Map<String, String> badges(String merchantId, String userId, MerchantRole role, Locale locale) {
        var all = new HashMap<String, String>();
        badgeContributors.forEach(
                c -> all.putAll(c.badges(new NavBadgeContributor.Context(merchantId, userId, role, locale))));
        return all;
    }

    static MockHttpServletRequestBuilder postJson(String path, String body, Object... vars) {
        return post(path, vars).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static JsonNode json(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static Instant ago(Duration duration) {
        return Instant.now().minus(duration);
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static void eventually(ThrowingCheck check) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(check::run);
    }

    @FunctionalInterface
    interface ThrowingCheck {
        void run() throws Exception;
    }
}
