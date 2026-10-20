package ca.northline.loyalty.web;

import ca.northline.loyalty.application.LoyaltyUseCases.ManageLoyaltyRules;
import ca.northline.region.api.LoyaltyRules;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The points earning rules on the console's finance screen (S-152; admin and finance read, {@code promotions} changes:
 * points are money). {@code {region}} is a province or market id of the region model, or {@code default}.
 *
 * <pre>
 * GET    /api/v1/console/loyalty/rules             {items: [Rule], regions: [RegionChoice]} — the default first
 * PUT    /api/v1/console/loyalty/rules/{region}    {pointsPerDollar?, kinds?, expiryMonths?, neverExpire?, referralPoints?,
 *                                                  referralMonthlyCap?, referralMinOrderCents?} → Rule
 * DELETE /api/v1/console/loyalty/rules/{region}    a province's or market's own row (falls back to its parent) → 204
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/loyalty/rules")
@RequiredArgsConstructor
class ConsoleLoyaltyController {

    private final ManageLoyaltyRules rules;

    record RuleRequest(
            @Nullable Integer pointsPerDollar,
            @Nullable Set<String> kinds,
            @Nullable Integer expiryMonths,
            @Nullable Boolean neverExpire,
            @Nullable Integer referralPoints,
            @Nullable Integer referralMonthlyCap,
            @Nullable Long referralMinOrderCents) {}

    /** @param regions the provinces and markets a rule can belong to */
    record RulesView(List<LoyaltyRules.Rule> items, List<LoyaltyRules.RegionChoice> regions) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.FINANCE)
    RulesView list() {
        return new RulesView(rules.rules(), rules.regions());
    }

    @PutMapping("/{region}")
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.PROMOTIONS)
    LoyaltyRules.Rule save(@PathVariable String region, @RequestBody RuleRequest body, CurrentStaff staff) {
        return rules.save(
                "default".equals(region) ? null : region,
                new ManageLoyaltyRules.RuleChange(
                        body.pointsPerDollar(),
                        body.kinds(),
                        body.expiryMonths(),
                        Boolean.TRUE.equals(body.neverExpire()),
                        body.referralPoints(),
                        body.referralMonthlyCap(),
                        body.referralMinOrderCents()),
                staff.userId(),
                staff.roleCodes());
    }

    @DeleteMapping("/{region}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.PROMOTIONS)
    void remove(@PathVariable String region, CurrentStaff staff) {
        rules.remove(region, staff.userId(), staff.roleCodes());
    }
}
