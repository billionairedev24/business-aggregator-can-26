package ca.northline.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.trust.api.QualityQuery;
import ca.northline.trust.api.RatingQuery;
import ca.northline.trust.api.ReviewReplied;
import ca.northline.trust.api.ReviewReported;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Studio › Reviews ({@code /reviews}), the quality score ({@code /quality}), the {@code reviews} badge, the trigger. */
@RecordApplicationEvents
class ReviewsApiTest extends IntegrationTest {

    static final String REVIEWS = "/api/v1/merchants/{m}/reviews";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    List<NavBadgeContributor> badgeContributors;

    @Autowired
    QualityQuery qualityQuery;

    @Autowired
    RatingQuery ratingQuery;

    String review(String merchantId, int rating, String author, String job, String text, String tags, int daysAgo) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id,
                                                   rating, tags, text, job_label, lang, created_at)
                        values (?, 'booking', ?, ?, ?, 'merchant', ?, ?, cast(? as text[]), ?, ?, 'en',
                                now() - make_interval(days => ?))
                        """)
                .params(id, Ids.next(), Ids.next(), author, merchantId, rating, tags, text, job, daysAgo)
                .update();
        return id;
    }

    /** Dana 5★ (3 d), Tran 4★ (7 d), Bouchard 5★ (14 d), and a 2★ a month back: average 4.0. */
    record Fixture(String merchantId, String owner, String dana, String tran, String bouchard) {}

    Fixture fixture() {
        var biz = data.business(MerchantRole.OWNER);
        var dana = review(
                biz.merchantId(),
                5,
                "Dana K.",
                "alternator",
                "Showed up at 7 am in −22°, fixed the alternator in the parkade.",
                "{on_time,clear_explanation,fair_price}",
                3);
        var tran = review(
                biz.merchantId(),
                4,
                "M. Tran",
                "diagnostic",
                "Thorough, but arrived 20 minutes late.",
                "{clear_explanation}",
                7);
        var bouchard = review(
                biz.merchantId(),
                5,
                "S. Bouchard",
                "oil & filter",
                "Fair price, parts at cost as promised.",
                "{fair_price,on_time}",
                14);
        review(biz.merchantId(), 2, "Kevin L.", "battery swap", null, "{}", 30);
        return new Fixture(biz.merchantId(), biz.userId(), dana, tran, bouchard);
    }

    static MockHttpServletRequestBuilder postJson(String path, String body, Object... vars) {
        return post(path, vars).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    Map<String, String> badges(String merchantId, String userId, Locale locale) {
        var all = new HashMap<String, String>();
        badgeContributors.forEach(c ->
                all.putAll(c.badges(new NavBadgeContributor.Context(merchantId, userId, MerchantRole.OWNER, locale))));
        return all;
    }

    @Nested
    class Browse {

        @Test
        void summaryHasAverageDistributionAndPraise() throws Exception {
            var f = fixture();
            review(f.merchantId(), 5, "Grace H.", "oil & filter", null, "{on_time}", 40);
            mvc.perform(get(REVIEWS + "/summary", f.merchantId()).with(TestJwt.member(f.owner())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.average").value(4.2))
                    .andExpect(jsonPath("$.count").value(5))
                    .andExpect(jsonPath("$.distribution[*].stars", contains(5, 4, 3, 2, 1)))
                    .andExpect(jsonPath("$.distribution[*].count", contains(3, 1, 0, 1, 0)))
                    .andExpect(jsonPath("$.distribution[0].percent").value(60))
                    .andExpect(jsonPath("$.praise[*].tag", contains("on_time", "clear_explanation", "fair_price")))
                    .andExpect(jsonPath("$.praise[0].percent").value(60));
            assertThat(ratingQuery.summary(f.merchantId())).isEqualTo(new RatingQuery.RatingSummary(4.2, 5));
            assertThat(badges(f.merchantId(), f.owner(), Locale.CANADA)).containsEntry("reviews", "4.2");
            assertThat(badges(f.merchantId(), f.owner(), Locale.CANADA_FRENCH)).containsEntry("reviews", "4,2");
        }

        @Test
        void newBusinessHasNoRatingAndNoBadge() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(get(REVIEWS + "/summary", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.count").value(0))
                    .andExpect(jsonPath("$.average").value(0.0));
            assertThat(badges(biz.merchantId(), biz.userId(), Locale.CANADA)).doesNotContainKey("reviews");
        }

        @Test
        void reviewsNewestFirst_inPages() throws Exception {
            var f = fixture();
            mvc.perform(get(REVIEWS, f.merchantId()).param("limit", "3").with(TestJwt.member(f.owner())))
                    .andExpect(jsonPath("$.items[*].authorName", contains("Dana K.", "M. Tran", "S. Bouchard")))
                    .andExpect(jsonPath("$.items[0].jobLabel").value("alternator"))
                    .andExpect(jsonPath("$.items[0].refType").value("booking"))
                    .andExpect(jsonPath("$.items[0].rating").value(5))
                    .andExpect(jsonPath("$.nextOffset").value(3));
            mvc.perform(get(REVIEWS, f.merchantId())
                            .param("limit", "3")
                            .param("offset", "3")
                            .with(TestJwt.member(f.owner())))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.nextOffset").doesNotExist());
        }

        @Test
        void bookkeeperReads_strangerIsForbidden_singleFactorIsForbidden() throws Exception {
            var f = fixture();
            var bookkeeper = data.user("Priya");
            data.member(f.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
            mvc.perform(get(REVIEWS, f.merchantId()).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isOk());
            mvc.perform(get(REVIEWS, f.merchantId()).with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden());
            mvc.perform(get(REVIEWS, f.merchantId()).with(TestJwt.memberWithoutMfa(f.owner())))
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }
    }

    @Nested
    class Reply {

        @Test
        void ownerRepliesOnce_publicly() throws Exception {
            var f = fixture();
            mvc.perform(postJson(
                                    REVIEWS + "/{id}/reply",
                                    "{\"text\":\"  Thanks — see you in spring.  \"}",
                                    f.merchantId(),
                                    f.tran())
                            .with(TestJwt.member(f.owner())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reply").value("Thanks — see you in spring."))
                    .andExpect(jsonPath("$.replyAt").isNotEmpty());
            assertThat(events.stream(ReviewReplied.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.aggregateId()).isEqualTo(f.tran()));
            mvc.perform(postJson(REVIEWS + "/{id}/reply", "{\"text\":\"Edited\"}", f.merchantId(), f.tran())
                            .with(TestJwt.member(f.owner())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("review_already_replied"));
        }

        @Test
        void technicianMayReply_bookkeeperMayNot() throws Exception {
            var f = fixture();
            var tech = data.user("Jas");
            data.member(f.merchantId(), tech, MerchantRole.TECHNICIAN);
            var bookkeeper = data.user("Priya");
            data.member(f.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
            mvc.perform(postJson(REVIEWS + "/{id}/reply", "{\"text\":\"Thanks!\"}", f.merchantId(), f.bouchard())
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden());
            mvc.perform(postJson(REVIEWS + "/{id}/reply", "{\"text\":\"Thanks!\"}", f.merchantId(), f.bouchard())
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isOk());
        }

        @Test
        void anotherBusinessesReviewIsNotFound() throws Exception {
            var f = fixture();
            var other = data.business(MerchantRole.OWNER);
            mvc.perform(postJson(REVIEWS + "/{id}/reply", "{\"text\":\"Hi\"}", other.merchantId(), f.tran())
                            .with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "''     | required | Write a reply before sending.",
                    "'   '  | required | Write a reply before sending.",
                })
        void emptyReplyIs422(String text, String rule, String message) throws Exception {
            var f = fixture();
            mvc.perform(postJson(REVIEWS + "/{id}/reply", "{\"text\":\"%s\"}".formatted(text), f.merchantId(), f.tran())
                            .with(TestJwt.member(f.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("text"))
                    .andExpect(jsonPath("$.errors[0].rule").value(rule))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void tooLongReplyIs422() throws Exception {
            var f = fixture();
            mvc.perform(postJson(
                                    REVIEWS + "/{id}/reply",
                                    "{\"text\":\"%s\"}".formatted("x".repeat(1001)),
                                    f.merchantId(),
                                    f.tran())
                            .with(TestJwt.member(f.owner())))
                    .andExpect(jsonPath("$.errors[0].rule").value("length"))
                    .andExpect(jsonPath("$.errors[0].message").value("Keep replies under 1,000 characters."));
        }
    }

    @Nested
    class Report {

        @Test
        void reportRaisesAFlagForTrustAndSafety_once() throws Exception {
            var f = fixture();
            mvc.perform(postJson(REVIEWS + "/{id}/report", "{\"reason\":\"fake\"}", f.merchantId(), f.tran())
                            .with(TestJwt.member(f.owner())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reportReason").value("fake"))
                    .andExpect(jsonPath("$.reportedAt").isNotEmpty());
            assertThat(jdbc.sql("""
                            select count(*) from trust.flags
                             where target_type = 'review' and target_id = ? and rule = 'review_report' and state = 'open'
                            """).params(f.tran()).query(Integer.class).single())
                    .isEqualTo(1);
            assertThat(events.stream(ReviewReported.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.reason()).isEqualTo("fake"));
            mvc.perform(postJson(REVIEWS + "/{id}/report", "{\"reason\":\"offensive\"}", f.merchantId(), f.tran())
                            .with(TestJwt.member(f.owner())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("review_already_reported"));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "{\"reason\":\"\"}                  | reason | required | Choose a reason.",
                    "{\"reason\":\"meh\"}               | reason | format   | Choose a reason.",
                    "{\"reason\":\"other\"}             | note   | required | Tell us what's wrong with this review.",
                    "{\"reason\":\"other\",\"note\":\" \"} | note | required | Tell us what's wrong with this review.",
                })
        void invalidReportIs422(String body, String field, String rule, String message) throws Exception {
            var f = fixture();
            mvc.perform(postJson(REVIEWS + "/{id}/report", body, f.merchantId(), f.dana())
                            .with(TestJwt.member(f.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].rule").value(rule))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void tooLongNoteIs422() throws Exception {
            var f = fixture();
            mvc.perform(postJson(
                                    REVIEWS + "/{id}/report",
                                    "{\"reason\":\"other\",\"note\":\"%s\"}".formatted("x".repeat(501)),
                                    f.merchantId(),
                                    f.dana())
                            .with(TestJwt.member(f.owner())))
                    .andExpect(jsonPath("$.errors[0].field").value("note"))
                    .andExpect(jsonPath("$.errors[0].message").value("Keep the note under 500 characters."));
        }
    }

    /** V073 {@code trust.reviews_immutable}: a verified review's content never changes; the reply is written once. */
    @Nested
    class ImmutableTrigger {

        @Test
        void ratingAndTextCannotBeEdited() {
            var f = fixture();
            assertThatThrownBy(() -> jdbc.sql("update trust.reviews set rating = 1 where id = ?")
                            .params(f.dana())
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("is verified and cannot be edited");
            assertThatThrownBy(() -> jdbc.sql("update trust.reviews set text = 'nicer' where id = ?")
                            .params(f.dana())
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void replyIsWrittenOnce_reportColumnsStayWritable() {
            var f = fixture();
            jdbc.sql("update trust.reviews set reply = 'Thanks', reply_at = now() where id = ?")
                    .params(f.dana())
                    .update();
            assertThatThrownBy(() -> jdbc.sql("update trust.reviews set reply = 'Edited' where id = ?")
                            .params(f.dana())
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("already has a reply");
            jdbc.sql("update trust.reviews set reported_at = now(), report_reason = 'fake' where id = ?")
                    .params(f.dana())
                    .update();
        }

        @Test
        void reviewsNeedACompletedTransaction() {
            var biz = data.business(MerchantRole.OWNER);
            assertThatThrownBy(() -> jdbc.sql("""
                            insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating)
                            values (?, 'quote', ?, ?, 'merchant', ?, 5)
                            """)
                            .params(Ids.next(), Ids.next(), Ids.next(), biz.merchantId())
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    class Quality {

        @Test
        void latestScoreWithComponentsInDashboardOrder() throws Exception {
            var biz = data.business(MerchantRole.TECHNICIAN);
            jdbc.sql("""
                            insert into trust.quality_scores (merchant_id, date, score, components) values
                              (?, current_date - 2, 88, '{"on_time":{"value":97,"floor":95}}'),
                              (?, current_date - 1, 91, cast(? as jsonb))
                            """).params(biz.merchantId(), biz.merchantId(), """
                            {"disputes":{"value":0.3,"floor":1},"rebook":{"value":71,"floor":40},"on_time":{"value":98,"floor":95},
                             "photos":{"value":85,"floor":90},"response":{"value":94,"floor":90}}""").update();
            mvc.perform(get("/api/v1/merchants/{m}/quality", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.score").value(91))
                    .andExpect(jsonPath(
                            "$.components[*].key", contains("on_time", "photos", "response", "rebook", "disputes")))
                    .andExpect(jsonPath("$.components[1].bar").value(85))
                    .andExpect(jsonPath("$.components[1].barFloor").value(90))
                    .andExpect(jsonPath("$.components[4].value").value(0.3))
                    .andExpect(jsonPath("$.components[4].bar").value(97))
                    .andExpect(jsonPath("$.components[4].barFloor").value(90))
                    .andExpect(jsonPath("$.components[4].inverted").value(true));
            assertThat(qualityQuery.latest(biz.merchantId()))
                    .get()
                    .satisfies(q -> assertThat(q.score()).isEqualTo(91));
        }

        @Test
        void noScoreYetIs404() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(get("/api/v1/merchants/{m}/quality", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNotFound());
        }
    }
}
