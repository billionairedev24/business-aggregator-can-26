package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port for {@code catalogue.integrations} (one Shopify / Square / Lightspeed connection per merchant and
 * platform), the OAuth requests of S-35 and webhook dedupe.
 */
public interface IntegrationRepository {

    enum Webhooks {
        NONE,
        ACTIVE,
        FAILED
    }

    enum SyncStatus {
        IMPORTING,
        OK,
        FAILED
    }

    /** A product the last full sync couldn't import, and why (a validation message). */
    record SyncError(String externalId, String title, String error) {}

    /**
     * @param id null until the first connect (rows from before S-35 only held the fake's label)
     * @param available whether the platform's app is configured (not stored)
     * @param lastSyncCount listings whose price or stock changed in the last full sync
     */
    @With
    record Connection(
            String merchantId,
            CommerceProvider provider,
            @Nullable String id,
            boolean connected,
            boolean available,
            boolean needsReconnect,
            @Nullable String externalAccountId,
            @Nullable String accountLabel,
            Set<String> scopes,
            @Nullable Instant connectedAt,
            @Nullable Instant lastSyncAt,
            @Nullable Integer lastSyncCount,
            int createdCount,
            int hiddenCount,
            List<SyncError> errors,
            @Nullable SyncStatus syncStatus,
            Webhooks webhooks,
            @Nullable Instant lastPolledAt,
            @Nullable String lastError) {

        public Connection {
            scopes = Set.copyOf(scopes);
            errors = List.copyOf(errors);
        }

        public static Connection none(String merchantId, CommerceProvider provider, boolean available) {
            return new Connection(
                    merchantId,
                    provider,
                    null,
                    false,
                    available,
                    false,
                    null,
                    null,
                    Set.of(),
                    null,
                    null,
                    null,
                    0,
                    0,
                    List.of(),
                    null,
                    Webhooks.NONE,
                    null,
                    null);
        }

        public String requiredId() {
            if (id == null) {
                throw new IllegalStateException("integration without id");
            }
            return id;
        }
    }

    /** Result of one full sync. */
    record SyncResult(int created, int updated, int hidden, List<SyncError> errors, Instant at) {}

    /** A pending OAuth consent: the state (stored hashed) belongs to this member, business and platform. */
    record OAuthRequest(
            String stateHash,
            String merchantId,
            String userId,
            CommerceProvider provider,
            @Nullable String shop,
            Instant createdAt,
            Instant expiresAt) {}

    List<Connection> all(String merchantId);

    Optional<Connection> find(String merchantId, CommerceProvider provider);

    Optional<Connection> byId(String id);

    /** Integrations (any status) of one platform account; a shop can be connected to more than one business. */
    List<Connection> byAccount(CommerceProvider provider, String externalAccountId);

    /** Stores a new or renewed grant; the connection is {@code connected}, {@code ok}, sync status importing. */
    void saveGrant(Connection connection, Sealed credentials);

    Optional<Sealed> credentials(String id);

    void replaceCredentials(String id, Sealed credentials);

    /** Tokens destroyed, status {@code disconnected}; the id and counters stay for history. */
    void disconnect(String id, Instant at);

    void markReconnect(String id, String error, Instant at);

    void markSyncing(String id);

    void recordSync(String id, SyncResult result);

    void recordFailure(String id, String error, Instant at);

    void setWebhooks(String id, Webhooks state);

    /**
     * Connected, healthy integrations to read now: without webhooks when not read since {@code pollBefore}, with
     * webhooks when not read since {@code reconcileBefore}.
     */
    List<String> due(Instant pollBefore, Instant reconcileBefore, int limit);

    /**
     * Claims a scheduled read: sets {@code last_polled_at} to {@code now} only while the integration is still due (same
     * rule as {@link #due}), so one replica wins when several run the job.
     */
    boolean claim(String id, Instant pollBefore, Instant reconcileBefore, Instant now);

    // ── OAuth requests ─────────────────────────────────────────────────────────────────────────────────────────────

    void saveRequest(OAuthRequest request);

    /** Deletes and returns the request (single use, even when refused); empty when unknown or expired. */
    Optional<OAuthRequest> takeRequest(String stateHash, Instant now);

    // ── webhooks ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** Records a delivery; false when it was seen before (a retry or a replay). */
    boolean firstDelivery(CommerceProvider provider, String deliveryId, Instant at);

    /** Expired OAuth requests and dedupe rows older than {@code receiptsBefore}; returns rows removed. */
    int purge(Instant now, Instant receiptsBefore);
}
