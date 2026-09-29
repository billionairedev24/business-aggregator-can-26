package ca.northline.messaging;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/** The V106 dev seed shows the design's messages, cases and reviews under the {@code local} profile. */
@ActiveProfiles({"test", "local"})
class MessagingSeedTest extends IntegrationTest {

    static final String RAVI = "01J9ZD3V00000000000000RAV1";
    static final String JAS = "01J9ZD3V000000000000000JAS";
    static final String PRAIRIE_WRENCH = "01J9ZD3V00000000000000PWM1";
    static final String PHO_DAU_BO = "01J9ZD3V00000000000000PDB1";

    @Test
    void prairieWrenchInboxIsTheDesigns() throws Exception {
        mvc.perform(get("/api/v1/merchants/{m}/threads", PRAIRIE_WRENCH).header("X-Dev-User", RAVI))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.items[*].counterpartName",
                        contains("Amara Osei", "M. Tran", "D. Kowalski", "Northline support")))
                .andExpect(jsonPath(
                        "$.items[*].lastMessage",
                        contains(
                                "Thanks! The dash light came on again yesterday.",
                                "Saturday 10 works. Send the quote?",
                                "Pads feel great, thanks Ravi.",
                                "Dispute DS-1188: please respond by Thu.")));
        mvc.perform(get("/api/v1/merchants/{m}/threads", PRAIRIE_WRENCH).header("X-Dev-User", JAS))
                .andExpect(jsonPath("$.items[*].counterpartName", contains("M. Tran")));
    }

    @Test
    void casesAndReviewsAreTheDesigns() throws Exception {
        mvc.perform(get("/api/v1/merchants/{m}/help/cases", PRAIRIE_WRENCH).header("X-Dev-User", RAVI))
                .andExpect(jsonPath("$.items[*].code", contains("HD-4471", "HD-4402", "HD-4298")))
                .andExpect(jsonPath("$.items[0].agentName").value("Dev K."));
        mvc.perform(get("/api/v1/merchants/{m}/reviews/summary", PRAIRIE_WRENCH).header("X-Dev-User", RAVI))
                .andExpect(jsonPath("$.average").value(4.9))
                .andExpect(jsonPath("$.count").value(312))
                .andExpect(jsonPath("$.praise[*].tag", contains("on_time", "clear_explanation", "fair_price")));
        mvc.perform(get("/api/v1/merchants/{m}/reviews", PRAIRIE_WRENCH)
                        .param("limit", "3")
                        .header("X-Dev-User", RAVI))
                .andExpect(jsonPath("$.items[*].authorName", contains("Dana K.", "M. Tran", "S. Bouchard")));
        mvc.perform(get("/api/v1/merchants/{m}/reviews/summary", PHO_DAU_BO).header("X-Dev-User", RAVI))
                .andExpect(jsonPath("$.average").value(4.8));
        mvc.perform(get("/api/v1/merchants/{m}/quality", PRAIRIE_WRENCH).header("X-Dev-User", RAVI))
                .andExpect(jsonPath("$.score").value(91));
    }
}
