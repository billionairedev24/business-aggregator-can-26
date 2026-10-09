package ca.northline.account.application;

import ca.northline.trust.api.ReviewPosting.Posted;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Reviewing what a customer booked or ordered (mobile gaps part 2, design 01 C11 / B9, design 06): the businesses of a
 * completed booking or a delivered order, once each, for 30 days after; the review can be changed for 24 hours.
 */
public final class Reviews {
    private Reviews() {}

    /**
     * @param status open (can be reviewed), reviewed, not_yet (not done yet) or closed (the 30 days passed, or it
     *     wasn't paid)
     * @param review the customer's review of this business, when written
     */
    public record Target(
            String merchantId,
            String merchantName,
            @Nullable String slug,
            String jobLabel,
            String status,
            @Nullable Posted review) {}

    /**
     * @param kind {@code booking} | {@code order} | {@code food}
     * @param reviewBy the end of the 30 days, once done
     */
    public record Context(
            String kind,
            String id,
            @Nullable String ref,
            @Nullable Instant reviewBy,
            List<Target> targets) {
        public Context {
            targets = List.copyOf(targets);
        }
    }

    public record Write(
            String kind,
            String id,
            @Nullable String merchantId,
            int rating,
            List<String> tags,
            @Nullable String text) {
        public Write {
            tags = List.copyOf(tags);
        }
    }

    public interface ReviewWhatYouBought {

        Context context(String customerId, String kind, String id);

        Posted post(String customerId, Write write, Locale locale);

        Posted edit(String customerId, String reviewId, int rating, List<String> tags, @Nullable String text);
    }
}
