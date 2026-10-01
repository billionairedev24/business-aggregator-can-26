package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.messaging.api.TicketOpened;
import ca.northline.messaging.domain.SupportSla;
import ca.northline.messaging.domain.TicketPriority;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Studio › Help &amp; support: help centre, platform status, cases (open, list, read, reply), the {@code help} badge. */
@RecordApplicationEvents
class HelpApiTest extends MessagingApiTest {

    static final String HELP = "/api/v1/merchants/{m}/help";
    static final String CASES = HELP + "/cases";
    static final String CASE = """
            {"topic":"verification","refType":"document","refId":"DOC-1","refLabel":"Document · WCB clearance",
             "body":"Uploaded the renewed letter — can instant book be turned back on today?\\nThanks",
             "channel":"chat","urgent":false}""";

    @Autowired
    ApplicationEvents events;

    @Nested
    class HelpCentre {

        @Test
        void providerTopicsFromTheDesign_withArticleCounts() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(get(HELP + "/topics", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.items[*].name",
                            contains(
                                    "Getting verified · licences & insurance",
                                    "Appointments, availability & no-shows",
                                    "Listings, products & bulk upload",
                                    "Escrow, payouts & fees",
                                    "Refunds & disputes",
                                    "Storefront, API & integrations")))
                    .andExpect(jsonPath("$.items[0].articleCount").value(3))
                    .andExpect(jsonPath("$.items[0].caseTopic").value("verification"));
        }

        @Test
        void kitchenTopicsInFrench() throws Exception {
            var merchant = data.merchant("kitchen", "Pho Dau Bo");
            var owner = member(merchant, MerchantRole.COOK);
            mvc.perform(get(HELP + "/topics", merchant)
                            .header("Accept-Language", "fr-CA")
                            .with(TestJwt.member(owner)))
                    .andExpect(jsonPath("$.items", hasSize(6)))
                    .andExpect(jsonPath("$.items[0].name").value("Vérification · permis AHS et inspections"));
        }

        @Test
        void suggestedArticlesPerPortal() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(get(HELP + "/articles", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath(
                            "$.items[*].title",
                            contains(
                                    "What the completion photo must show",
                                    "How the quality score and tiers are calculated",
                                    "Contesting an auto-refund within 48 h",
                                    "Split shifts and travel buffers")))
                    .andExpect(jsonPath("$.items[0].section").value("Escrow"))
                    .andExpect(jsonPath("$.items[0].readMin").value(2));
            var kitchen = data.merchant("kitchen", "Pho Dau Bo");
            var cook = member(kitchen, MerchantRole.COOK);
            mvc.perform(get(HELP + "/articles", kitchen).with(TestJwt.member(cook)))
                    .andExpect(jsonPath("$.items[0].title")
                            .value("Renewing your AHS Food Handling Permit before it lapses"));
        }

