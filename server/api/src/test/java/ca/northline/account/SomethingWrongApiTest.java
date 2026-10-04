package ca.northline.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;

/**
 * S-60 "Something's wrong": what can be reported and until when (the escrow windows), the report opening refund cases
 * for review — never approved by the clock, never paid — and one case in Northline's queue, Help &amp; cases.
 */
class SomethingWrongApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PaymentsJobs jobs;

    AccountFixtures f;
    String amara;
    String greens;
    String bakery;
    String wrench;

    @BeforeEach
    void people() {
        f = new AccountFixtures(jdbc);
        amara = data.user("Amara Osei");
        greens = shopFixtures.shop("Calgary", "Sunnyside Greens", "trusted");
        bakery = shopFixtures.shop("Calgary", "Glenmore Bakery", "master");
        wrench = data.merchant("provider", "Prairie Wrench");
    }

    /** A delivered goods order (one line per shop) with each line's escrow held for 7 more days. */
    private AccountFixtures.Order delivered(long unit) {
        var now = Instant.now();
        var order = f.goodsOrder(
                amara,
                "delivered",
                now.minus(Duration.ofDays(2)),
                now.minus(Duration.ofDays(1)),
                List.of(greens, bakery),
                unit);
        f.escrow(
                amara,
                greens,
                "goods",
                "order_line",
                order.lineIds().get(0),
                unit,
                "held",
                now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(6)));
        f.escrow(
                amara,
                bakery,
                "goods",
                "order_line",
                order.lineIds().get(1),
                unit,
                "held",
                now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(6)));
        return order;
    }

    private String report(String body) throws Exception {
        return mvc.perform(post("/api/v1/me/problems")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void reportOpensRefundCasesForReview_andACaseForNorthline() throws Exception {
        var order = delivered(1000);
        mvc.perform(get("/api/v1/me/problems/order/{id}", order.id()).with(TestJwt.customer(amara)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].status").value("open"))
                .andExpect(jsonPath("$.items[0].amountCents").value(1000))
                .andExpect(jsonPath("$.items[0].taxCents").value(50))
                .andExpect(jsonPath("$.items[0].merchantName").value("Sunnyside Greens"))
                .andExpect(jsonPath("$.reasons[0]").value("missing"));

        var body = report("""
                {"kind":"order","id":"%s","items":["%s"],"reason":"damaged","note":"Crushed in the bag"}""".formatted(order.id(), order.lineIds().getFirst()));
        String refundId = JsonPath.read(body, "$.refunds[0].id");
        assertThat((String) JsonPath.read(body, "$.refunds[0].number")).startsWith("RF-");
        assertThat((String) JsonPath.read(body, "$.caseCode")).startsWith("HD-");
        assertThat((Integer) JsonPath.read(body, "$.totalCents")).isEqualTo(1050);

        var refund = jdbc.sql("select state, auto, amount_cents, what from payments.refunds where id = ?")
                .param(refundId)
                .query((rs, _) -> Map.of(
                        "state",
                        rs.getString(1),
                        "auto",
                        rs.getBoolean(2),
                        "amount",
                        rs.getLong(3),
                        "what",
                        rs.getString(4)))
                .single();
        assertThat(refund)
                .containsEntry("state", "seller_review")
                .containsEntry("auto", false)
                .containsEntry("amount", 1000L);
        assertThat((String) refund.get("what")).isEqualTo("Damaged · Kale bunch — Crushed in the bag");
        assertThat(jdbc.sql("select state from payments.escrows where ref_id = ?")
                        .param(order.lineIds().getFirst())
                        .query(String.class)
                        .single())
                .isEqualTo("disputed");
        var ticket = jdbc.sql("""
                        select requester_type, merchant_id, topic, state, context ->> 'triageCategory',
                               (context -> 'refunds') ->> 0
                          from messaging.tickets where requester_id = ?""")
                .param(amara)
                .query((rs, _) -> new String[] {
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)
                })
                .single();
        assertThat(ticket).containsExactly("customer", null, "refund", "new", "damaged", refundId);

        // the same line again
        mvc.perform(post("/api/v1/me/problems")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"order\",\"id\":\"%s\",\"items\":[\"%s\"],\"reason\":\"missing\"}"
                                .formatted(order.id(), order.lineIds().getFirst())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_reported"));

        // a small refund is never approved by the clock: past the 24 h it goes to an agent
        jdbc.sql("update payments.refunds set contest_by = now() - interval '1 minute' where id = ?")
                .param(refundId)
                .update();
        jobs.lapseCases();
        assertThat(jdbc.sql("select state from payments.refunds where id = ?")
                        .param(refundId)
                        .query(String.class)
                        .single())
                .isEqualTo("agent_review");
    }

    @Test
    void windows_closedNotYetAndNothingPaid() throws Exception {
        var now = Instant.now();
        var order = f.goodsOrder(
                amara,
                "confirmed",
                now.minus(Duration.ofDays(9)),
                now.minus(Duration.ofDays(8)),
                List.of(greens),
                1000);
        f.escrow(
                amara,
                greens,
                "goods",
                "order_line",
                order.lineIds().getFirst(),
                1000,
                "released",
                now.minus(Duration.ofDays(8)),
                now.minus(Duration.ofDays(1)));
        mvc.perform(get("/api/v1/me/problems/order/{id}", order.id()).with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.status").value("closed"));
        mvc.perform(post("/api/v1/me/problems")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"order\",\"id\":\"%s\",\"items\":[\"%s\"],\"reason\":\"late\"}"
                                .formatted(order.id(), order.lineIds().getFirst())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("window_closed"));

        var packing = f.goodsOrder(amara, "packing", now, null, List.of(greens), 1000);
        f.escrow(amara, greens, "goods", "order_line", packing.lineIds().getFirst(), 1000, "held", null, null);
        mvc.perform(get("/api/v1/me/problems/order/{id}", packing.id()).with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.status").value("not_yet"));

        var unpaid = f.goodsOrder(amara, "delivered", now, now, List.of(greens), 1000);
        mvc.perform(get("/api/v1/me/problems/order/{id}", unpaid.id()).with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.status").value("not_paid"));
    }

    @Test
    void aJob_isReportedAsOne_andSafetyIsUrgent() throws Exception {
        var now = Instant.now();
        var job = f.booking(
                amara, wrench, null, "Brake inspection", "completed", now.minus(Duration.ofHours(5)), 8900, null, 0);
        f.escrow(
                amara,
                wrench,
                "service",
                "booking",
                job,
                8900,
                "held",
                now.minus(Duration.ofHours(3)),
                now.plus(Duration.ofHours(45)));
        mvc.perform(get("/api/v1/me/problems/booking/{id}", job).with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.reasons[0]").value("not_done"))
                .andExpect(jsonPath("$.items[0].amountCents").value(8900));
        var body = report("""
                {"kind":"booking","id":"%s","items":["%s"],"reason":"poor_quality","note":"Brakes still grinding",
                 "triageCategory":"safety","triageSummary":"Brakes grind after the inspection."}""".formatted(job, job));
        assertThat((Integer) JsonPath.read(body, "$.refunds.length()")).isEqualTo(1);
        var ticket = jdbc.sql(
                        "select priority, urgent, context ->> 'triageSummary' from messaging.tickets where requester_id = ?")
                .param(amara)
                .query((rs, _) -> new Object[] {rs.getString(1), rs.getBoolean(2), rs.getString(3)})
                .single();
        assertThat(ticket).containsExactly("urgent", true, "Brakes grind after the inspection.");
    }

    @Test
    void foodIsReleasedAtHandoff_butCanBeReportedForADay_inOneCase() throws Exception {
        var now = Instant.now();
        var order = f.goodsOrder(
                amara,
                "delivered",
                now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofHours(2)),
                List.of(greens, greens),
                1200);
        jdbc.sql("update orders.orders set type = 'food', fulfilment_mode = 'delivery' where id = ?")
                .param(order.id())
                .update();
        f.escrow(
                amara,
                greens,
                "food",
                "food_order",
                order.id(),
                2400,
                "released",
                now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(2)));
        mvc.perform(get("/api/v1/me/problems/food/{id}", order.id()).with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.status").value("open"));
        var body = report("""
                {"kind":"food","id":"%s","items":["%s","%s"],"reason":"missing"}""".formatted(
                        order.id(), order.lineIds().get(0), order.lineIds().get(1)));
        assertThat((Integer) JsonPath.read(body, "$.refunds.length()")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.refunds[0].amountCents")).isEqualTo(2400);
        // it's not an order of shops
        mvc.perform(get("/api/v1/me/problems/order/{id}", order.id()).with(TestJwt.customer(amara)))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationAndOwnership() throws Exception {
        var order = delivered(1000);
        mvc.perform(post("/api/v1/me/problems")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"order\",\"id\":\"%s\",\"items\":[],\"reason\":\"\"}"
                                .formatted(order.id())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='reason')].message").value("Pick what went wrong."));
        mvc.perform(post("/api/v1/me/problems")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"order\",\"id\":\"%s\",\"items\":[],\"reason\":\"damaged\"}"
                                .formatted(order.id())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick at least one item."));
        mvc.perform(post("/api/v1/me/problems")
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"order\",\"id\":\"%s\",\"items\":[\"%s\"],\"reason\":\"no_show\"}"
                                .formatted(order.id(), order.lineIds().getFirst())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick what went wrong."));
        var stranger = data.user("Stranger");
        mvc.perform(get("/api/v1/me/problems/order/{id}", order.id()).with(TestJwt.customer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/problems/order/{id}", order.id())).andExpect(status().isUnauthorized());
    }

    @Test
    void photos_helpAndCases_andANote() throws Exception {
        var order = delivered(446);
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", out);
        var jpeg = out.toByteArray();
        // S-104: a JPEG must be a readable image, not only start like one (photos come from the apps now too)
        mvc.perform(multipart("/api/v1/me/case-uploads")
                        .file(new MockMultipartFile(
                                "file", "fake.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0
                                }))
                        .with(TestJwt.customer(amara)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Attach JPG, PNG, HEIC or PDF files."));
        var upload = mvc.perform(multipart("/api/v1/me/case-uploads")
                        .file(new MockMultipartFile("file", "kale.jpg", "image/jpeg", jpeg))
                        .with(TestJwt.customer(amara)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String photo = JsonPath.read(upload, "$.id");
        mvc.perform(multipart("/api/v1/me/case-uploads")
                        .file(new MockMultipartFile("file", "x.exe", "application/octet-stream", new byte[] {1, 2}))
                        .with(TestJwt.customer(amara)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Attach JPG, PNG, HEIC or PDF files."));
        var stranger = data.user("Stranger");
        mvc.perform(post("/api/v1/me/problems")
                        .with(TestJwt.customer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"order\",\"id\":\"%s\",\"items\":[\"x\"],\"reason\":\"damaged\"}"
                                .formatted(order.id())))
                .andExpect(status().isNotFound());

        var body = report("""
                {"kind":"order","id":"%s","items":["%s"],"reason":"damaged","attachmentIds":["%s"]}""".formatted(order.id(), order.lineIds().getFirst(), photo));
        String refundId = JsonPath.read(body, "$.refunds[0].id");

        mvc.perform(get("/api/v1/me/cases").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(refundId))
                .andExpect(jsonPath("$.items[0].state").value("seller_review"))
                .andExpect(jsonPath("$.items[0].merchantName").value("Sunnyside Greens"))
                .andExpect(jsonPath("$.items[0].subject.id").value(order.id()));
        mvc.perform(get("/api/v1/me/cases/{id}", refundId).with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.steps[0].key").value("submitted"))
                .andExpect(jsonPath("$.steps[1].state").value("current"))
                .andExpect(jsonPath("$.steps[2].state").value("todo"))
                .andExpect(jsonPath("$.thread.code").value(startsWith("HD-")))
                .andExpect(jsonPath("$.thread.notes[0].by").value("you"))
                .andExpect(jsonPath("$.thread.notes[0].attachments[0].fileName").value("kale.jpg"));
        mvc.perform(post("/api/v1/me/cases/{id}/notes", refundId)
                        .with(TestJwt.customer(amara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Here's another angle.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thread.notes", hasSize(2)))
                .andExpect(jsonPath("$.thread.notes[1].body").value("Here's another angle."));
        mvc.perform(get("/api/v1/me/cases/{id}", refundId).with(TestJwt.customer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/case-uploads/{id}", photo).with(TestJwt.customer(stranger)))
                .andExpect(status().isNotFound());

        // support sees the photo on the case in the console (mobile gaps part 1): listed on the message, then opened
        var ticket = jdbc.sql("""
                        select h.ref_id from messaging.threads h join messaging.messages m on m.thread_id = h.id
                         where h.ref_type = 'ticket' and h.kind = 'case' and ? = any(m.attachments)""").params(photo).query(String.class).single();
        var agent = data.user("Ana Agent");
        mvc.perform(get("/api/v1/console/support/tickets/{id}", ticket).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].attachments[0].id").value(photo))
                .andExpect(jsonPath("$.notes[0].attachments[0].fileName").value("kale.jpg"))
                .andExpect(jsonPath("$.notes[0].attachments[0].contentType").value("image/jpeg"));
        var file = mvc.perform(get("/api/v1/console/support/tickets/{id}/attachments/{f}", ticket, photo)
                        .with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(file).isEqualTo(jpeg);
        // the customer, a staff member without the support screen: refused; a file of no message on this case: 404
        mvc.perform(get("/api/v1/console/support/tickets/{id}/attachments/{f}", ticket, photo)
                        .with(TestJwt.customer(amara)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/support/tickets/{id}/attachments/{f}", ticket, photo)
                        .with(TestJwt.staff(agent, StaffRole.FINANCE)))
                .andExpect(status().isForbidden());
        String loose = JsonPath.read(
                mvc.perform(multipart("/api/v1/me/case-uploads")
                                .file(new MockMultipartFile("file", "other.jpg", "image/jpeg", jpeg))
                                .with(TestJwt.customer(amara)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mvc.perform(get("/api/v1/console/support/tickets/{id}/attachments/{f}", ticket, loose)
                        .with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ordersAndBookings_offerSomethingsWrong_onADeliveredOrder() throws Exception {
        var order = delivered(1000);
        mvc.perform(get("/api/v1/me/activity").with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].action".formatted(order.id()))
                        .value("report"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].href".formatted(order.id()))
                        .value("/account/problem/order/" + order.id()));
    }
}
