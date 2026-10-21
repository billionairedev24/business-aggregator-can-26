package ca.northline.loyalty.application;

import ca.northline.payments.api.PlusBilling;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Northline Plus's plans and benefits as configuration ({@code northline.plus.*}, S-152; docs/runbooks/loyalty.md §
 * Plus). Since S-162 Northline bills the plans itself: each is a price and an RFC 5545 rule; how it is collected
 * (retries) is payments' ({@code northline.plus.billing.*}).
 *
 * @param monthlyPriceCents {@code PLUS_MONTHLY_PRICE_CENTS}: the monthly plan's price before tax
 * @param annualPriceCents {@code PLUS_ANNUAL_PRICE_CENTS}
 * @param monthlyRule {@code PLUS_MONTHLY_RRULE}: when the monthly plan renews ({@code FREQ=MONTHLY;INTERVAL=1})
 * @param annualRule {@code PLUS_ANNUAL_RRULE}: when the annual plan renews ({@code FREQ=YEARLY;INTERVAL=1})
 * @param trialDays {@code PLUS_TRIAL_DAYS}: a household's first membership starts free for this long (0 = none)
 * @param serviceFeeWaived {@code PLUS_SERVICE_FEE_WAIVED}: food orders' service fee is $0 for members
 * @param memberPrices {@code PLUS_MEMBER_PRICES}: shops' opted-in member prices apply
 * @param pointsMultiplierPct {@code PLUS_POINTS_MULTIPLIER_PCT}: members earn this % of the points (200 = 2×)
 * @param graceDays {@code PLUS_GRACE_DAYS}: a membership whose renewal is being retried keeps its benefits this many
 *     days past the period (at least a day past the last retry)
 */
@ConfigurationProperties("northline.plus")
public record PlusProperties(
        @DefaultValue("799") long monthlyPriceCents,
        @DefaultValue("6900") long annualPriceCents,
        @DefaultValue("FREQ=MONTHLY;INTERVAL=1") String monthlyRule,
        @DefaultValue("FREQ=YEARLY;INTERVAL=1") String annualRule,
        @DefaultValue("30") int trialDays,
        @DefaultValue("true") boolean serviceFeeWaived,
        @DefaultValue("true") boolean memberPrices,
        @DefaultValue("200") int pointsMultiplierPct,
        @DefaultValue("7") int graceDays) {

    public PlusProperties {
        if (monthlyPriceCents < 0
                || annualPriceCents < 0
                || trialDays < 0
                || trialDays > 90
                || pointsMultiplierPct < 100
                || pointsMultiplierPct > 500
                || graceDays < 0) {
            throw new IllegalArgumentException(
                    "northline.plus: prices ≥ 0, trialDays 0–90, pointsMultiplierPct 100–500");
        }
        // fails the start on a rule that isn't FREQ=MONTHLY|YEARLY[;INTERVAL=n]
        new PlusBilling.Plan("monthly", monthlyRule, monthlyPriceCents);
        new PlusBilling.Plan("annual", annualRule, annualPriceCents);
    }

    public long priceCents(String plan) {
        return "annual".equals(plan) ? annualPriceCents : monthlyPriceCents;
    }

    /** The plan as payments bills it. */
    public PlusBilling.Plan plan(String plan) {
        return "annual".equals(plan)
                ? new PlusBilling.Plan("annual", annualRule, annualPriceCents)
                : new PlusBilling.Plan("monthly", monthlyRule, monthlyPriceCents);
    }
}
