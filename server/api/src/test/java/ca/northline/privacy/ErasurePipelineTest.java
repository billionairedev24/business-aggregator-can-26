package ca.northline.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.privacy.api.MerchantDataErased;
import ca.northline.privacy.api.PersonalDataErased;
import ca.northline.privacy.application.PrivacyWork;
import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * S-105, the erasure pipeline end to end: the account is closed at once (sign-ins ended), every module's contributor
 * runs in order in its own transaction with its step, a failing module is retried and resumes where it stopped, a hold
 * (an order on its way) completes the request with the hold open until it clears, the sealed contact is wiped at the
 * end, events carry ids only, and the request completes within the law's deadline. A test-only contributor fails once.
 */
@RecordApplicationEvents
@Import(ErasurePipelineTest.Flaky.class)
class ErasurePipelineTest extends IntegrationTest {

    /** Fails the first time it runs for each person — a crash or an outage halfway through the pipeline. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Flaky {

        static final Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();
        static final AtomicInteger RUNS = new AtomicInteger();

        @Bean
        PersonalDataContributor flakyContributor() {
            return new PersonalDataContributor() {
                @Override
                public String module() {
                    return "zzflaky";
                }

                @Override
                public int order() {
                    return 500;
                }

                @Override
                public List<Section> export(Subject subject) {
                    return List.of();
                }

                @Override
                public Erasure erase(Subject subject) {
                    RUNS.incrementAndGet();
                    if (FAILED.add(subject.userId())) {
                        throw new IllegalStateException("simulated outage");
                    }
                    return Erasure.done();
                }
            };
        }
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PrivacyWork work;

    @Autowired
    ApplicationEvents events;

    String person;
    String merchant;

    @BeforeEach
    void person() {
        person = Ids.next();
        jdbc.sql("""
                        insert into identity.users (id, first_name, last_name, display_name, email, phone, locale, status)
                        values (:id, 'Kofi', 'Mensah', 'Kofi Mensah', :email, null, 'en-CA', 'active')
                        """)
                .param("id", person)
                .param("email", "kofi-" + person.toLowerCase(java.util.Locale.ROOT) + "@example.ca")
                .update();
        jdbc.sql("""
                        insert into identity.sessions (id, user_id, device, method) values (:id, :u, 'Pixel', 'passkey')
                        """).param("id", Ids.next()).param("u", person).update();
        merchant = data.merchant("provider", "Prairie Wrench");
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating, text,
                                                   author_name, lang)
                        values (:id, 'booking', :ref, :u, 'merchant', :m, 5, 'Great work', 'Kofi M.', 'en')
                        """)
                .param("id", Ids.next())
                .param("ref", Ids.next())
                .param("u", person)
                .param("m", merchant)
                .update();
    }

    private String erasureStartedByStaff() throws Exception {
        var id = (String) JsonPath.read(
                mvc.perform(post("/api/v1/me/privacy-requests")
                                .with(TestJwt.customer(person))
                                .header("X-Step-Up", "dev")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"erasure\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        assertThat(work.run(id)).as("still in its grace period").isFalse();
        var officer = data.user("Officer");
        mvc.perform(post("/api/v1/console/privacy-requests/{id}/start", id)
                        .with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                .andExpect(status().isOk());
        return id;
    }

    private String stepStatus(String requestId, String module) {
        return jdbc.sql("select status from privacy.erasure_steps where request_id = :r and module = :m")
                .param("r", requestId)
                .param("m", module)
                .query(String.class)
                .single();
    }

    @Test
    void everyModuleRuns_aFailureIsRetried_andTheRequestCompletesWithinTheDeadline() throws Exception {
        var id = erasureStartedByStaff();

        assertThat(work.run(id)).as("the flaky module failed: not complete yet").isFalse();

        assertThat(jdbc.sql("select status from identity.users where id = :u")
                        .param("u", person)
                        .query(String.class)
                        .single())
                .isEqualTo("erased");
        assertThat(jdbc.sql("select revoke_reason from identity.sessions where user_id = :u")
                        .param("u", person)
                        .query(String.class)
                        .single())
                .isEqualTo("erased");
        assertThat(stepStatus(id, "zzflaky")).isEqualTo("failed");
        assertThat(stepStatus(id, "trust")).isEqualTo("done");
        assertThat(stepStatus(id, "identity"))
                .as("modules after a failed one still run")
                .isEqualTo("done");
        assertThat(jdbc.sql("select last_error from privacy.erasure_steps where request_id = :r and module = 'zzflaky'")
                        .param("r", id)
                        .query(String.class)
                        .single())
                .isEqualTo("IllegalStateException");
        assertThat(jdbc.sql("select count(*) from privacy.erasure_steps where request_id = :r")
                        .param("r", id)
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(15);

        // resumes after the back-off; done steps are not run again
        var runs = Flaky.RUNS.get();
        jdbc.sql("update privacy.erasure_steps set next_attempt_at = now() where request_id = :r and status = 'failed'")
                .param("r", id)
                .update();
        work.runDue();

        assertThat(Flaky.RUNS.get()).isEqualTo(runs + 1);
        var request = jdbc.sql("""
                        select state || '/' || holds_open || '/' || (sealed_data is null) || '/'
                               || (completed_at <= coalesce(extended_to, due_at))
                          from privacy.requests where id = :id
                        """).param("id", id).query(String.class).single();
        assertThat(request).isEqualTo("completed/0/true/true");
        assertThat(jdbc.sql("select concat_ws('|', first_name, email, display_name) from identity.users where id = :u")
                        .param("u", person)
                        .query(String.class)
                        .single())
                .isEmpty();
        assertThat(events.stream(MerchantDataErased.class)
                        .filter(e -> e.requestId().equals(id)))
                .extracting(MerchantDataErased::aggregateId)
                .containsExactly(merchant);
        assertThat(events.stream(PersonalDataErased.class)
                        .filter(e -> e.aggregateId().equals(id)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.subjectId()).isEqualTo(person);
                    assertThat(e.holds()).isZero();
                });

        var officer = data.user("Officer");
        mvc.perform(get("/api/v1/console/privacy-requests/{id}", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.steps[?(@.module == 'payments')].retained[0].reason")
                        .value(org.hamcrest.Matchers.hasItem("financial_records")))
                .andExpect(jsonPath("$.steps[-1].module").value("identity"));
        assertThat(jdbc.sql("""
                        select count(*) from developer.audit_log where target_id = :id and action = 'privacy.request_erasure_step'
                        """).param("id", id).query(Integer.class).single()).isGreaterThanOrEqualTo(15);
    }

    @Test
    void anOrderOnItsWayHoldsItsStep_theRequestCompletes_andTheHoldClearsLater() throws Exception {
        var order = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, customer_id, type, state, ref) values (:id, :u, 'goods', 'packing', 'NL-9')
                        """).param("id", order).param("u", person).update();
        Flaky.FAILED.add(person); // the flaky module has no outage for this person
        var id = erasureStartedByStaff();

        assertThat(work.run(id)).isTrue();

        assertThat(stepStatus(id, "orders")).isEqualTo("held");
        assertThat(jdbc.sql(
                                "select state || '/' || holds_open || '/' || (sealed_data is null) from privacy.requests where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .as("completed, one hold open, the contact still sealed for it")
                .isEqualTo("completed/1/false");

        jdbc.sql("update orders.orders set state = 'confirmed', confirmed_at = now() where id = :id")
                .param("id", order)
                .update();
        work.runDue();
        assertThat(stepStatus(id, "orders"))
                .as("not before the hold's back-off")
                .isEqualTo("held");

        jdbc.sql("update privacy.erasure_steps set next_attempt_at = now() where request_id = :r and status = 'held'")
                .param("r", id)
                .update();
        work.runDue();

        assertThat(stepStatus(id, "orders")).isEqualTo("done");
        assertThat(jdbc.sql("select holds_open || '/' || (sealed_data is null) from privacy.requests where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("0/true");
        assertThat(jdbc.sql("""
                        select count(*) from developer.audit_log where target_id = :id and action = 'privacy.request_holds_cleared'
                        """).param("id", id).query(Integer.class).single()).isEqualTo(1);
        assertThat(Map.of(
                        "events",
                        events.stream(PersonalDataErased.class)
                                .filter(e -> e.aggregateId().equals(id))
                                .map(PersonalDataErased::holds)
                                .toList()))
                .containsEntry("events", List.of(1, 0));
    }
}
