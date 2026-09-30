package ca.northline.availability.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * What a Northline booking looks like in the member's own Google or Outlook calendar (S-32 write-back). The design
 * ("Northline bookings are written to your calendar with the customer's first name and address") is the ceiling: the
 * service, the customer's <em>first</em> name, the job address and the booking reference with a link back to the
 * Studio. Never the last name, phone, email, notes, access codes or vehicle.
 */
public record BookingEventText(String summary, @Nullable String location, String description) {

    public static final int MAX_SUMMARY = 200;

    public static BookingEventText of(
            String serviceTitle,
            @Nullable String customerFirstName,
            @Nullable String addressLine,
            @Nullable String ref,
            String studioLink) {
        var first = blankToNull(customerFirstName);
        var summary = first == null ? serviceTitle.strip() : serviceTitle.strip() + " · " + first;
        if (summary.length() > MAX_SUMMARY) {
            summary = summary.substring(0, MAX_SUMMARY);
        }
        var reference = blankToNull(ref);
        var description = (reference == null ? "Northline booking" : "Northline booking " + reference)
                + " · réservation Northline\n" + studioLink;
        return new BookingEventText(summary, blankToNull(addressLine), description);
    }

    /** Hash of everything written, so a change of time, service or address rewrites the event and nothing else does. */
    public String hash(Instant startsAt, Instant endsAt) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : new String[] {
                summary, location == null ? "" : location, description, startsAt.toString(), endsAt.toString()
            }) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
