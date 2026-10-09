package ca.northline.hire;

import static ca.northline.hire.BookingFlow.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.privacy.PersonalDataContributor;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import ca.northline.trust.api.RatingQuery;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mobile gaps part 2: customers review what they booked — once per business, within 30 days of completion; the stars,
 * tags and words can change for 24 hours (not after the business replies; the DB trigger enforces it too); words are
 * screened (personal information, profanity → masked + a flag); trust &amp; safety hide a review from the rating and
 * the public page; erasure keeps the stars. Messages in English and French.
 */
@Import(MovableClock.Config.class)
class ReviewPostingApiTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    RatingQuery ratings;

    @Autowired
    List<PersonalDataContributor> contributors;

    @Autowired
    TransactionTemplate tx;

    BookingFlow flow;
    String customer;

    @BeforeEach
    void setUp() {
        clock.reset();
        new CategorySeeder(dataSource).seed();
        flow = new BookingFlow(mvc, new HireFixtures(jdbc), "Review Wrench");
        customer = data.user("Dana Kowalski");
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    ResultActions post(String who, String body) throws Exception {
        return mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/me/reviews")
                        .with(TestJwt.customer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    static String review(String bookingId, int stars, String text) {
        return """
                {"kind":"booking","id":"%s","rating":%d,"tags":["on_time","fair_price"],"text":"%s"}""".formatted(bookingId, stars, text);
    }

    @Test
    void aCompletedBooking_isReviewedOnce_andCountsInTheRating() throws Exception {
        var booked = flow.book(customer, tomorrowAt(9), Map.of());
        mvc.perform(get("/api/v1/me/reviews/booking/{id}", booked).with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[0].status").value("not_yet"));
        post(customer, review(booked, 5, "Explained everything, fair price."))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_reviewable"));

        flow.job(booked, "en-route", Map.of());
        flow.job(booked, "on-site", Map.of());
        flow.job(booked, "complete", Map.of("report", "Pads 4 mm."));
        mvc.perform(get("/api/v1/me/reviews/booking/{id}", booked).with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.targets[0].status").value("open"))
                .andExpect(jsonPath("$.targets[0].merchantId").value(flow.provider.merchantId()))
                .andExpect(jsonPath("$.targets[0].jobLabel").value("Brake inspection"))
                .andExpect(jsonPath("$.reviewBy").isNotEmpty());

        var body = post(customer, review(booked, 4, "Explained everything, fair price."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.screened").value(false))
                .andExpect(jsonPath("$.editUntil").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String reviewId = JsonPath.read(body, "$.id");
        assertThat(ratings.summary(flow.provider.merchantId())).isEqualTo(new RatingQuery.RatingSummary(4.0, 1));
        assertThat(jdbc.sql("select author_name from trust.reviews where id = ?")
                        .params(reviewId)
                        .query(String.class)
                        .single())
                .isEqualTo("D. Kowalski"); // the display form every module uses (PersonDirectory.shortName)
        mvc.perform(get("/api/v1/public/providers/{slug}/reviews", flow.provider.slug()))
                .andExpect(jsonPath("$.items[0].id").value(reviewId));

        // once
        post(customer, review(booked, 5, "Second thoughts on this one."))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_reviewed"));
        // someone else can't review it
        post(data.user("Nosy"), review(booked, 1, "Never booked them at all.")).andExpect(status().isNotFound());

        // the author changes it inside the 24 hours
        mvc.perform(patch("/api/v1/me/reviews/{id}", reviewId)
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"tags\":[\"on_time\"],\"text\":\"Even better on reflection.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.editedAt").isNotEmpty());
        assertThat(ratings.summary(flow.provider.merchantId()).average()).isEqualTo(5.0);
        // not someone else's
        mvc.perform(patch("/api/v1/me/reviews/{id}", reviewId)
                        .with(TestJwt.customer(data.user("Other")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isNotFound());

        // after the 24 hours it is locked — by the service and by the trigger
        clock.advance(Duration.ofHours(25));
        mvc.perform(patch("/api/v1/me/reviews/{id}", reviewId)
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review_locked"));
        // the window itself can't be reopened
        assertThatThrownBy(() -> jdbc.sql("update trust.reviews set edit_until = now() + interval '1 day' where id = ?")
                        .params(reviewId)
                        .update())
                .hasMessageContaining("cannot be edited");
        // and a review whose window has passed (by the database's clock) keeps its stars
        var old = ca.northline.shared.Ids.next();
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating, tags,
                               lang, created_at, edit_until)
                        values (?, 'booking', ?, ?, 'merchant', ?, 4, '{}', 'en', now() - interval '2 days',
                                now() - interval '1 day')""")
                .params(old, ca.northline.shared.Ids.next(), customer, flow.provider.merchantId())
                .update();
        assertThatThrownBy(() -> jdbc.sql("update trust.reviews set rating = 1 where id = ?")
                        .params(old)
                        .update())
                .hasMessageContaining("cannot be edited");
    }

    @Test
    void theBusinessReplyLocksTheReview_andTheTriggerAllowsOnlyTheAuthorsWindow() throws Exception {
        var booked = flow.completed(customer, tomorrowAt(11));
        var body = post(customer, review(booked, 3, "Okay job, a bit late."))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String reviewId = JsonPath.read(body, "$.id");
        // inside the window the row may change
        jdbc.sql("update trust.reviews set tags = '{friendly}' where id = ?")
                .params(reviewId)
                .update();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/merchants/{m}/reviews/{id}/reply", flow.provider.merchantId(), reviewId)
                        .with(TestJwt.member(flow.provider.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Sorry we were late — thanks for the patience.\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/me/reviews/{id}", reviewId)
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review_locked"));
        assertThatThrownBy(() -> jdbc.sql("update trust.reviews set text = 'changed after the reply' where id = ?")
                        .params(reviewId)
                        .update())
                .hasMessageContaining("cannot be edited");
    }

    @Test
    void validationMessages_inEnglishAndFrench() throws Exception {
        var booked = flow.completed(customer, tomorrowAt(13));
        post(customer, """
                        {"kind":"booking","id":"%s","rating":6,"tags":["best_ever"],"text":"short"}""".formatted(booked))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'rating')].message").value("Choose from 1 to 5 stars."))
                .andExpect(
                        jsonPath("$.errors[?(@.field == 'tags')].message").value("Choose up to 5 of the tags offered."))
                .andExpect(jsonPath("$.errors[?(@.field == 'text')].message")
                        .value("Write at least 10 characters, or leave the review empty."));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/me/reviews")
                        .with(TestJwt.customer(customer))
                        .header("Accept-Language", "fr-CA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"booking","id":"%s","text":"%s"}""".formatted(booked, "x".repeat(1001))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'rating')].message").value("Choisissez de 1 à 5 étoiles."));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/me/reviews")
                        .with(TestJwt.customer(customer))
                        .header("Accept-Language", "fr-CA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"booking","id":"%s","rating":4,"text":"%s"}""".formatted(booked, "x".repeat(1001))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Votre avis doit faire moins de 1 000 caractères."));
        post(customer, "{\"kind\":\"parcel\",\"id\":\"x\",\"rating\":4}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose what you're reviewing."));

        // 30 days after completion it's too late
        clock.advance(Duration.ofDays(31));
        post(customer, review(booked, 4, "Late but honest review."))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review_window"));
    }

    @Test
    void wordsAreScreened_andTrustAndSafetyCanHideTheReview() throws Exception {
        var booked = flow.completed(customer, tomorrowAt(15));
        var body = post(customer, review(booked, 1, "Shitty work, call me at 403-555-0199 for the details."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.screened").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String reviewId = JsonPath.read(body, "$.id");
        String text = JsonPath.read(body, "$.text");
        assertThat(text)
                .doesNotContain("403-555-0199")
                .doesNotContainIgnoringCase("shitty")
                .contains("****");

        var flag = jdbc.sql("select id from trust.flags where target_type = 'review' and target_id = ? and rule = ?")
                .params(reviewId, "review_screened")
                .query(String.class)
                .single();
        assertThat(ratings.summary(flow.provider.merchantId()).count()).isEqualTo(1);

        var staff = data.user("Tess Trust");
        // finance can't open the trust queue; trust & safety without a second factor neither
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/console/trust/flags/{id}/action", flag)
                        .with(TestJwt.staff(staff, StaffRole.FINANCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"hide_review\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/console/trust/flags/{id}/action", flag)
                        .with(TestJwt.staffWithoutMfa(staff, StaffRole.TRUST_SAFETY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"hide_review\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/console/trust/flags/{id}/action", flag)
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"hide_review\",\"note\":\"Abusive\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("hide_review"));

        assertThat(ratings.summary(flow.provider.merchantId()).count()).isZero();
        mvc.perform(get("/api/v1/public/providers/{slug}/reviews", flow.provider.slug()))
                .andExpect(jsonPath("$.items").isEmpty());
        // the business still sees it, marked hidden
        mvc.perform(get("/api/v1/merchants/{m}/reviews", flow.provider.merchantId())
                        .with(TestJwt.member(flow.provider.owner())))
                .andExpect(jsonPath("$.items[0].id").value(reviewId))
                .andExpect(jsonPath("$.items[0].hiddenAt").isNotEmpty());
        // and staff can show it again (audit-logged)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/console/trust/reviews/{id}/show", reviewId)
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.changed").value(true));
        assertThat(ratings.summary(flow.provider.merchantId()).count()).isEqualTo(1);
        assertThat(jdbc.sql(
                                "select count(*) from developer.audit_log where target_id = ? and action like 'trust.review_%'")
                        .params(reviewId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void erasureKeepsTheStars_andBlanksTheRest() throws Exception {
        var booked = flow.completed(customer, tomorrowAt(17));
        post(customer, review(booked, 4, "Fine work, would book again.")).andExpect(status().isCreated());
        var trust = contributors.stream()
                .filter(c -> c.module().equals("trust"))
                .findFirst()
                .orElseThrow();
        tx.executeWithoutResult(
                _ -> trust.erase(new PersonalDataContributor.Subject(customer, null, null, Locale.CANADA)));
        var row = jdbc.sql("select rating, text, author_name from trust.reviews where author_id = ?")
                .params(customer)
                .query((rs, _) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getString(3)})
                .single();
        assertThat(row).containsExactly(4, null, null);
        assertThat(ratings.summary(flow.provider.merchantId())).isEqualTo(new RatingQuery.RatingSummary(4.0, 1));
    }
}
