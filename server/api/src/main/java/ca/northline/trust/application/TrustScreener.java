package ca.northline.trust.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.Prompts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * S-133: asks the model whether staff should look at one listing, review or message (prompt {@code trust-screen}, light
 * model, the system caller's budget). Only the item's own words and the automated checks' hints are sent: no names,
 * ids, contact details (the AI port redacts what slips through) or anything about other businesses. The verdict is a
 * suggestion: it can only put the item in the staff queue, with its explanation.
 */
@Service
@RequiredArgsConstructor
public class TrustScreener {

    /** The categories a flag may carry (the prompt's list); anything else the model says becomes {@code other}. */
    public static final Set<String> CATEGORIES = Set.of(
            "off_platform_payment",
            "abuse",
            "scam",
            "prohibited",
            "misleading",
            "fake_review",
            "personal_info",
            "spam");

    static final String JOB = "trust-screening";
    private static final int MAX_TEXT = 2000;
    private static final int MAX_EXPLANATION = 300;

    private final AiCompletions ai;
    private final Prompts prompts;

    /**
     * What is screened.
     *
     * @param kind {@code listing}, {@code review} or {@code message}
     * @param context one line about the item (its kind, the stars, who wrote to whom), no names
     * @param hints what the automated checks found ({@code price 93 % below the category median}), may be empty
     * @param text the item's own words
     */
    public record Item(String kind, String context, List<String> hints, String text) {}

    /** The model's suggestion: flag or not, the categories and why. {@code prompt} is {@code trust-screen@v1}. */
    public record Verdict(boolean flag, List<String> categories, String explanation, String model, String prompt) {}

    public Verdict screen(Item item) {
        var prompt = prompts.get("trust-screen");
        var input = new StringBuilder();
        input.append("Item: ").append(item.context()).append('\n');
        if (!item.hints().isEmpty()) {
            input.append("Automated checks: ")
                    .append(String.join("; ", item.hints()))
                    .append('\n');
        }
        var text = item.text().strip();
        input.append("Text:\n").append(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text);
        var answer = ai.complete(
                Request.of(AiFeature.TRUST_SCREEN, Caller.system(JOB), prompt, prompt.text(), input.toString())
                        .asJson()
                        .withMaxTokens(250));
        var json = answer.json().orElse(null);
        if (json == null) {
            // Unreadable answer: no flag, but say so (the screening record shows it; the item isn't lost to staff
            // because the deterministic detectors still raise their own flags).
            return new Verdict(false, List.of(), "The model's answer could not be read.", answer.model(), prompt.id());
        }
        var categories = new LinkedHashSet<String>();
        for (var c : json.path("categories")) {
            var code = c.asString("").strip().toLowerCase(Locale.ROOT);
            if (!code.isEmpty()) {
                categories.add(CATEGORIES.contains(code) ? code : "other");
            }
        }
        var flag = json.path("flag").asBoolean(false);
        if (flag && categories.isEmpty()) {
            categories.add("other");
        }
        var explanation = json.path("explanation").asString("").strip();
        if (flag && explanation.isEmpty()) {
            // Every flag explains itself: fall back to the categories.
            explanation = "Flagged for " + String.join(", ", categories).replace('_', ' ') + ".";
        }
        if (explanation.length() > MAX_EXPLANATION) {
            explanation = explanation.substring(0, MAX_EXPLANATION - 1) + "…";
        }
        return new Verdict(
                flag, flag ? new ArrayList<>(categories) : List.of(), explanation, answer.model(), prompt.id());
    }
}
