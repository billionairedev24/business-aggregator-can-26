package ca.northline.worker.push;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.push.*} (S-102; every variable in docs/runbooks/push.md).
 *
 * @param provider {@code local} (the log) or {@code native} (APNs + FCM; both need their credentials)
 * @param staleAfter an installation that hasn't refreshed for this long gets nothing and is pruned (the apps refresh
 *     at every start)
 * @param backoff the first pause after a provider throttles or fails; doubled each time up to {@code maxBackoff}
 */
@ConfigurationProperties("northline.push")
public record PushProperties(
        @DefaultValue("local") Provider provider,
        @DefaultValue Apns apns,
        @DefaultValue Fcm fcm,
        @DefaultValue("90d") Duration staleAfter,
        @DefaultValue("30s") Duration backoff,
        @DefaultValue("15m") Duration maxBackoff,
        @DefaultValue("10s") Duration timeout) {

    public enum Provider {
        LOCAL,
        NATIVE
    }

    /**
     * Apple Push Notification service, token-based (JWT) authentication.
     *
     * @param url {@code https://api.push.apple.com} (production apps) or {@code https://api.sandbox.push.apple.com}
     *     (development builds)
     * @param key the {@code .p8} signing key's PEM text ({@code PUSH_APNS_KEY}, a secret)
     * @param consumerTopic / courierTopic the apps' bundle ids ({@code apns-topic})
     */
    public record Apns(
            @DefaultValue("https://api.push.apple.com") String url,
            @Nullable String keyId,
            @Nullable String teamId,
            @Nullable String key,
            @DefaultValue("ca.northline.app") String consumerTopic,
            @DefaultValue("ca.northline.courier") String courierTopic) {}

    /**
     * Firebase Cloud Messaging HTTP v1.
     *
     * @param serviceAccount the Firebase service account's JSON key ({@code PUSH_FCM_SERVICE_ACCOUNT}, a secret)
     * @param tokenUrl overrides the key's {@code token_uri} (tests)
     */
    public record Fcm(
            @DefaultValue("https://fcm.googleapis.com") String url,
            @Nullable String serviceAccount,
            @Nullable String tokenUrl) {}
}
