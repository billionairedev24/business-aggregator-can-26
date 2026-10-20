package ca.northline.loyalty.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Northline Plus as configuration ({@code northline.plus.*}, S-152; docs/runbooks/loyalty.md § Plus). The billing
 * provider and Stripe Prices are payments' ({@code northline.plus.billing.*}).
 *
 * @param monthlyPriceCents {@code PLUS_MONTHLY_PRICE_CENTS}: shown in the account and charged by the local fake
 *     (Stripe charges its Price, which must say the same)
 * @param annualPriceCents {@code PLUS_ANNUAL_PRICE_CENTS}
 * @param trialDays {@code PLUS_TRIAL_DAYS}: a household's first membership starts free for this long (0 = none)
 * @param serviceFeeWaived {@code PLUS_SERVICE_FEE_WAIVED}: food orders' service fee is $0 for members
 * @param memberPrices {@code PLUS_MEMBER_PRICES}: shops' opted-in member prices apply
 * @param pointsMultiplierPct {@code PLUS_POINTS_MULTIPLIER_PCT}: members earn this % of the points (200 = 2×)
 * @param graceDays a membership whose payment Stripe is retrying keeps its benefits this many days past the period
 */
@ConfigurationProperties("northline.plus")
public record PlusProperties(
        @DefaultValue("799") long monthlyPriceCents,
        @DefaultValue("6900") long annualPriceCents,
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
    }

    public long priceCents(String plan) {
        return "annual".equals(plan) ? annualPriceCents : monthlyPriceCents;
    }
}
