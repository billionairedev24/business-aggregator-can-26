package ca.northline.catalogue.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.Prompts;
import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.shared.RuleViolation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link DraftListingCopy} over the AI port (prompt {@code listing-copy}, standard model). */
@Service
@RequiredArgsConstructor
public class ListingCopyService implements DraftListingCopy {

    public static final String NOTHING_TO_GO_ON = "Add a name or a category first.";

    private final AiCompletions ai;
    private final Prompts prompts;
    private final CategoryCatalog categories;
    private final JsonMapper json;

    @Override
    public Draft draft(String merchantId, String userId, Facts facts) {
        var category = facts.categoryId() == null
                ? null
                : categories.profile(facts.categoryId()).map(c -> c.name()).orElse(null);
        if (blank(facts.name()) && category == null) {
            throw RuleViolation.of("name", "required", NOTHING_TO_GO_ON);
        }
        var known = new LinkedHashMap<String, Object>();
        known.put("kind", facts.kind().code());
        put(known, "name", facts.name());
        put(known, "category", category);
        put(known, "brand", facts.brand());
        if (!facts.attributes().isEmpty()) {
            known.put("attributes", facts.attributes());
        }
        put(known, "whatsIncluded", facts.included());
        if (facts.durationMin() != null) {
            known.put("durationMinutes", facts.durationMin());
        }
        put(known, "notesFromTheBusiness", facts.notes());
        var prompt = prompts.get("listing-copy");
        var system = prompt.render(Map.of("titleMax", ListingMessages.TITLE_MAX, "bulletsMax", ListingMessages.BULLETS_MAX));
        var answer = ai.complete(Request.of(
                                AiFeature.LISTING_COPY,
                                Caller.member(userId, merchantId),
                                prompt,
                                system,
                                "Listing facts: " + json.writeValueAsString(known))
                        .asJson()
                        .withMaxTokens(900));
        var node = answer.json().orElseThrow(() -> new IllegalStateException("The model's draft wasn't JSON."));
        return new Draft(copy(node.path("en")), copy(node.path("fr")), true, answer.model(), prompt.id());
    }

    /** Clamped to the listing rules, so a draft never fails the editor's own validation. */
    static Copy copy(JsonNode n) {
        var bullets = new ArrayList<String>();
        for (var b : n.path("bullets")) {
            if (b.isString() && !b.asString().isBlank() && bullets.size() < ListingMessages.BULLETS_MAX) {
                bullets.add(cut(b.asString().strip(), ListingMessages.BULLET_MAX));
            }
        }
        return new Copy(
                cut(n.path("title").asString("").strip(), ListingMessages.TITLE_MAX),
                cut(n.path("description").asString("").strip(), ListingMessages.DESCRIPTION_MAX),
                List.copyOf(bullets));
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).strip();
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private static void put(Map<String, Object> m, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            m.put(key, value.strip());
        }
    }
}
