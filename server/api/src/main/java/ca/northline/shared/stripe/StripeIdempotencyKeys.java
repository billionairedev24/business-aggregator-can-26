package ca.northline.shared.stripe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * {@code Idempotency-Key} values for Stripe's mutating calls. A key names the operation and the Northline ids it acts
 * on ({@code nl1:capture:<escrowId>}), so a retried job, listener or request repeats the same Stripe call instead of
 * making a second one. When a person's request starts the call (instant payout) the client's {@code Idempotency-Key}
 * is folded in, hashed with its scope, so the client's retry maps to the same Stripe call and its raw value never
 * leaves Northline. Stripe keeps keys 24 h and refuses a key reused with different parameters.
 */
public final class StripeIdempotencyKeys {

    /** Bumped if the derivation ever changes, so old and new keys can't collide. */
    static final String PREFIX = "nl1";

    /** Stripe accepts up to 255 characters. */
    static final int MAX_LENGTH = 255;

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private StripeIdempotencyKeys() {}

    /** {@code nl1:<operation>:<id>:<id>…} — ids are ULIDs, Stripe ids ({@code acct_…}) or ISO dates. */
    public static String of(String operation, String... ids) {
        var sb = new StringBuilder(PREFIX).append(':').append(part(operation));
        for (var id : ids) {
            sb.append(':').append(part(id));
        }
        return fit(sb.toString());
    }

    /**
     * {@code nl1:<operation>:<scope ids>:<hash(scope, clientKey)>} — for calls a person's request makes; the same client
     * key in the same scope always gives the same Stripe key, another scope or key never does.
     */
    public static String fromClient(String operation, String scope, String clientKey) {
        if (clientKey.isBlank()) {
            throw new IllegalArgumentException("client idempotency key is blank");
        }
        return fit(PREFIX + ':' + part(operation) + ':' + digest(scope + '\n' + clientKey));
    }

    private static String part(String value) {
        if (!PART.matcher(value).matches()) {
            throw new IllegalArgumentException("not a key part: '" + value + "'");
        }
        return value;
    }

    /** Over-long keys keep their readable start and end with a digest of the whole. */
    private static String fit(String key) {
        if (key.length() <= MAX_LENGTH) {
            return key;
        }
        var digest = digest(key);
        return key.substring(0, MAX_LENGTH - digest.length() - 1) + '~' + digest;
    }

    private static String digest(String value) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
