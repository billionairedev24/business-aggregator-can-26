package ca.northline.developer.application;

import ca.northline.developer.domain.PublishableKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: {@code developer.publishable_keys}. */
public interface PublishableKeyStore {

    Optional<PublishableKey> active(String merchantId);

    Optional<PublishableKey> byKey(String key);

    void revoke(String id, Instant at);

    void insert(PublishableKey key, String createdBy);

    void origins(String id, List<String> allowedOrigins);
}
