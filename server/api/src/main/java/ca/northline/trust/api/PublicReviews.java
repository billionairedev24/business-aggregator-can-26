package ca.northline.trust.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A business's verified reviews as its public page shows them (S-54, storefront section "Reviews & reliability"):
 * newest first, what the customer wrote, the author's display form ("Dana K.", stored at review time), the kind of
 * transaction and the business's public reply. Nothing that identifies the author further.
 */
public interface PublicReviews {

    /**
     * @param refType {@code booking} | {@code order} ("verified booking" / "verified order")
     */
    record PublicReview(
            String id,
            int rating,
            @Nullable String text,
            @Nullable String author,
            @Nullable String jobLabel,
            String refType,
            Instant createdAt,
            @Nullable String reply) {}

    /** @param nextOffset the offset of the next page, null on the last one */
    record ReviewPage(List<PublicReview> items, @Nullable Integer nextOffset) {
        public ReviewPage {
            items = List.copyOf(items);
        }
    }

    ReviewPage newest(String merchantId, int limit, int offset);
}
