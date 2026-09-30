package ca.northline.food.application;

import ca.northline.food.application.PosImportViews.Diff;
import ca.northline.food.application.PosMenuSource.PosMenu;
import ca.northline.food.domain.PosProvider;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-36): {@code food.pos_connections}, {@code pos_oauth_requests}, {@code pos_links} (what each POS
 * entity became) and {@code pos_imports} (previews).
 */
public interface PosStore {

    enum Status {
        CONNECTED,
        RECONNECT,
        DISCONNECTED
    }

    enum LinkKind {
        SECTION,
        ITEM,
        GROUP,
        OPTION
    }

    record Connection(
            String id,
            String merchantId,
            PosProvider provider,
            Status status,
            @Nullable String accountId,
            @Nullable String accountLabel,
            @Nullable Instant connectedAt,
            @Nullable Instant lastImportAt) {}

    record OAuthRequest(
            String stateHash,
            String merchantId,
            String userId,
            PosProvider provider,
            @Nullable String menuId,
            Instant createdAt,
            Instant expiresAt) {}

    /**
     * @param scope the menu id for sections and items; empty for groups and options (kitchen-wide)
     */
    record Link(
            LinkKind kind,
            String scope,
            String externalId,
            String localId,
            String contentHash,
            @Nullable Instant removedAt) {}

    record ImportRow(
            String id,
            String merchantId,
            String menuId,
            PosProvider provider,
            String status,
            PosMenu menu,
            Diff diff,
            String createdBy,
            Instant createdAt,
            @Nullable Instant appliedAt) {}

    List<Connection> connections(String merchantId);

    Optional<Connection> connection(String merchantId, PosProvider provider);

    /**
     * Stores a new or renewed grant (status connected) under {@code id} (the existing connection's id when there is
     * one); {@code credentials} null for Toast (partner access).
     */
    Connection saveGrant(
            String id,
            String merchantId,
            PosProvider provider,
            String accountId,
            String accountLabel,
            @Nullable Sealed credentials,
            Instant at);

    Optional<Sealed> credentials(String connectionId);

    void replaceCredentials(String connectionId, Sealed credentials);

    void setStatus(String connectionId, Status status, Instant at);

    /** Tokens destroyed, status disconnected. */
    void disconnect(String connectionId, Instant at);

    void recordImport(String connectionId, Instant at);

    void saveRequest(OAuthRequest request);

    /** Deletes and returns the request (single use); empty when unknown or expired. */
    Optional<OAuthRequest> takeRequest(String stateHash, Instant now);

    /** Links of one kind and scope, by external id. */
    Map<String, Link> links(String merchantId, PosProvider provider, LinkKind kind, String scope);

    void saveLink(String merchantId, PosProvider provider, Link link, Instant at);

    void saveImport(ImportRow row);

    Optional<ImportRow> importRow(String merchantId, String importId);

    void markImport(String importId, String status, Instant at);
}
