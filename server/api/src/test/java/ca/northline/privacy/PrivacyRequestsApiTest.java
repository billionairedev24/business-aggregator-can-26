package ca.northline.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.privacy.application.PrivacyWork;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-105: a person's privacy requests from their account — identity checks (step-up proof or a texted code), the SLA
 * clock from their province's law, the erasure's grace period, withdrawal, the access export behind a short-lived link,
 * validation messages (en, fr-CA), and the audit log.
 */
class PrivacyRequestsApiTest extends IntegrationTest {

    static final String PATH = "/api/v1/me/privacy-requests";
    static final Pattern CODE = Pattern.compile("\\b([0-9]{6})\\b");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PrivacyWork work;

    String person;
    String phone;

    @BeforeEach
    void person() {
        person = Ids.next();
        phone = "+1587%07d".formatted(Math.floorMod(person.hashCode(), 8_000_000) + 2_000_000);
        jdbc.sql("""
                        insert into identity.users (id, first_name, last_name, display_name, email, phone, locale, status)
                        values (:id, 'Amara', 'Osei', 'Amara Osei', :email, :phone, 'en-CA', 'active')
                        """)
                .param("id", person)
                .param("email", "amara-" + person.toLowerCase(java.util.Locale.ROOT) + "@example.ca")
                .param("phone", phone)
                .update();
    }

