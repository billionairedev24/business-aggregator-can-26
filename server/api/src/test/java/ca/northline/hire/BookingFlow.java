package ca.northline.hire;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * A booking through the wizard's api (S-55) and the job flow (Studio), for tests that need a paid, booked or completed
 * job: one provider (from {@link HireFixtures}) with a $89 fixed-price brake inspection.
 */
public final class BookingFlow {

    public static final String MECHANIC = "service.automotive.mobile-mechanic";
    static final JsonMapper JSON = JsonMapper.builder().build();
    /** The fixture provider's market zone (tests only; the code never names a place). */
    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    private final MockMvc mvc;
    public final HireFixtures.Provider provider;
    public final String service;

    public BookingFlow(MockMvc mvc, HireFixtures fx, String name) {
        this.mvc = mvc;
        this.provider = fx.provider(name, "master", java.util.List.of("Beltline"));
        this.service = fx.service(provider.merchantId(), MECHANIC, "Brake inspection", "fixed", 8900L, 60);
    }

    public static Instant tomorrowAt(int hour) {
        return LocalDate.now(ZONE).plusDays(1).atTime(hour, 0).atZone(ZONE).toInstant();
    }

    public String hold(String customer, Instant at) throws Exception {
        var body = mvc.perform(post("/api/v1/me/bookings/holds")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                Map.of("slug", provider.slug(), "serviceId", service, "startsAt", at.toString()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.holdId");
    }

    public Map<String, Object> request(String holdId, Map<String, Object> extra) {
        var r = new LinkedHashMap<String, Object>();
        r.put("holdId", holdId);
        r.put("serviceId", service);
        r.put("description", "Grinding noise when braking, worse when cold.");
        r.put("vehicle", Map.of("year", "2018", "make", "Honda", "model", "Civic", "plate", "BKT 4471"));
        r.put("addressLine", "1204 Sample St");
        r.put("area", "Beltline");
        r.put("spot", "Driveway");
        r.put("accessNote", "Gate code 4471");
        r.put("contactPhone", "+1 403 555 0123");
        r.put("agreePolicies", true);
        r.put("agreeTerms", true);
        r.putAll(extra);
        return r;
    }

    public ResultActions checkout(String customer, String holdId, Map<String, Object> extra) throws Exception {
        return mvc.perform(post("/api/v1/me/bookings/checkout")
                .with(TestJwt.customerWithMfa(customer))
                .header("Idempotency-Key", "p-" + holdId + "-" + extra.hashCode())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(request(holdId, extra))));
    }

    public String confirm(String customer, String holdId) throws Exception {
        var body = mvc.perform(post("/api/v1/me/bookings/holds/{id}/confirm", holdId)
                        .with(TestJwt.customer(customer))
                        .header("Idempotency-Key", "c-" + holdId))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.bookingId");
    }

    /** Books and pays at {@code at}: the booking id. */
    public String book(String customer, Instant at, Map<String, Object> extra) throws Exception {
        var holdId = hold(customer, at);
        checkout(customer, holdId, extra).andExpect(status().isOk());
        return confirm(customer, holdId);
    }

    /** A Studio job step by the owner: {@code en-route}, {@code on-site}, {@code complete}. */
    public ResultActions job(String bookingId, String step, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/merchants/{m}/jobs/{id}/" + step, provider.merchantId(), bookingId)
                .with(TestJwt.member(provider.owner()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** Booked, travelled, on site and completed. */
    public String completed(String customer, Instant at) throws Exception {
        var id = book(customer, at, Map.of());
        job(id, "en-route", Map.of()).andExpect(status().isOk());
        job(id, "on-site", Map.of()).andExpect(status().isOk());
        job(id, "complete", Map.of("report", "Front pads 4 mm. No parts used.")).andExpect(status().isOk());
        return id;
    }
}
