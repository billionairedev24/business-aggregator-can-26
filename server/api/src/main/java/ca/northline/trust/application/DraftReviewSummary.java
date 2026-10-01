package ca.northline.trust.application;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-131: a summary of the business's verified reviews in English and French, as a draft on the Reviews screen. The
 * business may copy it into its page after editing; nothing is published automatically.
 */
public interface DraftReviewSummary {

    /** One review as the model sees it: no author name. */
    record ReviewText(
            int rating, @Nullable String job, @Nullable String text) {}

    record Version(String summary, List<String> themes) {}

    /** @param reviews how many reviews it was written from */
    record Summary(Version en, Version fr, int reviews, boolean aiAssisted, String model, String prompt) {}

    Summary summarize(String merchantId, String userId);

    /** The same from review texts (evals). */
    Summary summarize(String merchantId, String userId, List<ReviewText> reviews);
}
