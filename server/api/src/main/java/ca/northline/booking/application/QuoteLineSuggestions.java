package ca.northline.booking.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.Prompts;
import ca.northline.booking.domain.QuoteEnums.LineKind;
import ca.northline.shared.NotFound;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** {@link DraftQuoteLines} over the AI port (prompt {@code quote-lines}). Reads only this merchant's request. */
@Service
@RequiredArgsConstructor
public class QuoteLineSuggestions implements DraftQuoteLines {

    static final int MAX_LINES = 6;
    static final int DESCRIPTION_MAX = 80;

    private final AiCompletions ai;
    private final Prompts prompts;
    private final QuoteRequests requests;
    private final JsonMapper json;

    @Override
    public Suggestion suggest(String merchantId, String requestId, String userId, @Nullable String notes, Locale locale) {
        var request = requests.find(merchantId, requestId)
                .filter(r -> !r.declined())
                .orElseThrow(() -> new NotFound("quote request", requestId));
        return suggest(merchantId, userId, new Job(request.title(), request.body(), request.area()), notes, locale);
    }

    @Override
    public Suggestion suggest(String merchantId, String userId, Job job, @Nullable String notes, Locale locale) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("title", job.title());
        if (job.description() != null) {
            facts.put("description", job.description());
        }
        if (job.area() != null) {
            facts.put("area", job.area());
        }
        if (notes != null && !notes.isBlank()) {
            facts.put("notesFromTheBusiness", notes.strip());
        }
        var prompt = prompts.get("quote-lines");
        var system = prompt.render(Map.of("language", locale.getLanguage().equals("fr") ? "Canadian French" : "English"));
        var answer = ai.complete(Request.of(
                                AiFeature.QUOTE_LINES,
                                Caller.member(userId, merchantId),
                                prompt,
                                system,
                                "Job request: " + json.writeValueAsString(facts))
                        .asJson()
                        .withMaxTokens(600));
        var node = answer.json().orElseThrow(() -> new IllegalStateException("The model's suggestion wasn't JSON."));
        var lines = new ArrayList<Line>();
        for (var l : node.path("lines")) {
            var kind = Arrays.stream(LineKind.values())
                    .filter(k -> k != LineKind.DISCOUNT && k.code().equals(l.path("kind").asString("")))
                    .findFirst();
            var description = l.path("description").asString("").strip();
            if (kind.isEmpty() || description.isEmpty() || lines.size() >= MAX_LINES) {
                continue;
            }
            var qty = l.path("qty").isNumber() ? l.path("qty").decimalValue() : BigDecimal.ONE;
            if (qty.signum() <= 0 || qty.compareTo(BigDecimal.valueOf(999)) > 0) {
                qty = BigDecimal.ONE;
            }
            lines.add(new Line(
                    kind.get(),
                    description.length() > DESCRIPTION_MAX ? description.substring(0, DESCRIPTION_MAX).strip() : description,
                    qty.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()));
        }
        var questions = new ArrayList<String>();
        node.path("questions").forEach(q -> {
            if (q.isString() && questions.size() < 3) {
                questions.add(q.asString());
            }
        });
        return new Suggestion(lines, questions, true, answer.model(), prompt.id());
    }
}
