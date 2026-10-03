package ca.northline.uat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * S-121: UAT with the pilot group, end to end through the api — participants (people and businesses), their feedback
 * (gated, cleaned: no query strings, no tokens, the log redaction over the text; screenshots checked as S-104 says),
 * the console's triage flow with owners, tracker links, merging duplicates and the CSV, sign-offs per participant and
 * script, and the go/no-go report. {@link DryRun} is the recorded dry run (docs/uat/dry-run.md).
 */
class UatApiTest extends IntegrationTest {

    static final String ME = "/api/v1/me/pilot";
    static final String CONSOLE = "/api/v1/console/uat";

    @Autowired
    JdbcClient jdbc;

    String lead;
    String agent;
    String customer;
    String customerEmail;

    @BeforeEach
    void people() {
        lead = staff("Sam Lead", "support_lead");
        agent = staff("Ana Agent", "support");
        customer = Ids.next();
        customerEmail = "pilot-" + customer.toLowerCase(Locale.ROOT) + "@example.ca";
        jdbc.sql("""
                        insert into identity.users (id, first_name, last_name, display_name, email, locale, status)
                        values (:id, 'Dana', 'Pilot', 'Dana Pilot', :email, 'en-CA', 'active')
                        """).param("id", customer).param("email", customerEmail).update();
    }

