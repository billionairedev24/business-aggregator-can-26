package ca.northline.trust.application;

import ca.northline.ai.api.AssistantTool;
import ca.northline.shared.security.MerchantPermission;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S-130: the Studio assistant's reviews tool, over the Reviews screen's {@link BrowseReviews}. Reviews carry the
 * rating, the job, the text (trimmed), the date and whether the business replied — not the author's name.
 */
final class TrustAssistantTools {
    private TrustAssistantTools() {}

    @Component
    @RequiredArgsConstructor
    static final class ReviewsTool implements AssistantTool {
        private final BrowseReviews reviews;

        @Override
        public String name() {
            return "reviews_summary";
        }

        @Override
        public String description() {
            return "Verified reviews: average rating, count, star distribution, praise tags, and the latest reviews"
                    + " (rating, job, text, date, replied or not).";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of(
                    "latest",
                    Map.of("type", "integer", "minimum", 0, "maximum", 20, "description", "Reviews (default 10)"));
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.VIEW;
        }

        @Override
        public String screen() {
            return "reviews";
        }

        @Override
        public Result run(Call call) {
            var summary = reviews.summary(call.merchantId());
            var latest = reviews.reviews(call.merchantId(), call.integer("latest", 10, 0, 20), 0).items().stream()
                    .map(r -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("rating", r.rating());
                        m.put("job", r.jobLabel());
                        m.put(
                                "text",
                                r.text() == null
                                        ? null
                                        : r.text().length() > 400 ? r.text().substring(0, 400) + "…" : r.text());
                        m.put(
                                "date",
                                r.createdAt().atZone(call.zone()).toLocalDate().toString());
                        m.put("replied", r.reply() != null);
                        return m;
                    })
                    .toList();
            return new Result(
                    Map.of(
                            "average", summary.average(),
                            "count", summary.count(),
                            "distribution", summary.distribution(),
                            "praise", summary.praise(),
                            "latest", latest),
                    "reviews → " + summary.average() + " ★ (" + summary.count() + ")");
        }
    }
}
