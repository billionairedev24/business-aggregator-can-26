package ca.northline.ai.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class AiWebAdviceTest {

    @RestController
    static class Thrower {
        @GetMapping("/off")
        String off() {
            throw new AiUnavailable("AI is not configured here (OPENROUTER_API_KEY is not set).");
        }

        @GetMapping("/busy")
        String busy() {
            throw new AiRateLimited(
                    "merchant_tokens", Duration.ofMinutes(90), "This business has used today's AI allowance.");
        }
    }

    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new Thrower())
            .setControllerAdvice(new AiWebAdvice())
            .build();

    @Test
    void unavailableIs503() throws Exception {
        mvc.perform(get("/off"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_unavailable"))
                .andExpect(jsonPath("$.type").value("https://northline.ca/problems/ai-unavailable"));
    }

    @Test
    void rateLimitedIs429WithRetryAfterAndTheLimit() throws Exception {
        mvc.perform(get("/busy"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5400"))
                .andExpect(jsonPath("$.code").value("ai_rate_limited"))
                .andExpect(jsonPath("$.limit").value("merchant_tokens"));
    }
}
