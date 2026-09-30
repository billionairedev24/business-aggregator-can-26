package ca.northline.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.application.DevIdentityOutcomes;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Drives the onboarding API the way the wizard does, for tests that need an applicant in a given state. */
final class OnboardingFlow {

    static final String PDF = "%PDF-1.7 test";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MockMvc mvc;
    private final @Nullable JdbcClient jdbc;
    private final @Nullable DevIdentityOutcomes identity;

    OnboardingFlow(MockMvc mvc) {
        this(mvc, null, null);
    }

    /** With the fake Stripe Identity, so {@link #completeAll} can verify the owners too (S-22). */
    OnboardingFlow(MockMvc mvc, @Nullable DataSource dataSource, @Nullable DevIdentityOutcomes identity) {
        this.mvc = mvc;
        this.jdbc = dataSource == null ? null : JdbcClient.create(dataSource);
        this.identity = identity;
    }

    /** Every owner who needs it gets an emailed Stripe Identity link and finishes with {@code outcome}. */
    void verifyOwners(String merchantId, String userId, String outcome) throws Exception {
        if (jdbc == null || identity == null) {
            throw new IllegalStateException("new OnboardingFlow(mvc, dataSource, identity) to verify owners");
        }
        var body = mvc.perform(get("/api/v1/merchants/{id}/identity-checks", merchantId)
                        .with(TestJwt.member(userId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> owners = JsonPath.read(body, "$.items[?(@.status != 'verified')].principalId");
        for (var owner : owners) {
            startSession(merchantId, userId, owner, "{\"delivery\":\"email\",\"email\":\"owner@example.test\"}")
                    .andExpect(status().isOk());
            var session =
                    jdbc.sql("""
                            select stripe_session from merchants.owner_identity_checks
                             where merchant_id = ? and principal_id = ?""").params(merchantId, owner).query(String.class).single();
            if (identity.finish(session, outcome).isEmpty()) {
                throw new IllegalStateException("unknown fake session " + session);
            }
        }
    }

    ResultActions startSession(String merchantId, String userId, String principalId, String json) throws Exception {
        return mvc.perform(post("/api/v1/merchants/{id}/identity-checks/{p}/session", merchantId, principalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(TestJwt.member(userId)));
    }

    /** Account step → the new applicant's id. */
    String start(String userId, String type) throws Exception {
        var body = mvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"%s\",\"province\":\"AB\"}".formatted(type))
                        .with(TestJwt.member(userId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.merchantId");
    }

    String upload(String merchantId, String userId, String purpose) throws Exception {
        var file = new MockMultipartFile("file", "document.pdf", "application/pdf", PDF.getBytes());
        var body = mvc.perform(multipart("/api/v1/merchants/{id}/onboarding/documents", merchantId)
                        .file(file)
                        .param("purpose", purpose)
                        .with(TestJwt.member(userId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    ResultActions business(String merchantId, String userId, String json) throws Exception {
        return mvc.perform(put("/api/v1/merchants/{id}/onboarding/business", merchantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(TestJwt.member(userId)));
    }

    String onboarding(String merchantId, String userId) throws Exception {
        return mvc.perform(get("/api/v1/merchants/{id}/onboarding", merchantId).with(TestJwt.member(userId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    ResultActions complete(String merchantId, String userId, String verificationId, String json) throws Exception {
        return mvc.perform(post("/api/v1/merchants/{id}/verifications/{v}/complete", merchantId, verificationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(TestJwt.member(userId)));
    }

    /** Completes every open check with valid evidence. */
    void completeAll(String merchantId, String userId) throws Exception {
        List<Map<String, Object>> checks = JsonPath.read(onboarding(merchantId, userId), "$.checklist");
        for (var check : checks) {
            if (!"todo".equals(check.get("status"))) {
                continue;
            }
            var key = String.valueOf(check.get("key"));
            if ("identity".equals(check.get("action"))) {
                verifyOwners(merchantId, userId, "verified");
                continue;
            }
            var body = switch (String.valueOf(check.get("action"))) {
                case "number" ->
                    key.equals("gst") ? "{\"reference\":\"123456789 RT0001\"}" : "{\"reference\":\"44812\"}";
                case "upload" -> "{\"documentId\":\"%s\"}".formatted(upload(merchantId, userId, "verification"));
                case "sign" -> "{\"choice\":\"signed\"}";
                case "choose" ->
                    switch (key) {
                        case "returns_policy" -> "{\"choice\":\"standard\"}";
                        case "aglc" -> "{\"choice\":\"not_applicable\"}";
                        default -> "{\"choice\":\"none\"}";
                    };
                case "slot" ->
                    "{\"reference\":\"%s\"}"
                            .formatted(Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
                default -> "{}";
            };
            complete(merchantId, userId, String.valueOf(check.get("id")), body).andExpect(status().isOk());
        }
    }

    ResultActions submit(String merchantId, String userId) throws Exception {
        return mvc.perform(
                post("/api/v1/merchants/{id}/onboarding/submit", merchantId).with(TestJwt.member(userId)));
    }

    static String randomBn() {
        return String.valueOf(100_000_000 + RANDOM.nextInt(900_000_000));
    }

    /** Valid Alberta corporation with two principals, AMVIC-regulated + plain categories and a suggestion. */
    static String corpBusiness(String docId, String bn) {
        return """
                {"displayName":"Aspen Wrench","legalName":"2201456 Alberta Ltd.","structure":"corp_ab",
                 "gstNumber":"123456789 rt0001",
                 "legalDetails":{"legal_corporate_name":"2201456 Alberta Ltd.","alberta_corporate_access_number":"2201456789",
                   "business_number":"%s","incorporation_date":"2019-04-02","registered_office":"1208 17 Ave SW, Calgary AB",
                   "certificate_of_incorporation_doc":"%s"},
                 "principals":[{"legalName":"Ravi Sandhu","role":"director","ownershipPct":60},
                               {"legalName":"Priya Sandhu","role":"shareholder","ownershipPct":40}],
                 "categoryIds":["service.automotive.mobile-mechanic","service.cleaning-and-property.house-cleaning"],
                 "suggestedCategories":["Bike repair"],
                 "profile":{"serviceArea":"Calgary + 40 km · Airdrie","languages":["en","pa"],"yearsOperating":"6-10",
                   "description":"Mobile mechanic since 2019."}}
                """.formatted(bn, docId);
    }

    /** Valid sole proprietorship (no GST needed) in one category. */
    static String soleBusiness(String name, String categoryId) {
        return """
                {"displayName":"%s","legalName":"Amara Okafor","structure":"sole",
                 "legalDetails":{"owner_legal_name":"Amara Okafor","sin_collected_by_stripe":true,
                   "address":"12 Glenmore Trail SW, Calgary AB"},
                 "categoryIds":["%s"]}
                """.formatted(name, categoryId);
    }
}
