package ca.northline.messaging.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.Prompts;
import ca.northline.shared.RuleViolation;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** {@link TriageHelpRequest} over the AI port (prompt {@code help-triage}, light model). Only the report text is sent. */
@Service
@RequiredArgsConstructor
public class HelpTriage implements TriageHelpRequest {

    public static final String TOO_SHORT = "Tell us a little more (at least 10 characters).";
    public static final String TOO_LONG = "Keep it under 2,000 characters.";

    private final AiCompletions ai;
    private final Prompts prompts;

    @Override
    public Triage triage(String customerId, String text, @Nullable String refType) {
        var report = text.strip();
        if (report.length() < 10) {
            throw RuleViolation.of("text", "length", TOO_SHORT);
        }
        if (report.length() > 2000) {
            throw RuleViolation.of("text", "length", TOO_LONG);
        }
        var prompt = prompts.get("help-triage");
        var context =
                refType == null ? "" : "It is about an " + ("order".equals(refType) ? "order" : "booking") + ".\n";
        var answer = ai.complete(Request.of(
                        AiFeature.HELP_TRIAGE,
                        Caller.person(customerId),
                        prompt,
                        prompt.text(),
                        context + "Report: " + report)
                .asJson()
                .withMaxTokens(250));
        var n = answer.json().orElse(null);
        var code = n == null ? "" : n.path("category").asString("");
        var category = Arrays.stream(Category.values())
                .filter(c -> c.code().equals(code))
                .findFirst()
                .orElse(Category.OTHER);
        var summary = n == null ? "" : n.path("summary").asString("").strip();
        return new Triage(
                category,
                TriageHelpRequest.route(category),
                category == Category.SAFETY || (n != null && n.path("urgent").asBoolean(false)),
                summary.length() > 300 ? summary.substring(0, 300) : summary,
                true,
                answer.model());
    }
}
