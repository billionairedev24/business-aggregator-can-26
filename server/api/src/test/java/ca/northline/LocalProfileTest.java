package ca.northline;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test of the {@code local} profile: db/seed-dev personas are applied and {@code X-Dev-User} authenticates.
 * (Runs against the shared container; the V100 seed rows are harmless for other tests.)
 */
@ActiveProfiles({"test", "local"})
class LocalProfileTest extends IntegrationTest {

    static final String RAVI = "01J9ZD3V00000000000000RAV1";
    static final String JAS = "01J9ZD3V000000000000000JAS";
    static final String PRAIRIE_WRENCH = "01J9ZD3V00000000000000PWM1";

    @Test
    void ownerSeesTheDesignSwitchBusinessList() throws Exception {
        mvc.perform(get("/api/v1/me/businesses").header("X-Dev-User", RAVI))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.items[*].displayName", contains("Prairie Wrench", "Prairie Wrench Parts", "Pho Dau Bo")))
                .andExpect(jsonPath("$.items[*].type", contains("provider", "seller", "kitchen")))
                .andExpect(jsonPath("$.items[*].tier", contains("master", "trusted", "trusted")));
    }

    @Test
    void devUserGetsMerchantScopeAndMfa() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}", PRAIRIE_WRENCH).header("X-Dev-User", JAS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Prairie Wrench"));
    }

    @Test
    void devAcrHeaderSimulatesSingleFactor() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}", PRAIRIE_WRENCH)
                        .header("X-Dev-User", RAVI)
                        .header("X-Dev-Acr", "pwd"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    @Test
    void devUserMustBeAUlid() throws Exception {
        mvc.perform(get("/api/v1/me/businesses").header("X-Dev-User", "ravi")).andExpect(status().isBadRequest());
    }
}
