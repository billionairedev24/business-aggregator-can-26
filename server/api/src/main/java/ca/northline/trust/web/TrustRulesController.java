package ca.northline.trust.web;

import ca.northline.shared.ListResponse;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.trust.application.TrustRules;
import ca.northline.trust.application.TrustRules.Impact;
import ca.northline.trust.application.TrustRules.RuleView;
import ca.northline.trust.domain.TrustRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — trust &amp; safety rules as configuration (S-93, design 03 {@code trust}; admin and trust &amp;
 * safety open it, changing a rule needs {@code decide}).
 *
 * <pre>
 * GET /api/v1/console/trust/rules                                          {items: [RuleView]}
 * PUT /api/v1/console/trust/rules/{key} {value: {...}}                    RuleView
 * GET /api/v1/console/trust/rules/rating_floor/impact?rating=4.4[&amp;province=&amp;market=]   Impact
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/trust/rules")
@RequiredArgsConstructor
class TrustRulesController {

    static final String VALUE_REQUIRED = "Send the rule's value.";
    static final String RATING_RANGE = "Enter a rating from 1 to 5.";

    private final TrustRules rules;
    private final PlaceFilter places;

    record RuleRequest(@NotNull(message = VALUE_REQUIRED) Map<String, Object> value) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.TRUST)
    ListResponse<RuleView> rules() {
        return new ListResponse<>(rules.rules());
    }

    @PutMapping("/{key}")
    @RequiresConsole(value = ConsoleScreen.TRUST, actions = ConsoleAction.DECIDE)
    RuleView update(@PathVariable String key, @Valid @RequestBody RuleRequest body, CurrentStaff staff) {
        var rule = java.util.Arrays.stream(TrustRule.values())
                .filter(r -> r.code().equals(key))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("key", "option", TrustRules.RULE_UNKNOWN));
        return rules.update(rule, body.value(), staff.userId(), staff.roleCodes());
    }

    @GetMapping("/rating_floor/impact")
    @RequiresConsole(ConsoleScreen.TRUST)
    Impact impact(
            @RequestParam double rating,
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        if (rating < 1 || rating > 5) {
            throw RuleViolation.of("rating", "range", RATING_RANGE);
        }
        return rules.ratingFloorImpact(rating, places.resolve(province, market).scope());
    }
}