    private ResultActions open(String json, String proof) throws Exception {
        var request = post(PATH)
                .with(TestJwt.customer(person))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return mvc.perform(proof.isEmpty() ? request : request.header("X-Step-Up", proof));
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String lastCode() {
        var sent = texts.to(phone);
        var matcher = CODE.matcher(sent.getLast().body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @Nested
    class Erasure {

        @Test
        void aStepUpVerifiesAtOnce_andErasureStartsAfterTheGracePeriodUnderTheProvincesLaw() throws Exception {
            jdbc.sql("""
                            insert into identity.addresses (id, user_id, street, city, province, postal, is_default)
                            values (:id, :u, '1 Rue Principale', 'Gatineau', 'QC', 'J8X 1A1', true)
                            """).param("id", Ids.next()).param("u", person).update();
            var before = Instant.now();

            var result = open("{\"type\":\"erasure\"}", "dev")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reference").value(startsWith("PR-")))
                    .andExpect(jsonPath("$.type").value("erasure"))
                    .andExpect(jsonPath("$.state").value("verified"))
                    .andExpect(jsonPath("$.verification").value("step_up"))
                    .andExpect(jsonPath("$.province").value("QC"))
                    .andExpect(jsonPath("$.law.code").value("qc_law25"))
                    .andExpect(jsonPath("$.law.shortName").value("Law 25"))
                    .andExpect(jsonPath("$.law.extensionDays").value(0))
                    .andExpect(jsonPath("$.subjectKind").value("customer"));
            var body = result.andReturn().getResponse().getContentAsString();
            Instant due = Instant.parse(JsonPath.read(body, "$.dueAt"));
            Instant scheduled = Instant.parse(JsonPath.read(body, "$.scheduledFor"));
            assertThat(Duration.between(before, due).toDays()).isBetween(29L, 31L);
            assertThat(Duration.between(before, scheduled).toDays()).isBetween(6L, 7L);

            mvc.perform(get("/api/v1/me/profile").with(TestJwt.customer(person)))
                    .andExpect(jsonPath("$.erasureRequestedAt").isNotEmpty());
            assertThat(jdbc.sql("""
                            select string_agg(action, ',' order by at) from developer.audit_log
                             where target_type = 'privacy_request' and target_id = :id
                            """).param("id", id(result)).query(String.class).single())
                    .isEqualTo("privacy.request_opened");

            open("{\"type\":\"erasure\"}", "dev")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("request_open"))
                    .andExpect(jsonPath("$.detail").value("You already asked for this. We're working on it."));

            mvc.perform(post(PATH + "/{id}/withdraw", id(result)).with(TestJwt.customer(person)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("withdrawn"));
            mvc.perform(get("/api/v1/me/profile").with(TestJwt.customer(person)))
                    .andExpect(jsonPath("$.erasureRequestedAt").isEmpty());
        }

        @Test
        void withoutAProofACodeIsTexted_aWrongOneCounts_theRightOneVerifies() throws Exception {
            var result = open("{\"type\":\"erasure\"}", "")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.state").value("awaiting_verification"))
                    .andExpect(jsonPath("$.codeSentTo").value("•••• " + phone.substring(phone.length() - 4)))
                    .andExpect(jsonPath("$.law.code").value("ab_pipa")); // no address: the default province
            var id = id(result);
            var code = lastCode();
            var wrong = code.equals("000000") ? "111111" : "000000";

            mvc.perform(post(PATH + "/{id}/verify", id)
                            .with(TestJwt.customer(person))
                            .header("Accept-Language", "fr-CA")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + wrong + "\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("code"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Ce code ne correspond pas. Vérifiez le texto et réessayez."));
            assertThat(jdbc.sql("select code_attempts from privacy.requests where id = :id")
                            .param("id", id)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
            mvc.perform(post(PATH + "/{id}/verify", id)
                            .with(TestJwt.customer(person))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"12ab\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Enter the 6-digit code we texted you."));
            mvc.perform(post(PATH + "/{id}/verification-code", id).with(TestJwt.customer(person)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("code_too_soon"));

            mvc.perform(post(PATH + "/{id}/verify", id)
                            .with(TestJwt.customer(person))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + code + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("verified"))
                    .andExpect(jsonPath("$.verification").value("code"));
            mvc.perform(post(PATH + "/{id}/verify", id)
                            .with(TestJwt.customer(person))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + code + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("not_awaiting"));
        }

        @Test
        void aStaleProofIs403StepUpRequired_inTheCallersLanguage() throws Exception {
            open("{\"type\":\"erasure\"}", "not-a-proof")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"))
                    .andExpect(jsonPath("$.detail")
                            .value("Confirm it's you with your passkey or authenticator app, then try again."));
            var id = id(open("{\"type\":\"erasure\"}", "").andExpect(status().isCreated()));
            mvc.perform(post(PATH + "/{id}/verify", id)
                            .with(TestJwt.customer(person))
                            .header("X-Step-Up", "stale")
                            .header("Accept-Language", "fr"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value(containsString("clé d’accès")));
            mvc.perform(post(PATH + "/{id}/verify", id)
                            .with(TestJwt.customer(person))
                            .header("X-Step-Up", "dev"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verification").value("step_up"));
        }
    }

    @Nested
    class Access {

        @Test
        void theExportIsBuiltSealedAndDownloadedThroughAShortLivedLink() throws Exception {
            jdbc.sql("insert into account.preferences (user_id, allergies) values (:u, 'sesame')")
                    .param("u", person)
                    .update();
            var id = id(open("{\"type\":\"access\"}", "dev")
                    .andExpect(jsonPath("$.state").value("verified")));
            mvc.perform(post(PATH + "/{id}/download-link", id).with(TestJwt.customer(person)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("export_not_ready"));

            assertThat(work.run(id)).isTrue();

            mvc.perform(get(PATH + "/{id}", id).with(TestJwt.customer(person)))
                    .andExpect(jsonPath("$.state").value("completed"))
                    .andExpect(jsonPath("$.export.ready").value(true));
            var stored = jdbc.sql("select export_key from privacy.requests where id = :id")
                    .param("id", id)
                    .query(String.class)
                    .single();
            assertThat(stored).isEqualTo("exports/" + id + ".bin");

            var link = mvc.perform(post(PATH + "/{id}/download-link", id).with(TestJwt.customer(person)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value(startsWith("/api/v1/public/privacy-exports/")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String url = JsonPath.read(link, "$.url");
            String summaryUrl = JsonPath.read(link, "$.summaryUrl");

            mvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString(".json")))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.format").value("northline.privacy-export/v1"))
                    .andExpect(jsonPath("$.subjectId").value(person))
                    .andExpect(jsonPath("$.law.code").value("ab_pipa"))
                    .andExpect(jsonPath("$.sections['identity.account'][0].first_name")
                            .value("Amara"))
                    .andExpect(jsonPath("$.sections['account.preferences'][0].allergies")
                            .value("sesame"));
            mvc.perform(get(summaryUrl))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", startsWith("text/plain")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .string(containsString("What we hold about you:")));
            mvc.perform(get("/api/v1/public/privacy-exports/" + "A".repeat(43))).andExpect(status().isNotFound());

            jdbc.sql("update privacy.requests set link_expires_at = now() - interval '1 second' where id = :id")
                    .param("id", id)
                    .update();
            mvc.perform(get(url)).andExpect(status().isNotFound());

            jdbc.sql("update privacy.requests set export_expires_at = now() - interval '1 second' where id = :id")
                    .param("id", id)
                    .update();
            assertThat(work.sweep()).isPositive();
            mvc.perform(post(PATH + "/{id}/download-link", id).with(TestJwt.customer(person)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("export_gone"));
            assertThat(jdbc.sql("""
                            select string_agg(action, ',' order by at, id) from developer.audit_log
                             where target_id = :id
                            """).param("id", id).query(String.class).single())
                    .contains(
                            "privacy.request_opened",
                            "privacy.request_export_ready",
                            "privacy.request_link_issued",
                            "privacy.request_export_downloaded",
                            "privacy.request_export_deleted");
        }
    }

    @Nested
    class Correction {

        @Test
        void asksForFieldsPeopleCantChange_withExactMessages() throws Exception {
            mvc.perform(get(PATH + "/correctable-fields").with(TestJwt.customer(person)))
                    .andExpect(jsonPath("$.items", hasItems("phone", "receiptName", "reviewName", "legalName")));
            open("{\"type\":\"correction\"}", "dev")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("corrections"))
                    .andExpect(jsonPath("$.errors[0].message").value("Say what to correct."));
            open("{\"type\":\"correction\",\"corrections\":[{\"field\":\"shoeSize\",\"value\":\"9\"}]}", "dev")
                    .andExpect(jsonPath("$.errors[0].field").value("corrections[0].field"))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose a detail we can correct."));
            open("{\"type\":\"refund\"}", "dev")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Choose what you're asking for: a copy, a correction or deletion."));

            var id = id(open(
                            "{\"type\":\"correction\",\"corrections\":[{\"field\":\"phone\",\"value\":\"+15875550100\"}],"
                                    + "\"note\":\"New number\"}",
                            "dev")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.corrections[0].field").value("phone"))
                    .andExpect(jsonPath("$.note").value("New number")));
            assertThat(jdbc.sql("select sealed_data is not null from privacy.requests where id = :id")
                            .param("id", id)
                            .query(Boolean.class)
                            .single())
                    .as("what the person asked is sealed")
                    .isTrue();
        }
    }

    @Test
    void someoneElsesRequestIs404_andSignedOutIs401() throws Exception {
        var id = id(open("{\"type\":\"access\"}", "dev"));
        var stranger = data.user("Stranger");
        mvc.perform(get(PATH + "/{id}", id).with(TestJwt.customer(stranger))).andExpect(status().isNotFound());
        mvc.perform(post(PATH + "/{id}/withdraw", id).with(TestJwt.customer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(TestJwt.customer(person)))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].corrections").isEmpty());
    }
}
