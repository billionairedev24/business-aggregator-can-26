package ca.northline.uat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-121 dry run, part 1: the fake pilot group of the dev seed (V326) as the console and the people see it under the
 * {@code local} profile — the same seed `make up` gives a developer, so docs/uat/dry-run.md can be repeated by hand.
 */
@ActiveProfiles({"test", "local"})
class UatDevSeedTest extends IntegrationTest {

    static final String PRIYA_N = "01J9ZD3V00000000000000PNA1";
    static final String KOFI = "01J9ZD3V0000000000000C00K0";
    static final String RAVI = "01J9ZD3V00000000000000RAV1";

    @Test
    void theSeededPilotGroup_inTheConsole() throws Exception {
        mvc.perform(get("/api/v1/console/uat/feedback").param("state", "all").header("X-Dev-User", PRIYA_N))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.items[*].id",
                        hasItems(
                                "01J9ZD3VUF0000000000000001",
                                "01J9ZD3VUF0000000000000003",
                                "01J9ZD3VUF0000000000000006")));
        mvc.perform(get("/api/v1/console/uat/feedback/{id}", "01J9ZD3VUF0000000000000001")
                        .header("X-Dev-User", PRIYA_N))
                .andExpect(jsonPath("$.item.state").value("accepted"))
                .andExpect(jsonPath("$.item.blocking").value(true))
                .andExpect(jsonPath("$.duplicates[0].id").value("01J9ZD3VUF0000000000000006"))
                .andExpect(jsonPath("$.history[1].note").value("Kitchens miss orders: blocks launch."));
        mvc.perform(get("/api/v1/console/uat/go-no-go").header("X-Dev-User", PRIYA_N))
                .andExpect(jsonPath("$.verdict").value("no_go"))
                .andExpect(jsonPath(
                        "$.blockingItems[*].id",
                        hasItems(
                                "01J9ZD3VUF0000000000000001",
                                "01J9ZD3VUF0000000000000002",
                                "01J9ZD3VUF0000000000000003")))
                .andExpect(jsonPath("$.reasons[*].code", hasItems("blocking_open", "blocking_unverified")));
        mvc.perform(get("/api/v1/console/uat/participants").header("X-Dev-User", PRIYA_N))
                .andExpect(jsonPath("$.items[*].label", hasItem("Pilot kitchen 1 (Pho Dau Bo)")));
    }

    @Test
    void seededParticipantsSeeTheControl_othersDont() throws Exception {
        mvc.perform(get("/api/v1/me/pilot").header("X-Dev-User", KOFI))
                .andExpect(jsonPath("$.participant").value(true))
                .andExpect(jsonPath("$.persona").value("customer"));
        mvc.perform(get("/api/v1/me/pilot")
                        .param("merchantId", "01J9ZD3V00000000000000PDB1")
                        .header("X-Dev-User", RAVI))
                .andExpect(jsonPath("$.persona").value("kitchen"));
        mvc.perform(get("/api/v1/me/pilot").header("X-Dev-User", RAVI))
                .andExpect(jsonPath("$.participant").value(false));
    }
}
