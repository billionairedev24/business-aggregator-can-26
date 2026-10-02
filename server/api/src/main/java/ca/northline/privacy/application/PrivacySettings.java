package ca.northline.privacy.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code northline.privacy.*} (docs/runbooks/privacy-requests.md). Deadlines are not here: they are the law's, in the
 * region model.
 *
 * @param erasureGrace {@code PRIVACY_ERASURE_GRACE} (P7D): a verified erasure starts after this, so a mistaken
 *     "Delete my account" can be withdrawn; never later than a day before the law's deadline
 * @param exportTtl {@code PRIVACY_EXPORT_TTL} (P7D): how long an access export can be downloaded, then it is deleted
 * @param linkTtl {@code PRIVACY_LINK_TTL} (PT15M, at most PT1H): a download link's life
 * @param codeTtl how long a texted verification code works (PT15M)
 * @param holdRetry {@code PRIVACY_HOLD_RETRY} (P1D): when a held erasure step is tried again
 * @param runEvery {@code PRIVACY_RUN_INTERVAL} (PT1M): how often the pipeline looks for work
 */
@ConfigurationProperties("northline.privacy")
public record PrivacySettings(
        @Nullable Duration erasureGrace,
        @Nullable Duration exportTtl,
        @Nullable Duration linkTtl,
        @Nullable Duration codeTtl,
        @Nullable Duration holdRetry,
        @Nullable Duration runEvery) {

    private static final Duration MAX_LINK = Duration.ofHours(1);

    public Duration grace() {
        return erasureGrace == null ? Duration.ofDays(7) : erasureGrace;
    }

    public Duration exportLife() {
        return exportTtl == null ? Duration.ofDays(7) : exportTtl;
    }

    public Duration linkLife() {
        var ttl = linkTtl == null ? Duration.ofMinutes(15) : linkTtl;
        return ttl.compareTo(MAX_LINK) > 0 ? MAX_LINK : ttl;
    }

    public Duration codeLife() {
        return codeTtl == null ? Duration.ofMinutes(15) : codeTtl;
    }

    public Duration holdBackoff() {
        return holdRetry == null ? Duration.ofDays(1) : holdRetry;
    }
}
