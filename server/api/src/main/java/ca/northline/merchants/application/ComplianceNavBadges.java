package ca.northline.merchants.application;

import ca.northline.merchants.domain.ComplianceItem;
import ca.northline.merchants.domain.ComplianceRules;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.shared.NavBadgeContributor;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Sidebar badge of Stripe &amp; compliance: "1 due" (fr « 1 à faire ») when documents need the owner; kitchens show
 * "AHS" when a food-safety check (permit, handler certificates, inspection) is due or expires within 30 days
 * (design 02 navKitchen).
 */
@Component
@RequiredArgsConstructor
class ComplianceNavBadges implements NavBadgeContributor {

    private final ComplianceLedgerStore ledger;
    private final Clock clock;

    @Override
    public Map<String, String> badges(Context context) {
        var facts = ledger.facts(context.merchantId());
        if (facts.isEmpty()) {
            return Map.of();
        }
        var items = ledger.items(context.merchantId(), clock.instant());
        var foodSafety = facts.get().type() == MerchantType.KITCHEN
                && items.stream()
                        .anyMatch(i -> (i.due() || i.dueSoon()) && ComplianceRules.FOOD_SAFETY.contains(i.checkType()));
        if (foodSafety) {
            return Map.of("compliance", "AHS");
        }
        var due = items.stream().filter(ComplianceItem::due).count();
        return due == 0 ? Map.of() : Map.of("compliance", due + (context.french() ? " à faire" : " due"));
    }
}
