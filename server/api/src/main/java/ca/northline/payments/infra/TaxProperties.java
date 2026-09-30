package ca.northline.payments.infra;

import ca.northline.payments.api.EscrowKind;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.tax.*} — sales tax (S-21).
 *
 * @param provider {@code local} (fixed Canadian rates, nothing leaves the process; refused under staging/prod) or
 *     {@code stripe} (Stripe Tax on the platform account; needs {@code STRIPE_SECRET_KEY}) — {@code TAX_PROVIDER}
 * @param serviceTaxCode Stripe product tax code for services ({@code TAX_CODE_SERVICE})
 * @param goodsTaxCode for goods ({@code TAX_CODE_GOODS})
 * @param foodTaxCode for prepared food ({@code TAX_CODE_FOOD})
 */
@ConfigurationProperties("northline.tax")
record TaxProperties(
        @DefaultValue("local") Provider provider,
        @DefaultValue("txcd_20030000") String serviceTaxCode,
        @DefaultValue("txcd_99999999") String goodsTaxCode,
        @DefaultValue("txcd_40060003") String foodTaxCode) {

    enum Provider {
        LOCAL,
        STRIPE
    }

    String taxCode(EscrowKind kind) {
        return switch (kind) {
            case SERVICE -> serviceTaxCode;
            case GOODS -> goodsTaxCode;
            case FOOD -> foodTaxCode;
        };
    }
}