    private String staff(String name, String role) {
        var id = data.user(name);
        jdbc.sql(
                        "insert into identity.platform_roles (user_id, role, granted_at) values (?, 'staff', now()), (?, ?, now())")
                .params(id, id, role)
                .update();
        return id;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private ResultActions addCustomer(String email) throws Exception {
        return mvc.perform(json(
                post(CONSOLE + "/participants").with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                "{\"persona\":\"customer\",\"label\":\"Pilot customer\",\"contact\":\"%s\"}".formatted(email)));
    }

    private static String feedback(String extra) {
        return """
                {"app":"consumer","category":"bug","severity":"blocker",
                 "body":"Checkout failed. Call me at 403-555-0199 or dana@example.ca",
                 "route":"https://northline.example/checkout/pay?session=sk_test_FAKE123&next=/x#step-2",
                 "appVersion":"2026.10.1","locale":"en-CA","platform":"Firefox 131 · macOS 15"%s}
                """.formatted(extra);
    }

    private ResultActions send(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(ME + "/feedback").with(who), body));
    }

    private ResultActions move(String id, String body) throws Exception {
        return mvc.perform(
                json(post(CONSOLE + "/feedback/{id}/moves", id).with(TestJwt.staff(agent, StaffRole.SUPPORT)), body));
    }

    /** S-120: a business in the pilot cohort (what Pilot onboarding's enrolment writes). */
    private String enrol(String merchantId, String type, String label) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into merchants.pilot_businesses (id, market_id, business_type, label, merchant_id, created_by)
                        values (?, 'mkt-calgary', ?, ?, ?, ?)
                        """).params(id, type, label, merchantId, lead).update();
        return id;
    }

    static byte[] png(int w, int h) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Nested
    class Participants {

        @Test
        void onlyParticipantsSeeTheControl_andSend() throws Exception {
            mvc.perform(get(ME).with(TestJwt.customer(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participant").value(false))
                    .andExpect(jsonPath("$.screenshotMaxBytes").value(5 * 1024 * 1024));
            send(TestJwt.customer(customer), feedback(""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("Feedback here is for pilot participants."));

            addCustomer(customerEmail.toUpperCase(Locale.ROOT))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.persona").value("customer"))
                    .andExpect(jsonPath("$.signoffs[0].script").value("customer"))
                    .andExpect(jsonPath("$.signoffs[0].outcome").value("pending"));
            addCustomer(customerEmail)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("already_participant"));
            mvc.perform(get(ME).with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.participant").value(true))
                    .andExpect(jsonPath("$.persona").value("customer"));
        }

        @Test
        void aPilotBusinessOfS120TakesPartAsAWhole_forItsMembersOnly() throws Exception {
            var biz = data.business(MerchantRole.TECHNICIAN);
            var outsider = data.user("Not on the team");
            // businesses aren't added here: they are S-120's pilot cohort
            mvc.perform(json(
                            post(CONSOLE + "/participants").with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                            "{\"persona\":\"provider\",\"label\":\"Pilot\",\"contact\":\"x@example.ca\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Pilot businesses come from Pilot onboarding: invite or enrol the business there."));
            mvc.perform(get(ME).param("merchantId", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.participant").value(false));
            var pilot = enrol(biz.merchantId(), "both", "Pilot both " + biz.merchantId());

            mvc.perform(get(ME).param("merchantId", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.participant").value(true))
                    .andExpect(jsonPath("$.persona").value("provider"));
            mvc.perform(get(CONSOLE + "/participants").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(jsonPath("$.items[?(@.id=='%s')].who".formatted(pilot))
                            .value("business"))
                    .andExpect(jsonPath("$.items[?(@.id=='%s')].signoffs.length()".formatted(pilot))
                            .value(2));
            mvc.perform(json(
                            post(CONSOLE + "/participants/{id}/signoffs", pilot)
                                    .with(TestJwt.staff(lead, StaffRole.MERCHANT_SUCCESS)),
                            "{\"script\":\"merchant-seller\",\"outcome\":\"signed_off\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.signoffs[?(@.script=='merchant-seller')].outcome")
                            .value("signed_off"))
                    .andExpect(jsonPath("$.signoffs[?(@.script=='merchant-provider')].outcome")
                            .value("pending"));
            mvc.perform(post(CONSOLE + "/participants/{id}/deactivate", pilot)
                            .with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("pilot_business"));
            mvc.perform(get(ME).param("merchantId", biz.merchantId()).with(TestJwt.member(outsider)))
                    .andExpect(jsonPath("$.participant").value(false));
            send(TestJwt.member(outsider), feedback(",\"merchantId\":\"%s\"".formatted(biz.merchantId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("You're not on this business's team."));
            send(TestJwt.member(biz.userId()), feedback(",\"merchantId\":\"%s\"".formatted(biz.merchantId())))
                    .andExpect(status().isCreated());
            mvc.perform(get(CONSOLE + "/feedback")
                            .param("persona", "provider")
                            .with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(jsonPath("$.items[?(@.participant=='Pilot both %s')]".formatted(biz.merchantId()))
                            .isNotEmpty());
        }

        @Test
        void validationMessages() throws Exception {
            addCustomer(customerEmail).andExpect(status().isCreated());
            send(TestJwt.customer(customer), "{}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[?(@.field=='category')].message")
                            .value("Choose bug, confusing, idea or praise."))
                    .andExpect(jsonPath("$.errors[?(@.field=='severity')].message")
                            .value("Choose how much it got in your way."))
                    .andExpect(jsonPath("$.errors[?(@.field=='body')].message")
                            .value("Tell us what happened, in 1 to 4,000 characters."))
                    .andExpect(jsonPath("$.errors[?(@.field=='route')].message")
                            .value("The app didn't send its screen, version, language or device."));
            send(TestJwt.customer(customer), feedback(",\"screenshotId\":\"%s\"".formatted(Ids.next())))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("That screenshot isn't yours or has expired; attach it again."));
            mvc.perform(json(
                            post(CONSOLE + "/participants").with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                            "{\"persona\":\"courier\",\"label\":\"x\",\"contact\":\"nobody@example.invalid\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message").value("No Northline account matches that."));
        }

        @Test
        void textAndRouteAreCleaned_beforeTheyAreStored() throws Exception {
            addCustomer(customerEmail).andExpect(status().isCreated());
            var id = read(send(TestJwt.customer(customer), feedback("")).andExpect(status().isCreated()), "$.id");
            var row = jdbc.sql("select body, route, platform from uat.feedback where id = ?")
                    .param(id)
                    .query((rs, _) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)))
                    .single();
            assertThat(row.get(0))
                    .startsWith("Checkout failed.")
                    .doesNotContain("403-555-0199")
                    .doesNotContain("dana@example.ca");
            assertThat(row.get(1)).isEqualTo("/checkout/pay");
            assertThat(row.get(2)).isEqualTo("Firefox 131 · macOS 15");

            var invite = feedback("")
                    .replace(
                            "https://northline.example/checkout/pay?session=sk_test_FAKE123&next=/x#step-2",
                            "/invite/Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZg");
            var second = read(send(TestJwt.customer(customer), invite).andExpect(status().isCreated()), "$.id");
            assertThat(jdbc.sql("select route from uat.feedback where id = ?")
                            .param(second)
                            .query(String.class)
                            .single())
                    .isEqualTo("/invite/:token");
            mvc.perform(get(ME + "/feedback").with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.items[*].id", hasItem(id)))
                    .andExpect(jsonPath("$.items[0].state").value("new"));
        }

        @Test
        void screenshots_areImagesOfTheRightType_andSize() throws Exception {
            mvc.perform(multipart(ME + "/screenshots")
                            .file(new MockMultipartFile("file", "s.png", "image/png", png(4, 4)))
                            .with(TestJwt.customer(customer)))
                    .andExpect(status().isForbidden());
            addCustomer(customerEmail).andExpect(status().isCreated());
            mvc.perform(multipart(ME + "/screenshots")
                            .file(new MockMultipartFile("file", "s.gif", "image/gif", new byte[] {'G', 'I', 'F', '8'}))
                            .with(TestJwt.customer(customer)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message").value("Attach the screenshot as a PNG or JPEG image."));
            mvc.perform(multipart(ME + "/screenshots")
                            .file(new MockMultipartFile(
                                    "file",
                                    "s.png",
                                    "image/png",
                                    "%PDF-1.7 fake".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                            .with(TestJwt.customer(customer)))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(multipart(ME + "/screenshots")
                            .file(new MockMultipartFile("file", "s.png", "image/png", new byte[5 * 1024 * 1024 + 1]))
                            .with(TestJwt.customer(customer)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message").value("The screenshot must be 5 MB or smaller."));
            var shot = read(
                    mvc.perform(multipart(ME + "/screenshots")
                                    .file(new MockMultipartFile("file", "s.png", "image/png", png(8, 8)))
                                    .with(TestJwt.customer(customer)))
                            .andExpect(status().isCreated()),
                    "$.id");
            var id = read(
                    send(TestJwt.customer(customer), feedback(",\"screenshotId\":\"%s\"".formatted(shot)))
                            .andExpect(status().isCreated()),
                    "$.id");
            mvc.perform(get(CONSOLE + "/feedback/{id}/screenshot", id).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"));
            // the screenshot went with the feedback: it can't be attached twice
            send(TestJwt.customer(customer), feedback(",\"screenshotId\":\"%s\"".formatted(shot)))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    class Console {

        @Test
        void supportSupportLeadsAndAdmins_withASecondFactor() throws Exception {
            mvc.perform(get(CONSOLE + "/feedback").with(TestJwt.customer(customer)))
                    .andExpect(status().isForbidden());
            mvc.perform(get(CONSOLE + "/feedback").with(TestJwt.staffWithoutMfa(agent, StaffRole.SUPPORT)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
            for (var role : List.of(StaffRole.ANALYST, StaffRole.FINANCE, StaffRole.DISPATCH)) {
                mvc.perform(get(CONSOLE + "/go-no-go").with(TestJwt.staff(agent, role)))
                        .andExpect(status().isForbidden());
            }
            for (var role : List.of(StaffRole.SUPPORT, StaffRole.SUPPORT_LEAD, StaffRole.ADMIN)) {
                mvc.perform(get(CONSOLE + "/go-no-go").with(TestJwt.staff(agent, role)))
                        .andExpect(status().isOk());
            }
            mvc.perform(json(
                            post(CONSOLE + "/participants").with(TestJwt.staff(lead, StaffRole.TRUST_SAFETY)),
                            "{\"persona\":\"customer\",\"label\":\"x\",\"contact\":\"%s\"}".formatted(customerEmail)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void movesFollowTheFlow_andNeedWhatTheyNeed() throws Exception {
            addCustomer(customerEmail).andExpect(status().isCreated());
            var id = read(send(TestJwt.customer(customer), feedback("")).andExpect(status().isCreated()), "$.id");
            move(id, "{\"to\":\"fixed\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("move_not_allowed"));
            move(id, "{\"to\":\"triaged\"}").andExpect(status().isOk());
            move(id, "{\"to\":\"accepted\"}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message").value("Say whether it blocks the launch."));
            move(id, "{\"to\":\"duplicate\"}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose the item this one duplicates."));
            move(id, "{\"to\":\"duplicate\",\"duplicateOf\":\"%s\"}".formatted(id))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("An item can't duplicate itself or one of its own duplicates."));
            move(id, "{\"to\":\"nope\"}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose the new state."));
            mvc.perform(json(
                            post(CONSOLE + "/feedback/{id}/tracker", id).with(TestJwt.staff(agent, StaffRole.SUPPORT)),
                            "{\"url\":\"javascript:alert(1)\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Enter the tracker issue's web address (https://…), up to 500 characters."));
            mvc.perform(json(
                            post(CONSOLE + "/feedback/{id}/owner", id).with(TestJwt.staff(agent, StaffRole.SUPPORT)),
                            "{\"ownerId\":\"%s\"}".formatted(customer)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Choose someone on the Northline team who works on UAT."));
            mvc.perform(json(
                            post(CONSOLE + "/feedback/{id}/moves", id)
                                    .with(TestJwt.staff(agent, StaffRole.SUPPORT))
                                    .header("Accept-Language", "fr-CA"),
                            "{\"to\":\"accepted\"}"))
                    .andExpect(jsonPath("$.errors[0].message").value("Indiquez s’il bloque le lancement."));
        }

        @Test
        void signoffs_perParticipantAndScript() throws Exception {
            var participant = read(addCustomer(customerEmail).andExpect(status().isCreated()), "$.id");
            var path = CONSOLE + "/participants/{id}/signoffs";
            mvc.perform(json(
                            post(path, participant).with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                            "{\"script\":\"courier\",\"outcome\":\"signed_off\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message").value("That script is for another persona."));
            mvc.perform(json(
                            post(path, participant).with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                            "{\"script\":\"customer\",\"outcome\":\"blocked\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Name the blocking items or describe them in the comments."));
            mvc.perform(
                            json(
                                    post(path, participant).with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                                    "{\"script\":\"customer\",\"outcome\":\"with_comments\",\"comments\":\"Fine on my phone.\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.signoffs[0].outcome").value("with_comments"))
                    .andExpect(jsonPath("$.signoffs[0].scriptVersion").value("1.0"))
                    .andExpect(jsonPath("$.signoffs[0].recordedByName").value("Sam Lead"));
            assertThat(jdbc.sql("select count(*) from developer.audit_log where target_id = ? and action = ?")
                            .params(participant, "uat.signoff_recorded")
                            .query(Integer.class)
                            .single())
                    .isOne();
            mvc.perform(post(CONSOLE + "/participants/{id}/deactivate", participant)
                            .with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)))
                    .andExpect(jsonPath("$.active").value(false));
            mvc.perform(get(ME).with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.participant").value(false));
            mvc.perform(json(
                            post(path, participant).with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                            "{\"script\":\"customer\",\"outcome\":\"signed_off\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("participant_inactive"));
        }
    }

    /**
     * The S-121 dry run: a fake pilot customer reports a blocker with a screenshot, a duplicate comes in from a second
     * participant, support triages, accepts it as blocking, assigns it, links the tracker issue, merges the duplicate,
     * marks it fixed and verified, the participant signs off; the go/no-go report and both CSVs follow at every step.
     */
    @Nested
    class DryRun {

        @Test
        void aBlockerFromReportToSignOff() throws Exception {
            addCustomer(customerEmail).andExpect(status().isCreated());
            var second = data.user("Second pilot");
            jdbc.sql("update identity.users set email = ? where id = ?")
                    .params("second-" + second.toLowerCase(Locale.ROOT) + "@example.ca", second)
                    .update();
            mvc.perform(json(
                            post(CONSOLE + "/participants").with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                            "{\"persona\":\"customer\",\"label\":\"Pilot customer B\",\"contact\":\"second-%s@example.ca\"}"
                                    .formatted(second.toLowerCase(Locale.ROOT))))
                    .andExpect(status().isCreated());

            // 1. two participants report the same blocker
            var shot = read(
                    mvc.perform(multipart(ME + "/screenshots")
                                    .file(new MockMultipartFile("file", "s.png", "image/png", png(16, 9)))
                                    .with(TestJwt.customer(customer)))
                            .andExpect(status().isCreated()),
                    "$.id");
            var sent = send(TestJwt.customer(customer), feedback(",\"screenshotId\":\"%s\"".formatted(shot)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.startsWith("UAT-")));
            var id = read(sent, "$.id");
            var reference = read(sent, "$.reference");
            var dup = read(send(TestJwt.customer(second), feedback("")).andExpect(status().isCreated()), "$.id");

            // the untriaged blocker holds the launch
            mvc.perform(get(CONSOLE + "/go-no-go").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(jsonPath("$.verdict").value("no_go"))
                    .andExpect(jsonPath("$.blockingItems[?(@.id=='%s')].state".formatted(id))
                            .value("new"))
                    .andExpect(jsonPath("$.reasons[*].code", hasItem("blockers_untriaged")));
            mvc.perform(get(CONSOLE + "/feedback").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(jsonPath("$.items[*].id", hasItem(id)));

            // 2. triage: triaged → accepted (blocking), owner, tracker issue, the duplicate merged in
            move(id, "{\"to\":\"triaged\"}").andExpect(jsonPath("$.next", hasItem("accepted")));
            move(id, "{\"to\":\"accepted\",\"blocking\":true,\"note\":\"Customers can't pay: blocks launch.\"}")
                    .andExpect(jsonPath("$.item.state").value("accepted"))
                    .andExpect(jsonPath("$.item.blocking").value(true));
            mvc.perform(json(
                            post(CONSOLE + "/feedback/{id}/owner", id).with(TestJwt.staff(agent, StaffRole.SUPPORT)),
                            "{\"ownerId\":\"%s\"}".formatted(lead)))
                    .andExpect(jsonPath("$.item.ownerName").value("Sam Lead"));
            mvc.perform(json(
                            post(CONSOLE + "/feedback/{id}/tracker", id).with(TestJwt.staff(agent, StaffRole.SUPPORT)),
                            "{\"url\":\"https://tracker.example.com/NL-4242\"}"))
                    .andExpect(jsonPath("$.item.trackerUrl").value("https://tracker.example.com/NL-4242"));
            move(dup, "{\"to\":\"duplicate\",\"duplicateOf\":\"%s\"}".formatted(id))
                    .andExpect(jsonPath("$.item.duplicateOf").value(id))
                    .andExpect(jsonPath("$.duplicateOfItem.reference").value(reference));
            mvc.perform(get(CONSOLE + "/feedback/{id}", id).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(jsonPath("$.item.duplicates").value(1))
                    .andExpect(jsonPath("$.duplicates[0].id").value(dup))
                    .andExpect(jsonPath("$.history[1].note").value("Customers can't pay: blocks launch."))
                    .andExpect(jsonPath("$.history[1].actorName").value("Ana Agent"));
            mvc.perform(get(CONSOLE + "/go-no-go").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(jsonPath("$.blockingItems[?(@.id=='%s')].state".formatted(id))
                            .value("accepted"))
                    .andExpect(jsonPath("$.blockingItems[?(@.id=='%s')].reports".formatted(id))
                            .value(2))
                    .andExpect(jsonPath("$.reasons[*].code", hasItem("blocking_open")));

            // the participant signs off "blocked", naming the item
            var participant = jdbc.sql("select id from uat.participants where user_id = ?")
                    .param(customer)
                    .query(String.class)
                    .single();
            mvc.perform(json(
                            post(CONSOLE + "/participants/{p}/signoffs", participant)
                                    .with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                            "{\"script\":\"customer\",\"outcome\":\"blocked\",\"blockingIds\":[\"%s\"]}".formatted(id)))
                    .andExpect(jsonPath("$.signoffs[0].blockingRefs[0]").value(reference));

            // 3. fixed (still holds the launch until verified), verified, closed
            move(id, "{\"to\":\"fixed\"}").andExpect(status().isOk());
            mvc.perform(get(CONSOLE + "/go-no-go").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(jsonPath("$.blockingItems[?(@.id=='%s')].state".formatted(id))
                            .value("fixed"));
            move(id, "{\"to\":\"verified\"}").andExpect(status().isOk());
            move(id, "{\"to\":\"closed\"}").andExpect(jsonPath("$.next[0]").value("triaged"));
            mvc.perform(get(CONSOLE + "/go-no-go").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(jsonPath("$.blockingItems[*].id", not(hasItem(id))))
                    .andExpect(jsonPath("$.trend.length()").value(14));

            // 4. the participant signs off; their coverage counts it
            mvc.perform(json(
                            post(CONSOLE + "/participants/{p}/signoffs", participant)
                                    .with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)),
                            "{\"script\":\"customer\",\"outcome\":\"signed_off\"}"))
                    .andExpect(jsonPath("$.signoffs[0].outcome").value("signed_off"))
                    .andExpect(jsonPath("$.signoffs[0].history").value(2));
            mvc.perform(get(ME + "/feedback").with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.items[0].state").value("closed"));

            // CSVs: the queue (formula-safe) and the report, in French on request
            mvc.perform(get(CONSOLE + "/feedback/export").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString("uat-feedback.csv")))
                    .andExpect(content().string(containsString(reference + ",Closed,Yes,Bug,Blocker,Customer")))
                    .andExpect(content().string(containsString("https://tracker.example.com/NL-4242")));
            mvc.perform(get(CONSOLE + "/go-no-go/export")
                            .header("Accept-Language", "fr-CA")
                            .with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Profil,Scénario,Version")))
                    .andExpect(content().string(containsString("Client,Client,1.0")));
            assertThat(jdbc.sql("select count(*) from developer.audit_log where target_id = ? and action like 'uat.%'")
                            .param(id)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(7);
        }
    }
}
