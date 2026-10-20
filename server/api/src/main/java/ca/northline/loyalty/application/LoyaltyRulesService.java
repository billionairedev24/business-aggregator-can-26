package ca.northline.loyalty.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.loyalty.application.LoyaltyUseCases.ManageLoyaltyRules;
import ca.northline.region.api.LoyaltyRules;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ManageLoyaltyRules}: the console's finance screen edits the region's earning rules (region data; the region
 * module writes them), audit-logged with the before and after. A province's or market's own row overrides its parent;
 * removing it falls back.
 */
@Service
@RequiredArgsConstructor
@Transactional
class LoyaltyRulesService implements ManageLoyaltyRules {

    static final String PPD = "Points per dollar between 0 and 100.";
    static final String KINDS = "Choose which sales earn: goods, food or services.";
    static final String EXPIRY = "Points expire after 1 to 60 months, or never.";
    static final String REFERRAL_POINTS = "Referral points between 0 and 100,000.";
    static final String REFERRAL_CAP = "Rewarded referrals a month between 0 and 100.";
    static final String REFERRAL_MIN = "A first order of $0 or more.";
    static final String REGION = "Choose a province or market of the region model.";
    static final String DEFAULT_STAYS = "The default rule can be changed, not removed.";

    private final LoyaltyRules rules;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<LoyaltyRules.Rule> rules() {
        return rules.all();
    }

    @Override
    public LoyaltyRules.Rule save(@Nullable String regionId, RuleChange c, String staffId, String role) {
        if (regionId != null && !rules.regionExists(regionId)) {
            throw RuleViolation.of("regionId", "allowed", REGION);
        }
        var before = rules.byRegion(regionId).orElse(null);
        var base = before != null ? before : rules.of(null, null);
        var ppd = c.pointsPerDollar() != null ? c.pointsPerDollar() : base.pointsPerDollar();
        var kinds = c.kinds() != null ? c.kinds() : base.kinds();
        var expiry = c.neverExpire() ? null : c.expiryMonths() != null ? c.expiryMonths() : base.expiryMonths();
        var refPoints = c.referralPoints() != null ? c.referralPoints() : base.referralPoints();
        var refCap = c.referralMonthlyCap() != null ? c.referralMonthlyCap() : base.referralMonthlyCap();
        var refMin = c.referralMinOrderCents() != null ? c.referralMinOrderCents() : base.referralMinOrderCents();
        var problems = new ArrayList<Violation>();
        if (ppd < 0 || ppd > 100) {
            problems.add(new Violation("pointsPerDollar", "range", PPD));
        }
        if (kinds.isEmpty() || !LoyaltyRules.KINDS.containsAll(kinds)) {
            problems.add(new Violation("kinds", "allowed", KINDS));
        }
        if (expiry != null && (expiry < 1 || expiry > 60)) {
            problems.add(new Violation("expiryMonths", "range", EXPIRY));
        }
        if (refPoints < 0 || refPoints > 100_000) {
            problems.add(new Violation("referralPoints", "range", REFERRAL_POINTS));
        }
        if (refCap < 0 || refCap > 100) {
            problems.add(new Violation("referralMonthlyCap", "range", REFERRAL_CAP));
        }
        if (refMin < 0) {
            problems.add(new Violation("referralMinOrderCents", "range", REFERRAL_MIN));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        var saved = rules.save(
                regionId,
                new LoyaltyRules.Change(ppd, kinds, expiry, refPoints, refCap, refMin),
                staffId,
                clock.instant());
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                role,
                "loyalty.rule_changed",
                "loyalty_rule",
                Objects.requireNonNullElse(regionId, "default"),
                before == null ? null : view(before),
                view(saved)));
        return saved;
    }

    @Override
    public void remove(String regionId, String staffId, String role) {
        var before = rules.byRegion(regionId).orElseThrow(() -> new NotFound("loyalty rule", regionId));
        if (before.regionId() == null) {
            throw RuleViolation.of("regionId", "allowed", DEFAULT_STAYS);
        }
        rules.remove(regionId);
        audit.record(new AuditTrail.Entry(
                null, staffId, role, "loyalty.rule_removed", "loyalty_rule", regionId, view(before), null));
    }

    private static Map<String, Object> view(LoyaltyRules.Rule r) {
        var m = new HashMap<String, Object>();
        m.put("pointsPerDollar", r.pointsPerDollar());
        m.put("kinds", String.join(",", r.kinds().stream().sorted().toList()));
        m.put("expiryMonths", r.expiryMonths() == null ? "never" : String.valueOf(r.expiryMonths()));
        m.put("referralPoints", r.referralPoints());
        m.put("referralMonthlyCap", r.referralMonthlyCap());
        m.put("referralMinOrderCents", r.referralMinOrderCents());
        return m;
    }
}
