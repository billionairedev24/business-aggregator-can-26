package ca.northline.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.fulfilment.application.ProofStorage;
import ca.northline.messaging.application.AttachmentStorage;
import ca.northline.payments.application.DisputeEvidenceStorage;
import ca.northline.privacy.application.PrivacyRequests.Actor;
import ca.northline.privacy.application.PrivacyWork;
import ca.northline.privacy.application.Retention;
import ca.northline.privacy.application.RetentionCatalogue;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-107: every retention job against rows of its own module — past their period or not, held or not — in a dry run
 * (nothing changes, the count is right) and a real run (the right rows go, objects in storage too, holds respected,
 * a second run changes nothing); the law of the person's province keeping what decided a dispute; the overdue erasure
 * run through the S-105 pipeline; the report, its CSV, runs from the console, who may see and run, the audit log and
 * the metrics.
 */
class RetentionJobsTest extends IntegrationTest {

    static final String PATH = "/api/v1/console/retention";
    static final String POINT = "SRID=4326;POINT(0 0)";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Retention.Desk desk;

    @Autowired
    Retention.Work work;

    @Autowired
    RetentionCatalogue catalogue;

    @Autowired
    PrivacyWork privacy;

    @Autowired
    AttachmentStorage attachments;

    @Autowired
    DisputeEvidenceStorage evidence;

    @Autowired
    ProofStorage proofs;

    @Autowired
    MeterRegistry meters;

    final Actor officer = new Actor(Ids.next(), "privacy");

