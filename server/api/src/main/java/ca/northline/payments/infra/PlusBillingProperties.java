package ca.northline.payments.infra;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.plus.billing.*} (S-152, docs/runbooks/stripe.md § Plus).
 *
 * @param provider {@code PLUS_BILLING_PROVIDER}: {@code local} (the fake; refused under staging and prod) or
 *     {@code stripe} (Stripe Billing with {@code STRIPE_SECRET_KEY})
 * @param monthlyPrice {@code STRIPE_PLUS_MONTHLY_PRICE}: the monthly plan's recurring Price ({@code price_…})
 * @param annualPrice {@code STRIPE_PLUS_ANNUAL_PRICE}: the annual plan's recurring Price
 * @param automaticTax {@code PLUS_AUTOMATIC_TAX}: Stripe Tax on Plus invoices (needs a customer address at Stripe)
 */
@ConfigurationProperties("northline.plus.billing")
record PlusBillingProperties(
        @DefaultValue("local") Provider provider,
        @Nullable String monthlyPrice,
        @Nullable String annualPrice,
        @DefaultValue("false") boolean automaticTax) {

    enum Provider {
        LOCAL,
        STRIPE
    }

    String price(String plan) {
        var price = "annual".equals(plan) ? annualPrice : monthlyPrice;
        if (price == null || price.isBlank()) {
            throw new IllegalStateException("PLUS_BILLING_PROVIDER=stripe needs STRIPE_PLUS_"
                    + ("annual".equals(plan) ? "ANNUAL" : "MONTHLY") + "_PRICE (docs/runbooks/stripe.md § Plus).");
        }
        return price;
    }
}
