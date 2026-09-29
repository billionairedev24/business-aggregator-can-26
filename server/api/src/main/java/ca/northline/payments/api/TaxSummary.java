package ca.northline.payments.api;

import java.util.List;

/**
 * Tax collected per jurisdiction for one quarter ({@code payments.tax_jurisdiction_totals}, the Stripe Tax read model),
 * for Stripe &amp; compliance › "Tax · Stripe Tax + marketplace facilitator". Added by the settings &amp; compliance
 * workstream; the finance workstream owns the sync that writes the table.
 */
public interface TaxSummary {

    /**
     * @param period {@code 2026-Q3}
     */
    List<JurisdictionTotal> totals(String merchantId, String period);

    /**
     * @param jurisdiction {@code ab_gst} | {@code bc_gst_pst} | {@code platform_fee_gst} | …
     * @param handling {@code remitted_by_northline} | {@code not_selling} | {@code charged_on_invoice}
     */
    record JurisdictionTotal(String jurisdiction, long collectedCents, String handling) {}
}
