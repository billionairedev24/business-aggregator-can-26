package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.ApplicationDecided;
import ca.northline.merchants.api.ApplicationReviewed;
import ca.northline.merchants.application.DevIdentityOutcomes;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Two-person review of merchant applications (four-eyes, 2026-10-18): a reviewer recommends (stage 1), a different
 * staff member decides (stage 2). The approver can't be a reviewer of the open round (403 {@code same_person}), a
 * decision needs a review (409 {@code review_required}), request info sends the application back and the next
 * submission starts again at stage 1; every step is audit-logged, and the events carry ids and codes only.
 */
@RecordApplicationEvents
class FourEyesReviewApiTest extends IntegrationTest {

    static final String QUEUE = "/api/v1/console/verification/applications";
    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DevIdentityOutcomes identity;

    @Autowired
    ApplicationEvents events;

    OnboardingFlow flow;
    String owner;
    String reviewer;
    String approver;

    @BeforeEach
    void setUp() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        flow = new OnboardingFlow(mvc, dataSource, identity);
        owner = data.user("Amara Okafor");
        reviewer = data.user("Lena Park");
        approver = data.user("Dev Kaur");
    }

    String submittedBakery(String name) throws Exception {
        var id = flow.start(owner, "seller");
        flow.business(id, owner, OnboardingFlow.soleBusiness(name, "shop.food-and-grocery.bakery"))
                .andExpect(status().isOk());
        flow.completeAll(id, owner);
        flow.submit(id, owner).andExpect(status().isOk());
        return id;
    }

    ResultActions review(String id, String who, String body, StaffRole... roles) throws Exception {
        return mvc.perform(post(QUEUE + "/{id}/review", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.staff(who, roles.length == 0 ? new StaffRole[] {StaffRole.TRUST_SAFETY} : roles)));
    }

    ResultActions decide(String id, String who, String body, StaffRole... roles) throws Exception {
        return mvc.perform(post(QUEUE + "/{id}/decision", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.staff(who, roles.length == 0 ? new StaffRole[] {StaffRole.ADMIN} : roles)));
    }

    List<Map<String, Object>> audit(String merchantId) {
        return jdbc.sql("""
                        select action, actor_id, role, after::text as after from developer.audit_log
                         where merchant_id = ?
                           and (action like 'verification.application%' or action = 'verification.info_requested')
                         order by at""").params(merchantId).query().listOfRows();
    }

    @Test
    void reviewThenDecide_byTwoPeople_isLoggedAndPublished() throws Exception {
        var id = submittedBakery("Two Eyes Bakery");
        mvc.perform(get(QUEUE + "/{id}", id).with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.application.stage").value("review"))
                .andExpect(jsonPath("$.application.review").doesNotExist())
                .andExpect(jsonPath("$.fourEyes").value(true));

        review(id, reviewer, "{\"recommendation\":\"approve\",\"note\":\"Licence and insurance look right.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("pending"))
                .andExpect(jsonPath("$.application.stage").value("decision"))
                .andExpect(jsonPath("$.application.review.recommendation").value("approve"))
                .andExpect(jsonPath("$.application.review.reviewedBy").value(reviewer))
                .andExpect(jsonPath("$.application.review.reviewerName").value("Lena Park"))
                .andExpect(jsonPath("$.application.review.note").value("Licence and insurance look right."))
                .andExpect(jsonPath("$.reviews.length()").value(1));
        var queue = mvc.perform(get(QUEUE).with(TestJwt.staff(approver, StaffRole.TRUST_SAFETY)))
                .andExpect(jsonPath("$.fourEyes").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> stage = JsonPath.read(queue, "$.items[?(@.merchantId == '%s')].stage".formatted(id));
        assertThat(stage).containsExactly("decision");

        decide(id, approver, "{\"decision\":\"approve\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("active"))
                .andExpect(jsonPath("$.application.stage").doesNotExist())
                .andExpect(jsonPath("$.decisions[0].decidedBy").value(approver))
                .andExpect(jsonPath("$.decisions[0].reviewId").isString())
                .andExpect(jsonPath("$.reviews[0].decisionId").isString());

        var trail = audit(id);
        assertThat(trail)
                .extracting(a -> a.get("action"))
                .containsExactly("verification.application_reviewed", "verification.application_approved");
        assertThat(trail.getFirst()).containsEntry("actor_id", reviewer).containsEntry("role", "trust_safety");
        assertThat((String) trail.getFirst().get("after"))
                .contains("\"recommendation\": \"approve\"")
                .contains("\"note\": \"Licence and insurance look right.\"");
        assertThat(trail.getLast()).containsEntry("actor_id", approver).containsEntry("role", "admin");
        assertThat((String) trail.getLast().get("after"))
                .contains("\"reviewedBy\": \"%s\"".formatted(reviewer))
                .contains("\"status\": \"active\"");

        assertThat(events.stream(ApplicationReviewed.class)
                        .filter(e -> e.aggregateId().equals(id)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.actorId()).isEqualTo(reviewer);
                    assertThat(e.recommendation()).isEqualTo("approve");
                });
        // ids and codes only: no free text (the note) and no names on the review event
        assertThat(Arrays.stream(ApplicationReviewed.class.getRecordComponents())
                        .map(c -> c.getName()))
                .containsExactly("eventId", "occurredAt", "aggregateId", "actorId", "reviewId", "recommendation");
        assertThat(events.stream(ApplicationDecided.class)
                        .filter(e -> e.aggregateId().equals(id)))
                .singleElement()
                .satisfies(e -> assertThat(e.reviewerId()).isEqualTo(reviewer));
    }

    @Test
    void theReviewerCantDecide_403() throws Exception {
        var id = submittedBakery("Same Hands Bakery");
        // the demo-data script's body (scripts/demo/demo-data.mjs) also sends a `decision`: ignored
        review(id, reviewer, "{\"decision\":\"approve\",\"recommendation\":\"approve\",\"note\":\"Demo\"}")
                .andExpect(status().isOk());

        decide(id, reviewer, "{\"decision\":\"approve\"}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("same_person"))
                .andExpect(
                        jsonPath("$.detail").value("You reviewed this application, so someone else has to decide it."));
        mvc.perform(post(QUEUE + "/{id}/decision", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}")
                        .header("Accept-Language", "fr-CA")
                        .with(TestJwt.staff(reviewer, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail")
                        .value("Vous avez examiné cette demande : une autre personne doit la trancher."));

        // a second review by someone else doesn't let the first reviewer decide either: both looked at this round
        var other = data.user("Marc Belanger");
        review(id, other, "{\"recommendation\":\"approve\"}").andExpect(status().isOk());
        decide(id, reviewer, "{\"decision\":\"approve\"}").andExpect(status().isForbidden());
        decide(id, other, "{\"decision\":\"approve\"}").andExpect(status().isForbidden());
        assertThat(audit(id)).extracting(a -> a.get("action")).containsOnly("verification.application_reviewed");

        decide(id, approver, "{\"decision\":\"approve\"}").andExpect(status().isOk());
    }

    @Test
    void aDecisionNeedsAReview_409() throws Exception {
        var id = submittedBakery("Hasty Bakery");
        decide(id, approver, "{\"decision\":\"approve\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review_required"))
                .andExpect(
                        jsonPath("$.detail").value("Review this application first: another person then decides it."));
        assertThat(audit(id)).isEmpty();
    }

    @Test
    void rolesAreEnforced_403() throws Exception {
        var id = submittedBakery("Role Bakery");
        for (var role : List.of(StaffRole.SUPPORT, StaffRole.FINANCE, StaffRole.ANALYST, StaffRole.MERCHANT_SUCCESS)) {
            review(id, reviewer, "{\"recommendation\":\"approve\"}", role)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            decide(id, approver, "{\"decision\":\"approve\"}", role)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
        mvc.perform(post(QUEUE + "/{id}/review", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendation\":\"approve\"}")
                        .with(TestJwt.staffWithoutMfa(reviewer, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(post(QUEUE + "/{id}/review", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendation\":\"approve\"}")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isForbidden());
        assertThat(audit(id)).isEmpty();
    }

    @Test
    void reviewValidation_andApprovingAgainstTheReview() throws Exception {
        var id = submittedBakery("Doubtful Bakery");
        review(id, reviewer, "{\"recommendation\":\"maybe\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("recommendation"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose approve, reject or request info."));
        review(id, reviewer, "{\"recommendation\":\"reject\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("note"))
                .andExpect(jsonPath("$.errors[0].message").value("Say why you recommend rejecting it."));
        review(id, reviewer, "{\"recommendation\":\"request_info\",\"note\":\"x\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose what the business needs to fix."));
        review(id, reviewer, "{\"recommendation\":\"approve\",\"note\":\"%s\"}".formatted("x".repeat(501)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("At most 500 characters."));

        review(id, reviewer, "{\"recommendation\":\"reject\",\"note\":\"The bakery's address is a parking lot.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.review.recommendation").value("reject"));
        decide(id, approver, "{\"decision\":\"approve\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("note"))
                .andExpect(jsonPath("$.errors[0].message").value("Say why you approve against the review."));
        decide(id, approver, "{\"decision\":\"approve\",\"note\":\"Called them: it's a market stall there.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("active"));
        assertThat((String) audit(id).getLast().get("after")).contains("\"recommendation\": \"reject\"");
    }

    @Test
    void requestInfo_returnsToTheMerchant_andTheNextSubmissionIsReviewedAgain() throws Exception {
        var id = submittedBakery("Loop Bakery");
        var redo = "returns_policy";
        review(
                        id,
                        reviewer,
                        "{\"recommendation\":\"request_info\",\"checkKeys\":[\"%s\"],\"note\":\"Policy is blank.\"}"
                                .formatted(redo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.review.checkKeys[0]").value(redo));
        // still pending until someone else decides
        mvc.perform(get("/api/v1/merchants/{id}/onboarding", id).with(TestJwt.member(owner)))
                .andExpect(jsonPath("$.status").value("pending"));

        decide(
                        id,
                        approver,
                        "{\"decision\":\"request_info\",\"checkKeys\":[\"%s\"],\"note\":\"Add a returns policy.\"}"
                                .formatted(redo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("applicant"))
                .andExpect(jsonPath("$.application.decision").value("info_requested"));
        mvc.perform(get("/api/v1/merchants/{id}/onboarding", id).with(TestJwt.member(owner)))
                .andExpect(jsonPath("$.status").value("applicant"))
                .andExpect(jsonPath("$.checklist[?(@.key == '%s')].status".formatted(redo))
                        .value("rejected"));

        // the owner fixes it and submits again: stage 1 again, and the old review no longer counts
        List<String> redoId =
                JsonPath.read(flow.onboarding(id, owner), "$.checklist[?(@.key == '%s')].id".formatted(redo));
        flow.complete(id, owner, redoId.getFirst(), "{\"choice\":\"standard\"}").andExpect(status().isOk());
        flow.submit(id, owner).andExpect(status().isOk());
        mvc.perform(get(QUEUE + "/{id}", id).with(TestJwt.staff(approver, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.application.status").value("pending"))
                .andExpect(jsonPath("$.application.stage").value("review"))
                .andExpect(jsonPath("$.application.review").doesNotExist())
                .andExpect(jsonPath("$.reviews.length()").value(1))
                .andExpect(jsonPath("$.reviews[0].decisionId").isString());
        decide(id, approver, "{\"decision\":\"approve\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review_required"));

        // a new round: last time's approver reviews, last time's reviewer may decide now
        review(id, approver, "{\"recommendation\":\"approve\"}").andExpect(status().isOk());
        decide(id, reviewer, "{\"decision\":\"approve\"}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("active"));

        assertThat(audit(id))
                .extracting(a -> a.get("action"))
                .containsExactly(
                        "verification.application_reviewed",
                        "verification.info_requested",
                        "verification.application_reviewed",
                        "verification.application_approved");
        assertThat((String) audit(id).get(1).get("after"))
                .contains("\"recommendation\": \"request_info\"")
                .contains("\"note\": \"Add a returns policy.\"");
    }

    @Test
    void reviewingADecidedApplication_409() throws Exception {
        var id = submittedBakery("Done Bakery");
        review(id, reviewer, "{\"recommendation\":\"approve\"}").andExpect(status().isOk());
        decide(id, approver, "{\"decision\":\"approve\"}").andExpect(status().isOk());
        review(id, reviewer, "{\"recommendation\":\"approve\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_pending"));
    }
}
