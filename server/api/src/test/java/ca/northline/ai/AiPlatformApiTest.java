package ca.northline.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.Prompts;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The platform in the full context (profile test → the fake model, in-memory budgets): status and usage records. */
class AiPlatformApiTest extends IntegrationTest {

    @Autowired
    AiCompletions ai;

    @Autowired
    Prompts prompts;

    @Autowired
    JdbcClient jdbc;

    @Test
    void statusSaysWhetherAiIsOn() throws Exception {
        mvc.perform(get("/api/v1/ai/status").with(TestJwt.customer(Ids.next())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.provider").value("fake"));
    }

    @Test
    void statusNeedsASignIn() throws Exception {
        mvc.perform(get("/api/v1/ai/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyRequestIsRecordedWithoutItsContent() {
        var person = Ids.next();
        var merchant = Ids.next();
        var prompt = prompts.get("platform-check");
        var answer = ai.complete(AiCompletions.Request.of(
                AiFeature.PLATFORM, Caller.member(person, merchant), prompt, prompt.text(), "Secret question 42"));
        assertThat(answer.text()).startsWith("(fake model)");
        var row = jdbc.sql("select * from ai.usage where person_id = :p")
                .param("p", person)
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("feature", "platform")
                .containsEntry("merchant_id", merchant)
                .containsEntry("provider", "fake")
                .containsEntry("model", "fake/echo-1")
                .containsEntry("prompt", prompt.id())
                .containsEntry("outcome", "ok");
        assertThat(row.values()).noneMatch(v -> String.valueOf(v).contains("Secret question"));
    }
}
