package ca.northline.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.TestJwt;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-131 writing help end to end, with the scripted model: listing copy, quote lines, reply suggestions and review
 * summaries — always drafts (nothing saved or sent), marked AI-assisted, for the roles of the matching screen, reading
 * only the caller's business and only what the feature needs.
 */
class WritingHelpApiTest extends ScriptedModelTest {

    @Autowired
    JdbcClient jdbc;

    @Nested
    class ListingCopy {

        static final String DRAFT = """
                {"en":{"title":"Brake inspection","description":"A check of pads, rotors and fluid.","bullets":["Report"]},
                 "fr":{"title":"Inspection des freins","description":"Une vérification des plaquettes et du liquide.","bullets":["Rapport"]}}""";

        @Test
        void draftsBothLanguagesAndSavesNothing() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var before = listings(biz.merchantId());
            MODEL.enqueue(MockOpenRouter.answer(DRAFT));
            mvc.perform(post("/api/v1/merchants/{m}/listing-copy", biz.merchantId())
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"kind":"service","name":"Brake inspection","included":"Pads, rotors",
                                     "notes":"call me at 403-555-0199"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.en.title").value("Brake inspection"))
                    .andExpect(jsonPath("$.fr.title").value("Inspection des freins"))
                    .andExpect(jsonPath("$.aiAssisted").value(true))
                    .andExpect(jsonPath("$.prompt").value("listing-copy@v1"));
            assertThat(listings(biz.merchantId())).isEqualTo(before);
            var sent = MODEL.lastRequest();
            assertThat(sent.path("model").asString()).isEqualTo("google/gemini-3.7-flash");
            assertThat(sent.path("messages").path(1).path("content").asString())
                    .contains("Brake inspection")
                    .doesNotContain("403-555-0199");
        }

        @Test
        void rolesAndValidation() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var bookkeeper = data.user("Priya");
            data.member(biz.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
            mvc.perform(post("/api/v1/merchants/{m}/listing-copy", biz.merchantId())
                            .with(TestJwt.member(bookkeeper))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"service\",\"name\":\"x\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(post("/api/v1/merchants/{m}/listing-copy", biz.merchantId())
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"service\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Add a name or a category first."));
            mvc.perform(post("/api/v1/merchants/{m}/listing-copy", biz.merchantId())
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose service or product."));
            assertThat(MODEL.requests).isEmpty();
        }

        long listings(String merchantId) {
            return jdbc.sql("select (select count(*) from catalogue.services where merchant_id = :m)"
                            + " + (select count(*) from catalogue.offers where merchant_id = :m)")
                    .param("m", merchantId)
                    .query(Long.class)
                    .single();
        }
    }

    @Nested
    class QuoteLines {

        @Test
        void suggestsLinesWithoutPricesFromTheRequest() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var request = new OperationsFixtures(jdbc).quoteRequest(biz.merchantId(), data.user("Amara Osei"));
            MODEL.enqueue(MockOpenRouter.answer("""
                    {"lines":[{"kind":"labour","description":"Charging system diagnostic","qty":1},
                              {"kind":"part","description":"Alternator","qty":1},
                              {"kind":"discount","description":"Loyalty","qty":1}],"questions":["How old is the battery?"]}"""));
            mvc.perform(post("/api/v1/merchants/{m}/quote-requests/{r}/line-suggestions", biz.merchantId(), request)
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines.length()").value(2))
                    .andExpect(jsonPath("$.lines[0].kind").value("labour"))
                    .andExpect(jsonPath("$.lines[0].qty").value(1))
                    .andExpect(jsonPath("$.lines[1].unitCents").doesNotExist())
                    .andExpect(jsonPath("$.questions[0]").value("How old is the battery?"))
                    .andExpect(jsonPath("$.aiAssisted").value(true));
            assertThat(MODEL.lastRequest()
                            .path("messages")
                            .path(1)
                            .path("content")
                            .asString())
                    .contains("Alternator, 2016 Civic")
                    .doesNotContain("Amara");
        }

