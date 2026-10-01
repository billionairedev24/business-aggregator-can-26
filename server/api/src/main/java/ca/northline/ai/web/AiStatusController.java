package ca.northline.ai.web;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/ai/status}: whether AI features are on here, so the apps can hide their buttons when not. */
@RestController
@RequiredArgsConstructor
class AiStatusController {

    private final AiCompletions ai;
    private final LlmClient client;

    /** @param provider {@code openrouter} or {@code fake} (the Studio labels fake answers as such) */
    record AiStatus(boolean available, String provider) {}

    @GetMapping("/api/v1/ai/status")
    AiStatus status() {
        return new AiStatus(ai.available(), client.name());
    }
}
