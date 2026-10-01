package ca.northline.trust.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.messaging.api.OffPlatformPhrases;
import ca.northline.shared.MerchantScope;
import ca.northline.trust.api.ListingKeywordRules;
import ca.northline.trust.api.TrustConsequences;
import ca.northline.trust.domain.TrustRule;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TrustRules} over {@code trust.rules} (S-93): a rule never edited uses its default; an edit is validated,
 * stored and audit-logged ({@code trust.rule_changed}, platform-level, before/after). The keyword lists feed the
 * off-platform payment detector ({@link OffPlatformPhrases}, messaging) and listing vetting
 * ({@link ListingKeywordRules}, catalogue).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TrustRulesService implements TrustRules, OffPlatformPhrases, ListingKeywordRules, TrustConsequences {

    /** A business needs this many reviews in the window before the rating floor applies to it. */
    static final int MIN_REVIEWS = 5;

    private final TrustRuleStore store;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    public List<RuleView> rules() {
        var stored = store.all();
        return Arrays.stream(TrustRule.values())
                .map(r -> {
                    var s = stored.get(r.code());
                    return new RuleView(
                            r.code(),
                            s == null ? r.defaults() : s.value(),
                            r.defaults(),
                            r.fields(),
                            s == null ? null : s.updatedBy(),
                            s == null ? null : s.updatedAt());
                })
                .toList();
    }

    @Override
    @Transactional
    public RuleView update(TrustRule rule, Map<String, ?> value, String staffId, String role) {
        var clean = rule.validate(value);
        var before = value(rule);
        var now = clock.instant();
        store.save(rule.code(), clean, staffId, role, now);
        audit.record(new AuditTrail.Entry(
                null, staffId, role, "trust.rule_changed", "trust_rule", rule.code(), before, clean));
        return new RuleView(rule.code(), clean, rule.defaults(), rule.fields(), staffId, now);
    }

    @Override
    public Impact ratingFloorImpact(double rating, MerchantScope scope) {
        var floor = value(TrustRule.RATING_FLOOR);
        var days = ((Number) floor.getOrDefault("days", 90)).intValue();
        var counts = store.belowRating(rating, clock.instant().minus(Duration.ofDays(days)), MIN_REVIEWS, scope);
        return new Impact(rating, days, counts[0], counts[1]);
    }

    @Override
    public List<String> phrases() {
        return words(TrustRule.OFF_PLATFORM_PHRASES, "phrases");
    }

    @Override
    public Optional<String> restrictedTerm(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        return words(TrustRule.RESTRICTED_KEYWORDS, "words").stream()
                .filter(lower::contains)
                .findFirst();
    }

    // ── S-82: what the rules say should happen now ───────────────────────────────────────────────────────────

    @Override
    public RatingFloor ratingFloor() {
        var floor = value(TrustRule.RATING_FLOOR);
        return new RatingFloor(
                ((Number) floor.getOrDefault("rating", 4.2)).doubleValue(),
                ((Number) floor.getOrDefault("days", 90)).intValue(),
                ((Number) floor.getOrDefault("recoverDays", 30)).intValue());
    }

    @Override
    public List<Below> belowRatingFloor() {
        var floor = ratingFloor();
        return store.averages(clock.instant().minus(Duration.ofDays(floor.days())), MIN_REVIEWS).stream()
                .filter(b -> b.average() < floor.rating())
                .toList();
    }

    @Override
    public List<String> recovered(List<String> merchantIds) {
        var below = belowRatingFloor().stream().map(Below::merchantId).collect(java.util.stream.Collectors.toSet());
        return merchantIds.stream().filter(id -> !below.contains(id)).toList();
    }

    @Override
    public List<String> offPlatformAfterWarning(Duration within) {
        return store.warnedAgain(clock.instant().minus(within));
    }

    private Map<String, Object> value(TrustRule rule) {
        return store.find(rule.code()).map(TrustRuleStore.Stored::value).orElse(new LinkedHashMap<>(rule.defaults()));
    }

    private List<String> words(TrustRule rule, String field) {
        return value(rule).get(field) instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
    }
}