        @Test
        void anotherBusinesssRequestIsNotFound() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var other = data.business(MerchantRole.OWNER);
            var request = new OperationsFixtures(jdbc).quoteRequest(other.merchantId(), data.user("C"));
            mvc.perform(post("/api/v1/merchants/{m}/quote-requests/{r}/line-suggestions", biz.merchantId(), request)
                            .with(TestJwt.member(biz.userId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
            assertThat(MODEL.requests).isEmpty();
        }
    }

    @Nested
    class Replies {

        @Test
        void suggestsRepliesFromTheThreadAndSendsNothing() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var thread = thread(biz.merchantId());
            message(thread, "merchant", "See you Thursday at 9.", Instant.now().minusSeconds(3600));
            message(
                    thread,
                    "customer",
                    "Can I pay cash? My email is amara@example.com",
                    Instant.now().minusSeconds(60));
            var before = messages(thread);
            MODEL.enqueue(
                    MockOpenRouter.answer(
                            "{\"replies\":[\"Payments go through Northline.\",\"We can only take payment on Northline.\",\"Please pay on Northline.\"]}"));
            mvc.perform(post("/api/v1/merchants/{m}/threads/{t}/reply-suggestions", biz.merchantId(), thread)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replies.length()").value(3))
                    .andExpect(jsonPath("$.aiAssisted").value(true));
            assertThat(messages(thread)).isEqualTo(before);
            var sent =
                    MODEL.lastRequest().path("messages").path(1).path("content").asString();
            assertThat(sent).contains("Can I pay cash?").contains("[EMAIL]").doesNotContain("amara@example.com");
        }

        @Test
        void bookkeepersCantReplyAndAThreadEndingWithTheBusinessHasNothingToAnswer() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var bookkeeper = data.user("Priya");
            data.member(biz.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
            var thread = thread(biz.merchantId());
            message(thread, "merchant", "See you Thursday.", Instant.now());
            mvc.perform(post("/api/v1/merchants/{m}/threads/{t}/reply-suggestions", biz.merchantId(), thread)
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/merchants/{m}/threads/{t}/reply-suggestions", biz.merchantId(), thread)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("nothing_to_reply"));
            assertThat(MODEL.requests).isEmpty();
        }

        String thread(String merchantId) {
            var id = Ids.next();
            jdbc.sql("""
                            insert into messaging.threads (id, merchant_id, kind, ref_type, ref_id, ref_code, counterpart_id,
                                                           counterpart_name, subject, participant_ids, created_at)
                            values (?, ?, 'customer', 'booking', ?, 'BK-7712', ?, 'Amara Osei', 'brake inspection', '{}',
                                    now() - interval '1 day')
                            """).params(id, merchantId, Ids.next(), Ids.next()).update();
            return id;
        }

        void message(String threadId, String role, String body, Instant at) {
            jdbc.sql("""
                            insert into messaging.messages (id, thread_id, sender_id, sender_role, body, attachments, at, flagged)
                            values (?, ?, ?, ?, ?, '{}', ?, false)
                            """)
                    .params(Ids.next(), threadId, Ids.next(), role, body, at.atOffset(ZoneOffset.UTC))
                    .update();
        }

        long messages(String threadId) {
            return jdbc.sql("select count(*) from messaging.messages where thread_id = ?")
                    .param(threadId)
                    .query(Long.class)
                    .single();
        }
    }

    @Nested
    class ReviewSummary {

        @Test
        void summarizesWithoutAuthorNames() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            for (var i = 0; i < 3; i++) {
                jdbc.sql("""
                                insert into trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id,
                                                           rating, tags, text, job_label, lang, created_at)
                                values (?, 'booking', ?, ?, 'Dana K.', 'merchant', ?, 5, '{}', 'Explained everything clearly.',
                                        'alternator', 'en', now())
                                """)
                        .params(Ids.next(), Ids.next(), Ids.next(), biz.merchantId())
                        .update();
            }
            MODEL.enqueue(MockOpenRouter.answer("""
                    {"en":{"summary":"Customers praise clear explanations.","themes":["clear explanations"]},
                     "fr":{"summary":"Les clients apprécient les explications claires.","themes":["explications claires"]}}"""));
            mvc.perform(post("/api/v1/merchants/{m}/reviews/summary-draft", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.en.summary").value("Customers praise clear explanations."))
                    .andExpect(jsonPath("$.fr.themes[0]").value("explications claires"))
                    .andExpect(jsonPath("$.reviews").value(3))
                    .andExpect(jsonPath("$.aiAssisted").value(true));
            assertThat(MODEL.lastRequest()
                            .path("messages")
                            .path(1)
                            .path("content")
                            .asString())
                    .contains("Explained everything clearly.")
                    .doesNotContain("Dana");
        }

        @Test
        void needsThreeReviews() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(post("/api/v1/merchants/{m}/reviews/summary-draft", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("too_few_reviews"));
        }
    }
}
