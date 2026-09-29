package ca.northline.auth.signing;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code local} provider, optional ({@code SIGNING_KEYS_ROTATE_EVERY}, e.g. {@code 90d}): every hour, rotates when the
 * newest key is that old. Several instances sharing the directory rotate once (the check runs under the file lock).
 */
@Slf4j
@RequiredArgsConstructor
class LocalKeyRotationJob {

    private final LocalFileSigningKeys keys;
    private final SigningProperties props;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    void rotateWhenDue() {
        try {
            keys.rotateIfOlderThan(Objects.requireNonNull(props.rotateEvery()))
                    .ifPresent(r ->
                            log.info("Scheduled signing key rotation: {} signs from {}", r.keyId(), r.activatesAt()));
        } catch (RuntimeException e) {
            log.error("Scheduled signing key rotation failed", e);
        }
    }
}
