package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port for {@code catalogue.integrations}. */
public interface IntegrationRepository {

    record Connection(
            CommerceProvider provider,
            boolean connected,
            @Nullable String accountLabel,
            @Nullable Instant connectedAt,
            @Nullable Instant lastSyncAt,
            @Nullable Integer lastSyncCount) {}

    List<Connection> all(String merchantId);

    Optional<Connection> find(String merchantId, CommerceProvider provider);

    void upsert(String merchantId, Connection connection);
}
