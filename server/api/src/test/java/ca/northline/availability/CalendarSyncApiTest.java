package ca.northline.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.availability.application.CalendarUseCases.CalendarJobs;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-32 against the local fake provider (the {@code local}/{@code test} default): the OAuth round trip through the
 * callback, the refresh token sealed at rest, busy blocks in the preview, write-back, calendar choice and
 * authorization. The real Google / Microsoft adapters are covered by {@code CalendarProvidersWireMockTest}.
 */
class CalendarSyncApiTest extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CalendarJobs jobs;

    Business biz;
    String tech;

    @BeforeEach
    void setUp() {
        biz = data.business(MerchantRole.OWNER);
        tech = data.user("Jas Gill");
        data.member(biz.merchantId(), tech, MerchantRole.TECHNICIAN);
    }

    private String path(String rest) {
        return "/api/v1/merchants/" + biz.merchantId() + "/availability" + rest;
    }

    /** "Connect" → the fake consent page → the callback, as the browser would. Returns the callback's Location. */
    static Map<String, String> authorization(String json) {
        String url = JsonPath.read(json, "$.authorizationUrl");
        var q = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        return Map.of(
                "code", URLDecoder.decode(q.getFirst("code"), StandardCharsets.UTF_8),
                "state", URLDecoder.decode(q.getFirst("state"), StandardCharsets.UTF_8));
    }

    private Map<String, String> startConnect(String provider, String user) throws Exception {
        return authorization(mvc.perform(post(path("/calendars/" + provider)).with(TestJwt.member(user)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private String linkId(String provider, String user) {
        return jdbc.sql(
                        "select id from availability.calendar_links where merchant_id = ? and member_user_id = ? and provider = ?")
                .params(biz.merchantId(), user, provider)
                .query(String.class)
                .single();
    }

    @Test
    void connectGoogle_throughTheCallback_sealsTheToken_andBlocksSlots() throws Exception {
        var auth = startConnect("google", tech);
        mvc.perform(get("/api/v1/calendar/oauth/google/callback")
                        .param("code", auth.get("code"))
                        .param("state", auth.get("state"))
                        .with(TestJwt.member(tech)))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(
                                "Location",
                                "/b/" + biz.merchantId() + "/availability?calendar=google&result=connected"));

        var link = linkId("google", tech);
        var row = jdbc.sql("select token_ref, refresh_token_enc, state from availability.calendar_links where id = ?")
                .params(link)
                .query((rs, _) -> Map.of(
                        "ref", rs.getString("token_ref"),
                        "enc", new String(rs.getBytes("refresh_token_enc"), StandardCharsets.ISO_8859_1),
                        "state", rs.getString("state")))
                .single();
        assertThat(row.get("ref")).startsWith("local:");
        assertThat(row.get("enc")).doesNotContain("fake-refresh");
        assertThat(row.get("state")).isEqualTo("connected");

        // the first read runs after commit (outbox listener): the fake's lunch block tomorrow 12:00–13:00
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(
                                jdbc.sql("select count(*) from availability.calendar_busy_blocks where link_id = ?")
                                        .params(link)
                                        .query(Integer.class)
                                        .single())
                        .isEqualTo(1));
        assertThat(jdbc.sql("""
                                select column_name from information_schema.columns
                                 where table_schema = 'availability' and table_name = 'calendar_busy_blocks'
                                """).query(String.class).list())
                .as("busy blocks keep start, end and the provider's id — no title, attendees or place")
                .containsExactlyInAnyOrder("link_id", "calendar_id", "external_event_id", "starts_at", "ends_at");

        var tomorrow = LocalDate.now(ZONE).plusDays(1);
        mvc.perform(post(path("/preview"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberUserId":"%s","date":"%s","durationMin":60,"ranges":[["11:00","14:00"]],
                                 "intervalMin":60,"bufferMin":0}""".formatted(tech, tomorrow))
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busyBlocks").value(1))
                .andExpect(jsonPath("$.slots[?(@.start == '11:00')].free").value(contains(true)))
                .andExpect(jsonPath("$.slots[?(@.start == '12:00')].free").value(contains(false)))
                .andExpect(jsonPath("$.slots[?(@.start == '13:00')].free").value(contains(true)));

        mvc.perform(get(path("/sync")).with(TestJwt.member(tech)))
                .andExpect(jsonPath("$.calendars[0].connected").value(true))
                .andExpect(jsonPath("$.calendars[0].state").value("connected"))
                .andExpect(jsonPath("$.calendars[0].accountLabel").value("Google account (local fake)"))
                .andExpect(jsonPath("$.calendars[0].sources").value(contains("Work (local fake)")));
    }

    @Test
    void writeBack_createsMovesAndDeletesTheMembersEvents() throws Exception {
        var auth = startConnect("outlook", tech);
        mvc.perform(get("/api/v1/calendar/oauth/outlook/callback")
                        .param("code", auth.get("code"))
                        .param("state", auth.get("state"))
                        .with(TestJwt.member(tech)))
                .andExpect(status().isSeeOther());
        var link = linkId("outlook", tech);
        var ops = new OperationsFixtures(jdbc);
        var customer = data.user("Amara Osei");
        var job = ops.job(
                biz.merchantId(), tech, customer, java.time.Instant.now().plus(Duration.ofDays(2)), "confirmed");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            jobs.writeBackDue();
            assertThat(mirror(link, job)).isNotNull();
        });
        assertThat(mirror(link, job)).startsWith("fake-event-");

        jdbc.sql("update booking.bookings set state = 'cancelled' where id = ?")
                .params(job)
                .update();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            jobs.writeBackDue();
            assertThat(mirror(link, job)).isNull();
        });
    }

    private @org.jspecify.annotations.Nullable String mirror(String link, String booking) {
        return jdbc.sql(
                        "select external_event_id from availability.calendar_event_mirrors where link_id = ? and booking_id = ?")
                .params(link, booking)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    @Test
    void theStateBelongsToTheMemberWhoStarted_andIsSingleUse() throws Exception {
        var auth = startConnect("google", tech);
        // someone else signed in comes back with the member's state: refused, and the state is spent
        mvc.perform(get("/api/v1/calendar/oauth/google/callback")
                        .param("code", auth.get("code"))
                        .param("state", auth.get("state"))
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?calendar=google&result=failed"));
        mvc.perform(get("/api/v1/calendar/oauth/google/callback")
                        .param("code", auth.get("code"))
                        .param("state", auth.get("state"))
                        .with(TestJwt.member(tech)))
                .andExpect(header().string("Location", "/?calendar=google&result=expired"));
        mvc.perform(get("/api/v1/calendar/oauth/google/callback")
                        .param("code", auth.get("code"))
                        .param("state", "not-a-state")
                        .with(TestJwt.member(tech)))
                .andExpect(header().string("Location", "/?calendar=google&result=expired"));
        assertThat(jdbc.sql(
                                "select count(*) from availability.calendar_links where member_user_id = ? and provider = 'google'")
                        .params(tech)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void consentDenied_landsOnAvailabilityWithDenied() throws Exception {
        var auth = startConnect("google", tech);
        mvc.perform(get("/api/v1/calendar/oauth/google/callback")
                        .param("error", "access_denied")
                        .param("state", auth.get("state"))
                        .with(TestJwt.member(tech)))
                .andExpect(header().string(
                                "Location", "/b/" + biz.merchantId() + "/availability?calendar=google&result=denied"));
    }

    @Test
    void callbackNeedsASignedInMember_andAKnownProvider() throws Exception {
        mvc.perform(get("/api/v1/calendar/oauth/google/callback")
                        .param("code", "x")
                        .param("state", "y"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/calendar/oauth/ical/callback")
                        .param("state", "y")
                        .with(TestJwt.member(tech)))
                .andExpect(status().isNotFound());
    }

    @Test
    void chooseCalendars_listsThem_validates_andReplacesTheSources() throws Exception {
        var auth = startConnect("google", tech);
        mvc.perform(get("/api/v1/calendar/oauth/google/callback")
                        .param("code", auth.get("code"))
                        .param("state", auth.get("state"))
                        .with(TestJwt.member(tech)))
                .andExpect(status().isSeeOther());

        mvc.perform(get(path("/calendars/google/sources")).with(TestJwt.member(tech)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[?(@.id == 'primary')].selected").value(contains(true)))
                .andExpect(jsonPath("$.items[?(@.id == 'family')].selected").value(contains(false)));

        mvc.perform(put(path("/calendars/google/sources"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calendarIds\":[]}")
                        .with(TestJwt.member(tech)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("calendarIds"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose at least one calendar."));
        mvc.perform(put(path("/calendars/google/sources"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calendarIds\":[\"primary\",\"someone-else\"]}")
                        .with(TestJwt.member(tech)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("calendarIds[1]"))
                .andExpect(jsonPath("$.errors[0].message").value("This calendar isn't in your account any more."));

        mvc.perform(put(path("/calendars/google/sources"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calendarIds\":[\"family\"]}")
                        .with(TestJwt.member(tech)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == 'family')].selected").value(contains(true)))
                .andExpect(jsonPath("$.items[?(@.id == 'primary')].selected").value(contains(false)));
        mvc.perform(get(path("/sync")).with(TestJwt.member(tech)))
                .andExpect(jsonPath("$.calendars[0].sources").value(contains("Family (local fake)")));

        mvc.perform(delete(path("/calendars/google")).with(TestJwt.member(tech)))
                .andExpect(jsonPath("$.connected").value(false));
        assertThat(jdbc.sql("select count(*) from availability.calendar_sources s where not exists"
                                + " (select 1 from availability.calendar_links l where l.id = s.link_id)")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void authorization_membersOnly_withMfa_andNotForBookkeepers() throws Exception {
        var outsider = data.business(MerchantRole.OWNER);
        mvc.perform(post(path("/calendars/google")).with(TestJwt.member(outsider.userId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(post(path("/calendars/google")).with(TestJwt.memberWithoutMfa(tech)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        var bookkeeper = data.user("Priya Sandhu");
        data.member(biz.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
        mvc.perform(get(path("/calendars/google/sources")).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get(path("/calendars/google/sources")).with(TestJwt.member(tech)))
                .andExpect(status().isNotFound());
    }

    @Test
    void webhooksArePublic_butRefuseWhatTheyCantVerify() throws Exception {
        mvc.perform(post("/api/v1/webhooks/calendar/google")
                        .header("X-Goog-Channel-ID", "unknown")
                        .header("X-Goog-Channel-Token", "forged")
                        .header("X-Goog-Resource-State", "exists"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_notification"));
        mvc.perform(post("/api/v1/webhooks/calendar/microsoft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":[{\"subscriptionId\":\"nope\",\"clientState\":\"forged\"}]}"))
                .andExpect(status().isForbidden());
        // no subscription is being created: Graph's handshake is not answered
        mvc.perform(post("/api/v1/webhooks/calendar/microsoft").param("validationToken", "<script>x</script>"))
                .andExpect(status().isForbidden());
    }
}
