package ca.northline.merchants;

import static ca.northline.merchants.OnboardingFlow.soleBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.MerchantApproved;
import ca.northline.merchants.application.DevIdentityOutcomes;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * {@code local} profile: the dev-only "Simulate approval →" endpoint and the V102 seed (same context as
 * {@code LocalProfileTest}).
 */
@ActiveProfiles({"test", "local"})
@RecordApplicationEvents
class DevOnboardingTest extends IntegrationTest {

    static final String RAVI = "01J9ZD3V00000000000000RAV1";
    static final String PRAIRIE_WRENCH = "01J9ZD3V00000000000000PWM1";
    static final String PHO_DAU_BO = "01J9ZD3V00000000000000PDB1";

    @Autowired
    ApplicationEvents events;

    @Autowired
    DataSource dataSource;

    @Autowired
    DevIdentityOutcomes identity;

    @Test
    void simulateApproval_pendingBecomesActive() throws Exception {
        new CategorySeeder(dataSource).seed();
        var flow = new OnboardingFlow(mvc, dataSource, identity);
        var user = data.user("Dev");
        var id = flow.start(user, "seller");

        mvc.perform(post("/api/v1/dev/merchants/{id}/approve", id).with(TestJwt.member(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_pending"));

        flow.business(id, user, soleBusiness("Dev Bakery", "shop.food-and-grocery.bakery"))
                .andExpect(status().isOk());
        flow.completeAll(id, user);
        flow.submit(id, user).andExpect(status().isOk());

        mvc.perform(post("/api/v1/dev/merchants/{id}/approve", id).with(TestJwt.member(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.approvedAt").exists())
                .andExpect(jsonPath("$.checklist[*].status", everyItem(org.hamcrest.Matchers.is("verified"))));
        assertThat(events.stream(MerchantApproved.class)).singleElement().satisfies(e -> {
            assertThat(e.aggregateId()).isEqualTo(id);
            assertThat(e.merchantType()).isEqualTo("seller");
            assertThat(e.tier()).isEqualTo("registered");
        });
    }

    @Test
    void seededPersonasHaveACompleteApplicationAndPage() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}/onboarding", PRAIRIE_WRENCH).header("X-Dev-User", RAVI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.step").value("done"))
                .andExpect(jsonPath("$.business.structure").value("corp_ab"))
                .andExpect(jsonPath("$.business.principals", hasSize(2)))
                .andExpect(jsonPath(
                        "$.checklist[*].key",
                        contains("kyc", "registry", "licence:AMVIC", "insurance", "bank", "mfa")));
        mvc.perform(get("/api/v1/merchants/{id}/storefront", PRAIRIE_WRENCH).header("X-Dev-User", RAVI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("northline.ca/prairie-wrench"))
                .andExpect(jsonPath("$.tagline").value("Mobile mechanic · Calgary & Airdrie"))
                .andExpect(jsonPath("$.sections[5].kind").value("gallery"))
                .andExpect(jsonPath("$.sections[5].enabled").value(false))
                .andExpect(jsonPath("$.business.verifiedFacts", org.hamcrest.Matchers.hasItem("licence:AMVIC")));
        mvc.perform(get("/api/v1/storefronts/{slug}", "pho-dau-bo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageKind").value("menu_page"))
                .andExpect(jsonPath("$.business.cuisines[0]").value("vietnamese"));
        mvc.perform(get("/api/v1/merchants/{id}/onboarding", PHO_DAU_BO).header("X-Dev-User", RAVI))
                .andExpect(jsonPath("$.checklist", hasSize(12)));
    }

    /** S-31 "Simulate DNS records →": the local zone gets the records, the page's domain goes live on the local edge. */
    @Test
    void simulateDnsRecords_liveOnTheLocalEdge() throws Exception {
        var domain = "book-" + ca.northline.shared.Ids.next().toLowerCase(java.util.Locale.ROOT) + ".prairiewrench.ca";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/merchants/{id}/storefront", PRAIRIE_WRENCH)
                        .header("X-Dev-User", RAVI)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"customDomain\":\"%s\"}".formatted(domain)))
                .andExpect(jsonPath("$.customDomainStatus").value("pending"));
        try {
            mvc.perform(post("/api/v1/dev/merchants/{id}/storefront/domain/dns", PRAIRIE_WRENCH)
                            .header("X-Dev-User", RAVI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customDomainStatus").value("live"));
            mvc.perform(get("/api/v1/public/storefronts/by-host").param("host", domain))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value("prairie-wrench"));
        } finally {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                    "/api/v1/merchants/{id}/storefront", PRAIRIE_WRENCH)
                            .header("X-Dev-User", RAVI)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"customDomain\":\"\"}"))
                    .andExpect(status().isOk());
        }
    }
}
