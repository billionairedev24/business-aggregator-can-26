package ca.northline.payments.api;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What payments needs to know about a business: tier, own take rate, province. The merchants module implements it
 * over {@code merchants.api.MerchantDirectory} (S-37). It is declared here, not called on merchants.api directly,
 * because merchants already depends on payments (Connect, payouts, tax summary), so a call the other way would be a
 * module cycle.
 */
public interface MerchantBillingFacts {

    Optional<Billing> billing(String merchantId);

    /** S-41: who a tax statement is for — the legal entity, its trading name and GST/HST number. */
    Optional<StatementParty> statementParty(String merchantId);

    /** @param gstNumber the GST/HST registration ({@code 123456789 RT0001}), or null when the business has none */
    record StatementParty(String legalName, String displayName, @Nullable String gstNumber) {}

    /**
     * @param tier {@code registered|trusted|master}
     * @param takeRateBps the business's own rate, or null for the tier's default
     * @param province two-letter code, or null when not set
     */
    record Billing(
            @Nullable String tier,
            @Nullable Integer takeRateBps,
            @Nullable String province) {}
}
