package ca.northline.availability.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-136: a calendar read (the sync job, a notification or the read after connecting) racing the member's disconnect.
 * The read used to lock the calendar's source row first and update the link at the end, while the disconnect deletes
 * the link first and then, by cascade, its sources, so Postgres aborted one of them as a deadlock
 * ({@code CalendarSyncApiTest}'s "choose calendars, then disconnect" hit it now and then). Both now lock the link
 * first.
 */
class CalendarLockOrderTest extends IntegrationTest {

    @Autowired
    CalendarSyncService sync;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    Business biz;
    String tech;

    @BeforeEach
    void setUp() {
        biz = data.business(MerchantRole.OWNER);
        tech = data.user("Jas Gill");
        data.member(biz.merchantId(), tech, MerchantRole.TECHNICIAN);
    }

    /**
     * The interleaving that deadlocked, forced: the read holds the source and waits (here on a busy block a third
     * transaction holds) while the disconnect starts; then the read goes on to update the link.
     */
    @Test
    void aReadHoldingTheCalendar_andADisconnect_bothFinish() throws Exception {
        var link = connectGoogle();
        // a full read again (no cursor), so the read rewrites the busy blocks
        jdbc.sql("update availability.calendar_sources set sync_cursor = null where link_id = ?")
                .params(link)
                .update();
        try (var blocker = dataSource.getConnection();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try (var lockBusy = blocker.prepareStatement(
                    "select 1 from availability.calendar_busy_blocks where link_id = ? for update")) {
                lockBusy.setString(1, link);
                lockBusy.executeQuery().close();
            }
            var read = pool.submit(() -> sync.read(link, "primary"));
            awaitWaiting("delete from availability.calendar_busy_blocks");
            var disconnect = pool.submit(this::disconnect);
            awaitWaiting("availability.calendar_links");
            blocker.commit();

            assertThat(read.get(30, TimeUnit.SECONDS)).isTrue();
            disconnect.get(30, TimeUnit.SECONDS);
        }
        assertGone(link);
    }

    /** No forcing: reads of the calendar and the disconnect started together, a few rounds. */
    @Test
    void readsAndADisconnect_startedTogether_allFinish() throws Exception {
        for (var round = 0; round < 5; round++) {
            var link = connectGoogle();
            var tasks = new ArrayList<Callable<Object>>();
            for (var i = 0; i < 4; i++) {
                tasks.add(() -> sync.read(link, "primary"));
            }
            tasks.add(() -> disconnect());
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var result : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                    result.get(); // a deadlock would surface here as the loser's exception
                }
            }
            assertGone(link);
        }
    }

    // ── helpers
    // ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private String path(String rest) {
        return "/api/v1/merchants/" + biz.merchantId() + "/availability" + rest;
    }

    /** Connect → the fake consent page → the callback; returns the link once the first read and write-back ran. */
    private String connectGoogle() throws Exception {
        String url = JsonPath.read(
                mvc.perform(post(path("/calendars/google")).with(TestJwt.member(tech)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.authorizationUrl");
        var q = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        mvc.perform(get("/api/v1/calendar/oauth/google/callback")
                        .param("code", URLDecoder.decode(q.getFirst("code"), StandardCharsets.UTF_8))
                        .param("state", URLDecoder.decode(q.getFirst("state"), StandardCharsets.UTF_8))
                        .with(TestJwt.member(tech)))
                .andExpect(status().isSeeOther());
        var link =
                jdbc.sql("""
                        select id from availability.calendar_links
                         where merchant_id = ? and member_user_id = ? and provider = 'google'
                        """).params(biz.merchantId(), tech).query(String.class).single();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(
                                jdbc.sql("select count(*) from events.event_publication where serialized_event like ?")
                                        .params("%" + link + "%")
                                        .query(Integer.class)
                                        .single())
                        .isZero());
        assertThat(count("calendar_busy_blocks", link)).isEqualTo(1);
        return link;
    }

    private Void disconnect() throws Exception {
        mvc.perform(delete(path("/calendars/google")).with(TestJwt.member(tech)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
        return null;
    }

    /** Until a session is blocked on a row lock while running a statement that contains {@code sql}. */
    private void awaitWaiting(String sql) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("""
                                select count(*) from pg_stat_activity
                                 where wait_event_type = 'Lock' and position(? in query) > 0
                                """).params(sql).query(Integer.class).single() > 0);
    }

    private void assertGone(String link) {
        assertThat(count("calendar_links", link)).isZero();
        assertThat(count("calendar_sources", link)).isZero();
        assertThat(count("calendar_busy_blocks", link)).isZero();
    }

    private int count(String table, String link) {
        var column = table.equals("calendar_links") ? "id" : "link_id";
        return jdbc.sql("select count(*) from availability." + table + " where " + column + " = ?")
                .params(link)
                .query(Integer.class)
                .single();
    }
}
