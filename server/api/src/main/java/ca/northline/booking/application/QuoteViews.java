package ca.northline.booking.application;

import ca.northline.booking.application.MediaCatalog.MediaInfo;
import ca.northline.booking.domain.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read models of the "Quote requests" column. */
public final class QuoteViews {
    private QuoteViews() {}

    /** A quote with its attachment names — everything the customer sees ("View as customer"). */
    public record QuoteView(Quote quote, List<MediaInfo> attachments) {
        public QuoteView {
            attachments = List.copyOf(attachments);
        }
    }

    /** A request card: the customer's ask, and this merchant's draft or latest sent quote. */
    public record QuoteRequestCard(
            String id,
            String ref,
            String title,
            @Nullable String customerName,
            @Nullable BigDecimal reliability,
            @Nullable String area,
            @Nullable String body,
            @Nullable Instant preferredAt,
            Instant createdAt,
            @Nullable Instant respondBy,
            @Nullable Instant expiresAt,
            @Nullable QuoteView quote) {}
}
