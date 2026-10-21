package ca.northline.catalogue.application;

import ca.northline.catalogue.api.InstantBook.State;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-156): {@code catalogue.instant_book_settings}. */
public interface InstantBookStore {

    /** The row locked for an update (none = defaults). */
    Optional<State> lock(String merchantId);

    Optional<State> find(String merchantId);

    void save(
            String merchantId,
            boolean enabled,
            @Nullable Instant pausedAt,
            @Nullable String cause,
            @Nullable String reason,
            String actorId,
            Instant at);

    List<String> pausedFor(String cause);
}
