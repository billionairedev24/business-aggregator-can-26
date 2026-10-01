package ca.northline.ai.api;

import java.time.Duration;
import lombok.Getter;

/**
 * 429 {@code ai_rate_limited} with {@code Retry-After}: over a person's or a business's budget, or the provider is
 * throttling us.
 */
@Getter
public final class AiRateLimited extends RuntimeException {

    public static final String CODE = "ai_rate_limited";

    // Which limit: person_rate, person_tokens, merchant_tokens or provider.
    private final String limit;

    private final Duration retryAfter;

    public AiRateLimited(String limit, Duration retryAfter, String message) {
        super(message);
        this.limit = limit;
        this.retryAfter = retryAfter;
    }
}
