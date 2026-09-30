package ca.northline.worker.webhooks;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code northline.webhooks.*} — partner webhook delivery (S-33). Every value has a production default; the runbook
 * (docs/runbooks/webhooks.md) lists the environment variables.
 *
 * @param secretKey {@code WEBHOOK_SECRET_KEY}, the api's key for the endpoints' signing secrets (blank = the public
 *     development key, refused under the cloud profiles)
 * @param allowLocal lets deliveries go to {@code http://localhost} / loopback — local development and tests only;
 *     private, link-local and metadata addresses are refused regardless
 * @param maxInFlight endpoints delivered to at once by one worker replica (one virtual thread each)
 * @param batch deliveries sent to one endpoint per lease before its turn goes back to the pool
 * @param lease how long a replica owns an endpoint without renewing (a crashed replica's endpoints come back after it)
 * @param disableAfter no success for this long, with at least {@code disableMinFailures} failed attempts in a row,
 *     turns the endpoint off
 */
@ConfigurationProperties("northline.webhooks")
public record WebhookProperties(
        @Nullable String secretKey,
        @DefaultValue("false") boolean allowLocal,
        @DefaultValue("64") int maxInFlight,
        @DefaultValue("20") int batch,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration responseTimeout,
        @DefaultValue("15s") Duration totalTimeout,
        @DefaultValue("64KB") DataSize maxResponse,
        @DefaultValue("1000") int snippetChars,
        @DefaultValue Retry retry,
        @DefaultValue("3d") Duration disableAfter,
        @DefaultValue("10") int disableMinFailures,
        @DefaultValue("30d") Duration logRetention,

        @DefaultValue("Northline-Webhooks/1.0 (+https://northline.ca/developers/webhooks)")
        String userAgent) {

    /**
     * Back-off after a failed attempt: {@code initial × multiplier^(n-1)}, capped at {@code maxDelay}, ± {@code jitter};
     * the delivery fails for good after {@code maxAttempts}. Defaults: 30 s, 90 s, 4.5 min, 13.5 min, 40 min, 2 h, 6 h,
     * then every 12 h — 13 attempts over about 2.9 days.
     */
    public record Retry(
            @DefaultValue("30s") Duration initial,
            @DefaultValue("3") double multiplier,
            @DefaultValue("12h") Duration maxDelay,
            @DefaultValue("13") int maxAttempts,
            @DefaultValue("0.1") double jitter) {}
}
