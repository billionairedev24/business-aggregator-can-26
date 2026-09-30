package ca.northline.booking.application;

import ca.northline.shared.crypto.SecretSealer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A customer's private access instructions and contact number (S-55, {@code booking.access_notes}): the provider sees
 * them only for the two hours around the visit — from an hour before the start to an hour after (design 06: "shared
 * with the provider only for the two hours around the visit").
 */
@Component
@RequiredArgsConstructor
class AccessNotes {

    static final Duration AROUND = Duration.ofHours(1);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CustomerBookingStore store;
    private final SecretSealer sealer;

    static boolean visible(Instant startsAt, Instant now) {
        return !now.isBefore(startsAt.minus(AROUND)) && !now.isAfter(startsAt.plus(AROUND));
    }

    /**
     * @param legacy the {@code details.access} of bookings written before S-55 (seeded jobs), shown as before
     */
    @Nullable
    String forProvider(String bookingId, @Nullable String legacy, Instant startsAt, Instant now) {
        if (legacy != null) {
            return legacy;
        }
        if (!visible(startsAt, now)) {
            return null;
        }
        return store.accessNote(bookingId)
                .map(box -> {
                    Map<String, String> note = JSON.readValue(
                            sealer.open(box, CustomerBookingService.context(bookingId)), new TypeReference<>() {});
                    var parts = new ArrayList<String>();
                    if (note.get("access") != null) {
                        parts.add(note.get("access"));
                    }
                    if (note.get("phone") != null) {
                        parts.add("Contact: " + note.get("phone"));
                    }
                    return String.join(" · ", parts);
                })
                .orElse(null);
    }
}
