package ca.northline.developer.application;

import ca.northline.developer.domain.ApiKey;
import ca.northline.developer.domain.AuditRecord;
import ca.northline.developer.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: {@code developer.api_keys}, {@code developer.webhook_endpoints} (+ deliveries), {@code audit_log}. */
public interface DeveloperStore {

    List<ApiKey> activeKeys(String merchantId);

    Optional<ApiKey> findKey(String merchantId, String keyId);

    void insertKey(ApiKey key, byte[] hash, String createdBy);

    void revokeKey(String keyId, Instant at);

    List<WebhookEndpoint> endpoints(String merchantId);

    Optional<WebhookEndpoint> findEndpoint(String merchantId, String endpointId);

    void insertEndpoint(WebhookEndpoint endpoint, byte[] encryptedSecret, String secretRef, String createdBy);

    void replaceSecret(String endpointId, byte[] encryptedSecret, String secretRef);

    void deleteEndpoint(String endpointId);

    List<AuditRecord> audit(String merchantId, Instant since, int limit);
}
