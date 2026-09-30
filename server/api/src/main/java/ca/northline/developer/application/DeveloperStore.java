package ca.northline.developer.application;

import ca.northline.developer.domain.ApiKey;
import ca.northline.developer.domain.AuditRecord;
import ca.northline.developer.domain.WebhookDelivery;
import ca.northline.developer.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code developer.api_keys}, {@code developer.webhook_endpoints} (+ deliveries), {@code audit_log}. */
public interface DeveloperStore {

    List<ApiKey> activeKeys(String merchantId);

    Optional<ApiKey> findKey(String merchantId, String keyId);

    void insertKey(ApiKey key, byte[] hash, String createdBy);

    void revokeKey(String keyId, Instant at);

    List<WebhookEndpoint> endpoints(String merchantId);

    Optional<WebhookEndpoint> findEndpoint(String merchantId, String endpointId);

    void insertEndpoint(WebhookEndpoint endpoint, byte[] encryptedSecret, String secretRef, String createdBy);

    /**
     * Stores the new secret; the current one keeps signing until {@code previousUntil} (null = it is dropped at once).
     */
    void replaceSecret(String endpointId, byte[] encryptedSecret, String secretRef, @Nullable Instant previousUntil);

    /** Active again, with a clean health record (S-33). */
    void enableEndpoint(String endpointId);

    List<WebhookDelivery> deliveries(String endpointId, int limit);

    Optional<WebhookDelivery> findDelivery(String endpointId, String deliveryId);

    /**
     * Queues a delivery due at {@code at} for the worker: a resend of {@code resendOf} (its event, type and payload) or,
     * when {@code resendOf} is null, a test event whose payload the worker writes.
     */
    WebhookDelivery queueDelivery(
            String id,
            String merchantId,
            String endpointId,
            String eventId,
            String eventType,
            @Nullable String resendOf,
            boolean test,
            Instant at);

    void deleteEndpoint(String endpointId);

    List<AuditRecord> audit(String merchantId, Instant since, int limit);
}