    private Retention.RunView run(String category, boolean dryRun) {
        var runs = desk.run(new Retention.Command(dryRun, category), officer);
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().outcome()).isEqualTo("succeeded");
        return runs.getFirst();
    }

    private void sql(String sql, Object... params) {
        jdbc.sql(sql).params(params).update();
    }

    private @Nullable Object value(String sql, Object... params) {
        return jdbc.sql(sql)
                .params(params)
                .query((rs, _) -> java.util.Optional.ofNullable(rs.getObject(1)))
                .single()
                .orElse(null);
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    /** A person whose default address is in {@code province} (whose privacy law applies to them). */
    private String person(String province) {
        var id = data.user("Retention " + province);
        sql(
                "insert into identity.addresses (id, user_id, street, city, province, is_default) values (?, ?, '1 Main St', 'Town', ?, true)",
                Ids.next(),
                id,
                province);
        return id;
    }

    @Test
    void signInsGoAfterTwelveMonths_notThoseOfSomeoneWithAnOpenRequest() throws Exception {
        var user = data.user("Sign In");
        var old = Ids.next();
        var recent = Ids.next();
        sql(
                "insert into identity.sessions (id, user_id, device, method, created_at, last_seen_at) "
                        + "values (?, ?, 'Pixel', 'passkey', now() - interval '14 months', now() - interval '13 months')",
                old,
                user);
        sql(
                "insert into identity.sessions (id, user_id, device, method, created_at, last_seen_at) "
                        + "values (?, ?, 'Pixel', 'passkey', now() - interval '14 months', now() - interval '20 days')",
                recent,
                user);
        var asking = data.user("Asking");
        var kept = Ids.next();
        sql(
                "insert into identity.sessions (id, user_id, device, method, created_at, revoked_at) "
                        + "values (?, ?, 'iPhone', 'passkey', now() - interval '2 years', now() - interval '13 months')",
                kept,
                asking);
        mvc.perform(post("/api/v1/me/privacy-requests")
                        .with(TestJwt.customer(asking))
                        .header("X-Step-Up", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"access\"}"))
                .andExpect(status().isCreated());

        var dry = run("identity.sign_ins", true);
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.affected()).isPositive();
        assertThat(dry.held()).isPositive();
        assertThat(count("select count(*) from identity.sessions where id = ?", old))
                .isOne();

        var real = run("identity.sign_ins", false);
        assertThat(real.affected()).isPositive();
        assertThat(count("select count(*) from identity.sessions where id in (?, ?, ?)", old, recent, kept))
                .isEqualTo(2);
        assertThat(count("select count(*) from identity.sessions where id = ?", old))
                .isZero();
        assertThat(run("identity.sign_ins", false).affected()).as("idempotent").isZero();
        assertThat(meters.counter("northline.retention.rows", "category", "identity.sign_ins", "action", "delete")
                        .count())
                .isPositive();
    }

    @Test
    void conversationsGoTwoYearsAfterTheirLastMessage_withTheirFiles_unlessAnOrderOrADisputeHoldsThem() {
        var merchant = data.merchant("seller", "Retention Parts");
        var customer = data.user("Customer");
        var old = thread(merchant, customer, "order", Ids.next(), "3 years");
        var key = merchant + "/" + Ids.next() + ".jpg";
        attachments.put(key, new byte[] {1, 2, 3}, "image/jpeg");
        var file = Ids.next();
        sql(
                "insert into messaging.attachments (id, merchant_id, storage_key, file_name, content_type, byte_size, "
                        + "uploaded_by) values (?, ?, ?, 'a.jpg', 'image/jpeg', 3, ?)",
                file,
                merchant,
                key,
                customer);
        sql("update messaging.messages set attachments = array[?] where thread_id = ?", file, old);
        var recent = thread(merchant, customer, "order", Ids.next(), "1 year");
        var openOrder = Ids.next();
        sql(
                "insert into orders.orders (id, customer_id, type, state, placed_at) values (?, ?, 'goods', 'placed', "
                        + "now() - interval '3 years')",
                openOrder,
                customer);
        var held = thread(merchant, customer, "order", openOrder, "3 years");
        var dispute = Ids.next();
        sql(
                "insert into payments.disputes (id, opened_by, state, decided_at, opened_at, case_number) "
                        + "values (?, ?, 'decided', now() - interval '6 months', now() - interval '3 years', ?)",
                dispute,
                customer,
                "DS-" + dispute);
        var disputed = thread(merchant, customer, "dispute", dispute, "3 years");

        var dry = run("messaging.conversations", true);
        assertThat(dry.affected()).isPositive();
        assertThat(dry.held()).isGreaterThanOrEqualTo(2);

        run("messaging.conversations", false);
        assertThat(count("select count(*) from messaging.threads where id = ?", old))
                .isZero();
        assertThat(count("select count(*) from messaging.messages where thread_id = ?", old))
                .isZero();
        assertThat(count("select count(*) from messaging.attachments where id = ?", file))
                .isZero();
        assertThat(attachments.get(key)).isEmpty();
        assertThat(count("select count(*) from messaging.threads where id in (?, ?, ?)", recent, held, disputed))
                .isEqualTo(3);

        sql("update payments.disputes set decided_at = now() - interval '13 months' where id = ?", dispute);
        run("messaging.conversations", false);
        assertThat(count("select count(*) from messaging.threads where id = ?", disputed))
                .as("a year after the decision")
                .isZero();
    }

    private String thread(String merchant, String customer, String refType, String refId, String age) {
        var id = Ids.next();
        sql(
                "insert into messaging.threads (id, merchant_id, kind, ref_type, ref_id, counterpart_id, counterpart_name, "
                        + "last_message_at, created_at) values (?, ?, 'customer', ?, ?, ?, 'A. Customer', now() - cast(? as "
                        + "interval), now() - cast(? as interval))",
                id,
                merchant,
                refType,
                refId,
                customer,
                age,
                age);
        sql(
                "insert into messaging.messages (id, thread_id, sender_id, body, at, sender_role) "
                        + "values (?, ?, ?, 'Is it ready?', now() - cast(? as interval), 'customer')",
                Ids.next(),
                id,
                customer,
                age);
        return id;
    }

    @Test
    void helpCasesLoseTheirConversationAndWordsTwoYearsAfterResolution_photosTwoYearsAfterUpload() {
        var customer = data.user("Asker");
        var ticket = Ids.next();
        sql(
                "insert into messaging.tickets (id, requester_type, requester_id, topic, state, subject, context, "
                        + "resolution_note, resolved_at, created_at) values (?, 'customer', ?, 'order', 'resolved', 'Broken', "
                        + "'{\"portal\":\"consumer\"}', 'in your favour', now() - interval '3 years', now() - interval '3 years')",
                ticket,
                customer);
        var caseThread = Ids.next();
        sql(
                "insert into messaging.threads (id, kind, ref_type, ref_id, last_message_at, created_at) "
                        + "values (?, 'case', 'ticket', ?, now() - interval '3 years', now() - interval '3 years')",
                caseThread,
                ticket);
        var key = "customers/" + customer + "/" + Ids.next() + ".jpg";
        attachments.put(key, new byte[] {4}, "image/jpeg");
        var upload = Ids.next();
        sql(
                "insert into messaging.customer_uploads (id, customer_id, storage_key, file_name, content_type, byte_size, "
                        + "created_at) values (?, ?, ?, 'p.jpg', 'image/jpeg', 1, now() - interval '25 months')",
                upload,
                customer,
                key);

        run("messaging.help_cases", false);
        assertThat(count("select count(*) from messaging.threads where id = ?", caseThread))
                .isZero();
        assertThat(value("select subject from messaging.tickets where id = ?", ticket))
                .isNull();
        assertThat(value("select resolution_note from messaging.tickets where id = ?", ticket))
                .isNull();
        assertThat(value("select state from messaging.tickets where id = ?", ticket))
                .isEqualTo("resolved");
        assertThat(count("select count(*) from messaging.customer_uploads where id = ?", upload))
                .isZero();
        assertThat(attachments.get(key)).isEmpty();
    }

    @Test
    void checkInLocationsGoAfterNinetyDays_orWhenTheDisputeIsDecided_plusWhatTheProvincesLawAdds() {
        var merchant = data.merchant("provider", "Retention Wrench");
        var tech = data.user("Tech");
        var plain = booking(merchant, data.user("Plain"), "completed");
        var plainOld = event(plain, tech, "100 days");
        var plainRecent = event(plain, tech, "10 days");
        var upcoming = booking(merchant, data.user("Soon"), "confirmed");
        var upcomingOld = event(upcoming, tech, "120 days");
        var bcCustomer = person("BC");
        var bc = booking(merchant, bcCustomer, "completed");
        var bcOld = event(bc, tech, "120 days");
        decidedDispute("booking", bc, bcCustomer, "100 days");
        var abCustomer = person("AB");
        var ab = booking(merchant, abCustomer, "completed");
        var abOld = event(ab, tech, "120 days");
        decidedDispute("booking", ab, abCustomer, "100 days");
        var open = booking(merchant, data.user("Open"), "completed");
        var openOld = event(open, tech, "120 days");
        openDispute("booking", open);

        run("booking.checkin_locations", false);
        assertThat(located(plainOld)).isFalse();
        assertThat(located(plainRecent)).isTrue();
        assertThat(located(upcomingOld)).as("upcoming booking").isTrue();
        assertThat(located(bcOld))
                .as("BC PIPA: a year after the decision (region.privacy_laws)")
                .isTrue();
        assertThat(located(abOld))
                .as("no minimum: gone once the dispute is decided")
                .isFalse();
        assertThat(located(openOld)).as("open dispute").isTrue();
        assertThat(count("select count(*) from booking.booking_events where id = ?", plainOld))
                .as("the transition stays")
                .isOne();
    }

    private String booking(String merchant, String customer, String state) {
        var id = Ids.next();
        sql(
                "insert into booking.bookings (id, customer_id, merchant_id, state, starts_at, ends_at, address_line, details) "
                        + "values (?, ?, ?, ?, now() - interval '200 days', now() - interval '200 days', '1 Main St', "
                        + "'{\"note\":\"gate code\"}')",
                id,
                customer,
                merchant,
                state);
        return id;
    }

    private String event(String booking, String actor, String age) {
        var id = Ids.next();
        sql(
                "insert into booking.booking_events (id, booking_id, type, at, actor_id, geom) "
                        + "values (?, ?, 'on_site', now() - cast(? as interval), ?, cast(? as geography))",
                id,
                booking,
                age,
                actor,
                POINT);
        return id;
    }

    private boolean located(String event) {
        return count("select count(*) from booking.booking_events where id = ? and geom is not null", event) == 1;
    }

    /** A dispute decided {@code ago} about a booking or an order line, through its escrow. */
    private String decidedDispute(String refType, String refId, String customer, String ago) {
        var escrow = Ids.next();
        sql(
                "insert into payments.escrows (id, ref_type, ref_id, state, customer_id, occurred_at, created_at) "
                        + "values (?, ?, ?, 'refunded', ?, now() - interval '3 years', now() - interval '3 years')",
                escrow,
                refType,
                refId,
                customer);
        var dispute = Ids.next();
        sql(
                "insert into payments.disputes (id, ref_id, opened_by, state, decided_at, opened_at, case_number) "
                        + "values (?, ?, ?, 'decided', now() - cast(? as interval), now() - interval '3 years', ?)",
                dispute,
                escrow,
                customer,
                ago,
                "DS-" + dispute);
        return dispute;
    }

    private void openDispute(String refType, String refId) {
        var escrow = Ids.next();
        sql(
                "insert into payments.escrows (id, ref_type, ref_id, state, created_at) values (?, ?, ?, 'disputed', now())",
                escrow,
                refType,
                refId);
        var dispute = Ids.next();
        sql(
                "insert into payments.disputes (id, ref_id, state, opened_at, case_number) values (?, ?, 'open', now(), ?)",
                dispute,
                escrow,
                "DS-" + dispute);
    }

    @Test
    void disputeStatementsAndEvidenceFilesGoTwoYearsAfterTheTransaction_aYearAfterTheDecision() {
        var customer = person("AB");
        var key = Ids.next() + "/" + Ids.next() + ".pdf";
        evidence.put(key, new byte[] {9}, "application/pdf");
        var old = decidedDispute("booking", Ids.next(), customer, "400 days");
        sql(
                "update payments.disputes set customer_statement = 'It leaked', response = 'It did not', "
                        + "evidence = cast(? as jsonb) where id = ?",
                "[{\"id\":\"e1\",\"kind\":\"photo\",\"name\":\"leak.pdf\",\"storageKey\":\"" + key + "\"}]",
                old);
        var recent = decidedDispute("booking", Ids.next(), customer, "200 days");
        sql("update payments.disputes set customer_statement = 'Late' where id = ?", recent);

        var dry = run("payments.dispute_evidence", true);
        assertThat(dry.affected()).isPositive();
        assertThat(dry.held()).isPositive();

        run("payments.dispute_evidence", false);
        assertThat(value("select customer_statement from payments.disputes where id = ?", old))
                .isNull();
        assertThat(value("select evidence::text from payments.disputes where id = ?", old))
                .isEqualTo("[]");
        assertThat(value("select state from payments.disputes where id = ?", old))
                .isEqualTo("decided");
        assertThat(evidence.get(key)).isEmpty();
        assertThat(value("select customer_statement from payments.disputes where id = ?", recent))
                .isEqualTo("Late");
    }

    @Test
    void deliveryProofsGoTwoYearsAfterTheDropOff() {
        var order = Ids.next();
        var key = Ids.next() + ".jpg";
        proofs.put(key, new byte[] {7}, "image/jpeg");
        var stop = Ids.next();
        sql(
                "insert into fulfilment.stops (id, order_id, kind, seq, state, done_at, proof_kind, proof_media_id) "
                        + "values (?, ?, 'dropoff', 1, 'done', now() - interval '25 months', 'photo', ?)",
                stop,
                order,
                key);

        run("fulfilment.delivery_proofs", false);
        assertThat(value("select proof_media_id from fulfilment.stops where id = ?", stop))
                .isNull();
        assertThat(value("select proof_kind from fulfilment.stops where id = ?", stop))
                .isEqualTo("photo");
        assertThat(proofs.get(key)).isEmpty();
    }

    @Test
    void salesRecordsLoseThePersonAfterSevenYears_amountsStay_openOrdersHold() {
        var customer = data.user("Old Customer");
        var old = Ids.next();
        sql(
                "insert into orders.orders (id, customer_id, type, state, placed_at, delivery_area, subtotal_cents, tax_cents) "
                        + "values (?, ?, 'goods', 'delivered', now() - interval '8 years', 'Downtown', 1000, 50)",
                old,
                customer);
        var open = Ids.next();
        sql(
                "insert into orders.orders (id, customer_id, type, state, placed_at) "
                        + "values (?, ?, 'goods', 'placed', now() - interval '8 years')",
                open,
                customer);
        var merchant = data.merchant("provider", "Retention Seven");
        var booking = Ids.next();
        sql(
                "insert into booking.bookings (id, customer_id, merchant_id, state, starts_at, ends_at, address_line, "
                        + "price_cents, details) values (?, ?, ?, 'completed', now() - interval '8 years', now() - interval "
                        + "'8 years', '1 Main St', 9000, '{\"note\":\"x\"}')",
                booking,
                customer,
                merchant);
        var escrow = Ids.next();
        sql(
                "insert into payments.escrows (id, ref_type, ref_id, state, customer_id, customer_name, amount_cents, "
                        + "created_at) values (?, 'booking', ?, 'released', ?, 'O. Customer', 9000, now() - interval '8 years')",
                escrow,
                booking,
                customer);

        run("orders.sales_records", false);
        run("booking.sales_records", false);
        run("payments.financial_records", false);
        assertThat(value("select customer_id from orders.orders where id = ?", old))
                .isNull();
        assertThat(value("select subtotal_cents from orders.orders where id = ?", old))
                .isEqualTo(1000L);
        assertThat(value("select customer_id from orders.orders where id = ?", open))
                .isEqualTo(customer);
        assertThat(value("select customer_id from booking.bookings where id = ?", booking))
                .isNull();
        assertThat(value("select address_line from booking.bookings where id = ?", booking))
                .isNull();
        assertThat(value("select price_cents from booking.bookings where id = ?", booking))
                .isEqualTo(9000L);
        assertThat(value("select customer_name from payments.escrows where id = ?", escrow))
                .isNull();
        assertThat(value("select amount_cents from payments.escrows where id = ?", escrow))
                .isEqualTo(9000L);
    }

    @Test
    void theAuditLogGoesAfterSevenYears() {
        var old = Ids.next();
        var recent = Ids.next();
        sql(
                "insert into developer.audit_log (id, action, at) values (?, 'test.retention', now() - interval '8 years')",
                old);
        sql(
                "insert into developer.audit_log (id, action, at) values (?, 'test.retention', now() - interval '6 years')",
                recent);
        run("developer.audit_log", false);
        assertThat(count("select count(*) from developer.audit_log where id = ?", old))
                .isZero();
        assertThat(count("select count(*) from developer.audit_log where id = ?", recent))
                .isOne();
    }

    @Test
    void anErasureStillUnfinishedThirtyDaysAfterTheAccountClosedRunsThroughThePipelineNow() throws Exception {
        var person = data.user("Closing");
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
        mvc.perform(post("/api/v1/console/privacy-requests/{id}/start", id)
                        .with(TestJwt.staff(data.user("Officer"), StaffRole.PRIVACY)))
                .andExpect(status().isOk());
        privacy.run(id);
        sql("update privacy.requests set started_at = now() - interval '40 days' where id = ?", id);
        sql(
                "update privacy.erasure_steps set status = 'failed', next_attempt_at = now() + interval '6 hours', "
                        + "done_at = null where request_id = ? and module = 'account'",
                id);

        assertThat(run("account.closed_profile", true).affected()).isPositive();
        run("account.closed_profile", false);
        assertThat(value("select status from privacy.erasure_steps where request_id = ? and module = 'account'", id))
                .isEqualTo("done");
    }

    @Test
    void theNightlyRunRunsEveryCategoryOnce_andRecordsEachRun() {
        work.runScheduled();
        assertThat(work.runScheduled()).as("already ran tonight").isZero();
        var report = desk.report(Locale.CANADA);
        assertThat(report.categories()).hasSize(catalogue.categories().size());
        for (var category : report.categories()) {
            if (category.enforcement() == RetentionCatalogue.Enforcement.JOB
                    || category.enforcement() == RetentionCatalogue.Enforcement.PIPELINE) {
                assertThat(category.lastRun()).as(category.code()).isNotNull();
                assertThat(category.lastSuccessAt()).as(category.code()).isNotNull();
                assertThat(category.nextDueAt()).as(category.code()).isNotNull();
                assertThat(category.overdue()).isFalse();
            } else {
                assertThat(category.nextDueAt()).as(category.code()).isNull();
            }
        }
        assertThat(count("select count(*) from developer.audit_log where action = 'privacy.retention_run' "
                        + "and target_id = 'identity.sign_ins' and actor_id = 'system'"))
                .isPositive();
        assertThat(meters.find("northline.retention.last_success")
                        .tag("category", "identity.sign_ins")
                        .gauge())
                .isNotNull();
    }

    @Test
    void theReportIsForThePrivacyScreen_runsNeedThePrivacyGrant() throws Exception {
        var staff = data.user("Staff");
        mvc.perform(get(PATH).with(TestJwt.customer(staff))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(TestJwt.staffWithoutMfa(staff, StaffRole.PRIVACY)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(get(PATH).with(TestJwt.staff(staff, StaffRole.ANALYST))).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/export").with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/runs")
                        .with(TestJwt.staff(staff, StaffRole.SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true}"))
                .andExpect(status().isForbidden());

        mvc.perform(get(PATH).with(TestJwt.staff(staff, StaffRole.PRIVACY)).header("Accept-Language", "fr-CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()")
                        .value(catalogue.categories().size()))
                .andExpect(jsonPath("$.categories[?(@.code == 'identity.sign_ins')].period")
                        .value("P12M"))
                .andExpect(jsonPath("$.categories[?(@.code == 'identity.sign_ins')].name")
                        .value("Connexions (appareil, adresse réseau, ville)"))
                .andExpect(jsonPath("$.laws[?(@.code == 'bc_pipa')].decisionRetentionDays")
                        .value(365))
                .andExpect(jsonPath("$.operational.length()")
                        .value(catalogue.operational().size()));

        mvc.perform(get(PATH + "/export").with(TestJwt.staff(staff, StaffRole.SUPPORT_LEAD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"retention-report.csv\""))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("\"category\",\"module\",\"name\"")))
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("\"identity.sign_ins\",\"identity\"")));

        mvc.perform(post(PATH + "/runs")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true,\"category\":\"infrastructure.backups\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("category"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Choose a category of the retention schedule that has a job."));
        mvc.perform(post(PATH + "/runs")
                        .with(TestJwt.staff(staff, StaffRole.SUPPORT_LEAD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true,\"category\":\"developer.audit_log\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].category").value("developer.audit_log"))
                .andExpect(jsonPath("$.items[0].dryRun").value(true))
                .andExpect(jsonPath("$.items[0].trigger").value("staff"));
        assertThat(count(
                        "select count(*) from developer.audit_log where action = 'privacy.retention_run' "
                                + "and actor_id = ? and role = 'support_lead' and after->>'dryRun' = 'true'",
                        staff))
                .isOne();
    }
}
