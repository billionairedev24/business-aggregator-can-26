package ca.northline.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.food.api.KitchenOrderReady;
import ca.northline.orders.api.OrderPacked;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-68: {@code GET /api/v1/merchants/{id}/live} — members only (with MFA); "ready" on open; a message sent in one of
 * the business's threads, a food order and a goods order each reach the open stream as their event, with ids only;
 * another business's signals never do.
 */
class StudioLiveApiTest extends IntegrationTest {

    static final String LIVE = "/api/v1/merchants/{m}/live";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Test
    void onlyMembersWithMfaOpenTheStream() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get(LIVE, biz.merchantId()).with(TestJwt.member(data.user("Not a member"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(LIVE, biz.merchantId()).with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMessageSentInAThreadReachesTheOpenStreamWithTheThreadId() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var other = data.business(MerchantRole.OWNER);
        var stream = open(biz.merchantId(), biz.userId());
        var quiet = open(other.merchantId(), other.userId());
        assertThat(stream.getContentAsString()).contains("event:ready");

        var thread = thread(biz.merchantId());
        mvc.perform(post("/api/v1/merchants/{m}/threads/{t}/messages", biz.merchantId(), thread)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"On my way\"}")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isCreated());

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(stream.getContentAsString())
                        .contains("event:message")
                        .contains("{\"ref\":\"" + thread + "\"}"));
        assertThat(stream.getContentAsString()).doesNotContain("On my way");
        assertThat(quiet.getContentAsString()).doesNotContain("event:message");
    }

    @Test
    void foodOrdersSignalTheKitchenAndGoodsOrdersTheOrdersScreen() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var stream = open(biz.merchantId(), biz.userId());
        var food = Ids.next();
        var goods = Ids.next();
        var now = Instant.now();
        tx.executeWithoutResult(_ -> {
            events.publishEvent(new OrderPlaced(
                    Ids.next(),
                    now,
                    food,
                    biz.merchantId(),
                    Ids.next(),
                    "NL-1",
                    "food",
                    "pickup",
                    null,
                    1200,
                    60,
                    List.of()));
            events.publishEvent(new OrderPlaced(
                    Ids.next(),
                    now,
                    goods,
                    biz.merchantId(),
                    Ids.next(),
                    "NL-2",
                    "goods",
                    "pooled",
                    null,
                    3350,
                    182,
                    List.of()));
        });
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(stream.getContentAsString())
                        .contains("event:kitchen\ndata:{\"ref\":\"" + food + "\"}")
                        .contains("event:orders\ndata:{\"ref\":\"" + goods + "\"}"));

        tx.executeWithoutResult(_ -> {
            events.publishEvent(new KitchenOrderReady(Ids.next(), now, food, biz.merchantId(), biz.userId(), false));
            events.publishEvent(new OrderPacked(Ids.next(), now, goods, biz.merchantId(), biz.userId(), 1, "packed"));
        });
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var body = stream.getContentAsString();
            assertThat(body.split("event:kitchen").length - 1).isEqualTo(2);
            assertThat(body.split("event:orders").length - 1).isEqualTo(2);
        });
    }

    private MockHttpServletResponse open(String merchantId, String userId) throws Exception {
        return mvc.perform(get(LIVE, merchantId).with(TestJwt.member(userId)))
                .andExpect(request().asyncStarted())
                .andReturn()
                .getResponse();
    }

    private String thread(String merchantId) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into messaging.threads (id, merchant_id, kind, ref_type, ref_id, ref_code, counterpart_id,
                                                       counterpart_name, subject, assignee_id, participant_ids, created_at)
                        values (?, ?, 'customer', 'booking', ?, 'BK-0068', ?, 'Amara Osei', 'Live', null, '{}', now())
                        """).params(id, merchantId, Ids.next(), Ids.next()).update();
        return id;
    }
}
