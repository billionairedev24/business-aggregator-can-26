package ca.northline.developer.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every business's API keys, for the console's API &amp; webhooks screen (S-96, design 03 "Partner keys"): staff issue a
 * key for a business (its secret shown once) and revoke one, each audit-logged under that business with the staff
 * member as actor. Settings › API &amp; integrations stays the business's own view.
 */
public interface PartnerKeys {

    /** Keys of every business, active first, newest first. */
    List<Key> all();

    /** 422 for a bad name or scope (the Studio's rules); {@code roles} = the console roles acted with. */
    Issued issue(String merchantId, String name, List<String> scopes, String staffId, String roles);

    /** 404 for an unknown or already revoked key. */
    Key revoke(String keyId, String staffId, String roles);

    record Key(
            String id,
            String merchantId,
            String name,
            List<String> scopes,
            String prefix,
            int rateLimit,
            Instant createdAt,
            @Nullable Instant lastUsedAt,
            @Nullable Instant revokedAt) {

        public Key {
            scopes = List.copyOf(scopes);
        }
    }

    record Issued(Key key, String secret) {}
}