        @Test
        void searchFindsTheDesignsPayoutArticles_titleMatchFirst() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(get(HELP + "/articles", biz.merchantId())
                            .param("q", "payout on hold")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[0].title").value("Why is my payout on hold?"))
                    .andExpect(jsonPath("$.items[*].title", hasItem("How the 48-hour auto-release works")))
                    .andExpect(jsonPath("$.items[*].title", hasItem("Instant payouts: fees and eligibility")));
            mvc.perform(get(HELP + "/articles", biz.merchantId())
                            .param("q", "versement retenu")
                            .header("Accept-Language", "fr")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[0].title").value("Pourquoi mon versement est-il retenu?"));
            mvc.perform(get(HELP + "/articles", biz.merchantId())
                            .param("q", "zzzz")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }

        @Test
        void articlesOfATopic_andOneArticle_butNotAnotherPortals() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(get(HELP + "/articles", biz.merchantId())
                            .param("topic", "refunds")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(2)));
            mvc.perform(get(HELP + "/articles/{slug}", biz.merchantId(), "payout-on-hold")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.body", startsWith("A payout is held when Stripe needs something")))
                    .andExpect(jsonPath("$.topicKeys", hasItem("escrow")));
            mvc.perform(get(HELP + "/articles/{slug}", biz.merchantId(), "priority-allergens")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void platformStatus() throws Exception {
            var biz = data.business(MerchantRole.BOOKKEEPER);
            mvc.perform(get(HELP + "/status", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath(
                            "$.items[*].name",
                            contains(
                                    "Ordering & checkout",
                                    "Payments (Stripe)",
                                    "Payouts",
                                    "Live tracking",
                                    "Notifications (SMS)",
                                    "API & webhooks")))
                    .andExpect(jsonPath("$.items[0].state").value("operational"));
        }

        @Test
        void nonMemberIsForbidden() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(get(HELP + "/topics", biz.merchantId()).with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden());
            mvc.perform(get(CASES, biz.merchantId()).with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }
    }

    @Nested
    class Cases {

        @Test
        void masterTierOpensACase_priorityQueueSla_contextAttached_threadStarted() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            tier(biz.merchantId(), "master");
            var before = Instant.now();
            var created =
                    json(mvc.perform(postJson(CASES, CASE, biz.merchantId()).with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.code", startsWith("HD-")))
                            .andExpect(jsonPath("$.subject")
                                    .value("Uploaded the renewed letter — can instant book be turned back on today?"))
                            .andExpect(jsonPath("$.state").value("new"))
                            .andExpect(jsonPath("$.priority").value("priority"))
                            .andExpect(jsonPath("$.refLabel").value("Document · WCB clearance"))
                            .andExpect(jsonPath("$.channel").value("chat")));
            var id = created.get("id").asString();
            var due = Instant.parse(created.get("slaDueAt").asString());
            assertThat(due)
                    .isBetween(
                            SupportSla.dueAt(before, TicketPriority.PRIORITY, java.time.ZoneId.of("America/Edmonton"))
                                    .minusSeconds(1),
                            SupportSla.dueAt(
                                            Instant.now(),
                                            TicketPriority.PRIORITY,
                                            java.time.ZoneId.of("America/Edmonton"))
                                    .plusSeconds(1));
            var context = jdbc.sql("select context::text from messaging.tickets where id = ?")
                    .params(id)
                    .query(String.class)
                    .single();
            assertThat(context)
                    .contains(
                            "\"portal\": \"provider\"", "\"tier\": \"master\"", "\"role\": \"owner\"", "recentEvents");
            assertThat(events.stream(TicketOpened.class)).singleElement().satisfies(e -> {
                assertThat(e.aggregateId()).isEqualTo(id);
                assertThat(e.priority()).isEqualTo("priority");
                assertThat(e.topic()).isEqualTo("verification");
            });

            mvc.perform(get(CASES + "/{id}", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.summary.id").value(id))
                    .andExpect(jsonPath("$.messages", hasSize(1)))
                    .andExpect(jsonPath("$.messages[0].senderRole").value("merchant"));
            // case threads never show in Messages
            mvc.perform(get("/api/v1/merchants/{m}/threads", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(0)));
            assertThat(badges(biz.merchantId(), biz.userId(), MerchantRole.OWNER, Locale.CANADA))
                    .containsEntry("help", "1 open");
            assertThat(badges(biz.merchantId(), biz.userId(), MerchantRole.OWNER, Locale.CANADA_FRENCH))
                    .containsEntry("help", "1 ouvert");
        }

        @Test
        void urgentCaseGetsFifteenMinutes_normalTierFourHours() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var urgent = json(
                    mvc.perform(postJson(CASES, CASE.replace("\"urgent\":false", "\"urgent\":true"), biz.merchantId())
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(jsonPath("$.priority").value("urgent"))
                            .andExpect(jsonPath("$.urgent").value(true)));
            var normal =
                    json(mvc.perform(postJson(CASES, CASE, biz.merchantId()).with(TestJwt.member(biz.userId())))
                            .andExpect(jsonPath("$.priority").value("normal")));
            assertThat(Instant.parse(urgent.get("slaDueAt").asString()))
                    .isBefore(Instant.parse(normal.get("slaDueAt").asString()));
            mvc.perform(get(CASES, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(2)));
        }

        @Test
        void bookkeeperMayOpenACase() throws Exception {
            var biz = data.business(MerchantRole.BOOKKEEPER);
            mvc.perform(postJson(CASES, CASE.replace("verification", "payouts"), biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isCreated());
        }

        @Test
        void listShowsOpenCasesFirst_withAgentAndLastReply() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var resolved =
                    ticket(biz.merchantId(), 99001, "Payout arrived a day late", "resolved", Duration.ofDays(20));
            var open =
                    ticket(biz.merchantId(), 99002, "WCB clearance letter uploaded", "in_progress", Duration.ofDays(1));
            var t = thread(biz.merchantId(), "case", null, "Northline support", "ticket", "HD-99002");
            jdbc.sql("update messaging.threads set ref_id = ? where id = ?")
                    .params(open, t)
                    .update();
            message(t, "merchant", "Uploaded the renewed letter", ago(Duration.ofHours(3)));
            message(t, "agent", "Thanks Ravi — document received.", ago(Duration.ofHours(2)));
            mvc.perform(get(CASES, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[*].id", contains(open, resolved)))
                    .andExpect(jsonPath("$.items[0].agentName").value("Dev K."))
                    .andExpect(jsonPath("$.items[0].lastAgentReplyAt").isNotEmpty())
                    .andExpect(jsonPath("$.items[1].resolutionNote").value("bank holiday"));
        }

        @Test
        void replyToACaseWaitingOnTheBusiness_goesBackToNorthline() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var id = json(mvc.perform(postJson(CASES, CASE, biz.merchantId()).with(TestJwt.member(biz.userId()))))
                    .get("id")
                    .asString();
            jdbc.sql("update messaging.tickets set state = 'waiting', sla_due_at = null where id = ?")
                    .params(id)
                    .update();
            mvc.perform(postJson(
                                    CASES + "/{id}/messages",
                                    "{\"body\":\"Here's the letter again.\"}",
                                    biz.merchantId(),
                                    id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value("Here's the letter again."));
            mvc.perform(get(CASES + "/{id}", biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.summary.state").value("in_progress"))
                    .andExpect(jsonPath("$.summary.slaDueAt").isNotEmpty())
                    .andExpect(jsonPath("$.messages", hasSize(2)));
        }

        @Test
        void resolvedCaseCannotBeReplied() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var id = ticket(biz.merchantId(), 99003, "Old", "resolved", Duration.ofDays(3));
            mvc.perform(postJson(CASES + "/{id}/messages", "{\"body\":\"Hello?\"}", biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("case_resolved"));
        }

        @Test
        void otherBusinessesCasesAreNotFound() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var id = ticket(biz.merchantId(), 99004, "Mine", "new", Duration.ofDays(1));
            var other = data.business(MerchantRole.OWNER);
            mvc.perform(get(CASES + "/{id}", other.merchantId(), id).with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void relatedListsTheBusinessesLinkedBookingsOrdersAndDisputes() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var t = thread(biz.merchantId(), "customer", null, "Amara Osei", "booking", "BK-7712");
            message(t, "customer", "Hi", ago(Duration.ofMinutes(5)));
            var o = thread(biz.merchantId(), "customer", null, "M. Tran", "order", "NL-48213");
            message(o, "customer", "Hi", ago(Duration.ofMinutes(10)));
            mvc.perform(get(HELP + "/related", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[*].label", contains("Booking BK-7712 · A. Osei", "Order NL-48213")))
                    .andExpect(jsonPath("$.items[0].type").value("booking"));
        }

        /** Help › Contact support — every message. */
        @ParameterizedTest(name = "[{index}] {0} → {2}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "\"topic\":\"verification\"     | \"topic\":\"\"          | topic   | required | Choose a topic.",
                    "\"topic\":\"verification\"     | \"topic\":\"gossip\"    | topic   | format   | Choose a topic.",
                    "\"channel\":\"chat\"           | \"channel\":\"\"        | channel | required | Choose how we should reach you.",
                    "\"channel\":\"chat\"           | \"channel\":\"fax\"     | channel | format   | Choose how we should reach you.",
                    "\"refType\":\"document\"       | \"refType\":\"parcel\"  | refType | format   | Pick a record from the list.",
                    "\"refId\":\"DOC-1\"            | \"refId\":\"\"          | refId   | required | Pick a record from the list.",
                })
        void invalidCaseIs422(String from, String to, String field, String rule, String message) throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            mvc.perform(postJson(CASES, CASE.replace(from, to), biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].rule").value(rule))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void emptyAndTooLongDescriptionsAre422() throws Exception {
            var biz = data.business(MerchantRole.OWNER);
            var body = CASE.substring(CASE.indexOf("\"body\""), CASE.indexOf(",\n \"channel\""));
            mvc.perform(postJson(CASES, CASE.replace(body, "\"body\":\"  \""), biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.errors[0].field").value("body"))
                    .andExpect(jsonPath("$.errors[0].message").value("Tell us what's happening."));
            mvc.perform(postJson(
                                    CASES,
                                    CASE.replace(body, "\"body\":\"%s\"".formatted("x".repeat(4001))),
                                    biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.errors[0].field").value("body"))
                    .andExpect(jsonPath("$.errors[0].rule").value("length"))
                    .andExpect(jsonPath("$.errors[0].message").value("Keep it under 4,000 characters."));
        }
    }

    private String ticket(String merchantId, int number, String subject, String state, Duration age) {
        var id = ca.northline.shared.Ids.next();
        jdbc.sql("""
                        insert into messaging.tickets (id, number, requester_type, requester_id, merchant_id, topic, subject,
                                                       priority, state, agent_name, resolution_note, resolved_at,
                                                       created_at, updated_at)
                        values (?, ?, 'merchant', ?, ?, 'payouts', ?, 'normal', ?, 'Dev K.',
                                case when ? = 'resolved' then 'bank holiday' end,
                                case when ? = 'resolved' then now() end, ?, ?)
                        """)
                .params(
                        id,
                        number + 10 * (int) (Math.random() * 1_000_000),
                        merchantId,
                        merchantId,
                        subject,
                        state,
                        state,
                        state,
                        utc(ago(age)),
                        utc(ago(age)))
                .update();
        return id;
    }
}
