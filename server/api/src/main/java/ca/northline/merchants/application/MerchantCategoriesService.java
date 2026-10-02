package ca.northline.merchants.application;

import ca.northline.merchants.api.MerchantCategories;
import ca.northline.merchants.api.MerchantCategoriesChanged;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link MerchantCategories} and {@link CategoryLimitLookup} (S-94). */
@Service
@RequiredArgsConstructor
@Transactional
class MerchantCategoriesService implements MerchantCategories, CategoryLimitLookup {

    /** fr-CA in docs/spec/validation-messages.fr-CA.tsv. */
    static final String LIMIT_RANGE = "Enter a limit from 1 to 50.";

    static final String TYPE = "Choose provider, seller, both or kitchen.";

    private final MerchantCategoryStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Limit> limits() {
        return store.limits();
    }

    @Override
    @Transactional(readOnly = true)
    public int limit(MerchantType type) {
        return store.limit(type.code()).map(Limit::max).orElse(type.categoryLimit());
    }

    @Override
    public Limit setLimit(String merchantType, int max, String actorId) {
        if (java.util.Arrays.stream(MerchantType.values())
                .noneMatch(t -> t.code().equals(merchantType))) {
            throw RuleViolation.of("merchantType", "unknown", TYPE);
        }
        if (max < 1 || max > 50) {
            throw RuleViolation.of("max", "range", LIMIT_RANGE);
        }
        store.setLimit(merchantType, max, actorId, clock.instant());
        return store.limit(merchantType).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Usage> usage() {
        return store.usage();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions() {
        return store.suggestions().entrySet().stream()
                .map(e -> new Suggestion(
                        e.getKey(), e.getValue().getKey(), e.getValue().getValue()))
                .sorted(Comparator.comparingInt(
                                (Suggestion s) -> -s.businesses().size())
                        .thenComparing(Suggestion::name))
                .toList();
    }

    @Override
    public Resolution resolve(String suggestionId, String categoryId, boolean regulated, String actorId) {
        var holders = store.holders(suggestionId);
        if (holders.isEmpty()) {
            throw new NotFound("suggestion", suggestionId);
        }
        var already = store.holding(holders, categoryId);
        var moved = new ArrayList<String>();
        var now = clock.instant();
        for (var merchantId : holders) {
            if (already.contains(merchantId)) {
                store.drop(merchantId, suggestionId);
            } else {
                store.move(merchantId, suggestionId, categoryId, regulated ? "requested" : "approved");
                moved.add(merchantId);
            }
            events.publishEvent(new MerchantCategoriesChanged(Ids.next(), now, merchantId, actorId, categoryId));
        }
        return new Resolution(suggestionId, categoryId, moved, already);
    }
}
