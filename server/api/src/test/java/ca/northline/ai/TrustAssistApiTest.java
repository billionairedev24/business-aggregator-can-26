package ca.northline.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestJwt;
import ca.northline.trust.application.ScanAnomalies;
import ca.northline.trust.application.ScreenTrustContent;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * S-133 end to end with the scripted model: the screening job reads listings, reviews and messages since its marks and
 * queues what the model flags (nothing else changes), the weekly anomaly scan explains the businesses the rules pick,
 * and staff decide each flag in the console queue. Test data lives in 2100 so the marks and weeks see nothing else.
 */
class TrustAssistApiTest extends ScriptedModelTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ScreenTrustContent screening;

    @Autowired
    ScanAnomalies anomalies;

    @Nested
    class Screening {

        @Test
        void screensWhatIsNewAndQueuesOnlyWhatTheModelFlags() {
            marks("2100-01-01T00:00:00Z");
            var biz = data.business(MerchantRole.OWNER);
            var listing = pendingService(biz.merchantId(), "Full brake job", 1200L, "2100-01-01T00:01:00Z");
            var review = review(biz.merchantId(), 5, "Explained everything clearly.", "2100-01-01T00:02:00Z");
            var thread = thread(biz.merchantId());
            var message = message(
                    thread,
                    "merchant",
                    "Pay me by e-transfer at 403-555-0199 and skip the app fee",
                    "2100-01-01T00:03:00Z");
            MODEL.responder = req -> {
                var input = lastUser(req);
                if (input.contains("e-transfer")) {
                    return MockOpenRouter.answer("""
                            {"flag":true,"categories":["off_platform_payment"],
                             "explanation":"The business asks to be paid by e-transfer to skip the platform fee."}""");
                }
                if (input.contains("brake job")) {
                    return MockOpenRouter.answer("""
                            {"flag":true,"categories":["misleading","made_up"],"explanation":""}""");
                }
                return MockOpenRouter.answer("{\"flag\":false,\"categories\":[],\"explanation\":\"Fine.\"}");
            };

            var result = screening.screenNew();

            assertThat(result.screened()).isEqualTo(3);
            assertThat(result.flagged()).isEqualTo(2);
            assertThat(result.deferred()).isEmpty();
            var listingFlag = flag("listing", listing);
            assertThat(listingFlag.path("source").asString()).isEqualTo("ai");
            assertThat(listingFlag.path("categories").asString()).isEqualTo("misleading,other");
            // every flag explains itself, even when the model forgot to
            assertThat(listingFlag.path("explanation").asString()).isEqualTo("Flagged for misleading, other.");
            assertThat(listingFlag.path("prompt").asString()).isEqualTo("trust-screen@v1");
            assertThat(flag("message", message).path("explanation").asString()).contains("e-transfer");
            assertThat(count("select count(*) from trust.flags where target_id = ? and rule = 'ai_screen'", review))
                    .isZero();
            // a suggestion only: the listing is still waiting for vetting, the message is still there
            assertThat(jdbc.sql("select vetting from catalogue.services where id = ?")
                            .param(listing)
                            .query(String.class)
                            .single())
                    .isEqualTo("pending");
            assertThat(count("select count(*) from messaging.messages where id = ?", message))
                    .isOne();
            // the minimum: the item's words, no names, ids or contact details
            var listingRequest = MODEL.requests.stream()
                    .map(TrustAssistApiTest::lastUser)
                    .filter(s -> s.contains("brake job"))
                    .findFirst()
                    .orElseThrow();
            assertThat(listingRequest)
                    .contains("a service listing submitted for vetting", "price $12.00", "Full brake job");
            for (var sent : MODEL.requests) {
                var text = sent.toString();
                assertThat(text)
                        .doesNotContain("403-555-0199")
                        .doesNotContain(biz.merchantId())
                        .doesNotContain(thread)
                        .doesNotContain("Amara");
                assertThat(sent.path("model").asString()).isEqualTo("google/gemini-3.5-flash-lite");
            }
            assertThat(count(
                            "select count(*) from ai.usage where feature = ? and person_id = 'system:trust-screening'"
                                    + " and created_at > now() - interval '1 minute'",
                            "trust_screen"))
                    .isGreaterThanOrEqualTo(3);
            assertThat(count(
                            "select count(*) from trust.ai_screenings where target_id = ? and flagged = false and explanation is null",
                            review))
                    .isOne();

            MODEL.reset();
            assertThat(screening.screenNew().screened()).isZero();
            assertThat(MODEL.requests).isEmpty();
        }

        @Test
        void whenTheModelIsUnavailableTheJobPausesWithoutSkippingAnything() {
            marks("2100-02-01T00:00:00Z");
            var biz = data.business(MerchantRole.OWNER);
            var review = review(biz.merchantId(), 1, "Never showed up and never answered.", "2100-02-01T00:01:00Z");
            MODEL.responder = req -> MockOpenRouter.status(503);

            var paused = screening.screenNew();

            assertThat(paused.screened()).isZero();
            assertThat(paused.deferred()).contains("review");
            MODEL.reset();
            MODEL.responder =
                    req -> MockOpenRouter.answer("{\"flag\":false,\"categories\":[],\"explanation\":\"Honest.\"}");
            assertThat(screening.screenNew().screened()).isOne();
            assertThat(count("select count(*) from trust.ai_screenings where target_id = ?", review))
                    .isOne();
        }
    }

    @Nested
    class AnomalyScan {

        @Test
        void theRulesPickTheBusinessAndTheModelExplainsItToStaff() {
            var week = LocalDate.of(2100, 3, 1);
            var biz = data.business(MerchantRole.OWNER);
            for (var i = 0; i < 16; i++) {
                review(biz.merchantId(), 4, "Good.", "2100-01-" + (10 + i) + "T12:00:00Z");
            }
            for (var i = 0; i < 14; i++) {
                review(biz.merchantId(), 5, "Great!", "2100-03-0" + (1 + i % 7) + "T1" + (i % 10) + ":00:00Z");
            }
            MODEL.enqueue(MockOpenRouter.answer("""
                    {"businesses":[{"ref":"A","explanation":"14 reviews this week against about 2 a week before."}],
                     "summary":"One review burst."}"""));

            var scans = anomalies.scan(week.plusDays(3));

            assertThat(scans).hasSize(1);
            assertThat(scans.getFirst().weekStart()).isEqualTo(week);
            assertThat(scans.getFirst().flagsRaised()).isOne();
            assertThat(scans.getFirst().aiExplained()).isTrue();
            var evidence = flag("merchant", biz.merchantId(), "anomaly_2100-03-01");
            assertThat(evidence.path("signals").asString()).isEqualTo("review_burst");
            assertThat(evidence.path("explanation").asString())
                    .isEqualTo("14 reviews this week against about 2 a week before.");
            assertThat(evidence.path("source").asString()).isEqualTo("ai");
            var sent = MODEL.lastRequest();
            assertThat(sent.path("model").asString()).isEqualTo("google/gemini-3.7-flash");
            assertThat(lastUser(sent))
                    .contains("A: signals review_burst; reviews this week 14 (previous 8 weeks: 16")
                    .doesNotContain(biz.merchantId());
            assertThat(sent.toString()).doesNotContain("Great!");
            // once per market and week
            assertThat(anomalies.scan(week)).isEmpty();
        }

        @Test
        void withoutTheModelTheRulesExplainTheFlag() {
            var week = LocalDate.of(2100, 3, 8);
            var biz = data.business(MerchantRole.OWNER);
            for (var i = 0; i < 3; i++) {
                jdbc.sql("""
                                insert into trust.flags (id, target_type, target_id, rule, evidence, state, actor_id,
                                                         merchant_id, created_at)
                                values (?, 'message', ?, 'off_platform_payment', '{}', 'open', 'x', ?, '2100-03-09T10:00:00Z')
                                """).params(Ids.next(), Ids.next(), biz.merchantId()).update();
            }
            MODEL.enqueue(MockOpenRouter.status(503));

            var scans = anomalies.scan(week);

            assertThat(scans)
                    .singleElement()
                    .satisfies(s -> assertThat(s.aiExplained()).isFalse());
            var evidence = flag("merchant", biz.merchantId(), "anomaly_2100-03-08");
            assertThat(evidence.path("source").asString()).isEqualTo("rules");
            assertThat(evidence.path("explanation").asString())
                    .startsWith("Stands out this week: repeated off-platform payment flags — ")
                    .contains("off-platform payment flags 3");
        }
    }

    @Nested
    class ConsoleQueue {

        @Test
        void staffSeeEveryFlagExplainedAndDecideIt() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var aiFlag = Ids.next();
            jdbc.sql("""
                            insert into trust.flags (id, target_type, target_id, rule, evidence, state, actor_id, merchant_id)
                            values (?, 'review', ?, 'ai_screen',
                                    '{"source":"ai","categories":"abuse","explanation":"Insults the owner.","model":"m"}',
                                    'open', 'system:trust-screening', ?)
                            """).params(aiFlag, Ids.next(), biz.merchantId()).update();
            var ruleFlag = Ids.next();
            jdbc.sql("""
                            insert into trust.flags (id, target_type, target_id, rule, evidence, state, actor_id, merchant_id)
                            values (?, 'message', ?, 'off_platform_payment', '{"threadId":"t"}', 'open', 'u', ?)
                            """).params(ruleFlag, Ids.next(), biz.merchantId()).update();
            var staff = data.user("Sam");

            mvc.perform(get("/api/v1/console/trust/flags?source=ai&limit=200").with(TestJwt.staff(staff)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].explanation", aiFlag)
                            .value("Insults the owner."))
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].categories[0]", aiFlag)
                            .value("abuse"))
                    .andExpect(jsonPath("$.items[?(@.id == '%s')]", ruleFlag).isEmpty());
            mvc.perform(get("/api/v1/console/trust/flags?source=rules&limit=200")
                            .with(TestJwt.staff(staff)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].source", ruleFlag)
                            .value("rules"))
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].explanation", ruleFlag)
                            .value("The message detector found masked contact details or words about paying outside"
                                    + " Northline."));

            mvc.perform(post("/api/v1/console/trust/flags/{id}/decision", aiFlag)
                            .with(TestJwt.staff(staff))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"dismissed\",\"note\":\"Harsh but about the work.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("dismissed"))
                    .andExpect(jsonPath("$.decidedBy").value(staff))
                    .andExpect(jsonPath("$.decisionNote").value("Harsh but about the work."));
            mvc.perform(post("/api/v1/console/trust/flags/{id}/decision", aiFlag)
                            .with(TestJwt.staff(staff))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"actioned\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("flag_decided"));
            mvc.perform(post("/api/v1/console/trust/flags/{id}/decision", ruleFlag)
                            .with(TestJwt.staff(staff))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"delete_business\"}"))
                    .andExpect(status().isUnprocessableContent());
            mvc.perform(get("/api/v1/console/trust/flags?state=dismissed&limit=200")
                            .with(TestJwt.staff(staff)))
                    .andExpect(
                            jsonPath("$.items[?(@.id == '%s')].state", aiFlag).value("dismissed"));
            assertThat(count(
                            "select count(*) from developer.audit_log where action = 'trust.flag_decided' and target_id = ?",
                            aiFlag))
                    .isOne();
        }

        @Test
        void onlyStaffWithASecondFactor() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(get("/api/v1/console/trust/flags").with(TestJwt.member(biz.userId())))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/trust/flags").with(TestJwt.staffWithoutMfa(data.user("Sam"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }
    }

    // --- fixtures (all in 2100) ---

    static String lastUser(JsonNode req) {
        var messages = req.path("messages");
        return messages.path(messages.size() - 1).path("content").asString("");
    }

    void marks(String at) {
        for (var source : new String[] {"listing", "review", "message"}) {
            jdbc.sql("""
                            insert into trust.ai_screening_marks (source, after_at, after_id) values (?, ?, '')
                            on conflict (source) do update set after_at = excluded.after_at, after_id = ''
                            """).params(source, OffsetDateTime.parse(at)).update();
        }
    }

    String pendingService(String merchantId, String name, long priceCents, String submittedAt) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into catalogue.services (id, merchant_id, category_id, name, name_i18n, included,
                               pricing_mode, price_cents, duration_min, buffer_min, instant_book, vetting, status,
                               submitted_at)
                        values (?, ?, ?, ?, jsonb_build_object('en', ?::text), 'Final price confirmed on site.', 'fixed',
                                ?, 60, 0, false, 'pending', 'hidden', ?)
                        """)
                .params(id, merchantId, null, name, name, priceCents, OffsetDateTime.parse(submittedAt))
                .update();
        return id;
    }

    String review(String merchantId, int rating, String text, String at) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id,
                                                   rating, tags, text, job_label, lang, created_at)
                        values (?, 'booking', ?, ?, 'Amara O.', 'merchant', ?, ?, '{}', ?, 'brakes', 'en', ?)
                        """)
                .params(id, Ids.next(), Ids.next(), merchantId, rating, text, OffsetDateTime.parse(at))
                .update();
        return id;
    }

    String thread(String merchantId) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into messaging.threads (id, merchant_id, kind, ref_type, ref_id, ref_code, counterpart_id,
                                                       counterpart_name, subject, participant_ids, created_at)
                        values (?, ?, 'customer', 'booking', ?, 'BK-7712', ?, 'Amara Osei', 'brake inspection', '{}', now())
                        """).params(id, merchantId, Ids.next(), Ids.next()).update();
        return id;
    }

    String message(String threadId, String role, String body, String at) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into messaging.messages (id, thread_id, sender_id, sender_role, sender_name, body,
                                                        attachments, at, flagged)
                        values (?, ?, ?, ?, 'Amara', ?, '{}', ?, true)
                        """)
                .params(id, threadId, Ids.next(), role, body, OffsetDateTime.parse(at))
                .update();
        return id;
    }

    JsonNode flag(String targetType, String targetId) {
        return flag(targetType, targetId, "ai_screen");
    }

    JsonNode flag(String targetType, String targetId, String rule) {
        var raw = jdbc.sql(
                        "select evidence::text from trust.flags where target_type = ? and target_id = ? and rule = ?")
                .params(targetType, targetId, rule)
                .query(String.class)
                .single();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw);
    }

    long count(String sql, String param) {
        return jdbc.sql(sql).param(param).query(Long.class).single();
    }
}
