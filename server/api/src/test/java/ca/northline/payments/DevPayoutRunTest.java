package ca.northline.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-117, {@code local} profile: the end-to-end suite's payout run pays what is payable now, whatever the schedule, once;
 * owners only. The api's outbox answers without sign-in. (Same context as {@code LocalProfileTest}; the seeded
 * Prairie Wrench has an active bank account and a balance.)
 */
@ActiveProfiles({"test", "local"})
class DevPayoutRunTest extends IntegrationTest {

    static final String RAVI = "01J9ZD3V00000000000000RAV1";
    static final String BOOKKEEPER = "01J9ZD3V00000000000000PR1Y";
    static final String PRAIRIE_WRENCH = "01J9ZD3V00000000000000PWM1";

    @Test
    void paysEverythingPayable_once_ownersOnly() throws Exception {
        mvc.perform(post("/api/v1/dev/merchants/{m}/payouts/run", PRAIRIE_WRENCH)
                        .header("X-Dev-User", BOOKKEEPER))
                .andExpect(status().isForbidden());

        var overview = mvc.perform(get("/api/v1/merchants/{m}/payouts/overview", PRAIRIE_WRENCH)
                        .header("X-Dev-User", RAVI))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long payable = ((Number) JsonPath.read(overview, "$.payableCents")).longValue();

        if (payable > 0) {
            mvc.perform(post("/api/v1/dev/merchants/{m}/payouts/run", PRAIRIE_WRENCH)
                            .header("X-Dev-User", RAVI))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.kind").value("scheduled"))
                    .andExpect(jsonPath("$.amountCents").value(payable))
                    .andExpect(jsonPath("$.destination").value("TD ··3391"));
            mvc.perform(get("/api/v1/merchants/{m}/payouts", PRAIRIE_WRENCH).header("X-Dev-User", RAVI))
                    .andExpect(jsonPath("$.items[0].amountCents").value(payable));
        }
        // nothing left to pay
        mvc.perform(post("/api/v1/dev/merchants/{m}/payouts/run", PRAIRIE_WRENCH)
                        .header("X-Dev-User", RAVI))
                .andExpect(status().isNoContent());
    }

    @Test
    void theOutboxAnswersWithoutSignIn() throws Exception {
        mvc.perform(get("/api/v1/dev/outbox").param("to", "nobody@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }
}
