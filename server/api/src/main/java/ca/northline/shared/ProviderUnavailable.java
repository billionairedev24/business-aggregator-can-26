package ca.northline.shared;

import lombok.Getter;

/**
 * An outside provider the request needs (Stripe for checkout) is down, timing out or throttling us: nothing was done,
 * the same request can be sent again later (its idempotency key was released). Rendered as HTTP 503 ProblemDetail with
 * {@code code} and a {@code Retry-After} header, so the apps say "try again in a few minutes" instead of an error page
 * (S-115, docs/runbooks/stripe-incidents.md). Not for a provider's refusal (a declined card is a 4xx).
 */
@Getter
public class ProviderUnavailable extends RuntimeException {
    private final String code;
    private final int retryAfterSeconds;

    public ProviderUnavailable(String code, String message, int retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
