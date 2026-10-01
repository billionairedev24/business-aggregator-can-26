package ca.northline.ai;

import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests of AI features with a scripted model: OpenRouter pointed at one {@link MockOpenRouter}
 * shared by every subclass, so they all reuse a single Spring context (one context more than {@link IntegrationTest},
 * not one per class). Queue answers on {@link #MODEL}; it is reset before each test.
 */
public abstract class ScriptedModelTest extends IntegrationTest {

    protected static final MockOpenRouter MODEL = new MockOpenRouter();

    @DynamicPropertySource
    static void openRouter(DynamicPropertyRegistry registry) {
        registry.add("northline.ai.provider", () -> "openrouter");
        registry.add("northline.ai.openrouter.base-url", MODEL::baseUrl);
        registry.add("northline.ai.openrouter.api-key", () -> "sk-or-v1-FAKE-scripted-test");
    }

    @BeforeEach
    void resetModel() {
        MODEL.reset();
    }
}
