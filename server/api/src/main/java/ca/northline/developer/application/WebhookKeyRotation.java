package ca.northline.developer.application;

import ca.northline.developer.application.DeveloperStore.StoredSecrets;
import ca.northline.shared.Bytes;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Engineering follow-ups (S-115 gap; docs/runbooks/key-rotation.md § 3): after {@code WEBHOOK_SECRET_KEY} is rotated
 * (a new key with a new {@code WEBHOOK_SECRET_KEY_ID}, the old one in {@code WEBHOOK_SECRET_PREVIOUS_KEYS}), re-encrypts
 * every endpoint's signing secret — and a previous secret still signing next to it — with the current key, and moves
 * {@code secret_ref} to it. The secrets themselves never change, so partners notice nothing. Runs every {@code
 * WEBHOOK_REENCRYPT_EVERY} (1 h) on every replica, at most {@code batch} endpoints per run; a row is only written
 * while it still holds what was read. When {@link #stale()} is 0 and no previous secret is left under the old key, the
 * old key can leave {@code WEBHOOK_SECRET_PREVIOUS_KEYS} (api and worker).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookKeyRotation {

    static final String METRIC = "northline.crypto.reencrypted";
    static final String TABLE = "developer.webhook_endpoints";

    private final DeveloperStore store;
    private final WebhookSecretCipher cipher;
    private final MeterRegistry meters;
    private final Clock clock;

    @Value("${northline.developer.webhook-reencrypt-batch:200}")
    private int batch = 200;

    /** What one run did: endpoints re-encrypted, endpoints that failed (a key missing from the ring). */
    public record Outcome(int reencrypted, int failed) {}

    @Scheduled(
            fixedDelayString = "${northline.developer.webhook-reencrypt-every:1h}",
            initialDelayString = "${northline.developer.webhook-reencrypt-initial-delay:3m}")
    void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            // no key configured (webhooks unavailable), or the database is away: next run
            log.warn("Webhook secret re-encryption skipped: {}", e.toString());
        }
    }

    /** One pass; returns how many endpoints were re-encrypted and how many failed. */
    public Outcome run() {
        var current = cipher.keyRef();
        var reencrypted = 0;
        var failed = 0;
        for (var row : store.secretsToReencrypt(current, clock.instant(), batch)) {
            try {
                if (reencrypt(row, current)) {
                    reencrypted++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn(
                        "Webhook secret re-encryption: endpoint {} ({}) failed: {}",
                        row.endpointId(),
                        row.secretRef(),
                        e.toString());
            }
        }
        meters.counter(METRIC, "table", TABLE, "outcome", "reencrypted").increment(reencrypted);
        meters.counter(METRIC, "table", TABLE, "outcome", "failed").increment(failed);
        if (reencrypted > 0 || failed > 0) {
            log.info(
                    "Webhook secret re-encryption: {} endpoint(s) moved to {}, {} failed",
                    reencrypted,
                    current,
                    failed);
        }
        return new Outcome(reencrypted, failed);
    }

    /** Endpoints whose secret is still under another key than the current one. */
    public int stale() {
        return store.secretsUnderOtherKeys(cipher.keyRef());
    }

    private boolean reencrypt(StoredSecrets row, String current) {
        var secret = cipher.open(row.secret().toArray(), row.secretRef());
        var previous =
                row.previous() == null ? null : cipher.open(row.previous().toArray(), row.secretRef());
        var secretMoves = !secret.keyRef().equals(current) || !current.equals(row.secretRef());
        var previousMoves = previous != null && !previous.keyRef().equals(current);
        if (!secretMoves && !previousMoves) {
            return false;
        }
        return store.reencrypt(
                row,
                secretMoves ? Bytes.of(cipher.encrypt(secret.secret())) : row.secret(),
                previousBytes(row, previous, previousMoves),
                current);
    }

    private @Nullable Bytes previousBytes(
            StoredSecrets row, WebhookSecretCipher.@Nullable Opened previous, boolean moves) {
        if (previous == null) {
            return null;
        }
        return moves ? Bytes.of(cipher.encrypt(previous.secret())) : row.previous();
    }
}
