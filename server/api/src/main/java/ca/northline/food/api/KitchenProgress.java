package ca.northline.food.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The kitchen display's times for one food order (S-57 tracking): accepted with a ready-by, ready, handed off. */
public interface KitchenProgress {

    Optional<Ticket> of(String orderId);

    record Ticket(
            String stage,
            @Nullable Integer prepMin,
            @Nullable Instant acceptedAt,
            @Nullable Instant readyBy,
            @Nullable Instant readyAt,
            @Nullable Instant handedOffAt) {}
}
