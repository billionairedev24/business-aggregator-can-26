package ca.northline.trust.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.Prompts;
import ca.northline.shared.Conflict;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link DraftReviewSummary} over the AI port (prompt {@code review-summary}, light model). */
@Service
@RequiredArgsConstructor
public class ReviewSummaryService implements DraftReviewSummary {

    /** The latest reviews read: enough for the themes without sending the whole history. */
    static final int REVIEWS = 40;

    static final int MIN_REVIEWS = 3;
    static final int TEXT_MAX = 500;

    private final AiCompletions ai;
    private final Prompts prompts;
    private final BrowseReviews reviews;
    private final JsonMapper json;

    @Override
    public Summary summarize(String merchantId, String userId) {
        var texts = reviews.reviews(merchantId, REVIEWS, 0).items().stream()
                .map(r -> new ReviewText(r.rating(), r.jobLabel(), r.text()))
                .toList();
        return summarize(merchantId, userId, texts);
    }

    @Override
    public Summary summarize(String merchantId, String userId, List<ReviewText> texts) {
        if (texts.size() < MIN_REVIEWS) {
            throw new Conflict("too_few_reviews", "A summary needs at least 3 reviews.");
        }
        var input = texts.stream()
                .map(r -> new ReviewText(
                        r.rating(),
                        r.job(),
                        r.text() == null || r.text().length() <= TEXT_MAX
                                ? r.text()
                                : r.text().substring(0, TEXT_MAX)))
                .toList();
        var prompt = prompts.get("review-summary");
        var answer = ai.complete(Request.of(
                        AiFeature.REVIEW_SUMMARY,
                        Caller.member(userId, merchantId),
                        prompt,
                        prompt.text(),
                        "Verified reviews (newest first): " + json.writeValueAsString(input))
                .asJson()
                .withMaxTokens(700));
        var node = answer.json().orElseThrow(() -> new IllegalStateException("The model's summary wasn't JSON."));
        return new Summary(
                version(node.path("en")), version(node.path("fr")), texts.size(), true, answer.model(), prompt.id());
    }

    static Version version(JsonNode n) {
        var themes = new ArrayList<String>();
        n.path("themes").forEach(t -> {
            if (t.isString() && themes.size() < 4) {
                themes.add(t.asString().strip());
            }
        });
        var summary = n.path("summary").asString("").strip();
        return new Version(summary.length() > 800 ? summary.substring(0, 800) : summary, List.copyOf(themes));
    }
}
