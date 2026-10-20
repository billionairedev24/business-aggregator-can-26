package ca.northline.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.accounts.*} (S-154): email + password accounts.
 *
 * @param codeTtl how long an email code and its link work (15 min)
 * @param resendAfter cool-down before another email can be asked for
 * @param codeMaxAttempts wrong guesses before a code stops working (a new one is needed)
 * @param pendingTtl how long an account waits for its email to be confirmed before it is forgotten
 * @param passwordHash Argon2id cost (OWASP 2024 minimum: 19 MiB, 2 iterations, 1 lane)
 * @param sendAsync send the emails off the request thread (true; the test suite sends them in order, after commit)
 */
@ConfigurationProperties("northline.auth.accounts")
public record AccountProperties(
        @DefaultValue("15m") Duration codeTtl,
        @DefaultValue("60s") Duration resendAfter,
        @DefaultValue("5") int codeMaxAttempts,
        @DefaultValue("7d") Duration pendingTtl,
        @DefaultValue PasswordHash passwordHash,
        @DefaultValue("true") boolean sendAsync) {

    /** Argon2id parameters; changing them re-hashes nothing (old hashes carry their own parameters). */
    public record PasswordHash(
            @DefaultValue("19456") int memoryKib,
            @DefaultValue("2") int iterations,
            @DefaultValue("1") int parallelism) {}
}
