package ca.northline.developer.api;

import java.util.List;
import java.util.Optional;

/**
 * S-76: resolves a website embed's publishable key ({@code pk_live_…}) to its business, for the public embed endpoint.
 * Revoked or unknown keys resolve to nothing.
 */
public interface EmbedKeys {

    Optional<EmbedKey> active(String publishableKey);

    /** @param allowedOrigins the sites the embed may answer on; empty = any */
    record EmbedKey(String merchantId, List<String> allowedOrigins) {

        public EmbedKey {
            allowedOrigins = List.copyOf(allowedOrigins);
        }

        public boolean allows(String origin) {
            return allowedOrigins.isEmpty() || allowedOrigins.contains(origin);
        }
    }
}
