package ca.northline.availability.application;

import ca.northline.availability.domain.CalendarProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port for {@code availability.calendar_links}. */
public interface CalendarLinkRepository {

    List<Link> of(String merchantId, String memberUserId);

    Optional<Link> find(String merchantId, String memberUserId, CalendarProvider provider);

    void upsert(String merchantId, Link link);

    void delete(String merchantId, String memberUserId, CalendarProvider provider);

    record Link(
            String id,
            String memberUserId,
            CalendarProvider provider,
            String accountLabel,
            String tokenRef,
            Instant connectedAt,
            @Nullable Instant lastSyncAt) {}
}
