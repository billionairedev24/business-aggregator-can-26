package ca.northline.developer.domain;

import java.time.Instant;
import java.util.List;

/**
 * S-76: the key a business's website embed snippet carries ({@code pk_live_…}). Public by design: it only says which
 * business's published page the embed shows, and on which sites ({@code allowedOrigins}, empty = any).
 */
public record PublishableKey(String id, String merchantId, String key, List<String> allowedOrigins, Instant createdAt) {

    public PublishableKey {
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
