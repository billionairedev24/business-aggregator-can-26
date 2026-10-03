package ca.northline.golive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.golive.api.PilotReadiness;
import ca.northline.golive.application.UatVerdict;
import ca.northline.golive.domain.Gate;
import ca.northline.identity.api.OncallRota;
import ca.northline.payments.api.StripeMode;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.Regions;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-118: a market's go-live checklist, manual gates recorded with who and when, the two-person launch (and its
 * emergency override), the rollback that hides the market again, and the hypercare rota. Each test opens its own
 * market in a live province (far from every other test's points) and removes it afterwards. The sources the test
 * database can't make real — Stripe live keys, a signed-off UAT, ten pilot businesses, a covered on-call rota — are
 * mocks; the manual gates go through the API.
 */
class GoLiveApiTest extends IntegrationTest {

    static final String POLYGON =
            "{\"type\":\"Polygon\",\"coordinates\":[[[-117.05,58.48],[-116.95,58.48],[-116.95,58.52],[-117.05,58.52],[-117.05,58.48]]]}";
    static final List<String> MANUAL = List.of(
            "security_findings",
            "pentest",
            "backup_drill",
            "alert_rules",
            "slo_alerts",
            "legal_signoff",
            "pci_saq_a",
            "stripe_webhooks",
            "dns_certs",
            "app_stores",
            "a11y_criticals",
            "e2e_results",
            "load_test");

    @MockitoBean
    PilotReadiness pilots;

    @MockitoBean
    StripeMode stripe;

    @MockitoBean
    UatVerdict uat;

    @MockitoBean
    OncallRota rota;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Regions regions;

    String requester;
    String approver;
    String city;
    String market;

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String staffMember(String name) {
        var id = data.user(name);
        jdbc.sql("insert into identity.platform_roles (user_id, role) values (?, 'staff'), (?, 'admin')")
                .params(id, id)
                .update();
        return id;
    }

    @BeforeEach
    void market() throws Exception {
        requester = staffMember("Avery Admin");
        approver = staffMember("Blake Admin");
        city = "Golive " + Ids.next().substring(18);
        var body = mvc.perform(json(
                                post("/api/v1/console/regions/markets"),
                                "{\"province\":\"AB\",\"city\":\"%s\",\"lat\":58.5,\"lng\":-117.0,\"radiusKm\":5}"
                                        .formatted(city))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        market = JsonPath.<List<String>>read(body, "$.markets[?(@.city == '%s')].id".formatted(city))
                .getFirst();
        mvc.perform(json(
                                post("/api/v1/console/regions/zones"),
                                "{\"marketId\":\"%s\",\"name\":\"Centre\",\"boundary\":%s}"
                                        .formatted(market, quote(POLYGON)))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isOk());
        mvc.perform(json(
                                post("/api/v1/console/regions/markets/{id}/stage", market),
                                "{\"stage\":\"pilot\",\"confirm\":\"%s\"}".formatted(city))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isOk());
        when(pilots.counts(anyString())).thenReturn(new PilotReadiness.Counts(12, 12, 0));
        when(stripe.mode()).thenReturn(new StripeMode.Mode("live", "live", true, true));
        when(uat.verdict()).thenReturn(new UatVerdict.Verdict(true, 0, 0, List.of()));
        var now = Instant.now();
        when(rota.between(any(), any()))
                .thenReturn(List.of(new OncallRota.Shift(
                        "S1",
                        requester,
                        "Avery Admin",
                        now.minusSeconds(3600),
                        now.plus(Duration.ofDays(30)),
                        "Platform")));
        when(rota.add(anyString(), any(), any(), anyString(), anyString()))
                .thenAnswer(i -> new OncallRota.Shift(
                        Ids.next(), i.getArgument(0), "x", i.getArgument(1), i.getArgument(2), i.getArgument(3)));
    }

    @AfterEach
    void removeMarket() {
        jdbc.sql("delete from region.zones where region_id = ?").param(market).update();
        jdbc.sql("delete from region.regions where id = ?").param(market).update();
        regions.refresh();
    }

    static String quote(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    void recordAll(String status) throws Exception {
        for (var gate : MANUAL) {
            mvc.perform(json(
                                    post("/api/v1/console/go-live/{m}/gates/{g}", market, gate),
                                    "{\"status\":\"%s\",\"evidence\":\"Checked for the rehearsal.\",\"evidenceUrl\":\"https://example.test/evidence\"}"
                                            .formatted(status))
                            .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    String request(String body) throws Exception {
        var response = mvc.perform(json(post("/api/v1/console/go-live/{m}/launch-requests", market), body)
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.state").value("pending"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.request.id");
    }

    String stage() {
        return jdbc.sql("select stage from region.regions where id = ?")
                .param(market)
                .query(String.class)
                .single();
    }

    long audited(String action) {
        return jdbc.sql("select count(*) from developer.audit_log where action = ? and target_id = ?")
                .params(action, market)
                .query(Long.class)
                .single();
    }

    String hiddenCause(String merchantId) {
        return jdbc.sql("select coalesce(search_hidden_cause, '') from merchants.merchants where id = ?")
                .param(merchantId)
                .query(String.class)
                .single();
    }

    @Test
    void theChecklist_listsEveryGate_manualOnesPending_andBlocksTheLaunch() throws Exception {
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.stage").value("pilot"))
                .andExpect(jsonPath("$.market.frenchFirst").value(false))
                .andExpect(jsonPath("$.gates", hasSize(Gate.values().length)))
                .andExpect(
                        jsonPath("$.gates[?(@.key == 'market_zones')].status").value("pass"))
                .andExpect(
                        jsonPath("$.gates[?(@.key == 'province_live')].status").value("pass"))
                .andExpect(jsonPath("$.gates[?(@.key == 'pilot_businesses')].params.ready")
                        .value("12"))
                .andExpect(
                        jsonPath("$.gates[?(@.key == 'oncall_coverage')].code").value("oncall_covered"))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].status")
                        .value("pending"))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].code")
                        .value("not_recorded"))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].owner")
                        .value("security"))
                .andExpect(jsonPath("$.gates[?(@.key == 'alert_rules')].recordable")
                        .value(true))
                .andExpect(jsonPath("$.gates[?(@.key == 'stripe_live')].recordable")
                        .value(false))
                .andExpect(jsonPath("$.gates[?(@.key == 'french_coverage')].status")
                        .value("not_applicable"))
                .andExpect(
                        jsonPath("$.gates[?(@.key == 'app_stores')].required").value(false))
                .andExpect(jsonPath("$.blocking", hasItem("security_findings")))
                .andExpect(jsonPath("$.blocking", not(hasItem("app_stores"))))
                .andExpect(jsonPath("$.ready").value(false));
        mvc.perform(get("/api/v1/console/go-live").with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].stage".formatted(market))
                        .value("pilot"));

        // a failing automatic source blocks too
        when(pilots.counts(anyString())).thenReturn(new PilotReadiness.Counts(7, 9, 2));
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.gates[?(@.key == 'pilot_businesses')].status")
                        .value("fail"))
                .andExpect(jsonPath("$.gates[?(@.key == 'pilot_businesses')].params.min")
                        .value("10"))
                .andExpect(jsonPath("$.blocking", hasItem("pilot_businesses")));

        mvc.perform(json(post("/api/v1/console/go-live/{m}/launch-requests", market), "{}")
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_ready"));
        mvc.perform(json(post("/api/v1/console/go-live/{m}/launch-requests", market), "{\"overrideReason\":\"now\"}")
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("overrideReason"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Give the reason for the emergency override, 20 to 500 characters."));
        assertThat(stage()).isEqualTo("pilot");
    }

    @Test
    void manualGates_areRecordedWithWhoAndWhen_automaticOnesAreNot() throws Exception {
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/gates/security_findings", market),
                                "{\"status\":\"pass\",\"evidence\":\"No open critical or high finding (findings.md).\",\"source\":\"script\"}")
                        .with(TestJwt.staff(approver, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].status")
                        .value("pass"))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].code")
                        .value("recorded"))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].source")
                        .value("script"))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].recordedBy.name")
                        .value("Blake Admin"))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].recordedAt")
                        .isNotEmpty());
        assertThat(audited("golive.gate_recorded")).isEqualTo(1);

        // an older record that is too old counts as pending again
        jdbc.sql("update golive.gate_records set recorded_at = now() - interval '15 days' where market_id = ?")
                .param(market)
                .update();
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].status")
                        .value("pending"))
                .andExpect(jsonPath("$.gates[?(@.key == 'security_findings')].code")
                        .value("record_expired"));

        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/gates/stripe_live", market),
                                "{\"status\":\"pass\",\"evidence\":\"x\"}")
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("gate_automatic"));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/gates/pentest", market),
                                "{\"status\":\"maybe\",\"evidence\":\"x\"}")
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose pass, fail or not applicable."));
        mvc.perform(json(post("/api/v1/console/go-live/{m}/gates/pentest", market), "{\"status\":\"pass\"}")
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Describe the evidence in 1 to 1,000 characters."));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/gates/pentest", market),
                                "{\"status\":\"pass\",\"evidence\":\"Report\",\"evidenceUrl\":\"http://insecure.test\"}")
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Give a link that starts with https:// (500 characters at most), or none."));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/gates/nope", market),
                                "{\"status\":\"pass\",\"evidence\":\"x\"}")
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a gate from the checklist."));
    }

    @Test
    void twoAdminsLaunch_theMarketOpens_aRollbackHidesItAgain_andItLaunchesAgain() throws Exception {
        // a business of the market, hidden before launch (S-120's pilot cause)
        var business = data.merchant("seller", "Golive Bakery");
        jdbc.sql(
                        "update merchants.merchants set province = 'AB', city = ?, search_hidden_at = now(), search_hidden_cause = 'pilot' where id = ?")
                .params(city, business)
                .update();
        recordAll("pass");
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.blocking", hasSize(0)));

        var id = request("{\"note\":\"Go/no-go meeting said go.\"}");
        assertThat(audited("golive.launch_requested")).isEqualTo(1);
        mvc.perform(json(post("/api/v1/console/go-live/{m}/launch-requests", market), "{}")
                        .with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("request_pending"));

        // the requester can't approve their own request
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/launch-requests/{id}/approve", market, id),
                                "{\"confirm\":\"%s\"}".formatted(city))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("same_person"));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/launch-requests/{id}/approve", market, id),
                                "{\"confirm\":\"elsewhere\"}")
                        .with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Type the market's name to confirm."));
        assertThat(stage()).isEqualTo("pilot");

        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/launch-requests/{id}/approve", market, id),
                                "{\"confirm\":\"%s\"}".formatted(city.toUpperCase(java.util.Locale.ROOT)))
                        .with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.stage").value("live"))
                .andExpect(jsonPath("$.request").doesNotExist())
                .andExpect(jsonPath("$.requests[0].state").value("approved"))
                .andExpect(jsonPath("$.requests[0].requestedBy.name").value("Avery Admin"))
                .andExpect(jsonPath("$.requests[0].decidedBy.name").value("Blake Admin"))
                .andExpect(jsonPath("$.events[0].kind").value("launched"));
        assertThat(stage()).isEqualTo("live");
        assertThat(regions.marketById(market).orElseThrow().status()).isEqualTo(LaunchStatus.LIVE);
        assertThat(audited("golive.launch_approved")).isEqualTo(1);
        assertThat(audited("region.stage_changed")).isEqualTo(2); // pilot from the switchboard, then live
        assertThat(hiddenCause(business)).isEmpty();
        // public discovery: the market is live, nothing to wait for
        mvc.perform(get("/api/v1/geo/markets"))
                .andExpect(jsonPath("$.items[?(@.code == 'AB')].markets[?(@.id == '%s')].stage".formatted(market))
                        .value("live"));

        // rollback: one admin, a reason, the market's name
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/rollback", market),
                                "{\"reason\":\"short\",\"confirm\":\"%s\"}".formatted(city))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Give the reason for the rollback, 10 to 500 characters."));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/rollback", market),
                                "{\"reason\":\"Checkout errors above the rollback threshold.\",\"confirm\":\"%s\"}"
                                        .formatted(city))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.stage").value("pilot"))
                .andExpect(jsonPath("$.events[0].kind").value("rolled_back"))
                .andExpect(jsonPath("$.events[0].reason").value("Checkout errors above the rollback threshold."));
        assertThat(stage()).isEqualTo("pilot");
        assertThat(hiddenCause(business)).isEqualTo("pilot");
        assertThat(audited("golive.rolled_back")).isEqualTo(1);
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/rollback", market),
                                "{\"reason\":\"Checkout errors above the rollback threshold.\",\"confirm\":\"%s\"}"
                                        .formatted(city))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_live"));

        // and again, the other way round
        var second = request("{}");
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/launch-requests/{id}/approve", market, second),
                                "{\"confirm\":\"%s\"}".formatted(city))
                        .with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.stage").value("live"));
        assertThat(hiddenCause(business)).isEmpty();
    }

    @Test
    void anEmergencyOverride_launchesWithFailingGates_withItsReasonAudited() throws Exception {
        var id = request(
                "{\"overrideReason\":\"Board decision: launch for the long weekend, pentest report pending.\"}");
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.request.override").value(true))
                .andExpect(jsonPath("$.request.blocking", hasItem("pentest")));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/launch-requests/{id}/approve", market, id),
                                "{\"confirm\":\"%s\"}".formatted(city))
                        .with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.stage").value("live"));
        assertThat(jdbc.sql("""
                        select (after ->> 'override')::boolean from developer.audit_log
                         where action = 'golive.launch_approved' and target_id = ?""").param(market).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aRequestIsWithdrawnByItsRequester_orRejectedByAnotherAdmin() throws Exception {
        recordAll("pass");
        var id = request("{}");
        mvc.perform(json(post("/api/v1/console/go-live/{m}/launch-requests/{id}/close", market, id), "{}")
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].state").value("withdrawn"));
        var again = request("{}");
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/launch-requests/{id}/close", market, again),
                                "{\"reason\":\"Wait for Monday.\"}")
                        .with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].state").value("rejected"))
                .andExpect(jsonPath("$.requests[0].decisionNote").value("Wait for Monday."));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/launch-requests/{id}/approve", market, again),
                                "{\"confirm\":\"%s\"}".formatted(city))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("request_closed"));
        // a lapsed request no longer counts; a new one can be asked
        var third = request("{}");
        jdbc.sql(
                        "update golive.launch_requests set requested_at = now() - interval '2 days', expires_at = now() - interval '1 day' where id = ?")
                .param(third)
                .update();
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/launch-requests/{id}/approve", market, third),
                                "{\"confirm\":\"%s\"}".formatted(city))
                        .with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("request_expired"));
        request("{}");
    }

    @Test
    void hypercare_isFourteenDaysOnTopOfTheOncallRota_onceLive() throws Exception {
        var body = "{\"primaries\":[\"%s\",\"%s\"],\"secondaries\":[\"%s\",\"%s\"],\"businessContacts\":[\"%s\"]}"
                .formatted(requester, approver, approver, requester, requester);
        mvc.perform(json(post("/api/v1/console/go-live/{m}/hypercare", market), body)
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_live"));
        jdbc.sql("update region.regions set stage = 'live' where id = ?")
                .param(market)
                .update();
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/hypercare", market),
                                "{\"primaries\":[\"%s\"],\"secondaries\":[\"%s\"],\"businessContacts\":[\"%s\"]}"
                                        .formatted(requester, requester, requester))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("The primary and the secondary must be two different people each day."));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/hypercare", market),
                                "{\"primaries\":[\"%s\"],\"secondaries\":[\"%s\"],\"businessContacts\":[\"%s\"]}"
                                        .formatted(requester, data.user("Not Staff"), requester))
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose someone on the Northline team."));
        mvc.perform(json(post("/api/v1/console/go-live/{m}/hypercare", market), body)
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hypercare.days", hasSize(14)))
                .andExpect(jsonPath("$.hypercare.days[0].primary.name").value("Avery Admin"))
                .andExpect(jsonPath("$.hypercare.days[0].secondary.name").value("Blake Admin"))
                .andExpect(jsonPath("$.hypercare.days[1].primary.name").value("Blake Admin"))
                .andExpect(jsonPath("$.hypercare.days[1].secondary.name").value("Avery Admin"));
        verify(rota, times(28)).add(anyString(), any(), any(), anyString(), anyString());
        verify(rota, atLeastOnce())
                .add(anyString(), any(), any(), org.mockito.ArgumentMatchers.contains(city), anyString());
        mvc.perform(json(post("/api/v1/console/go-live/{m}/hypercare", market), body)
                        .with(TestJwt.staff(requester, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("hypercare_exists"));
    }

    @Test
    void whoMayDoWhat() throws Exception {
        var someone = data.user("Sam");
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staff(someone, StaffRole.MERCHANT_SUCCESS)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staff(someone, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staff(someone, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get("/api/v1/console/go-live/{m}", market).with(TestJwt.staffWithoutMfa(someone, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/gates/pentest", market),
                                "{\"status\":\"pass\",\"evidence\":\"x\"}")
                        .with(TestJwt.staff(someone, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isForbidden());
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/gates/pentest", market),
                                "{\"status\":\"pass\",\"evidence\":\"x\"}")
                        .with(TestJwt.staff(someone, StaffRole.MERCHANT_SUCCESS)))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/console/go-live/{m}/launch-requests", market), "{}")
                        .with(TestJwt.staff(someone, StaffRole.FINANCE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(json(
                                post("/api/v1/console/go-live/{m}/rollback", market),
                                "{\"reason\":\"Because of reasons.\",\"confirm\":\"x\"}")
                        .with(TestJwt.staff(someone, StaffRole.MERCHANT_SUCCESS)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/go-live/{m}", "mkt-nowhere").with(TestJwt.staff(someone, StaffRole.ADMIN)))
                .andExpect(status().isNotFound());
    }
}
